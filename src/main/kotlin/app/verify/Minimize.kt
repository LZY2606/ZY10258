package app.verify

import app.ir.*

/** Greedy counterexample minimization: shrink each input toward simpler values
 *  while the mismatch of the same kind persists. The original is never mutated. */
object Minimizer {

    fun minimize(pair: Verifier.BuiltPair, config: VerifyConfig, ce: CounterExample): CounterExample {
        val evaluator = Evaluator(config.overflowMode, config.unrollLimit)
        val params = pair.src.params
        var current = ce.inputs.toMutableMap()

        fun mismatchKind(env: Map<String, Val>): String? {
            val s = evaluator.run(pair.src, env)
            val d = evaluator.run(pair.dst, env)
            val sDef = s as? Outcome.Defined
            val dDef = d as? Outcome.Defined
            return when (config.refinement) {
                Refinement.TARGET_REFINES_SOURCE -> when {
                    sDef == null -> null
                    dDef == null -> "target-ub"
                    !valsEqual(sDef.value, dDef.value) -> "value-mismatch"
                    else -> null
                }
                Refinement.STRICT_BOTH_DEFINED -> when {
                    sDef != null && dDef != null ->
                        if (valsEqual(sDef.value, dDef.value)) null else "value-mismatch"
                    sDef == null && dDef == null -> null
                    sDef == null -> "source-ub"
                    else -> "target-ub"
                }
            }
        }

        for (p in params) {
            var cur = parseRawVal(current[p.name] ?: continue)
            for (cand in candidates(cur)) {
                if (rank(cand) >= rank(cur)) continue // only move toward strictly simpler values
                val trial = HashMap(current.mapValues { parseRawVal(it.value) })
                trial[p.name] = cand
                if (mismatchKind(trial) == ce.kind) {
                    current[p.name] = rawVal(cand)
                    cur = cand
                }
            }
        }
        val env = current.mapValues { parseRawVal(it.value) }
        val s = evaluator.run(pair.src, env)
        val d = evaluator.run(pair.dst, env)
        fun side(o: Outcome): SideResult = when (o) {
            is Outcome.Defined -> SideResult("defined", renderVal(o.value), null, o.trace.path, o.trace.values)
            is Outcome.Ub -> SideResult("ub", null, o.reason, o.trace.path, o.trace.values)
            is Outcome.UnrollExceeded -> SideResult("unroll-exceeded", null, "limit ${o.limit}", o.trace.path, o.trace.values)
            is Outcome.Unmodeled -> SideResult("unmodeled", null, o.hint, emptyList(), emptyList())
        }
        return CounterExample(
            inputs = current.toMap(),
            inputsDisplay = env.mapValues { renderVal(it.value) },
            kind = ce.kind, source = side(s), target = side(d), minimized = true
        )
    }

    /** Simplicity rank: small magnitudes and canonical specials rank lower. */
    private fun rank(v: Val): Int = when (v) {
        is Val.IntV -> if (v.masked <= 15) v.masked.toInt() else 16 + java.lang.Long.bitCount(v.masked)
        is Val.FloatV -> {
            val order = listOf(0.0f, 1.0f, -0.0f, -1.0f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            val idx = order.indexOfFirst { it.toRawBits() == v.f.toRawBits() }
            if (idx >= 0) idx else 100
        }
        Val.Poison -> 1000
    }

    private fun candidates(v: Val): List<Val> = when (v) {
        is Val.IntV -> {
            val w = v.width
            val m = mask(w)
            listOf(0L, 1L, 2L, m, m - 1, (1L shl (w - 1)) and m, ((1L shl (w - 1)) - 1) and m,
                w.toLong() and m, (w - 1).toLong() and m)
                .distinct()
                .filter { it != v.masked }
                .map { Val.IntV(w, it) }
        }
        is Val.FloatV -> listOf(0.0f, 1.0f, -0.0f, -1.0f, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .filter { it.toRawBits() != v.f.toRawBits() }
            .map { Val.FloatV(it) }
        Val.Poison -> emptyList()
    }
}
