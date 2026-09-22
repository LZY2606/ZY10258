package app

data class Fixture(
    val id: String,
    val title: String,
    val description: String,
    val src: String,
    val tgt: String
)

object Fixtures {
    val all: List<Fixture> = listOf(
        Fixture(
            id = "eq-add-zero",
            title = "恒等：x + 0",
            description = "add x, 0 与 x 在环绕语义下等价（无旗标）。",
            src = """
                int x
                entry:
                  a = add x, 0
                  ret a
            """.trimIndent(),
            tgt = """
                int x
                entry:
                  ret x
            """.trimIndent()
        ),
        Fixture(
            id = "nsw-refine-ok",
            title = "有符号溢出：nsw 加法 → 移位（合法方向）",
            description = "源 add nsw x, x 溢出即毒化（UB），目标 shl x, 1 总有定义。源 UB 时目标可取任意值，按精炼口径成立。",
            src = """
                int x
                entry:
                  a = add nsw x, x
                  ret a
            """.trimIndent(),
            tgt = """
                int x
                entry:
                  a = shl x, 1
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "nsw-refine-bad",
            title = "有符号溢出：移位 → nsw 加法（非法方向）",
            description = "源 shl x, 1 总有定义，目标 add nsw x, x 在溢出时 UB。源有定义而目标 UB，按精炼口径判定为反例。",
            src = """
                int x
                entry:
                  a = shl x, 1
                  ret a
            """.trimIndent(),
            tgt = """
                int x
                entry:
                  a = add nsw x, x
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "overshift-mask-bad",
            title = "超宽移位：掩码改写引入 UB",
            description = "源先把移位量掩码到位宽内（总有定义），目标未掩码，移位量 ≥ 位宽时产生毒化。目标 UB 而源有定义 → 反例。",
            src = """
                int x, y
                entry:
                  m = and y, ${'$'}MASK
                  a = shl x, m
                  ret a
            """.trimIndent(),
            tgt = """
                int x, y
                entry:
                  a = shl x, y
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "overshift-mask-ok",
            title = "超宽移位：未掩码 → 掩码（合法方向）",
            description = "源 shl x, y 在 y ≥ 位宽时 UB，目标掩码后总有定义。源 UB 时目标任意，按精炼口径成立。",
            src = """
                int x, y
                entry:
                  a = shl x, y
                  ret a
            """.trimIndent(),
            tgt = """
                int x, y
                entry:
                  m = and y, ${'$'}MASK
                  a = shl x, m
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "poison-freeze-bad",
            title = "毒化值：去掉 freeze",
            description = "源 freeze 了可能毒化的加法结果（总有定义），目标直接返回，溢出时 UB。目标 UB 而源有定义 → 反例。",
            src = """
                int x
                entry:
                  a = add nsw x, 1
                  b = freeze a
                  ret b
            """.trimIndent(),
            tgt = """
                int x
                entry:
                  a = add nsw x, 1
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "float-add-zero",
            title = "浮点非数值：fadd x, 0.0 ≠ x",
            description = "IEEE 下 x = -0.0 时 fadd 得 +0.0，位级不等 → 反例；NaN 输入在 NNAN 模式下产生毒化，按口径跳过。",
            src = """
                float u
                entry:
                  a = fadd u, 0.0
                  ret a
            """.trimIndent(),
            tgt = """
                float u
                entry:
                  ret u
            """.trimIndent()
        ),
        Fixture(
            id = "loop-sum",
            title = "循环：三次累加 → 乘法",
            description = "循环累加 x 三次与 mul x, 3 在无旗标语义下等价；展开上限小于 4 时触发资源上限。",
            src = """
                int x
                entry:
                  jmp loop
                loop:
                  i = phi [0, entry], [i1, loop]
                  s = phi [0, entry], [s1, loop]
                  i1 = add i, 1
                  s1 = add s, x
                  c = icmp slt i1, 3
                  br c, loop, exit
                exit:
                  ret s1
            """.trimIndent(),
            tgt = """
                int x
                entry:
                  a = mul x, 3
                  ret a
            """.trimIndent()
        ),
        Fixture(
            id = "unknown-space",
            title = "未知：输入空间超预算",
            description = "三个整型参数在 16 位宽下输入空间为 2^48，超出求解预算 → UNKNOWN（严格区别于 EQUIVALENT）。低位宽下可判等价。",
            src = """
                int x, y, z
                entry:
                  a = add x, y
                  b = add a, z
                  ret b
            """.trimIndent(),
            tgt = """
                int x, y, z
                entry:
                  a = add y, z
                  b = add x, a
                  ret b
            """.trimIndent()
        ),
        Fixture(
            id = "unmodeled-fdiv",
            title = "未建模：fdiv",
            description = "浮点除法语义未建模，整体判定为 UNMODELED，不与等价混淆。",
            src = """
                float u
                entry:
                  a = fdiv u, 2.0
                  ret a
            """.trimIndent(),
            tgt = """
                float u
                entry:
                  a = fmul u, 0.5
                  ret a
            """.trimIndent()
        )
    )

    fun byId(id: String): Fixture? = all.find { it.id == id }

    /** 掩码常量依赖位宽：渲染时按位宽替换 ${'$'}MASK 占位。 */
    fun render(ir: String, width: Int): String = ir.replace("${'$'}MASK", (width - 1).toString())
}
