package uk.m4xy.fckt.syntax

enum class T {
    IDENT, INT, FLOAT, STRING, CHAR, NEWLINE, EOF,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, DOT, COLON, SEMI, ASSIGN, ARROW, PIPE, TILDE, BANG, UNDERSCORE,
    PLUS, MINUS, STAR, SLASH, PERCENT, EQ, NEQ, LT, GT, LE, GE, AND, OR,
    TYPE, MODULE, PRIVATE, WHEN, IF, ELSE, IS, TRUE, FALSE,
}

/** A string literal's pieces: literal text, or the source of a `$name` / `${expr}` interpolation. */
sealed interface RawPart {
    data class Text(val text: String) : RawPart
    data class Code(val source: String, val pos: Pos) : RawPart
}

data class Token(val kind: T, val text: String, val pos: Pos, val parts: List<RawPart> = emptyList())

private val KEYWORDS = mapOf(
    "type" to T.TYPE, "when" to T.WHEN, "if" to T.IF, "else" to T.ELSE,
    "is" to T.IS, "true" to T.TRUE, "false" to T.FALSE, "module" to T.MODULE, "private" to T.PRIVATE,
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
                src.startsWith("\"\"\"", i) -> rawString(pos)
                c == '"' -> string(pos)
                c == '\'' -> char(pos)
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

    private fun escape(pos: Pos): Char {
        val e = src.getOrNull(i) ?: throw CompileError(pos, "unterminated literal")
        advance()
        return when (e) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '"' -> '"'; '\'' -> '\''; '$' -> '$'
            else -> throw CompileError(Pos(line, col - 1), "unknown escape \\$e")
        }
    }

    private fun char(pos: Pos) {
        advance()
        val c = when (src.getOrNull(i)) {
            null, '\n', '\'' -> throw CompileError(pos, "empty or unterminated character literal")
            '\\' -> {
                advance()
                escape(pos)
            }
            else -> src[i].also { advance() }
        }
        if (src.getOrNull(i) != '\'') throw CompileError(pos, "a character literal holds one character")
        advance()
        add(Token(T.CHAR, c.toString(), pos))
    }

    /** `\"\"\"text\"\"\"`: no escapes and may span lines, but `$name` and `${expr}` still interpolate, as in Kotlin. */
    private fun rawString(pos: Pos) {
        advance(3)
        val parts = mutableListOf<RawPart>()
        val text = StringBuilder()
        while (true) {
            if (i >= src.length) throw CompileError(pos, "unterminated raw string")
            if (src.startsWith("\"\"\"", i) && !src.startsWith("\"\"\"\"", i)) {
                advance(3)
                break
            }
            if (!template(parts, text, pos)) {
                text.append(src[i])
                advance()
            }
        }
        if (text.isNotEmpty()) parts += RawPart.Text(text.toString())
        add(Token(T.STRING, "", pos, parts))
    }

    /** Reads a `$name` or `${expr}` at the cursor into [parts], first flushing [text]; false if there's none. */
    private fun template(parts: MutableList<RawPart>, text: StringBuilder, pos: Pos): Boolean {
        val c = src[i]
        val next = src.getOrNull(i + 1)
        if (c != '$' || next == null || !(next == '{' || next.isLetter() || next == '_')) return false
        if (text.isNotEmpty()) parts += RawPart.Text(text.toString())
        text.clear()
        if (next == '{') {
            advance(2)
            val codePos = Pos(line, col)
            val start = i
            var depth = 1
            while (i < src.length) {
                if (src[i] == '"') {
                    skipString(pos)
                    continue
                }
                if (src[i] == '{') depth++
                if (src[i] == '}' && --depth == 0) break
                advance()
            }
            if (i >= src.length) throw CompileError(pos, "unterminated string template")
            parts += RawPart.Code(src.substring(start, i), codePos)
            advance()
        } else {
            advance()
            val codePos = Pos(line, col)
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) advance()
            parts += RawPart.Code(src.substring(start, i), codePos)
        }
        return true
    }

    /** Moves past a string literal inside a template, so its braces and quotes aren't the template's. */
    private fun skipString(pos: Pos) {
        if (src.startsWith("\"\"\"", i)) {
            advance(3)
            while (i < src.length && !(src.startsWith("\"\"\"", i) && !src.startsWith("\"\"\"\"", i))) advance()
            if (i >= src.length) throw CompileError(pos, "unterminated raw string")
            advance(3)
            return
        }
        advance()
        while (i < src.length && src[i] != '"' && src[i] != '\n') advance(if (src[i] == '\\') 2 else 1)
        if (i >= src.length || src[i] == '\n') throw CompileError(pos, "unterminated string")
        advance()
    }

    private fun string(pos: Pos) {
        advance()
        val parts = mutableListOf<RawPart>()
        val text = StringBuilder()
        while (true) {
            if (i >= src.length || src[i] == '\n') throw CompileError(pos, "unterminated string")
            when {
                src[i] == '"' -> {
                    advance()
                    break
                }
                src[i] == '\\' -> {
                    advance()
                    text.append(escape(pos))
                }
                template(parts, text, pos) -> {}
                else -> {
                    text.append(src[i])
                    advance()
                }
            }
        }
        if (text.isNotEmpty()) parts += RawPart.Text(text.toString())
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
