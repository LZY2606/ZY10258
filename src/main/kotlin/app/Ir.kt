package app

/** 受限 SSA 片段的 IR。所有整数共享运行期选定的固定位宽。 */

sealed class Operand {
    data class Ref(val name: String) : Operand()
    data class ILit(val text: String) : Operand()
    data class FLit(val text: String) : Operand()
}

sealed class Instr {
    abstract val name: String

    data class ConstI(override val name: String, val literal: String) : Instr()
    data class ConstF(override val name: String, val literal: String) : Instr()
    data class Bin(
        override val name: String,
        val op: String, // add sub mul and or xor shl lshr ashr udiv sdiv
        val flags: Set<String>, // nsw / nuw
        val a: Operand,
        val b: Operand
    ) : Instr()

    data class ICmp(override val name: String, val pred: String, val a: Operand, val b: Operand) : Instr()
    data class FBin(override val name: String, val op: String, val a: Operand, val b: Operand) : Instr()
    data class FCmp(override val name: String, val pred: String, val a: Operand, val b: Operand) : Instr()
    data class Select(override val name: String, val cond: Operand, val t: Operand, val f: Operand) : Instr()
    data class Freeze(override val name: String, val v: Operand) : Instr()
    data class Phi(override val name: String, val incoming: List<Pair<Operand, String>>) : Instr()
}

sealed class Term {
    data class Ret(val v: Operand) : Term()
    data class Br(val cond: Operand, val ifTrue: String, val ifFalse: String) : Term()
    data class Jmp(val target: String) : Term()
}

data class Block(val name: String, val instrs: List<Instr>, val term: Term)

data class Program(
    val intParams: List<String>,
    val floatParams: List<String>,
    val blocks: List<Block>
) {
    val entry: Block get() = blocks.first()
    private val byName = blocks.associateBy { it.name }
    fun block(name: String): Block = byName[name] ?: error("unknown block $name")
}

private val intLit = Regex("-?\\d+")
private val floatLit = Regex("-?(\\d+\\.\\d*|\\d*\\.\\d+)([eE][+-]?\\d+)?|-?nan|-?inf(inity)?")

fun parseOperand(tok: String): Operand = when {
    tok.matches(intLit) -> Operand.ILit(tok)
    tok.matches(floatLit) -> Operand.FLit(tok)
    else -> Operand.Ref(tok)
}

fun parseProgram(text: String): Program {
    val intParams = mutableListOf<String>()
    val floatParams = mutableListOf<String>()
    val blocks = mutableListOf<Block>()
    var curName: String? = null
    val curInstrs = mutableListOf<Instr>()
    var curTerm: Term? = null

    fun flush() {
        val n = curName ?: return
        val t = curTerm ?: throw IllegalArgumentException("block $n missing terminator")
        blocks.add(Block(n, curInstrs.toList(), t))
        curInstrs.clear()
        curTerm = null
    }

    for (raw in text.lines()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        when {
            line.startsWith("int ") ->
                line.removePrefix("int ").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(intParams::add)
            line.startsWith("float ") ->
                line.removePrefix("float ").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(floatParams::add)
            line.endsWith(":") && !line.contains(' ') -> {
                flush()
                curName = line.removeSuffix(":")
            }
            curName == null -> throw IllegalArgumentException("content outside block: $line")
            line.startsWith("ret ") -> curTerm = Term.Ret(parseOperand(line.removePrefix("ret ").trim()))
            line.startsWith("jmp ") -> curTerm = Term.Jmp(line.removePrefix("jmp ").trim())
            line.startsWith("br ") -> {
                val parts = line.removePrefix("br ").split(',').map { it.trim() }
                require(parts.size == 3) { "br needs 3 parts: $line" }
                curTerm = Term.Br(parseOperand(parts[0]), parts[1], parts[2])
            }
            " = " in line -> {
                val lhs = line.substringBefore(" = ").trim()
                val rhs = line.substringAfter(" = ").trim()
                curInstrs.add(parseInstr(lhs, rhs))
            }
            else -> throw IllegalArgumentException("cannot parse line: $line")
        }
    }
    flush()
    require(blocks.isNotEmpty()) { "no blocks" }
    return Program(intParams, floatParams, blocks)
}

