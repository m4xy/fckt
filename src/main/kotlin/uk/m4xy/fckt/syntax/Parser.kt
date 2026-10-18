package uk.m4xy.fckt.syntax

/** Recursive-descent parser for `.fk` source. */
class Parser(private val tokens: List<Token>) {
    private var p = 0

    companion object {
        fun parse(source: String): SourceFile = Parser(Lexer(source).tokenize()).file()
    }

    // ---- Token helpers ----

    private val peek get() = tokens[p]
    private fun peekAt(offset: Int) = tokens[minOf(p + offset, tokens.size - 1)]
    private fun at(kind: T) = peek.kind == kind
    private fun next() = tokens[p].also { if (p < tokens.size - 1) p++ }

    private fun accept(kind: T): Token? = if (at(kind)) next() else null

    private fun expect(kind: T, what: String): Token =
        accept(kind) ?: throw error("expected $what but found ${describe(peek)}")

    private fun error(message: String, pos: Pos = peek.pos) = CompileError(pos, message)

    private fun describe(token: Token) = when (token.kind) {
        T.EOF -> "end of file"
        T.NEWLINE -> "end of line"
        T.STRING -> "a string"
        else -> "'${token.text}'"
    }

    private fun skipNewlines() {
        while (at(T.NEWLINE) || at(T.SEMI)) next()
    }

    private fun ident(what: String) = expect(T.IDENT, what).text

    // ---- Declarations ----

    fun file(): SourceFile {
        val decls = mutableListOf<Decl>()
        skipNewlines()
        while (!at(T.EOF)) {
            decls += decl()
            if (!at(T.EOF)) {
                if (!at(T.NEWLINE) && !at(T.SEMI)) throw error("expected end of line after declaration but found ${describe(peek)}")
                skipNewlines()
            }
        }
        return SourceFile(decls)
    }

    private fun decl(): Decl {
        val pos = peek.pos
        return when {
            accept(T.TYPE) != null -> {
                val name = upperIdent("a type name")
                val typeParams = typeParams()
                expect(T.LPAREN, "'(' after the type name")
                val fields = fieldList(pos)
                expect(T.RPAREN, "')'")
                Decl.Record(pos, name, typeParams, fields)
            }
            accept(T.INTERFACE) != null -> {
                val name = upperIdent("an interface name")
                val typeParams = typeParams()
                expect(T.LBRACE, "'{'")
                skipNewlines()
                val variants = mutableListOf<VariantDef>()
                while (!at(T.RBRACE)) {
                    val variantPos = peek.pos
                    val variantName = upperIdent("a variant name")
                    val fields = if (accept(T.LPAREN) != null) fieldList(variantPos).also { expect(T.RPAREN, "')'") } else emptyList()
                    variants += VariantDef(variantPos, variantName, fields)
                    accept(T.COMMA)
                    skipNewlines()
                }
                expect(T.RBRACE, "'}'")
                Decl.Sealed(pos, name, typeParams, variants)
            }
            at(T.IDENT) -> {
                val name = next().text
                if (name[0].isUpperCase()) throw error("functions and values start with a lower-case letter", pos)
                val typeParams = typeParams()
                val params = if (at(T.LPAREN)) params() else null
                val returnType = if (accept(T.COLON) != null) type() else null
                expect(T.ASSIGN, "'='")
                skipNewlines()
                Decl.Function(pos, name, typeParams, params, returnType, expr())
            }
            else -> throw error("expected a declaration but found ${describe(peek)}")
        }
    }

    private fun upperIdent(what: String): String {
        val token = expect(T.IDENT, what)
        if (!token.text[0].isUpperCase()) throw error("$what must start with an upper-case letter", token.pos)
        return token.text
    }

    private fun typeParams(): List<String> {
        if (accept(T.LT) == null) return emptyList()
        val names = mutableListOf(upperIdent("a type parameter"))
        while (accept(T.COMMA) != null) names += upperIdent("a type parameter")
        expect(T.GT, "'>'")
        return names
    }

