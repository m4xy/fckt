package uk.m4xy.fckt.types

import uk.m4xy.fckt.ocaml.snake
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.BinaryRef
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.syntax.VariantDef

/** The checked program: declarations plus what lowering needs to know about them. */
class Program(
    val records: List<Decl.Record>,
    val sealeds: List<Decl.Sealed>,
    /** Functions and values in dependency order, each group mutually recursive (or a single, maybe self-recursive, one). */
    val groups: List<FunctionGroup>,
    val ambiguousLabels: Set<String>,
    val ambiguousConstructors: Set<String>,
    val main: Decl.Function?,
)

class FunctionGroup(val decls: List<Decl.Function>, val recursive: Boolean)

/** A constructor's shape once instantiated: field names and types, and the type it builds. */
class ConstructorShape(val ref: ConstructorRef, val fieldNames: List<String>, val fieldTypes: List<Type>, val result: Type)

private class Local(val type: Type, val ocamlName: String, val cast: Cast? = null)

/** `is Variant` narrowed a variable: [recordVar] is bound to the variant's inline record. */
private class Cast(val variant: VariantDef, val fieldTypes: Map<String, Type>, val recordVar: String)

private class Scope(val parent: Scope?) {
    val vars = mutableMapOf<String, Local>()
    fun lookup(name: String): Local? = vars[name] ?: parent?.lookup(name)
}

class Checker(private val file: SourceFile) {
    private val u = Unifier()
    private val records = linkedMapOf<String, Decl.Record>()
    private val sealeds = linkedMapOf<String, Decl.Sealed>()
    private val variantsByName = mutableMapOf<String, MutableList<Pair<Decl.Sealed, VariantDef>>>()
    private val globals = linkedMapOf<String, Decl.Function>()

    private val schemes = mutableMapOf<Decl.Function, Scheme>()
    private val provisional = mutableMapOf<Decl.Function, Type>()
    private var typeParams: Map<String, Type> = emptyMap()
    private var castCounter = 0

    fun check(): Program {
        collectTypes()
        collectGlobals()
        val groups = dependencyGroups()
        for (group in groups) checkGroup(group)

        val labelCounts = records.values.flatMap { r -> r.fields.map { snake(it.name) } }.groupingBy { it }.eachCount()
        val main = globals["main"]?.also {
            val fn = schemes.getValue(it).type as? Type.Fn
            val params = fn?.params
            if (params == null || params.size > 1 || (params.size == 1 && !u.unify(params[0], Type.list(Type.STRING)))) {
                throw CompileError(it.pos, "main must be declared as main() = ... or main(args: List<String>) = ...")
            }
            val result = fn.result
            if (!u.unify(result, Type.UNIT)) throw CompileError(it.pos, "main must return Unit, not $result")
        }
        return Program(
            records = records.values.toList(),
            sealeds = sealeds.values.toList(),
            groups = groups,
            ambiguousLabels = labelCounts.filterValues { it > 1 }.keys,
            ambiguousConstructors = variantsByName.filterValues { it.size > 1 }.keys,
            main = main,
        )
    }

    // ---- Declarations ----

