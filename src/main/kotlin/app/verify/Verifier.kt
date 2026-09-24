package app.verify

import app.ir.*
import kotlinx.serialization.Serializable

enum class FloatMode { STRICT, FINITE_ONLY }
enum class Refinement { TARGET_REFINES_SOURCE, STRICT_BOTH_DEFINED }

@Serializable
data class VerifyConfig(
    val bitWidth: Int = 8,
    val overflowMode: OverflowMode = OverflowMode.RESPECT,
    val floatMode: FloatMode = FloatMode.STRICT,
    val unrollLimit: Int = 64,
    val refinement: Refinement = Refinement.TARGET_REFINES_SOURCE,
    val evalBudget: Long = 200_000
)

enum class Status { PROVEN_EQUIVALENT, COUNTEREXAMPLE, RESOURCE_LIMIT, UNMODELED }

@Serializable
data class SideResult(
    val outcome: String,            // "defined" | "ub" | "unroll-exceeded"
    val value: String?,             // rendered result value when defined
    val reason: String?,            // UB reason when undefined
    val path: List<String>,         // path conditions / block trace
    val values: List<Pair<String, String>> // intermediate SSA values in execution order
)

@Serializable
data class CounterExample(
    val inputs: Map<String, String>,   // param -> raw encoding "i8:255" / "f32:7fc00000"
    val inputsDisplay: Map<String, String>,
    val kind: String,                  // value-mismatch | target-ub | source-ub | definedness-mismatch
    val source: SideResult,
    val target: SideResult,
    val minimized: Boolean = false
)

@Serializable
data class PairResult(
    val pairId: String,
    val status: Status,
    val explored: Long,
    val totalSpace: String,            // exact count or ">= <bound>" when saturated
    val exhaustive: Boolean,
    val note: String = "",
    val ce: CounterExample? = null,
    val srcRanges: Map<String, String> = emptyMap(),
    val dstRanges: Map<String, String> = emptyMap()
)

fun parseRawVal(s: String): Val = when {
    s.startsWith("i") -> {
        val w = s.substring(1, s.indexOf(':')).toInt()
        Val.IntV(w, s.substring(s.indexOf(':') + 1).toLong())
    }
    s.startsWith("f32:") -> Val.FloatV(s.substring(4).toLong(16).toInt().let { Float.fromBits(it) })
    else -> error("bad raw val: $s")
}

fun rawVal(v: Val): String = when (v) {
    is Val.IntV -> "i${v.width}:${v.masked}"
    is Val.FloatV -> "f32:${v.f.toRawBits().toLong().and(0xffffffff).toString(16)}"
    Val.Poison -> "poison"
}

fun valsEqual(a: Val, b: Val): Boolean = when {
    a is Val.IntV && b is Val.IntV -> a.width == b.width && a.masked == b.masked
    a is Val.FloatV && b is Val.FloatV ->
        (a.f.isNaN() && b.f.isNaN()) || a.f.toRawBits() == b.f.toRawBits()
    else -> false
}

class Verifier(private val config: VerifyConfig) {

    private val evaluator = Evaluator(config.overflowMode, config.unrollLimit)

    data class BuiltPair(val id: String, val src: Fragment, val dst: Fragment)