    /** `a: A, b: B`, or a single unnamed `A`, which is named `value`. */
    private fun fieldList(declPos: Pos): List<FieldDef> {
        val fields = mutableListOf<FieldDef>()
        while (!at(T.RPAREN)) {
            val pos = peek.pos
            if (at(T.IDENT) && peekAt(1).kind == T.COLON) {
                val name = next().text
                next()
                fields += FieldDef(pos, name, type())
            } else {
                fields += FieldDef(pos, "", type())
            }
            if (accept(T.COMMA) == null) break
        }
        val unnamed = fields.filter { it.name.isEmpty() }
        if (unnamed.isNotEmpty()) {
            if (fields.size > 1) throw error("only a type with a single field can leave it unnamed", unnamed.first().pos)
            return listOf(fields.single().copy(name = "value"))
        }
        if (fields.isEmpty()) throw error("a type needs at least one field", declPos)
        return fields
    }

    private fun params(): List<Param> {
        expect(T.LPAREN, "'('")
        val params = mutableListOf<Param>()
        while (!at(T.RPAREN)) {
            val pos = peek.pos
            val name = ident("a parameter name")
            val type = if (accept(T.COLON) != null) type() else null
            params += Param(pos, name, type)
            if (accept(T.COMMA) == null) break
        }
        expect(T.RPAREN, "')'")
        return params
    }

    // ---- Types ----

    private fun type(): TypeRef {
        val pos = peek.pos
        if (accept(T.LPAREN) != null) {
            val params = mutableListOf<TypeRef>()
            while (!at(T.RPAREN)) {
                params += type()
                if (accept(T.COMMA) == null) break
            }
            expect(T.RPAREN, "')'")
            if (accept(T.ARROW) == null) {
                if (params.size == 1) return params.single()
                throw error("expected '->' after a function type's parameters")
            }
            return TypeRef.Function(pos, params, type())
        }
        val name = upperIdent("a type")
        val args = mutableListOf<TypeRef>()
        if (accept(T.LT) != null) {
            do args += type() while (accept(T.COMMA) != null)
            expect(T.GT, "'>'")
        }
        return TypeRef.Named(pos, name, args)
    }

    // ---- Expressions ----

    fun expr(): Expr {
        lambdaStart()?.let { return it }
        return pipe()
    }

    /** Parses a lambda if one starts here: `x ->`, `(a, b) ->`, `() ->` or `~`. */
    private fun lambdaStart(): Expr? {
        val pos = peek.pos
        if (accept(T.TILDE) != null) return Expr.Lambda(pos, emptyList(), expr())
        if (at(T.IDENT) && peekAt(1).kind == T.ARROW) {
            val name = next().text
            next()
            return Expr.Lambda(pos, listOf(Param(pos, name, null)), expr())
        }
        if (at(T.LPAREN) && isLambdaParams()) {
            val params = params()
            expect(T.ARROW, "'->'")
            return Expr.Lambda(pos, params, expr())
        }
        return null
    }

    /** Whether `(` starts a parameter list followed by `->`, found by trying to parse one. */
    private fun isLambdaParams(): Boolean {
        val save = p
        return try {
            params()
            at(T.ARROW)
        } catch (_: CompileError) {
            false
        } finally {
            p = save
        }
    }

    private fun pipe(): Expr {
        var left = or()
        while (at(T.PIPE)) {
            val pos = next().pos
            skipNewlines()
            val right = lambdaStart() ?: or()
            left = Expr.Pipe(pos, left, right)
        }
        return left
    }

    private fun binaryLevel(ops: Set<T>, operand: () -> Expr): Expr {
        var left = operand()
        while (peek.kind in ops) {
            val op = next()
            left = Expr.Binary(op.pos, op.text, left, operand())
        }
        return left
    }

    private fun or(): Expr = binaryLevel(setOf(T.OR)) { and() }
    private fun and(): Expr = binaryLevel(setOf(T.AND)) { equality() }
    private fun equality(): Expr = binaryLevel(setOf(T.EQ, T.NEQ)) { comparison() }
    private fun comparison(): Expr = binaryLevel(setOf(T.LT, T.GT, T.LE, T.GE)) { additive() }
    private fun additive(): Expr = binaryLevel(setOf(T.PLUS, T.MINUS)) { multiplicative() }
    private fun multiplicative(): Expr = binaryLevel(setOf(T.STAR, T.SLASH, T.PERCENT)) { unary() }

