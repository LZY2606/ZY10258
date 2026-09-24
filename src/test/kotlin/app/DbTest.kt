package app

import app.db.Db
import app.fixtures.Fixtures
import app.ir.OverflowMode
import app.verify.Minimizer
import app.verify.Status
import app.verify.Verifier
import app.verify.VerifyConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class DbTest {
    @Test
    fun `run records survive export, wipe and re-import`() {
        val db = Db(":memory:")
        val cfg = VerifyConfig(bitWidth = 8, overflowMode = OverflowMode.RESPECT)
        val verifier = Verifier(cfg)
        val results = Fixtures.all.map { fx -> verifier.verify(fx.build(8)) }
        val minimized = results.mapNotNull { r ->
            r.ce?.let { ce ->
                r.pairId to Minimizer.minimize(Fixtures.byId(r.pairId)!!.build(8), cfg, ce)
            }
        }.toMap()
        val runId = db.insertRun(cfg, results, minimized)
        assertTrue(runId > 0)

        val before = db.listRuns()
        assertEquals(1, before.size)
        assertEquals(Fixtures.all.size, before[0].results.size)
        // counterexample rows carry both original and minimized forms
        val ceRow = before[0].results.first { it.pairId == "inc-cmp-wrap" }
        assertNotNull(ceRow.originalCe)
        assertNotNull(ceRow.minimizedCe)
        assertTrue(ceRow.minimizedCe!!.minimized)
        assertTrue(!ceRow.originalCe!!.minimized)

        val exported = db.exportJson()
        db.reset()
        assertEquals(0, db.listRuns().size)

        val imported = db.importJson(exported)
        assertEquals(1, imported)
        val after = db.listRuns()
        assertEquals(1, after.size)
        assertEquals(before[0].results.map { it.pairId to it.status },
                     after[0].results.map { it.pairId to it.status })
        assertEquals(before[0].config, after[0].config)
        db.close()
    }

    @Test
    fun `statuses are stored distinctly`() {
        val db = Db(":memory:")
        val cfg = VerifyConfig()
        val verifier = Verifier(cfg)
        val results = Fixtures.all.map { fx -> verifier.verify(fx.build(8)) }
        db.insertRun(cfg, results, emptyMap())
        val run = db.listRuns().single()
        val statuses = run.results.map { it.status }.toSet()
        assertTrue(Status.PROVEN_EQUIVALENT in statuses)
        assertTrue(Status.COUNTEREXAMPLE in statuses)
        assertTrue(Status.RESOURCE_LIMIT in statuses) // loop-count with default unroll 64 at i8 (n up to 255)
        assertTrue(Status.UNMODELED in statuses)
        db.close()
    }
}