    fun verify(pair: BuiltPair): PairResult {
        if (pair.src.containsUnmodeled() || pair.dst.containsUnmodeled()) {
            return PairResult(pair.id, Status.UNMODELED, 0, "n/a", false,
                note = "fragment uses operations outside the modelled semantics")
        }
        val params = pair.src.params
        val domainSizes = params.map { domainSize(it.ty) }
        val total = domainSizes.fold(1L) { acc, d -> satMul(acc, d) }
        val exhaustive = total >= 0 && total <= config.evalBudget &&
            params.all { it.ty !is Ty.IntT || it.ty.width <= 20 }
        val domains = if (exhaustive) params.map { domainFor(it.ty) } else emptyList()

        val srcRanges = RangeCollector()
        val dstRanges = RangeCollector()
        var explored = 0L
        var unknown = false

        val iterator: Iterator<List<Val>> =
            if (exhaustive) cartesian(domains).iterator()
            else sampledInputs(params, domains, config.evalBudget).iterator()

        while (iterator.hasNext() && explored < config.evalBudget) {
            val inputVals = iterator.next()
            explored++
            val env = params.mapIndexed { i, p -> p.name to inputVals[i] }.toMap()
            val s = evaluator.run(pair.src, env)
            val d = evaluator.run(pair.dst, env)
            srcRanges.accept(pair.src, s)
            dstRanges.accept(pair.dst, d)
            if (s is Outcome.UnrollExceeded || d is Outcome.UnrollExceeded) unknown = true
            val ce = compare(env, s, d)
            if (ce != null) {
                return PairResult(pair.id, Status.COUNTEREXAMPLE, explored,
                    totalSpace(total), exhaustive, ce = ce,
                    srcRanges = srcRanges.render(), dstRanges = dstRanges.render())
            }
        }
        val status = if (exhaustive && !unknown) Status.PROVEN_EQUIVALENT else Status.RESOURCE_LIMIT
        val note = when {
            status == Status.PROVEN_EQUIVALENT -> "exhaustively checked $explored input(s) at i${config.bitWidth}"
            unknown -> "loop unroll limit ${config.unrollLimit} hit; result is unknown, not equivalent"
            else -> "input space too large; sampled $explored of ${totalSpace(total)} inputs; result is unknown, not equivalent"
        }
        return PairResult(pair.id, status, explored, totalSpace(total), exhaustive, note,
            srcRanges = srcRanges.render(), dstRanges = dstRanges.render())
    }

    /** Returns a counterexample if the two outcomes violate the refinement policy, else null. */
    private fun compare(env: Map<String, Val>, s: Outcome, d: Outcome): CounterExample? {
        fun side(o: Outcome): SideResult = when (o) {
            is Outcome.Defined -> SideResult("defined", renderVal(o.value), null, o.trace.path, o.trace.values)
            is Outcome.Ub -> SideResult("ub", null, o.reason, o.trace.path, o.trace.values)
            is Outcome.UnrollExceeded -> SideResult("unroll-exceeded", null, "limit ${o.limit}", o.trace.path, o.trace.values)
            is Outcome.Unmodeled -> SideResult("unmodeled", null, o.hint, emptyList(), emptyList())
        }
        fun ce(kind: String) = CounterExample(
            inputs = env.mapValues { rawVal(it.value) },
            inputsDisplay = env.mapValues { renderVal(it.value) },
            kind = kind, source = side(s), target = side(d)
        )
        val sDef = s as? Outcome.Defined
        val dDef = d as? Outcome.Defined
        return when (config.refinement) {
            // Only inputs where the source is defined constrain the target.
            Refinement.TARGET_REFINES_SOURCE -> when {
                sDef == null -> null
                dDef == null -> ce("target-ub")
                !valsEqual(sDef.value, dDef.value) -> ce("value-mismatch")
                else -> null
            }
            // Both sides must be defined and agree on every input.
            Refinement.STRICT_BOTH_DEFINED -> when {
                sDef != null && dDef != null ->
                    if (valsEqual(sDef.value, dDef.value)) null else ce("value-mismatch")
                sDef == null && dDef == null -> null
                sDef == null -> ce("source-ub")
                else -> ce("target-ub")
            }
        }
    }

    private fun domainSize(ty: Ty): Long = when (ty) {
        is Ty.IntT -> if (ty.width >= 62) -1 else 1L shl ty.width
        Ty.F32 -> floatDomain().size.toLong()
    }

    /** Full concrete domain for one parameter (only called when exhaustive). */
    private fun domainFor(ty: Ty): List<Val> = when (ty) {
        is Ty.IntT -> {
            require(ty.width <= 20) { "exhaustive domain too large for i${ty.width}" }
            (0L until (1L shl ty.width)).map { Val.IntV(ty.width, it) }
        }
        Ty.F32 -> floatDomain()
    }

    private fun floatDomain(): List<Val> {
        val finite = listOf(0.0f, -0.0f, 1.0f, -1.0f, 0.5f, -0.5f, 2.0f,
            Float.MAX_VALUE, Float.MIN_VALUE, java.lang.Float.MIN_NORMAL)
        val nonFinite = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        return (if (config.floatMode == FloatMode.STRICT) finite + nonFinite else finite)
            .map { Val.FloatV(it) }
    }

