package uk.m4xy.fckt.intellij

/**
 * What a word is that its tokens alone tell: a keyword only in place, a named argument, or an
 * annotation. What a name means, and so its colour, is the language server's to say.
 */
enum class Role { KEYWORD, NAMED_ARGUMENT, ANNOTATION }

data class Span(val start: Int, val end: Int, val role: Role)

fun semantics(text: String): List<Span> {
    val code = scan(text).filter { it.kind != TokKind.WHITESPACE && it.kind != TokKind.LINE_COMMENT && it.kind != TokKind.BLOCK_COMMENT }
    fun word(t: Tok?) = t?.let { text.substring(it.start, it.end) }
    fun sameLine(a: Tok, b: Tok) = text.substring(a.end, b.start).none { it == '\n' }
    // Whether [t] is on a line that starts with `import`.
    fun importLine(t: Tok): Boolean {
        val lineStart = text.lastIndexOf('\n', t.start - 1) + 1
        return text.substring(lineStart, t.start).trimStart().startsWith("import ")
    }
    val spans = mutableListOf<Span>()
    // What's open around each token: named arguments are only directly in a call's parentheses.
    val open = ArrayDeque<TokKind>()
    for ((i, t) in code.withIndex()) {
        when (t.kind) {
            TokKind.LPAREN, TokKind.LBRACE, TokKind.LBRACKET -> open.addLast(t.kind)
            TokKind.RPAREN, TokKind.RBRACE, TokKind.RBRACKET -> open.removeLastOrNull()
            else -> {}
        }
        if (t.kind != TokKind.IDENT) continue
        val name = word(t)!!
        val prev = code.getOrNull(i - 1)
        val next = code.getOrNull(i + 1)
        val role: Role? = when {
            prev?.kind == TokKind.AT -> Role.ANNOTATION
            (name == "spec" || name == "effect") && word(next)?.firstOrNull()?.isUpperCase() == true -> Role.KEYWORD
            // `import a.b.C as D`, at a line's start.
            name == "import" && next?.kind == TokKind.IDENT && (prev == null || !sameLine(prev, t)) -> Role.KEYWORD
            name == "as" && next?.kind == TokKind.IDENT && importLine(t) -> Role.KEYWORD
            (name == "opaque" || name == "annotation") && word(next) == "type" -> Role.KEYWORD
            // `internal` before what it declares, at a line's start: a function may still be called internal.
            name == "internal" && next != null && sameLine(t, next) && (prev == null || !sameLine(prev, t)) &&
                next.kind !in setOf(TokKind.LPAREN, TokKind.COLON, TokKind.ASSIGN, TokKind.DOT, TokKind.OPERATOR) -> Role.KEYWORD
            // `operator Set<A>.plus(...)`: before the type it's an operator of, at a line's start.
            name == "operator" && word(next)?.firstOrNull()?.isUpperCase() == true && sameLine(t, next!!) &&
                (prev == null || !sameLine(prev, t) || word(prev) == "private" || word(prev) == "internal") -> Role.KEYWORD
            (name == "var" || name == "vararg") && next?.kind == TokKind.IDENT && sameLine(t, next) -> Role.KEYWORD
            name == "external" && next != null && (next.kind == TokKind.IDENT || next.kind == TokKind.LPAREN || word(next) == "type") &&
                (prev == null || !sameLine(prev, t) || word(prev) == "private" || word(prev) == "internal" || word(prev) == "operator") -> Role.KEYWORD
            name == "by" && prev != null && sameLine(prev, t) && (prev.kind == TokKind.IDENT || prev.kind == TokKind.RPAREN) &&
                next != null && sameLine(t, next) && next.kind !in setOf(TokKind.ASSIGN, TokKind.DOT, TokKind.COMMA, TokKind.RPAREN, TokKind.LPAREN, TokKind.COLON) -> Role.KEYWORD
            next?.kind == TokKind.ASSIGN && open.lastOrNull() == TokKind.LPAREN && (prev?.kind == TokKind.LPAREN || prev?.kind == TokKind.COMMA) -> Role.NAMED_ARGUMENT
            else -> null
        }
        if (role != null) spans += Span(t.start, t.end, role)
    }
    return spans
}
