package app.fixtures

import app.ir.*
import app.verify.Verifier

class FragBuilder(private val name: String, private val resultTy: Ty) {
    private val params = mutableListOf<Param>()
    private val blocks = mutableListOf<Block>()
    fun param(n: String, ty: Ty) { params.add(Param(n, ty)) }
    fun block(label: String, term: Term, vararg instrs: Instr) {
        blocks.add(Block(label, instrs.toList(), term))
    }
    fun build() = Fragment(name, params.toList(), blocks.toList(), resultTy)
}

fun frag(name: String, resultTy: Ty, init: FragBuilder.() -> Unit): Fragment {
    val b = FragBuilder(name, resultTy)
    b.init()
    return b.build()
}

data class Fixture(
    val id: String,
    val title: String,
    val description: String,
    val build: (Int) -> Verifier.BuiltPair
)

object Fixtures {
    val all: List<Fixture> = listOf(

        Fixture("add-double", "x+x ⇒ x<<1（无旗标，等价）",
            "整数加倍改写为左移一位；两者均按位宽环绕，期望已证明等价。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_add_double", i) {
                param("x", i)
                block("entry", Term.Ret("r"),
                    Instr.IBin("r", i, IntOp.ADD, "x", "x"))
            }
            val dst = frag("dst_add_double", i) {
                param("x", i)
                block("entry", Term.Ret("r"),
                    Instr.Const("c1", i, 1),
                    Instr.IBin("r", i, IntOp.SHL, "x", "c1"))
            }
            Verifier.BuiltPair("add-double", src, dst)
        },

