package app

import app.fixtures.frag
import app.ir.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue


private inline fun <reified T> assertIs(x: Any?) {
    org.junit.jupiter.api.Assertions.assertTrue(x is T, "expected ${T::class.simpleName} but was $x")
}

class EvalTest {
    private val ev = Evaluator(OverflowMode.RESPECT, 64)

    private fun runSingle(i: Instr, inputs: Map<String, Val> = emptyMap(), w: Int = 8): Outcome {
        val ty = Ty.IntT(w)
        val f = frag("t", ty) {
            for ((n, v) in inputs) param(n, (v as? Val.IntV)?.let { Ty.IntT(it.width) } ?: Ty.F32)
            block("entry", Term.Ret(i.name), i)
        }
        return ev.run(f, inputs)
    }

    @Test
    fun `constants are parsed at fixed width, not host bigint`() {
        // 256 in i8 must wrap to 0; 255 + 1 wraps to 0 without flags
        val c = intConst(8, 256)
        assertEquals(0L, c.masked)
        assertEquals(0L, intConst(8, 255).let { one ->
            val out = runSingle(Instr.IBin("r", Ty.IntT(8), IntOp.ADD, "a", "b"),
                mapOf("a" to one, "b" to Val.IntV(8, 1)))
            (out as Outcome.Defined).let { (it.value as Val.IntV).masked }
        })
        // i64 keeps full 64-bit patterns
        assertEquals(-1L, intConst(64, -1).masked)
    }

    @Test
    fun `nsw add overflow produces poison, ignore mode wraps`() {
        val i = Ty.IntT(8)
        val instr = Instr.IBin("r", i, IntOp.ADD, "a", "b", nsw = true)
        val inputs = mapOf("a" to Val.IntV(8, 127), "b" to Val.IntV(8, 1))
        val poisoned = runSingle(instr, inputs)
        assertIs<Outcome.Ub>(poisoned) // ret of poison is UB
        val wrapped = Evaluator(OverflowMode.IGNORE, 64).let { ev2 ->
            val f = frag("t", i) {
                param("a", i); param("b", i)
                block("entry", Term.Ret("r"), instr)
            }
            ev2.run(f, inputs)
        }
        assertIs<Outcome.Defined>(wrapped)
        assertEquals(-128L, ((wrapped as Outcome.Defined).value as Val.IntV).signed)
    }

    @Test
    fun `shift by width or more is poison`() {
        val i = Ty.IntT(8)
        val shl = Instr.IBin("r", i, IntOp.SHL, "a", "b")
        assertIs<Outcome.Ub>(runSingle(shl, mapOf("a" to Val.IntV(8, 1), "b" to Val.IntV(8, 8))))
        assertIs<Outcome.Ub>(runSingle(shl, mapOf("a" to Val.IntV(8, 1), "b" to Val.IntV(8, 255))))
        val ok = runSingle(shl, mapOf("a" to Val.IntV(8, 1), "b" to Val.IntV(8, 7)))
        assertEquals(128L, ((ok as Outcome.Defined).value as Val.IntV).masked)
    }

    @Test
    fun `select discards poison in unchosen arm, and propagates it`() {
        val f = frag("t", B1) {
            param("c", B1); param("p", B1)
            block("entry", Term.Ret("r"),
                Instr.Const("one", B1, 1),
                Instr.Const("zero", B1, 0),
                Instr.IBin("x", B1, IntOp.ADD, "p", "one", nsw = true), // poison when p=1
                Instr.Select("r", "c", "x", "zero"))
        }
        // c=false, p=1 -> x poison but unchosen -> defined false
        val out = ev.run(f, mapOf("c" to Val.IntV(1, 0), "p" to Val.IntV(1, 1)))
        assertIs<Outcome.Defined>(out)
        assertEquals(0L, ((out as Outcome.Defined).value as Val.IntV).masked)
        // c=true, p=1 -> chosen poison -> UB on ret
        assertIs<Outcome.Ub>(ev.run(f, mapOf("c" to Val.IntV(1, 1), "p" to Val.IntV(1, 1))))
    }

