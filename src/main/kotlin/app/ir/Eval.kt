package app.ir

/** How overflow flags (nsw/nuw) are honoured. */
enum class OverflowMode { RESPECT, IGNORE }

/** Outcome of concretely executing a fragment on one input. */
sealed interface Outcome {
    val trace: Trace?

    data class Defined(val value: Val, override val trace: Trace) : Outcome
    data class Ub(val reason: String, override val trace: Trace) : Outcome
    data class Unmodeled(val hint: String) : Outcome { override val trace: Trace? get() = null }
    data class UnrollExceeded(val limit: Int, override val trace: Trace) : Outcome
}

/** Execution trace: intermediate values in execution order + taken path. */
data class Trace(
    val values: MutableList<Pair<String, String>> = mutableListOf(),
    val path: MutableList<String> = mutableListOf()
)

class Evaluator(val overflowMode: OverflowMode, val unrollLimit: Int) {

    fun run(f: Fragment, inputs: Map<String, Val>): Outcome {
        if (f.containsUnmodeled()) {
            val hint = (f.allInstrs().first { it is Instr.Unmodeled } as Instr.Unmodeled).hint
            return Outcome.Unmodeled(hint)
        }
        val env = HashMap<String, Val>(inputs)
        val trace = Trace()
        val visits = HashMap<String, Int>()
        var block = f.entry
        var from: String? = null
        while (true) {
            val n = (visits[block.label] ?: 0) + 1
            visits[block.label] = n
            if (n > unrollLimit) return Outcome.UnrollExceeded(unrollLimit, trace)
            if (from == null) trace.path.add(block.label)
            val phis = block.instrs.takeWhile { it is Instr.Phi }
            for (p in phis) {
                p as Instr.Phi
                val inc = p.incomings.firstOrNull { it.second == from }
                    ?: return Outcome.Ub("phi %${p.name} has no incoming for predecessor ${from ?: "<entry>"}", trace)
                val v = env[inc.first] ?: return Outcome.Ub("phi %${p.name} reads undefined %${inc.first}", trace)
                env[p.name] = v
                trace.values.add(p.name to renderVal(v))
            }
            for (i in block.instrs.drop(phis.size)) {
                val v = evalInstr(i, env) ?: return Outcome.Unmodeled((i as Instr.Unmodeled).hint)
                env[i.name] = v
                trace.values.add(i.name to renderVal(v))
            }
            when (val t = block.term) {
                is Term.Jmp -> { trace.path.add("${block.label} -> ${t.target}"); from = block.label; block = f.block(t.target) }
                is Term.Br -> {
                    when (val c = env[t.cond]) {
                        Val.Poison -> return Outcome.Ub("branch on poison condition %${t.cond}", trace)
                        is Val.IntV -> {
                            val next = if (c.masked != 0L) t.thenB else t.elseB
                            trace.path.add("${block.label} -> $next (cond=${c.masked != 0L})")
                            from = block.label; block = f.block(next)
                        }
                        else -> return Outcome.Ub("branch condition %${t.cond} is not i1", trace)
                    }
                }
                is Term.Ret -> {
                    return when (val v = env[t.value]) {
                        null -> Outcome.Ub("ret of undefined %${t.value}", trace)
                        Val.Poison -> Outcome.Ub("returned poison (%${t.value})", trace)
                        else -> Outcome.Defined(v, trace)
                    }
                }
            }
        }
    }

