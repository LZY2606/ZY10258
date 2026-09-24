package app

import app.fixtures.Fixtures
import app.ir.OverflowMode
import app.verify.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class VerifierTest {
    private fun cfg(
        w: Int = 8,
        overflow: OverflowMode = OverflowMode.RESPECT,
        float: FloatMode = FloatMode.STRICT,
        unroll: Int = 64,
        refine: Refinement = Refinement.TARGET_REFINES_SOURCE,
        budget: Long = 200_000
    ) = VerifyConfig(w, overflow, float, unroll, refine, budget)

    private fun run(id: String, c: VerifyConfig): PairResult =
        Verifier(c).verify(Fixtures.byId(id)!!.build(c.bitWidth))

    @Test
    fun `equivalent pair is proven equivalent exhaustively`() {
        val r = run("add-double", cfg())
        assertEquals(Status.PROVEN_EQUIVALENT, r.status)
        assertTrue(r.exhaustive)
        assertNull(r.ce)
    }

    @Test
    fun `wrap increment-compare finds counterexample at signed max`() {
        val r = run("inc-cmp-wrap", cfg())
        assertEquals(Status.COUNTEREXAMPLE, r.status)
        val ce = r.ce!!
        assertEquals("value-mismatch", ce.kind)
        assertEquals("i8:127", ce.inputs["x"])
        assertEquals("defined", ce.source.outcome)
        assertEquals("defined", ce.target.outcome)
        assertTrue(ce.source.path.isNotEmpty())
        assertTrue(ce.source.values.isNotEmpty())
    }

    @Test
    fun `nsw variant is a valid refinement under RESPECT but fails under IGNORE`() {
        val ok = run("inc-cmp-nsw", cfg())
        assertEquals(Status.PROVEN_EQUIVALENT, ok.status)
        val bad = run("inc-cmp-nsw", cfg(overflow = OverflowMode.IGNORE))
        assertEquals(Status.COUNTEREXAMPLE, bad.status)
    }

    @Test
    fun `source-ub inputs are not reported as counterexamples under refinement`() {
        // under TARGET_REFINES_SOURCE, x=max makes source poison -> skipped, so equivalent.
        // under STRICT_BOTH_DEFINED the same input must be flagged instead.
        val strict = run("inc-cmp-nsw", cfg(refine = Refinement.STRICT_BOTH_DEFINED))
        assertEquals(Status.COUNTEREXAMPLE, strict.status)
        assertEquals("source-ub", strict.ce!!.kind)
    }

    @Test
    fun `overshift counterexample at k equals width`() {
        val r = run("overshift-select", cfg())
        assertEquals(Status.COUNTEREXAMPLE, r.status)
        val ce = r.ce!!
        assertEquals("value-mismatch", ce.kind)
        assertEquals("i8:8", ce.inputs["k"])
    }

    @Test
    fun `poison select counterexample has defined source and undefined target`() {
        val r = run("poison-select-and", cfg())
        assertEquals(Status.COUNTEREXAMPLE, r.status)
        val ce = r.ce!!
        assertEquals("target-ub", ce.kind)
        assertEquals("defined", ce.source.outcome)
        assertEquals("ub", ce.target.outcome)
    }

    @Test
    fun `float NaN counterexample only in strict mode`() {
        val strict = run("fcmp-self", cfg())
        assertEquals(Status.COUNTEREXAMPLE, strict.status)
        assertTrue(strict.ce!!.inputsDisplay["f"]!!.contains("NaN"))
        val finite = run("fcmp-self", cfg(float = FloatMode.FINITE_ONLY))
        assertEquals(Status.PROVEN_EQUIVALENT, finite.status)
    }

    @Test
    fun `fadd negative zero counterexample`() {
        val r = run("fadd-zero", cfg())
        assertEquals(Status.COUNTEREXAMPLE, r.status)
        assertTrue(r.ce!!.inputsDisplay["f"]!!.contains("-0.0"))
    }

    @Test
    fun `unmodeled operation is reported distinctly`() {
        val r = run("ext-call", cfg())
        assertEquals(Status.UNMODELED, r.status)
        assertNull(r.ce)
    }

    @Test
    fun `loop beyond unroll limit is resource limit, never equivalent`() {
        val limited = run("loop-count", cfg(unroll = 4))
        assertEquals(Status.RESOURCE_LIMIT, limited.status)
        val enough = run("loop-count", cfg(unroll = 1024))
        assertEquals(Status.PROVEN_EQUIVALENT, enough.status)
    }

    @Test
    fun `tiny budget yields resource limit, strictly not equivalent`() {
        val r = run("add-double", cfg(budget = 10))
        assertEquals(Status.RESOURCE_LIMIT, r.status)
        assertTrue(!r.exhaustive)
    }

    @Test
    fun `large width is sampled not exhaustive`() {
        val r = run("add-double", cfg(w = 32))
        assertEquals(Status.RESOURCE_LIMIT, r.status)
        assertTrue(!r.exhaustive)
    }

    @Test
    fun `minimizer shrinks counterexample but keeps it a counterexample`() {
        val c = cfg()
        val pair = Fixtures.byId("overshift-select")!!.build(8)
        val r = Verifier(c).verify(pair)
        assertEquals(Status.COUNTEREXAMPLE, r.status)
        val min = Minimizer.minimize(pair, c, r.ce!!)
        assertTrue(min.minimized)
        assertEquals(r.ce!!.kind, min.kind)
        // original is untouched
        assertTrue(!r.ce!!.minimized)
        // minimized still reproduces: k stays at width, x shrinks to 1
        assertEquals("i8:8", min.inputs["k"])
        assertEquals("i8:1", min.inputs["x"])
    }
}
