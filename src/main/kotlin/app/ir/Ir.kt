package app.ir

/** Types of the restricted SSA IR. */
sealed interface Ty {
    data class IntT(val width: Int) : Ty {
        init { require(width in 1..64) { "int width must be in 1..64" } }
        override fun toString() = "i$width"
    }
    data object F32 : Ty { override fun toString() = "f32" }
}

val B1 = Ty.IntT(1)

/** Concrete runtime values. IntV.bits is always masked to `width` bits. */
sealed interface Val {
    data class IntV(val width: Int, val bits: Long) : Val {
        init { require(width in 1..64) }
        val masked: Long get() = bits and mask(width)
        val signed: Long get() = signExtend(masked, width)
        fun render(): String = "i$width ${signed} (0x${(masked and mask(width)).toString(16)})"
    }
    data class FloatV(val f: Float) : Val {
        fun render(): String = when {
            f.isNaN() -> "f32 NaN"
            f == Float.POSITIVE_INFINITY -> "f32 +Inf"
            f == Float.NEGATIVE_INFINITY -> "f32 -Inf"
            else -> "f32 $f"
        }
    }
    data object Poison : Val { override fun toString() = "poison" }
}

fun mask(width: Int): Long = if (width == 64) -1L else (1L shl width) - 1L

fun signExtend(bits: Long, width: Int): Long =
    if (width == 64) bits else (bits shl (64 - width)) shr (64 - width)

/** Parse an integer constant at a fixed width: value is reduced modulo 2^width.
 *  No host big-integer semantics leak in: e.g. const 256 in i8 is 0. */
fun intConst(width: Int, value: Long): Val.IntV = Val.IntV(width, value and mask(width))

enum class IntOp { ADD, SUB, MUL, AND, OR, XOR, SHL, LSHR, ASHR }
enum class CmpPred { EQ, NE, SLT, SLE, SGT, SGE, ULT, ULE, UGT, UGE }
enum class FloatOp { FADD, FSUB, FMUL }
enum class FCmpPred { OEQ, OGT, OGE, OLT, OLE, ONE, UEQ, ORD }

sealed interface Instr {
    val name: String

    data class Const(override val name: String, val ty: Ty, val intValue: Long = 0, val floatValue: Float = 0f) : Instr
    data class IBin(override val name: String, val ty: Ty.IntT, val op: IntOp,
                    val a: String, val b: String, val nsw: Boolean = false, val nuw: Boolean = false) : Instr
    data class ICmp(override val name: String, val pred: CmpPred, val a: String, val b: String) : Instr
    data class FBin(override val name: String, val op: FloatOp, val a: String, val b: String) : Instr
    data class FCmp(override val name: String, val pred: FCmpPred, val a: String, val b: String) : Instr
    data class Select(override val name: String, val cond: String, val t: String, val f: String) : Instr
    data class Freeze(override val name: String, val ty: Ty, val v: String) : Instr
    data class Phi(override val name: String, val ty: Ty, val incomings: List<Pair<String, String>>) : Instr
    /** Operation the semantics deliberately does not model (calls, atomics, ...). */
    data class Unmodeled(override val name: String, val hint: String) : Instr
}

sealed interface Term {
    data class Jmp(val target: String) : Term
    data class Br(val cond: String, val thenB: String, val elseB: String) : Term
    data class Ret(val value: String) : Term
}

data class Param(val name: String, val ty: Ty)

data class Block(val label: String, val instrs: List<Instr>, val term: Term)

data class Fragment(val name: String, val params: List<Param>, val body: List<Block>, val resultTy: Ty) {
    val entry: Block get() = body.first()
    fun block(label: String): Block = body.first { it.label == label }
    fun allInstrs(): List<Instr> = body.flatMap { it.instrs }
    fun containsUnmodeled(): Boolean = allInstrs().any { it is Instr.Unmodeled }
}

/** Render a fragment as LLVM-flavoured text for the UI. */
fun render(f: Fragment): String = buildString {
    append("define ").append(f.resultTy).append(" @").append(f.name).append('(')
    append(f.params.joinToString(", ") { "${it.ty} %${it.name}" }).appendLine(") {")
    for (b in f.body) {
        append(b.label).appendLine(":")
        for (i in b.instrs) append("  ").appendLine(renderInstr(i))
        append("  ").appendLine(renderTerm(b.term))
    }
    append('}')
}

fun renderInstr(i: Instr): String = when (i) {
    is Instr.Const -> when (i.ty) {
        is Ty.IntT -> "%${i.name} = const ${i.ty} ${signExtend(i.intValue and mask(i.ty.width), i.ty.width)}"
        Ty.F32 -> "%${i.name} = const f32 ${i.floatValue}"
    }
    is Instr.IBin -> buildString {
        append("%${i.name} = ${i.op.name.lowercase()}")
        if (i.nsw) append(" nsw")
        if (i.nuw) append(" nuw")
        append(" ${i.ty} %${i.a}, %${i.b}")
    }
    is Instr.ICmp -> "%${i.name} = icmp ${i.pred.name.lowercase()} %${i.a}, %${i.b}"
    is Instr.FBin -> "%${i.name} = ${i.op.name.lowercase()} f32 %${i.a}, %${i.b}"
    is Instr.FCmp -> "%${i.name} = fcmp ${i.pred.name.lowercase()} f32 %${i.a}, %${i.b}"
    is Instr.Select -> "%${i.name} = select i1 %${i.cond}, %${i.t}, %${i.f}"
    is Instr.Freeze -> "%${i.name} = freeze ${i.ty} %${i.v}"
    is Instr.Phi -> "%${i.name} = phi ${i.ty} " + i.incomings.joinToString(", ") { "[ %${it.first}, %${it.second} ]" }
    is Instr.Unmodeled -> "%${i.name} = unmodeled \"${i.hint}\""
}

fun renderTerm(t: Term): String = when (t) {
    is Term.Jmp -> "jmp %${t.target}"
    is Term.Br -> "br i1 %${t.cond}, %${t.thenB}, %${t.elseB}"
    is Term.Ret -> "ret %${t.value}"
}