    /** Returns null only for Unmodeled instructions. */
    private fun evalInstr(i: Instr, env: Map<String, Val>): Val? = when (i) {
        is Instr.Const -> when (i.ty) {
            is Ty.IntT -> intConst(i.ty.width, i.intValue)
            Ty.F32 -> Val.FloatV(i.floatValue)
        }
        is Instr.Unmodeled -> null
        is Instr.Phi -> error("phi handled separately")
        is Instr.Freeze -> when (val v = env[i.v]) {
            // freeze of poison yields a deterministic arbitrary value of the declared type
            Val.Poison -> when (i.ty) {
                is Ty.IntT -> Val.IntV(i.ty.width, 0)
                Ty.F32 -> Val.FloatV(0.0f)
            }
            null -> Val.Poison
            else -> v
        }
        is Instr.Select -> {
            val c = env[i.cond]
            if (c == Val.Poison || c == null) Val.Poison
            else {
                val chosen = if ((c as Val.IntV).masked != 0L) env[i.t] else env[i.f]
                chosen ?: Val.Poison
            }
        }
        is Instr.IBin -> {
            val a = env[i.a]; val b = env[i.b]
            if (a == Val.Poison || b == Val.Poison || a == null || b == null) Val.Poison
            else iBin(i, a as Val.IntV, b as Val.IntV)
        }
        is Instr.ICmp -> {
            val a = env[i.a]; val b = env[i.b]
            if (a == Val.Poison || b == Val.Poison || a == null || b == null) Val.Poison
            else {
                val x = a as Val.IntV; val y = b as Val.IntV
                val r = when (i.pred) {
                    CmpPred.EQ -> x.masked == y.masked
                    CmpPred.NE -> x.masked != y.masked
                    CmpPred.SLT -> x.signed < y.signed
                    CmpPred.SLE -> x.signed <= y.signed
                    CmpPred.SGT -> x.signed > y.signed
                    CmpPred.SGE -> x.signed >= y.signed
                    CmpPred.ULT -> java.lang.Long.compareUnsigned(x.masked, y.masked) < 0
                    CmpPred.ULE -> java.lang.Long.compareUnsigned(x.masked, y.masked) <= 0
                    CmpPred.UGT -> java.lang.Long.compareUnsigned(x.masked, y.masked) > 0
                    CmpPred.UGE -> java.lang.Long.compareUnsigned(x.masked, y.masked) >= 0
                }
                Val.IntV(1, if (r) 1 else 0)
            }
        }
        is Instr.FBin -> {
            val a = env[i.a]; val b = env[i.b]
            if (a == Val.Poison || b == Val.Poison || a == null || b == null) Val.Poison
            else {
                val x = (a as Val.FloatV).f; val y = (b as Val.FloatV).f
                Val.FloatV(when (i.op) {
                    FloatOp.FADD -> x + y
                    FloatOp.FSUB -> x - y
                    FloatOp.FMUL -> x * y
                })
            }
        }
        is Instr.FCmp -> {
            val a = env[i.a]; val b = env[i.b]
            if (a == Val.Poison || b == Val.Poison || a == null || b == null) Val.Poison
            else {
                val x = (a as Val.FloatV).f; val y = (b as Val.FloatV).f
                val nan = x.isNaN() || y.isNaN()
                val r = when (i.pred) {
                    FCmpPred.OEQ -> !nan && x == y
                    FCmpPred.OGT -> !nan && x > y
                    FCmpPred.OGE -> !nan && x >= y
                    FCmpPred.OLT -> !nan && x < y
                    FCmpPred.OLE -> !nan && x <= y
                    FCmpPred.ONE -> !nan && x != y
                    FCmpPred.UEQ -> nan || x == y
                    FCmpPred.ORD -> !nan
                }
                Val.IntV(1, if (r) 1 else 0)
            }
        }
    }

    private fun iBin(i: Instr.IBin, a: Val.IntV, b: Val.IntV): Val {
        val w = i.ty.width
        val m = mask(w)
        val x = a.masked; val y = b.masked
        val respect = overflowMode == OverflowMode.RESPECT
        when (i.op) {
            IntOp.ADD -> {
                val r = (x + y) and m
                if (respect) {
                    if (i.nuw && w < 64 && (x + y) ushr w != 0L) return Val.Poison
                    if (i.nuw && w == 64 && java.lang.Long.compareUnsigned(r, x) < 0) return Val.Poison
                    if (i.nsw) {
                        val sx = a.signed; val sy = b.signed; val sr = signExtend(r, w)
                        if ((sx >= 0) == (sy >= 0) && (sr >= 0) != (sx >= 0)) return Val.Poison
                    }
                }
                return Val.IntV(w, r)
            }
            IntOp.SUB -> {
                val r = (x - y) and m
                if (respect) {
                    if (i.nuw && java.lang.Long.compareUnsigned(x, y) < 0) return Val.Poison
                    if (i.nsw) {
                        val sx = a.signed; val sy = b.signed; val sr = signExtend(r, w)
                        if ((sx >= 0) != (sy >= 0) && (sr >= 0) != (sx >= 0)) return Val.Poison
                    }
                }
                return Val.IntV(w, r)
            }
            IntOp.MUL -> {
                val r = (x * y) and m
                if (respect) {
                    if (i.nuw) {
                        val hi = Math.unsignedMultiplyHigh(x, y)
                        val lo = x * y
                        val overflow = hi != 0L || (w < 64 && (lo ushr w) != 0L)
                        if (overflow) return Val.Poison
                    }
                    if (i.nsw) {
                        val sx = a.signed; val sy = b.signed
                        val overflow = if (w == 64) {
                            val hi = Math.multiplyHigh(sx, sy)
                            val lo = sx * sy
                            hi != (lo shr 63)
                        } else {
                            val full = sx * sy
                            full != signExtend(full and m, w)
                        }
                        if (overflow) return Val.Poison
                    }
                }
                return Val.IntV(w, r)
            }
            IntOp.AND -> return Val.IntV(w, x and y)
            IntOp.OR -> return Val.IntV(w, x or y)
            IntOp.XOR -> return Val.IntV(w, x xor y)
            IntOp.SHL -> {
                if (y >= w.toLong()) return Val.Poison // overshift: shift amount >= width
                val k = y.toInt()
                val r = (x shl k) and m
                if (respect && k > 0) {
                    if (i.nuw && (x ushr (w - k)) != 0L) return Val.Poison
                    if (i.nsw && signExtend(r, w) shr k != a.signed) return Val.Poison
                }
                return Val.IntV(w, r)
            }
            IntOp.LSHR -> {
                if (y >= w.toLong()) return Val.Poison
                return Val.IntV(w, (x ushr y.toInt()) and m)
            }
            IntOp.ASHR -> {
                if (y >= w.toLong()) return Val.Poison
                return Val.IntV(w, (a.signed shr y.toInt()) and m)
            }
        }
    }
}

fun renderVal(v: Val): String = when (v) {
    is Val.IntV -> v.render()
    is Val.FloatV -> v.render()
    Val.Poison -> "poison"
}
