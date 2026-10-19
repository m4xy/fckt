package uk.m4xy.fckt.ocaml

import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Param
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.syntax.VariantDef
import uk.m4xy.fckt.types.ASSERT
import uk.m4xy.fckt.types.ASSERT_EQUALS
import uk.m4xy.fckt.types.Builtin
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type
import uk.m4xy.fckt.types.stronglyConnected

/**
 * OCaml code that lowering makes up rather than translates: show functions and constructor
 * wrappers. [Src] embeds a checked fckt expression.
 */
private sealed interface Gen {
    data class Id(val name: String) : Gen
    data class Lit(val text: String) : Gen

    /** A string literal, kept unquoted so adjacent ones can merge. */
    data class Str(val text: String) : Gen
    data class App(val fn: Gen, val args: List<Gen>) : Gen
    data class Op(val op: String, val left: Gen, val right: Gen) : Gen
    data class Fun(val params: List<String>, val body: Gen) : Gen
    data class Field(val record: Gen, val name: String) : Gen
    data class Record(val entries: List<Pair<String, Gen>>) : Gen
    data class Ctor(val name: String, val arg: Gen?) : Gen
    data class Ann(val expr: Gen, val type: String) : Gen
    data class Match(val subject: Gen, val cases: List<Pair<String, Gen>>) : Gen
    data class Tuple(val elements: List<Gen>) : Gen
    data class If(val condition: Gen, val then: Gen, val otherwise: Gen) : Gen
    data class ListOf(val items: List<Gen>) : Gen
    data class Src(val expr: Expr) : Gen
}

private fun concat(parts: List<Gen>): Gen {
    val merged = mutableListOf<Gen>()
    for (part in parts) {
        val last = merged.lastOrNull()
        if (part is Gen.Str && last is Gen.Str) merged[merged.lastIndex] = Gen.Str(last.text + part.text) else merged += part
    }
    return if (merged.isEmpty()) Gen.Str("") else merged.reduceRight { part, rest -> Gen.Op("^", part, rest) }
}

/** Translates a checked program into the Kastor-built OCaml tree. */
class Lowering(private val program: Program) {
    private val records = program.records.associateBy { it.name }
    private val sealeds = program.sealeds.associateBy { it.name }

    /** User types whose show function is needed, and the `fk_` helpers that are. */
    private val showTypes = linkedSetOf<String>()
    private val helpers = linkedSetOf<String>()

    /** The module being lowered: names are written relative to it. */
    private var currentPath: List<String> = emptyList()

    /** Show functions for user types, by type, once generated. */
    private val showBodies = linkedMapOf<String, Pair<List<String>, Gen>>()

    fun lower(): StructureElement<Unit> {
        // Lower every function first; that finds which show functions are needed.
        val functionItems = program.groups.associateWith { group ->
            currentPath = group.decls.first().path
            StructureElement.build<Unit> {
                let(group.recursive) {
                    for (decl in group.decls) function(decl)
                }
            }.children.single()
        }
        // Each show function can need those of its fields' types.
        while (true) {
            val name = showTypes.firstOrNull { it !in showBodies } ?: break
            currentPath = name.split('.').dropLast(1)
            showBodies[name] = showBody(name)
        }
        currentPath = emptyList()
        val helperItems = StructureElement.build<Unit> { if (helpers.isNotEmpty()) helperFunctions() }.children
        val main = program.main?.let {
            StructureElement.build<Unit> {
                let(false) {
                    binding("()") {
                        apply {
                            ident("main")
                            // The command-line arguments, without the program name.
                            if (it.params!!.isEmpty()) literal("()")
                            else apply { ident("List.tl"); apply { ident("Array.to_list"); ident("Sys.argv") } }
                        }
                    }
                }
            }.children
        }.orEmpty()
        return StructureElement(helperItems + moduleItems(emptyList(), program.root, functionItems) + main)
    }

    // ---- Modules ----

