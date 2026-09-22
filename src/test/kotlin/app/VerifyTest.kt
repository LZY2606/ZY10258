package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyTest {

    private fun fixture(id: String) = Fixtures.byId(id) ?: error("no fixture $id")

    private fun run(id: String, cfg: RunConfig): VerifyResult {
        val f = fixture(id)
        val src = parseProgram(Fixtures.render(f.src, cfg.width))
        val tgt = parseProgram(Fixtures.render(f.tgt, cfg.width))
        return Verifier.verify(src, tgt, cfg)
    }

    private fun cfg(
        width: Int = 4,
        overflow: OverflowMode = OverflowMode.STRICT,
        float: FloatMode = FloatMode.IEEE,
        unroll: Int = 8,
        policy: Policy = Policy.REFINEMENT
    ) = RunConfig(width, overflow.name, float.name, unroll, policy.name)

    @Test
    fun `equivalent pair proven equivalent`() {
        val r = run("eq-add-zero", cfg(width = 8))
        assertEquals(Status.EQUIVALENT.name, r.status)
        assertEquals(256, r.checkedInputs)
        assertNull(r.ce)
    }

    @Test
    fun `nsw overflow refinement holds in legal direction`() {
        val r = run("nsw-refine-ok", cfg(width = 4))
        assertEquals(Status.EQUIVALENT.name, r.status)
    }

    @Test
    fun `nsw overflow in illegal direction yields counterexample`() {
        val r = run("nsw-refine-bad", cfg(width = 4))
        assertEquals(Status.COUNTEREXAMPLE.name, r.status)
        val ce = r.ce
        assertNotNull(ce)
        assertEquals("target-ub", ce.reason)
        // x = 4 (i4) 时 shl 得 8（有定义），add nsw 4+4 溢出 → 目标 UB
        assertEquals("4", ce.inputs["x"])
        assertTrue(ce.srcResult.startsWith("i4"))
        assertEquals("UB", ce.tgtResult)
    }

    @Test
    fun `source UB inputs are skipped not treated as counterexample`() {
        // 反向：源 UB 时目标有定义，按精炼口径必须跳过
        val r = run("nsw-refine-ok", cfg(width = 4))
        assertNull(r.ce)
        assertEquals(Status.EQUIVALENT.name, r.status)
    }

    @Test
    fun `overshift masked source vs unmasked target yields counterexample`() {
        val r = run("overshift-mask-bad", cfg(width = 4))
        assertEquals(Status.COUNTEREXAMPLE.name, r.status)
        val ce = r.ce
        assertNotNull(ce)
        assertEquals("target-ub", ce.reason)
        // 移位量 y >= 4 时目标毒化
        assertTrue((ce.inputs["y"]!!.toInt()) >= 4)
    }

    @Test
    fun `overshift legal direction is equivalent`() {
        val r = run("overshift-mask-ok", cfg(width = 4))
        assertEquals(Status.EQUIVALENT.name, r.status)
    }

    @Test
    fun `poison freeze removal yields counterexample`() {
        val r = run("poison-freeze-bad", cfg(width = 4))
        assertEquals(Status.COUNTEREXAMPLE.name, r.status)
        assertEquals("target-ub", r.ce!!.reason)
    }

    @Test
    fun `float minus zero yields counterexample under IEEE`() {
        val r = run("float-add-zero", cfg(float = FloatMode.IEEE))
        assertEquals(Status.COUNTEREXAMPLE.name, r.status)
        val ce = r.ce!!
        assertEquals("value-mismatch", ce.reason)
        assertEquals("-0.0", ce.inputs["u"])
        assertEquals("f 0.0", ce.srcResult)
        assertEquals("f -0.0", ce.tgtResult)
    }

    @Test
    fun `float NaN poisoned under NNAN and skipped by refinement`() {
        // NNAN 下 NaN 输入使源毒化（UB），按口径跳过；但 -0.0 仍是反例
        val r = run("float-add-zero", cfg(float = FloatMode.NNAN))
        assertEquals(Status.COUNTEREXAMPLE.name, r.status)
        assertEquals("-0.0", r.ce!!.inputs["u"])
    }

    @Test
    fun `loop unroll limit yields resource limit`() {
        val r = run("loop-sum", cfg(width = 4, unroll = 2))
        assertEquals(Status.RESOURCE_LIMIT.name, r.status)
        assertTrue(r.note.contains("unroll"))
    }

    @Test
    fun `loop within unroll limit is equivalent`() {
        val r = run("loop-sum", cfg(width = 4, unroll = 8))
        assertEquals(Status.EQUIVALENT.name, r.status)
    }

    @Test
    fun `large input space yields unknown not equivalent`() {
        val r = run("unknown-space", cfg(width = 16))
        assertEquals(Status.UNKNOWN.name, r.status)
        assertNotEquals(Status.EQUIVALENT.name, r.status)
        assertEquals(0, r.checkedInputs)
    }

    @Test
    fun `same pair at small width is equivalent`() {
        val r = run("unknown-space", cfg(width = 4))
        assertEquals(Status.EQUIVALENT.name, r.status)
    }

    @Test
    fun `unmodeled fdiv yields unmodeled status`() {
        val r = run("unmodeled-fdiv", cfg())
        assertEquals(Status.UNMODELED.name, r.status)
    }

    @Test
    fun `fast float mode is unmodeled`() {
        val r = run("float-add-zero", cfg(float = FloatMode.FAST))
        assertEquals(Status.UNMODELED.name, r.status)
    }

    @Test
    fun `integer constants are parsed at fixed width`() {
        // 常量 255 在 i4 下必须截断为 15，不使用宿主大整数语义
        val prog = parseProgram(
            """
            int x
            entry:
              a = const 255
              b = add a, 1
              ret b
            """.trimIndent()
        )
        val out = Evaluator(prog, EvalConfig(width = 4), Inputs(mapOf("x" to 0L))).run()
        assertTrue(out.defined)
        val v = out.result as Value.IntV
        assertEquals(0L, v.masked) // 255 -> 15, 15 + 1 环绕为 0
    }

    @Test
    fun `integer wraparound uses fixed width not host semantics`() {
        val prog = parseProgram(
            """
            int x
            entry:
              a = mul x, x
              ret a
            """.trimIndent()
        )
        // i4: x=15 -> 225 mod 16 = 1
        val out = Evaluator(prog, EvalConfig(width = 4), Inputs(mapOf("x" to 15L))).run()
        assertEquals(1L, (out.result as Value.IntV).masked)
    }

    @Test
    fun `minimizer shrinks counterexample and keeps it valid`() {
        val f = fixture("overshift-mask-bad")
        val c = cfg(width = 4)
        val src = parseProgram(Fixtures.render(f.src, c.width))
        val tgt = parseProgram(Fixtures.render(f.tgt, c.width))
        val r = Verifier.verify(src, tgt, c)
        val ce = r.ce!!
        val min = Verifier.minimize(src, tgt, c, ce)
        assertTrue(min.reason.contains("minimized"))
        // 缩减后仍触发同类反例：目标 UB
        assertEquals("UB", min.tgtResult)
        // x 被缩减到 0，y 缩减到最小触发值 4
        assertEquals("0", min.inputs["x"])
        assertEquals("4", min.inputs["y"])
        // 原例保留在结果中
        assertNotNull(r.ce)
    }

    @Test
    fun `db roundtrip export clear import`() {
        Db.init("build/test-witness.db")
        Db.clear()
        val f = fixture("eq-add-zero")
        val c = cfg(width = 4)
        val result = run("eq-add-zero", c)
        val id = Db.insert(
            RunRecord(
                createdAt = "2026-09-22T00:00:00Z",
                fixture = f.id,
                config = c,
                srcIr = f.src,
                tgtIr = f.tgt,
                result = result
            )
        )
        assertEquals(1, Db.list().size)
        val exported = Db.exportJson()
        assertTrue(exported.contains("eq-add-zero"))
        Db.clear()
        assertEquals(0, Db.list().size)
        val n = Db.importJson(exported)
        assertEquals(1, n)
        val loaded = Db.list().first()
        assertEquals("eq-add-zero", loaded.fixture)
        assertEquals(Status.EQUIVALENT.name, loaded.result.status)
        assertTrue(loaded.id > 0)
        assertTrue(id > 0)
    }
}
