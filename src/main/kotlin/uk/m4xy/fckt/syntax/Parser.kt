package uk.m4xy.fckt.syntax

import uk.m4xy.fckt.types.BUILTIN_TYPES

/** Recursive-descent parser for `.fk` source. */
class Parser(private val tokens: List<Token>) {
    private var p = 0

    /** The `get` calls written `xs[i]`, which `xs[i] = v` turns into a `set`. */
    private val indexes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Expr, Boolean>())

    companion object {
        fun parse(source: String, file: String? = null): SourceFile = Parser(Lexer(source, Pos(1, 1, file)).tokenize()).file()
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
        return SourceFile(receiverTypeParams(decls, typeNames(decls)))
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
                (if (at(T.RPAREN)) emptyList() else fieldList(pos)).also { expect(T.RPAREN, "')'") }
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
        if (accept(T.PRIVATE) != null) {
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
                    val args = mutableListOf<TypeRef>()
                    do {
                        val argPos = peek.pos
                        args += TypeRef.Named(argPos, moduleName("a module, by name"), emptyList())
                    } while (accept(T.COMMA) != null)
                    expect(T.RPAREN, "')'")
                    return Decl.Module(pos, name, emptyList(), application = TypeRef.Named(functorPos, functor, args))
                }
                // module Name(M: Spec, ...): a functor
                val params = mutableListOf<ModuleParam>()
                if (at(T.LT)) throw error("a functor takes its modules in parentheses: module $name(M: Spec)")
                if (accept(T.LPAREN) != null) {
                    do {
                        val paramPos = peek.pos
                        val paramName = upperIdent("a module parameter")
                        expect(T.COLON, "':' and the spec the module must follow")
                        params += ModuleParam(paramPos, paramName, specName())
                    } while (accept(T.COMMA) != null)
                    expect(T.RPAREN, "')'")
                }
                // module Name : Spec { ... }
                val spec = if (accept(T.COLON) != null) specName() else null
                expect(T.LBRACE, "'{' after the module name")
                val decls = members { decl() }
                Decl.Module(pos, name, decls, spec = spec, params = params)
            }
            // `spec` is a keyword only here, so it can still name a value.
            at(T.IDENT) && peek.text == "spec" && peekAt(1).kind == T.IDENT && (peekAt(2).kind == T.LBRACE || peekAt(2).kind == T.LT) -> {
                next()
                val name = upperIdent("a spec name")
                // A handled effect may be generic: @Effect spec Yielding<A> { yield(value: A): Unit }.
                val specTypeParams = typeParams()
                expect(T.LBRACE, "'{'")
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
                        val params = if (at(T.LPAREN)) params() else null
                        params?.firstOrNull { it.type == null }?.let { throw error("a spec's parameters need types: ${it.name}: Type", it.pos) }
                        expect(T.COLON, if (params == null) "':' and the value's type" else "':' and the result type")
                        values += SpecValue(memberPos, valueName, typeParams, params, type(), annotations)
                    }
                }
                Decl.Spec(pos, name, types, values, typeParams = specTypeParams)
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
        val params = if (at(T.LPAREN)) params() else null
        val returnType = if (accept(T.COLON) != null) type() else null
        expect(T.ASSIGN, "'='")
        skipNewlines()
        val body = expr()
        if (receiver == null) return Decl.Function(pos, name, typeParams, params, returnType, body)
        // Named for its receiver once its type parameters are known, in receiverTypeParams.
        return Decl.Function(
            pos, "$$name", typeParams, listOf(Param(receiver.pos, "this", receiver)) + params.orEmpty(), returnType, body,
            extension = true, property = params == null,
        )
    }

    /** Bare names in an extension's receiver that aren't types, `A` in `List<A>`: its type parameters. */
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
                    }
                }
                val receiver = d.params!!.first().type!!
                walk(receiver)
                val params = (names - types - BUILTIN_TYPES.keys - d.typeParams.toSet()).toList()
                // Named for the receiver's type, without its type parameters: List<Int>.sum is ListInt$sum.
                fun mangle(t: TypeRef): String = when (t) {
                    is TypeRef.Named -> if (t.name in params) "" else t.name.substringAfterLast('.') + t.args.joinToString("") { mangle(it) }
                    is TypeRef.Tuple -> "Tuple" + t.elements.joinToString("") { mangle(it) }
                    is TypeRef.Function -> "Function"
                    is TypeRef.Effects -> ""
                }
                d.copy(name = mangle(receiver) + "$" + d.methodName, typeParams = params + d.typeParams)
            }
            else -> d
        }
    }

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

    /** `<A, B>`; an effect parameter is `@E`, and named `@E` here. */
    private fun typeParams(): List<String> {
        if (accept(T.LT) == null) return emptyList()
        fun param() = (if (accept(T.AT) != null) "@" else "") + upperIdent("a type parameter")
        val names = mutableListOf(param())
        while (accept(T.COMMA) != null) names += param()
        expect(T.GT, "'>'")
        return names
    }

    /** `a: A, b: B`, or a single unnamed `A`, which is named `value`. */
    private fun fieldList(declPos: Pos): List<FieldDef> {
        val fields = mutableListOf<FieldDef>()
        while (!at(T.RPAREN)) {
            val annotations = annotations()
            // `var balance: Int`: a field that can be changed. `var` is a keyword only here.
            val isVar = at(T.IDENT) && peek.text == "var" && peekAt(1).kind == T.IDENT && peekAt(2).kind == T.COLON
            if (isVar) next()
            val pos = peek.pos
            if (at(T.IDENT) && peekAt(1).kind == T.COLON) {
                val name = next().text
                next()
                fields += FieldDef(pos, name, type(), annotations, isVar)
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
        return fields
    }

    private fun params(): List<Param> {
        expect(T.LPAREN, "'('")
        val params = mutableListOf<Param>()
        while (!at(T.RPAREN)) {
            val annotations = annotations()
            val pos = peek.pos
            // `_` for one that isn't used, as in Kotlin's lambdas.
            val name = if (accept(T.UNDERSCORE) != null) "_" else ident("a parameter name")
            val type = if (accept(T.COLON) != null) type() else null
            params += Param(pos, name, type, annotations)
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
        var name = upperIdent("a type")
        while (at(T.DOT) && peekAt(1).kind == T.IDENT && peekAt(1).text[0].isUpperCase()) {
            next()
            name += "." + next().text
        }
        val args = mutableListOf<TypeRef>()
        if (accept(T.LT) != null) {
            // `_` marks where a spec's type takes its parameters: Monad<Result<_, String>>.
            do args += accept(T.UNDERSCORE)?.let { TypeRef.Named(it.pos, "_", emptyList()) } ?: type() while (accept(T.COMMA) != null)
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
        if (accept(T.TILDE) != null) return Expr.Lambda(pos, emptyList(), expr(), ignoresArguments = true)
        if ((at(T.IDENT) || at(T.UNDERSCORE)) && peekAt(1).kind == T.ARROW) {
            val name = if (at(T.UNDERSCORE)) next().let { "_" } else next().text
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
            skipNewlines()
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
                // `Console(println = ...) { ... }`, `handler { ... }`: a handler value runs the block, on the same line.
                at(T.LBRACE) && (e is Expr.Call || (e is Expr.Name && e.name[0].isLowerCase())) && tokens[p - 1].pos.line == peek.pos.line ->
                    Expr.Handle(e.pos, e, block() as Expr.Block)
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
            T.CHAR -> Expr.CharLit(pos, next().text.single())
            T.IDENT -> Expr.Name(pos, next().text)
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
        val then = expr()
        val save = p
        skipNewlines()
        // Without an else, it's done for what it does, and gives Unit.
        if (accept(T.ELSE) == null) {
            p = save
            return Expr.If(pos, condition, then, Expr.Name(pos, "Unit"), hasElse = false)
        }
        skipNewlines()
        return Expr.If(pos, condition, then, expr())
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
        // `account.balance = 10`, `x += 1`: changing a var; `xs[i] = v` calls `xs.set(i, v)`.
        if (at(T.ASSIGN) || at(T.OP_ASSIGN)) {
            if (e !is Expr.Name && e !is Expr.Member && e !in indexes) throw error("only a var, a var field or an index can be assigned to")
            val op = next()
            skipNewlines()
            val written = expr()
            val value = if (op.kind == T.ASSIGN) written else Expr.Binary(op.pos, op.text.dropLast(1), e, written)
            if (e is Expr.Call && e in indexes) {
                val get = e.callee as Expr.Member
                return Statement.Effect(pos, Expr.Call(get.pos, Expr.Member(get.pos, get.target, "set"), listOf(e.args.single(), Arg(null, value))))
            }
            return Statement.Effect(pos, Expr.Assign(op.pos, e, value))
        }
        return Statement.Effect(pos, e)
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
