package app

import kotlinx.serialization.Serializable

/** 求解状态：等价 / 反例 / 资源上限 / 求解未知 / 语义未建模，严格区分。 */
enum class Status { EQUIVALENT, COUNTEREXAMPLE, RESOURCE_LIMIT, UNKNOWN, UNMODELED }

@Serializable
data class ValueRange(val min: String, val max: String, val sawNaN: Boolean = false)

@Serializable
data class CounterExample(
    val reason: String,
    val inputs: Map<String, String>,
    val pathSrc: List<String>,
    val pathTgt: List<String>,
    val condSrc: List<String>,
    val condTgt: List<String>,
    val traceSrc: List<String>,
    val traceTgt: List<String>,
    val srcResult: String,
    val tgtResult: String
)

@Serializable
data class VerifyResult(
    val status: String,
    val checkedInputs: Long,
    val totalInputs: Long,
    val srcRanges: Map<String, ValueRange> = emptyMap(),
    val tgtRanges: Map<String, ValueRange> = emptyMap(),
    val srcPaths: List<String> = emptyList(),
    val tgtPaths: List<String> = emptyList(),
    val ce: CounterExample? = null,
    val minimized: CounterExample? = null,
    val note: String = ""
)

@Serializable
data class RunConfig(
    val width: Int = 8,
    val overflowMode: String = "STRICT",
    val floatMode: String = "IEEE",
    val unrollLimit: Int = 8,
    val policy: String = "REFINEMENT",
    val budget: Long = 500_000
) {
    fun evalConfig() = EvalConfig(
        width = width,
        overflowMode = OverflowMode.valueOf(overflowMode),
        floatMode = FloatMode.valueOf(floatMode),
        unrollLimit = unrollLimit
    )

    fun policy() = Policy.valueOf(policy)
}

