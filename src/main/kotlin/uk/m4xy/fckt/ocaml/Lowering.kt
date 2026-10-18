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

    fun lower(): StructureElement<Unit> {
        val types = StructureElement.build<Unit> {
            for (group in typeGroups()) types { typeDefinitions(group) }
        }
        val functions = StructureElement.build<Unit> {
            for (group in program.groups) {
                let(group.recursive) {
                    for (decl in group.decls) function(decl)
                }
            }
            program.main?.let {
                let(false) {
                    binding("()") { apply { ident("main"); literal("()") } }
                }
            }
        }
        // Lowering the functions found which show functions they use; emit those before them.
        val shows = StructureElement.build<Unit> { showFunctions() }
        return StructureElement(types.children + shows.children + functions.children)
    }

    // ---- Types ----

    private fun TypeGroupScope<Unit>.typeDefinitions(group: List<Decl>) {
        for (decl in group) {
            when (decl) {
                is Decl.Record -> typeDef(snake(decl.name)) {
                    params = decl.typeParams.map(::typeVar)
                    for (field in decl.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams))
                }
                is Decl.Sealed -> typeDef(snake(decl.name)) {
                    params = decl.typeParams.map(::typeVar)
                    for (variant in decl.variants) {
                        variant(variant.name) {
                            for (field in variant.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams))
                        }
                    }
                }
                is Decl.Function -> {}
            }
        }
    }

    /** Type declarations in dependency order, grouped only where they're mutually recursive. */
    private fun typeGroups(): List<List<Decl>> {
        val decls = (program.records + program.sealeds).sortedWith(compareBy({ it.pos.line }, { it.pos.col }))
        val byName = decls.associateBy { it.name }
        fun refs(ref: TypeRef): List<String> = when (ref) {
            is TypeRef.Named -> listOf(ref.name) + ref.args.flatMap(::refs)
            is TypeRef.Function -> ref.params.flatMap(::refs) + refs(ref.result)
        }
        return stronglyConnected(decls) { decl ->
            val fields = when (decl) {
                is Decl.Record -> decl.fields
                is Decl.Sealed -> decl.variants.flatMap { it.fields }
                is Decl.Function -> emptyList()
            }
            fields.flatMap { refs(it.type) }.mapNotNull { byName[it] }.distinct()
        }
    }

    /** OCaml syntax for a written type, with [typeParams] as type variables. */
    private fun typeText(ref: TypeRef, typeParams: List<String>): String = when (ref) {
        is TypeRef.Function -> functionText(ref.params.map { typeText(it, typeParams) to (it is TypeRef.Function) }, typeText(ref.result, typeParams))
        is TypeRef.Named ->
            if (ref.name in typeParams) typeVar(ref.name)
            else applied(ref.name, ref.args.map { typeText(it, typeParams) to (it is TypeRef.Function) })
    }

    /** OCaml syntax for an inferred type; unknown parts become `_`. */
    private fun typeText(type: Type): String = when (val t = type.prune()) {
        is Type.Var -> "_"
        is Type.Fn -> functionText(t.params.map { typeText(it) to (it.prune() is Type.Fn) }, typeText(t.result))
        is Type.Con -> applied(t.name, t.args.map { typeText(it) to (it.prune() is Type.Fn) })
    }

    private fun functionText(params: List<Pair<String, Boolean>>, result: String): String {
        val ps = if (params.isEmpty()) listOf("unit") else params.map { (text, isFn) -> if (isFn) "($text)" else text }
        return (ps + result).joinToString(" -> ")
    }

    private fun applied(name: String, args: List<Pair<String, Boolean>>): String {
        val base = when (name) {
            "Int" -> "int"; "Float" -> "float"; "String" -> "string"; "Boolean" -> "bool"; "Unit" -> "unit"
            "Char" -> "char"; "List" -> "list"; "Option" -> "option"
            else -> snake(name)
        }
        return when (args.size) {
            0 -> base
            1 -> args[0].let { (text, isFn) -> if (isFn) "($text) $base" else "$text $base" }
            else -> "(${args.joinToString(", ") { it.first }}) $base"
        }
    }

    // ---- Functions ----

    private fun LetGroupScope<Unit>.function(decl: Decl.Function) {
        binding(snake(decl.name)) {
            decl.params?.let { params = paramTexts(it, decl.typeParams) }
            decl.returnType?.let { returnType = typeText(it, decl.typeParams) }
            expr(decl.body)
        }
    }

    private fun paramTexts(params: List<Param>, typeParams: List<String>): List<String> =
        if (params.isEmpty()) listOf("()")
        else params.map { p -> p.type?.let { "(${snake(p.name)} : ${typeText(it, typeParams)})" } ?: snake(p.name) }

    // ---- Expressions ----
    // Every expression scope implements the same parent interfaces, so these generic extensions
    // can add an expression to any of them.

    private fun <S> S.expr(e: Expr) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
        when (e) {
            is Expr.IntLit -> literal(if (e.type?.prune() == Type.FLOAT) "${e.value}." else e.value.toString())
            is Expr.FloatLit -> literal(e.value.toString())
            is Expr.BoolLit -> literal(e.value.toString())
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
                is NameRef.Global -> ident(snake(r.decl.name))
                is NameRef.Builtin -> ident(r.ocaml)
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
            is Expr.ListLit -> listLit { e.items.forEach { expr(it) } }
            is Expr.Call -> call(e)
            is Expr.Member -> when (val r = e.resolved!!) {
                is MemberRef.Field -> fieldGet(r.ocamlName) { gen(qualified(Gen.Src(e.target), r)) }
                is MemberRef.CastField -> fieldGet(r.ocamlName) { ident(r.recordVar) }
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
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
        when (val r = e.resolved!!) {
            is CallRef.Function -> apply {
                expr(e.callee)
                if (r.order.isEmpty()) literal("()")
                r.order.forEach { expr(e.args[it].value) }
            }
            is CallRef.Construct -> gen(construct(r.ref, e.type!!, r.order.map { Gen.Src(e.args[it].value) }))
            is CallRef.Extension -> apply {
                ident(snake(r.decl.name))
                expr((e.callee as Expr.Member).target)
                e.args.forEach { expr(it.value) }
            }
            is CallRef.Builtin -> gen(builtin(r.method, (e.callee as Expr.Member).target, e.args.map { it.value }))
        }
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
            val record = Gen.Record(ref.decl.fields.map { snake(it.name) }.zip(args))
            if (ref.decl.fields.any { snake(it.name) in program.ambiguousLabels }) Gen.Ann(record, typeText(type)) else record
        }
        is ConstructorRef.Variant -> {
            val variant = ref.variant
            val value = when {
                variant == null -> Gen.Ctor(ref.name, args.singleOrNull())
                variant.fields.isEmpty() -> Gen.Ctor(ref.name, null)
                else -> Gen.Ctor(ref.name, Gen.Record(variant.fields.map { snake(it.name) }.zip(args)))
            }
            if (ref.name in program.ambiguousConstructors) Gen.Ann(value, typeText(type)) else value
        }
    }

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
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
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
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
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
        is Pattern.Constructor -> {
            val args = p.args?.map { pattern(it) }
            when (val ref = p.resolved!!) {
                is ConstructorRef.Record -> args?.let { recordPattern(ref.decl.fields.map { it.name }, it) } ?: "_"
                is ConstructorRef.Variant -> when {
                    ref.variant == null -> if (ref.name == "None") "None" else "Some ${args?.single()?.let(::atomPattern) ?: "_"}"
                    ref.variant.fields.isEmpty() -> ref.name
                    args != null -> "${ref.name} ${recordPattern(ref.variant.fields.map { it.name }, args)}"
                    else -> "${ref.name} ${p.castVar ?: "_"}"
                }
            }
        }
    }

    private fun recordPattern(fields: List<String>, args: List<String>) =
        "{ " + fields.zip(args).joinToString("; ") { (f, a) -> "${snake(f)} = $a" } + " }"

    private fun atomPattern(p: String) = if (' ' in p && !p.startsWith("{") || p.startsWith("-")) "($p)" else p

    private fun literalText(e: Expr): String = when (e) {
        is Expr.IntLit -> e.value.toString()
        is Expr.FloatLit -> e.value.toString()
        is Expr.BoolLit -> e.value.toString()
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
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
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
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit> {
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
            if (args.isEmpty()) Gen.Id(showName(name)) else Gen.App(Gen.Id(showName(name)), args)
        }
    }

    private fun showName(type: String) = "show_${snake(type).removeSuffix("_")}"

    private fun helper(name: String) = name.also { helpers += it }

    /** [showCon] for a field's written type, inside the show function of a type with [typeParams]. */
    private fun showRef(ref: TypeRef, typeParams: List<String>): Gen = when (ref) {
        is TypeRef.Function -> Gen.Fun(listOf("_"), Gen.Str("<fun>"))
        is TypeRef.Named ->
            if (ref.name in typeParams) Gen.Id("show_${ref.name.lowercase()}")
            else showCon(if (ref.name == "Double") "Float" else ref.name, ref.args.map { showRef(it, typeParams) })
    }

    private fun StructureScope<Unit>.showFunctions() {
        // Generate show functions until no new type is needed; each can need the types of its fields.
        val bodies = linkedMapOf<String, Pair<List<String>, Gen>>()
        while (true) {
            val name = showTypes.firstOrNull { it !in bodies } ?: break
            bodies[name] = showBody(name)
        }
        if ("fk_show_list" in helpers || "fk_show_option" in helpers || "fk_show_float" in helpers) helperFunctions()
        if (bodies.isEmpty()) return
        val names = bodies.keys.map(::showName).toSet()
        let(bodies.size > 1 || bodies.values.any { (_, body) -> mentions(body, names) }) {
            for ((name, body) in bodies) {
                binding(showName(name)) {
                    params = body.first
                    gen(body.second)
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
            return params to fieldsText(r.name, r.fields, r.typeParams, v)
        }
        val s = sealeds.getValue(name)
        val params = s.typeParams.map { "show_${it.lowercase()}" } + "(v : ${applied(s.name, s.typeParams.map { typeVar(it) to false })})"
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
                // Kotlin prints 1.0 where OCaml's string_of_float prints "1.".
                binding("fk_show_float") {
                    params = listOf("f")
                    letIn("s") {
                        apply { ident("string_of_float"); ident("f") }
                        ifThenElse {
                            apply { ident("String.ends_with"); literal("~suffix:\".\""); ident("s") }
                            infix("^") { ident("s"); literal("\"0\"") }
                            ident("s")
                        }
                    }
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
