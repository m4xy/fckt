package uk.m4xy.fckt.ocaml

import uk.m4xy.kastor.ResolvedNode

/**
 * Prints a resolved OCaml tree as source, adding the parentheses OCaml's grammar needs.
 *
 * Constructs that extend as far right as they can (`fun`, `let ... in`, `if`, `match`) and
 * sequences are parenthesized unless the position allows them, which [Ctx] tracks.
 */
object Printer {
    private const val WIDTH = 80

    /** What may appear unparenthesized here: open-ended constructs, `a; b`, and `match` (which would swallow later cases). */
    private data class Ctx(val open: Boolean, val seq: Boolean, val match: Boolean)

    private val FULL = Ctx(open = true, seq = true, match = true)
    private val CLOSED = Ctx(open = false, seq = false, match = false)

    private const val ATOM = 100
    private const val FIELD = 95
    private const val APPLY = 90

    /** OCaml's binary operator precedences, and whether each is right-associative. */
    private fun infixLevel(op: String): Pair<Int, Boolean> = when (op) {
        "*", "/", "*.", "/.", "mod" -> 70 to false
        "+", "-", "+.", "-." -> 65 to false
        "::" -> 62 to true
        "^", "@" -> 60 to true
        "=", "<>", "<", ">", "<=", ">=", "|>", "==", "!=" -> 55 to false
        "&&" -> 50 to true
        "||" -> 45 to true
        else -> 55 to false
    }

    fun print(root: ResolvedNode): String = root.children.joinToString("\n\n") { item(it) } + "\n"

    private fun ResolvedNode.str(key: String) = attributes[key] as String

    @Suppress("UNCHECKED_CAST")
    private fun ResolvedNode.list(key: String) = attributes[key] as List<String>? ?: emptyList()

    // ---- Structure items ----

    private fun item(n: ResolvedNode): String = when (n.name) {
        "comment" -> "(* ${n.str("text")} *)"
        "types" -> "type " + n.children.joinToString("\n\nand ") { typeDef(it) }
        "let" -> (if (n.attributes["rec"] == true) "let rec " else "let ") + n.children.joinToString("\n\nand ") { binding(it) }
        else -> error("unexpected ${n.name} at the top level")
    }

    private fun typeParams(params: List<String>) = when (params.size) {
        0 -> ""
        1 -> params[0] + " "
        else -> "(${params.joinToString(", ")}) "
    }

    private fun typeDef(n: ResolvedNode): String {
        val head = typeParams(n.list("params")) + n.str("name") + " ="
        val first = n.children.firstOrNull() ?: error("type ${n.str("name")} is empty")
        if (n.attributes["polymorphic"] == true) {
            val tags = n.children.map { v ->
                "`" + v.str("name") + if (v.children.isEmpty()) "" else " of " + v.children.joinToString(" * ") { tupleElement(it.str("type")) }
            }
            val oneLine = "$head [ ${tags.joinToString(" | ")} ]"
            return if (oneLine.length <= WIDTH) oneLine else "$head [\n" + tags.joinToString("\n") { "  | $it" } + "\n]"
        }
        return if (first.name == "field-decl") {
            head + " " + fields(n.children)
        } else {
            head + n.children.joinToString("") { v ->
                "\n  | " + v.str("name") + if (v.children.isEmpty()) "" else " of " + fields(v.children)
            }
        }
    }

    /** A type inside `a * b`, parenthesized if it's a function or tuple itself. */
    private fun tupleElement(type: String) = if ("->" in type || " * " in type) "($type)" else type

    private fun fields(children: List<ResolvedNode>): String {
        val parts = children.map { "${it.str("name")} : ${it.str("type")}" }
        val oneLine = "{ ${parts.joinToString("; ")} }"
        return if (oneLine.length <= 60) oneLine else "{\n" + parts.joinToString("") { "    $it;\n" } + "  }"
    }

    private fun binding(n: ResolvedNode): String {
        val header = (listOf(n.str("name")) + n.list("params")).joinToString(" ") +
            ((n.attributes["returnType"] as String?)?.let { " : $it" } ?: "") + " ="
        val body = expr(n.children.single(), FULL)
        return if ('\n' !in body && header.length + body.length < WIDTH) "$header $body" else "$header\n${indent(body)}"
    }

    // ---- Expressions ----

    private fun precedence(n: ResolvedNode): Int = when (n.name) {
        "ident", "record", "list", "annotated", "tuple", "record-update" -> ATOM
        "literal" -> if (n.str("text").startsWith("-")) APPLY - 5 else ATOM
        "field" -> FIELD
        "apply" -> APPLY
        "construct" -> if (n.children.isEmpty()) ATOM else APPLY
        "infix" -> infixLevel(n.str("op")).first
        else -> 0
    }

    private fun needsParens(n: ResolvedNode, min: Int, ctx: Ctx): Boolean = when (n.name) {
        "lambda", "let-in", "if" -> !ctx.open
        "match" -> !ctx.open || !ctx.match
        "sequence" -> !ctx.seq
        else -> precedence(n) < min
    }