    private fun collectTypes() {
        for (decl in file.decls) {
            if (decl is Decl.Function) continue
            if (decl.name in BUILTIN_TYPES) throw CompileError(decl.pos, "'${decl.name}' is a built-in type")
            if (decl.name in records || decl.name in sealeds) throw CompileError(decl.pos, "type '${decl.name}' is declared twice")
            when (decl) {
                is Decl.Record -> records[decl.name] = decl
                is Decl.Sealed -> {
                    if (decl.variants.isEmpty()) throw CompileError(decl.pos, "interface '${decl.name}' has no variants")
                    sealeds[decl.name] = decl
                    for (variant in decl.variants) {
                        if (variant.name == "Some" || variant.name == "None") {
                            throw CompileError(variant.pos, "'${variant.name}' is reserved for Option")
                        }
                        variantsByName.getOrPut(variant.name) { mutableListOf() } += decl to variant
                    }
                }
                is Decl.Function -> {}
            }
        }
        for ((name, variants) in variantsByName) {
            if (name in records) throw CompileError(variants.first().second.pos, "'$name' is both a type and a variant")
            variants.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
                throw CompileError(it[1].second.pos, "variant '$name' is declared twice in ${it[1].first.name}")
            }
        }
        // Resolve every field type now, so bad references are reported at the declaration.
        for (r in records.values) {
            checkFieldNames(r.fields.map { it.name to it.pos })
            withTypeParams(r.typeParams) { r.fields.forEach { resolve(it.type) } }
        }
        for (s in sealeds.values) {
            withTypeParams(s.typeParams) {
                s.variants.forEach { v ->
                    checkFieldNames(v.fields.map { it.name to it.pos })
                    v.fields.forEach { resolve(it.type) }
                }
            }
        }
    }

    private fun checkFieldNames(fields: List<Pair<String, Pos>>) {
        fields.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
            throw CompileError(it[1].second, "field '${it[1].first}' is declared twice")
        }
    }

    private fun collectGlobals() {
        for (decl in file.decls.filterIsInstance<Decl.Function>()) {
            if (decl.name in globals) throw CompileError(decl.pos, "'${decl.name}' is declared twice")
            globals[decl.name] = decl
        }
    }

    private inline fun <R> withTypeParams(names: List<String>, block: () -> R): R {
        val saved = typeParams
        typeParams = names.associateWith { Type.Con("#$it") }
        try {
            return block()
        } finally {
            typeParams = saved
        }
    }

    /** Converts written type syntax to a [Type], with type parameters taken from [typeParams]. */
    private fun resolve(ref: TypeRef): Type = when (ref) {
        is TypeRef.Function -> Type.Fn(ref.params.map { resolve(it) }, resolve(ref.result))
        is TypeRef.Tuple -> Type.tuple(ref.elements.map { resolve(it) })
        is TypeRef.Named -> {
            val args = ref.args.map { resolve(it) }
            fun arity(n: Int) {
                if (args.size != n) throw CompileError(ref.pos, "'${ref.name}' takes $n type argument${if (n == 1) "" else "s"}, not ${args.size}")
            }
            typeParams[ref.name]?.also { arity(0) }
                ?: BUILTIN_TYPES[ref.name]?.let {
                    arity(it)
                    Type.Con(if (ref.name == "Double") "Float" else ref.name, args)
                }
                ?: records[ref.name]?.let {
                    arity(it.typeParams.size)
                    Type.Con(ref.name, args)
                }
                ?: sealeds[ref.name]?.let {
                    arity(it.typeParams.size)
                    Type.Con(ref.name, args)
                }
                ?: throw CompileError(ref.pos, "unknown type '${ref.name}'")
        }
    }

    /** Field types of a type declaration, with its type parameters replaced by [args]. */
    private fun fieldTypes(params: List<String>, fields: List<uk.m4xy.fckt.syntax.FieldDef>, args: List<Type>): List<Type> {
        val saved = typeParams
        typeParams = params.zip(args).toMap()
        try {
            return fields.map { resolve(it.type) }
        } finally {
            typeParams = saved
        }
    }

    // ---- Dependency order ----

    private fun dependencyGroups(): List<FunctionGroup> {
        val decls = globals.values.toList()
        val deps = decls.associateWith { decl ->
            val refs = linkedSetOf<String>()
            val bound = (decl.params?.map { it.name } ?: emptyList()).toSet()
            collectRefs(decl.body, bound, refs)
            refs.mapNotNull { globals[it] }
        }
        return stronglyConnected(decls) { deps.getValue(it) }.map { component ->
            val recursive = component.size > 1 || component[0] in deps.getValue(component[0])
            component.firstOrNull { it.params == null && recursive }?.let {
                throw CompileError(it.pos, "value '${it.name}' is defined in terms of itself")
            }
            FunctionGroup(component, recursive)
        }
    }

    /** Names an expression may refer to that aren't bound inside it. Over-approximates, which is harmless. */
    private fun collectRefs(e: Expr, bound: Set<String>, into: MutableSet<String>) {
        fun go(x: Expr, b: Set<String> = bound) = collectRefs(x, b, into)
        when (e) {
            is Expr.Name -> if (e.name !in bound) into += e.name
            is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit -> {}
            is Expr.Tuple -> e.elements.forEach { go(it) }
            is Expr.Spread -> go(e.list)
            is Expr.StringLit -> e.parts.forEach { if (it is StringPart.Interpolated) go(it.expr) }
            is Expr.ListLit -> e.items.forEach { go(it) }
            is Expr.Call -> {
                go(e.callee)
                e.args.forEach { go(it.value) }
            }
            is Expr.Member -> {
                go(e.target)
                if (e.name !in bound) into += e.name // possibly an extension call
            }
            is Expr.Binary -> {
                go(e.left)
                go(e.right)
            }
            is Expr.Unary -> go(e.operand)
            is Expr.Lambda -> go(e.body, bound + e.params.map { it.name })
            is Expr.Pipe -> {
                go(e.value)
                go(e.function)
            }
            is Expr.If -> {
                go(e.condition)
                go(e.then)
                go(e.otherwise)
            }
            is Expr.When -> {
                e.subject?.let { go(it) }
                for (branch in e.branches) {
                    branch.condition?.let { go(it) }
                    go(branch.body, bound + branch.patterns.flatMap { patternNames(it) })
                }
            }
            is Expr.Block -> {
                var b = bound
                for (s in e.statements) {
                    when (s) {
                        is Statement.Binding -> {
                            val inner = b + s.name + (s.params?.map { it.name } ?: emptyList())
                            go(s.value, if (s.params != null) inner else b)
                            b = b + s.name
                        }
                        is Statement.Effect -> go(s.expr, b)
                        is Statement.Destructure -> {
                            go(s.value, b)
                            b = b + patternNames(s.pattern)
                        }
                    }
                }
                go(e.result, b)
            }
        }
    }

    private fun patternNames(p: Pattern): List<String> = when (p) {
        is Pattern.Bind -> listOf(p.name)
        is Pattern.Constructor -> p.args?.flatMap { patternNames(it) } ?: emptyList()
        is Pattern.Tuple -> p.elements.flatMap { patternNames(it) }
        is Pattern.ListPattern -> p.elements.flatMap { patternNames(it) } + (p.rest?.let { patternNames(it) } ?: emptyList())
        is Pattern.Wildcard, is Pattern.Literal -> emptyList()
    }

    // ---- Functions ----

    private fun checkGroup(group: FunctionGroup) {
        val declaredParams = mutableMapOf<Decl.Function, Map<String, Type.Var>>()
        for (decl in group.decls) {
            val vars = decl.typeParams.associateWith { u.fresh() }
            declaredParams[decl] = vars
            typeParams = vars
            val result = decl.returnType?.let { resolve(it) } ?: u.fresh()
            provisional[decl] = decl.params?.let { params ->
                checkDistinct(params.map { it.name to it.pos })
                Type.Fn(params.map { p -> p.type?.let { resolve(it) } ?: u.fresh() }, result)
            } ?: result
        }
        for (decl in group.decls) {
            typeParams = declaredParams.getValue(decl)
            val type = provisional.getValue(decl)
            val scope = Scope(null)
            if (type is Type.Fn) {
                decl.params!!.zip(type.params).forEach { (p, t) -> scope.vars[p.name] = Local(t, snake(p.name)) }
                check(decl.body, type.result, scope)
            } else {
                check(decl.body, type, scope)
            }
        }
        typeParams = emptyMap()
        for (decl in group.decls) {
            // A declared type parameter must stay abstract: `id<T>(x: T): Int = x` is an error.
            val vars = declaredParams.getValue(decl)
            val seen = mutableSetOf<Type>()
            for ((name, v) in vars) {
                val t = v.prune()
                if (t !is Type.Var || !seen.add(t)) throw CompileError(decl.pos, "type parameter $name of '${decl.name}' is always $t here")
            }
            val type = provisional.remove(decl)!!.zonk()
            // Values aren't generalized: OCaml's value restriction wouldn't generalize most of them either.
            schemes[decl] = if (decl.params == null) Scheme.mono(type) else Scheme(type.freeVars().toList(), type)
        }
    }

    private fun checkDistinct(names: List<Pair<String, Pos>>) {
        names.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
            throw CompileError(it[1].second, "'${it[1].first}' is declared twice")
        }
    }

    private fun globalType(decl: Decl.Function): Type =
        provisional[decl] ?: u.instantiate(schemes[decl] ?: error("${decl.name} used before it was checked"))

    // ---- Expressions ----

    private fun mismatch(pos: Pos, expected: Type, actual: Type): Nothing =
        throw CompileError(pos, "type mismatch: expected $expected but found $actual")

    private fun expect(actual: Type, expected: Type, pos: Pos) {
        val a = actual.zonk()
        val e = expected.zonk()
        if (!u.unify(actual, expected)) mismatch(pos, e, a)
    }

    private fun check(e: Expr, expected: Type, scope: Scope) {
        expect(infer(e, expected, scope), expected, e.pos)
    }

    /** Infers [e]'s type; [expected], if known, guides lambdas, literals and constructors. */
    private fun infer(e: Expr, expected: Type?, scope: Scope): Type {
        val type = inferInner(e, expected, scope)
        e.type = type
        return type
    }

    private fun inferInner(e: Expr, expected: Type?, scope: Scope): Type = when (e) {
        // An integer literal can stand for a float where one is expected.
        is Expr.IntLit -> if (expected?.prune() == Type.FLOAT) Type.FLOAT else Type.INT
        is Expr.FloatLit -> Type.FLOAT
        is Expr.BoolLit -> Type.BOOLEAN
        is Expr.CharLit -> Type.CHAR
        is Expr.Tuple -> {
            val hint = (expected?.prune() as? Type.Con)?.takeIf { it.name == Type.TUPLE && it.args.size == e.elements.size }
            Type.tuple(e.elements.mapIndexed { i, x -> infer(x, hint?.args?.get(i), scope).also { t -> hint?.let { expect(t, it.args[i], x.pos) } } })
        }
        is Expr.Spread -> throw CompileError(e.pos, "'*' can only spread a list inside a list literal")
        is Expr.StringLit -> {
            e.parts.forEach { if (it is StringPart.Interpolated) infer(it.expr, null, scope) }
            Type.STRING
        }
        is Expr.Name -> inferName(e, expected, scope)
        is Expr.ListLit -> {
            val element = (expected?.prune() as? Type.Con)?.takeIf { it.name == "List" }?.args?.get(0) ?: u.fresh()
            for (item in e.items) {
                if (item is Expr.Spread) {
                    check(item.list, Type.list(element), scope)
                    item.type = Type.list(element)
                } else {
                    check(item, element, scope)
                }
            }
            Type.list(element)
        }
        is Expr.Call -> inferCall(e, expected, scope)
        is Expr.Member -> inferMember(e, scope)
        is Expr.Binary -> inferBinary(e, scope)
        is Expr.Unary -> when (e.op) {
            "!" -> Type.BOOLEAN.also { check(e.operand, Type.BOOLEAN, scope) }
            else -> {
                val t = infer(e.operand, expected, scope).prune()
                if (t is Type.Var) u.unify(t, Type.INT)
                if (t.prune() != Type.INT && t.prune() != Type.FLOAT) throw CompileError(e.pos, "can't negate a $t")
                t
            }
        }
        is Expr.Lambda -> inferLambda(e, expected, scope)
        is Expr.Pipe -> inferPipe(e, scope)
        is Expr.If -> {
            check(e.condition, Type.BOOLEAN, scope)
            val result = expected ?: u.fresh()
            check(e.then, result, scope)
            check(e.otherwise, result, scope)
            result
        }
        is Expr.When -> inferWhen(e, expected, scope)
        is Expr.Block -> inferBlock(e, expected, scope)
    }

    private fun inferName(e: Expr.Name, expected: Type?, scope: Scope): Type {
        scope.lookup(e.name)?.let {
            e.resolved = NameRef.Local(it.ocamlName)
            return it.type
        }
        globals[e.name]?.let {
            e.resolved = NameRef.Global(it)
            return globalType(it)
        }
        constructor(e.name, expected, e.pos, asValue = true)?.let { shape ->
            e.resolved = NameRef.Constructor(shape.ref)
            return if (shape.fieldTypes.isEmpty()) shape.result else Type.Fn(shape.fieldTypes, shape.result)
        }
        Builtins.function(e.name, u)?.let { (type, ocaml) ->
            e.resolved = NameRef.Builtin(ocaml)
            return type
        }
        throw CompileError(e.pos, "unknown name '${e.name}'")
    }

    /**
     * Finds the constructor [name] (a record type, a variant, `Some` or `None`). A variant name used by
     * several interfaces is told apart by [hint], the type the constructor should build.
     */
    private fun constructor(name: String, hint: Type?, pos: Pos, asValue: Boolean = false): ConstructorShape? {
        records[name]?.let { r ->
            val args = r.typeParams.map { u.fresh() }
            return ConstructorShape(ConstructorRef.Record(r), r.fields.map { it.name }, fieldTypes(r.typeParams, r.fields, args), Type.Con(r.name, args))
        }
        if (name == "Some" || name == "None") {
            val a = u.fresh()
            val ref = ConstructorRef.Variant(null, null, name)
            return if (name == "Some") ConstructorShape(ref, listOf("value"), listOf(a), Type.option(a))
            else ConstructorShape(ref, emptyList(), emptyList(), Type.option(a))
        }
        val candidates = variantsByName[name] ?: return null
        val chosen = if (candidates.size == 1) {
            candidates.single()
        } else {
            val hinted = when (val h = hint?.prune()) {
                is Type.Con -> h.name
                is Type.Fn -> if (asValue) (h.result.prune() as? Type.Con)?.name else null
                else -> null
            }
            candidates.firstOrNull { it.first.name == hinted }
                ?: throw CompileError(
                    pos,
                    "'$name' is a variant of ${candidates.joinToString(" and ") { it.first.name }}; " +
                        "annotate the expected type to pick one",
                )
        }
        val (sealed, variant) = chosen
        val args = sealed.typeParams.map { u.fresh() }
        return ConstructorShape(
            ConstructorRef.Variant(sealed, variant, name),
            variant.fields.map { it.name },
            fieldTypes(sealed.typeParams, variant.fields, args),
            Type.Con(sealed.name, args),
        )
    }

    /** Maps declared parameter names to argument indices: `order[i]` is the argument for parameter i. */
    private fun argOrder(args: List<Arg>, params: List<String>, pos: Pos, what: String): List<Int> {
        if (args.none { it.name != null }) {
            if (args.size != params.size) throw CompileError(pos, "$what takes ${params.size} argument${if (params.size == 1) "" else "s"}, not ${args.size}")
            return params.indices.toList()
        }
        val byName = mutableMapOf<String, Int>()
        args.forEachIndexed { i, arg ->
            if (arg.name !in params) throw CompileError(arg.value.pos, "$what has no parameter '${arg.name}'")
            if (byName.put(arg.name!!, i) != null) throw CompileError(arg.value.pos, "'${arg.name}' is passed twice")
        }
        return params.map { byName[it] ?: throw CompileError(pos, "$what is missing argument '$it'") }
    }

    private fun checkArgs(args: List<Arg>, order: List<Int>, params: List<Type>, scope: Scope) {
        // Check in parameter order, so earlier arguments can inform the types of later lambdas.
        order.forEachIndexed { i, argIndex -> check(args[argIndex].value, params[i], scope) }
    }

    private fun inferCall(e: Expr.Call, expected: Type?, scope: Scope): Type {
        val callee = e.callee
        if (callee is Expr.Name && scope.lookup(callee.name) == null) {
            val global = globals[callee.name]
            if (global?.params != null) {
                val fn = globalType(global) as Type.Fn
                callee.resolved = NameRef.Global(global)
                callee.type = fn
                val order = argOrder(e.args, global.params.map { it.name }, e.pos, "'${global.name}'")
                checkArgs(e.args, order, fn.params, scope)
                e.resolved = CallRef.Function(order)
                return fn.result
            }
            if (global == null) {
                constructor(callee.name, expected, callee.pos)?.let { shape ->
                    if (shape.fieldTypes.isEmpty()) throw CompileError(e.pos, "'${callee.name}' takes no arguments; write it without ()")
                    val order = argOrder(e.args, shape.fieldNames, e.pos, "'${callee.name}'")
                    expected?.let { u.unify(shape.result, it) }
                    checkArgs(e.args, order, shape.fieldTypes, scope)
                    e.resolved = CallRef.Construct(shape.ref, order)
                    return shape.result
                }
            }
        }
        if (callee is Expr.Member) inferMethodCall(e, callee, scope)?.let { return it }

        if (e.args.any { it.name != null }) throw CompileError(e.pos, "named arguments need a call to a declared function or type")
        val fn = when (val t = infer(callee, null, scope).prune()) {
            is Type.Fn -> t
            is Type.Var -> Type.Fn(e.args.map { u.fresh() }, u.fresh()).also { u.unify(t, it) }
            else -> throw CompileError(callee.pos, "a $t is not a function")
        }
        if (fn.params.size != e.args.size) throw CompileError(e.pos, "this function takes ${fn.params.size} arguments, not ${e.args.size}")
        checkArgs(e.args, e.args.indices.toList(), fn.params, scope)
        e.resolved = CallRef.Function(e.args.indices.toList())
        return fn.result
    }

    /** `target.name(args)`: a function-valued field, a built-in method, or a top-level function called on `target`. */
    private fun inferMethodCall(e: Expr.Call, member: Expr.Member, scope: Scope): Type? {
        val receiver = infer(member.target, null, scope).prune()
        field(member, receiver, scope)?.let { (ref, type) ->
            member.resolved = ref
            member.type = type
            return null // Called like any other function value.
        }
        if (member.name == "copy" && receiver is Type.Con && receiver.name in records) {
            val record = records.getValue(receiver.name)
            if (e.args.any { it.name == null }) throw CompileError(e.pos, "copy takes named arguments: copy(field = value)")
            val types = fieldTypes(record.typeParams, record.fields, receiver.args)
            val seen = mutableSetOf<String>()
            for (arg in e.args) {
                val index = record.fields.indexOfFirst { it.name == arg.name }
                if (index < 0) throw CompileError(arg.value.pos, "${record.name} has no field '${arg.name}'")
                if (!seen.add(arg.name!!)) throw CompileError(arg.value.pos, "'${arg.name}' is passed twice")
                check(arg.value, types[index], scope)
            }
            e.resolved = CallRef.Copy(record)
            return receiver
        }
        if (e.args.any { it.name != null }) throw CompileError(e.pos, "named arguments aren't supported in method calls")
        Builtins.member(receiver, member.name, u)?.let { builtin ->
            if (builtin.isProperty) throw CompileError(member.pos, "'${member.name}' is a property; write it without ()")
            if (builtin.params.size != e.args.size) {
                throw CompileError(e.pos, "'${member.name}' takes ${builtin.params.size} arguments, not ${e.args.size}")
            }
            checkArgs(e.args, e.args.indices.toList(), builtin.params, scope)
            member.resolved = MemberRef.Builtin(builtin.translation)
            e.resolved = CallRef.Builtin(builtin.translation)
            return builtin.result
        }
        val global = globals[member.name]?.takeIf { scope.lookup(member.name) == null }
        if (global?.params != null && global.params.size == e.args.size + 1) {
            val fn = globalType(global) as Type.Fn
            expect(receiver, fn.params[0], member.target.pos)
            checkArgs(e.args, e.args.indices.toList(), fn.params.drop(1), scope)
            e.resolved = CallRef.Extension(global)
            return fn.result
        }
        if (receiver is Type.Var) throw CompileError(member.pos, "can't tell what '${member.name}' is: the receiver's type isn't known here; annotate it")
        throw CompileError(member.pos, "$receiver has no method '${member.name}'")
    }

    /** A record field, or a field of the variant a variable was narrowed to by `is`. */
    private fun field(member: Expr.Member, receiver: Type, scope: Scope): Pair<MemberRef, Type>? {
        val target = member.target
        if (target is Expr.Name) {
            scope.lookup(target.name)?.cast?.let { cast ->
                cast.fieldTypes[member.name]?.let { return MemberRef.CastField(cast.recordVar, snake(member.name)) to it }
            }
        }
        if (receiver !is Type.Con) return null
        val record = records[receiver.name] ?: return null
        val index = record.fields.indexOfFirst { it.name == member.name }
        if (index < 0) return null
        val type = fieldTypes(record.typeParams, record.fields, receiver.args)[index]
        return MemberRef.Field(snake(member.name), receiver.name) to type
    }

    private fun inferMember(e: Expr.Member, scope: Scope): Type {
        val receiver = infer(e.target, null, scope).prune()
        field(e, receiver, scope)?.let { (ref, type) ->
            e.resolved = ref
            return type
        }
        Builtins.member(receiver, e.name, u)?.let {
            if (!it.isProperty) throw CompileError(e.pos, "'${e.name}' is a method; call it with ()")
            e.resolved = MemberRef.Builtin(it.translation)
            return it.result
        }
        if (receiver is Type.Var) throw CompileError(e.pos, "can't tell what '${e.name}' is: the receiver's type isn't known here; annotate it")
        throw CompileError(e.pos, "$receiver has no field '${e.name}'")
    }

    private fun isNumeric(t: Type) = t == Type.INT || t == Type.FLOAT

    private fun inferBinary(e: Expr.Binary, scope: Scope): Type {
        when (e.op) {
            "&&", "||" -> {
                check(e.left, Type.BOOLEAN, scope)
                check(e.right, Type.BOOLEAN, scope)
                e.resolved = BinaryRef(e.op, false, false)
                return Type.BOOLEAN
            }
            "==", "!=" -> {
                val l = infer(e.left, null, scope)
                check(e.right, l, scope)
                e.resolved = BinaryRef(if (e.op == "==") "=" else "<>", false, false)
                return Type.BOOLEAN
            }
        }
        var l = infer(e.left, null, scope).prune()
        var r = infer(e.right, l.takeIf { it !is Type.Var }, scope).prune()
        val arithmetic = e.op in setOf("+", "-", "*", "/", "%")

        val isList = { t: Type -> t is Type.Con && t.name == "List" }
        if (e.op == "+" && (isList(l) || isList(r))) {
            expect(r, l, e.right.pos)
            e.resolved = BinaryRef("@", false, false)
            return l
        }
        if (e.op == "+" && (l == Type.STRING || r == Type.STRING)) {
            expect(l, Type.STRING, e.left.pos)
            expect(r, Type.STRING, e.right.pos)
            e.resolved = BinaryRef("^", false, false)
            return Type.STRING
        }
        // Settle unknown operands from the other side, defaulting to Int.
        if (l is Type.Var && r is Type.Var) {
            u.unify(l, r)
            if (arithmetic) u.unify(l, Type.INT)
        } else if (l is Type.Var) {
            u.unify(l, r)
        } else if (r is Type.Var) {
            u.unify(r, l)
        }
        l = l.prune()
        r = r.prune()

        if (isNumeric(l) && isNumeric(r)) {
            val float = l == Type.FLOAT || r == Type.FLOAT
            val op = when {
                !arithmetic -> e.op
                !float -> if (e.op == "%") "mod" else e.op
                else -> if (e.op == "%") "Float.rem" else e.op + "."
            }
            e.resolved = BinaryRef(op, float && l == Type.INT, float && r == Type.INT)
            return if (arithmetic) (if (float) Type.FLOAT else Type.INT) else Type.BOOLEAN
        }
        if (arithmetic) throw CompileError(e.pos, "'${e.op}' can't be applied to $l and $r")
        expect(r, l, e.right.pos)
        e.resolved = BinaryRef(e.op, false, false)
        return Type.BOOLEAN
    }

    private fun inferLambda(e: Expr.Lambda, expected: Type?, scope: Scope): Type {
        val expectedFn = expected?.prune() as? Type.Fn
        if (e.ignoresArguments) {
            val params = expectedFn?.params ?: emptyList()
            e.paramNames = params.map { "_" }
            val result = expectedFn?.result ?: u.fresh()
            check(e.body, result, scope)
            return Type.Fn(params, result)
        }
        val hint = expectedFn?.takeIf { it.params.size == e.params.size }
        checkDistinct(e.params.map { it.name to it.pos })
        val inner = Scope(scope)
        val params = e.params.mapIndexed { i, p ->
            val declared = p.type?.let { resolve(it) }
            val type = declared ?: hint?.params?.get(i) ?: u.fresh()
            if (declared != null && hint != null) expect(hint.params[i], declared, p.pos)
            inner.vars[p.name] = Local(type, snake(p.name))
            type
        }
        e.paramNames = e.params.map { snake(it.name) }
        val result = hint?.result ?: u.fresh()
        check(e.body, result, inner)
        return Type.Fn(params, result)
    }

    private fun inferPipe(e: Expr.Pipe, scope: Scope): Type {
        val value = infer(e.value, null, scope)
        val f = e.function
        // `x |> f(a)` passes x as f's last argument when f takes one more than it's given.
        if (f is Expr.Call && f.args.none { it.name != null } && f.callee !is Expr.Member) {
            val callee = f.callee
            val isConstructor = callee is Expr.Name && scope.lookup(callee.name) == null &&
                globals[callee.name] == null && constructor(callee.name, null, callee.pos) != null
            if (!isConstructor) {
                val fn = infer(callee, null, scope).prune()
                if (fn is Type.Fn && fn.params.size == f.args.size + 1) {
                    checkArgs(f.args, f.args.indices.toList(), fn.params.dropLast(1), scope)
                    expect(value, fn.params.last(), e.value.pos)
                    f.resolved = CallRef.Function(f.args.indices.toList())
                    f.type = Type.Fn(listOf(fn.params.last()), fn.result)
                    e.partial = true
                    return fn.result
                }
            }
        }
        val result = u.fresh()
        check(f, Type.Fn(listOf(value), result), scope)
        return result
    }

    // ---- when ----

    private fun inferWhen(e: Expr.When, expected: Type?, scope: Scope): Type {
        val result = expected ?: u.fresh()
        val subject = e.subject
        if (subject == null) {
            if (e.branches.lastOrNull()?.condition != null || e.branches.isEmpty()) {
                throw CompileError(e.pos, "a 'when' without a subject needs an 'else' branch")
            }
            for (branch in e.branches) {
                branch.condition?.let { check(it, Type.BOOLEAN, scope) }
                check(branch.body, result, scope)
            }
            return result
        }
        val subjectType = infer(subject, null, scope)
        for (branch in e.branches) {
            val inner = Scope(scope)
            if (branch.patterns.size > 1 && branch.patterns.any { patternNames(it).isNotEmpty() }) {
                throw CompileError(branch.pos, "a branch with several patterns can't bind names")
            }
            for (pattern in branch.patterns) checkPattern(pattern, subjectType, inner, subject, e)
            check(branch.body, result, inner)
        }
        return result
    }

    private fun checkPattern(p: Pattern, type: Type, scope: Scope, subject: Expr?, owner: Expr.When?) {
        when (p) {
            is Pattern.Wildcard -> {}
            is Pattern.Bind -> {
                if (scope.vars.containsKey(p.name)) throw CompileError(p.pos, "'${p.name}' is bound twice in this pattern")
                p.ocamlName = snake(p.name)
                scope.vars[p.name] = Local(type, p.ocamlName)
            }
            is Pattern.Literal -> check(p.expr, type, scope)
            is Pattern.Tuple -> {
                val t = type.prune()
                val record = (t as? Type.Con)?.let { records[it.name] }
                if (record != null) {
                    // A record's fields, in declaration order, like a Kotlin data class.
                    if (p.elements.size != record.fields.size) {
                        throw CompileError(p.pos, "${record.name} has ${record.fields.size} fields, not ${p.elements.size}")
                    }
                    p.record = record
                    p.elements.zip(fieldTypes(record.typeParams, record.fields, t.args)).forEach { (el, ft) -> checkPattern(el, ft, scope, null, owner) }
                } else {
                    val elements = p.elements.map { u.fresh() }
                    expect(Type.tuple(elements), type, p.pos)
                    p.elements.zip(elements).forEach { (el, et) -> checkPattern(el, et, scope, null, owner) }
                }
            }
            is Pattern.ListPattern -> {
                val element = u.fresh()
                expect(Type.list(element), type, p.pos)
                p.elements.forEach { checkPattern(it, element, scope, null, owner) }
                p.rest?.let { checkPattern(it, Type.list(element), scope, null, owner) }
            }
            is Pattern.Constructor -> {
                val shape = constructor(p.name, type, p.pos) ?: throw CompileError(p.pos, "unknown type or variant '${p.name}'")
                expect(shape.result, type, p.pos)
                p.resolved = shape.ref
                if (p.name in variantsByName && variantsByName.getValue(p.name).size > 1) owner?.annotateSubject = true
                val args = p.args
                if (args != null) {
                    if (args.size != shape.fieldTypes.size) {
                        throw CompileError(p.pos, "'${p.name}' has ${shape.fieldTypes.size} fields, not ${args.size}")
                    }
                    args.zip(shape.fieldTypes).forEach { (arg, t) -> checkPattern(arg, t, scope, null, owner) }
                } else if (!p.isCheck && shape.fieldTypes.isNotEmpty()) {
                    throw CompileError(p.pos, "'${p.name}' has fields: match them with ${p.name}(...) or use 'is ${p.name}'")
                }
                // `is Variant` on a variable narrows it, so the body can read the variant's fields.
                val ref = shape.ref
                if (p.isCheck && subject is Expr.Name && ref is ConstructorRef.Variant && ref.variant != null && shape.fieldTypes.isNotEmpty()) {
                    val local = scope.lookup(subject.name) ?: return
                    val recordVar = "${local.ocamlName}__${snake(p.name)}${castCounter++.takeIf { it > 0 } ?: ""}"
                    p.castVar = recordVar
                    val cast = Cast(ref.variant, shape.fieldNames.zip(shape.fieldTypes).toMap(), recordVar)
                    scope.vars[subject.name] = Local(local.type, local.ocamlName, cast)
                }
            }
        }
    }

    private fun irrefutable(p: Pattern): Boolean = when (p) {
        is Pattern.Bind, is Pattern.Wildcard -> true
        is Pattern.Tuple -> p.elements.all { irrefutable(it) }
        else -> false
    }

    // ---- Blocks ----

    private fun inferBlock(e: Expr.Block, expected: Type?, scope: Scope): Type {
        val inner = Scope(scope)
        for (statement in e.statements) {
            when (statement) {
                is Statement.Effect -> infer(statement.expr, null, inner)
                is Statement.Destructure -> {
                    val type = infer(statement.value, null, inner)
                    val names = patternNames(statement.pattern)
                    checkDistinct(names.map { it to statement.pos })
                    names.firstOrNull { inner.vars.containsKey(it) }?.let {
                        throw CompileError(statement.pos, "'$it' is already defined in this block")
                    }
                    // Bind into a scratch scope first, so the value can't see its own names.
                    if (!irrefutable(statement.pattern)) {
                        throw CompileError(statement.pos, "a destructuring binding takes only names and '_'; use 'when' to match other patterns")
                    }
                    val bound = Scope(inner)
                    checkPattern(statement.pattern, type, bound, null, null)
                    inner.vars.putAll(bound.vars)
                }
                is Statement.Binding -> {
                    if (inner.vars.containsKey(statement.name)) {
                        throw CompileError(statement.pos, "'${statement.name}' is already defined in this block")
                    }
                    statement.ocamlName = snake(statement.name)
                    val params = statement.params
                    if (params == null) {
                        val declared = statement.returnType?.let { resolve(it) }
                        val type = infer(statement.value, declared, inner)
                        declared?.let { expect(type, it, statement.value.pos) }
                        inner.vars[statement.name] = Local(type, statement.ocamlName)
                    } else {
                        checkDistinct(params.map { it.name to it.pos })
                        val fn = Type.Fn(params.map { p -> p.type?.let { resolve(it) } ?: u.fresh() }, statement.returnType?.let { resolve(it) } ?: u.fresh())
                        inner.vars[statement.name] = Local(fn, statement.ocamlName)
                        val body = Scope(inner)
                        params.zip(fn.params).forEach { (p, t) -> body.vars[p.name] = Local(t, snake(p.name)) }
                        statement.paramNames = params.map { snake(it.name) }
                        val refs = mutableSetOf<String>()
                        collectRefs(statement.value, params.map { it.name }.toSet(), refs)
                        statement.recursive = statement.name in refs
                        check(statement.value, fn.result, body)
                    }
                }
            }
        }
        return infer(e.result, expected, inner)
    }
}
