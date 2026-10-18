package uk.m4xy.fckt.syntax

enum class T {
    IDENT, INT, FLOAT, STRING, NEWLINE, EOF,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, DOT, COLON, SEMI, ASSIGN, ARROW, PIPE, TILDE, BANG, UNDERSCORE,
    PLUS, MINUS, STAR, SLASH, PERCENT, EQ, NEQ, LT, GT, LE, GE, AND, OR,
    TYPE, INTERFACE, WHEN, IF, ELSE, IS, TRUE, FALSE,
}

/** A string literal's pieces: literal text, or the source of a `$name` / `${expr}` interpolation. */
sealed interface RawPart {
    data class Text(val text: String) : RawPart
    data class Code(val source: String, val pos: Pos) : RawPart
}

data class Token(val kind: T, val text: String, val pos: Pos, val parts: List<RawPart> = emptyList())

private val KEYWORDS = mapOf(
    "type" to T.TYPE, "interface" to T.INTERFACE, "when" to T.WHEN, "if" to T.IF, "else" to T.ELSE,
    "is" to T.IS, "true" to T.TRUE, "false" to T.FALSE,
)

/** Tokens after which a line break never ends a statement. */
private val CONTINUES_LINE = setOf(
    T.COMMA, T.DOT, T.COLON, T.ASSIGN, T.ARROW, T.PIPE, T.TILDE, T.BANG, T.PLUS, T.MINUS, T.STAR, T.SLASH,
    T.PERCENT, T.EQ, T.NEQ, T.LT, T.GT, T.LE, T.GE, T.AND, T.OR, T.LPAREN, T.LBRACKET, T.LBRACE,
    T.SEMI, T.NEWLINE,
)

/**
 * Splits source into tokens. Line breaks are significant only directly inside braces (or at the
 * top level), and not after an operator or before a line that starts with `.` or `|>`.
 */
class Lexer(private val src: String, private val origin: Pos = Pos(1, 1)) {
    private var i = 0
    private var line = origin.line
    private var col = origin.col
    private val tokens = mutableListOf<Token>()
    private val delimiters = ArrayDeque<T>()

    fun tokenize(): List<Token> {
        while (true) {
            skipSpaceAndComments()
            if (i >= src.length) break
            val c = src[i]
            val pos = Pos(line, col)
            when {
                c == '\n' -> {
                    advance()
                    newline(pos)
                }
                c.isLetter() || c == '_' -> identifier(pos)
                c.isDigit() -> number(pos)
                c == '"' -> string(pos)
                else -> symbol(pos)
            }
        }
        add(Token(T.EOF, "", Pos(line, col)))
        return tokens
    }

    private fun newline(pos: Pos) {
        val inBraces = delimiters.isEmpty() || delimiters.last() == T.LBRACE
        if (!inBraces || tokens.isEmpty() || tokens.last().kind in CONTINUES_LINE) return
        if (nextLineContinues()) return
        add(Token(T.NEWLINE, "\n", pos))
    }

    private fun nextLineContinues(): Boolean {
        var j = i
        while (true) {
            while (j < src.length && src[j].isWhitespace()) j++
            if (src.startsWith("//", j)) {
                while (j < src.length && src[j] != '\n') j++
                continue
            }
            break
        }
        return (src.startsWith(".", j) && !src.startsWith("..", j)) || src.startsWith("|>", j)
    }

    private fun skipSpaceAndComments() {
        while (i < src.length) {
            when {
                src[i] == ' ' || src[i] == '\t' || src[i] == '\r' -> advance()
                src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') advance()
                src.startsWith("/*", i) -> {
                    val start = Pos(line, col)
                    advance(2)
                    while (i < src.length && !src.startsWith("*/", i)) advance()
                    if (i >= src.length) throw CompileError(start, "unterminated comment")
                    advance(2)
                }
                else -> return
            }
        }
    }

