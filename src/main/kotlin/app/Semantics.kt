package app

/** 运行期值：固定位宽整数（环绕语义，绝不使用宿主大整数）、双精度浮点、毒化值。 */
sealed class Value {
    data class IntV(val width: Int, val bits: Long) : Value() {
        val masked: Long get() = bits and mask(width)
        val signed: Long get() = (masked shl (64 - width)) shr (64 - width)
        val unsigned: Long get() = masked
    }

    data class FloatV(val v: Double) : Value()
    data object Poison : Value()
}

fun mask(width: Int): Long = if (width >= 64) -1L else (1L shl width) - 1

fun intV(width: Int, bits: Long): Value.IntV = Value.IntV(width, bits and mask(width))

class UnmodeledException(message: String) : Exception(message)
class ResourceLimitException(message: String) : Exception(message)

enum class OverflowMode { STRICT, IGNORED }
enum class FloatMode { IEEE, NNAN, FAST }
enum class Policy { REFINEMENT, BOTH_DEFINED }

data class EvalConfig(
    val width: Int = 8,
    val overflowMode: OverflowMode = OverflowMode.STRICT,
    val floatMode: FloatMode = FloatMode.IEEE,
    val unrollLimit: Int = 8,
    val stepLimit: Int = 10000
)

data class Inputs(
    val ints: Map<String, Long> = emptyMap(),
    val floats: Map<String, Double> = emptyMap()
)

data class EvalOutcome(
    val defined: Boolean,
    val result: Value?,
    val trace: List<Pair<String, Value>>,
    val blocks: List<String>,
    val conditions: List<String>,
    val ubReason: String? = null
)

fun renderValue(v: Value?): String = when (v) {
    null -> "UB"
    Value.Poison -> "poison"
    is Value.IntV -> "i${v.width} ${v.masked} (signed ${v.signed})"
    is Value.FloatV -> when {
        v.v.isNaN() -> "f NaN"
        v.v == Double.POSITIVE_INFINITY -> "f +Inf"
        v.v == Double.NEGATIVE_INFINITY -> "f -Inf"
        else -> "f ${v.v}"
    }
}