    @Test
    fun `branch on poison is UB`() {
        val f = frag("t", B1) {
            param("p", B1)
            block("entry", Term.Br("x", "a", "b"),
                Instr.Const("one", B1, 1),
                Instr.IBin("x", B1, IntOp.ADD, "p", "one", nsw = true))
            block("a", Term.Ret("one"), Instr.Const("one", B1, 1))
            block("b", Term.Ret("zero"), Instr.Const("zero", B1, 0))
        }
        assertIs<Outcome.Ub>(ev.run(f, mapOf("p" to Val.IntV(1, 1))))
    }

    @Test
    fun `freeze makes poison a deterministic value`() {
        val f = frag("t", B1) {
            param("p", B1)
            block("entry", Term.Ret("z"),
                Instr.Const("one", B1, 1),
                Instr.IBin("x", B1, IntOp.ADD, "p", "one", nsw = true),
                Instr.Freeze("z", B1, "x"))
        }
        val out = ev.run(f, mapOf("p" to Val.IntV(1, 1)))
        assertIs<Outcome.Defined>(out)
    }

    @Test
    fun `float NaN and negative zero semantics`() {
        // fcmp oeq NaN, NaN = false; fcmp ueq NaN, NaN = true
        val oeq = frag("t", B1) {
            param("f", Ty.F32)
            block("entry", Term.Ret("c"), Instr.FCmp("c", FCmpPred.OEQ, "f", "f"))
        }
        val out = ev.run(oeq, mapOf("f" to Val.FloatV(Float.NaN)))
        assertEquals(0L, ((out as Outcome.Defined).value as Val.IntV).masked)
        // -0.0 + 0.0 = +0.0 (bitwise different from -0.0)
        val add = frag("t", Ty.F32) {
            param("f", Ty.F32)
            block("entry", Term.Ret("r"),
                Instr.Const("z", Ty.F32, floatValue = 0.0f),
                Instr.FBin("r", FloatOp.FADD, "f", "z"))
        }
        val r = ev.run(add, mapOf("f" to Val.FloatV(-0.0f)))
        assertEquals(0.0f.toRawBits(), ((r as Outcome.Defined).value as Val.FloatV).f.toRawBits())
        assertTrue(!valsEqualZero())
    }

    private fun valsEqualZero(): Boolean =
        app.verify.valsEqual(Val.FloatV(-0.0f), Val.FloatV(0.0f))

    @Test
    fun `loop respects unroll limit`() {
        val i = Ty.IntT(8)
        val f = frag("t", i) {
            param("n", i)
            block("entry", Term.Jmp("loop"),
                Instr.Const("zero", i, 0), Instr.Const("one", i, 1))
            block("loop", Term.Br("c", "body", "exit"),
                Instr.Phi("i", i, listOf("zero" to "entry", "i1" to "body")),
                Instr.Phi("acc", i, listOf("zero" to "entry", "acc1" to "body")),
                Instr.ICmp("c", CmpPred.ULT, "i", "n"))
            block("body", Term.Jmp("loop"),
                Instr.IBin("i1", i, IntOp.ADD, "i", "one"),
                Instr.IBin("acc1", i, IntOp.ADD, "acc", "one"))
            block("exit", Term.Ret("acc"))
        }
        val small = Evaluator(OverflowMode.RESPECT, 4)
        assertIs<Outcome.UnrollExceeded>(small.run(f, mapOf("n" to Val.IntV(8, 10))))
        val big = Evaluator(OverflowMode.RESPECT, 64)
        val out = big.run(f, mapOf("n" to Val.IntV(8, 10)))
        assertEquals(10L, ((out as Outcome.Defined).value as Val.IntV).masked)
    }
}