    private fun edgeInts(w: Int): List<Long> {
        val m = mask(w)
        val raw = listOf(0L, 1L, 2L, 3L, (w - 1).toLong(), w.toLong(), (w + 1).toLong(),
            m, m - 1, (1L shl (w - 1)) and m, ((1L shl (w - 1)) - 1) and m, m ushr 1)
        return raw.map { it and m }.distinct()
    }

    private fun sampledInputs(params: List<Param>, domains: List<List<Val>>, budget: Long): Sequence<List<Val>> = sequence {
        // 1) edge-value cross product (deterministic)
        val edgeDomains = params.map { p ->
            when (p.ty) {
                is Ty.IntT -> edgeInts(p.ty.width).map { Val.IntV(p.ty.width, it) }
                Ty.F32 -> floatDomain()
            }
        }
        val seen = HashSet<String>()
        var emitted = 0L
        for (combo in cartesian(edgeDomains)) {
            if (emitted >= budget) return@sequence
            seen.add(combo.joinToString(",") { rawVal(it) }); emitted++; yield(combo)
        }
        // 2) deterministic pseudo-random bit patterns for the remaining budget
        var rng = 0x9E3779B97F4A7C15uL.toLong()
        while (emitted < budget) {
            val combo = params.map { p ->
                when (p.ty) {
                    is Ty.IntT -> {
                        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
                        Val.IntV(p.ty.width, rng and mask(p.ty.width))
                    }
                    Ty.F32 -> floatDomain()[(rng ushr 32).toInt().mod(floatDomain().size)]
                }
            }
            val key = combo.joinToString(",") { rawVal(it) }
            if (seen.add(key)) { emitted++; yield(combo) }
        }
    }

    private fun cartesian(domains: List<List<Val>>): Sequence<List<Val>> = sequence {
        if (domains.isEmpty()) { yield(emptyList()); return@sequence }
        val idx = IntArray(domains.size)
        while (true) {
            yield(domains.mapIndexed { i, d -> d[idx[i]] })
            var p = domains.size - 1
            while (p >= 0) {
                idx[p]++
                if (idx[p] < domains[p].size) break
                idx[p] = 0; p--
            }
            if (p < 0) break
        }
    }

    private fun satMul(a: Long, b: Long): Long {
        if (a < 0 || b < 0) return -1
        if (a != 0L && b > Long.MAX_VALUE / a) return -1
        return a * b
    }

    private fun totalSpace(total: Long): String = if (total < 0) "> 2^63" else total.toString()

    /** Tracks observed value ranges per SSA instruction across probe runs. */
    inner class RangeCollector {
        private val intMin = HashMap<String, Long>()
        private val intMax = HashMap<String, Long>()
        private val poison = HashSet<String>()
        private val floatBad = HashSet<String>()
        private val floatMin = HashMap<String, Float>()
        private val floatMax = HashMap<String, Float>()

        fun accept(f: Fragment, o: Outcome) {
            val trace = o.trace ?: return
            for ((name, rendered) in trace.values) {
                // rendered values are strings; re-derive typed info from the render is fragile,
                // so ranges are computed from the trace strings produced by renderVal.
                when {
                    rendered == "poison" -> poison.add(name)
                    rendered.startsWith("f32 NaN") || rendered.contains("Inf") -> floatBad.add(name)
                    rendered.startsWith("f32 ") -> {
                        val v = rendered.removePrefix("f32 ").toFloatOrNull() ?: continue
                        floatMin.merge(name, v) { a, b -> minOf(a, b) }
                        floatMax.merge(name, v) { a, b -> maxOf(a, b) }
                    }
                    rendered.startsWith("i") -> {
                        val signed = rendered.substringAfter(' ').substringBefore(' ').toLongOrNull() ?: continue
                        intMin.merge(name, signed) { a, b -> minOf(a, b) }
                        intMax.merge(name, signed) { a, b -> maxOf(a, b) }
                    }
                }
            }
        }

        fun render(): Map<String, String> {
            val out = sortedMapOf<String, String>()
            for ((n, lo) in intMin) out[n] = "[$lo, ${intMax[n]}]" + if (n in poison) " +poison" else ""
            for (n in poison) out.putIfAbsent(n, "poison only")
            for ((n, lo) in floatMin) out[n] = "[$lo, ${floatMax[n]}]" + if (n in floatBad) " +non-finite" else ""
            for (n in floatBad) out.putIfAbsent(n, "non-finite")
            return out
        }
    }
}