    private fun identifier(pos: Pos) {
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) advance()
        val text = src.substring(start, i)
        val kind = if (text == "_") T.UNDERSCORE else KEYWORDS[text] ?: T.IDENT
        add(Token(kind, text, pos))
    }

    private fun number(pos: Pos) {
        val start = i
        while (i < src.length && (src[i].isDigit() || src[i] == '_')) advance()
        var float = false
        if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
            float = true
            advance()
            while (i < src.length && src[i].isDigit()) advance()
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            float = true
            advance()
            if (i < src.length && (src[i] == '+' || src[i] == '-')) advance()
            while (i < src.length && src[i].isDigit()) advance()
        }
        add(Token(if (float) T.FLOAT else T.INT, src.substring(start, i).replace("_", ""), pos))
    }

    private fun string(pos: Pos) {
        advance()
        val parts = mutableListOf<RawPart>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) parts += RawPart.Text(text.toString())
            text.clear()
        }
        while (true) {
            if (i >= src.length || src[i] == '\n') throw CompileError(pos, "unterminated string")
            val c = src[i]
            when {
                c == '"' -> {
                    advance()
                    break
                }
                c == '\\' -> {
                    advance()
                    val e = src.getOrNull(i) ?: throw CompileError(pos, "unterminated string")
                    text.append(
                        when (e) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'; '$' -> '$'
                            else -> throw CompileError(Pos(line, col), "unknown escape \\$e")
                        },
                    )
                    advance()
                }
                c == '$' && src.getOrNull(i + 1) == '{' -> {
                    flush()
                    advance(2)
                    val codePos = Pos(line, col)
                    val start = i
                    var depth = 1
                    while (i < src.length) {
                        if (src[i] == '{') depth++
                        if (src[i] == '}' && --depth == 0) break
                        if (src[i] == '\n') throw CompileError(pos, "unterminated string template")
                        advance()
                    }
                    if (i >= src.length) throw CompileError(pos, "unterminated string template")
                    parts += RawPart.Code(src.substring(start, i), codePos)
                    advance()
                }
                c == '$' && (src.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true) -> {
                    flush()
                    advance()
                    val codePos = Pos(line, col)
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) advance()
                    parts += RawPart.Code(src.substring(start, i), codePos)
                }
                else -> {
                    text.append(c)
                    advance()
                }
            }
        }
        flush()
        add(Token(T.STRING, "", pos, parts))
    }

    private fun symbol(pos: Pos) {
        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
        val twoKind = when (two) {
            "->" -> T.ARROW; "|>" -> T.PIPE; "==" -> T.EQ; "!=" -> T.NEQ; "<=" -> T.LE; ">=" -> T.GE
            "&&" -> T.AND; "||" -> T.OR
            else -> null
        }
        if (twoKind != null) {
            advance(2)
            add(Token(twoKind, two, pos))
            return
        }
        val kind = when (src[i]) {
            '(' -> T.LPAREN; ')' -> T.RPAREN; '{' -> T.LBRACE; '}' -> T.RBRACE; '[' -> T.LBRACKET; ']' -> T.RBRACKET
            ',' -> T.COMMA; '.' -> T.DOT; ':' -> T.COLON; ';' -> T.SEMI; '=' -> T.ASSIGN; '~' -> T.TILDE
            '!' -> T.BANG; '+' -> T.PLUS; '-' -> T.MINUS; '*' -> T.STAR; '/' -> T.SLASH; '%' -> T.PERCENT
            '<' -> T.LT; '>' -> T.GT
            else -> throw CompileError(pos, "unexpected character '${src[i]}'")
        }
        val text = src[i].toString()
        advance()
        when (kind) {
            T.LPAREN, T.LBRACE, T.LBRACKET -> delimiters.addLast(kind)
            T.RPAREN, T.RBRACE, T.RBRACKET -> {
                delimiters.removeLastOrNull()
                // `}` ends whatever statement was open on its line.
                if (kind == T.RBRACE && tokens.lastOrNull()?.kind == T.NEWLINE) tokens.removeLast()
            }
            else -> {}
        }
        add(Token(kind, text, pos))
    }

    private fun add(token: Token) {
        tokens += token
    }

    private fun advance(n: Int = 1) {
        repeat(n) {
            if (src[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            i++
        }
    }
}
