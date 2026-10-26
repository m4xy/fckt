package uk.m4xy.fckt.intellij

/** What a [Tok] is, for colouring; whitespace and comments are tokens too, so every character is in one. */
enum class TokKind {
    WHITESPACE, LINE_COMMENT, BLOCK_COMMENT,
    KEYWORD, IDENT, NUMBER,
    STRING, CHAR, ESCAPE, TEMPLATE,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, DOT, COLON, SEMI, ARROW, ASSIGN, OPERATOR, AT,
    BAD,
}

/** A token from [start] to [end]; [nested] if it's inside a string, where lexing can't restart. */
data class Tok(val kind: TokKind, val start: Int, val end: Int, val nested: Boolean)

/** Words that are keywords wherever they appear. `spec`, `external`, `opaque`, `internal`, `operator`, `annotation` and `by` are keywords only in place. */
val KEYWORDS = setOf("type", "module", "private", "when", "if", "else", "is", "true", "false", "this")

/**
 * Splits fckt source into tokens for highlighting. Unlike the compiler's lexer, it never fails:
 * an unterminated string or comment runs to the end, and an unknown character is [TokKind.BAD].
 * Strings hold `$name` and `${code}` templates, whose code is tokenized as code.
 */
fun scan(text: CharSequence, from: Int = 0, until: Int = text.length): List<Tok> {
    // Never past the text, whatever range is asked for.
    val to = until.coerceIn(0, text.length)
    val toks = mutableListOf<Tok>()
    // What's open: a string, a raw string, or a template's code, with how many of its own braces are open.
    class Frame(val kind: Char, var braces: Int = 0)
    val frames = ArrayDeque<Frame>()
    var i = from.coerceIn(0, to)
    fun add(kind: TokKind, start: Int, end: Int) {
        if (end > start) toks += Tok(kind, start, end, frames.isNotEmpty())
    }
    fun at(s: String) = text.regionMatches(i, s, 0, s.length)

    // `$name` or `${`: a template inside a string. True if there was one.
    fun template(): Boolean {
        if (text[i] != '$' || i + 1 >= to) return false
        val next = text[i + 1]
        if (next == '{') {
            add(TokKind.TEMPLATE, i, i + 2)
            i += 2
            frames.addLast(Frame('c'))
            return true
        }
        if (!next.isLetter() && next != '_') return false
        add(TokKind.TEMPLATE, i, i + 1)
        val start = ++i
        while (i < to && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        add(TokKind.IDENT, start, i)
        return true
    }

    while (i < to) {
        val frame = frames.lastOrNull()
        if (frame != null && frame.kind != 'c') {
            // Inside a string: text up to an escape, a template or the end.
            val raw = frame.kind == 'r'
            when {
                raw && at("\"\"\"") && !text.regionMatches(i + 1, "\"\"\"", 0, 3) -> {
                    frames.removeLast()
                    add(TokKind.STRING, i, i + 3)
                    i += 3
                }
                !raw && text[i] == '"' -> {
                    frames.removeLast()
                    add(TokKind.STRING, i, i + 1)
                    i++
                }
                !raw && text[i] == '\n' -> frames.removeLast() // unterminated: the line ends it
                !raw && text[i] == '\\' -> {
                    add(TokKind.ESCAPE, i, minOf(i + 2, to))
                    i = minOf(i + 2, to)
                }
                template() -> {}
                else -> {
                    val start = i
                    while (i < to && !(text[i] == '$' || (raw && at("\"\"\"")) || (!raw && (text[i] == '"' || text[i] == '\\' || text[i] == '\n')))) i++
                    if (i == start) i++ // a `$` that starts no template
                    add(TokKind.STRING, start, i)
                }
            }
            continue
        }
        val c = text[i]
        val start = i
        when {
            c.isWhitespace() -> {
                while (i < to && text[i].isWhitespace()) i++
                add(TokKind.WHITESPACE, start, i)
            }
            at("//") -> {
                while (i < to && text[i] != '\n') i++
                add(TokKind.LINE_COMMENT, start, i)
            }
            at("/*") -> {
                i += 2
                while (i < to && !at("*/")) i++
                i = minOf(i + 2, to)
                add(TokKind.BLOCK_COMMENT, start, i)
            }
            c.isLetter() || c == '_' -> {
                while (i < to && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                add(if (text.substring(start, i) in KEYWORDS) TokKind.KEYWORD else TokKind.IDENT, start, i)
            }
            c.isDigit() -> {
                // 1_000, 2.5 and 1e-6, as the compiler reads them.
                while (i < to && (text[i].isDigit() || text[i] == '_')) i++
                if (i + 1 < to && text[i] == '.' && text[i + 1].isDigit()) i++
                while (i < to && text[i].isDigit()) i++
                if (i < to && (text[i] == 'e' || text[i] == 'E')) {
                    i++
                    if (i < to && (text[i] == '+' || text[i] == '-')) i++
                }
                while (i < to && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                add(TokKind.NUMBER, start, i)
            }
            at("\"\"\"") -> {
                i += 3
                add(TokKind.STRING, start, i)
                frames.addLast(Frame('r'))
            }
            c == '"' -> {
                i++
                add(TokKind.STRING, start, i)
                frames.addLast(Frame('s'))
            }
            c == '\'' -> {
                i++
                if (i < to && text[i] == '\\') {
                    i++
                    // \u00e9, or \u{1F600}.
                    if (i < to && text[i] == 'u') {
                        i++
                        if (i < to && text[i] == '{') {
                            while (i < to && text[i] != '}' && text[i] != '\'' && text[i] != '\n') i++
                            if (i < to && text[i] == '}') i++
                        } else {
                            repeat(4) { if (i < to && text[i].isLetterOrDigit()) i++ }
                        }
                    } else if (i < to) {
                        i++
                    }
                } else if (i < to && text[i] != '\n') {
                    // One code point, which may be two of Kotlin's chars.
                    i += Character.charCount(Character.codePointAt(text, i))
                }
                if (i < to && text[i] == '\'') i++
                add(TokKind.CHAR, minOf(start, to), minOf(i, to))
            }
            else -> {
                val two = if (i + 1 < to) text.substring(i, i + 2) else ""
                val (kind, length) = when (two) {
                    "->" -> TokKind.ARROW to 2
                    "|>", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=" -> TokKind.OPERATOR to 2
                    else -> when (c) {
                        '(' -> TokKind.LPAREN to 1
                        ')' -> TokKind.RPAREN to 1
                        '{' -> TokKind.LBRACE to 1
                        '}' -> TokKind.RBRACE to 1
                        '[' -> TokKind.LBRACKET to 1
                        ']' -> TokKind.RBRACKET to 1
                        ',' -> TokKind.COMMA to 1
                        '.' -> TokKind.DOT to 1
                        ':' -> TokKind.COLON to 1
                        ';' -> TokKind.SEMI to 1
                        '=' -> TokKind.ASSIGN to 1
                        '@' -> TokKind.AT to 1
                        '+', '-', '*', '/', '%', '<', '>', '!', '~', '|', '&' -> TokKind.OPERATOR to 1
                        else -> TokKind.BAD to 1
                    }
                }
                // A template's code ends at the brace that closes it.
                if (frame != null && kind == TokKind.LBRACE) frame.braces++
                if (frame != null && kind == TokKind.RBRACE && frame.braces-- == 0) {
                    frames.removeLast()
                    add(TokKind.TEMPLATE, i, i + 1)
                    i++
                    continue
                }
                i += length
                add(kind, start, i)
            }
        }
    }
    return toks
}