val FLOAT_SAMPLES = listOf(0.0, -0.0, 1.5, -2.25, 0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

private class RangeAcc {
    var min: Long? = null
    var max: Long? = null
    var fmin: Double? = null
    var fmax: Double? = null
    var sawNaN = false
    var isFloat = false

    fun add(v: Value) {
        when (v) {
            is Value.IntV -> {
                val s = v.signed
                min = minOf(min ?: s, s)
                max = maxOf(max ?: s, s)
            }
            is Value.FloatV -> {
                isFloat = true
                if (v.v.isNaN()) sawNaN = true
                else {
                    fmin = minOf(fmin ?: v.v, v.v)
                    fmax = maxOf(fmax ?: v.v, v.v)
                }
            }
            Value.Poison -> {}
        }
    }

    fun toRange(): ValueRange = if (isFloat) {
        ValueRange(fmin?.toString() ?: "-", fmax?.toString() ?: "-", sawNaN)
    } else {
        ValueRange(min?.toString() ?: "-", max?.toString() ?: "-")
    }
}

object Verifier {

    fun verify(src: Program, tgt: Program, cfg: RunConfig): VerifyResult {
        val evalCfg = cfg.evalConfig()
        // 未建模语义快速判定：任一侧含未建模指令则整体为 UNMODELED。
        try {
            probe(src, evalCfg)
            probe(tgt, evalCfg)
        } catch (e: UnmodeledException) {
            return VerifyResult(Status.UNMODELED.name, 0, 0, note = e.message ?: "unmodeled")
        }

        val intParams = src.intParams
        val floatParams = src.floatParams
        val perInt = 1L shl evalCfg.width.coerceAtMost(20)
        var total = 1L
        repeat(intParams.size) { total *= perInt }
        repeat(floatParams.size) { total *= FLOAT_SAMPLES.size }
        if (total > cfg.budget) {
            // 求解器在预算内无法判定：严格区别于 EQUIVALENT。
            return VerifyResult(Status.UNKNOWN.name, 0, total, note = "input space $total exceeds budget ${cfg.budget}")
        }

        val srcRanges = HashMap<String, RangeAcc>()
        val tgtRanges = HashMap<String, RangeAcc>()
        val srcPaths = LinkedHashSet<String>()
        val tgtPaths = LinkedHashSet<String>()
        var checked = 0L
        var resourceHit: String? = null
        var ce: CounterExample? = null

        val intValues = LongArray(intParams.size)
        val floatValues = DoubleArray(floatParams.size)

        fun inputs(): Inputs {
            val ints = HashMap<String, Long>()
            for (i in intParams.indices) ints[intParams[i]] = intValues[i]
            val floats = HashMap<String, Double>()
            for (i in floatParams.indices) floats[floatParams[i]] = floatValues[i]
            return Inputs(ints, floats)
        }

        fun visit(depth: Int) {
            if (ce != null) return
            if (depth < intParams.size) {
                var v = 0L
                while (v < perInt) {
                    intValues[depth] = v
                    visit(depth + 1)
                    if (ce != null) return
                    v++
                }
                return
            }
            val fdepth = depth - intParams.size
            if (fdepth < floatParams.size) {
                for (f in FLOAT_SAMPLES) {
                    floatValues[fdepth] = f
                    visit(depth + 1)
                    if (ce != null) return
                }
                return
            }
            val inp = inputs()
            val s = try {
                Evaluator(src, evalCfg, inp).run()
            } catch (e: ResourceLimitException) {
                resourceHit = "source: ${e.message}"
                return
            }
            val t = try {
                Evaluator(tgt, evalCfg, inp).run()
            } catch (e: ResourceLimitException) {
                resourceHit = "target: ${e.message}"
                return
            }
            checked++
            s.trace.forEach { (n, v) -> srcRanges.getOrPut(n) { RangeAcc() }.add(v) }
            t.trace.forEach { (n, v) -> tgtRanges.getOrPut(n) { RangeAcc() }.add(v) }
            srcPaths.add(pathString(s))
            tgtPaths.add(pathString(t))
            val verdict = compare(s, t, cfg.policy())
            if (verdict != null) {
                ce = buildCe(verdict, inp, s, t)
            }
        }
        visit(0)

        val status = when {
            ce != null -> Status.COUNTEREXAMPLE
            resourceHit != null -> Status.RESOURCE_LIMIT
            else -> Status.EQUIVALENT
        }
        return VerifyResult(
            status = status.name,
            checkedInputs = checked,
            totalInputs = total,
            srcRanges = srcRanges.mapValues { it.value.toRange() },
            tgtRanges = tgtRanges.mapValues { it.value.toRange() },
            srcPaths = srcPaths.toList(),
            tgtPaths = tgtPaths.toList(),
            ce = ce,
            note = resourceHit ?: ""
        )
    }

    private fun probe(prog: Program, cfg: EvalConfig) {
        // 用零输入试跑一遍，未建模指令在此暴露；资源/路径问题忽略。
        val inp = Inputs(prog.intParams.associateWith { 0L }, prog.floatParams.associateWith { 0.0 })
        try {
            Evaluator(prog, cfg, inp).run()
        } catch (e: UnmodeledException) {
            throw e
        } catch (_: ResourceLimitException) {
        } catch (_: Exception) {
        }
    }

    private fun pathString(o: EvalOutcome): String {
        val sb = StringBuilder()
        o.blocks.forEachIndexed { i, b ->
            if (i > 0) sb.append(" -> ")
            sb.append(b)
        }
        if (o.conditions.isNotEmpty()) sb.append("  [").append(o.conditions.joinToString(", ")).append("]")
        if (!o.defined) sb.append("  (UB: ").append(o.ubReason).append(")")
        return sb.toString()
    }

    /** 返回 null 表示该输入上两边一致（或按口径跳过）。 */
    private fun compare(s: EvalOutcome, t: EvalOutcome, policy: Policy): String? {
        return when (policy) {
            Policy.REFINEMENT -> {
                // 源未定义：目标可为任意值，跳过，绝不把任意值当作反例。
                if (!s.defined) null
                else if (!t.defined) "target-ub"
                else if (!valueEq(s.result, t.result)) "value-mismatch"
                else null
            }
            Policy.BOTH_DEFINED -> {
                if (!s.defined || !t.defined) null
                else if (!valueEq(s.result, t.result)) "value-mismatch"
                else null
            }
        }
    }

    private fun valueEq(a: Value?, b: Value?): Boolean = when {
        a is Value.IntV && b is Value.IntV -> a.masked == b.masked
        a is Value.FloatV && b is Value.FloatV ->
            (a.v.isNaN() && b.v.isNaN()) || a.v.toRawBits() == b.v.toRawBits()
        else -> false
    }

    private fun buildCe(reason: String, inp: Inputs, s: EvalOutcome, t: EvalOutcome): CounterExample =
        CounterExample(
            reason = reason,
            inputs = renderInputs(inp),
            pathSrc = s.blocks,
            pathTgt = t.blocks,
            condSrc = s.conditions,
            condTgt = t.conditions,
            traceSrc = s.trace.map { "${it.first} = ${renderValue(it.second)}" },
            traceTgt = t.trace.map { "${it.first} = ${renderValue(it.second)}" },
            srcResult = renderValue(s.result),
            tgtResult = renderValue(t.result)
        )

    private fun renderInputs(inp: Inputs): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        inp.ints.forEach { (k, v) -> m[k] = v.toString() }
        inp.floats.forEach { (k, v) -> m[k] = renderValue(Value.FloatV(v)).removePrefix("f ") }
        return m
    }

    /** 反例缩减：贪心地把输入向 0 / 更简单值收缩，仍触发同类反例。原例始终保留。 */
    fun minimize(src: Program, tgt: Program, cfg: RunConfig, ce: CounterExample): CounterExample {
        val evalCfg = cfg.evalConfig()
        val ints = HashMap<String, Long>()
        val floats = HashMap<String, Double>()
        for (p in src.intParams) ints[p] = ce.inputs[p]?.toLongOrNull() ?: 0L
        for (p in src.floatParams) floats[p] = parseSample(ce.inputs[p])

        fun stillCe(): Boolean {
            val inp = Inputs(ints.toMap(), floats.toMap())
            val s = try {
                Evaluator(src, evalCfg, inp).run()
            } catch (e: Exception) {
                return false
            }
            val t = try {
                Evaluator(tgt, evalCfg, inp).run()
            } catch (e: Exception) {
                return false
            }
            return compare(s, t, cfg.policy()) == ce.reason
        }

        fun popcount(v: Long) = java.lang.Long.bitCount(v)
        fun simpler(a: Long, b: Long) = popcount(a) < popcount(b) || (popcount(a) == popcount(b) && a < b)
        for (p in src.intParams) {
            val candidates = mutableListOf(0L, 1L)
            var cur = ints[p] ?: 0L
            while (cur != 0L) {
                cur = cur and (cur - 1) // 逐位清除最低位
                candidates.add(cur)
            }
            for (cand in candidates) {
                val old = ints[p]!!
                if (cand == old || !simpler(cand, old)) continue
                ints[p] = cand
                if (!stillCe()) ints[p] = old
            }
        }
        for (p in src.floatParams) {
            for (cand in listOf(0.0, 1.0, -0.0)) {
                val old = floats[p]!!
                if (old.toRawBits() == cand.toRawBits()) continue
                floats[p] = cand
                if (!stillCe()) floats[p] = old
            }
        }
        val inp = Inputs(ints.toMap(), floats.toMap())
        val s = Evaluator(src, evalCfg, inp).run()
        val t = Evaluator(tgt, evalCfg, inp).run()
        return buildCe(ce.reason + " (minimized)", inp, s, t)
    }

    private fun parseSample(text: String?): Double = when (text) {
        null -> 0.0
        "NaN" -> Double.NaN
        "+Inf" -> Double.POSITIVE_INFINITY
        "-Inf" -> Double.NEGATIVE_INFINITY
        else -> text.toDoubleOrNull() ?: 0.0
    }
}
