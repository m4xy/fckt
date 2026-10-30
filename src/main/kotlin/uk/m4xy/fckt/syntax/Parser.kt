package uk.m4xy.fckt.syntax

import uk.m4xy.fckt.types.BUILTIN_TYPES

/** Recursive-descent parser for `.fk` source. */
class Parser(private val tokens: List<Token>, private val file: String? = null, private val isInterface: Boolean = false) {
    private var p = 0

    /** The `get` calls written `xs[i]`, which `xs[i] = v` turns into a `set`. */
    private val indexes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Expr, Boolean>())

    /** `e is T` or `e !is T`, as read: what's checked, the check, and whether it's `!is`. */
    private class Check(val subject: Expr, val pattern: Pattern.Constructor, val negated: Boolean)

    /** The `when`s `x is T` is read as, by what they check, so what decides on one can narrow x. */
    private val checks = java.util.IdentityHashMap<Expr, Check>()

    /** The `&&` chains that start with a check, by their operands and the `&&`s', so an `if` on one can narrow in its branch. */
    private val conjunctions = java.util.IdentityHashMap<Expr, Pair<List<Expr>, List<Pos>>>()

    companion object {
        fun parse(source: String, file: String? = null): SourceFile = Parser(Lexer(source, Pos(1, 1, file)).tokenize(), file).file()

        /**
         * A library's interface, as `fckt --library` writes it: declarations without their code. A
         * function is its signature, `greet(name: String): Unit`, and a default is `= compiled`.
         */
        fun parseInterface(source: String, file: String? = null): SourceFile =
            Parser(Lexer(source, Pos(1, 1, file)).tokenize(), file, isInterface = true).file()
    }

    /** The prelude's annotation that makes one an effect, which `effect Name` declares one with. */
    private val EFFECT_ANNOTATION = "Effect"

    // ---- Token helpers ----

    /** Tokens that end something, which a declaration can't start with. */
    private val CLOSERS = setOf(T.RBRACE, T.RPAREN, T.RBRACKET, T.NEWLINE, T.SEMI)

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
        val errors = mutableListOf<CompileError>()
        skipNewlines()
        val imports = imports()
        while (!at(T.EOF)) {
            try {
                decls += decl()
                // A declaration ends its line. After an error, a bracket left open hides the line's
                // end from the lexer, so a later line is an end too.
                if (!at(T.EOF)) {
                    val newLine = errors.isNotEmpty() && peek.pos.line > tokens[p - 1].pos.line
                    if (!at(T.NEWLINE) && !at(T.SEMI) && !newLine) throw error("expected end of line after declaration but found ${describe(peek)}")
                    skipNewlines()
                }
            } catch (e: CompileError) {
                // Carry on from the next declaration, the next line that starts at the left edge.
                errors += e
                next()
                while (!at(T.EOF) && !(peek.pos.col == 1 && peek.kind !in CLOSERS)) next()
            }
        }
        CompileErrors.throwIfAny(errors)
        return SourceFile(receiverTypeParams(decls, typeNames(decls)), mapOf(file to imports))
    }

    /** The `import` lines a file starts with. `import` is a keyword only there, at a line's start. */
    private fun imports(): List<Import> {
        val imports = mutableListOf<Import>()
        while (at(T.IDENT) && peek.text == "import" && peekAt(1).kind == T.IDENT) {
            val pos = next().pos
            val path = mutableListOf(ident("a name to import"))
            var wildcard = false
            while (accept(T.DOT) != null) {
                if (accept(T.STAR) != null) {
                    wildcard = true
                    break
                }
                path += ident("a name to import, or *")
            }
            val alias = if (!wildcard && at(T.IDENT) && peek.text == "as") {
                next()
                ident("the name to import it as")
            } else {
                null
            }
            if (path.size < 2 && !wildcard) throw error("import a declaration by its package, as in import geometry.Shapes", pos)
            imports += Import(pos, path, wildcard, alias)
            // After `.*`, the lexer takes the line to go on, as it would after a multiplication.
            val nextLine = peek.pos.line > tokens[p - 1].pos.line
            if (!at(T.EOF) && !at(T.NEWLINE) && !at(T.SEMI) && !nextLine) throw error("expected end of line after an import but found ${describe(peek)}")
            skipNewlines()
        }
        return imports
    }

    private fun decl(): Decl {
        val pos = peek.pos
        if (at(T.AT)) {
            val annotations = annotations()
            if (at(T.EOF) || at(T.RBRACE)) throw error("an annotation goes before a declaration", annotations.last().pos)
            return annotate(decl(), annotations)
        }
        // `annotation type Name(fields)`: written `@Name(...)` on declarations, and only checked.
        if (at(T.IDENT) && peek.text == "annotation" && peekAt(1).kind == T.TYPE) {
            next()
            next()
            val name = upperIdent("an annotation's name")
            if (at(T.LT)) throw error("an annotation has no type parameters: its fields say what it refers to, like Type or Case<T>")
            if (at(T.LBRACE) || at(T.ASSIGN)) throw error("an annotation is a record: annotation type $name(field: Type)")
            val fields = if (accept(T.LPAREN) != null) {
                (if (at(T.RPAREN)) emptyList() else fieldList(pos, varargs = true)).also { expect(T.RPAREN, "')'") }
            } else {
                emptyList()
            }
            return Decl.Record(pos, name, emptyList(), fields, isAnnotation = true)
        }
        // `opaque type T = ...`: outside its module, T can be held and passed back, but not built or looked into.
        if (at(T.IDENT) && peek.text == "opaque" && peekAt(1).kind == T.TYPE) {
            next()
            return when (val d = decl()) {
                is Decl.Record -> d.copy(isOpaque = true)
                is Decl.Sealed -> d.copy(isOpaque = true)
                is Decl.Alias -> d.copy(isOpaque = true)
                else -> throw IllegalStateException("opaque applies to a type")
            }
        }
        // `internal`: seen only in its project, and not in its interface. A soft keyword, so a function may still be called internal.
        if (at(T.IDENT) && peek.text == "internal" && peekAt(1).kind !in setOf(T.LPAREN, T.COLON, T.ASSIGN, T.DOT, T.LT)) {
            next()
            if (at(T.PRIVATE)) throw error("a private declaration is hidden already; it can't also be internal")
            return when (val d = decl()) {
                is Decl.Record -> d.copy(isInternal = true)
                is Decl.Sealed -> d.copy(isInternal = true)
                is Decl.Function -> d.copy(isInternal = true)
                is Decl.Module -> d.copy(isInternal = true)
                is Decl.Alias -> d.copy(isInternal = true)
                is Decl.Spec -> d.copy(isInternal = true)
            }
        }
        // `operator Set<A>.plus(...)`: what `+` on a Set calls. A soft keyword, as internal is.
        if (at(T.IDENT) && peek.text == "operator" && peekAt(1).kind !in setOf(T.LPAREN, T.COLON, T.ASSIGN, T.DOT, T.LT)) {
            val at = next().pos
            val d = decl() as? Decl.Function ?: throw CompileError(at, "only a function can be an operator")
            val binary = OPERATOR_METHODS.values + RANGE_METHODS.values
            val arity = if (d.methodName == UNARY_MINUS) 0 else if (d.methodName in binary) 1 else {
                throw CompileError(d.pos, "there's no operator '${d.methodName}': an operator is one of ${(binary + UNARY_MINUS).joinToString(", ")}")
            }
            if (!d.extension) throw CompileError(d.pos, "an operator is a type's, so it's an extension: operator Type.${d.methodName}(...)")
            if (d.params.orEmpty().size - 1 != arity) throw CompileError(d.pos, "${d.methodName} takes ${if (arity == 0) "no parameters" else "one parameter, the right-hand side"}")
            return d.copy(isOperator = true)
        }
        if (accept(T.PRIVATE) != null) {
            if (at(T.IDENT) && peek.text == "internal") throw error("a private declaration is hidden already; it can't also be internal")
            if (at(T.IDENT) && peek.text == "opaque") throw error("a private type is hidden already; it can't also be opaque")
            return when (val d = decl()) {
                is Decl.Record -> d.copy(isPrivate = true)
                is Decl.Sealed -> d.copy(isPrivate = true)
                is Decl.Function -> d.copy(isPrivate = true)
                is Decl.Module -> d.copy(isPrivate = true)
                is Decl.Alias -> d.copy(isPrivate = true)
                is Decl.Spec -> d.copy(isPrivate = true)
            }
        }
        return when {
            accept(T.MODULE) != null -> {
                val name = upperIdent("a module name")
                // module Name = Functor(Arg, ...)
                if (accept(T.ASSIGN) != null) {
                    val functorPos = peek.pos
                    val functor = moduleName("a functor applied to modules: Functor(Module)")
                    if (at(T.LT)) throw error("a functor takes its modules in parentheses: $functor(Module)")
                    if (accept(T.LPAREN) == null) throw error("expected modules for $functor: $functor(Module)")
                    val args = mutableListOf<ModuleArg>()
                    // Functor(): every module its default.
                    if (!at(T.RPAREN)) do {
                        val argName = if (at(T.IDENT) && peekAt(1).kind == T.ASSIGN) upperIdent("a module parameter's name").also { next() } else null
                        if (argName == null && args.any { it.name != null }) throw error("a module given by position goes before those given by name")
                        val argPos = peek.pos
                        args += ModuleArg(argName, TypeRef.Named(argPos, moduleName("a module, by name"), emptyList()))
                    } while (accept(T.COMMA) != null)
                    expect(T.RPAREN, "')'")
                    return Decl.Module(pos, name, emptyList(), application = Application(TypeRef.Named(functorPos, functor, emptyList()), args))
                }
                // module Name<E>: generic in E, as each of its declarations is
                if (at(T.LT) && peekAt(2).kind == T.COLON) throw error("a functor takes its modules in parentheses: module $name(M: Spec)")
                val moduleTypeParams = typeParams()
                // module Name(M: Spec, ...): a functor
                val params = mutableListOf<ModuleParam>()
                if (accept(T.LPAREN) != null) {
                    do {
                        val paramPos = peek.pos
                        val paramName = upperIdent("a module parameter")
                        expect(T.COLON, "':' and the spec the module must follow")
                        val spec = specName()
                        val default = if (accept(T.ASSIGN) != null) TypeRef.Named(peek.pos, moduleName("the module it's given by default"), emptyList()) else null
                        params += ModuleParam(paramPos, paramName, spec, default)
                    } while (accept(T.COMMA) != null)
                    expect(T.RPAREN, "')'")
                }
                // module Name : Spec { ... }
                val spec = if (accept(T.COLON) != null) specName() else null
                expect(T.LBRACE, "'{' after the module name")
                val decls = members { decl() }
                if (moduleTypeParams.isNotEmpty() && params.isNotEmpty()) throw error("a functor can't take type parameters too yet", pos)
                Decl.Module(pos, name, decls, spec = spec, params = params, typeParams = moduleTypeParams)
            }
            // `effect Console { println(s: String): Unit }`: an effect with operations, which a handler
            // answers; `effect Mutating`: one without, only tracked. A keyword only here, as `spec` is.
            at(T.IDENT) && peek.text == "effect" && peekAt(1).kind == T.IDENT && peekAt(1).text[0].isUpperCase() -> {
                next()
                val name = upperIdent("an effect's name")
                val typeParams = typeParams()
                val effect = listOf(Annotation(pos, EFFECT_ANNOTATION, emptyList()))
                // `effect OrdersDb = Database`: the same operations, as a separate effect.
                if (accept(T.ASSIGN) != null) {
                    if (typeParams.isNotEmpty()) throw error("a copy of an effect takes the type parameters of the one it copies: effect $name = ...")
                    val target = peek.pos
                    return annotate(Decl.Spec(pos, name, emptyList(), emptyList(), copyOf = TypeRef.Named(target, moduleName("the effect it's a copy of"), emptyList())), effect)
                }
                if (at(T.LPAREN)) throw error("an effect has no fields: its operations go in braces, as in effect $name { op(x: Int): Unit }")
                if (accept(T.LBRACE) != null) return annotate(specBody(pos, name, typeParams), effect)
                if (typeParams.isNotEmpty()) throw error("only an effect with operations takes type parameters, which its operations use")
                Decl.Record(pos, name, emptyList(), emptyList(), isAnnotation = true, annotations = effect)
            }
            // `spec` is a keyword only here, so it can still name a value.
            at(T.IDENT) && peek.text == "spec" && peekAt(1).kind == T.IDENT && peekAt(2).kind in setOf(T.LBRACE, T.LT, T.COLON) -> {
                next()
                val name = upperIdent("a spec name")
                val specTypeParams = typeParams()
                // spec Monad : Applicative { ... }: it has what Applicative has, and more
                val parents = mutableListOf<TypeRef.Named>()
                if (accept(T.COLON) != null) do parents += specName() while (accept(T.COMMA) != null)
                expect(T.LBRACE, "'{'")
                specBody(pos, name, specTypeParams).copy(parents = parents)
            }
            at(T.IDENT) && peek.text == "interface" && peekAt(1).kind == T.IDENT ->
                throw error("write a type with cases as 'type ${peekAt(1).text} { ... }'")
            accept(T.TYPE) != null -> {
                val name = upperIdent("a type name")
                val typeParams = typeParams()
                // type Name { Cases }: a type with cases
                if (accept(T.LBRACE) != null) return typeWithCases(pos, name, typeParams)
                // type Name = Other: another name for a type
                if (accept(T.ASSIGN) != null) return Decl.Alias(pos, name, typeParams, type())
                // type Name(fields), or type Name private(...): like Kotlin's private constructor
                val privateConstructor = accept(T.PRIVATE) != null
                expect(T.LPAREN, "'(' or '{' after the type name")
                val fields = fieldList(pos)
                expect(T.RPAREN, "')'")
                Decl.Record(pos, name, typeParams, fields, privateConstructor = privateConstructor)
            }
            // `external type Array<A> = "'a array"`: a type OCaml implements.
            at(T.IDENT) && peek.text == "external" && peekAt(1).kind == T.TYPE -> {
                next()
                next()
                val name = upperIdent("a type name")
                val typeParams = typeParams()
                expect(T.ASSIGN, "'=' and the OCaml type, in a string: \"'a array\"")
                val ocaml = stringLiteral(expect(T.STRING, "the OCaml type, in a string: \"'a array\"")) as Expr.StringLit
                val text = ocaml.parts.singleOrNull() as? StringPart.Text ?: throw error("an external type is OCaml in a string, without templates", ocaml.pos)
                Decl.Record(pos, name, typeParams, emptyList(), external = text.text)
            }
            at(T.IDENT) && peek.text == "external" && peekAt(1).kind in setOf(T.IDENT, T.LPAREN) -> {
                next()
                val d = function(pos)
                if (d.body !is Expr.StringLit || d.body.parts.any { it !is StringPart.Text }) {
                    throw error("an external's body is OCaml in a string: \"List.map f this\"", d.body.pos)
                }
                if (d.returnType == null || d.params.orEmpty().any { it.type == null }) {
                    throw error("an external needs the types of its parameters and result", d.pos)
                }
                d.copy(external = d.body.parts.joinToString("") { (it as StringPart.Text).text })
            }
            at(T.IDENT) || at(T.LPAREN) -> function(pos)
            else -> throw error("expected a declaration but found ${describe(peek)}")
        }
    }

    /**
     * `name<T>(params): R = body`, or an extension: `Receiver.name(params): R = body`, whose
     * receiver is the parameter `this`. A receiver starts with an upper-case letter or `(`.
     */
    private fun function(pos: Pos): Decl.Function {
        val receiver = if (at(T.LPAREN) || (at(T.IDENT) && peek.text[0].isUpperCase())) {
            type().also { expect(T.DOT, "'.' and the name of the extension") }
        } else {
            null
        }
        val name = expect(T.IDENT, "a function name").text
        if (name[0].isUpperCase()) throw error("functions and values start with a lower-case letter", pos)
        val typeParams = typeParams()
        val params = if (at(T.LPAREN)) params(defaults = true) else null
        val returnType = if (accept(T.COLON) != null) type() else null
        val body = if (isInterface && !at(T.ASSIGN)) Expr.Compiled(pos) else {
            expect(T.ASSIGN, "'='")
            skipNewlines()
            expr()
        }
        if (receiver == null) return Decl.Function(pos, name, typeParams, params, returnType, body)
        // Named for its receiver once its type parameters are known, in receiverTypeParams.
        return Decl.Function(
            pos, "$$name", typeParams, listOf(Param(receiver.pos, "this", receiver)) + params.orEmpty(), returnType, body,
            extension = true, property = params == null,
        )
    }

    /**
     * Bare names in an extension's receiver that name type parameters, `A` in `List<A>`: a capital
     * letter, maybe with digits, that the file doesn't declare as a type. Any other name is a type,
     * which may be declared in another file.
     */
    private fun receiverTypeParams(ds: List<Decl>, types: Set<String>): List<Decl> = ds.map { d ->
        when {
            d is Decl.Module -> d.copy(decls = receiverTypeParams(d.decls, types))
            d is Decl.Function && d.extension -> {
                val names = linkedSetOf<String>()
                fun walk(t: TypeRef) {
                    when (t) {
                        is TypeRef.Named -> if (t.args.isEmpty() && '.' !in t.name) names += t.name else t.args.forEach(::walk)
                        is TypeRef.Tuple -> t.elements.forEach(::walk)
                        is TypeRef.Function -> (t.params + t.result).forEach(::walk)
                        is TypeRef.Effects -> {}
                        is TypeRef.Given -> walk(t.type)
                    }
                }
                val receiver = d.params!!.first().type!!
                walk(receiver)
                val params = (names - types - BUILTIN_TYPES.keys - d.typeParams.toSet()).filter { TYPE_PARAMETER.matches(it) }
                // Named for the receiver's type, without its type parameters: List<Int>.sum is ListInt$sum.
                fun mangle(t: TypeRef): String = when (t) {
                    is TypeRef.Named -> if (t.name in params) "" else t.name.substringAfterLast('.') + t.args.joinToString("") { mangle(it) }
                    is TypeRef.Tuple -> "Tuple" + t.elements.joinToString("") { mangle(it) }
                    is TypeRef.Function -> "Function"
                    is TypeRef.Effects -> ""
                    is TypeRef.Given -> mangle(t.type)
                }
                d.copy(name = mangle(receiver) + "$" + d.methodName, typeParams = params + d.typeParams)
            }
            else -> d
        }
    }

    private val TYPE_PARAMETER = Regex("[A-Z][0-9]*")

    private fun typeNames(ds: List<Decl>): Set<String> = ds.flatMap { d ->
        when (d) {
            is Decl.Module -> typeNames(d.decls)
            is Decl.Record, is Decl.Sealed, is Decl.Alias -> listOf(d.name)
            is Decl.Spec -> d.types.map { it.name }
            is Decl.Function -> emptyList()
        }
    }.toSet()

    /** `Spec`, or `Spec<A, B>`, which says what the spec's types are, in order. */
    /** A module's name, which may be qualified: `Sets.SortedSet`. */
    private fun moduleName(what: String): String {
        var name = upperIdent(what)
        while (at(T.DOT) && peekAt(1).kind == T.IDENT && peekAt(1).text[0].isUpperCase()) {
            next()
            name += "." + next().text
        }
        return name
    }

    private fun specName(): TypeRef.Named = type() as? TypeRef.Named ?: throw error("expected a spec's name")

    /** Items one per line up to a `}`, after the `{`. */
    private fun <R> members(item: () -> R): List<R> {
        skipNewlines()
        val items = mutableListOf<R>()
        while (!at(T.RBRACE)) {
            items += item()
            if (!at(T.RBRACE)) {
                if (!at(T.NEWLINE) && !at(T.SEMI)) throw error("expected end of line after declaration but found ${describe(peek)}")
                skipNewlines()
            }
        }
        expect(T.RBRACE, "'}'")
        return items
    }

    /** The `{ ... }` of `type Name { Cases }`, after the `{`. */
    private fun typeWithCases(pos: Pos, name: String, typeParams: List<String>): Decl.Sealed {
        skipNewlines()
        val variants = mutableListOf<VariantDef>()
        val caseRefs = mutableListOf<CaseRefDef>()
        val includes = mutableListOf<TypeRef.Named>()
        while (!at(T.RBRACE)) {
            val annotations = annotations()
            val variantPos = peek.pos
            if (annotations.isNotEmpty() && (at(T.STAR) || peekAt(1).kind == T.DOT)) {
                throw error("annotate a case where it's declared")
            }
            if (accept(T.STAR) != null) {
                // *Other<T>: every case of Other
                includes += type() as? TypeRef.Named ?: throw error("'*' takes a type with cases", variantPos)
            } else {
                val variantName = upperIdent("a variant name")
                if (at(T.DOT)) {
                    // Other.Case: one case of another type, which may itself be qualified
                    val path = mutableListOf(variantName)
                    while (accept(T.DOT) != null) path += upperIdent("a case name")
                    caseRefs += CaseRefDef(variantPos, path.dropLast(1).joinToString("."), path.last())
                } else {
                    val fields = if (accept(T.LPAREN) != null) fieldList(variantPos).also { expect(T.RPAREN, "')'") } else emptyList()
                    variants += VariantDef(variantPos, variantName, fields, annotations)
                }
            }
            accept(T.COMMA)
            skipNewlines()
        }
        expect(T.RBRACE, "'}'")
        return Decl.Sealed(pos, name, typeParams, variants, caseRefs, includes)
    }

    private fun upperIdent(what: String): String {
        val token = expect(T.IDENT, what)
        if (!token.text[0].isUpperCase()) throw error("$what must start with an upper-case letter", token.pos)
        return token.text
    }

    /** A spec's members, after its `{`: abstract types, and values with their types. */
    private fun specBody(pos: Pos, name: String, typeParams: List<String>): Decl.Spec {
        val types = mutableListOf<SpecType>()
        val values = mutableListOf<SpecValue>()
        members {
            val annotations = annotations()
            val memberPos = peek.pos
            if (annotations.isNotEmpty() && at(T.TYPE)) throw error("a spec's type takes no annotations")
            if (accept(T.TYPE) != null) {
                val typeName = upperIdent("a type name")
                val typeParams = typeParams()
                if (at(T.ASSIGN) || at(T.LPAREN) || at(T.LBRACE)) {
                    throw error("a spec's types are abstract: write just 'type $typeName', and define it in each module")
                }
                types += SpecType(memberPos, typeName, typeParams)
            } else {
                val valueName = ident("a type or a value in the spec")
                if (valueName[0].isUpperCase()) throw error("functions and values start with a lower-case letter", memberPos)
                val typeParams = typeParams()
                val params = if (at(T.LPAREN)) params(defaults = true) else null
                params?.firstOrNull { it.type == null }?.let { throw error("a spec's parameters need types: ${it.name}: Type", it.pos) }
                expect(T.COLON, if (params == null) "':' and the value's type" else "':' and the result type")
                val type = type()
                // A default: what a module that doesn't define it has.
                val default = if (accept(T.ASSIGN) != null) {
                    skipNewlines()
                    expr()
                } else {
                    null
                }
                values += SpecValue(memberPos, valueName, typeParams, params, type, annotations, default)
            }
        }
        return Decl.Spec(pos, name, types, values, typeParams = typeParams)
    }

    /** `<A, B>`; an effect parameter is `@E`, and named `@E` here. */
    private fun typeParams(): List<String> {
        if (accept(T.LT) == null) return emptyList()
        fun param() = (if (accept(T.AT) != null) "@" else "") + upperIdent("a type parameter")
        val names = mutableListOf(param())
        while (accept(T.COMMA) != null) names += param()
        expect(T.GT, "'>'")
        return names
    }

    /**
     * `a: A, b: B`, or a single unnamed `A`, which is named `value`. With [varargs], an annotation's,
     * the last can be `vararg`.
     */
    private fun fieldList(declPos: Pos, varargs: Boolean = false): List<FieldDef> {
        val fields = mutableListOf<FieldDef>()
        while (!at(T.RPAREN)) {
            val annotations = annotations()
            // `var balance: Int`: a field that can be changed. `var` is a keyword only here.
            val isVar = at(T.IDENT) && peek.text == "var" && peekAt(1).kind == T.IDENT && peekAt(2).kind == T.COLON
            if (isVar) next()
            // `vararg fixtures: Function`: an annotation's last field, given its items in order.
            val varargPos = peek.pos
            val isVararg = at(T.IDENT) && peek.text == "vararg" && peekAt(1).kind == T.IDENT && peekAt(2).kind == T.COLON
            if (isVararg) {
                next()
                if (!varargs) throw error("only an annotation's last field can be vararg", varargPos)
                if (fields.any { it.isVararg }) throw error("only an annotation's last field can be vararg", fields.first { it.isVararg }.pos)
            }
            val pos = peek.pos
            if (at(T.IDENT) && peekAt(1).kind == T.COLON) {
                val name = next().text
                next()
                val type = type()
                // `version: String = "0.1.0"`: what it is when a constructor call leaves it out.
                val default = if (accept(T.ASSIGN) != null) expr() else null
                if (isVararg && default != null) throw error("a vararg field is a list of what it's given, so it has no default: none is an empty list", pos)
                fields += FieldDef(pos, name, type, annotations, isVar, default, isVararg)
            } else {
                fields += FieldDef(pos, "", type(), annotations)
            }
            if (accept(T.COMMA) == null) break
        }
        val unnamed = fields.filter { it.name.isEmpty() }
        if (unnamed.isNotEmpty()) {
            if (fields.size > 1) throw error("only a type with a single field can leave it unnamed", unnamed.first().pos)
            return listOf(fields.single().copy(name = "value"))
        }
        if (fields.isEmpty()) throw error("a type needs at least one field", declPos)
        fields.dropLast(1).firstOrNull { it.isVararg }?.let { throw error("only an annotation's last field can be vararg", it.pos) }
        return fields
    }

    /** `(a: A, b: B)`; with [defaults], a declared function's, a parameter can have one: `b: B = value`. */
    private fun params(defaults: Boolean = false): List<Param> {
        expect(T.LPAREN, "'('")
        val params = mutableListOf<Param>()
        while (!at(T.RPAREN)) {
            val annotations = annotations()
            val pos = peek.pos
            // `_` for one that isn't used, as in Kotlin's lambdas.
            val name = if (accept(T.UNDERSCORE) != null) "_" else ident("a parameter name")
            val type = if (accept(T.COLON) != null) type() else null
            val default = if (at(T.ASSIGN)) {
                if (!defaults) throw error("only a declared function's parameters can have a default")
                next()
                expr()
            } else {
                null
            }
            params += Param(pos, name, type, annotations, default)
            if (accept(T.COMMA) == null) break
        }
        expect(T.RPAREN, "')'")
        return params
    }

    // ---- Annotations ----

    /** [annotations] before [d]'s own. */
    private fun annotate(d: Decl, annotations: List<Annotation>): Decl = when (d) {
        is Decl.Record -> d.copy(annotations = annotations + d.annotations)
        is Decl.Sealed -> d.copy(annotations = annotations + d.annotations)
        is Decl.Function -> d.copy(annotations = annotations + d.annotations)
        is Decl.Module -> d.copy(annotations = annotations + d.annotations)
        is Decl.Alias -> d.copy(annotations = annotations + d.annotations)
        is Decl.Spec -> d.copy(annotations = annotations + d.annotations)
    }

    /** `@Name` or `@Name(args)`, as many as there are, each on its line or before what it's on. */
    private fun annotations(): List<Annotation> {
        val annotations = mutableListOf<Annotation>()
        while (at(T.AT)) {
            val pos = next().pos
            var name = ident("an annotation's name")
            while (at(T.DOT) && peekAt(1).kind == T.IDENT) {
                next()
                name += "." + next().text
            }
            // `@Yielding<Int>`: a generic effect's type arguments.
            val typeArgs = if (at(T.LT) && tokens[p - 1].pos.line == peek.pos.line) {
                next()
                val list = mutableListOf(type())
                while (accept(T.COMMA) != null) list += type()
                expect(T.GT, "'>'")
                list
            } else {
                emptyList()
            }
            annotations += Annotation(pos, name, if (at(T.LPAREN)) metaArgs() else emptyList(), typeArgs)
            skipNewlines()
        }
        return annotations
    }

    private fun metaArgs(): List<MetaArg> {
        expect(T.LPAREN, "'('")
        val args = mutableListOf<MetaArg>()
        while (!at(T.RPAREN)) {
            val name = if (at(T.IDENT) && peekAt(1).kind == T.ASSIGN) next().text.also { next() } else null
            args += MetaArg(name, meta())
            if (accept(T.COMMA) == null) break
        }
        expect(T.RPAREN, "')'")
        return args
    }

    /** An annotation's argument: a constant or a reference, never a calculation. */
    private fun meta(): Meta {
        val pos = peek.pos
        if (at(T.TILDE) || (at(T.IDENT) && peekAt(1).kind == T.ARROW) || (at(T.LPAREN) && isLambdaParams())) {
            throw error("an annotation's arguments are constants and references, so not a lambda: name a function instead")
        }
        return when {
            at(T.INT) || at(T.FLOAT) || at(T.CHAR) || at(T.TRUE) || at(T.FALSE) -> Meta.Literal(primary())
            at(T.STRING) -> {
                val e = primary() as Expr.StringLit
                if (e.parts.any { it !is StringPart.Text }) throw error("an annotation's strings are constants, without templates", pos)
                Meta.Literal(e)
            }
            at(T.MINUS) && (peekAt(1).kind == T.INT || peekAt(1).kind == T.FLOAT) -> {
                next()
                Meta.Literal(Expr.Unary(pos, "-", primary()))
            }
            accept(T.LBRACKET) != null -> {
                val items = mutableListOf<Meta>()
                while (!at(T.RBRACKET)) {
                    items += meta()
                    if (accept(T.COMMA) == null) break
                }
                expect(T.RBRACKET, "']'")
                Meta.ListOf(pos, items)
            }
            accept(T.LPAREN) != null -> {
                val items = mutableListOf(meta())
                while (accept(T.COMMA) != null) items += meta()
                expect(T.RPAREN, "')'")
                items.singleOrNull() ?: Meta.TupleOf(pos, items)
            }
            at(T.IDENT) -> {
                var name = next().text
                while (at(T.DOT) && peekAt(1).kind == T.IDENT) {
                    next()
                    name += "." + next().text
                }
                // No comparisons here, so `<` always starts type arguments: List<User>.
                val typeArgs = if (accept(T.LT) != null) {
                    val args = mutableListOf(type())
                    while (accept(T.COMMA) != null) args += type()
                    expect(T.GT, "'>'")
                    args
                } else {
                    null
                }
                Meta.Ref(pos, name, typeArgs, if (at(T.LPAREN)) metaArgs() else null)
            }
            else -> throw error("an annotation's argument is a constant or a reference, like 1, \"text\", a name or List<Type>, not ${describe(peek)}")
        }
    }

    // ---- Types ----

    private fun type(): TypeRef {
        val pos = peek.pos
        // `@Console (String) -> Unit`: a function that may print. An effect here has no arguments,
        // but a generic one has type arguments: `@Yielding<Int> () -> Unit`.
        if (at(T.AT)) {
            val effects = mutableListOf<Annotation>()
            while (at(T.AT)) {
                val at = next().pos
                var name = ident("an effect's name")
                while (at(T.DOT) && peekAt(1).kind == T.IDENT) {
                    next()
                    name += "." + next().text
                }
                val typeArgs = if (accept(T.LT) != null) {
                    val list = mutableListOf(type())
                    while (accept(T.COMMA) != null) list += type()
                    expect(T.GT, "'>'")
                    list
                } else {
                    emptyList()
                }
                effects += Annotation(at, name, emptyList(), typeArgs)
            }
            // Effects alone are a type argument for an effect parameter: Queue<@Console>.
            if (!at(T.LPAREN)) return TypeRef.Effects(pos, effects)
            val function = type() as? TypeRef.Function ?: throw error("an effect goes before a function type, like @Console (String) -> Unit", pos)
            return function.copy(pos = pos, effects = effects + function.effects)
        }
        if (accept(T.LPAREN) != null) {
            val params = mutableListOf<TypeRef>()
            while (!at(T.RPAREN)) {
                params += type()
                if (accept(T.COMMA) == null) break
            }
            expect(T.RPAREN, "')'")
            if (accept(T.ARROW) == null) {
                return when (params.size) {
                    0 -> throw error("expected '->' after '()' in a function type")
                    1 -> params.single()
                    else -> TypeRef.Tuple(pos, params)
                }
            }
            return TypeRef.Function(pos, params, type())
        }
        // A type may be qualified by modules: Ratios.Ratio
        var name = if (at(T.IDENT) && peek.text[0].isLowerCase() && peekAt(1).kind == T.DOT && peekAt(2).kind == T.IDENT && peekAt(2).text[0].isUpperCase()) next().text else upperIdent("a type")
        while (at(T.DOT) && peekAt(1).kind == T.IDENT && peekAt(1).text[0].isUpperCase()) {
            next()
            name += "." + next().text
        }
        val args = mutableListOf<TypeRef>()
        if (accept(T.LT) != null) {
            // `_` marks where a spec's type takes its parameters: Monad<Result<_, String>>.
            do args += accept(T.UNDERSCORE)?.let { TypeRef.Named(it.pos, "_", emptyList()) } ?: given() ?: type() while (accept(T.COMMA) != null)
            expect(T.GT, "'>'")
        }
        return TypeRef.Named(pos, name, args)
    }

    private fun given(): TypeRef? {
        if (!at(T.IDENT) || peekAt(1).kind != T.ASSIGN) return null
        val name = next()
        next()
        return TypeRef.Given(name.pos, name.text, type())
    }

    // ---- Expressions ----

    fun expr(): Expr {
        lambdaStart()?.let { return it }
        return pipe()
    }

    /** Parses a lambda if one starts here: `x ->`, `(a, b) ->`, `() ->` or `~`. */
    private fun lambdaStart(): Expr? {
        val pos = peek.pos
        if (accept(T.TILDE) != null) return Expr.Lambda(pos, emptyList(), expr(), ignoresArguments = true)
        if ((at(T.IDENT) || at(T.UNDERSCORE)) && peekAt(1).kind == T.ARROW) {
            val name = if (at(T.UNDERSCORE)) next().let { "_" } else next().text
            next()
            return Expr.Lambda(pos, listOf(Param(pos, name, null)), expr())
        }
        if (at(T.LPAREN) && isLambdaParams()) {
            val params = params(defaults = true)
            params.firstOrNull { it.default != null }?.let { throw error("only a declared function's parameters can have a default", it.default!!.pos) }
            expect(T.ARROW, "'->'")
            return Expr.Lambda(pos, params, expr())
        }
        return null
    }

    /** Whether `(` starts a parameter list followed by `->`, found by trying to parse one. */
    /**
     * Type arguments and the `(` after them, read from here, or null if they aren't: the tokens
     * are read on success, and left otherwise.
     */
    private fun callTypeArgs(): List<TypeRef>? {
        val save = p
        val types = try {
            expect(T.LT, "'<'")
            val list = mutableListOf(type())
            while (accept(T.COMMA) != null) list += type()
            expect(T.GT, "'>'")
            list.takeIf { at(T.LPAREN) && tokens[p - 1].pos.line == peek.pos.line }
        } catch (_: CompileError) {
            null
        }
        if (types == null) p = save
        return types
    }

    private fun isLambdaParams(): Boolean {
        val save = p
        return try {
            params(defaults = true)
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
            skipNewlines()
            left = Expr.Binary(op.pos, op.text, left, operand())
        }
        return left
    }

    private fun or(): Expr = logical(T.OR) { and() }
    private fun and(): Expr = logical(T.AND) { equality() }
    private fun equality(): Expr = binaryLevel(setOf(T.EQ, T.NEQ)) { comparison() }
    private fun comparison(): Expr = binaryLevel(setOf(T.LT, T.GT, T.LE, T.GE)) { typeCheck() }

    /**
     * `e is T`, or `e !is T`, as Kotlin's: whether e is a case T, or of the type with cases T. It's
     * a `when` on e, `when (e) { is T -> true; else -> false }`, so where it decides something,
     * as an `if`'s condition, before `&&` or `||`, or a `when`'s branch, it's read as a `when` on
     * e deciding that, in whose branch for T e is narrowed, as `is T ->` narrows it.
     */
    private fun typeCheck(): Expr {
        var e = range()
        while (at(T.IS) || (at(T.BANG) && peekAt(1).kind == T.IS)) {
            val negated = accept(T.BANG) != null
            val pos = next().pos
            val check = Check(e, checkPattern(pos), negated)
            e = decide(check, pos, Expr.BoolLit(pos, true), Expr.BoolLit(pos, false))
            checks[e] = check
        }
        return e
    }

    /** `when (e) { is T -> yes; else -> no }`, from [check]: for `!is`, the other way round. */
    private fun decide(check: Check, pos: Pos, yes: Expr, no: Expr): Expr {
        val (onT, otherwise) = if (check.negated) no to yes else yes to no
        return Expr.When(pos, check.subject, listOf(Branch(pos, listOf(check.pattern), null, onT), Branch(pos, emptyList(), null, otherwise)))
    }

    /**
     * `a && b && c`, or `||`, left to right. Where a check is among them, the rest go in its `when`,
     * where what it checks is narrowed: `x is T && x.ready` reads x as a T after the `&&`.
     */
    private fun logical(op: T, operand: () -> Expr): Expr {
        val first = operand()
        if (!at(op)) return first
        val operands = mutableListOf(first)
        val positions = mutableListOf<Pos>()
        while (at(op)) {
            positions += next().pos
            skipNewlines()
            operands += operand()
        }
        val text = if (op == T.AND) "&&" else "||"
        if (operands.none { it in checks }) return operands.drop(1).withIndex().fold(first) { left, (i, right) -> Expr.Binary(positions[i], text, left, right) }
        return chain(op, operands, positions, 0).also { if (op == T.AND && operands.first() in checks) conjunctions[it] = operands to positions }
    }

    /** [operands] from the [i]th, joined by [op]: after a check, in its `when`. */
    private fun chain(op: T, operands: List<Expr>, positions: List<Pos>, i: Int): Expr {
        val operand = operands[i]
        if (i == operands.size - 1) return operand
        val rest = chain(op, operands, positions, i + 1)
        val check = checks[operand] ?: return Expr.Binary(positions[i], if (op == T.AND) "&&" else "||", operand, rest)
        return if (op == T.AND) decide(check, operand.pos, rest, Expr.BoolLit(operand.pos, false))
        else decide(check, operand.pos, Expr.BoolLit(operand.pos, true), rest)
    }

    /**
     * `if (x is T && ...) then else otherwise`, from the chain's [i]th operand on: each check a
     * `when` that narrows what it checks in the rest, and in then; otherwise is where any fails.
     */
    private fun ifChain(pos: Pos, operands: List<Expr>, positions: List<Pos>, i: Int, then: Expr, otherwise: Expr, hasElse: Boolean, copies: Copier): Expr {
        val check = checks[operands[i]]
            ?: return Expr.If(pos, chain(T.AND, operands, positions, i), then, copies.expr(otherwise), hasElse)
        val inside = if (i == operands.size - 1) then else ifChain(pos, operands, positions, i + 1, then, otherwise, hasElse, copies)
        return decide(check, operands[i].pos, inside, if (i == 0) otherwise else copies.expr(otherwise))
    }

    /** The type after `is`, which may be qualified: `is Circle`, `is Shapes.Circle`. */
    private fun checkPattern(pos: Pos): Pattern.Constructor {
        val path = mutableListOf(upperIdent("a type after 'is'"))
        while (at(T.DOT) && peekAt(1).kind == T.IDENT && peekAt(1).text[0].isUpperCase()) {
            next()
            path += next().text
        }
        return Pattern.Constructor(pos, path.last(), null, isCheck = true, qualifier = path.dropLast(1).joinToString(".").ifEmpty { null })
    }
    /** `a..b` and `a..<b`, as Kotlin's: tighter than a comparison, looser than `+` and `-`. */
    private fun range(): Expr = binaryLevel(setOf(T.RANGE, T.RANGE_UNTIL)) { additive() }
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
                // `emptyMap<String, Int>()`: types for the function's type parameters, if what's
                // between `<` and `>` is types and a call follows; otherwise `<` compares.
                at(T.LT) && (e is Expr.Name || e is Expr.Member) -> {
                    val typeArgs = callTypeArgs() ?: return e
                    Expr.Call(peek.pos, e, args(), typeArgs)
                }
                // `xs[i]` calls `xs.get(i)`, so any type with a get can be indexed. On the same
                // line only: a `[` that starts one is a list.
                at(T.LBRACKET) && tokens[p - 1].pos.line == peek.pos.line -> {
                    val pos = next().pos
                    val index = expr()
                    expect(T.RBRACKET, "']'")
                    Expr.Call(pos, Expr.Member(pos, e, "get"), listOf(Arg(null, index))).also { indexes += it }
                }
                // `Options { ... }`: a module's name right before a block runs the block in it.
                at(T.LBRACE) && isModulePath(e) -> Expr.Monadic(e.pos, e, block() as Expr.Block)
                // `Console(println = ...) { ... }`, `handler { ... }`: a handler value runs the block, on the same line;
                // or `path.reading { ... }`: a method given its last argument as a block.
                at(T.LBRACE) && (e is Expr.Call || (e is Expr.Name && e.name[0].isLowerCase()) || (e is Expr.Member && e.name[0].isLowerCase())) &&
                    tokens[p - 1].pos.line == peek.pos.line ->
                    trailingBlock().let { (params, block) -> Expr.Handle(e.pos, e, block, params) }
                else -> return e
            }
        }
    }

    private fun isModulePath(e: Expr): Boolean = when (e) {
        is Expr.Name -> e.name[0].isUpperCase()
        is Expr.Member -> e.name[0].isUpperCase() && isModulePath(e.target)
        else -> false
    }

    private fun args(): List<Arg> {
        expect(T.LPAREN, "'('")
        val args = mutableListOf<Arg>()
        while (!at(T.RPAREN)) {
            val name = if (at(T.IDENT) && peekAt(1).kind == T.ASSIGN) next().text.also { next() } else null
            args += Arg(name, expr())
            if (accept(T.COMMA) == null) break
        }
        // As in Kotlin, those passed in order come first; the checker says if one follows a named one.
        expect(T.RPAREN, "')'")
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
            T.CHAR -> Expr.CharLit(pos, next().text.codePointAt(0))
            T.IDENT -> if (isInterface && token.text == "compiled") Expr.Compiled(pos).also { next() } else Expr.Name(pos, next().text)
            T.LPAREN -> {
                next()
                val items = mutableListOf(expr())
                while (accept(T.COMMA) != null) items += expr()
                expect(T.RPAREN, "')'")
                items.singleOrNull() ?: Expr.Tuple(pos, items)
            }
            T.LBRACKET -> {
                next()
                val items = mutableListOf<Expr>()
                while (!at(T.RBRACKET)) {
                    // `*xs` spreads a list, as Kotlin's `*` spreads an array into varargs.
                    val spread = accept(T.STAR)
                    items += if (spread != null) Expr.Spread(spread.pos, expr()) else expr()
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
        val then = branch()
        val save = p
        skipNewlines()
        // Without an else, it's done for what it does, and gives Unit.
        val hasElse = accept(T.ELSE) != null
        if (!hasElse) p = save else skipNewlines()
        val otherwise = if (hasElse) branch() else Expr.Name(pos, "Unit")
        // On a check, a `when` on what it checks, narrowed in then.
        checks[condition]?.let { return decide(it, condition.pos, then, otherwise) }
        conjunctions[condition]?.let { (operands, positions) -> return ifChain(pos, operands, positions, 0, then, otherwise, hasElse, Copier({ name -> name })) }
        return Expr.If(pos, condition, then, otherwise, hasElse)
    }

    private fun whenExpr(): Expr {
        val pos = expect(T.WHEN, "'when'").pos
        // `when (a, b)` matches on the tuple of both.
        val subject = if (at(T.LPAREN)) {
            val subjectPos = next().pos
            val items = mutableListOf(expr())
            while (accept(T.COMMA) != null) items += expr()
            expect(T.RPAREN, "')'")
            items.singleOrNull() ?: Expr.Tuple(subjectPos, items)
        } else {
            null
        }
        expect(T.LBRACE, "'{'")
        skipNewlines()
        val branches = mutableListOf<Branch>()
        while (!at(T.RBRACE)) {
            val branchPos = peek.pos
            if (accept(T.ELSE) != null) {
                // `else if ready ->`: whatever's left, if it holds.
                val guard = guard()
                if (guard != null && subject == null) throw error("a 'when' without a subject has conditions already: make this one a branch of its own, without 'else if'", guard.pos)
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, emptyList(), null, branch(), guard)
            } else if (subject == null) {
                // Not expr(): a bare `flag -> ...` is a condition here, not a lambda.
                val condition = pipe()
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, emptyList(), condition, branch())
            } else {
                val patterns = mutableListOf(pattern())
                while (accept(T.COMMA) != null) patterns += pattern()
                val guard = guard()
                expect(T.ARROW, "'->'")
                branches += Branch(branchPos, patterns, null, branch(), guard)
            }
            if (!at(T.RBRACE)) {
                if (!at(T.NEWLINE) && !at(T.SEMI)) throw error("expected end of line after a 'when' branch but found ${describe(peek)}")
                skipNewlines()
            }
        }
        expect(T.RBRACE, "'}'")
        return if (subject == null) checkedBranches(pos, branches) else Expr.When(pos, subject, branches)
    }

    /**
     * A `when` without a subject, whose branch on a check, `x is T ->`, decides on x: the branches
     * before it, then a `when` on x, narrowed in that branch, and with the branches after it as
     * its else. Without an else to end them, it's as written.
     */
    private fun checkedBranches(pos: Pos, branches: List<Branch>): Expr {
        val at = branches.indexOfFirst { it.condition != null && it.condition in checks }
        if (at < 0 || branches.last().condition != null) return Expr.When(pos, null, branches)
        val branch = branches[at]
        val rest = branches.drop(at + 1)
        val otherwise = rest.singleOrNull()?.body ?: checkedBranches(rest.first().pos, rest)
        val decided = decide(checks.getValue(branch.condition), branch.pos, branch.body, otherwise)
        return if (at == 0) decided else Expr.When(pos, null, branches.take(at) + Branch(branch.pos, emptyList(), null, decided))
    }

    /** `if n > 0` after a branch's patterns, as Kotlin 2.1 writes a guard. Not expr(): `ready ->` isn't a lambda here. */
    private fun guard(): Expr? = if (accept(T.IF) != null) pipe() else null

    private fun pattern(): Pattern {
        val pos = peek.pos
        return when {
            accept(T.UNDERSCORE) != null -> Pattern.Wildcard(pos)
            accept(T.LPAREN) != null -> {
                val items = mutableListOf(pattern())
                while (accept(T.COMMA) != null) items += pattern()
                expect(T.RPAREN, "')'")
                items.singleOrNull() ?: Pattern.Tuple(pos, items)
            }
            accept(T.LBRACKET) != null -> {
                val items = mutableListOf<Pattern>()
                var rest: Pattern? = null
                while (!at(T.RBRACKET)) {
                    if (accept(T.STAR) != null) {
                        rest = pattern()
                        if (rest !is Pattern.Bind && rest !is Pattern.Wildcard) throw error("'*' takes a name or '_'", rest.pos)
                        if (!at(T.RBRACKET)) throw error("'*rest' must come last in a list pattern")
                        break
                    }
                    items += pattern()
                    if (accept(T.COMMA) == null) break
                }
                expect(T.RBRACKET, "']'")
                Pattern.ListPattern(pos, items, rest)
            }
            accept(T.IS) != null -> {
                val path = mutableListOf(upperIdent("a type after 'is'"))
                while (accept(T.DOT) != null) path += upperIdent("a name")
                Pattern.Constructor(pos, path.last(), null, isCheck = true, qualifier = path.dropLast(1).joinToString(".").ifEmpty { null })
            }
            at(T.IDENT) && peek.text[0].isUpperCase() -> {
                // Shape.Circle, or with modules, Shapes.Shape.Circle: all but the last name qualify it.
                val path = mutableListOf(next().text)
                while (accept(T.DOT) != null) path += upperIdent("a case name")
                val qualifier = path.dropLast(1).joinToString(".").ifEmpty { null }
                val name = path.last()
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
                Pattern.Constructor(pos, name, args, isCheck = false, qualifier = qualifier)
            }
            at(T.IDENT) -> Pattern.Bind(pos, next().text)
            at(T.INT) || at(T.FLOAT) || at(T.STRING) || at(T.CHAR) || at(T.TRUE) || at(T.FALSE) -> Pattern.Literal(pos, primary())
            at(T.MINUS) && (peekAt(1).kind == T.INT || peekAt(1).kind == T.FLOAT) -> Pattern.Literal(pos, unary())
            else -> throw error("expected a pattern but found ${describe(peek)}")
        }
    }

    /** A block given as a function's last argument, with the parameters it starts with, if any: `{ a, b -> ... }`. */
    private fun trailingBlock(): Pair<List<Param>?, Expr.Block> {
        val pos = expect(T.LBRACE, "'{'").pos
        skipNewlines()
        var n = 0
        while (peekAt(n).kind == T.IDENT || peekAt(n).kind == T.UNDERSCORE) {
            n++
            if (peekAt(n).kind == T.COMMA) n++ else break
        }
        val params = if (n > 0 && peekAt(n).kind == T.ARROW) {
            buildList<Param> {
                while (!at(T.ARROW)) {
                    val name = next()
                    add(Param(name.pos, name.text, null))
                    accept(T.COMMA)
                }
                next()
            }
        } else {
            null
        }
        return params to blockRest(pos) as Expr.Block
    }

    private fun block(): Expr = blockRest(expect(T.LBRACE, "'{'").pos)

    /** A block's statements and result, after its `{` at [pos]. */
    private fun blockRest(pos: Pos): Expr {
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
        val last = statements.removeLastOrNull() ?: return Expr.Block(pos, emptyList(), Expr.Name(pos, "Unit"))
        if (last is Statement.Binding && !last.isVar && last.params == null && last.returnType == null) {
            return Expr.Block(pos, statements + last, Expr.Name(last.pos, "Unit"), endsWith = last)
        }
        if (last !is Statement.Effect) throw error("a block must end with an expression, not a binding", last.pos)
        return Expr.Block(pos, statements, last.expr)
    }

    private fun statement(): Statement {
        val pos = peek.pos
        binding()?.let { pattern ->
            skipNewlines()
            return Statement.Bind(pos, pattern, expr())
        }
        if (at(T.LPAREN)) destructuring()?.let { pattern ->
            skipNewlines()
            return Statement.Destructure(pos, pattern, expr())
        }
        // `var x = 0`: a local that can be changed. `var` is a keyword only here.
        if (at(T.IDENT) && peek.text == "var" && peekAt(1).kind == T.IDENT && (peekAt(2).kind == T.ASSIGN || peekAt(2).kind == T.COLON)) {
            next()
            val namePos = peek.pos
            val name = next().text
            val type = if (accept(T.COLON) != null) type() else null
            expect(T.ASSIGN, "'=' and the variable's first value")
            skipNewlines()
            return Statement.Binding(namePos, name, null, type, expr(), isVar = true)
        }
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
        val e = expr()
        return Statement.Effect(pos, assignment(e) ?: e)
    }

    /**
     * What changes [e], if an assignment follows it: `account.balance = 10`, `x += 1`, changing a
     * var; `xs[i] = v`, which calls `xs.set(i, v)`.
     */
    private fun assignment(e: Expr): Expr? {
        if (!at(T.ASSIGN) && !at(T.OP_ASSIGN)) return null
        if (e !is Expr.Name && e !is Expr.Member && e !in indexes) throw error("only a var, a var field or an index can be assigned to")
        val op = next()
        skipNewlines()
        val written = expr()
        val value = if (op.kind == T.ASSIGN) written else Expr.Binary(op.pos, op.text.dropLast(1), e, written)
        if (e is Expr.Call && e in indexes) {
            val get = e.callee as Expr.Member
            return Expr.Call(get.pos, Expr.Member(get.pos, get.target, "set"), listOf(e.args.single(), Arg(null, value)))
        }
        return Expr.Assign(op.pos, e, value)
    }

    /** An `if`'s or a `when`'s branch: an expression, or, as in Kotlin, an assignment, `if (c) x = 1`. */
    private fun branch(): Expr {
        val e = expr()
        return assignment(e) ?: e
    }

    /** `x by` at the start of a statement: parses the pattern and the `by`, or returns null having consumed nothing. */
    private fun binding(): Pattern? {
        if (!at(T.IDENT) && !at(T.UNDERSCORE) && !at(T.LPAREN)) return null
        val save = p
        try {
            val pattern = pattern()
            if (at(T.IDENT) && peek.text == "by") {
                next()
                return pattern
            }
        } catch (_: CompileError) {
        }
        p = save
        return null
    }

    /** `(a, b) =` at the start of a statement: parses the pattern and the `=`, or returns null having consumed nothing. */
    private fun destructuring(): Pattern? {
        val save = p
        try {
            val pattern = pattern()
            if (pattern is Pattern.Tuple && accept(T.ASSIGN) != null) return pattern
        } catch (_: CompileError) {
        }
        p = save
        return null
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