    /**
     * The items of the module at [path]: its types (each followed by its show functions), nested
     * modules and functions, ordered so each comes after what it uses.
     */
    private fun moduleItems(
        path: List<String>,
        decls: List<Decl>,
        functionItems: Map<uk.m4xy.fckt.types.FunctionGroup, StructureChild<Unit>>,
    ): List<StructureChild<Unit>> {
        currentPath = path
        val types = decls.filter { it is Decl.Record || it is Decl.Sealed }
        val modules = decls.filterIsInstance<Decl.Module>()
        val groups = program.groups.filter { it.decls.first().path == path }

        // Graph nodes, in the order they'd be emitted given no constraints: types, modules, functions.
        val typeGroups = typeGroups(types)
        val nodes: List<Any> = typeGroups + modules + groups
        fun insideOf(m: Decl.Module, name: String) = name.startsWith(m.name + ".")
        val edges = nodes.associateWith { node ->
            when (node) {
                is Decl.Module -> {
                    // A module comes after the functions here that its own functions use.
                    val used = functionsIn(node).flatMap { program.uses[it].orEmpty() }
                    groups.filter { g -> g.decls.any { it in used } }
                }
                is uk.m4xy.fckt.types.FunctionGroup -> {
                    val used = node.decls.flatMap { program.uses[it].orEmpty() }
                    modules.filter { m -> used.any { insideOf(m, it.name) } }
                }
                else -> {
                    // A type comes after the modules whose types it uses.
                    @Suppress("UNCHECKED_CAST")
                    val names = (node as List<Decl>).flatMap { typeRefsOf(it) }
                    modules.filter { m -> names.any { insideOf(m, it) } }
                }
            }
        }
        val ordered = uk.m4xy.fckt.types.stronglyConnected(nodes) { edges.getValue(it) }.map { component ->
            if (component.size > 1) {
                val names = component.map { n -> if (n is Decl.Module) "module ${n.simpleName}" else "its functions" }
                throw CompileError((component.first { it is Decl.Module } as Decl.Module).pos, "${names.joinToString(" and ")} use each other")
            }
            component.single()
        }
        return ordered.flatMap { node ->
            currentPath = path
            when (node) {
                is Decl.Module -> listOf(StructureChild.ModuleDef(moduleDef(node, functionItems)))
                is uk.m4xy.fckt.types.FunctionGroup -> listOf(functionItems.getValue(node))
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val group = node as List<Decl>
                    StructureElement.build<Unit> {
                        types { typeDefinitions(group, forSignature = false) }
                        showFunctions(group.map { it.name })
                    }.children
                }
            }
        }
    }

    private fun moduleDef(m: Decl.Module, functionItems: Map<uk.m4xy.fckt.types.FunctionGroup, StructureChild<Unit>>): ModuleDefElement<Unit> {
        val path = m.name.split('.')
        val items = moduleItems(path, m.decls, functionItems).map { child ->
            when (child) {
                is StructureChild.TypeGroup -> ModuleDefChild.TypeGroup(child.element)
                is StructureChild.LetGroup -> ModuleDefChild.LetGroup(child.element)
                is StructureChild.Comment -> ModuleDefChild.Comment(child.element)
                is StructureChild.ModuleDef -> ModuleDefChild.ModuleDef(child.element)
            }
        }
        // A module with private members states what it shows; otherwise OCaml infers it.
        currentPath = path
        val signature = if (hasPrivate(m)) {
            listOf(ModuleDefChild.Signature(SignatureElement.build<Unit> { signatureItems(m) }))
        } else {
            emptyList()
        }
        return ModuleDefElement(name = uk.m4xy.kastor.Attribute.Value(m.simpleName), children = signature + items)
    }

    private fun hasPrivate(m: Decl.Module): Boolean = m.decls.any {
        it.isPrivate || (it is Decl.Record && it.privateConstructor) || (it is Decl.Module && hasPrivate(it))
    }

    private fun functionsIn(m: Decl.Module): List<Decl.Function> = m.decls.flatMap {
        when (it) {
            is Decl.Function -> listOf(it)
            is Decl.Module -> functionsIn(it)
            else -> emptyList()
        }
    }

    /** The full names of the user types a type declaration's fields use. */
    private fun typeRefsOf(d: Decl): List<String> {
        fun refs(ref: TypeRef): List<String> = when (ref) {
            is TypeRef.Named -> listOfNotNull(program.typeNames[ref]) + ref.args.flatMap(::refs)
            is TypeRef.Function -> ref.params.flatMap(::refs) + refs(ref.result)
            is TypeRef.Tuple -> ref.elements.flatMap(::refs)
        }
        val fields = when (d) {
            is Decl.Record -> d.fields
            is Decl.Sealed -> program.cases.getValue(d.name).flatMap { it.fields }
            else -> emptyList()
        }
        val origins = (d as? Decl.Sealed)?.let { s -> program.cases.getValue(s.name).map { it.origin.name } }.orEmpty()
        return fields.flatMap { refs(it.type) } + origins
    }

    /** What module [m] shows outside: its public types, nested modules, show functions and values. */
    private fun <S> S.signatureItems(m: Decl.Module) where S : TypeGroupParent<Unit>, S : SigValueParent<Unit>, S : SigModuleParent<Unit> {
        val path = m.name.split('.')
        currentPath = path
        val types = m.decls.filter { (it is Decl.Record || it is Decl.Sealed) && !it.isPrivate }
        for (group in typeGroups(types)) types { typeDefinitions(group, forSignature = true) }
        for (inner in m.decls.filterIsInstance<Decl.Module>().filter { !it.isPrivate }) {
            sigModule(inner.simpleName) { signatureItems(inner) }
            currentPath = path
        }
        for (type in types) {
            if (type.name !in showBodies) continue
            val params = if (type is Decl.Record) type.typeParams else (type as Decl.Sealed).typeParams
            val shown = applied(type.name, params.map { typeVar(it) to false })
            sigValue(showName(type.name), (params.map { "(${typeVar(it)} -> string)" } + shown + "string").joinToString(" -> "))
        }
        for (f in m.decls.filterIsInstance<Decl.Function>().filter { !it.isPrivate }) {
            val type = program.functionTypes.getValue(f)
            sigValue(snake(f.simpleName), typeText(type, mutableMapOf()))
        }
    }

    /** [full] as written from the current module: `Ratios.make` is `make` inside Ratios, and `Ratios.make` outside. */
    private fun ref(full: String, transform: (String) -> String): String {
        val parts = full.split('.')
        val module = parts.dropLast(1)
        val common = module.zip(currentPath).takeWhile { (a, b) -> a == b }.size
        return (module.drop(common) + transform(parts.last())).joinToString(".")
    }

    /** What to put before a record's labels from here: `Ratios.` outside Ratios, else nothing. */
    private fun labelPrefix(record: Decl.Record): String = ref((record.path + "x").joinToString("."), { "" })

    // ---- Types ----

    /** A group of type definitions; [forSignature] makes a record with a private constructor `private`. */
    private fun TypeGroupScope<Unit>.typeDefinitions(group: List<Decl>, forSignature: Boolean) {
        for (decl in group) {
            when (decl) {
                is Decl.Record -> typeDef(snake(decl.simpleName)) {
                    params = decl.typeParams.map(::typeVar)
                    if (forSignature && decl.privateConstructor) isPrivate = true
                    for (field in decl.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams))
                }
                is Decl.Sealed -> if (decl.name in program.polymorphic) {
                    typeDef(snake(decl.simpleName)) {
                        params = decl.typeParams.map(::typeVar)
                        polymorphic = true
                        for (case in program.cases.getValue(decl.name)) {
                            variant(tagName(case.origin, case.variant)) {
                                for (field in case.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams))
                            }
                        }
                    }
                } else typeDef(snake(decl.simpleName)) {
                    params = decl.typeParams.map(::typeVar)
                    for (variant in decl.variants) {
                        variant(variant.name) {
                            for (field in variant.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams))
                        }
                    }
                }
                is Decl.Function, is Decl.Module -> {}
            }
        }
    }

    /** [types] in dependency order, grouped only where they're mutually recursive. */
    private fun typeGroups(types: List<Decl>): List<List<Decl>> {
        val byName = types.associateBy { it.name }
        return stronglyConnected(types) { decl ->
            typeRefsOf(decl).mapNotNull { byName[it] }.distinct()
        }
    }

    /** OCaml syntax for a written type, with [typeParams] as type variables. */
    private fun typeText(ref: TypeRef, typeParams: List<String>): String = when (ref) {
        is TypeRef.Function -> functionText(ref.params.map { typeText(it, typeParams) to compound(it) }, typeText(ref.result, typeParams))
        is TypeRef.Tuple -> tupleText(ref.elements.map { typeText(it, typeParams) to compound(it) })
        is TypeRef.Named ->
            if (ref.name in typeParams) typeVar(ref.name)
            else applied(program.typeNames[ref] ?: ref.name, ref.args.map { typeText(it, typeParams) to compound(it) })
    }

    /** Whether a type needs parentheses as an argument or a tuple element. */
    private fun compound(ref: TypeRef) = ref is TypeRef.Function || ref is TypeRef.Tuple
    private fun compound(type: Type) = type.prune().let { it is Type.Fn || (it is Type.Con && it.name == Type.TUPLE) }

    private fun tupleText(elements: List<Pair<String, Boolean>>) =
        elements.joinToString(" * ") { (text, paren) -> if (paren) "($text)" else text }

    /**
     * OCaml syntax for an inferred type. Unknown parts become `_`, or, given [vars] (for a
     * signature), named type variables.
     */
    private fun typeText(type: Type, vars: MutableMap<Type.Var, String>? = null): String = when (val t = type.prune()) {
        is Type.Var -> vars?.getOrPut(t) { "'" + ('a' + vars.size) } ?: "_"
        is Type.Fn -> functionText(t.params.map { typeText(it, vars) to compound(it) }, typeText(t.result, vars))
        is Type.Con ->
            if (t.name == Type.TUPLE) tupleText(t.args.map { typeText(it, vars) to compound(it) })
            else applied(t.name, t.args.map { typeText(it, vars) to compound(it) })
    }

    private fun functionText(params: List<Pair<String, Boolean>>, result: String): String {
        val ps = if (params.isEmpty()) listOf("unit") else params.map { (text, paren) -> if (paren) "($text)" else text }
        return (ps + result).joinToString(" -> ")
    }

    private fun applied(name: String, args: List<Pair<String, Boolean>>): String {
        val base = when (name) {
            "Int" -> "int"; "Float" -> "float"; "String" -> "string"; "Boolean" -> "bool"; "Unit" -> "unit"
            "Char" -> "char"; "List" -> "list"; "Option" -> "option"; "Lazy" -> "Lazy.t"
            else -> ref(name, ::snake)
        }
        return when (args.size) {
            0 -> base
            1 -> args[0].let { (text, paren) -> if (paren) "($text) $base" else "$text $base" }
            else -> "(${args.joinToString(", ") { it.first }}) $base"
        }
    }

    // ---- Functions ----

    private fun LetGroupScope<Unit>.function(decl: Decl.Function) {
        binding(snake(decl.simpleName)) {
            decl.params?.let { params = paramTexts(it, decl.typeParams) }
            decl.returnType?.let { returnType = typeText(it, decl.typeParams) }
            expr(decl.body)
        }
    }

    private fun paramTexts(params: List<Param>, typeParams: List<String>): List<String> =
        if (params.isEmpty()) listOf("()")
        else params.map { p -> p.type?.let { "(${snake(p.name)} : ${typeText(it, typeParams)})" } ?: snake(p.name) }

    // ---- Expressions ----
    // Every expression scope implements the same parent sealed types, so these generic extensions
    // can add an expression to any of them.

    private fun <S> S.expr(e: Expr) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        // A narrower sealed type used as a wider one: (v :> alphabet).
        val widened = e.coerceTo ?: return exprUnwidened(e)
        annotated(typeText(widened)) {
            coerce = true
            exprUnwidened(e)
        }
    }

    private fun <S> S.exprUnwidened(e: Expr) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        when (e) {
            is Expr.IntLit -> literal(if (e.type?.prune() == Type.FLOAT) "${e.value}." else e.value.toString())
            is Expr.FloatLit -> literal(e.value.toString())
            is Expr.BoolLit -> literal(e.value.toString())
            is Expr.CharLit -> literal(charText(e.value))
            is Expr.Tuple -> tuple { e.elements.forEach { expr(it) } }
            is Expr.Spread -> throw CompileError(e.pos, "'*' can only spread a list inside a list literal")
            is Expr.StringLit -> gen(
                concat(
                    e.parts.map { part ->
                        when (part) {
                            is StringPart.Text -> Gen.Str(part.text)
                            is StringPart.Interpolated -> {
                                val type = part.expr.type!!.prune()
                                if (type == Type.STRING) Gen.Src(part.expr) else Gen.App(show(type, part.expr), listOf(Gen.Src(part.expr)))
                            }
                        }
                    },
                ),
            )
            is Expr.Name -> when (val r = e.resolved!!) {
                is NameRef.Local -> ident(r.ocamlName)
                is NameRef.Global -> ident(ref(r.decl.name, ::snake))
                is NameRef.Builtin -> {
                    if (r.ocaml == ASSERT || r.ocaml == ASSERT_EQUALS) throw CompileError(e.pos, "${r.ocaml} must be called")
                    ident(r.ocaml)
                }
                is NameRef.Constructor -> {
                    val fn = e.type!!.prune()
                    if (fn is Type.Fn) {
                        val names = fn.params.indices.map { "x$it" }
                        gen(Gen.Fun(names, construct(r.ref, fn.result, names.map { Gen.Id(it) })))
                    } else {
                        gen(construct(r.ref, fn, emptyList()))
                    }
                }
            }
            is Expr.ListLit -> gen(listExpr(e.items))
            is Expr.Call -> call(e)
            is Expr.Member -> when (val r = e.resolved!!) {
                is MemberRef.Field -> fieldGet(labelPrefix(records.getValue(r.record)) + r.ocamlName) { gen(qualified(Gen.Src(e.target), r)) }
                is MemberRef.CastField -> fieldGet(r.ocamlName) { ident(r.recordVar) }
                is MemberRef.CastTagField -> ident(r.variable)
                is MemberRef.Global -> ident(ref(r.decl.name, ::snake))
                is MemberRef.Case -> {
                    val fn = e.type!!.prune()
                    if (fn is Type.Fn) {
                        val names = fn.params.indices.map { "x$it" }
                        gen(Gen.Fun(names, construct(r.ref, fn.result, names.map { Gen.Id(it) })))
                    } else {
                        gen(construct(r.ref, fn, emptyList()))
                    }
                }
                is MemberRef.Builtin -> gen(builtin(r.method, e.target, emptyList()))
            }
            is Expr.Binary -> {
                val r = e.resolved!!
                fun side(x: Expr, promote: Boolean): Gen = if (promote) Gen.App(Gen.Id("float_of_int"), listOf(Gen.Src(x))) else Gen.Src(x)
                if (r.ocamlOp == "Float.rem") gen(Gen.App(Gen.Id("Float.rem"), listOf(side(e.left, r.promoteLeft), side(e.right, r.promoteRight))))
                else gen(Gen.Op(r.ocamlOp, side(e.left, r.promoteLeft), side(e.right, r.promoteRight)))
            }
            is Expr.Unary -> when {
                e.op == "!" -> apply { ident("not"); expr(e.operand) }
                e.operand is Expr.IntLit || e.operand is Expr.FloatLit -> {
                    val float = e.type?.prune() == Type.FLOAT
                    val text = when (val o = e.operand) {
                        is Expr.IntLit -> if (float) "${o.value}." else o.value.toString()
                        else -> (o as Expr.FloatLit).value.toString()
                    }
                    literal("-$text")
                }
                else -> apply {
                    ident(if (e.type?.prune() == Type.FLOAT) "Float.neg" else "Int.neg")
                    expr(e.operand)
                }
            }
            is Expr.Lambda -> lambda(e.paramNames.ifEmpty { listOf("()") }) { expr(e.body) }
            is Expr.Pipe -> infix("|>") {
                expr(e.value)
                val f = e.function
                if (e.partial && f is Expr.Call) {
                    apply {
                        expr(f.callee)
                        f.args.forEach { expr(it.value) }
                    }
                } else {
                    expr(f)
                }
            }
            is Expr.If -> ifThenElse {
                expr(e.condition)
                expr(e.then)
                expr(e.otherwise)
            }
            is Expr.When -> whenExpr(e)
            is Expr.Block -> block(e, 0)
        }
    }

    private fun <S> S.call(e: Expr.Call) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        val builtin = ((e.callee as? Expr.Name)?.resolved as? NameRef.Builtin)?.ocaml
        if (builtin == ASSERT || builtin == ASSERT_EQUALS) return gen(assertion(e, builtin))
        when (val r = e.resolved!!) {
            is CallRef.Function -> apply {
                expr(e.callee)
                if (r.order.isEmpty()) literal("()")
                r.order.forEach { expr(e.args[it].value) }
            }
            is CallRef.Construct -> gen(construct(r.ref, e.type!!, r.order.map { Gen.Src(e.args[it].value) }))
            is CallRef.Extension -> apply {
                ident(ref(r.decl.name, ::snake))
                expr((e.callee as Expr.Member).target)
                e.args.forEach { expr(it.value) }
            }
            is CallRef.Builtin -> gen(builtin(r.method, (e.callee as Expr.Member).target, e.args.map { it.value }))
            is CallRef.Copy -> {
                val target = (e.callee as Expr.Member).target
                // Replacing every field makes a new record; OCaml warns about a `with` that keeps nothing.
                if (e.args.size == r.record.fields.size) {
                    val byName = e.args.associate { it.name!! to it.value }
                    return gen(construct(ConstructorRef.Record(r.record), e.type!!, r.record.fields.map { Gen.Src(byName.getValue(it.name)) }))
                }
                val ambiguous = e.args.any { snake(it.name!!) in program.ambiguousLabels }
                val update: RecordUpdateScope<Unit>.() -> Unit = {
                    expr(target)
                    e.args.forEachIndexed { i, arg ->
                        entry((if (i == 0) labelPrefix(r.record) else "") + snake(arg.name!!)) { expr(arg.value) }
                    }
                }
                if (ambiguous) annotated(typeText(e.type!!)) { recordUpdate(update) } else recordUpdate(update)
            }
        }
    }

    /**
     * `assert(c)` is `if c then () else failwith "line N: assertion failed"`, and
     * `assertEquals(e, a)` calls a helper that shows both values, in Kotlin's words, when they differ.
     */
    private fun assertion(e: Expr.Call, name: String): Gen {
        val where = "line ${e.pos.line}"
        val args = e.args.map { it.value }
        if (name == ASSERT) {
            return Gen.If(Gen.Src(args[0]), Gen.Lit("()"), Gen.App(Gen.Id("failwith"), listOf(Gen.Str("$where: assertion failed"))))
        }
        return Gen.App(
            Gen.Id(helper("fk_assert_equals")),
            listOf(show(args[1].type!!, args[1]), Gen.Str(where), Gen.Src(args[0]), Gen.Src(args[1])),
        )
    }

    /** `[a, b]`, `a :: rest` or `xs @ rest`: elements are consed on, and a spread list is appended. */
    private fun listExpr(items: List<Expr>): Gen {
        if (items.none { it is Expr.Spread }) return Gen.ListOf(items.map { Gen.Src(it) })
        var result: Gen? = null
        for (item in items.asReversed()) {
            result = when {
                item is Expr.Spread -> if (result == null) Gen.Src(item.list) else Gen.Op("@", Gen.Src(item.list), result)
                else -> Gen.Op("::", Gen.Src(item), result ?: Gen.ListOf(emptyList()))
            }
        }
        return result!!
    }

    private fun charText(c: Char) = when (c) {
        '\'' -> "'\\''"
        '\\' -> "'\\\\'"
        '\n' -> "'\\n'"
        '\t' -> "'\\t'"
        '\r' -> "'\\r'"
        else -> if (c.code < 0x20) "'\\%03d'".format(c.code) else "'$c'"
    }

    private fun builtin(method: Builtin, receiver: Expr, args: List<Expr>): Gen = when (method) {
        is Builtin.Template -> Gen.App(
            Gen.Id(method.head),
            method.slots.map {
                when (it) {
                    Builtin.Slot.Receiver -> Gen.Src(receiver)
                    is Builtin.Slot.Arg -> Gen.Src(args[it.index])
                    is Builtin.Slot.Raw -> Gen.Lit(it.text)
                }
            },
        )
        Builtin.Show -> {
            val type = receiver.type!!.prune()
            if (type == Type.STRING) Gen.Src(receiver) else Gen.App(show(type, receiver), listOf(Gen.Src(receiver)))
        }
        Builtin.JoinToString -> {
            val element = (receiver.type!!.prune() as Type.Con).args[0].prune()
            val strings = if (element == Type.STRING) Gen.Src(receiver)
            else Gen.App(Gen.Id("List.map"), listOf(show(element, receiver), Gen.Src(receiver)))
            Gen.App(Gen.Id("String.concat"), listOf(Gen.Src(args[0]), strings))
        }
    }

    /** `{ a = x; ... }`, `Variant { ... }` or `Some x`, annotated with [type] when OCaml can't tell which it is. */
    private fun construct(ref: ConstructorRef, type: Type, args: List<Gen>): Gen = when (ref) {
        is ConstructorRef.Record -> {
            val prefix = labelPrefix(ref.decl)
            val record = Gen.Record(ref.decl.fields.mapIndexed { i, f -> (if (i == 0) prefix else "") + snake(f.name) }.zip(args))
            if (ref.decl.fields.any { snake(it.name) in program.ambiguousLabels }) Gen.Ann(record, typeText(type)) else record
        }
        is ConstructorRef.Variant -> {
            val variant = ref.variant
            val origin = ref.sealed
            // A shared case is a tag, carrying its fields as one value or a tuple.
            if (origin != null && variant != null && origin.name in program.polymorphic) {
                return Gen.Ctor(
                    "`" + tagName(origin, variant),
                    when (args.size) {
                        0 -> null
                        1 -> args.single()
                        else -> Gen.Tuple(args)
                    },
                )
            }
            val name = ctorName(ref)
            val value = when {
                variant == null -> Gen.Ctor(name, args.singleOrNull())
                variant.fields.isEmpty() -> Gen.Ctor(name, null)
                else -> Gen.Ctor(name, Gen.Record(variant.fields.map { snake(it.name) }.zip(args)))
            }
            if (ref.name in program.ambiguousConstructors) Gen.Ann(value, typeText(type)) else value
        }
    }

    /** A shared case's tag, named after the sealed type that declares it: `Alphabet_A`. */
    private fun tagName(origin: Decl.Sealed, variant: VariantDef) = "${origin.name.replace('.', '_')}_${variant.name}"

    /** An ordinary constructor as written from here: `Circle` inside Shapes, `Shapes.Circle` outside. */
    private fun ctorName(ref: ConstructorRef.Variant): String =
        ref.sealed?.let { ref((it.path + ref.name).joinToString("."), { name -> name }) } ?: ref.name

    /** The record of a field access, annotated with its type when the label belongs to several records. */
    private fun qualified(record: Gen, field: MemberRef.Field): Gen {
        if (field.ocamlName !in program.ambiguousLabels) return record
        val decl = records.getValue(field.record)
        return Gen.Ann(record, applied(decl.name, decl.typeParams.map { "_" to false }))
    }

    // ---- when ----

    private fun <S> S.whenExpr(e: Expr.When) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        val subject = e.subject
        if (subject == null) {
            conditions(e, 0)
            return
        }
        match {
            val annotate = e.annotateSubject || e.branches.any { b -> b.patterns.any { hasAmbiguousRecordPattern(it) } }
            if (annotate) annotated(typeText(subject.type!!)) { expr(subject) } else expr(subject)
            for (branch in e.branches) {
                val pattern = if (branch.patterns.isEmpty()) "_" else branch.patterns.joinToString(" | ") { pattern(it) }
                case(pattern) { expr(branch.body) }
            }
        }
    }

    private fun hasAmbiguousRecordPattern(p: Pattern): Boolean = p is Pattern.Constructor && (
        (p.resolved as? ConstructorRef.Record)?.decl?.fields?.any { snake(it.name) in program.ambiguousLabels } == true ||
            p.args?.any { hasAmbiguousRecordPattern(it) } == true
        )

    private fun <S> S.conditions(e: Expr.When, from: Int) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        val branch = e.branches[from]
        val condition = branch.condition ?: return expr(branch.body)
        ifThenElse {
            expr(condition)
            expr(branch.body)
            conditions(e, from + 1)
        }
    }

    private fun pattern(p: Pattern): String = when (p) {
        is Pattern.Wildcard -> "_"
        is Pattern.Bind -> p.ocamlName
        is Pattern.Literal -> literalText(p.expr)
        is Pattern.Tuple -> p.record?.let { r -> recordPattern(r.fields.map { it.name }, p.elements.map { pattern(it) }, labelPrefix(r)) }
            ?: p.elements.joinToString(", ", "(", ")") { pattern(it) }
        is Pattern.ListPattern -> {
            val rest = p.rest
            if (rest == null) p.elements.joinToString("; ", "[", "]") { pattern(it) }
            else (p.elements.map { atomPattern(pattern(it)) } + pattern(rest)).joinToString(" :: ")
        }
        is Pattern.Constructor -> {
            val args = p.args?.map { pattern(it) }
            val ref = p.resolved
            val narrowTo = p.narrowTo
            val origin = (ref as? ConstructorRef.Variant)?.sealed
            val variant = (ref as? ConstructorRef.Variant)?.variant
            when {
                // `is Vowel`: any of Vowel's tags, rebinding the subject at the narrower type.
                narrowTo != null ->
                    if (narrowTo.name !in program.polymorphic) "_"
                    else "#" + ref(narrowTo.name, ::snake) + (p.narrowVar?.let { " as $it" } ?: "")
                origin != null && variant != null && origin.name in program.polymorphic -> {
                    val tag = "`" + tagName(origin, variant)
                    val payload = args ?: p.castFieldVars ?: variant.fields.map { "_" }
                    when (payload.size) {
                        0 -> tag
                        1 -> "$tag ${atomPattern(payload.single())}"
                        else -> "$tag (${payload.joinToString(", ")})"
                    }
                }
                else -> ordinaryPattern(p, args, ref!!)
            }
        }
    }

    private fun ordinaryPattern(p: Pattern.Constructor, args: List<String>?, ref: ConstructorRef): String = when (ref) {
        is ConstructorRef.Record -> args?.let { recordPattern(ref.decl.fields.map { it.name }, it, labelPrefix(ref.decl)) } ?: "_"
        is ConstructorRef.Variant -> when {
            ref.variant == null -> if (ref.name == "None") "None" else "Some ${args?.single()?.let(::atomPattern) ?: "_"}"
            ref.variant.fields.isEmpty() -> ctorName(ref)
            args != null -> "${ctorName(ref)} ${recordPattern(ref.variant.fields.map { it.name }, args)}"
            else -> "${ctorName(ref)} ${p.castVar ?: "_"}"
        }
    }

    private fun recordPattern(fields: List<String>, args: List<String>, prefix: String = "") =
        "{ " + fields.zip(args).withIndex().joinToString("; ") { (i, fa) -> "${if (i == 0) prefix else ""}${snake(fa.first)} = ${fa.second}" } + " }"

    private fun atomPattern(p: String) =
        if (' ' in p && !p.startsWith("{") && !p.startsWith("(") && !p.startsWith("[") || p.startsWith("-")) "($p)" else p

    private fun literalText(e: Expr): String = when (e) {
        is Expr.IntLit -> e.value.toString()
        is Expr.FloatLit -> e.value.toString()
        is Expr.BoolLit -> e.value.toString()
        is Expr.CharLit -> charText(e.value)
        is Expr.StringLit -> quote(
            e.parts.joinToString("") {
                (it as? StringPart.Text)?.text ?: throw CompileError(e.pos, "a pattern can't contain a string template")
            },
        )
        is Expr.Unary -> "-" + literalText(e.operand)
        else -> throw CompileError(e.pos, "not a literal")
    }

    // ---- Blocks ----

    private fun <S> S.block(e: Expr.Block, from: Int) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        if (from == e.statements.size) return expr(e.result)
        when (val s = e.statements[from]) {
            is Statement.Binding -> letIn(s.ocamlName) {
                if (s.params != null) {
                    params = s.paramNames.ifEmpty { listOf("()") }
                    rec = s.recursive
                }
                expr(s.value)
                block(e, from + 1)
            }
            is Statement.Destructure -> letIn(pattern(s.pattern)) {
                expr(s.value)
                block(e, from + 1)
            }
            is Statement.Effect -> {
                // Run consecutive effects as one sequence.
                var end = from
                while (end < e.statements.size && e.statements[end] is Statement.Effect) end++
                sequence {
                    for (i in from until end) {
                        val effect = (e.statements[i] as Statement.Effect).expr
                        if (effect.type?.prune() == Type.UNIT) expr(effect) else apply { ident("ignore"); expr(effect) }
                    }
                    block(e, end)
                }
            }
        }
    }

    private fun <S> S.gen(g: Gen) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        when (g) {
            is Gen.Id -> ident(g.name)
            is Gen.Lit -> literal(g.text)
            is Gen.Str -> literal(quote(g.text))
            is Gen.App -> apply {
                gen(g.fn)
                g.args.forEach { gen(it) }
            }
            is Gen.Op -> infix(g.op) {
                gen(g.left)
                gen(g.right)
            }
            is Gen.Fun -> lambda(g.params) { gen(g.body) }
            is Gen.Field -> fieldGet(g.name) { gen(g.record) }
            is Gen.Record -> recordLit {
                for ((name, value) in g.entries) entry(name) { gen(value) }
            }
            is Gen.Ctor -> construct(g.name) { g.arg?.let { gen(it) } }
            is Gen.Ann -> annotated(g.type) { gen(g.expr) }
            is Gen.Match -> match {
                gen(g.subject)
                for ((pattern, body) in g.cases) case(pattern) { gen(body) }
            }
            is Gen.Src -> expr(g.expr)
            is Gen.Tuple -> tuple { g.elements.forEach { gen(it) } }
            is Gen.If -> ifThenElse {
                gen(g.condition)
                gen(g.then)
                gen(g.otherwise)
            }
            is Gen.ListOf -> listLit { g.items.forEach { gen(it) } }
        }
    }

    // ---- Showing values, for string templates and toString() ----

    /** A function that renders values of [type] like Kotlin's `toString()`. */
    private fun show(type: Type, at: Expr): Gen = when (val t = type.prune()) {
        is Type.Var -> throw CompileError(at.pos, "can't turn a value of unknown type into a string; annotate its type")
        is Type.Fn -> Gen.Fun(listOf("_"), Gen.Str("<fun>"))
        is Type.Con -> showCon(t.name, t.args.map { show(it, at) })
    }

    private fun showCon(name: String, args: List<Gen>): Gen = when (name) {
        Type.TUPLE -> {
            val names = args.indices.map { "x$it" }
            Gen.Fun(
                listOf(names.joinToString(", ", "(", ")")),
                concat(
                    listOf<Gen>(Gen.Str("(")) + args.flatMapIndexed { i, show ->
                        listOfNotNull(if (i > 0) Gen.Str(", ") else null, Gen.App(show, listOf(Gen.Id(names[i]))))
                    } + Gen.Str(")"),
                ),
            )
        }
        "Lazy" -> Gen.Fun(listOf("_"), Gen.Str("Lazy(...)"))
        "String" -> Gen.Id("Fun.id")
        "Int" -> Gen.Id("string_of_int")
        "Boolean" -> Gen.Id("string_of_bool")
        "Char" -> Gen.App(Gen.Id("String.make"), listOf(Gen.Lit("1")))
        "Unit" -> Gen.Fun(listOf("()"), Gen.Str("()"))
        "Float" -> Gen.Id(helper("fk_show_float"))
        "List" -> Gen.App(Gen.Id(helper("fk_show_list")), args)
        "Option" -> Gen.App(Gen.Id(helper("fk_show_option")), args)
        else -> {
            showTypes += name
            // A type's show function lives in the type's module.
            val show = ref((name.split('.').dropLast(1) + showName(name)).joinToString("."), { it })
            if (args.isEmpty()) Gen.Id(show) else Gen.App(Gen.Id(show), args)
        }
    }

    private fun showName(type: String) = "show_${snake(type.substringAfterLast('.')).removeSuffix("_")}"

    private fun helper(name: String) = name.also { helpers += it }

    /** [showCon] for a field's written type, inside the show function of a type with [typeParams]. */
    private fun showRef(ref: TypeRef, typeParams: List<String>): Gen = when (ref) {
        is TypeRef.Function -> Gen.Fun(listOf("_"), Gen.Str("<fun>"))
        is TypeRef.Tuple -> showCon(Type.TUPLE, ref.elements.map { showRef(it, typeParams) })
        is TypeRef.Named ->
            if (ref.name in typeParams) Gen.Id("show_${ref.name.lowercase()}")
            else showCon(program.typeNames[ref] ?: if (ref.name == "Double") "Float" else ref.name, ref.args.map { showRef(it, typeParams) })
    }

    /** The show functions for whichever of [types] need one; only those that call each other share a `let rec`. */
    private fun StructureScope<Unit>.showFunctions(types: List<String>) {
        val needed = types.filter { it in showBodies }
        val groups = stronglyConnected(needed) { type ->
            needed.filter { mentions(showBodies.getValue(type).second, setOf(showName(it))) }
        }
        for (group in groups) {
            val recursive = group.size > 1 || mentions(showBodies.getValue(group[0]).second, setOf(showName(group[0])))
            let(recursive) {
                for (name in group) {
                    val (params, body) = showBodies.getValue(name)
                    binding(showName(name)) {
                        this.params = params
                        gen(body)
                    }
                }
            }
        }
    }

    private fun mentions(g: Gen, names: Set<String>): Boolean = when (g) {
        is Gen.Id -> g.name in names
        is Gen.Lit, is Gen.Str, is Gen.Src -> false
        is Gen.App -> mentions(g.fn, names) || g.args.any { mentions(it, names) }
        is Gen.Op -> mentions(g.left, names) || mentions(g.right, names)
        is Gen.Fun -> mentions(g.body, names)
        is Gen.Field -> mentions(g.record, names)
        is Gen.Record -> g.entries.any { mentions(it.second, names) }
        is Gen.Ctor -> g.arg?.let { mentions(it, names) } ?: false
        is Gen.Ann -> mentions(g.expr, names)
        is Gen.Match -> mentions(g.subject, names) || g.cases.any { mentions(it.second, names) }
        is Gen.Tuple -> g.elements.any { mentions(it, names) }
        is Gen.If -> mentions(g.condition, names) || mentions(g.then, names) || mentions(g.otherwise, names)
        is Gen.ListOf -> g.items.any { mentions(it, names) }
    }

    private fun showBody(name: String): Pair<List<String>, Gen> {
        val v = Gen.Id("v")
        fun fieldsText(label: String, fields: List<uk.m4xy.fckt.syntax.FieldDef>, typeParams: List<String>, record: Gen): Gen =
            concat(
                listOf(Gen.Str("$label(")) +
                    fields.flatMapIndexed { i, f ->
                        listOf(
                            Gen.Str((if (i > 0) ", " else "") + f.name + "="),
                            Gen.App(showRef(f.type, typeParams), listOf(Gen.Field(record, snake(f.name)))),
                        )
                    } + Gen.Str(")"),
            )

        records[name]?.let { r ->
            val params = r.typeParams.map { "show_${it.lowercase()}" } +
                "(v : ${applied(r.name, r.typeParams.map { typeVar(it) to false })})"
            return params to fieldsText(r.simpleName, r.fields, r.typeParams, v)
        }
        val s = sealeds.getValue(name)
        val params = s.typeParams.map { "show_${it.lowercase()}" } + "(v : ${applied(s.name, s.typeParams.map { typeVar(it) to false })})"
        if (s.name in program.polymorphic) {
            // Tags carry their fields as a tuple; bind each to show it.
            return params to Gen.Match(
                v,
                program.cases.getValue(s.name).map { case ->
                    val tag = "`" + tagName(case.origin, case.variant)
                    val names = case.fields.indices.map { "x$it" }
                    val pattern = when (names.size) {
                        0 -> tag
                        1 -> "$tag x0"
                        else -> "$tag (${names.joinToString(", ")})"
                    }
                    pattern to if (case.fields.isEmpty()) Gen.Str(case.name)
                    else concat(
                        listOf<Gen>(Gen.Str("${case.name}(")) + case.fields.flatMapIndexed { i, f ->
                            listOf(
                                Gen.Str((if (i > 0) ", " else "") + f.name + "="),
                                Gen.App(showRef(f.type, s.typeParams), listOf(Gen.Id(names[i]))),
                            )
                        } + Gen.Str(")"),
                    )
                },
            )
        }
        return params to Gen.Match(
            v,
            s.variants.map { variant ->
                if (variant.fields.isEmpty()) variant.name to Gen.Str(variant.name)
                else "${variant.name} r" to fieldsText(variant.name, variant.fields, s.typeParams, Gen.Id("r"))
            },
        )
    }

    private fun StructureScope<Unit>.helperFunctions() {
        let(false) {
            if ("fk_show_float" in helpers) {
                // Like Kotlin: the shortest form that reads back as the same number, always with a
                // decimal point (1.0 where OCaml's string_of_float prints "1.").
                binding("fk_show_float") {
                    params = listOf("f")
                    letIn("shortest") {
                        params = listOf("digits")
                        rec = true
                        letIn("s") {
                            apply { ident("Printf.sprintf"); literal("\"%.*g\""); ident("digits"); ident("f") }
                            ifThenElse {
                                infix("||") {
                                    infix(">=") { ident("digits"); literal("17") }
                                    infix("=") { apply { ident("float_of_string"); ident("s") }; ident("f") }
                                }
                                ident("s")
                                apply { ident("shortest"); infix("+") { ident("digits"); literal("1") } }
                            }
                        }
                        letIn("s") {
                            apply { ident("shortest"); literal("15") }
                            ifThenElse {
                                apply {
                                    ident("String.exists")
                                    lambda(listOf("c")) { apply { ident("String.contains"); literal("\".eni\""); ident("c") } }
                                    ident("s")
                                }
                                ident("s")
                                infix("^") { ident("s"); literal("\".0\"") }
                            }
                        }
                    }
                }
            }
            if ("fk_assert_equals" in helpers) {
                binding("fk_assert_equals") {
                    params = listOf("show", "where", "expected", "actual")
                    gen(
                        Gen.If(
                            Gen.Op("=", Gen.Id("expected"), Gen.Id("actual")),
                            Gen.Lit("()"),
                            Gen.App(
                                Gen.Id("failwith"),
                                listOf(
                                    concat(
                                        listOf(
                                            Gen.Id("where"), Gen.Str(": expected:<"), Gen.App(Gen.Id("show"), listOf(Gen.Id("expected"))),
                                            Gen.Str("> but was:<"), Gen.App(Gen.Id("show"), listOf(Gen.Id("actual"))), Gen.Str(">"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
            if ("fk_show_list" in helpers) {
                binding("fk_show_list") {
                    params = listOf("show", "l")
                    gen(
                        concat(
                            listOf(
                                Gen.Str("["),
                                Gen.App(Gen.Id("String.concat"), listOf(Gen.Str(", "), Gen.App(Gen.Id("List.map"), listOf(Gen.Id("show"), Gen.Id("l"))))),
                                Gen.Str("]"),
                            ),
                        ),
                    )
                }
            }
            if ("fk_show_option" in helpers) {
                binding("fk_show_option") {
                    params = listOf("show", "o")
                    gen(
                        Gen.Match(
                            Gen.Id("o"),
                            listOf(
                                "Some x" to concat(listOf(Gen.Str("Some("), Gen.App(Gen.Id("show"), listOf(Gen.Id("x"))), Gen.Str(")"))),
                                "None" to Gen.Str("None"),
                            ),
                        ),
                    )
                }
            }
        }
    }
}