private fun parseInstr(lhs: String, rhs: String): Instr {
    val tokens = rhs.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (tokens[0]) {
        "const" -> Instr.ConstI(lhs, tokens[1])
        "fconst" -> Instr.ConstF(lhs, tokens[1])
        "add", "sub", "mul", "and", "or", "xor", "shl", "lshr", "ashr", "udiv", "sdiv" -> {
            val flags = tokens.drop(1).takeWhile { it == "nsw" || it == "nuw" }.toSet()
            val ops = tokens.drop(1 + flags.size).joinToString(" ").split(',').map { it.trim() }
            require(ops.size == 2) { "binop needs 2 operands: $rhs" }
            Instr.Bin(lhs, tokens[0], flags, parseOperand(ops[0]), parseOperand(ops[1]))
        }
        "icmp" -> {
            val ops = rhs.substringAfter(tokens[1]).split(',').map { it.trim() }
            Instr.ICmp(lhs, tokens[1], parseOperand(ops[0]), parseOperand(ops[1]))
        }
        "fadd", "fsub", "fmul", "fdiv" -> {
            val ops = rhs.substringAfter(tokens[0]).split(',').map { it.trim() }
            Instr.FBin(lhs, tokens[0], parseOperand(ops[0]), parseOperand(ops[1]))
        }
        "fcmp" -> {
            val ops = rhs.substringAfter(tokens[1]).split(',').map { it.trim() }
            Instr.FCmp(lhs, tokens[1], parseOperand(ops[0]), parseOperand(ops[1]))
        }
        "select" -> {
            val ops = rhs.removePrefix("select").split(',').map { it.trim() }
            require(ops.size == 3) { "select needs 3 operands: $rhs" }
            Instr.Select(lhs, parseOperand(ops[0]), parseOperand(ops[1]), parseOperand(ops[2]))
        }
        "freeze" -> Instr.Freeze(lhs, parseOperand(tokens[1]))
        "phi" -> {
            val body = rhs.removePrefix("phi")
            val incoming = Regex("\\[([^,]+),\\s*([A-Za-z0-9_]+)\\]").findAll(body)
                .map { parseOperand(it.groupValues[1].trim()) to it.groupValues[2].trim() }.toList()
            require(incoming.isNotEmpty()) { "phi needs incoming: $rhs" }
            Instr.Phi(lhs, incoming)
        }
        else -> throw IllegalArgumentException("unknown op: ${tokens[0]}")
    }
}

fun Program.pretty(): String {
    val sb = StringBuilder()
    if (intParams.isNotEmpty()) sb.appendLine("int " + intParams.joinToString(", "))
    if (floatParams.isNotEmpty()) sb.appendLine("float " + floatParams.joinToString(", "))
    for (b in blocks) {
        sb.appendLine(b.name + ":")
        for (i in b.instrs) sb.appendLine("  " + prettyInstr(i))
        sb.appendLine("  " + prettyTerm(b.term))
    }
    return sb.toString().trimEnd()
}

private fun prettyOp(o: Operand): String = when (o) {
    is Operand.Ref -> o.name
    is Operand.ILit -> o.text
    is Operand.FLit -> o.text
}

private fun prettyInstr(i: Instr): String = when (i) {
    is Instr.ConstI -> "${i.name} = const ${i.literal}"
    is Instr.ConstF -> "${i.name} = fconst ${i.literal}"
    is Instr.Bin -> "${i.name} = ${i.op}" + (if (i.flags.isEmpty()) "" else " " + i.flags.sorted().joinToString(" ")) +
        " ${prettyOp(i.a)}, ${prettyOp(i.b)}"
    is Instr.ICmp -> "${i.name} = icmp ${i.pred} ${prettyOp(i.a)}, ${prettyOp(i.b)}"
    is Instr.FBin -> "${i.name} = ${i.op} ${prettyOp(i.a)}, ${prettyOp(i.b)}"
    is Instr.FCmp -> "${i.name} = fcmp ${i.pred} ${prettyOp(i.a)}, ${prettyOp(i.b)}"
    is Instr.Select -> "${i.name} = select ${prettyOp(i.cond)}, ${prettyOp(i.t)}, ${prettyOp(i.f)}"
    is Instr.Freeze -> "${i.name} = freeze ${prettyOp(i.v)}"
    is Instr.Phi -> "${i.name} = phi " + i.incoming.joinToString(", ") { "[${prettyOp(it.first)}, ${it.second}]" }
}

private fun prettyTerm(t: Term): String = when (t) {
    is Term.Ret -> "ret ${prettyOp(t.v)}"
    is Term.Br -> "br ${prettyOp(t.cond)}, ${t.ifTrue}, ${t.ifFalse}"
    is Term.Jmp -> "jmp ${t.target}"
}