        Fixture("inc-cmp-wrap", "icmp sgt (x+1), x ⇒ true（环绕，反例）",
            "不带 nsw 的加一比较被折叠为常量 true；x 取最大有符号值时 x+1 环绕为最小值，比较结果为 false，构成反例。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_inc_cmp_wrap", B1) {
                param("x", i)
                block("entry", Term.Ret("c"),
                    Instr.Const("one", i, 1),
                    Instr.IBin("a", i, IntOp.ADD, "x", "one"),
                    Instr.ICmp("c", CmpPred.SGT, "a", "x"))
            }
            val dst = frag("dst_inc_cmp_wrap", B1) {
                param("x", i)
                block("entry", Term.Ret("c"), Instr.Const("c", B1, 1))
            }
            Verifier.BuiltPair("inc-cmp-wrap", src, dst)
        },

        Fixture("inc-cmp-nsw", "icmp sgt (x+1 nsw), x ⇒ true（nsw，等价精化）",
            "带 nsw 的同一改写：x 为最大有符号值时源侧加法产生毒化值，源未定义的输入不约束目标，按目标精化源口径为等价；若关闭溢出旗标语义则退化为反例。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_inc_cmp_nsw", B1) {
                param("x", i)
                block("entry", Term.Ret("c"),
                    Instr.Const("one", i, 1),
                    Instr.IBin("a", i, IntOp.ADD, "x", "one", nsw = true),
                    Instr.ICmp("c", CmpPred.SGT, "a", "x"))
            }
            val dst = frag("dst_inc_cmp_nsw", B1) {
                param("x", i)
                block("entry", Term.Ret("c"), Instr.Const("c", B1, 1))
            }
            Verifier.BuiltPair("inc-cmp-nsw", src, dst)
        },

        Fixture("overshift-select", "超宽移位守卫 ⇒ 掩码移位（反例）",
            "源侧对移位量做 k < 位宽守卫，越界时返回 0；目标用 k & (w-1) 掩码（宿主移位语义）。k 等于位宽时源为 0 而目标为 x，构成反例。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_overshift", i) {
                param("x", i); param("k", i)
                block("entry", Term.Ret("r"),
                    Instr.Const("w", i, w.toLong()),
                    Instr.Const("zero", i, 0),
                    Instr.ICmp("ok", CmpPred.ULT, "k", "w"),
                    Instr.IBin("s", i, IntOp.SHL, "x", "k"),
                    Instr.Select("r", "ok", "s", "zero"))
            }
            val dst = frag("dst_overshift", i) {
                param("x", i); param("k", i)
                block("entry", Term.Ret("r"),
                    Instr.Const("m", i, (w - 1).toLong()),
                    Instr.IBin("kk", i, IntOp.AND, "k", "m"),
                    Instr.IBin("r", i, IntOp.SHL, "x", "kk"))
            }
            Verifier.BuiltPair("overshift-select", src, dst)
        },

        Fixture("poison-select-and", "select c, x, false ⇒ and c, x（毒化反例）",
            "x 由 nsw 加法产生，p=1 时 x 为毒化值；select 在 c=false 时丢弃毒化臂得到 false，而 and 会把毒化传播到结果，目标未定义而源有定义，构成反例。") { w ->
            val src = frag("src_poison_select", B1) {
                param("c", B1); param("p", B1)
                block("entry", Term.Ret("r"),
                    Instr.Const("one", B1, 1),
                    Instr.Const("zero", B1, 0),
                    Instr.IBin("x", B1, IntOp.ADD, "p", "one", nsw = true),
                    Instr.Select("r", "c", "x", "zero"))
            }
            val dst = frag("dst_poison_select", B1) {
                param("c", B1); param("p", B1)
                block("entry", Term.Ret("r"),
                    Instr.Const("one", B1, 1),
                    Instr.IBin("x", B1, IntOp.ADD, "p", "one", nsw = true),
                    Instr.IBin("r", B1, IntOp.AND, "c", "x"))
            }
            Verifier.BuiltPair("poison-select-and", src, dst)
        },

        Fixture("fcmp-self", "fcmp oeq f, f ⇒ true（NaN 反例）",
            "浮点自比较折叠为 true 在 f 为 NaN 时不成立（有序比较对 NaN 为 false）。严格浮点模式下为反例；有限值模式下等价。") { _ ->
            val src = frag("src_fcmp_self", B1) {
                param("f", Ty.F32)
                block("entry", Term.Ret("c"), Instr.FCmp("c", FCmpPred.OEQ, "f", "f"))
            }
            val dst = frag("dst_fcmp_self", B1) {
                param("f", Ty.F32)
                block("entry", Term.Ret("c"), Instr.Const("c", B1, 1))
            }
            Verifier.BuiltPair("fcmp-self", src, dst)
        },

        Fixture("fadd-zero", "fadd f, +0.0 ⇒ f（负零反例）",
            "IEEE 算术中 -0.0 + 0.0 = +0.0，按位比较与 -0.0 不同；恒等改写在 f = -0.0 时构成反例。") { _ ->
            val src = frag("src_fadd_zero", Ty.F32) {
                param("f", Ty.F32)
                block("entry", Term.Ret("r"),
                    Instr.Const("z", Ty.F32, floatValue = 0.0f),
                    Instr.FBin("r", FloatOp.FADD, "f", "z"))
            }
            val dst = frag("dst_fadd_zero", Ty.F32) {
                param("f", Ty.F32)
                block("entry", Term.Ret("f"))
            }
            Verifier.BuiltPair("fadd-zero", src, dst)
        },

        Fixture("ext-call", "外部调用 ⇒ 参数（语义未建模）",
            "片段含未建模操作（外部调用）。系统不猜测其语义，直接报告语义未建模，绝不给出等价或反例结论。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_ext_call", i) {
                param("x", i)
                block("entry", Term.Ret("y"),
                    Instr.Unmodeled("y", "call i$w @external(i$w %x)"))
            }
            val dst = frag("dst_ext_call", i) {
                param("x", i)
                block("entry", Term.Ret("x"))
            }
            Verifier.BuiltPair("ext-call", src, dst)
        },

        Fixture("loop-count", "计数循环 ⇒ 直接返回 n（受展开上限约束）",
            "循环累加 n 次 1 改写为返回 n。展开上限小于迭代次数时验证器无法穷举，报告资源上限（未知）而非等价。") { w ->
            val i = Ty.IntT(w)
            val src = frag("src_loop_count", i) {
                param("n", i)
                block("entry", Term.Jmp("loop"),
                    Instr.Const("zero", i, 0),
                    Instr.Const("one", i, 1))
                block("loop", Term.Br("c", "body", "exit"),
                    Instr.Phi("i", i, listOf("zero" to "entry", "i1" to "body")),
                    Instr.Phi("acc", i, listOf("zero" to "entry", "acc1" to "body")),
                    Instr.ICmp("c", CmpPred.ULT, "i", "n"))
                block("body", Term.Jmp("loop"),
                    Instr.IBin("i1", i, IntOp.ADD, "i", "one"),
                    Instr.IBin("acc1", i, IntOp.ADD, "acc", "one"))
                block("exit", Term.Ret("acc"))
            }
            val dst = frag("dst_loop_count", i) {
                param("n", i)
                block("entry", Term.Ret("n"))
            }
            Verifier.BuiltPair("loop-count", src, dst)
        }
    )

    fun byId(id: String): Fixture? = all.firstOrNull { it.id == id }
}