    private fun unary(): Expr {
        val pos = peek.pos
        if (accept(T.MINUS) != null) return Expr.Unary(pos, "-", unary())
        if (accept(T.BANG) != null) return Expr.Unary(pos, "!", unary())
        return postfix()
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            e = when {
                at(T.DOT) -> {
                    next()
                    val name = expect(T.IDENT, "a member name after '.'")
                    Expr.Member(name.pos, e, name.text)
                }
                at(T.LPAREN) -> Expr.Call(peek.pos, e, args())
                else -> return e
            }
        }
    }

    private fun args(): List<Arg> {
        expect(T.LPAREN, "'('")
        val args = mutableListOf<Arg>()
        while (!at(T.RPAREN)) {
            val name = if (at(T.IDENT) && peekAt(1).kind == T.ASSIGN) next().text.also { next() } else null
            args += Arg(name, expr())
            if (accept(T.COMMA) == null) break
        }
        expect(T.RPAREN, "')'")
        if (args.any { it.name != null } && args.any { it.name == null }) {
            throw error("can't mix named and positional arguments")
        }
        return args
    }

    private fun primary(): Expr {
        val token = peek
        val pos = token.pos
        return when (token.kind) {
            T.INT -> Expr.IntLit(pos, next().text.toLongOrNull() ?: throw error("integer literal is too large"))
            T.FLOAT -> Expr.FloatLit(pos, next().text.toDouble())
            T.TRUE -> Expr.BoolLit(pos, true).also { next() }
            T.FALSE -> Expr.BoolLit(pos, false).also { next() }
            T.STRING -> stringLiteral(next())
            T.IDENT -> Expr.Name(pos, next().text)
            T.LPAREN -> {
                next()
                val e = expr()
                expect(T.RPAREN, "')'")
                e
            }
            T.LBRACKET -> {
                next()
                val items = mutableListOf<Expr>()
                while (!at(T.RBRACKET)) {
                    items += expr()
                    if (accept(T.COMMA) == null) break
                }
                expect(T.RBRACKET, "']'")
                Expr.ListLit(pos, items)
            }
            T.LBRACE -> block()
            T.IF -> ifExpr()
            T.WHEN -> whenExpr()
            else -> throw error("expected an expression but found ${describe(token)}")
        }
    }

    private fun stringLiteral(token: Token): Expr {
        val parts = token.parts.map { part ->
            when (part) {
                is RawPart.Text -> StringPart.Text(part.text)
                is RawPart.Code -> {
                    val inner = Parser(Lexer(part.source, part.pos).tokenize())
                    val e = inner.expr()
                    if (!inner.at(T.EOF)) throw inner.error("unexpected ${inner.describe(inner.peek)} in string template")
                    StringPart.Interpolated(e)
                }
            }
        }
        return Expr.StringLit(token.pos, parts)
    }

    private fun ifExpr(): Expr {
        val pos = expect(T.IF, "'if'").pos
        expect(T.LPAREN, "'(' after 'if'")
        val condition = expr()
        expect(T.RPAREN, "')'")
        skipNewlines()
        val then = expr()
        val save = p
        skipNewlines()
        if (accept(T.ELSE) == null) {
            p = save
            throw error("'if' needs an 'else': every expression must have a value", pos)
        }
        skipNewlines()
        return Expr.If(pos, condition, then, expr())
    }

    private fun whenExpr(): Expr {
        val pos = expect(T.WHEN, "'when'").pos
        val subject = if (accept(T.LPAREN) != null) expr().also { expect(T.RPAREN, "')'") } else null
        expect(T.LBRACE, "'{'")
        skipNewlines()
        val branches = mutableListOf<Branch>()
        while (!at(T.RBRACE)) {
            val branchPos = peek.pos
            if (accept(T.ELSE) != null) {
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, emptyList(), null, expr())
            } else if (subject == null) {
                // Not expr(): a bare `flag -> ...` is a condition here, not a lambda.
                val condition = pipe()
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, emptyList(), condition, expr())
            } else {
                val patterns = mutableListOf(pattern())
                while (accept(T.COMMA) != null) patterns += pattern()
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, patterns, null, expr())
            }
            if (!at(T.RBRACE)) {
                if (!at(T.NEWLINE) && !at(T.SEMI)) throw error("expected end of line after a 'when' branch but found ${describe(peek)}")
                skipNewlines()
            }
        }
        expect(T.RBRACE, "'}'")
        return Expr.When(pos, subject, branches)
    }

    private fun pattern(): Pattern {
        val pos = peek.pos
        return when {
            accept(T.UNDERSCORE) != null -> Pattern.Wildcard(pos)
            accept(T.IS) != null -> Pattern.Constructor(pos, upperIdent("a type after 'is'"), null, isCheck = true)
            at(T.IDENT) && peek.text[0].isUpperCase() -> {
                val name = next().text
                val args = if (accept(T.LPAREN) != null) {
                    val args = mutableListOf<Pattern>()
                    while (!at(T.RPAREN)) {
                        args += pattern()
                        if (accept(T.COMMA) == null) break
                    }
                    expect(T.RPAREN, "')'")
                    args
                } else {
                    null
                }
                Pattern.Constructor(pos, name, args, isCheck = false)
            }
            at(T.IDENT) -> Pattern.Bind(pos, next().text)
            at(T.INT) || at(T.FLOAT) || at(T.STRING) || at(T.TRUE) || at(T.FALSE) -> Pattern.Literal(pos, primary())
            at(T.MINUS) && (peekAt(1).kind == T.INT || peekAt(1).kind == T.FLOAT) -> Pattern.Literal(pos, unary())
            else -> throw error("expected a pattern but found ${describe(peek)}")
        }
    }

    private fun block(): Expr {
        val pos = expect(T.LBRACE, "'{'").pos
        skipNewlines()
        val statements = mutableListOf<Statement>()
        while (!at(T.RBRACE)) {
            statements += statement()
            if (!at(T.RBRACE)) {
                if (!at(T.NEWLINE) && !at(T.SEMI)) throw error("expected end of line but found ${describe(peek)}")
                skipNewlines()
            }
        }
        expect(T.RBRACE, "'}'")
        val last = statements.removeLastOrNull() ?: throw error("a block needs a result expression", pos)
        if (last !is Statement.Effect) throw error("a block must end with an expression, not a binding", last.pos)
        return Expr.Block(pos, statements, last.expr)
    }

    private fun statement(): Statement {
        val pos = peek.pos
        if (at(T.IDENT) && peekAt(1).kind == T.ASSIGN) {
            val name = next().text
            next()
            skipNewlines()
            return Statement.Binding(pos, name, null, null, expr())
        }
        if (at(T.IDENT) && peekAt(1).kind == T.COLON) {
            val name = next().text
            next()
            val type = type()
            expect(T.ASSIGN, "'='")
            skipNewlines()
            return Statement.Binding(pos, name, null, type, expr())
        }
        if (at(T.IDENT) && peekAt(1).kind == T.LPAREN && isLocalFunction()) {
            val name = next().text
            val params = params()
            val returnType = if (accept(T.COLON) != null) type() else null
            expect(T.ASSIGN, "'='")
            skipNewlines()
            return Statement.Binding(pos, name, params, returnType, expr())
        }
        return Statement.Effect(pos, expr())
    }

    /** `name(...)` followed by `=` or `:` declares a local function rather than calling one. */
    private fun isLocalFunction(): Boolean {
        var depth = 0
        var j = p + 1
        while (j < tokens.size) {
            when (tokens[j].kind) {
                T.LPAREN -> depth++
                T.RPAREN -> if (--depth == 0) return tokens.getOrNull(j + 1)?.kind in setOf(T.ASSIGN, T.COLON)
                T.EOF -> return false
                else -> {}
            }
            j++
        }
        return false
    }
}