class Evaluator(
    private val prog: Program,
    private val cfg: EvalConfig,
    inputs: Inputs
) {
    private val env = HashMap<String, Value>()
    private val trace = mutableListOf<Pair<String, Value>>()
    private val blocksWalked = mutableListOf<String>()
    private val conditions = mutableListOf<String>()
    private val blockExec = HashMap<String, Int>()
    private var steps = 0

    init {
        for (p in prog.intParams) env[p] = intV(cfg.width, inputs.ints[p] ?: 0L)
        for (p in prog.floatParams) env[p] = Value.FloatV(inputs.floats[p] ?: 0.0)
    }

    private fun operand(o: Operand): Value = when (o) {
        is Operand.Ref -> env[o.name] ?: error("unbound value ${o.name}")
        // 整数常量按固定位宽解析并截断，不使用宿主大整数语义。
        is Operand.ILit -> intV(cfg.width, o.text.toLong())
        is Operand.FLit -> Value.FloatV(parseFloat(o.text))
    }

    private fun parseFloat(text: String): Double = when (text.lowercase()) {
        "nan", "-nan" -> Double.NaN
        "inf", "infinity", "+inf" -> Double.POSITIVE_INFINITY
        "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
        else -> text.toDouble()
    }

    fun run(): EvalOutcome {
        var current = prog.entry.name
        var pred: String? = null
        while (true) {
            if (++steps > cfg.stepLimit) throw ResourceLimitException("step limit ${cfg.stepLimit}")
            val count = (blockExec[current] ?: 0) + 1
            blockExec[current] = count
            if (count > cfg.unrollLimit) {
                throw ResourceLimitException("block $current executed $count times > unroll limit ${cfg.unrollLimit}")
            }
            val blk = prog.block(current)
            blocksWalked.add(current)
            val p = pred
            for (ins in blk.instrs) {
                val v = evalInstr(ins, p)
                env[ins.name] = v
                trace.add(ins.name to v)
            }
            when (val t = blk.term) {
                is Term.Ret -> {
                    val v = operand(t.v)
                    return if (v == Value.Poison) {
                        EvalOutcome(false, null, trace, blocksWalked, conditions, "return of poison")
                    } else {
                        EvalOutcome(true, v, trace, blocksWalked, conditions)
                    }
                }
                is Term.Jmp -> {
                    pred = current
                    current = t.target
                }
                is Term.Br -> {
                    val c = operand(t.cond)
                    if (c == Value.Poison) {
                        return EvalOutcome(false, null, trace, blocksWalked, conditions, "branch on poison condition")
                    }
                    require(c is Value.IntV) { "branch condition must be i1" }
                    val taken = c.masked != 0L
                    conditions.add("${renderOperand(t.cond)}=${if (taken) "true" else "false"}")
                    pred = current
                    current = if (taken) t.ifTrue else t.ifFalse
                }
            }
        }
    }

    private fun renderOperand(o: Operand): String = when (o) {
        is Operand.Ref -> o.name
        is Operand.ILit -> o.text
        is Operand.FLit -> o.text
    }

    private fun evalInstr(ins: Instr, pred: String?): Value = when (ins) {
        is Instr.ConstI -> intV(cfg.width, ins.literal.toLong())
        is Instr.ConstF -> Value.FloatV(parseFloat(ins.literal))
        is Instr.Phi -> {
            val incoming = ins.incoming.firstOrNull { it.second == pred }
                ?: throw IllegalStateException("phi ${ins.name} has no incoming from $pred")
            operand(incoming.first)
        }
        is Instr.Freeze -> when (val v = operand(ins.v)) {
            Value.Poison -> intV(cfg.width, 0) // 冻结毒化值：确定性地取 0，保证反例可重放
            else -> v
        }
        is Instr.Select -> {
            val c = operand(ins.cond)
            if (c == Value.Poison) Value.Poison
            else {
                require(c is Value.IntV) { "select cond must be i1" }
                if (c.masked != 0L) operand(ins.t) else operand(ins.f)
            }
        }
        is Instr.ICmp -> {
            val a = operand(ins.a)
            val b = operand(ins.b)
            if (a == Value.Poison || b == Value.Poison) Value.Poison
            else {
                require(a is Value.IntV && b is Value.IntV)
                val r = when (ins.pred) {
                    "eq" -> a.masked == b.masked
                    "ne" -> a.masked != b.masked
                    "slt" -> a.signed < b.signed
                    "sle" -> a.signed <= b.signed
                    "sgt" -> a.signed > b.signed
                    "sge" -> a.signed >= b.signed
                    "ult" -> a.unsigned < b.unsigned
                    "ule" -> a.unsigned <= b.unsigned
                    "ugt" -> a.unsigned > b.unsigned
                    "uge" -> a.unsigned >= b.unsigned
                    else -> throw UnmodeledException("icmp pred ${ins.pred}")
                }
                intV(1, if (r) 1 else 0)
            }
        }
        is Instr.Bin -> evalBin(ins)
        is Instr.FBin -> evalFBin(ins)
        is Instr.FCmp -> evalFCmp(ins)
    }

    private fun evalBin(ins: Instr.Bin): Value {
        val a = operand(ins.a)
        val b = operand(ins.b)
        if (a == Value.Poison || b == Value.Poison) return Value.Poison
        require(a is Value.IntV && b is Value.IntV)
        val w = cfg.width
        val ua = a.unsigned
        val ub = b.unsigned
        val sa = a.signed
        val sb = b.signed
        val strict = cfg.overflowMode == OverflowMode.STRICT
        fun poisonIf(flag: String, bad: Boolean, wrapped: Long): Value =
            if (strict && flag in ins.flags && bad) Value.Poison else intV(w, wrapped)
        return when (ins.op) {
            "add" -> {
                val r = (ua + ub) and mask(w)
                val sr = Value.IntV(w, r).signed
                val nswBad = (sa >= 0 && sb >= 0 && sr < 0) || (sa < 0 && sb < 0 && sr >= 0)
                val nuwBad = ua + ub > mask(w)
                if (strict && (("nsw" in ins.flags && nswBad) || ("nuw" in ins.flags && nuwBad))) Value.Poison
                else intV(w, r)
            }
            "sub" -> {
                val r = (ua - ub) and mask(w)
                val sr = Value.IntV(w, r).signed
                val nswBad = (sa >= 0 && sb < 0 && sr < 0) || (sa < 0 && sb >= 0 && sr >= 0)
                val nuwBad = ua < ub
                if (strict && (("nsw" in ins.flags && nswBad) || ("nuw" in ins.flags && nuwBad))) Value.Poison
                else intV(w, r)
            }
            "mul" -> {
                val r = (ua * ub) and mask(w)
                // 位宽 <= 32，积的精确值落在 Long 内，可用于溢出判定；结果仍按位宽环绕。
                val exactS = sa * sb
                val exactU = ua * ub
                val nswBad = exactS != Value.IntV(w, exactS and mask(w)).signed
                val nuwBad = exactU > mask(w)
                if (strict && (("nsw" in ins.flags && nswBad) || ("nuw" in ins.flags && nuwBad))) Value.Poison
                else intV(w, r)
            }
            "and" -> intV(w, ua and ub)
            "or" -> intV(w, ua or ub)
            "xor" -> intV(w, ua xor ub)
            "shl" -> {
                if (ub >= w) return Value.Poison // 超宽移位
                val r = (ua shl ub.toInt()) and mask(w)
                poisonIf("nsw", Value.IntV(w, r).signed shr ub.toInt() != sa, r)
            }
            "lshr" -> {
                if (ub >= w) return Value.Poison
                intV(w, ua ushr ub.toInt())
            }
            "ashr" -> {
                if (ub >= w) return Value.Poison
                intV(w, sa shr ub.toInt())
            }
            "udiv", "sdiv" -> throw UnmodeledException("integer division not modeled: ${ins.op}")
            else -> throw UnmodeledException("op ${ins.op}")
        }
    }

    private fun evalFBin(ins: Instr.FBin): Value {
        if (cfg.floatMode == FloatMode.FAST) throw UnmodeledException("fast-math float mode not modeled")
        val a = operand(ins.a)
        val b = operand(ins.b)
        if (a == Value.Poison || b == Value.Poison) return Value.Poison
        require(a is Value.FloatV && b is Value.FloatV)
        if (ins.op == "fdiv") throw UnmodeledException("fdiv not modeled")
        val r = when (ins.op) {
            "fadd" -> a.v + b.v
            "fsub" -> a.v - b.v
            "fmul" -> a.v * b.v
            else -> throw UnmodeledException("op ${ins.op}")
        }
        return floatResult(r)
    }

    private fun evalFCmp(ins: Instr.FCmp): Value {
        if (cfg.floatMode == FloatMode.FAST) throw UnmodeledException("fast-math float mode not modeled")
        val a = operand(ins.a)
        val b = operand(ins.b)
        if (a == Value.Poison || b == Value.Poison) return Value.Poison
        require(a is Value.FloatV && b is Value.FloatV)
        if (cfg.floatMode == FloatMode.NNAN && (a.v.isNaN() || b.v.isNaN())) return Value.Poison
        val x = a.v
        val y = b.v
        val r = when (ins.pred) {
            "oeq" -> x == y
            "one" -> x != y && !x.isNaN() && !y.isNaN()
            "ogt" -> x > y
            "oge" -> x >= y
            "olt" -> x < y
            "ole" -> x <= y
            "une" -> x != y || x.isNaN() || y.isNaN()
            "uno" -> x.isNaN() || y.isNaN()
            else -> throw UnmodeledException("fcmp pred ${ins.pred}")
        }
        return intV(1, if (r) 1 else 0)
    }

    private fun floatResult(r: Double): Value =
        if (cfg.floatMode == FloatMode.NNAN && r.isNaN()) Value.Poison else Value.FloatV(r)
}