    /** [n] in a position that needs at least precedence [min] and allows [ctx]. */
    private fun sub(n: ResolvedNode, min: Int, ctx: Ctx = CLOSED): String =
        if (needsParens(n, min, ctx)) parens(expr(n, FULL)) else expr(n, ctx)

    private fun parens(s: String) = "(" + s.replace("\n", "\n ") + ")"

    private fun expr(n: ResolvedNode, ctx: Ctx): String = when (n.name) {
        "ident" -> n.str("name")
        "literal" -> n.str("text")
        "apply" -> {
            val fn = sub(n.children[0], APPLY)
            val args = n.children.drop(1).map { sub(it, FIELD) }
            hang((listOf(fn) + args).joinToString(" "))
        }
        "construct" -> n.children.singleOrNull()?.let { n.str("name") + " " + sub(it, FIELD) } ?: n.str("name")
        "field" -> sub(n.children.single(), FIELD) + "." + n.str("name")
        "annotated" -> "(" + expr(n.children.single(), FULL) + (if (n.attributes["coerce"] == true) " :> " else " : ") + n.str("type") + ")"
        "infix" -> infix(n)
        "lambda" -> {
            val head = "fun ${n.list("params").joinToString(" ")} ->"
            val body = expr(n.children.single(), ctx)
            if ('\n' !in body && head.length + body.length < WIDTH) "$head $body" else "$head\n${indent(body)}"
        }
        "let-in" -> {
            val rec = if (n.attributes["rec"] == true) "rec " else ""
            val head = "let $rec" + (listOf(n.str("name")) + n.list("params")).joinToString(" ") + " ="
            val value = expr(n.children[0], FULL)
            val body = expr(n.children[1], ctx)
            if ('\n' !in value && head.length + value.length < WIDTH) "$head $value in\n$body"
            else "$head\n${indent(value)}\nin\n$body"
        }
        "sequence" -> n.children.mapIndexed { i, child ->
            if (i == n.children.lastIndex) sub(child, 0, ctx) else sub(child, 0)
        }.joinToString(";\n")
        "if" -> {
            val (c, t, e) = n.children
            val cond = sub(c, 0)
            val then = sub(t, 0)
            val otherwise = sub(e, 0, ctx.copy(seq = false))
            val oneLine = "if $cond then $then else $otherwise"
            when {
                '\n' !in oneLine && oneLine.length <= WIDTH -> oneLine
                otherwise.startsWith("if ") -> "if $cond then\n${indent(then)}\nelse $otherwise"
                else -> "if $cond then\n${indent(then)}\nelse\n${indent(otherwise)}"
            }
        }
        "match" -> {
            val subject = sub(n.children[0], 0)
            val cases = n.children.drop(1)
            "match $subject with" + cases.mapIndexed { i, case ->
                val bodyCtx = Ctx(open = true, seq = true, match = i == cases.lastIndex && ctx.match)
                val body = sub(case.children.single(), 0, bodyCtx)
                val head = "\n| ${case.str("pattern")} ->"
                if ('\n' !in body && head.length + body.length < WIDTH) "$head $body" else "$head\n${indent(body, 4)}"
            }.joinToString("")
        }
        "record" -> collection("{ ", " }", n.children.map { entry -> entry.str("name") + " = " + sub(entry.children.single(), 0) })
        "list" -> collection("[", "]", n.children.map { sub(it, 0) })
        "tuple" -> collection("(", ")", n.children.map { sub(it, 0) }, ", ")
        "record-update" -> collection(
            "{ " + sub(n.children[0], FIELD) + " with ",
            " }",
            n.children.drop(1).map { entry -> entry.str("name") + " = " + sub(entry.children.single(), 0) },
        )
        else -> error("unexpected ${n.name} in an expression")
    }

    private fun infix(n: ResolvedNode): String {
        val op = n.str("op")
        val (level, right) = infixLevel(op)
        val l = sub(n.children[0], if (right) level + 1 else level)
        val r = sub(n.children[1], if (right) level else level + 1)
        val oneLine = "$l $op $r"
        // Long pipelines and concatenations read best one operand per line.
        val breakable = op == "|>" || op == "^" || op == "&&" || op == "||"
        return if (breakable && ('\n' in oneLine || oneLine.length > WIDTH)) "$l\n$op $r" else oneLine
    }

    private fun collection(open: String, close: String, items: List<String>, separator: String = "; "): String {
        if (items.isEmpty()) return open.trim() + close.trim()
        val oneLine = open + items.joinToString(separator) + close
        if ('\n' !in oneLine && oneLine.length <= WIDTH) return oneLine
        return open.trim() + "\n" + items.joinToString(separator.trim() + "\n") { indent(it) } + "\n" + close.trim()
    }

    /** Indents every line after the first, for a multi-line expression that continues a line. */
    private fun hang(s: String) = s.replace("\n", "\n  ")

    private fun indent(s: String, by: Int = 2): String {
        val pad = " ".repeat(by)
        return s.lines().joinToString("\n") { if (it.isBlank()) it else pad + it }
    }
}
