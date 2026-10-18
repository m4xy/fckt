package uk.m4xy.fckt.types

import uk.m4xy.fckt.ocaml.snake
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.BinaryRef
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.FieldDef
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
    /** Every interface's cases, including those it takes from other interfaces. */
    val cases: Map<String, List<CaseDecl>>,
    /** Interfaces that share cases with another, which become OCaml polymorphic variants. */
    val polymorphic: Set<String>,
)

/** A case of an interface: [variant], declared by [origin], with [fields] typed in the interface's own terms. */
class CaseDecl(val origin: Decl.Sealed, val variant: VariantDef, val fields: List<FieldDef>) {
    val name get() = variant.name
    fun sameCase(other: CaseDecl) = origin === other.origin && variant === other.variant
}

class FunctionGroup(val decls: List<Decl.Function>, val recursive: Boolean)

/** A constructor's shape once instantiated: field names and types, and the type it builds. */
class ConstructorShape(val ref: ConstructorRef, val fieldNames: List<String>, val fieldTypes: List<Type>, val result: Type)

private class Local(val type: Type, val ocamlName: String, val cast: Cast? = null)

/**
 * `is Variant` narrowed a variable: [recordVar] is bound to the variant's inline record, or, for a
 * tag, [tagFields] maps each field to the variable bound to it.
 */
private class Cast(val variant: VariantDef, val fieldTypes: Map<String, Type>, val recordVar: String, val tagFields: Map<String, String>? = null)

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
    private val caseDecls = mutableMapOf<String, List<CaseDecl>>()
    private val polymorphic = mutableSetOf<String>()

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
            ambiguousConstructors = variantsByName.filterValues { v -> v.size > 1 && v.any { it.first.name !in polymorphic } }.keys,
            main = main,
            cases = caseDecls,
            polymorphic = polymorphic,
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
                    if (decl.variants.isEmpty() && decl.caseRefs.isEmpty() && decl.includes.isEmpty()) {
                        throw CompileError(decl.pos, "interface '${decl.name}' has no variants")
                    }
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
                s.includes.forEach { resolve(it) }
            }
        }
        for (s in sealeds.values) computeCases(s, emptySet())
        // An interface that takes in another's cases, and the interfaces it takes them from, share
        // values, so they become polymorphic variants.
        for ((name, cases) in caseDecls) {
            for (case in cases) {
                if (case.origin.name != name) {
                    polymorphic += name
                    polymorphic += case.origin.name
                }
            }
        }
    }

    /** [s]'s own variants, then the cases it names one by one, then the ones it includes. */
    private fun computeCases(s: Decl.Sealed, visiting: Set<String>): List<CaseDecl> {
        caseDecls[s.name]?.let { return it }
        if (s.name in visiting) throw CompileError(s.pos, "interface '${s.name}' includes itself")
        val result = mutableListOf<CaseDecl>()
        fun add(case: CaseDecl, pos: Pos) {
            val same = result.firstOrNull { it.sameCase(case) }
            if (same == null) {
                result += case
            } else if (same.fields.map { text(it.type) } != case.fields.map { text(it.type) }) {
                throw CompileError(pos, "'${s.name}' takes ${case.origin.name}.${case.name} twice, with different types")
            }
        }
        s.variants.forEach { add(CaseDecl(s, it, it.fields), it.pos) }
        for (ref in s.caseRefs) {
            val owner = sealeds[ref.owner] ?: throw CompileError(ref.pos, "unknown interface '${ref.owner}'")
            if (owner.typeParams.isNotEmpty()) {
                throw CompileError(ref.pos, "can't take single cases from the generic interface ${owner.name}; include it whole with *${owner.name}<...>")
            }
            val matches = computeCases(owner, visiting + s.name).filter { it.name == ref.case }
            val case = matches.singleOrNull()
                ?: throw CompileError(ref.pos, if (matches.isEmpty()) "${owner.name} has no case '${ref.case}'" else "${owner.name} has several cases named '${ref.case}'")
            add(case, ref.pos)
        }
        for (include in s.includes) {
            val other = sealeds[include.name] ?: throw CompileError(include.pos, "'*' takes an interface, and '${include.name}' isn't one")
            val substitution = other.typeParams.zip(include.args).toMap()
            for (case in computeCases(other, visiting + s.name)) {
                add(CaseDecl(case.origin, case.variant, case.fields.map { it.copy(type = substitute(it.type, substitution)) }), include.pos)
            }
        }
        caseDecls[s.name] = result
        return result
    }

    private fun substitute(ref: TypeRef, substitution: Map<String, TypeRef>): TypeRef = when (ref) {
        is TypeRef.Named ->
            if (ref.args.isEmpty() && ref.name in substitution) substitution.getValue(ref.name)
            else ref.copy(args = ref.args.map { substitute(it, substitution) })
        is TypeRef.Function -> ref.copy(params = ref.params.map { substitute(it, substitution) }, result = substitute(ref.result, substitution))
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { substitute(it, substitution) })
    }

    /** Written type syntax as text, ignoring positions. */
    private fun text(ref: TypeRef): String = when (ref) {
        is TypeRef.Named -> if (ref.args.isEmpty()) ref.name else "${ref.name}<${ref.args.joinToString(", ") { text(it) }}>"
        is TypeRef.Function -> "(${ref.params.joinToString(", ") { text(it) }}) -> ${text(ref.result)}"
        is TypeRef.Tuple -> "(${ref.elements.joinToString(", ") { text(it) }})"
    }

    /** The cases of interface [name] applied to [args], each with its field types. */
    private fun cases(name: String, args: List<Type>): List<Pair<CaseDecl, List<Type>>> {
        val s = sealeds.getValue(name)
        return caseDecls.getValue(name).map { it to fieldTypes(s.typeParams, it.fields, args) }
    }

    private fun sealedName(type: Type): String? = (type.prune() as? Type.Con)?.name?.takeIf { it in sealeds }

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
        assign(e, infer(e, expected, scope), expected)
    }

    /**
     * Uses [e], of type [actual], where [expected] is wanted. Besides being equal, the two may
     * differ by an interface that's a subset of another (a Vowel used as an Alphabet), which
     * marks [e] to be widened.
     */
    private fun assign(e: Expr, actual: Type, expected: Type) {
        if (retypeCase(e, expected)) return
        val before = actual.zonk()
        val wanted = expected.zonk()
        if (!subtype(actual, expected)) mismatch(e.pos, wanted, before)
        if (actual.zonk() != expected.zonk()) e.coerceTo = expected
    }

    /** The case [e] spells out, as in `Alphabet.A` or `Cons(1, Nil)`, if it is one. */
    private fun caseOf(e: Expr): ConstructorRef.Variant? {
        val ref = when (e) {
            is Expr.Name -> (e.resolved as? NameRef.Constructor)?.ref
            is Expr.Member -> (e.resolved as? MemberRef.Case)?.ref
            is Expr.Call -> (e.resolved as? CallRef.Construct)?.ref
            else -> null
        }
        return (ref as? ConstructorRef.Variant)?.takeIf { it.variant != null }
    }

    /** A case written out takes on any interface that has it: `Alphabet.E` is a Vowel where a Vowel is wanted. */
    private fun retypeCase(e: Expr, expected: Type): Boolean {
        val case = caseOf(e) ?: return false
        val target = expected.prune() as? Type.Con ?: return false
        if (target.name !in sealeds || sealedName(e.type ?: return false) == target.name) return false
        if (cases(target.name, target.args).none { it.first.origin === case.sealed && it.first.variant === case.variant }) return false
        e.type = expected
        return true
    }

    /** Whether a [sub] value can be used as a [sup], binding type variables as needed. */
    private fun subtype(sub: Type, sup: Type): Boolean {
        val x = sub.prune()
        val y = sup.prune()
        return when {
            x is Type.Var || y is Type.Var -> u.unify(x, y)
            x is Type.Con && y is Type.Con -> when {
                x.name == y.name && x.name in COVARIANT ->
                    x.args.size == y.args.size && x.args.zip(y.args).all { (a, b) -> subtype(a, b) }
                x.name == y.name -> u.unify(x, y)
                x.name in sealeds && y.name in sealeds -> caseSubset(x, y)
                else -> false
            }
            x is Type.Fn && y is Type.Fn ->
                x.params.size == y.params.size &&
                    y.params.zip(x.params).all { (a, b) -> subtype(a, b) } && subtype(x.result, y.result)
            else -> false
        }
    }

    /** Every case of interface [x] is a case of [y], with the same field types. */
    private fun caseSubset(x: Type.Con, y: Type.Con): Boolean {
        val ys = cases(y.name, y.args)
        return cases(x.name, x.args).all { (case, types) ->
            val match = ys.firstOrNull { it.first.sameCase(case) } ?: return false
            types.zip(match.second).all { (a, b) -> u.unify(a, b) }
        }
    }

    /**
     * The type of several branches' results together. Where they're different interfaces, it's the
     * smallest declared interface that has all their cases: `if (c) Alphabet.A else Digit.Zero` is a
     * Symbol if `interface Symbol { Alphabet.A, Digit.Zero }` is declared.
     */
    private fun join(exprs: List<Expr>, pos: Pos): Type {
        val types = exprs.map { it.type!! }
        val sealedTypes = types.mapNotNull { t -> (t.prune() as? Type.Con)?.takeIf { it.name in sealeds } }
        val result: Type = if (sealedTypes.map { it.name }.distinct().size <= 1) {
            types.first()
        } else {
            // What each branch needs: just its case if it spells one out, else all of its type's cases.
            val needed = exprs.flatMap { e ->
                val case = caseOf(e)
                val type = sealedName(e.type!!)
                when {
                    case != null -> caseDecls.getValue(case.sealed!!.name).filter { it.origin === case.sealed && it.variant === case.variant }.take(1)
                    type != null -> caseDecls.getValue(type)
                    else -> emptyList()
                }
            }
            fun covers(name: String) = needed.all { n -> caseDecls.getValue(name).any { it.sameCase(n) } }
            sealedTypes.filter { covers(it.name) }.minByOrNull { caseDecls.getValue(it.name).size }
                ?: sealeds.values.filter { it.typeParams.isEmpty() && covers(it.name) }.minByOrNull { caseDecls.getValue(it.name).size }
                    ?.let { Type.Con(it.name) }
                ?: throw CompileError(
                    pos,
                    "no interface has all of ${needed.distinctBy { it.origin.name + "." + it.name }.joinToString { "${it.origin.name}.${it.name}" }}; " +
                        "declare one that does, or annotate the type",
                )
        }
        exprs.zip(types).forEach { (e, t) -> assign(e, t, result) }
        return result
    }

    private companion object {
        /** Built-in types whose values can be widened inside them: a List<Vowel> is a List<Alphabet>. */
        val COVARIANT = setOf("List", "Option", "Lazy", Type.TUPLE)
    }

    /** [type], unless it's still an unknown variable, which branches shouldn't be checked against: they're joined. */
    private fun known(type: Type?): Type? = type?.takeIf { it.prune() !is Type.Var }

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
            Type.tuple(e.elements.mapIndexed { i, x -> infer(x, hint?.args?.get(i), scope).also { t -> hint?.let { assign(x, t, it.args[i]) } } })
        }
        is Expr.Spread -> throw CompileError(e.pos, "'*' can only spread a list inside a list literal")
        is Expr.StringLit -> {
            e.parts.forEach { if (it is StringPart.Interpolated) infer(it.expr, null, scope) }
            Type.STRING
        }
        is Expr.Name -> inferName(e, expected, scope)
        is Expr.ListLit -> {
            val given = (expected?.prune() as? Type.Con)?.takeIf { it.name == "List" }?.args?.get(0)?.let { known(it) }
            val elements = e.items.filter { it !is Expr.Spread }
            val element = when {
                given != null -> given.also { t -> elements.forEach { check(it, t, scope) } }
                elements.isEmpty() -> u.fresh()
                else -> {
                    elements.forEach { infer(it, null, scope) }
                    join(elements, e.pos)
                }
            }
            for (item in e.items.filterIsInstance<Expr.Spread>()) {
                check(item.list, Type.list(element), scope)
                item.type = Type.list(element)
            }
            Type.list(element)
        }
        is Expr.Call -> inferCall(e, expected, scope)
        is Expr.Member -> inferMember(e, expected, scope)
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
            val expected = known(expected)
            if (expected != null) {
                check(e.then, expected, scope)
                check(e.otherwise, expected, scope)
                expected
            } else {
                infer(e.then, null, scope)
                infer(e.otherwise, null, scope)
                join(listOf(e.then, e.otherwise), e.pos)
            }
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
     * Finds the constructor [name] (a record type, a variant, `Some` or `None`), or with a
     * [qualifier], the case [name] of that interface (`Alphabet.A`). Where [hint], the type the
     * constructor should build, is an interface with that case, the result has that type; so a
     * variant name used by several interfaces is told apart by what's expected.
     */
    private fun constructor(name: String, hint: Type?, pos: Pos, asValue: Boolean = false, qualifier: String? = null): ConstructorShape? {
        if (qualifier == null) {
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
        }
        val hinted = when (val h = hint?.prune()) {
            is Type.Con -> h.name
            is Type.Fn -> if (asValue) (h.result.prune() as? Type.Con)?.name else null
            else -> null
        }?.takeIf { it in sealeds }
        val case: CaseDecl = if (qualifier != null) {
            val owner = sealeds[qualifier] ?: return null
            val matches = caseDecls.getValue(owner.name).filter { it.name == name }
            matches.singleOrNull()
                ?: throw CompileError(pos, if (matches.isEmpty()) "${owner.name} has no case '$name'" else "${owner.name} has several cases named '$name'")
        } else {
            val inHint = hinted?.let { h -> caseDecls.getValue(h).filter { it.name == name } }.orEmpty()
            inHint.singleOrNull() ?: run {
                val candidates = variantsByName[name] ?: return null
                val (origin, variant) = candidates.singleOrNull()
                    ?: throw CompileError(
                        pos,
                        "'$name' is a case of ${candidates.joinToString(" and ") { it.first.name }}; " +
                            "write ${candidates.first().first.name}.$name, or annotate the expected type",
                    )
                caseDecls.getValue(origin.name).first { it.variant === variant }
            }
        }
        // The type it builds: the expected interface if that has the case, else the one named.
        val home = hinted?.takeIf { h -> caseDecls.getValue(h).any { it.sameCase(case) } } ?: qualifier ?: case.origin.name
        val args = sealeds.getValue(home).typeParams.map { u.fresh() }
        val fieldTypes = cases(home, args).first { it.first.sameCase(case) }.second
        return ConstructorShape(
            ConstructorRef.Variant(case.origin, case.variant, name),
            case.variant.fields.map { it.name },
            fieldTypes,
            Type.Con(home, args),
        )
    }

    /** `Interface.Case` in an expression: the qualifier names an interface, not a value. */
    private fun qualifiedCase(member: Expr.Member, scope: Scope): String? {
        val target = member.target as? Expr.Name ?: return null
        return target.name.takeIf { it in sealeds && scope.lookup(it) == null && it !in globals }
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
            if (global == null) constructor(callee.name, expected, callee.pos)?.let { return construct(e, it, callee.name, expected, scope) }
        }
        if (callee is Expr.Member) {
            qualifiedCase(callee, scope)?.let { qualifier ->
                val shape = constructor(callee.name, expected, callee.pos, qualifier = qualifier)!!
                return construct(e, shape, "$qualifier.${callee.name}", expected, scope)
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
        if ((callee as? Expr.Name)?.resolved == NameRef.Builtin(ASSERT_EQUALS)) {
            // The actual value's type tells apart a variant name in the expected value: assertEquals(A, permute(B)).
            check(e.args[1].value, fn.params[1], scope)
            check(e.args[0].value, fn.params[0], scope)
        } else {
            checkArgs(e.args, e.args.indices.toList(), fn.params, scope)
        }
        e.resolved = CallRef.Function(e.args.indices.toList())
        return fn.result
    }

    private fun construct(e: Expr.Call, shape: ConstructorShape, name: String, expected: Type?, scope: Scope): Type {
        if (shape.fieldTypes.isEmpty()) throw CompileError(e.pos, "'$name' takes no arguments; write it without ()")
        val order = argOrder(e.args, shape.fieldNames, e.pos, "'$name'")
        expected?.let { u.unify(shape.result, it) }
        checkArgs(e.args, order, shape.fieldTypes, scope)
        e.resolved = CallRef.Construct(shape.ref, order)
        return shape.result
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
            assign(member.target, receiver, fn.params[0])
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
                cast.fieldTypes[member.name]?.let { type ->
                    val tagField = cast.tagFields?.get(member.name)
                    return (if (tagField != null) MemberRef.CastTagField(tagField) else MemberRef.CastField(cast.recordVar, snake(member.name))) to type
                }
            }
        }
        if (receiver !is Type.Con) return null
        val record = records[receiver.name] ?: return null
        val index = record.fields.indexOfFirst { it.name == member.name }
        if (index < 0) return null
        val type = fieldTypes(record.typeParams, record.fields, receiver.args)[index]
        return MemberRef.Field(snake(member.name), receiver.name) to type
    }

    private fun inferMember(e: Expr.Member, expected: Type?, scope: Scope): Type {
        qualifiedCase(e, scope)?.let { qualifier ->
            val shape = constructor(e.name, expected, e.pos, asValue = true, qualifier = qualifier)!!
            e.resolved = MemberRef.Case(shape.ref)
            return if (shape.fieldTypes.isEmpty()) shape.result else Type.Fn(shape.fieldTypes, shape.result)
        }
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
                val r = infer(e.right, l, scope)
                // Different interfaces compare as the smallest one that has both: Alphabet.E == vowel.
                if (sealedName(l) != null && sealedName(r) != null && sealedName(l) != sealedName(r)) join(listOf(e.left, e.right), e.pos)
                else assign(e.right, r, l)
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
                    assign(e.value, value, fn.params.last())
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

    private fun inferWhen(e: Expr.When, expectedOrVar: Type?, scope: Scope): Type {
        val expected = known(expectedOrVar)
        val subject = e.subject
        // Checks a branch's body against what's expected, or infers it to join with the others.
        fun body(branch: uk.m4xy.fckt.syntax.Branch, inner: Scope) {
            if (expected != null) check(branch.body, expected, inner) else infer(branch.body, null, inner)
        }
        if (subject == null) {
            if (e.branches.lastOrNull()?.condition != null || e.branches.isEmpty()) {
                throw CompileError(e.pos, "a 'when' without a subject needs an 'else' branch")
            }
            for (branch in e.branches) {
                branch.condition?.let { check(it, Type.BOOLEAN, scope) }
                body(branch, scope)
            }
        } else {
            val subjectType = infer(subject, null, scope)
            for (branch in e.branches) {
                val inner = Scope(scope)
                if (branch.patterns.size > 1 && branch.patterns.any { patternNames(it).isNotEmpty() }) {
                    throw CompileError(branch.pos, "a branch with several patterns can't bind names")
                }
                for (pattern in branch.patterns) checkPattern(pattern, subjectType, inner, subject, e)
                body(branch, inner)
                // `is Vowel` rebinds the subject only if the branch uses it.
                if (subject is Expr.Name) {
                    val refs = mutableSetOf<String>()
                    collectRefs(branch.body, emptySet(), refs)
                    if (subject.name !in refs) branch.patterns.filterIsInstance<Pattern.Constructor>().forEach { it.narrowVar = null }
                }
            }
        }
        return expected ?: join(e.branches.map { it.body }, e.pos)
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
            is Pattern.Constructor -> checkConstructorPattern(p, type, scope, subject, owner)
        }
    }

    private fun checkConstructorPattern(p: Pattern.Constructor, type: Type, scope: Scope, subject: Expr?, owner: Expr.When?) {
        val t = type.prune()
        val subjectSealed = (t as? Type.Con)?.takeIf { it.name in sealeds }
        // `is Vowel`, naming an interface rather than one of the subject's cases, narrows the subject.
        if (p.isCheck && p.qualifier == null && p.name in sealeds && caseDecls[subjectSealed?.name]?.none { it.name == p.name } != false) {
            narrow(p, t, scope, subject)
            return
        }
        val shape = if (subjectSealed != null) {
            // Match against the subject's own cases, so Alphabet.A matches a Vowel.
            val qualifierCases = p.qualifier?.let { q ->
                caseDecls[q] ?: throw CompileError(p.pos, "unknown interface '$q'")
            }
            val matches = cases(subjectSealed.name, subjectSealed.args).filter { (case, _) ->
                case.name == p.name && (qualifierCases == null || qualifierCases.any { it.sameCase(case) })
            }
            val (case, fieldTypes) = matches.singleOrNull()
                ?: throw CompileError(
                    p.pos,
                    if (matches.isEmpty()) "${subjectSealed.name} has no case '${p.qualifier?.let { "$it." } ?: ""}${p.name}'"
                    else "${subjectSealed.name} has several cases named '${p.name}'; qualify it, as in ${matches.first().first.origin.name}.${p.name}",
                )
            ConstructorShape(ConstructorRef.Variant(case.origin, case.variant, p.name), case.variant.fields.map { it.name }, fieldTypes, subjectSealed)
        } else {
            constructor(p.name, type, p.pos, qualifier = p.qualifier)?.also { expect(it.result, type, p.pos) }
                ?: throw CompileError(p.pos, "unknown type or variant '${p.name}'")
        }
        p.resolved = shape.ref
        val ref = shape.ref
        val polymorphicCase = ref is ConstructorRef.Variant && ref.sealed?.name in polymorphic
        if (!polymorphicCase && p.name in variantsByName && variantsByName.getValue(p.name).size > 1) owner?.annotateSubject = true
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
        if (p.isCheck && subject is Expr.Name && ref is ConstructorRef.Variant && ref.variant != null && shape.fieldTypes.isNotEmpty()) {
            val local = scope.lookup(subject.name) ?: return
            val base = "${local.ocamlName}__${snake(p.name)}${castCounter++.takeIf { it > 0 } ?: ""}"
            val fieldTypes = shape.fieldNames.zip(shape.fieldTypes).toMap()
            val cast = if (polymorphicCase) {
                // A tag's fields are a tuple, so each is bound to a variable of its own.
                val vars = shape.fieldNames.associateWith { "${base}_${snake(it)}" }
                p.castFieldVars = shape.fieldNames.map { vars.getValue(it) }
                Cast(ref.variant, fieldTypes, base, vars)
            } else {
                p.castVar = base
                Cast(ref.variant, fieldTypes, base)
            }
            scope.vars[subject.name] = Local(local.type, local.ocamlName, cast)
        }
    }

    /** `is Vowel`: the subject is one of Vowel's cases, which must all be cases of the subject's type. */
    private fun narrow(p: Pattern.Constructor, subjectType: Type, scope: Scope, subject: Expr?) {
        val target = sealeds.getValue(p.name)
        val subjectSealed = (subjectType as? Type.Con)?.takeIf { it.name in sealeds }
            ?: throw CompileError(p.pos, "'is ${p.name}' needs the subject to be an interface, not $subjectType")
        val narrowed = Type.Con(target.name, target.typeParams.map { u.fresh() })
        if (!caseSubset(narrowed, subjectSealed)) {
            val missing = caseDecls.getValue(target.name).filter { c -> caseDecls.getValue(subjectSealed.name).none { it.sameCase(c) } }
            throw CompileError(p.pos, "${target.name} isn't part of ${subjectSealed.name}: ${subjectSealed.name} has no ${missing.joinToString { "${it.origin.name}.${it.name}" }}")
        }
        p.narrowTo = target
        if (subject is Expr.Name) {
            val local = scope.lookup(subject.name) ?: return
            p.narrowVar = local.ocamlName
            scope.vars[subject.name] = Local(narrowed, local.ocamlName)
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
                        declared?.let { assign(statement.value, type, it) }
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
