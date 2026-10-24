package uk.m4xy.fckt.types

import uk.m4xy.fckt.ocaml.snake
import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.BinaryRef
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Copier
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.FieldDef
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.SpecValue
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
    /** Every sealed type's cases, including those it takes from other sealed types. */
    val cases: Map<String, List<CaseDecl>>,
    /** Sealed types that share cases with another, which become OCaml polymorphic variants. */
    val polymorphic: Set<String>,
    /** The declarations as written, nesting included, renamed to their full names. */
    val root: List<Decl>,
    /** Each function's type, generalized. */
    val functionTypes: Map<Decl.Function, Type>,
    /** A function's type as callers outside its module see it, where that differs: its spec's, or with opaque types. */
    val outsideTypes: Map<Decl.Function, Type>,
    /** The functions each function uses. */
    val uses: Map<Decl.Function, List<Decl.Function>>,
    /** The full name each written user type refers to. */
    val typeNames: Map<TypeRef.Named, String>,
    val aliases: List<Decl.Alias>,
    /** Each spec's values' types, its own types written `@T`. */
    val specTypes: Map<Decl.Spec, Map<String, Type>>,
    /** The spec of each module that has one, by the module's full name. */
    val moduleSpecs: Map<String, Decl.Spec>,
    /** Each functor's parameters, by the functor's full name: each module's name and spec. */
    val functorParams: Map<String, List<Pair<String, Decl.Spec>>>,
    /** Each module made by a functor: the functor's and arguments' full names. */
    val applications: Map<String, Pair<String, List<String>>>,
    /** The type in a functor that each type in a module made from it copies. */
    val copyOrigins: Map<String, String>,
    /** What a spec's type arguments make each of a module's types, by the type's full name. */
    val fixedTypes: Map<String, Decl.Alias>,
    /** The `annotation type`s, which [root] leaves out, as they're only checked. */
    val annotationTypes: List<Decl.Record>,
    /** The handled effects: specs marked `@Effect`, whose operations OCaml performs. */
    val effects: List<Decl.Spec>,
    /** The handlers `main` runs in: for each effect it does that needs one, its default. */
    val mainHandlers: List<Pair<Decl.Spec, Decl.Module>>,
)

/** A case of a sealed type: [variant], declared by [origin], with [fields] typed in the sealed type's own terms. */
class CaseDecl(val origin: Decl.Sealed, val variant: VariantDef, val fields: List<FieldDef>) {
    val name get() = variant.name
    fun sameCase(other: CaseDecl) = origin === other.origin && variant === other.variant
}

class FunctionGroup(val decls: List<Decl.Function>, var recursive: Boolean)

/** A constructor's shape once instantiated: field names and types, and the type it builds. */
class ConstructorShape(val ref: ConstructorRef, val fieldNames: List<String>, val fieldTypes: List<Type>, val result: Type)

/** A local or parameter; a `var` one has a [region] of its own, which assigning it changes. */
private class Local(val type: Type, val ocamlName: String, val cast: Cast? = null, val region: Type? = null) {
    val isVar get() = region != null
}

/**
 * `is Variant` narrowed a variable: [recordVar] is bound to the variant's inline record, or, for a
 * tag, [tagFields] maps each field to the variable bound to it.
 */
private class Cast(val variant: VariantDef, val fieldTypes: Map<String, Type>, val recordVar: String, val tagFields: Map<String, String>? = null)

private class Scope(val parent: Scope?) {
    val vars = mutableMapOf<String, Local>()
    fun lookup(name: String): Local? = vars[name] ?: parent?.lookup(name)
}

/**
 * Infers and checks [file]'s types. Without [coverage], a `when` that misses some values isn't an
 * error, for tools that want to know what names refer to while it's being written.
 */
class Checker(file: SourceFile, private val coverage: Boolean = true) {
    private val u = Unifier()

    /** The declarations with their full names: `make` in `module Ratios` is `Ratios.make`. */
    private val root: List<Decl> = file.decls.map { qualify(it, "") }
    private val modules = linkedMapOf<String, Decl.Module>()
    private val decls: MutableList<Decl> = flatten(root).toMutableList()

    /** The module being checked, which decides what names mean and what's visible. */
    private var currentPath: List<String> = emptyList()
    private val typeNames = java.util.IdentityHashMap<TypeRef.Named, String>()
    private val uses = mutableMapOf<Decl.Function, List<Decl.Function>>()

    private val records = linkedMapOf<String, Decl.Record>()
    private val sealeds = linkedMapOf<String, Decl.Sealed>()
    private val aliases = linkedMapOf<String, Decl.Alias>()
    private val specs = linkedMapOf<String, Decl.Spec>()

    /** Types whose values can change: records with a `var` field, and types that hold one. They have regions. */
    private val regioned = mutableSetOf<String>()

    /** While resolving a type's fields, the region of the value they're in, which the values they hold share. */
    private var currentRegion: Type? = null

    /** Handled effects' operations, by the full name they're called by: several where names clash. */
    private val operations = mutableMapOf<String, MutableList<Pair<Decl.Spec, SpecValue>>>()

    /** Each handled effect's type parameters, as its operations' schemes have them first. */
    private val specTypeVars = mutableMapOf<Decl.Spec, List<Type.Var>>()

    /** What `main` does that needs a handler, and each one's default, which lowering puts around it. */
    private var mainHandlers: List<Pair<Decl.Spec, Decl.Module>> = emptyList()

    /** `annotation type`s, which aren't types values can have, and what their fields are. */
    private val annotationTypes = linkedMapOf<String, Decl.Record>()
    private val annotationFields = mutableMapOf<Decl.Record, List<Type>>()
    private val moduleSpecs = mutableMapOf<String, Decl.Spec>()
    private val specSchemes = mutableMapOf<Decl.Spec, Map<String, Scheme>>()

    /** A spec'd module's functions as seen outside it: their spec's types, with its types abstract. */
    private val outsideSchemes = mutableMapOf<Decl.Function, Scheme>()
    private val variantsByName = mutableMapOf<String, MutableList<Pair<Decl.Sealed, VariantDef>>>()
    private val globals = linkedMapOf<String, Decl.Function>()

    /** Extensions by the name they're called with: `map` for `List$map` and `Option$map`. */
    private val extensions = mutableMapOf<String, MutableList<Decl.Function>>()
    private val caseDecls = mutableMapOf<String, List<CaseDecl>>()

    /** Each `when` with a subject in the group being checked, with its subject's type, to check it covers every value. */
    private val pendingWhens = mutableListOf<Pair<Expr.When, Type>>()

    /** What the code being checked may do, a row of effects: each call adds the effects it has. */
    private var effects: Type = u.fresh()

    /**
     * While resolving a function's parameters' types, the effects an unannotated function type there
     * has: the function's own, so that `map(xs, f)` does whatever `f` does. Elsewhere, null: an
     * unannotated function type is pure.
     */
    private var arrowEffects: Type? = null
    private val polymorphic = mutableSetOf<String>()

    private val schemes = mutableMapOf<Decl.Function, Scheme>()
    private val provisional = mutableMapOf<Decl.Function, Type>()
    private var typeParams: Map<String, Type> = emptyMap()

    /** While checking a spec, its own types and how many parameters each takes. */
    private var specTypes: Map<String, Int> = emptyMap()
    private val expanding = mutableSetOf<String>()
    private var castCounter = 0

    /** A functor's parameter's members, which stand for whatever module it's given: they have no body. */
    private val parameterMembers = mutableSetOf<Decl.Function>()
    private val functorParams = linkedMapOf<String, List<Pair<String, Decl.Spec>>>()
    private val parameterModules = mutableSetOf<String>()

    /** Set while reading a function's written type as callers outside its module see it. */
    private var outsideView = false

    /** For each module made by a functor: the functor, and the module given for each parameter. */
    private val applications = linkedMapOf<String, Pair<Decl.Module, List<Decl.Module>>>()

    /** `IntSet.M` names `IntOrder` inside `module IntSet = SortedSet(IntOrder)`. */
    private val moduleAliases = mutableMapOf<String, String>()
    private val copyOrigins = mutableMapOf<String, String>()
    private val argumentsChecked = mutableSetOf<Pair<String, String>>()

    /**
     * `module IntOrder : Ordered<Int>`: Ordered's T, in IntOrder, is Int, even outside it. Each is
     * an alias, by the type's full name, written where the module is.
     */
    private val fixedTypes = mutableMapOf<String, Decl.Alias>()

    init {
        for (d in decls) if (d is Decl.Spec) {
            if (d.name in specs) throw CompileError(d.pos, "spec '${d.simpleName}' is declared twice")
            specs[d.name] = d
        }
        for (f in modules.values.filter { it.params.isNotEmpty() }) addParameters(f)
        for (app in modules.values.filter { it.application != null }) apply(app)
        currentPath = emptyList()
    }

    /**
     * Inside functor [f], each parameter `M : Spec` is a module with just what the spec lists, its
     * types abstract: the body must work for any module that follows the spec.
     */
    private fun addParameters(f: Decl.Module) {
        if (f.spec != null) throw CompileError(f.spec.pos, "a functor can't have a spec yet")
        enclosingFunctor(f.path)?.let { throw CompileError(f.pos, "a functor can't be inside another functor (${it.simpleName}) yet") }
        currentPath = f.path
        checkDistinct(f.params.map { it.name to it.pos })
        functorParams[f.name] = f.params.map { p ->
            val spec = find(p.spec.name, specs, p.spec.pos) ?: throw CompileError(p.spec.pos, "unknown spec '${p.spec.name}'")
            val name = "${f.name}.${p.name}"
            if (name in modules) throw CompileError(p.pos, "${f.simpleName} has a module named ${p.name} already")
            val types = spec.types.map { Decl.Record(p.pos, "$name.${it.name}", it.typeParams, emptyList()) }
            fixTypes(name, f.path, spec, p.spec)
            val values = spec.values.map { Decl.Function(p.pos, "$name.${it.name}", it.typeParams, it.params, null, Expr.BoolLit(p.pos, true)) }
            modules[name] = Decl.Module(p.pos, name, types + values, spec = p.spec)
            parameterModules += name
            decls += types + values
            parameterMembers += values
            p.name to spec
        }
    }

    /** `module IntSet = SortedSet(IntOrder)`: a copy of SortedSet's declarations, with its `M` naming IntOrder. */
    private fun apply(app: Decl.Module) {
        val ref = app.application!!
        enclosingFunctor(app.path)?.let { throw CompileError(app.pos, "a functor can't be applied inside another functor (${it.simpleName}) yet") }
        currentPath = app.path
        val functor = find(ref.name, modules, ref.pos) ?: throw CompileError(ref.pos, "unknown module '${ref.name}'")
        if (functor.params.isEmpty()) throw CompileError(ref.pos, "${functor.simpleName} isn't a functor: it takes no modules")
        if (functor.params.size != ref.args.size) {
            throw CompileError(ref.pos, "${functor.simpleName} takes ${functor.params.size} module${if (functor.params.size == 1) "" else "s"}, not ${ref.args.size}")
        }
        val args = ref.args.map { arg ->
            val name = (arg as TypeRef.Named).name
            val m = find(name, modules, arg.pos) ?: throw CompileError(arg.pos, "unknown module '$name'")
            if (m.params.isNotEmpty()) throw CompileError(arg.pos, "${m.simpleName} is a functor; give it modules first")
            m
        }
        val copier = Copier({ name -> app.name + name.removePrefix(functor.name) }) { original, copy ->
            if (copy !is Decl.Function && copy !is Decl.Module && copy !is Decl.Spec) copyOrigins[copy.name] = original.name
        }
        val copies = functor.decls.map { copier.decl(it) }
        modules[app.name] = app.copy(decls = copies)
        decls += flatten(copies)
        functor.params.zip(args).forEach { (p, arg) -> moduleAliases["${app.name}.${p.name}"] = arg.name }
        applications[app.name] = functor to args
    }

    /**
     * `Spec<A, B>` for [module], written in [scope]: an alias for each of the spec's types, in order,
     * saying what it is. A type that takes parameters takes a type that takes as many more:
     * `Monad<Option>` makes `M<A>` an `Option<A>`. The parameters go at the end, or where `_` marks
     * them: `Monad<Result<_, String>>` makes `M<A>` a `Result<A, String>`.
     */
    private fun fixTypes(module: String, scope: List<String>, spec: Decl.Spec, ref: TypeRef.Named) {
        if (ref.args.size > spec.types.size) {
            throw CompileError(ref.pos, "${spec.simpleName} has ${spec.types.size} type${if (spec.types.size == 1) "" else "s"}, so it takes at most that many type arguments, not ${ref.args.size}")
        }
        for ((t, arg) in spec.types.zip(ref.args)) {
            val params = t.typeParams.map { TypeRef.Named(arg.pos, it, emptyList()) }
            val target = when {
                params.isEmpty() -> arg
                arg is TypeRef.Named -> {
                    val holes = arg.args.count { it is TypeRef.Named && it.name == "_" }
                    if (holes == 0) arg.copy(args = arg.args + params)
                    else if (holes != params.size) {
                        throw CompileError(arg.pos, "${spec.simpleName}'s ${t.name} takes ${params.size} type parameter${if (params.size == 1) "" else "s"}, so ${arg.name} needs ${params.size} '_', not $holes")
                    } else {
                        val fill = params.iterator()
                        arg.copy(args = arg.args.map { if (it is TypeRef.Named && it.name == "_") fill.next() else it })
                    }
                }
                else -> throw CompileError(arg.pos, "${spec.simpleName}'s ${t.name} takes type parameters, so it needs a named type, like Option")
            }
            val alias = Decl.Alias(arg.pos, (scope + "${module.substringAfterLast('.')}#${t.name}").joinToString("."), t.typeParams, target)
            aliases[alias.name] = alias
            fixedTypes["$module.${t.name}"] = alias
        }
    }

    /** What [module]'s [name] type applied to [args] is outside the module: what its spec's type arguments say, or abstract. */
    private fun outsideType(module: String, name: String, args: List<Type>): Type =
        fixedTypes["$module.$name"]?.let { fixed ->
            try {
                expand(fixed, args)
            } catch (e: CompileError) {
                val n = fixed.typeParams.size
                throw if (n == 0) e else CompileError(e.pos, "${name} takes $n type parameter${if (n == 1) "" else "s"}, so its type argument must take $n more: ${e.message}")
            }
        } ?: typeDecl("$module.$name")?.takeIf { module !in parameterModules }?.let { d ->
            when {
                // An opaque record or type with cases keeps its name; only what's in it is hidden.
                d is Decl.Alias && d.isOpaque -> null
                d is Decl.Alias -> expand(d, args)
                else -> Type.Con(d.name, args)
            }
        } ?: Type.Con(Type.ABSTRACT + "$module.$name", args)

    private fun enclosingFunctor(path: List<String>): Decl.Module? =
        (1..path.size).map { modules[path.take(it).joinToString(".")] }.firstOrNull { it != null && it.params.isNotEmpty() }

    /** The module made by a functor that [path] is in, if any. */
    private fun enclosingApplication(path: List<String>): String? =
        (1..path.size).map { path.take(it).joinToString(".") }.firstOrNull { it in applications }

    /** [name] with any module alias at its start replaced by the module it names. */
    private fun canonical(name: String): String {
        if (moduleAliases.isEmpty()) return name
        val parts = name.split('.')
        for (n in parts.size downTo 1) {
            moduleAliases[parts.take(n).joinToString(".")]?.let { return canonical((listOf(it) + parts.drop(n)).joinToString(".")) }
        }
        return name
    }

    /** Every module given to a functor has what its parameter's spec lists. */
    private fun checkArgumentsDefine() {
        for ((app, made) in applications) {
            val (functor, args) = made
            currentPath = modules.getValue(app).path
            for ((param, arg) in functorParams.getValue(functor.name).zip(args)) {
                val (name, spec) = param
                val argSpec = moduleSpecs[arg.name]
                fun fail(why: String): Nothing = throw CompileError(modules.getValue(app).pos, "${arg.simpleName} can't be ${functor.simpleName}'s $name: $why")
                // M : Ordered<Int> needs a module whose T is Int, as seen from here.
                for (t in spec.types) {
                    if ("${functor.name}.$name.${t.name}" !in fixedTypes) continue
                    val d = typeDecl("${arg.name}.${t.name}") ?: continue
                    val rigid = t.typeParams.map { Type.Con("#$it") }
                    val needed = outsideType("${functor.name}.$name", t.name, rigid)
                    val given = typeOf(d, rigid)
                    if (!u.unify(needed, given)) fail("${functor.simpleName} needs its ${t.name} to be $needed, but here it's $given")
                }
                if (argSpec === spec) continue
                if (argSpec != null) fail("it follows ${argSpec.simpleName}, not ${spec.simpleName}")
                for (t in spec.types) {
                    val d = typeDecl("${arg.name}.${t.name}") ?: fail("it doesn't define type ${t.name}, which ${spec.simpleName} needs")
                    whyHidden(d)?.let { fail(it) }
                    if (typeParamsOf(d).size != t.typeParams.size) fail("${spec.simpleName} says ${t.name} takes ${t.typeParams.size} type parameters, not ${typeParamsOf(d).size}")
                }
                for (v in spec.values) {
                    val f = globals["${arg.name}.${v.name}"] ?: fail("it doesn't define '${v.name}', which ${spec.simpleName} needs")
                    whyHidden(f)?.let { fail(it) }
                }
            }
        }
        currentPath = emptyList()
    }

    /**
     * The values of modules given to functors have the types their parameters' specs give them, once
     * they're checked. Modules that follow the spec were checked already.
     */
    private fun checkArgumentTypes(app: String) {
        val (functor, args) = applications.getValue(app)
        val saved = currentPath
        currentPath = modules.getValue(app).path
        for ((param, arg) in functorParams.getValue(functor.name).zip(args)) {
            val (name, spec) = param
            if (moduleSpecs[arg.name] === spec) continue
            for (v in spec.values) {
                val f = globals.getValue("${arg.name}.${v.name}")
                if (f !in schemes || !argumentsChecked.add(app to f.name)) continue
                val wanted = specSchemes.getValue(spec).getValue(v.name)
                val names = wanted.vars.zip(v.typeParams)
                val open = wanted.vars.drop(names.size).associateWith { u.fresh() as Type }
                val inside = specView(wanted.type, names.associate { (tv, n) -> tv to Type.Con("#$n") as Type } + open) { t, targs ->
                    typeOf(typeDecl("${arg.name}.$t")!!, targs)
                }
                val actual = globalType(f)
                val shown = actual.zonk()
                if (!u.unify(actual, inside)) {
                    throw CompileError(
                        modules.getValue(app).pos,
                        "${arg.simpleName} can't be ${functor.simpleName}'s $name: '${v.name}' has type $shown, " +
                            "but ${spec.simpleName} needs ${Type.render(wanted.type, names.toMap().toMutableMap())}, which here is ${inside.zonk()}",
                    )
                }
            }
        }
        currentPath = saved
    }

    fun check(): Program {
        collectTypes()
        checkSpecs()
        collectGlobals()
        checkModuleSpecs()
        checkArgumentsDefine()
        val groups = dependencyGroups()
        for (group in groups) checkGroup(group)
        for (app in applications.keys) checkArgumentTypes(app)
        checkExposure()
        checkAnnotations()

        // Copies made by functors share the functor's OCaml labels and constructors.
        val labelCounts = records.values.filter { it.name !in copyOrigins }.flatMap { r -> r.fields.map { snake(it.name) } }.groupingBy { it }.eachCount()
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
            groups = groups.filter { g -> g.decls.none { it.external != null } },
            ambiguousLabels = labelCounts.filterValues { it > 1 }.keys,
            ambiguousConstructors = variantsByName.mapValues { (_, v) -> v.filter { it.first.name !in copyOrigins } }
                .filterValues { v -> v.size > 1 && v.any { it.first.name !in polymorphic } }.keys,
            main = main,
            cases = caseDecls,
            polymorphic = polymorphic,
            // Annotations are only checked: their types aren't compiled.
            root = withoutAnnotationTypes(root),
            annotationTypes = annotationTypes.values.toList(),
            effects = specs.values.filter { isEffect(it) && it.values.isNotEmpty() },
            mainHandlers = mainHandlers,
            functionTypes = schemes.mapValues { it.value.type.zonk() },
            outsideTypes = outsideSchemes.filterKeys { it !in parameterMembers }.mapValues { it.value.type.zonk() },
            uses = uses,
            typeNames = typeNames,
            aliases = aliases.values.toList(),
            specTypes = specSchemes.mapValues { (_, values) -> values.mapValues { it.value.type.zonk() } },
            moduleSpecs = moduleSpecs,
            functorParams = functorParams,
            applications = applications.mapValues { (_, made) -> made.first.name to made.second.map { it.name } },
            copyOrigins = copyOrigins,
            fixedTypes = fixedTypes,
        )
    }

    /**
     * A public function or type can't mention a type that's private to its module: callers outside
     * could see it but not name it.
     */
    private fun checkExposure() {
        fun privateTypes(t: Type): List<Decl> = when (val x = t.prune()) {
            is Type.Var -> emptyList()
            is Type.Fn -> x.params.flatMap { privateTypes(it) } + privateTypes(x.result)
            is Type.Con -> listOfNotNull((records[x.name] ?: sealeds[x.name])?.takeIf { it.isPrivate }) + x.args.flatMap { privateTypes(it) }
        }
        // Visible beyond the private type's module, unless it's in a module nested inside it.
        fun exposes(user: Decl, hidden: Decl) =
            !user.isPrivate && hidden.path.isNotEmpty() && !(user.path.size > hidden.path.size && user.path.take(hidden.path.size) == hidden.path)
        fun complain(user: Decl, hidden: Decl): Nothing = throw CompileError(
            user.pos,
            "'${user.simpleName}' is public, but its type uses ${hidden.simpleName}, which is private to ${hidden.path.joinToString(".")}",
        )
        for (f in globals.values.filter { it !in parameterMembers }) {
            privateTypes(schemes.getValue(f).type).firstOrNull { exposes(f, it) }?.let { complain(f, it) }
        }
        for (d in records.values + sealeds.values) {
            val fields = if (d is Decl.Record) d.fields else caseDecls.getValue(d.name).flatMap { it.fields }
            val withParams = if (d is Decl.Record) d.typeParams else (d as Decl.Sealed).typeParams
            currentPath = d.path
            withTypeParams(withParams) {
                fields.flatMap { privateTypes(resolve(it.type)) }.firstOrNull { exposes(d, it) }?.let { complain(d, it) }
            }
        }
        for (d in records.values + sealeds.values + aliases.values) {
            if (d.isOpaque && d.path.isEmpty()) throw CompileError(d.pos, "an opaque type is opaque outside its module, so it needs to be in one")
        }
        for (a in aliases.values) {
            currentPath = a.path
            withTypeParams(a.typeParams) { privateTypes(resolve(a.target)).firstOrNull { exposes(a, it) }?.let { complain(a, it) } }
        }
        currentPath = emptyList()
    }

    // ---- Modules and names ----

    private fun qualify(d: Decl, prefix: String): Decl = when (d) {
        is Decl.Record -> d.copy(name = prefix + d.name)
        is Decl.Sealed -> d.copy(name = prefix + d.name)
        is Decl.Function -> d.copy(name = prefix + d.name)
        is Decl.Module -> d.copy(name = prefix + d.name, decls = d.decls.map { qualify(it, prefix + d.name + ".") })
        is Decl.Alias -> d.copy(name = prefix + d.name)
        is Decl.Spec -> d.copy(name = prefix + d.name)
    }

    private fun flatten(ds: List<Decl>): List<Decl> = ds.flatMap { d ->
        if (d is Decl.Module) {
            if (d.name in modules) throw CompileError(d.pos, "module '${d.simpleName}' is declared twice")
            modules[d.name] = d
            flatten(d.decls)
        } else {
            listOf(d)
        }
    }

    /** Whether the current module is [path] or inside it. */
    private fun inside(path: List<String>) = currentPath.take(path.size) == path

    /** Whether [d], and every module around it, can be seen from the current module. */
    private fun visible(d: Decl): Boolean = whyHidden(d) == null

    /** Why [d] can't be seen from the current module, or null if it can. */
    private fun whyHidden(d: Decl): String? {
        if (d.isPrivate && !inside(d.path)) return "'${d.simpleName}' is private to ${d.path.joinToString(".").ifEmpty { "this file" }}"
        enclosingFunctor(d.path)?.takeIf { !inside(it.name.split('.')) }?.let {
            return "'${d.simpleName}' is in the functor ${it.simpleName}, which makes modules: use it through one, made with module Name = ${it.simpleName}(...)"
        }
        for (n in 1..d.path.size) modules[d.path.take(n).joinToString(".")]?.let { m -> whyHidden(m)?.let { return it } }
        return null
    }

    /**
     * Whether [d] is a type that's abstract here: it can be named, but not built or looked into.
     * An `opaque` type is, outside its module; so is a functor parameter's type, outside the
     * module it stands for. Reading a function's type as callers outside see it, [outsideView]
     * makes it so inside too.
     */
    private fun abstractHere(d: Decl): Boolean {
        if (d !is Decl.Record && d !is Decl.Sealed && d !is Decl.Alias) return false
        val abstract = d.isOpaque || d.path.joinToString(".") in parameterModules
        return abstract && (outsideView || !inside(d.path))
    }

    private fun abstractError(d: Decl, pos: Pos): Nothing {
        val module = d.path.joinToString(".")
        throw CompileError(
            pos,
            if (module in parameterModules) "${d.simpleName} is abstract here: ${moduleSpecs.getValue(module).simpleName} doesn't say what it is"
            else "${d.simpleName} is opaque outside $module: only $module can build one or look into it",
        )
    }

    /**
     * Finds what [name] means from the current module: in module `A.B`, `x` could be `A.B.x`, `A.x`
     * or `x`, innermost first, and `C.x` could be `A.B.C.x`, `A.C.x` or `C.x`. Reports a private
     * declaration at [pos], or, without [pos], skips it.
     */
    private fun <D : Decl> find(name: String, table: Map<String, D>, pos: Pos?): D? {
        for (n in currentPath.size downTo 0) {
            val d = table[canonical((currentPath.take(n) + name).joinToString("."))] ?: continue
            val hidden = whyHidden(d)
            if (hidden != null) {
                if (pos == null) continue
                throw CompileError(pos, hidden)
            }
            return d
        }
        return null
    }

    private fun lookupGlobal(name: String, pos: Pos?) = find(name, globals, pos)
    private fun lookupModule(name: String, pos: Pos?) = find(name, modules, pos)
    private fun lookupSealed(name: String, pos: Pos?) = find(name, sealeds, pos)
    private fun lookupRecord(name: String, pos: Pos?) = find(name, records, pos)
    private fun lookupAlias(name: String, pos: Pos?) = find(name, aliases, pos)

    private fun typeDecl(name: String): Decl? = records[name] ?: sealeds[name] ?: aliases[name]

    private fun typeParamsOf(d: Decl): List<String> = when (d) {
        is Decl.Record -> d.typeParams
        is Decl.Sealed -> d.typeParams
        is Decl.Alias -> d.typeParams
        else -> emptyList()
    }

    /** The dotted name an expression spells, like `Ratios.make` or `A.B.C`, unless it starts with a local. */
    private fun dotted(e: Expr, scope: Scope?): String? = when (e) {
        is Expr.Name -> e.name.takeIf { scope?.lookup(it) == null }
        is Expr.Member -> dotted(e.target, scope)?.let { "$it.${e.name}" }
        else -> null
    }

    /** The module an expression names, if it names one: `Ratios`, or `Shapes.Inner`. */
    private fun moduleOf(e: Expr, scope: Scope?): Decl.Module? {
        val name = dotted(e, scope) ?: return null
        if (e is Expr.Name && (lookupGlobal(name, null) != null)) return null
        return lookupModule(name, e.pos)
    }

    /** `Ratios.make`: a function or value in a module, named through it. */
    private fun moduleMember(member: Expr.Member, scope: Scope): Decl.Function? {
        val module = moduleOf(member.target, scope) ?: return null
        val d = globals[module.name + "." + member.name] ?: return null
        whyHidden(d)?.let { throw CompileError(member.pos, it) }
        return d
    }

    // ---- Declarations ----

    private fun collectTypes() {
        for (decl in decls) {
            if (decl is Decl.Function) continue
            if (decl is Decl.Spec) continue
            if (decl.simpleName in BUILTIN_TYPES) throw CompileError(decl.pos, "'${decl.simpleName}' is a built-in type")
            if (typeDecl(decl.name) != null || decl.name in annotationTypes) throw CompileError(decl.pos, "type '${decl.simpleName}' is declared twice")
            when (decl) {
                is Decl.Alias -> aliases[decl.name] = decl
                is Decl.Record -> if (decl.isAnnotation) annotationTypes[decl.name] = decl else records[decl.name] = decl
                is Decl.Sealed -> {
                    if (decl.variants.isEmpty() && decl.caseRefs.isEmpty() && decl.includes.isEmpty()) {
                        throw CompileError(decl.pos, "type '${decl.simpleName}' has no cases")
                    }
                    sealeds[decl.name] = decl
                    for (variant in decl.variants) {
                        if (variant.name == "Some" || variant.name == "None") {
                            throw CompileError(variant.pos, "'${variant.name}' is reserved for Option")
                        }
                        variantsByName.getOrPut(variant.name) { mutableListOf() } += decl to variant
                    }
                }
                is Decl.Function, is Decl.Module, is Decl.Spec -> {}
            }
        }
        // Which spec each module follows. A functor's parameters have theirs already, with what
        // their type arguments say; a module says what its types are in its body.
        for (m in modules.values) {
            val ref = m.spec ?: continue
            currentPath = m.path
            val spec = find(ref.name, specs, ref.pos) ?: throw CompileError(ref.pos, "unknown spec '${ref.name}'")
            if (ref.args.isNotEmpty() && m.name !in parameterModules) {
                throw CompileError(ref.args.first().pos, "${m.simpleName} says what its types are in its body, so write just : ${spec.simpleName}; type arguments are for a functor's parameters, like F(M: ${spec.simpleName}<...>)")
            }
            if (spec.typeParams.isNotEmpty()) {
                throw CompileError(ref.pos, "${m.simpleName} can't follow ${spec.simpleName}, which takes type parameters; handle it with a value instead: ${spec.simpleName}(...) { ... }")
            }
            moduleSpecs[m.name] = spec
        }
        currentPath = emptyList()
        for ((name, variants) in variantsByName) {
            variants.firstOrNull { (s, _) -> (s.path + name).joinToString(".") in records }?.let {
                throw CompileError(it.second.pos, "'$name' is both a type and a variant")
            }
            variants.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
                throw CompileError(it[1].second.pos, "variant '$name' is declared twice in ${it[1].first.name}")
            }
        }
        findRegioned()
        // Resolve every field type now, so bad references are reported at the declaration.
        for (a in aliases.values.filter { it !in fixedTypes.values }) {
            currentPath = a.path
            expand(a, a.typeParams.map { Type.Con("#$it") })
        }
        for (r in records.values) {
            currentPath = r.path
            checkFieldNames(r.fields.map { it.name to it.pos })
            withTypeParams(r.typeParams) { r.fields.forEach { resolve(it.type) } }
        }
        for (s in sealeds.values) {
            currentPath = s.path
            withTypeParams(s.typeParams) {
                s.variants.forEach { v ->
                    checkFieldNames(v.fields.map { it.name to it.pos })
                    v.fields.forEach { resolve(it.type) }
                }
                s.includes.forEach { resolve(it) }
            }
        }
        for (s in sealeds.values) computeCases(s, emptySet())
        for (a in annotationTypes.values) {
            currentPath = a.path
            checkFieldNames(a.fields.map { it.name to it.pos })
            annotationFields[a] = a.fields.map { resolveMeta(it.type) }
        }
        currentPath = emptyList()
        // A sealed type that takes in another's cases, and the sealed types it takes them from, share
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
        if (s.name in visiting) throw CompileError(s.pos, "type '${s.simpleName}' includes itself")
        val outerPath = currentPath
        currentPath = s.path
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
            val owner = lookupSealed(ref.owner, ref.pos) ?: throw CompileError(ref.pos, "unknown type '${ref.owner}'")
            if (owner.typeParams.isNotEmpty()) {
                throw CompileError(ref.pos, "can't take single cases from the generic type ${owner.simpleName}; include it whole with *${owner.simpleName}<...>")
            }
            val matches = computeCases(owner, visiting + s.name).filter { it.name == ref.case }
            val case = matches.singleOrNull()
                ?: throw CompileError(ref.pos, if (matches.isEmpty()) "${owner.simpleName} has no case '${ref.case}'" else "${owner.simpleName} has several cases named '${ref.case}'")
            add(case, ref.pos)
        }
        for (include in s.includes) {
            val other = lookupSealed(include.name, include.pos) ?: throw CompileError(include.pos, "'*' takes a type with cases, and '${include.name}' isn't one")
            val substitution = other.typeParams.zip(include.args).toMap()
            for (case in computeCases(other, visiting + s.name)) {
                add(CaseDecl(case.origin, case.variant, case.fields.map { it.copy(type = substitute(it.type, substitution)) }), include.pos)
            }
        }
        caseDecls[s.name] = result
        currentPath = outerPath
        return result
    }

    private fun substitute(ref: TypeRef, substitution: Map<String, TypeRef>): TypeRef = when (ref) {
        is TypeRef.Named ->
            if (ref.args.isEmpty() && ref.name in substitution) substitution.getValue(ref.name)
            else ref.copy(args = ref.args.map { substitute(it, substitution) }).also { copy -> typeNames[ref]?.let { typeNames[copy] = it } }
        is TypeRef.Function -> ref.copy(params = ref.params.map { substitute(it, substitution) }, result = substitute(ref.result, substitution))
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { substitute(it, substitution) })
    }

    /** Written type syntax as text, ignoring positions. */
    private fun text(ref: TypeRef): String = when (ref) {
        is TypeRef.Named -> if (ref.args.isEmpty()) ref.name else "${ref.name}<${ref.args.joinToString(", ") { text(it) }}>"
        is TypeRef.Function -> "(${ref.params.joinToString(", ") { text(it) }}) -> ${text(ref.result)}"
        is TypeRef.Tuple -> "(${ref.elements.joinToString(", ") { text(it) }})"
    }

    /** The cases of sealed type [name] applied to [args], each with its field types. */
    /** [name]'s cases, with their fields' types for [args], holding values in [region]. */
    private fun cases(name: String, args: List<Type>, region: Type? = null): List<Pair<CaseDecl, List<Type>>> {
        val s = sealeds.getValue(name)
        return caseDecls.getValue(name).map { it to fieldTypes(s.typeParams, it.fields, args, region) }
    }

    private fun sealedName(type: Type): String? = (type.prune() as? Type.Con)?.name?.takeIf { it in sealeds }

    private fun checkFieldNames(fields: List<Pair<String, Pos>>) {
        fields.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
            throw CompileError(it[1].second, "field '${it[1].first}' is declared twice")
        }
    }

    private fun collectGlobals() {
        for (decl in decls.filterIsInstance<Decl.Function>()) {
            if (decl.name in globals) {
                if (decl.extension) throw CompileError(decl.pos, "'${decl.methodName}' is declared twice for ${text(decl.params!!.first().type!!)}")
                throw CompileError(decl.pos, "'${decl.simpleName}' is declared twice")
            }
            globals[decl.name] = decl
            if (decl.extension) extensions.getOrPut(decl.methodName) { mutableListOf() } += decl
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
        is TypeRef.Function -> Type.Fn(
            ref.params.map { resolve(it) },
            resolve(ref.result),
            if (ref.effects.isEmpty()) arrowEffects ?: Type.effects(emptySet(), null) else Type.row(effectLabels(ref.effects), null),
        )
        is TypeRef.Tuple -> Type.tuple(ref.elements.map { resolve(it) })
        is TypeRef.Named -> {
            val args = ref.args.map { resolve(it) }
            fun arity(n: Int) {
                if (args.size != n) throw CompileError(ref.pos, "'${ref.name}' takes $n type argument${if (n == 1) "" else "s"}, not ${args.size}")
            }
            typeParams[ref.name]?.also { arity(0) }
                ?: specTypes[ref.name]?.let {
                    arity(it)
                    Type.Con(Type.SPEC_TYPE + ref.name, args)
                }
                ?: BUILTIN_TYPES[ref.name]?.let {
                    arity(it)
                    Type.Con(if (ref.name == "Double") "Float" else ref.name, args)
                }
                // Once found, a written type keeps meaning the same declaration wherever its type is needed.
                ?: (typeNames[ref]?.let { typeDecl(it) } ?: lookupRecord(ref.name, ref.pos) ?: lookupSealed(ref.name, ref.pos) ?: lookupAlias(ref.name, ref.pos))?.let {
                    arity(typeParamsOf(it).size)
                    typeNames[ref] = it.name
                    typeOf(it, args)
                }
                ?: find(ref.name, annotationTypes, null)?.let {
                    throw CompileError(ref.pos, "'${ref.name}' is an annotation, written @${ref.name}(...) before a declaration; no value has its type")
                }
                ?: throw CompileError(ref.pos, if (ref.name == "_") "'_' only marks a spec type's parameters, as in Monad<Result<_, String>>" else "unknown type '${ref.name}'")
        }
    }

    /** The type declaration [d] applied to [args], as seen from the current module. */
    private fun typeOf(d: Decl, args: List<Type>): Type = withRegion(
        d,
        when {
            abstractHere(d) -> outsideType(d.path.joinToString("."), d.simpleName, args)
            d is Decl.Alias -> expand(d, args)
            else -> Type.Con(d.name, args)
        },
    )

    /** [type], of declaration [d], with a region if its values can change: the one it's in, or its own. */
    private fun withRegion(d: Decl, type: Type): Type =
        if (d.name in regioned && type is Type.Con && type.region == null) type.copy(region = currentRegion ?: u.fresh()) else type

    /** A new region for a value of [d] being made, if its values can change. */
    private fun newRegion(d: Decl): Type? = if (d.name in regioned) u.fresh() else null

    /**
     * Which types' values can change: a record with a `var` field, then, until there are no more,
     * the records and types with cases whose fields hold one. Only a record's fields can be `var`.
     */
    private fun findRegioned() {
        for (s in sealeds.values) {
            s.variants.flatMap { it.fields }.firstOrNull { it.isVar }?.let {
                throw CompileError(it.pos, "only a record's fields can be var; make ${it.name}'s case hold a record with a var field")
            }
        }
        // An OCaml type may change too: its own functions say if they do.
        records.values.filter { r -> r.external != null || r.fields.any { it.isVar } }.forEach { regioned += it.name }
        fun holds(ref: TypeRef): Boolean = when (ref) {
            // A function isn't a value that changes, whatever it takes or gives.
            is TypeRef.Function -> false
            is TypeRef.Tuple -> ref.elements.any(::holds)
            is TypeRef.Named -> ref.args.any(::holds) || (lookupRecord(ref.name, null) ?: lookupSealed(ref.name, null))?.name in regioned ||
                lookupAlias(ref.name, null)?.let { holds(it.target) } == true
        }
        do {
            val before = regioned.size
            for (d in records.values + sealeds.values) {
                if (d.name in regioned) continue
                currentPath = d.path
                val fields = if (d is Decl.Record) d.fields else (d as Decl.Sealed).variants.flatMap { it.fields }
                if (fields.any { holds(it.type) }) regioned += d.name
            }
        } while (regioned.size != before)
        currentPath = emptyList()
    }

    /** What alias [a] stands for, applied to [args]; its target means what it does where it's written. */
    private fun expand(a: Decl.Alias, args: List<Type>): Type {
        if (!expanding.add(a.name)) throw CompileError(a.pos, "type '${a.simpleName}' is defined in terms of itself")
        val saved = Triple(typeParams, specTypes, currentPath)
        typeParams = a.typeParams.zip(args).toMap()
        specTypes = emptyMap()
        currentPath = a.path
        try {
            return resolve(a.target)
        } finally {
            typeParams = saved.first
            specTypes = saved.second
            currentPath = saved.third
            expanding.remove(a.name)
        }
    }

    // ---- Annotations ----

    /**
     * An annotation's field's type: an ordinary type, or a reference only the checker knows:
     * `Type`, `Spec`, `Module` or `Module<Spec>`, `Case<T>`, `Field<R, T>` (`_` for any T), another
     * annotation, or lists and tuples of them. Those are [Type.Con]s named with [Type.META].
     */
    private fun resolveMeta(ref: TypeRef): Type {
        if (ref is TypeRef.Tuple) return Type.tuple(ref.elements.map { resolveMeta(it) })
        if (ref !is TypeRef.Named) return resolve(ref)
        fun arity(n: Int) {
            if (ref.args.size != n) throw CompileError(ref.pos, "'${ref.name}' takes $n type argument${if (n == 1) "" else "s"}, not ${ref.args.size}")
        }
        return when (ref.name) {
            "Type", "Spec" -> Type.Con(Type.META + ref.name).also { arity(0) }
            "Module" -> {
                if (ref.args.size > 1) arity(1)
                val spec = ref.args.singleOrNull()?.let { arg ->
                    (arg as? TypeRef.Named)?.takeIf { it.args.isEmpty() }?.let { find(it.name, specs, it.pos) }
                        ?: throw CompileError(arg.pos, "Module<...> takes a spec, like Module<Ordered>")
                }
                Type.Con(Type.META + "Module", listOfNotNull(spec?.let { Type.Con(it.name) }))
            }
            "Case" -> {
                arity(1)
                val type = resolve(ref.args[0])
                if (sealedName(type) == null) throw CompileError(ref.args[0].pos, "Case<...> takes a type with cases, not $type")
                Type.Con(Type.META + "Case", listOf(type))
            }
            "Field" -> {
                arity(2)
                val record = resolve(ref.args[0])
                if ((record.prune() as? Type.Con)?.name !in records) throw CompileError(ref.args[0].pos, "Field<R, T> takes a record type as R, not $record")
                val any = (ref.args[1] as? TypeRef.Named)?.takeIf { it.name == "_" && it.args.isEmpty() }
                Type.Con(Type.META + "Field", listOf(record, if (any != null) Type.Con(Type.META + "_") else resolve(ref.args[1])))
            }
            "List" -> Type.list(resolveMeta(ref.args.singleOrNull() ?: return resolve(ref)))
            else -> find(ref.name, annotationTypes, ref.pos)?.let { Type.Con(Type.META + "@" + it.name).also { arity(0) } } ?: resolve(ref)
        }
    }

    private fun hasMeta(type: Type): Boolean = (type.prune() as? Type.Con)?.let { it.name.startsWith(Type.META) || it.args.any(::hasMeta) } ?: false

    /** What an argument of [type] must be, for messages. */
    private fun metaName(type: Type): String {
        val t = type.prune() as? Type.Con ?: return "a value of type $type"
        return when (t.name.removePrefix(Type.META).takeIf { t.name.startsWith(Type.META) }) {
            "Type" -> "a type, like List<User>"
            "Spec" -> "a spec, by name"
            "Module" -> t.args.singleOrNull()?.let { "a module that follows ${Type.displayName((it as Type.Con).name)}" } ?: "a module, by name"
            "Case" -> "a case of ${t.args[0]}"
            "Field" -> "a field of ${t.args[0]}, like ${t.args[0]}.name"
            null -> when (t.name) {
                "List" -> "a list of ${metaName(t.args[0])}s"
                Type.TUPLE -> "a tuple of ${t.args.joinToString(", ") { metaName(it) }}"
                else -> "a value of type $type"
            }
            else -> "an annotation, @${t.name.removePrefix(Type.META + "@").substringAfterLast('.')}(...)"
        }
    }

    /** The program's annotations, each against its declaration, from the module it's written in. */
    private fun checkAnnotations() {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Annotation, Boolean>())
        fun check(path: List<String>, annotations: List<Annotation>) {
            currentPath = path
            for (a in annotations) if (seen.add(a)) checkAnnotation(a)
            // What they pass on must agree.
            if (annotations.isNotEmpty()) reached(annotations, path, annotations.first().pos)
        }
        // A module inside one that says what may be done can only narrow it.
        for (m in modules.values) {
            val effects = effectsOf(m.annotations, m.path, m.pos)
            val (outer, allowed) = ceiling(m.path) ?: continue
            (effects - allowed).takeIf { effects.isNotEmpty() && it.isNotEmpty() }?.let {
                throw CompileError(m.pos, "${m.simpleName} is in ${outer.simpleName}, which may only ${allows(allowed)}, so it can't be ${shown(it)}")
            }
        }
        for (d in decls + modules.values) {
            check(d.path, d.annotations)
            when (d) {
                is Decl.Record -> d.fields.forEach { check(d.path, it.annotations) }
                is Decl.Sealed -> d.variants.forEach { v ->
                    check(d.path, v.annotations)
                    v.fields.forEach { check(d.path, it.annotations) }
                }
                is Decl.Function -> d.params.orEmpty().forEach { check(d.path, it.annotations) }
                is Decl.Spec -> d.values.forEach { check(d.path, it.annotations) }
                else -> {}
            }
        }
        currentPath = emptyList()
    }

    private fun checkAnnotation(a: Annotation) {
        // A handled effect, as @Console or @Yielding<Int>: no arguments, and its type arguments, if any.
        if (find(a.name, annotationTypes, null) == null) {
            find(a.name, specs, null)?.takeIf { isEffect(it) }?.let { spec ->
                a.decl = spec
                if (a.args.isNotEmpty()) throw CompileError(a.pos, "an effect takes no arguments")
                if (a.typeArgs.isNotEmpty() && a.typeArgs.size != spec.typeParams.size) {
                    throw CompileError(a.pos, "${spec.simpleName} takes ${spec.typeParams.size} type argument${if (spec.typeParams.size == 1) "" else "s"}, not ${a.typeArgs.size}")
                }
                return
            }
        }
        if (a.typeArgs.isNotEmpty()) throw CompileError(a.pos, "only a generic effect takes type arguments, as @Yielding<Int> does")
        val decl = find(a.name, annotationTypes, a.pos) ?: throw CompileError(
            a.pos,
            if (find(a.name, records, null) != null || find(a.name, sealeds, null) != null) {
                "'${a.name}' isn't an annotation; declare one with annotation type ${a.name.substringAfterLast('.')}(...)"
            } else {
                "unknown annotation '${a.name}'"
            },
        )
        a.decl = decl
        val order = argOrder(a.args.map { it.name to it.value.pos }, decl.fields.map { it.name }, a.pos, "@${a.name}")
        annotationFields.getValue(decl).forEachIndexed { i, type -> checkMeta(a.args[order[i]].value, type) }
    }

    /** Checks [m] is what an argument of [expected] can be, and sets what it refers to. */
    private fun checkMeta(m: Meta, expected: Type) {
        val t = expected.prune() as? Type.Con
        if (t == null || !hasMeta(t)) return checkConstant(m, expected)
        fun wrong(): Nothing = throw CompileError(m.pos, "this should be ${metaName(expected)}")
        val ref = m as? Meta.Ref
        // A reference by name alone: not a type with arguments, or something made with arguments.
        fun name(): String = ref?.takeIf { it.typeArgs == null && it.args == null }?.name ?: wrong()
        m.resolved = when (t.name) {
            "List" -> {
                (m as? Meta.ListOf ?: wrong()).items.forEach { checkMeta(it, t.args[0]) }
                null
            }
            Type.TUPLE -> {
                val items = (m as? Meta.TupleOf)?.items?.takeIf { it.size == t.args.size } ?: wrong()
                items.zip(t.args).forEach { (item, type) -> checkMeta(item, type) }
                null
            }
            Type.META + "Type" -> MetaRef.Type(resolve(typeRefOf(m) ?: wrong()))
            Type.META + "Spec" -> MetaRef.Spec(find(name(), specs, m.pos) ?: throw CompileError(m.pos, "unknown spec '${name()}'"))
            Type.META + "Module" -> {
                val module = lookupModule(name(), m.pos) ?: throw CompileError(m.pos, "unknown module '${name()}'")
                if (module.params.isNotEmpty()) throw CompileError(m.pos, "${module.simpleName} is a functor; pass a module it makes, module Name = ${module.simpleName}(...)")
                val spec = (t.args.singleOrNull() as? Type.Con)?.name
                if (spec != null && moduleSpecs[module.name]?.name != spec) {
                    val simple = Type.displayName(spec).substringAfterLast('.')
                    throw CompileError(m.pos, "${module.simpleName} doesn't follow $simple: declare it as module ${module.simpleName} : $simple")
                }
                MetaRef.Module(module)
            }
            Type.META + "Case" -> {
                val name = name()
                val sealed = sealedName(t.args[0])!!
                val qualifier = name.substringBeforeLast('.', "").ifEmpty { null }?.let { q ->
                    lookupSealed(q, m.pos) ?: throw CompileError(m.pos, "unknown type '$q'")
                }
                val case = cases(sealed, (t.args[0].prune() as Type.Con).args).map { it.first }
                    .firstOrNull { c -> c.name == name.substringAfterLast('.') && (qualifier == null || caseDecls.getValue(qualifier.name).any { it.sameCase(c) }) }
                    ?: throw CompileError(m.pos, "'$name' isn't a case of ${t.args[0]}")
                MetaRef.Case(case.origin, case.variant)
            }
            Type.META + "Field" -> {
                val name = name()
                val owner = name.substringBeforeLast('.', "").ifEmpty { throw CompileError(m.pos, "write a field with its type, like ${t.args[0]}.$name") }
                val wanted = (t.args[0].prune() as Type.Con)
                val record = lookupRecord(owner, m.pos)?.takeIf { it.name == wanted.name } ?: throw CompileError(m.pos, "expected a field of ${t.args[0]}, not of $owner")
                val index = record.fields.indexOfFirst { it.name == name.substringAfterLast('.') }
                if (index < 0) throw CompileError(m.pos, "${record.simpleName} has no field '${name.substringAfterLast('.')}'")
                val actual = fieldTypes(record.typeParams, record.fields, wanted.args)[index]
                val type = t.args[1]
                if ((type.prune() as? Type.Con)?.name != Type.META + "_" && !u.unify(actual, type)) {
                    throw CompileError(m.pos, "$name is a $actual, not a ${type.zonk()}")
                }
                MetaRef.Field(record, record.fields[index])
            }
            else -> {
                val wanted = annotationTypes.getValue(t.name.removePrefix(Type.META + "@"))
                if (ref?.typeArgs != null || find(ref?.name ?: wrong(), annotationTypes, m.pos) !== wanted) wrong()
                val nested = Annotation(m.pos, ref.name, ref.args.orEmpty())
                checkAnnotation(nested)
                MetaRef.Annotation(nested)
            }
        }
    }

    /** [m] as a type: `User`, `List<User>`, `(Int, String)`. */
    private fun typeRefOf(m: Meta): TypeRef? = when (m) {
        is Meta.Ref -> if (m.args != null) null else TypeRef.Named(m.pos, m.name, m.typeArgs.orEmpty())
        is Meta.TupleOf -> TypeRef.Tuple(m.pos, m.items.map { typeRefOf(it) ?: return null })
        else -> null
    }

    /**
     * Checks [m], an ordinary value, as an expression of type [expected]: a constant, a function or
     * value by name, or a case or record made of them. Never a call, which would need running.
     */
    private fun checkConstant(m: Meta, expected: Type) {
        val e = expressionOf(m)
        check(e, expected, Scope(null))
        fun constant(e: Expr) {
            when (e) {
                is Expr.Call -> {
                    if (e.resolved !is CallRef.Construct) throw CompileError(e.pos, "an annotation's arguments are constants and references, so not a call: pass the function itself")
                    e.args.forEach { constant(it.value) }
                }
                is Expr.Member -> if (e.resolved !is MemberRef.Global && e.resolved !is MemberRef.Case) {
                    throw CompileError(e.pos, "an annotation's arguments are constants and references, so not a field of a value")
                }
                is Expr.ListLit -> e.items.forEach(::constant)
                is Expr.Tuple -> e.elements.forEach(::constant)
                else -> {}
            }
        }
        constant(e)
        m.resolved = MetaRef.Value(e)
    }

    private fun expressionOf(m: Meta): Expr = when (m) {
        is Meta.Literal -> m.expr
        is Meta.ListOf -> Expr.ListLit(m.pos, m.items.map(::expressionOf))
        is Meta.TupleOf -> Expr.Tuple(m.pos, m.items.map(::expressionOf))
        is Meta.Ref -> {
            if (m.typeArgs != null) throw CompileError(m.pos, "a type goes only where an annotation's field is a Type, like List<User>")
            val path = m.name.split('.')
            val callee = path.drop(1).fold<String, Expr>(Expr.Name(m.pos, path[0])) { target, name -> Expr.Member(m.pos, target, name) }
            m.args?.let { args -> Expr.Call(m.pos, callee, args.map { Arg(it.name, expressionOf(it.value)) }) } ?: callee
        }
    }

    /** [ds] without annotation types, which are only checked. */
    private fun withoutAnnotationTypes(ds: List<Decl>): List<Decl> = ds.filter { !(it is Decl.Record && it.isAnnotation) }.map { d ->
        // Only a module that holds one is copied, so the rest stay the declarations the checker knows.
        if (d is Decl.Module && hasAnnotationType(d)) d.copy(decls = withoutAnnotationTypes(d.decls)) else d
    }

    private fun hasAnnotationType(m: Decl.Module): Boolean =
        m.decls.any { (it is Decl.Record && it.isAnnotation) || (it is Decl.Module && hasAnnotationType(it)) }

    // ---- Effects ----

    /** Changing what's in [region]. */
    private fun mutating(region: Type): Type = Type.row(listOf(Type.Con(MUTATING, listOf(region))), null)

    /** `x = value` or `r.field = value`: changes a var, so it's `@Mutating` on where the var lives. */
    private fun inferAssign(e: Expr.Assign, scope: Scope): Type {
        val region = when (val target = e.target) {
            is Expr.Name -> {
                val local = scope.lookup(target.name)
                    ?: throw CompileError(target.pos, if (lookupGlobal(target.name, null) != null) "'${target.name}' is a top-level value, which can't be changed" else "unknown name '${target.name}'")
                if (!local.isVar) throw CompileError(target.pos, "'${target.name}' can't be changed; declare it with var ${target.name} = ...")
                infer(target, null, scope)
                local.region!!
            }
            is Expr.Member -> {
                val receiver = infer(target.target, null, scope).prune()
                val (ref, _) = field(target, receiver, scope) ?: throw CompileError(target.pos, "$receiver has no field '${target.name}'")
                target.resolved = ref
                val record = records.getValue((receiver as Type.Con).name)
                if (!record.fields.first { it.name == target.name }.isVar) {
                    throw CompileError(target.pos, "${record.simpleName}'s ${target.name} can't be changed; declare it with var ${target.name}: ...")
                }
                infer(target, null, scope)
                receiver.region!!
            }
            else -> throw CompileError(e.pos, "only a var or a var field can be assigned to")
        }
        check(e.value, e.target.type!!, scope)
        perform(mutating(region), e.pos)
        return Type.UNIT
    }

    /**
     * What [a], written in [path], names: an annotation type, or a handled effect's spec, as
     * `@Console` does; null if it names neither, which checking it reports.
     */
    private fun annotationType(a: Annotation, path: List<String>): Decl? {
        a.decl?.let { return it }
        val saved = currentPath
        currentPath = path
        try {
            return find(a.name, annotationTypes, null) ?: find(a.name, specs, null)?.takeIf { isEffect(it) }
        } finally {
            currentPath = saved
        }
    }

    /** Whether [d] is annotated with the prelude's [name], as `@Meta annotation type Effect` is with Meta. */
    private fun marked(d: Decl, name: String): Boolean = d.annotations.any { annotationType(it, d.path)?.name == name }

    /** An effect: an annotation type or a spec annotated `@Effect`. */
    private fun isEffect(d: Decl): Boolean = marked(d, EFFECT)

    /** An annotation as it reaches what it's on: what it names, as written, and the annotation types it came through. */
    private class Reached(val type: Decl, val annotation: Annotation, val via: List<Decl>)

    /**
     * [annotations], written in [path], with every annotation they pass on: an annotation type's own
     * annotations reach whatever it's used on, as `@Console @Network annotation type Io` makes `@Io`
     * mean both, except `@Meta` ones, which describe the annotation type itself, as `@Effect` does.
     * The same annotation reached twice with different arguments is an error, unless it's
     * `@Repeatable`, or declared directly here, which chooses.
     */
    private fun reached(annotations: List<Annotation>, path: List<String>, pos: Pos): List<Reached> {
        val all = mutableListOf<Reached>()
        fun walk(list: List<Annotation>, at: List<String>, via: List<Decl>) {
            for (a in list) {
                val type = annotationType(a, at) ?: continue
                if (type in via) {
                    val loop = via.dropWhile { it !== type } + type
                    throw CompileError(type.pos, "annotations can't be annotated in a circle: ${loop.joinToString(" -> ") { "@" + it.simpleName }}")
                }
                all += Reached(type, a, via)
                walk(type.annotations.filter { annotationType(it, type.path)?.let { t -> marked(t, META_ANNOTATION) } != true }, type.path, via + type)
            }
        }
        walk(annotations, path, emptyList())
        val direct = all.filter { it.via.isEmpty() }.map { it.type }.toSet()
        val kept = all.filter { it.via.isEmpty() || it.type !in direct }
        for ((type, list) in kept.groupBy { it.type }) {
            if (marked(type, REPEATABLE)) continue
            val values = list.distinctBy { text(it.annotation) }
            if (values.size > 1) {
                val ways = values.joinToString(", and ") { r -> "@" + text(r.annotation) + (r.via.lastOrNull()?.let { " through ${it.simpleName}" } ?: "") }
                throw CompileError(pos, "this gets @${type.simpleName} more than once, with different values: $ways; choose one by declaring @${type.simpleName} here")
            }
        }
        return kept
    }

    /** An annotation as written, to tell apart two with different arguments. */
    private fun text(a: Annotation): String =
        a.name.substringAfterLast('.') + if (a.args.isEmpty()) "" else a.args.joinToString(", ", "(", ")") { (it.name?.let { n -> "$n = " } ?: "") + text(it.value) }

    private fun text(m: Meta): String = when (m) {
        is Meta.Literal -> when (val e = m.expr) {
            is Expr.StringLit -> "\"" + e.parts.joinToString("") { (it as StringPart.Text).text } + "\""
            is Expr.IntLit -> e.value.toString()
            is Expr.FloatLit -> e.value.toString()
            is Expr.BoolLit -> e.value.toString()
            is Expr.CharLit -> "'${e.value}'"
            is Expr.Unary -> "-" + text(Meta.Literal(e.operand))
            else -> "?"
        }
        is Meta.Ref -> m.name + (m.typeArgs?.joinToString(", ", "<", ">") { text(it) } ?: "") + (m.args?.joinToString(", ", "(", ")") { text(it.value) } ?: "")
        is Meta.ListOf -> m.items.joinToString(", ", "[", "]") { text(it) }
        is Meta.TupleOf -> m.items.joinToString(", ", "(", ")") { text(it) }
    }

    /** The effects [annotations], written in [path], allow, directly or through the annotations they're made of. */
    private fun effectsOf(annotations: List<Annotation>, path: List<String>, pos: Pos): Set<String> =
        reached(annotations, path, pos).filter { isEffect(it.type) }.map { it.type.name }.toSet()

    /**
     * The effects a function type's [annotations] say it has, `@Io (String) -> Unit`: each must be
     * or carry one. A generic effect has its type arguments: `@Yielding<Int> () -> Unit`.
     */
    private fun effectLabels(annotations: List<Annotation>): List<Type.Con> = annotations.flatMap { a ->
        val decl = annotationType(a, currentPath) ?: throw CompileError(a.pos, "unknown effect '${a.name}'")
        a.decl = decl
        if (decl is Decl.Spec && a.typeArgs.isNotEmpty()) return@flatMap listOf(Type.Con(decl.name, a.typeArgs.map { resolve(it) }))
        effectsOf(listOf(a), currentPath, a.pos).map { Type.Con(it) }.ifEmpty {
            throw CompileError(a.pos, "'${a.name}' isn't an effect; declare one with @Effect annotation type ${a.name.substringAfterLast('.')}")
        }
    }

    /** The generic effects [annotations], written in [path], give type arguments: `@Yielding<Int>`. */
    private fun declaredLabels(annotations: List<Annotation>, path: List<String>): List<Type.Con> = annotations.mapNotNull { a ->
        val spec = annotationType(a, path) as? Decl.Spec ?: return@mapNotNull null
        if (a.typeArgs.isEmpty()) null else Type.Con(spec.name, a.typeArgs.map { resolve(it) })
    }

    // ---- Handled effects ----

    /** The operation [name] calls from here, if it's one: unqualified, it must be the only one of that name. */
    private fun operation(name: String, pos: Pos): Pair<Decl.Spec, SpecValue>? {
        for (n in currentPath.size downTo 0) {
            val found = operations[(currentPath.take(n) + name).joinToString(".")]?.filter { visible(it.first) }.orEmpty()
            if (found.isEmpty()) continue
            if (found.size > 1) {
                throw CompileError(pos, "'$name' is an operation of ${found.joinToString(" and ") { it.first.simpleName }}; write ${found.first().first.simpleName}.$name")
            }
            return found.single()
        }
        return null
    }

    /** `Console.println`: an operation named through its effect. */
    private fun qualifiedOperation(member: Expr.Member, scope: Scope): Pair<Decl.Spec, SpecValue>? {
        val spec = dotted(member.target, scope)?.let { find(it, specs, null) }?.takeIf { isEffect(it) } ?: return null
        val op = spec.values.firstOrNull { it.name == member.name } ?: throw CompileError(member.pos, "${spec.simpleName} has no operation '${member.name}'")
        return spec to op
    }

    /** A fresh instance of [op]'s type, and the effect using it does: [spec] with the type arguments it's used with. */
    private fun operationType(spec: Decl.Spec, op: SpecValue): Pair<Type, Type.Con> {
        val scheme = specSchemes.getValue(spec).getValue(op.name)
        val mapping = scheme.vars.associateWith { u.fresh() as Type }
        return u.substitute(scheme.type, mapping) to Type.Con(spec.name, specTypeVars.getValue(spec).map { mapping.getValue(it) })
    }

    /** `println("hi")`: performs Console's println, so the code it's in does @Console. */
    private fun callOperation(e: Expr.Call, spec: Decl.Spec, op: SpecValue, scope: Scope): Type {
        val (type, label) = operationType(spec, op)
        val fn = type as? Type.Fn ?: throw CompileError(e.pos, "'${op.name}' is a value of ${spec.simpleName}, not a function; write it without ()")
        when (val callee = e.callee) {
            is Expr.Name -> callee.resolved = NameRef.Operation(spec, op)
            is Expr.Member -> callee.resolved = MemberRef.Operation(spec, op)
            else -> {}
        }
        e.callee.type = fn
        val order = argOrder(e.args.map { it.name to it.value.pos }, op.params!!.map { it.name }, e.pos, "'${op.name}'")
        checkArgs(e.args, order, fn.params, scope)
        e.resolved = CallRef.Function(order)
        perform(Type.row(listOf(label), null), e.pos)
        return fn.result
    }

    /** An operation used as a value, as in `xs.forEach(println)`: a function that performs it when called. */
    private fun operationValue(spec: Decl.Spec, op: SpecValue, pos: Pos): Type {
        val (type, label) = operationType(spec, op)
        if (type !is Type.Fn) {
            perform(Type.row(listOf(label), null), pos)
            return type
        }
        return Type.Fn(type.params, type.result, Type.row(listOf(label), u.fresh()))
    }

    /**
     * `Console(println = s -> ..., readLine = ~"x")`: a handler made from a function, or value, for
     * each of [spec]'s operations. Its answers run where it handles, so what they do is its type's
     * last argument, after the effect's own.
     */
    private fun makeHandler(e: Expr.Call, spec: Decl.Spec, scope: Scope): Type {
        val args = specTypeVars.getValue(spec).map { u.fresh() as Type }
        val answers = u.fresh()
        val types = spec.values.map { v ->
            val scheme = specSchemes.getValue(spec).getValue(v.name)
            val mapping = scheme.vars.associateWith { u.fresh() as Type } + specTypeVars.getValue(spec).zip(args).toMap()
            val t = u.substitute(scheme.type, mapping)
            if (t is Type.Fn) Type.Fn(t.params, t.result, answers) else t
        }
        val order = argOrder(e.args.map { it.name to it.value.pos }, spec.values.map { it.name }, e.pos, spec.simpleName)
        checkArgs(e.args, order, types, scope)
        e.resolved = CallRef.Handler(spec, order)
        return Type.Con(Type.HANDLER + spec.name, args + answers)
    }

    /** A module following [spec], as a handler: what its functions do is what its answers do. */
    private fun moduleHandler(module: Decl.Module, spec: Decl.Spec): Type {
        val answers = u.fresh()
        for (v in spec.values) {
            val f = globals.getValue("${module.name}.${v.name}")
            (globalType(f) as? Type.Fn)?.let { subEffects(it.effects, answers) }
        }
        return Type.Con(Type.HANDLER + spec.name, listOf(answers))
    }

    /** `handler { block }`. */
    private fun inferHandle(e: Expr.Handle, expected: Type?, scope: Scope): Type {
        val handler = infer(e.handler, null, scope).prune() as? Type.Con
        val spec = handler?.name?.takeIf { it.startsWith(Type.HANDLER) }?.let { specs[it.removePrefix(Type.HANDLER)] }
            ?: throw CompileError(e.handler.pos, "this isn't a handler: make one from a handled effect, as Console(println = ...), or use a module that follows one")
        e.handles = spec
        return handling(spec, handler.args.dropLast(1), handler.args.last(), e.block, expected, scope, e.pos)
    }

    /**
     * Runs [block] with [spec]<[args]> handled: inside, it may do the effect; outside, what's left
     * of what it does is done here, as is what the handler's [answers] do.
     */
    private fun handling(spec: Decl.Spec, args: List<Type>, answers: Type, block: Expr.Block, expected: Type?, scope: Scope, pos: Pos): Type {
        val rest = u.fresh()
        val type = doing(Type.row(listOf(Type.Con(spec.name, args)), rest)) { infer(block, expected, scope) }
        perform(rest, pos)
        perform(answers, pos)
        return type
    }

    /** The module `@HandledBy` names as [spec]'s default, which `main` uses if it doesn't handle it. */
    private fun defaultHandler(spec: Decl.Spec): Decl.Module? {
        val annotation = spec.annotations.firstOrNull { annotationType(it, spec.path)?.name == HANDLED_BY } ?: return null
        val name = (annotation.args.singleOrNull()?.value as? Meta.Ref)?.name ?: return null
        val saved = currentPath
        currentPath = spec.path
        try {
            return lookupModule(name, null)
        } finally {
            currentPath = saved
        }
    }

    /**
     * The handlers `main` runs in: every handled effect's default, as code a spec hides may do one
     * where nothing can see it. What `main` does that has no default is an error.
     */
    private fun mainDefaults(main: Decl.Function, row: Type): List<Pair<Decl.Spec, Decl.Module>> {
        for (label in Type.row(row).first) {
            val spec = specs[label.name]?.takeIf { isEffect(it) && it.values.isNotEmpty() } ?: continue
            if (defaultHandler(spec) == null) {
                throw CompileError(main.pos, "main does @${spec.simpleName}, but nothing handles it: run it in a handler, like ${spec.simpleName}(...) { ... }")
            }
        }
        return specs.values.filter { isEffect(it) && it.values.isNotEmpty() }.mapNotNull { spec -> defaultHandler(spec)?.let { spec to it } }
    }

    /**
     * The module around [path] that says what may be done in it, with what: the innermost one with
     * effects, which pass to everything in it. A module a functor made has the functor's.
     */
    private fun ceiling(path: List<String>): Pair<Decl.Module, Set<String>>? {
        for (n in path.size downTo 1) {
            val name = path.take(n).joinToString(".")
            val module = applications[name]?.first ?: modules[name] ?: continue
            val effects = effectsOf(module.annotations, module.path, module.pos)
            if (effects.isNotEmpty()) return module to effects
        }
        return null
    }

    /**
     * What [decl] may do: what its module allows, if its module says, and then it can't add more;
     * otherwise what it declares itself.
     */
    private fun allowed(decl: Decl.Function): Set<String> {
        val own = effectsOf(decl.annotations, decl.path, decl.pos)
        val (module, effects) = ceiling(decl.path) ?: return own
        (own - effects).takeIf { it.isNotEmpty() }?.let {
            throw CompileError(decl.pos, "'${decl.simpleName}' is in ${module.simpleName}, which may only ${allows(effects)}, so it can't be ${shown(it)}")
        }
        return effects
    }

    /** What a module's effects let its functions do, for messages: `do @Console`, or nothing for `@Pure`. */
    private fun allows(effects: Set<String>): String = if (effects - PURE == emptySet<String>()) "be pure" else "do ${shown(effects - PURE)}"

    /** Effects as they're written: `@Console @FileSystem`. */
    private fun shown(effects: Set<String>): String = effects.sorted().joinToString(" ") { "@" + it.substringAfterLast('.') }

    private inline fun <R> withArrowEffects(row: Type, block: () -> R): R {
        val saved = arrowEffects
        arrowEffects = row
        try {
            return block()
        } finally {
            arrowEffects = saved
        }
    }

    /** Checks [block] as code that does [row], as a function's or a lambda's body does. */
    private inline fun <R> doing(row: Type, block: () -> R): R {
        val saved = effects
        effects = row
        try {
            return block()
        } finally {
            effects = saved
        }
    }

    /**
     * Adds what a call does, [row], to what the code being checked does. A row with no rest gets
     * one, so the code can do other things too. It fails only where the code must do no more than
     * a written type says: a lambda passed where an `@Console (String) -> Unit` is wanted can't
     * also write files.
     */
    private fun perform(row: Type, pos: Pos) {
        val (labels, rest) = Type.row(row)
        val open = if (rest == null) Type.row(labels, u.fresh()) else row
        if (!u.unify(open, effects)) {
            val allowed = Type.effectNames(effects)
            val extra = labels.map { it.name }.toSet() - allowed
            val can = if (allowed.isEmpty()) "says it's pure" else "says it can only do ${shown(allowed)}"
            throw CompileError(pos, "this does ${shown(extra)}, but the type of the function it's in $can")
        }
    }

    /**
     * That [decl] may do what its body does, [row]: what it declares, or what its module allows.
     * `main` may do anything, as running a program is what it's for; a top-level value can't do
     * anything, as it runs on its own.
     */
    private fun checkEffects(decl: Decl.Function, row: Type) {
        if (decl.name == "main") return
        // A module a functor made was checked as the functor; what it does with its real modules is theirs.
        if (enclosingApplication(decl.path) != null) return
        // What a functor's parameters do is theirs too: a use case may do whatever its repositories do.
        val done = Type.effectNames(row) - parameterEffects(decl.path)
        if (decl.params == null) {
            if (done.isNotEmpty()) throw CompileError(decl.pos, "'${decl.simpleName}' does ${shown(done)}, but a top-level value runs when the program starts; make it a function: ${decl.simpleName}() = ...")
            return
        }
        val undeclared = done - allowed(decl)
        if (undeclared.isEmpty()) return
        ceiling(decl.path)?.let { (module, effects) ->
            throw CompileError(decl.pos, "'${decl.simpleName}' does ${shown(undeclared)}, but it's in ${module.simpleName}, which may only ${allows(effects)}")
        }
        throw CompileError(decl.pos, "'${decl.simpleName}' does ${shown(undeclared)}, so it must say so: ${shown(undeclared)} ${decl.simpleName}(...)")
    }

    /**
     * [row] without changes to regions [type] doesn't mention: values that were made inside the
     * function and never left it, which no one else can see change. [row] is the function's own,
     * which its parameters' function types share, so it doesn't count as mentioning them.
     */
    private fun masked(row: Type, type: Type): Type {
        val (labels, rest) = Type.row(row)
        val visible = regionsIn(type, row.prune())
        // A written @Mutating says nothing about where, so it stays.
        return Type.row(labels.filter { it.name != MUTATING || it.args.firstOrNull()?.prune().let { r -> r !is Type.Var || r in visible } }, rest)
    }

    /** The regions [type] mentions, in its values' types and in what its functions change, but not in [skip]. */
    private fun regionsIn(type: Type, skip: Type? = null, into: MutableSet<Type> = mutableSetOf()): Set<Type> {
        when (val t = type.prune()) {
            is Type.Var -> {}
            is Type.Con -> {
                t.region?.prune()?.let { into += it }
                if (t.name == Type.EFFECTS) {
                    Type.row(t).first.forEach { label -> label.args.firstOrNull()?.prune()?.let { into += it } }
                } else {
                    t.args.forEach { regionsIn(it, skip, into) }
                }
            }
            is Type.Fn -> {
                t.params.forEach { regionsIn(it, skip, into) }
                regionsIn(t.result, skip, into)
                if (t.effects.prune() !== skip) regionsIn(t.effects, skip, into)
            }
        }
        return into
    }

    // ---- Specs ----

    /** Resolves each spec's values' types, with the spec's own types as `@T`. */
    private fun checkSpecs() {
        for (spec in specs.values) {
            currentPath = spec.path
            checkDistinct(spec.types.map { it.name to it.pos })
            checkDistinct(spec.values.map { it.name to it.pos })
            spec.types.firstOrNull { it.name in BUILTIN_TYPES }?.let { throw CompileError(it.pos, "'${it.name}' is a built-in type") }
            specTypes = spec.types.associate { it.name to it.typeParams.size }
            val specVars = spec.typeParams.associateWith { u.fresh() }
            specTypeVars[spec] = specVars.values.toList()
            specSchemes[spec] = spec.values.associate { v ->
                val vars = v.typeParams.associateWith { u.fresh() }
                typeParams = specVars + vars
                val result = resolve(v.type)
                // What it may do: what it declares, and whatever its function parameters do.
                val rest = u.fresh()
                val row = Type.effects(specEffects(spec, v), rest)
                val type = v.params?.let { params ->
                    checkDistinct(params.map { it.name to it.pos })
                    arrowEffects = row
                    try {
                        Type.Fn(params.map { resolve(it.type!!) }, result, row)
                    } finally {
                        arrowEffects = null
                    }
                } ?: result
                v.name to Scheme(specVars.values.toList() + vars.values.toList() + rest, type)
            }
            typeParams = emptyMap()
            // A handled effect's operations are called by name wherever it can be seen.
            if (isEffect(spec)) for (v in spec.values) operations.getOrPut((spec.path + v.name).joinToString(".")) { mutableListOf() } += spec to v
        }
        specTypes = emptyMap()
        currentPath = emptyList()
        // A functor's parameter's values have their spec's types, its types abstract.
        for (f in parameterMembers) {
            val module = f.path.joinToString(".")
            val wanted = specSchemes.getValue(moduleSpecs.getValue(module)).getValue(f.simpleName)
            outsideSchemes[f] = Scheme(wanted.vars, specView(wanted.type, emptyMap()) { name, args -> outsideType(module, name, args) })
        }
    }

    /** Each module with a spec defines everything the spec lists, publicly; types take the right number of parameters. */
    private fun checkModuleSpecs() {
        for ((name, spec) in moduleSpecs) {
            val m = modules.getValue(name)
            for (t in spec.types) {
                val d = typeDecl("$name.${t.name}")
                    ?: throw CompileError(m.pos, "${m.simpleName} doesn't define type ${t.name}, which its spec ${spec.simpleName} needs")
                if (d.isPrivate) throw CompileError(d.pos, "${t.name} is in ${m.simpleName}'s spec ${spec.simpleName}, so it can't be private")
                val n = typeParamsOf(d).size
                if (n != t.typeParams.size) {
                    throw CompileError(d.pos, "${spec.simpleName} says ${t.name} takes ${t.typeParams.size} type parameter${if (t.typeParams.size == 1) "" else "s"}, not $n")
                }
            }
            for (v in spec.values) {
                val f = globals["$name.${v.name}"]
                    ?: throw CompileError(m.pos, "${m.simpleName} doesn't define '${v.name}', which its spec ${spec.simpleName} needs")
                if (f.isPrivate) throw CompileError(f.pos, "'${v.name}' is in ${m.simpleName}'s spec ${spec.simpleName}, so it can't be private")
            }
        }
        currentPath = emptyList()
    }

    /**
     * Checks that [decl], once inferred, has the type its module's spec gives it, where the spec's
     * types are what the module made them and its type parameters work for any type. Then records
     * the type callers outside the module see.
     */
    private fun conform(decl: Decl.Function) {
        val module = decl.path.joinToString(".")
        val spec = moduleSpecs[module] ?: return
        val wanted = specSchemes.getValue(spec)[decl.simpleName] ?: return
        val names = wanted.vars.zip(spec.values.first { it.name == decl.simpleName }.typeParams)
        // Its type parameters must work for any type; what else it may do is open, to be checked below.
        val rest = wanted.vars.drop(names.size).associateWith { u.fresh() as Type }
        val rigid = names.associate { (v, n) -> v to Type.Con("#$n") as Type } + rest
        val inside = specView(wanted.type, rigid) { name, args -> typeOf(typeDecl("$module.$name")!!, args) }
        val actual = u.instantiate(schemes.getValue(decl))
        val shown = actual.zonk()
        if (!u.unify(actual, inside)) {
            throw CompileError(decl.pos, "'${decl.simpleName}' has type $shown, but ${spec.simpleName} needs ${Type.render(wanted.type, names.toMap().toMutableMap())}, which here is ${inside.zonk()}")
        }
        // What it does, against what the spec says its function may do.
        val value = spec.values.first { it.name == decl.simpleName }
        val declared = specEffects(spec, value)
        val hides = hides(spec, value)
        val done = (inside as? Type.Fn)?.let { Type.effectNames(it.effects) }.orEmpty()
        val extra = done - declared
        if (extra.isNotEmpty() && declared.isNotEmpty() && !hides) {
            throw CompileError(decl.pos, "'${decl.simpleName}' does ${shown(extra)}, but ${spec.simpleName}'s ${decl.simpleName} doesn't say it can: declare it there, as ${shown(extra)} ${decl.simpleName}(...)")
        }
        // An effect with operations needs a handler, which callers can't give if they can't see it:
        // so it can be hidden only if it has a default, which main always puts around everything.
        if (hides) {
            extra.firstOrNull { name -> specs[name]?.let { isEffect(it) && it.values.isNotEmpty() && defaultHandler(it) == null } == true }?.let {
                val effect = it.substringAfterLast('.')
                throw CompileError(
                    decl.pos,
                    "'${decl.simpleName}' does ${shown(setOf(it))}, which needs a handler and has no default, so ${spec.simpleName} can't hide it: " +
                        "handle it inside ${module.substringAfterLast('.')}, or give $effect a default with @HandledBy",
                )
            }
        }
        // Callers outside see the spec's view: with what the spec says it does, or, where the spec
        // says nothing, what this one does.
        val shownType = specView(wanted.type, emptyMap()) { name, args -> outsideType(module, name, args) }
        outsideSchemes[decl] = if (declared.isEmpty() && shownType is Type.Fn) {
            val rest = u.fresh()
            Scheme(wanted.vars + rest, Type.Fn(shownType.params, shownType.result, Type.effects(done, rest)))
        } else {
            Scheme(wanted.vars, shownType)
        }
    }

    /**
     * What [spec]'s [value] says it may do: its own effects, or, if it has none, the spec's, which
     * pass to everything in it as a module's do. Empty if neither says: then it's whatever the
     * module that follows it does.
     */
    private fun specEffects(spec: Decl.Spec, value: uk.m4xy.fckt.syntax.SpecValue): Set<String> =
        effectsOf(value.annotations, spec.path, value.pos).ifEmpty { effectsOf(spec.annotations, spec.path, spec.pos) }

    /**
     * Whether what a module following [spec] does beyond what [value] says stays inside it: so for
     * a spec annotated with an `@Encapsulates` annotation, as `@Repository` may be, unless [value]
     * says what it does itself.
     */
    private fun hides(spec: Decl.Spec, value: uk.m4xy.fckt.syntax.SpecValue): Boolean =
        effectsOf(value.annotations, spec.path, value.pos).isEmpty() &&
            reached(spec.annotations, spec.path, spec.pos).any { it.type.name == ENCAPSULATES || marked(it.type, ENCAPSULATES) }

    /** The effects the specs of the functor around [path] say its parameters' functions have, which aren't the functor's own. */
    private fun parameterEffects(path: List<String>): Set<String> {
        val functor = enclosingFunctor(path) ?: return emptySet()
        return functorParams[functor.name].orEmpty().flatMap { (_, spec) -> spec.values.flatMap { specEffects(spec, it) } }.toSet()
    }

    /** A spec's [type] with its type variables replaced from [vars], and each of its own types by [own]. */
    private fun specView(type: Type, vars: Map<Type.Var, Type>, own: (String, List<Type>) -> Type): Type = when (val t = type.prune()) {
        is Type.Var -> vars[t] ?: t
        is Type.Fn -> Type.Fn(t.params.map { specView(it, vars, own) }, specView(t.result, vars, own), specView(t.effects, vars, own))
        is Type.Con -> t.args.map { specView(it, vars, own) }.let { args ->
            if (t.name.startsWith(Type.SPEC_TYPE)) own(t.name.removePrefix(Type.SPEC_TYPE), args) else Type.Con(t.name, args)
        }
    }

    /** Field types of a type declaration, with its type parameters replaced by [args]. */
    /** Field types of a type declaration, with its type parameters replaced by [args], holding values in [region]. */
    private fun fieldTypes(params: List<String>, fields: List<uk.m4xy.fckt.syntax.FieldDef>, args: List<Type>, region: Type? = null): List<Type> {
        val saved = typeParams to currentRegion
        typeParams = params.zip(args).toMap()
        currentRegion = region
        try {
            return fields.map { resolve(it.type) }
        } finally {
            typeParams = saved.first
            currentRegion = saved.second
        }
    }

    // ---- Dependency order ----

    private fun dependencyGroups(): List<FunctionGroup> {
        val decls = globals.values.filter { it !in parameterMembers }
        val deps = decls.associateWith { decl -> refsOf(decl).also { uses[decl] = it }.filter { it !in parameterMembers } }
        return stronglyConnected(decls) { deps.getValue(it) }.map { component ->
            val recursive = component.size > 1 || component[0] in deps.getValue(component[0])
            component.firstOrNull { it.params == null && recursive }?.let {
                throw CompileError(it.pos, "value '${it.simpleName}' is defined in terms of itself")
            }
            // OCaml modules can't call each other, so neither can functions in different ones.
            component.map { it.path }.distinct().takeIf { it.size > 1 }?.let {
                throw CompileError(
                    component.first().pos,
                    "${component.joinToString(" and ") { "'${it.name}'" }} call each other but are in different modules",
                )
            }
            FunctionGroup(component, recursive)
        }
    }

    /** The functions and values [decl]'s body may use; once it's checked, only those it does. */
    private fun refsOf(decl: Decl.Function): List<Decl.Function> {
        val refs = linkedSetOf<String>()
        val bound = (decl.params?.map { it.name } ?: emptyList()).toSet()
        collectRefs(decl.body, bound, refs)
        // A name may be plain (`gcd`) or go through modules (`Ratios.make`), seen from the decl's module.
        val saved = currentPath
        currentPath = decl.path
        try {
            return refs.flatMap { ref ->
                // A method's name may be any extension of that name.
                // A module used as a handler: its functions.
                lookupModule(ref, null)?.takeIf { m -> moduleSpecs[m.name]?.let { isEffect(it) } == true }
                    ?.let { m -> globals.values.filter { it.path == m.name.split('.') } }.orEmpty() +
                extensions[ref].orEmpty().filter { visible(it) } + listOfNotNull(
                    lookupGlobal(ref, null) ?: ref.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { module ->
                        lookupModule(module, null)?.let { m -> globals[m.name + "." + ref.substringAfterLast('.')]?.takeIf { visible(it) } }
                    },
                )
            }.distinct()
        } finally {
            currentPath = saved
        }
    }

    /**
     * Names an expression may refer to that aren't bound inside it. Over-approximates, which is
     * harmless; once checked, a built-in method or a field isn't taken for a function of its name.
     */
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
                val callee = e.callee
                when {
                    callee is Expr.Member && e.resolved is CallRef.Builtin -> go(callee.target)
                    // Once checked, a method call is to the one extension it resolved to.
                    callee is Expr.Member && e.resolved is CallRef.Extension -> {
                        go(callee.target)
                        into += (e.resolved as CallRef.Extension).decl.name
                    }
                    else -> go(callee)
                }
                e.args.forEach { go(it.value) }
            }
            is Expr.Member -> {
                go(e.target)
                (e.resolved as? MemberRef.Extension)?.let { into += it.decl.name }
                val known = e.resolved is MemberRef.Builtin || e.resolved is MemberRef.Field || e.resolved is MemberRef.Extension
                if (e.name !in bound && !known) into += e.name // possibly an extension call
                dotted(e, null)?.takeIf { it.substringBefore('.') !in bound }?.let { into += it } // possibly Module.member
            }
            is Expr.Binary -> {
                go(e.left)
                go(e.right)
            }
            is Expr.Unary -> go(e.operand)
            is Expr.Assign -> {
                go(e.target)
                go(e.value)
            }
            is Expr.Handle -> {
                go(e.handler)
                go(e.block)
            }
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
                        is Statement.Bind -> {
                            go(s.value, b)
                            b = b + patternNames(s.pattern)
                        }
                    }
                }
                go(e.result, b)
            }
            is Expr.Monadic -> {
                dotted(e.module, null)?.takeIf { it.substringBefore('.') !in bound }?.let { module ->
                    listOf("bind", "pure", "map", "product").forEach { into += "$module.$it" }
                }
                go(e.block)
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
        checkGroupIn(group)
        // `xs.map(f)` in a function called map looked like it might call itself; now it's known.
        val only = group.decls.singleOrNull()
        if (group.recursive && only != null && only !in refsOf(only)) group.recursive = false
    }

    private fun checkGroupIn(group: FunctionGroup) {
        val app = enclosingApplication(group.decls.first().path) ?: return checkGroupBody(group)
        // A module made by a functor: its modules first, then the functor's body for them.
        checkArgumentTypes(app)
        try {
            checkGroupBody(group)
        } catch (e: CompileError) {
            val (functor, args) = applications.getValue(app)
            throw CompileError(modules.getValue(app).pos, "in ${functor.simpleName}<${args.joinToString { it.simpleName }}>, at ${e.pos}: ${e.message}")
        }
    }

    private fun checkGroupBody(group: FunctionGroup) {
        val declaredParams = mutableMapOf<Decl.Function, Map<String, Type.Var>>()
        val outside = mutableMapOf<Decl.Function, Type>()
        currentPath = group.decls.first().path
        val rows = mutableMapOf<Decl.Function, Type>()
        val declaredArgs = mutableMapOf<Decl.Function, List<Type.Con>>()
        for (decl in group.decls) {
            val vars = decl.typeParams.associateWith { u.fresh() }
            declaredParams[decl] = vars
            typeParams = vars
            val result = decl.returnType?.let { resolve(it) } ?: u.fresh()
            // What it does: what its body's calls do, which is also what its function parameters may do.
            val row = u.fresh()
            rows[decl] = row
            declaredArgs[decl] = declaredLabels(decl.annotations, decl.path)
            val inside = decl.params?.let { params ->
                checkDistinct(params.map { it.name to it.pos })
                Type.Fn(withArrowEffects(row) { params.map { p -> p.type?.let { resolve(it) } ?: u.fresh() } }, result, row)
            } ?: result
            provisional[decl] = inside
            // Inside, an opaque type is what it stands for; callers outside see the type as written.
            if (decl !in parameterMembers && opaqueAround(decl.path)) {
                outsideView = true
                try {
                    val shownResult = decl.returnType?.let { resolve(it) } ?: result
                    outside[decl] = if (inside is Type.Fn) {
                        val params = withArrowEffects(row) { decl.params!!.zip(inside.params).map { (p, t) -> p.type?.let { resolve(it) } ?: t } }
                        Type.Fn(params, shownResult, row)
                    } else {
                        shownResult
                    }
                } finally {
                    outsideView = false
                }
            }
        }
        for (decl in group.decls) {
            typeParams = declaredParams.getValue(decl)
            val type = provisional.getValue(decl)
            val scope = Scope(null)
            val row = rows.getValue(decl)
            // An external's body is OCaml, trusted to have its declared type and effects.
            if (decl.external != null) {
                // An external that's @Mutating changes what it's given, so where that was made decides who sees it.
                // What it does is what it declares: its body is OCaml, which can't be checked.
                val declared = effectsOf(decl.annotations, decl.path, decl.pos)
                allowed(decl)
                val regions = (type as? Type.Fn)?.params.orEmpty().flatMap { regionsIn(it) }.filterIsInstance<Type.Var>().distinct()
                val changes = if (MUTATING in declared && regions.isNotEmpty()) regions.map { Type.Con(MUTATING, listOf(it)) } else emptyList()
                val labels = declared.filter { it != MUTATING || changes.isEmpty() }.map { Type.Con(it) } + changes
                u.unify(row, Type.row(labels, u.fresh()))
                continue
            }
            effects = row
            if (type is Type.Fn) {
                decl.params!!.zip(type.params).forEach { (p, t) -> scope.vars[p.name] = Local(t, snake(p.name)) }
                check(decl.body, type.result, scope)
            } else {
                check(decl.body, type, scope)
            }
        }
        effects = u.fresh()
        typeParams = emptyMap()
        // What each does as callers see it: changing values that never left it doesn't count.
        val done = rows.mapValues { (decl, row) -> masked(row, provisional.getValue(decl)) }
        // A generic effect's declared type arguments are what it does: @Yielding<Int> yields Ints.
        for ((decl, labels) in declaredArgs) {
            for (label in labels) {
                val did = Type.row(done.getValue(decl)).first.firstOrNull { it.name == label.name } ?: continue
                if (!did.args.zip(label.args).all { (p, q) -> u.unify(p, q) }) {
                    throw CompileError(decl.pos, "'${decl.simpleName}' does @${Type.displayName(label.name).substringAfterLast('.')}<${did.args.joinToString { it.zonk().toString() }}>, but says <${label.args.joinToString { it.zonk().toString() }}>")
                }
            }
        }
        group.decls.firstOrNull { it.name == "main" }?.let { mainHandlers = mainDefaults(it, done.getValue(it)) }
        for (decl in group.decls) if (decl.external == null) checkEffects(decl, done.getValue(decl))
        for (decl in group.decls) {
            // A declared type parameter must stay abstract: `id<T>(x: T): Int = x` is an error.
            val vars = declaredParams.getValue(decl)
            val seen = mutableSetOf<Type>()
            for ((name, v) in vars) {
                val t = v.prune()
                if (t !is Type.Var || !seen.add(t)) throw CompileError(decl.pos, "type parameter $name of '${decl.name}' is always $t here")
            }
            fun seen(t: Type) = (if (t is Type.Fn) Type.Fn(t.params, t.result, done.getValue(decl)) else t).zonk()
            val type = seen(provisional.remove(decl)!!)
            // A top-level value lives as long as the program, and so do the values it holds.
            if (decl.params == null) regionsIn(type).forEach { if (it is Type.Var) u.unify(it, Type.GLOBAL) }
            // Values aren't generalized: OCaml's value restriction wouldn't generalize most of them either.
            schemes[decl] = if (decl.params == null) Scheme.mono(type) else Scheme(type.freeVars().toList(), type)
            outside[decl]?.let(::seen)?.let { shown -> outsideSchemes[decl] = if (decl.params == null) Scheme.mono(shown) else Scheme(shown.freeVars().toList(), shown) }
        }
        for (decl in group.decls) conform(decl)
        // Now its types are known, each `when` must cover every value of its subject's.
        val whens = pendingWhens.toList()
        pendingWhens.clear()
        if (coverage) whens.forEach { (e, type) -> checkCovers(e, type) }
    }

    /** That [e]'s patterns match every value of [subject], as Kotlin requires of a `when` over a sealed type. */
    private fun checkCovers(e: Expr.When, subject: Type) {
        val patterns = e.branches.flatMap { b -> if (b.patterns.isEmpty()) listOf(Pat.ANY) else b.patterns.flatMap(::coverage) }
        val missing = Coverage(::shapes).missing(patterns, subject.zonk())
        if (missing.isEmpty()) return
        val type = subject.prune()
        if (missing.size == 1 && missing[0].ctor == null) {
            val what = if (type is Type.Var) "every value" else "every $type"
            throw CompileError(e.pos, "this when doesn't cover $what; add an else")
        }
        val shown = missing.map(Coverage::show)
        val list = if (shown.size > 10) shown.take(8).joinToString(", ") + " and ${shown.size - 8} more" else shown.joinToString(", ")
        throw CompileError(e.pos, "this when doesn't cover $list; add ${if (shown.size == 1) "it" else "them"}, or an else")
    }

    /** The shapes a value of [type] can have, with their fields' types; null if they can't all be listed. */
    private fun shapes(type: Type): List<Pair<Ctor, List<Type>>>? {
        val t = type.prune() as? Type.Con ?: return null
        val args = t.args
        return when {
            t.name in sealeds -> cases(t.name, args, t.region).map { (case, fields) -> Ctor.Case(case.origin.name, case.name) to fields }
            t.name in records -> records.getValue(t.name).let { r -> listOf(Ctor.Record(r.name) to fieldTypes(r.typeParams, r.fields, args, t.region)) }
            t.name == Type.TUPLE -> listOf(Ctor.Tuple(args.size) to args)
            t.name == "List" -> listOf(Ctor.Nil to emptyList(), Ctor.Cons to listOf(args[0], t))
            t.name == "Option" -> listOf(Ctor.Case("", "Some") to listOf(args[0]), Ctor.Case("", "None") to emptyList())
            t.name == "Result" -> listOf(Ctor.Case("", "Ok") to listOf(args[0]), Ctor.Case("", "Error") to listOf(args[1]))
            t.name == "Boolean" -> listOf(Ctor.Bool(true) to emptyList(), Ctor.Bool(false) to emptyList())
            else -> null
        }
    }

    /** What [p] matches, as coverage patterns: several for `is Vowel`, which matches each of Vowel's cases. */
    private fun coverage(p: Pattern): List<Pat> = when (p) {
        is Pattern.Wildcard, is Pattern.Bind -> listOf(Pat.ANY)
        is Pattern.Literal -> listOf(literal(p.expr))
        is Pattern.Tuple -> product(p.elements.map(::coverage)).map { Pat(p.record?.let { Ctor.Record(it.name) } ?: Ctor.Tuple(p.elements.size), it) }
        is Pattern.ListPattern -> {
            // [a, b, *rest] is a, then b, then rest; without a rest, the list ends there.
            val end = p.rest?.let(::coverage) ?: listOf(Pat(Ctor.Nil))
            p.elements.foldRight(end) { element, tails -> coverage(element).flatMap { e -> tails.map { Pat(Ctor.Cons, listOf(e, it)) } } }
        }
        is Pattern.Constructor -> {
            val narrowTo = p.narrowTo
            if (narrowTo != null) {
                caseDecls.getValue(narrowTo.name).map { c -> Pat(Ctor.Case(c.origin.name, c.name), c.fields.map { Pat.ANY }) }
            } else when (val ref = p.resolved) {
                is ConstructorRef.Record -> {
                    val args = p.args?.map(::coverage) ?: ref.decl.fields.map { listOf(Pat.ANY) }
                    product(args).map { Pat(Ctor.Record(ref.decl.name), it) }
                }
                is ConstructorRef.Variant -> {
                    val ctor = Ctor.Case(ref.sealed?.name ?: "", ref.name)
                    val arity = ref.variant?.fields?.size ?: if (ref.name == "None") 0 else 1
                    product(p.args?.map(::coverage) ?: List(arity) { listOf(Pat.ANY) }).map { Pat(ctor, it) }
                }
                null -> listOf(Pat.ANY)
            }
        }
    }

    private fun literal(e: Expr): Pat = when (e) {
        is Expr.BoolLit -> Pat(Ctor.Bool(e.value))
        is Expr.IntLit -> Pat(Ctor.Literal(e.value.toString()))
        is Expr.FloatLit -> Pat(Ctor.Literal(e.value.toString()))
        is Expr.CharLit -> Pat(Ctor.Literal("'${e.value}'"))
        is Expr.StringLit -> Pat(Ctor.Literal("\"" + e.parts.joinToString("") { (it as? StringPart.Text)?.text ?: "\$" } + "\""))
        is Expr.Unary -> Pat(Ctor.Literal(e.op + ((literal(e.operand).ctor as? Ctor.Literal)?.text ?: "")))
        else -> Pat(Ctor.Literal(e.toString()))
    }

    /** Every way of picking one from each of [choices]. */
    private fun product(choices: List<List<Pat>>): List<List<Pat>> =
        choices.fold(listOf(emptyList())) { acc, options -> acc.flatMap { prefix -> options.map { prefix + it } } }

    private fun checkDistinct(names: List<Pair<String, Pos>>) {
        names.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
            throw CompileError(it[1].second, "'${it[1].first}' is declared twice")
        }
    }

    /** Whether a module that [path] is in has an opaque alias, which its functions' inferred types show as what it stands for. */
    private fun opaqueAround(path: List<String>): Boolean =
        aliases.values.any { it.isOpaque && it.path.isNotEmpty() && path.take(it.path.size) == it.path }

    private fun globalType(decl: Decl.Function): Type {
        if (!inside(decl.path)) outsideSchemes[decl]?.let { return u.instantiate(it) }
        return provisional[decl] ?: u.instantiate(schemes[decl] ?: error("${decl.name} used before it was checked"))
    }

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
     * differ by a sealed type that's a subset of another (a Vowel used as an Alphabet), which
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

    /** A case written out takes on any sealed type that has it: `Alphabet.E` is a Vowel where a Vowel is wanted. */
    private fun retypeCase(e: Expr, expected: Type): Boolean {
        val case = caseOf(e) ?: return false
        val target = expected.prune() as? Type.Con ?: return false
        if (target.name !in sealeds || sealedName(e.type ?: return false) == target.name) return false
        if (cases(target.name, target.args, target.region).none { it.first.origin === case.sealed && it.first.variant === case.variant }) return false
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
                    y.params.zip(x.params).all { (a, b) -> subtype(a, b) } && subtype(x.result, y.result) && subEffects(x.effects, y.effects)
            else -> false
        }
    }

    /** A function that does [x] can be used where one that does [y] is wanted: [x] is within [y]. */
    private fun subEffects(x: Type, y: Type): Boolean {
        val (labels, rest) = Type.row(x)
        return u.unify(if (rest == null) Type.row(labels, u.fresh()) else x, y)
    }

    /** Every case of sealed type [x] is a case of [y], with the same field types. */
    private fun caseSubset(x: Type.Con, y: Type.Con): Boolean {
        // The same value, so the same region.
        if (x.region != null && y.region != null && !u.unify(x.region, y.region)) return false
        val ys = cases(y.name, y.args, y.region)
        return cases(x.name, x.args, x.region).all { (case, types) ->
            val match = ys.firstOrNull { it.first.sameCase(case) } ?: return false
            types.zip(match.second).all { (a, b) -> u.unify(a, b) }
        }
    }

    /**
     * The type of several branches' results together. Where they're different sealed types, it's the
     * smallest declared sealed type that has all their cases: `if (c) Alphabet.A else Digit.Zero` is a
     * Symbol if `type Symbol { Alphabet.A, Digit.Zero }` is declared.
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
                    "no type has all of ${needed.distinctBy { it.origin.name + "." + it.name }.joinToString { "${it.origin.name}.${it.name}" }}; " +
                        "declare one that does, or annotate the type",
                )
        }
        exprs.zip(types).forEach { (e, t) -> assign(e, t, result) }
        return result
    }

    private companion object {
        /** Built-in types whose values can be widened inside them: a List<Vowel> is a List<Alphabet>. */
        val COVARIANT = setOf("List", "Option", "Lazy", "Result", Type.TUPLE)
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
        is Expr.Assign -> inferAssign(e, scope)
        is Expr.Handle -> inferHandle(e, expected, scope)
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
        is Expr.Block -> inferBlock(e, expected, scope, null)
        is Expr.Monadic -> inferMonadic(e, scope)
    }

    private fun inferName(e: Expr.Name, expected: Type?, scope: Scope): Type {
        scope.lookup(e.name)?.let {
            e.resolved = NameRef.Local(it.ocamlName, it.isVar)
            return it.type
        }
        lookupGlobal(e.name, e.pos)?.let {
            e.resolved = NameRef.Global(it)
            return globalType(it)
        }
        operation(e.name, e.pos)?.let { (spec, op) ->
            e.resolved = NameRef.Operation(spec, op)
            return operationValue(spec, op, e.pos)
        }
        constructor(e.name, expected, e.pos, asValue = true)?.let { shape ->
            e.resolved = NameRef.Constructor(shape.ref)
            return if (shape.fieldTypes.isEmpty()) shape.result else Type.Fn(shape.fieldTypes, shape.result, u.fresh())
        }
        Builtins.function(e.name, u)?.let { (type, ocaml) ->
            e.resolved = NameRef.Builtin(ocaml)
            return type
        }
        // A module following a handled effect's spec is a handler.
        lookupModule(e.name, null)?.let { m ->
            moduleSpecs[m.name]?.takeIf { isEffect(it) }?.let { spec ->
                e.resolved = NameRef.Handler(m, spec)
                return moduleHandler(m, spec)
            }
        }
        throw CompileError(e.pos, "unknown name '${e.name}'")
    }

    /**
     * Finds the constructor [name] (a record type, a variant, `Some` or `None`). A [qualifier] names
     * where to look: a sealed type (`Alphabet.A`) or a module (`Ratios.Ratio`, `Shapes.Circle`).
     * Where [hint], the type the constructor should build, is a sealed type with that case, the
     * result has that type; so a variant name used by several sealed types is told apart by what's
     * expected. [building] is false in patterns, which may take apart a record they couldn't build.
     */
    private fun constructor(
        name: String,
        hint: Type?,
        pos: Pos,
        asValue: Boolean = false,
        qualifier: String? = null,
        building: Boolean = true,
    ): ConstructorShape? {
        val qualifierSealed = qualifier?.let { lookupSealed(it, pos) }?.also { if (abstractHere(it)) abstractError(it, pos) }
        val qualifierModule = if (qualifier != null && qualifierSealed == null) lookupModule(qualifier, pos) ?: return null else null
        val record = when {
            qualifierModule != null -> records[qualifierModule.name + "." + name]?.also { r ->
                whyHidden(r)?.let { throw CompileError(pos, it) }
            }
            qualifier == null -> lookupRecord(name, pos)
            else -> null
        }
        if (record != null) {
            if (abstractHere(record)) abstractError(record, pos)
            if (record.external != null) throw CompileError(pos, "${record.simpleName} is an OCaml type, made by its functions, not by ${record.simpleName}(...)")
            if (building && record.privateConstructor && !inside(record.path)) {
                throw CompileError(pos, "${record.simpleName}'s constructor is private to ${record.path.joinToString(".")}")
            }
            val args = record.typeParams.map { u.fresh() }
            val region = newRegion(record)
            return ConstructorShape(
                ConstructorRef.Record(record),
                record.fields.map { it.name },
                fieldTypes(record.typeParams, record.fields, args, region),
                Type.Con(record.name, args, region),
            )
        }
        // Ok and Error are Result's, unless the program has cases of its own by those names.
        val wantsResult = when (val h = hint?.prune()) {
            is Type.Con -> h.name == "Result"
            is Type.Fn -> (h.result.prune() as? Type.Con)?.name == "Result"
            else -> false
        }
        if (qualifier == null && wantsResult) resultCase(name)?.let { return it }
        if (qualifier == null && (name == "Some" || name == "None")) {
            val a = u.fresh()
            val ref = ConstructorRef.Variant(null, null, name)
            return if (name == "Some") ConstructorShape(ref, listOf("value"), listOf(a), Type.option(a))
            else ConstructorShape(ref, emptyList(), emptyList(), Type.option(a))
        }
        val hinted = when (val h = hint?.prune()) {
            is Type.Con -> h.name
            is Type.Fn -> if (asValue) (h.result.prune() as? Type.Con)?.name else null
            else -> null
        }?.takeIf { it in sealeds }
        val case: CaseDecl = if (qualifierSealed != null) {
            val matches = caseDecls.getValue(qualifierSealed.name).filter { it.name == name }
            matches.singleOrNull()
                ?: throw CompileError(pos, if (matches.isEmpty()) "${qualifierSealed.simpleName} has no case '$name'" else "${qualifierSealed.simpleName} has several cases named '$name'")
        } else {
            val inHint = hinted?.let { h -> caseDecls.getValue(h).filter { it.name == name } }.orEmpty()
            inHint.singleOrNull() ?: run {
                // Unqualified, a variant is in scope inside its module; through a module, it's that module's.
                val candidates = variantsByName[name].orEmpty().filter { (s, _) ->
                    visible(s) && !abstractHere(s) && if (qualifierModule != null) s.path == qualifierModule.name.split('.') else inside(s.path)
                }
                if (candidates.isEmpty()) return if (qualifier == null) resultCase(name) else null
                val (origin, variant) = candidates.singleOrNull()
                    ?: throw CompileError(
                        pos,
                        "'$name' is a case of ${candidates.joinToString(" and ") { it.first.simpleName }}; " +
                            "write ${candidates.first().first.simpleName}.$name, or annotate the expected type",
                    )
                caseDecls.getValue(origin.name).first { it.variant === variant }
            }
        }
        // The type it builds: the expected sealed type if that has the case, else the one named.
        val home = hinted?.takeIf { h -> caseDecls.getValue(h).any { it.sameCase(case) } } ?: qualifierSealed?.name ?: case.origin.name
        val args = sealeds.getValue(home).typeParams.map { u.fresh() }
        val region = newRegion(sealeds.getValue(home))
        val fieldTypes = cases(home, args, region).first { it.first.sameCase(case) }.second
        return ConstructorShape(
            ConstructorRef.Variant(case.origin, case.variant, name),
            case.variant.fields.map { it.name },
            fieldTypes,
            Type.Con(home, args, region),
        )
    }

    /** `Ok(value)` or `Error(error)`, building a Result. */
    private fun resultCase(name: String): ConstructorShape? {
        if (name != "Ok" && name != "Error") return null
        val error = u.fresh()
        val value = u.fresh()
        val ref = ConstructorRef.Variant(null, null, name)
        return if (name == "Ok") ConstructorShape(ref, listOf("value"), listOf(value), Type.result(value, error))
        else ConstructorShape(ref, listOf("error"), listOf(error), Type.result(value, error))
    }

    /** `Alphabet.A`, `Ratios.Ratio` or `Shapes.Circle`: what qualifies a constructor, if [member] names one. */
    private fun qualifiedCase(member: Expr.Member, scope: Scope): String? {
        val qualifier = dotted(member.target, scope) ?: return null
        if (member.target is Expr.Name && lookupGlobal(qualifier, null) != null) return null
        return qualifier.takeIf { lookupSealed(it, null) != null || lookupModule(it, null) != null }
    }

    /**
     * Maps declared parameter names to argument indices: `order[i]` is the argument for parameter
     * i. Each of [args] is its name, if it's passed by one, and where it's written.
     */
    private fun argOrder(args: List<Pair<String?, Pos>>, params: List<String>, pos: Pos, what: String): List<Int> {
        if (args.none { it.first != null }) {
            if (args.size != params.size) throw CompileError(pos, "$what takes ${params.size} argument${if (params.size == 1) "" else "s"}, not ${args.size}")
            return params.indices.toList()
        }
        // As in Kotlin, the first arguments can be passed in order, and the rest by name.
        val byName = mutableMapOf<String, Int>()
        args.forEachIndexed { i, (name, at) ->
            if (name == null) {
                if (args.take(i).any { it.first != null }) throw CompileError(at, "an argument passed in order can't follow one passed by name")
                if (i >= params.size) throw CompileError(pos, "$what takes ${params.size} argument${if (params.size == 1) "" else "s"}, not ${args.size}")
                byName[params[i]] = i
            } else {
                if (name !in params) throw CompileError(at, "$what has no parameter '$name'")
                if (byName.put(name, i) != null) throw CompileError(at, "'$name' is passed twice")
            }
        }
        return params.map { byName[it] ?: throw CompileError(pos, "$what is missing argument '$it'") }
    }

    private fun checkArgs(args: List<Arg>, order: List<Int>, params: List<Type>, scope: Scope) {
        // Check in parameter order, so earlier arguments can inform the types of later lambdas.
        order.forEachIndexed { i, argIndex -> check(args[argIndex].value, params[i], scope) }
    }

    private fun inferCall(e: Expr.Call, expected: Type?, scope: Scope): Type {
        val callee = e.callee
        // A declared function, by name or through its module, is called with its parameters' names.
        val global = when (callee) {
            is Expr.Name -> if (scope.lookup(callee.name) == null) lookupGlobal(callee.name, callee.pos) else null
            is Expr.Member -> moduleMember(callee, scope)
            else -> null
        }
        if (global?.params != null) {
            val fn = globalType(global) as Type.Fn
            when (callee) {
                is Expr.Name -> callee.resolved = NameRef.Global(global)
                is Expr.Member -> callee.resolved = MemberRef.Global(global)
                else -> {}
            }
            callee.type = fn
            val order = argOrder(e.args.map { it.name to it.value.pos }, global.params.map { it.name }, e.pos, "'${global.simpleName}'")
            checkArgs(e.args, order, fn.params, scope)
            e.resolved = CallRef.Function(order)
            perform(fn.effects, e.pos)
            return fn.result
        }
        if (global == null) {
            val op = when (callee) {
                is Expr.Name -> if (scope.lookup(callee.name) == null) operation(callee.name, callee.pos) else null
                is Expr.Member -> qualifiedOperation(callee, scope)
                else -> null
            }
            if (op != null) return callOperation(e, op.first, op.second, scope)
            // A handled effect's spec, made into a handler from a function for each operation.
            if (callee is Expr.Name && scope.lookup(callee.name) == null) {
                find(callee.name, specs, null)?.takeIf { isEffect(it) }?.let { return makeHandler(e, it, scope) }
            }
        }
        if (callee is Expr.Name && global == null && scope.lookup(callee.name) == null) {
            constructor(callee.name, expected, callee.pos)?.let { return construct(e, it, callee.name, expected, scope) }
        }
        if (callee is Expr.Member && global == null) {
            qualifiedCase(callee, scope)?.let { qualifier ->
                constructor(callee.name, expected, callee.pos, qualifier = qualifier)?.let {
                    return construct(e, it, "$qualifier.${callee.name}", expected, scope)
                }
                throw CompileError(callee.pos, "$qualifier has no '${callee.name}'")
            }
        }
        if (callee is Expr.Member) inferMethodCall(e, callee, scope)?.let { return it }

        if (e.args.any { it.name != null }) throw CompileError(e.pos, "named arguments need a call to a declared function or type")
        val fn = when (val t = infer(callee, null, scope).prune()) {
            is Type.Fn -> t
            is Type.Var -> Type.Fn(e.args.map { u.fresh() }, u.fresh(), u.fresh()).also { u.unify(t, it) }
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
        perform(fn.effects, e.pos)
        return fn.result
    }

    private fun construct(e: Expr.Call, shape: ConstructorShape, name: String, expected: Type?, scope: Scope): Type {
        if (shape.fieldTypes.isEmpty()) throw CompileError(e.pos, "'$name' takes no arguments; write it without ()")
        val order = argOrder(e.args.map { it.name to it.value.pos }, shape.fieldNames, e.pos, "'$name'")
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
            if (abstractHere(record)) abstractError(record, member.pos)
            if (record.privateConstructor && !inside(record.path)) {
                throw CompileError(member.pos, "${record.simpleName}'s constructor is private to ${record.path.joinToString(".")}, so only it can copy one")
            }
            if (e.args.any { it.name == null }) throw CompileError(e.pos, "copy takes named arguments: copy(field = value)")
            val types = fieldTypes(record.typeParams, record.fields, receiver.args, receiver.region)
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
        extension(member, receiver)?.let { decl ->
            if (decl.property) throw CompileError(member.pos, "'${member.name}' is a property; write it without ()")
            val fn = globalType(decl) as Type.Fn
            if (fn.params.size - 1 != e.args.size) {
                throw CompileError(e.pos, "'${member.name}' takes ${fn.params.size - 1} argument${if (fn.params.size == 2) "" else "s"}, not ${e.args.size}")
            }
            assign(member.target, receiver, fn.params[0])
            checkArgs(e.args, e.args.indices.toList(), fn.params.drop(1), scope)
            e.resolved = CallRef.Extension(decl)
            perform(fn.effects, e.pos)
            return fn.result
        }
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
        val global = lookupGlobal(member.name, null)?.takeIf { scope.lookup(member.name) == null }
        if (global?.params != null && global.params.size == e.args.size + 1) {
            val fn = globalType(global) as Type.Fn
            assign(member.target, receiver, fn.params[0])
            checkArgs(e.args, e.args.indices.toList(), fn.params.drop(1), scope)
            e.resolved = CallRef.Extension(global)
            perform(fn.effects, e.pos)
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
        if (abstractHere(record)) abstractError(record, member.pos)
        val type = fieldTypes(record.typeParams, record.fields, receiver.args, receiver.region)[index]
        return MemberRef.Field(snake(member.name), receiver.name) to type
    }

    private fun inferMember(e: Expr.Member, expected: Type?, scope: Scope): Type {
        moduleMember(e, scope)?.let {
            e.resolved = MemberRef.Global(it)
            return globalType(it)
        }
        qualifiedCase(e, scope)?.let { qualifier ->
            val shape = constructor(e.name, expected, e.pos, asValue = true, qualifier = qualifier)
                ?: throw CompileError(e.pos, "$qualifier has no '${e.name}'")
            e.resolved = MemberRef.Case(shape.ref)
            return if (shape.fieldTypes.isEmpty()) shape.result else Type.Fn(shape.fieldTypes, shape.result, u.fresh())
        }
        val receiver = infer(e.target, null, scope).prune()
        field(e, receiver, scope)?.let { (ref, type) ->
            e.resolved = ref
            return type
        }
        extension(e, receiver)?.let { decl ->
            if (!decl.property) throw CompileError(e.pos, "'${e.name}' is a method; call it with ()")
            val fn = globalType(decl) as Type.Fn
            assign(e.target, receiver, fn.params[0])
            e.resolved = MemberRef.Extension(decl)
            perform(fn.effects, e.pos)
            return fn.result
        }
        Builtins.member(receiver, e.name, u)?.let {
            if (!it.isProperty) throw CompileError(e.pos, "'${e.name}' is a method; call it with ()")
            e.resolved = MemberRef.Builtin(it.translation)
            return it.result
        }
        if (receiver is Type.Var) throw CompileError(e.pos, "can't tell what '${e.name}' is: the receiver's type isn't known here; annotate it")
        throw CompileError(e.pos, "$receiver has no field '${e.name}'")
    }

    /**
     * The extension [member] calls on a [receiver]: of those with its name, the one whose receiver
     * type fits, or the most specific, as `List<Int>.sum` is over `List<A>.sum`.
     */
    private fun extension(member: Expr.Member, receiver: Type): Decl.Function? {
        val candidates = extensions[member.name].orEmpty().filter { visible(it) }
        // As in Kotlin, the receiver's type must be known to choose; the caller says so if it isn't.
        if (candidates.isEmpty() || receiver.prune() is Type.Var) return null
        val fitting = candidates.mapNotNull { d -> fit(receiverType(d), receiver)?.let { d to it } }
        if (fitting.isEmpty()) return null
        val best = fitting.maxOf { it.second }
        val chosen = fitting.filter { it.second == best }
        chosen.singleOrNull()?.let { return it.first }
        throw CompileError(
            member.pos,
            "can't tell which '${member.name}' this is: ${generic(receiver)} fits " +
                chosen.joinToString(" and ") { (d, _) -> "${generic(receiverType(d))}.${member.name}" } + "; annotate its type",
        )
    }

    /** [d]'s receiver type, with its type parameters as unknowns. */
    private fun receiverType(d: Decl.Function): Type {
        val saved = currentPath to typeParams
        currentPath = d.path
        typeParams = d.typeParams.associateWith { u.fresh() }
        try {
            return resolve(d.params!!.first().type!!)
        } finally {
            currentPath = saved.first
            typeParams = saved.second
        }
    }

    /** Whether a receiver of type [actual] can be a [wanted], and if so how specifically: the named types they share. */
    private fun fit(wanted: Type, actual: Type): Int? {
        val w = wanted.prune()
        val a = actual.prune()
        return when {
            w is Type.Var -> 0
            a is Type.Var -> 0
            w is Type.Con && a is Type.Con -> if (w.name != a.name || w.args.size != a.args.size) null
            else w.args.zip(a.args).fold(1 as Int?) { acc, (x, y) -> acc?.let { n -> fit(x, y)?.let { n + it } } }
            w is Type.Fn && a is Type.Fn -> if (w.params.size != a.params.size) null
            else (w.params + w.result).zip(a.params + a.result).fold(0 as Int?) { acc, (x, y) -> acc?.let { n -> fit(x, y)?.let { n + it } } }
            else -> null
        }
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
                // Different sealed types compare as the smallest one that has both: Alphabet.E == vowel.
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
        // What its body does is what calling it does, not what the code around it does.
        val row = expectedFn?.effects ?: u.fresh()
        if (e.ignoresArguments) {
            val params = expectedFn?.params ?: emptyList()
            e.paramNames = params.map { "_" }
            val result = expectedFn?.result ?: u.fresh()
            doing(row) { check(e.body, result, scope) }
            return Type.Fn(params, result, row)
        }
        val hint = expectedFn?.takeIf { it.params.size == e.params.size }
        checkDistinct(e.params.map { it.name to it.pos })
        val inner = Scope(scope)
        val params = e.params.mapIndexed { i, p ->
            val declared = p.type?.let { withArrowEffects(row) { resolve(it) } }
            val type = declared ?: hint?.params?.get(i) ?: u.fresh()
            if (declared != null && hint != null) expect(hint.params[i], declared, p.pos)
            inner.vars[p.name] = Local(type, snake(p.name))
            type
        }
        e.paramNames = e.params.map { snake(it.name) }
        val result = hint?.result ?: u.fresh()
        doing(row) { check(e.body, result, inner) }
        return Type.Fn(params, result, row)
    }

    private fun inferPipe(e: Expr.Pipe, scope: Scope): Type {
        val value = infer(e.value, null, scope)
        val f = e.function
        // `x |> f(a)` passes x as f's last argument when f takes one more than it's given.
        if (f is Expr.Call && f.args.none { it.name != null } && f.callee !is Expr.Member) {
            val callee = f.callee
            val isConstructor = callee is Expr.Name && scope.lookup(callee.name) == null &&
                lookupGlobal(callee.name, null) == null && constructor(callee.name, null, callee.pos) != null
            if (!isConstructor) {
                val fn = infer(callee, null, scope).prune()
                if (fn is Type.Fn && fn.params.size == f.args.size + 1) {
                    checkArgs(f.args, f.args.indices.toList(), fn.params.dropLast(1), scope)
                    assign(e.value, value, fn.params.last())
                    f.resolved = CallRef.Function(f.args.indices.toList())
                    f.type = Type.Fn(listOf(fn.params.last()), fn.result, fn.effects)
                    e.partial = true
                    perform(fn.effects, e.pos)
                    return fn.result
                }
            }
        }
        val result = u.fresh()
        val row = u.fresh()
        check(f, Type.Fn(listOf(value), result, row), scope)
        perform(row, e.pos)
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
            pendingWhens += e to subjectType
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
                    if (abstractHere(record)) abstractError(record, p.pos)
                    // A record's fields, in declaration order, like a Kotlin data class.
                    if (p.elements.size != record.fields.size) {
                        throw CompileError(p.pos, "${record.name} has ${record.fields.size} fields, not ${p.elements.size}")
                    }
                    p.record = record
                    p.elements.zip(fieldTypes(record.typeParams, record.fields, t.args, t.region)).forEach { (el, ft) -> checkPattern(el, ft, scope, null, owner) }
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
        // `is Vowel`, naming a sealed type rather than one of the subject's cases, narrows the subject.
        if (p.isCheck) {
            val target = lookupSealed(listOfNotNull(p.qualifier, p.name).joinToString("."), null)
            val isCase = p.qualifier == null && caseDecls[subjectSealed?.name]?.any { it.name == p.name } == true
            if (target != null && !isCase) {
                narrow(p, target, t, scope, subject)
                return
            }
        }
        val shape = if (subjectSealed != null) {
            // Match against the subject's own cases, so Alphabet.A matches a Vowel.
            // Through a sealed type, its cases; through a module, those of the sealed types in it.
            val qualifierCases = p.qualifier?.let { q ->
                lookupSealed(q, p.pos)?.let { if (abstractHere(it)) abstractError(it, p.pos); caseDecls.getValue(it.name) }
                    ?: lookupModule(q, p.pos)?.let { m -> caseDecls.filterKeys { it.substringBeforeLast('.') == m.name }.values.flatten() }
                    ?: throw CompileError(p.pos, "unknown type or module '$q'")
            }
            val matches = cases(subjectSealed.name, subjectSealed.args, subjectSealed.region).filter { (case, _) ->
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
            constructor(p.name, type, p.pos, qualifier = p.qualifier, building = false)?.also { expect(it.result, type, p.pos) }
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
    private fun narrow(p: Pattern.Constructor, target: Decl.Sealed, subjectType: Type, scope: Scope, subject: Expr?) {
        val subjectSealed = (subjectType as? Type.Con)?.takeIf { it.name in sealeds }
            ?: throw CompileError(p.pos, "'is ${p.name}' needs the subject to be a type with cases, not $subjectType")
        val narrowed = Type.Con(target.name, target.typeParams.map { u.fresh() }, newRegion(target))
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

    /** `Options { ... }`: checks the block with `by` going through the module's bind, and the result through its pure. */
    private fun inferMonadic(e: Expr.Monadic, scope: Scope): Type {
        val name = dotted(e.module, scope)
        val module = name?.let { lookupModule(it, null) }
            ?: throw CompileError(e.module.pos, "${name ?: "this"} isn't a module, so $name { ... } has nothing to bind with")
        // A module following a handled effect's spec handles the block instead.
        moduleSpecs[module.name]?.takeIf { isEffect(it) }?.let { spec ->
            e.handles = spec
            e.handler = module
            return handling(spec, emptyList(), (moduleHandler(module, spec) as Type.Con).args.last(), e.block, null, scope, e.pos)
        }
        fun member(what: String) = globals[module.name + "." + what]?.takeIf { visible(it) }
        val bind = member("bind")
        val pure = member("pure")
        val map = member("map")
        val product = member("product")
        when {
            bind != null && pure != null -> {
                e.bind = bind
                e.pure = pure
            }
            map != null && product != null -> {
                e.map = map
                e.product = product
                e.pure = pure
            }
            else -> throw CompileError(
                e.module.pos,
                "$name has neither bind and pure nor map and product, so it can't run a block; give it pure and bind, as Monad has",
            )
        }
        return inferBlock(e.block, null, scope, e)
    }

    /** A fresh instance of [monad]'s [what], if it takes [arity] values. */
    private fun instance(monad: Expr.Monadic, decl: Decl.Function, arity: Int, what: String): Type.Fn {
        val fn = globalType(decl).prune() as? Type.Fn
        if (fn == null || fn.params.size != arity) throw CompileError(monad.module.pos, "${decl.name} should take $what")
        perform(fn.effects, monad.pos)
        return fn
    }

    /** A fresh instance of [monad]'s map, as (value, function), if it has map's shape. */
    private fun mapType(monad: Expr.Monadic): Pair<Type.Fn, Type.Fn> {
        val fn = instance(monad, monad.map!!, 2, "a value and a function to apply to what it holds")
        val f = fn.params[1].prune() as? Type.Fn
        if (f == null || f.params.size != 1) throw CompileError(monad.module.pos, "${monad.map!!.name} should take a value and a function to apply to what it holds")
        return fn to f
    }

    /** Checks that `by` line [value] is a [monad] value, giving its type and the type of what it holds. */
    private fun step(monad: Expr.Monadic, value: Expr, scope: Scope): Pair<Type, Type> {
        val (map, f) = mapType(monad)
        val wanted = generic(map.params[0])
        val actual = infer(value, map.params[0], scope)
        if (!subtype(actual, map.params[0])) {
            throw CompileError(value.pos, "${dotted(monad.module, null)} maps $wanted, but this is ${actual.zonk()}")
        }
        return map.params[0] to f.params[0]
    }

    /**
     * Without a bind, the `by` lines in [steps] are independent: they're paired up with product, as
     * `((a, b), c)`, and the rest of the block is a function of the pairs, applied with map.
     */
    private fun combine(monad: Expr.Monadic, steps: List<Pair<Type, Type>>, pos: Pos): Pair<Type, Type> {
        var values = steps[0].first
        var held = steps[0].second
        for ((value, element) in steps.drop(1)) {
            val product = instance(monad, monad.product!!, 2, "two values, to pair what they hold")
            expect(values, product.params[0], pos)
            expect(value, product.params[1], pos)
            values = product.result
            held = Type.tuple(listOf(held, element))
        }
        val (map, f) = mapType(monad)
        expect(values, map.params[0], pos)
        if (!u.unify(f.params[0], held)) {
            throw CompileError(monad.module.pos, "${monad.product!!.name} should pair what its two values hold, as (A, B)")
        }
        return f.result to map.result
    }

    /** A fresh instance of [monad]'s bind, as (value, continuation, result), if it has bind's shape. */
    private fun bindType(monad: Expr.Monadic): Triple<Type, Type.Fn, Type> {
        val bind = monad.bind!!
        val fn = globalType(bind).prune() as? Type.Fn
        val next = fn?.params?.getOrNull(1)?.prune() as? Type.Fn
        if (fn == null || fn.params.size != 2 || next == null || next.params.size != 1) {
            throw CompileError(monad.module.pos, "${bind.name} should take a value and a function to go on with, as Monad's bind does")
        }
        perform(fn.effects, monad.pos)
        return Triple(fn.params[0], next, fn.result)
    }

    /** [type] with its unknowns written A, B, ...: `Option<A>`, as a spec would write it. */
    private fun generic(type: Type): String {
        val names = mutableMapOf<Type.Var, String>()
        fun walk(t: Type) {
            when (val p = t.prune()) {
                is Type.Var -> names.getOrPut(p) { ('A' + names.size).toString() }
                is Type.Con -> p.args.forEach(::walk)
                is Type.Fn -> {
                    p.params.forEach(::walk)
                    walk(p.result)
                }
            }
        }
        walk(type)
        return Type.render(type, names)
    }

    /** Binds an irrefutable [pattern] of [type] into [inner], as `(a, b) = ...` and `(a, b) by ...` do. */
    private fun bindPattern(statement: Statement, pattern: Pattern, type: Type, inner: Scope) {
        val names = patternNames(pattern)
        checkDistinct(names.map { it to statement.pos })
        names.firstOrNull { inner.vars.containsKey(it) }?.let {
            throw CompileError(statement.pos, "'$it' is already defined in this block")
        }
        // Bind into a scratch scope first, so the value can't see its own names.
        if (!irrefutable(pattern)) {
            val what = if (statement is Statement.Bind) "'by'" else "a destructuring binding"
            throw CompileError(statement.pos, "$what takes only names and '_'; use 'when' to match other patterns")
        }
        val bound = Scope(inner)
        checkPattern(pattern, type, bound, null, null)
        inner.vars.putAll(bound.vars)
    }

    /** A block; in [monad]'s, `by` lines bind through it and the result is wrapped by its pure. */
    private fun inferBlock(e: Expr.Block, expected: Type?, scope: Scope, monad: Expr.Monadic?): Type {
        val inner = Scope(scope)
        // In a monad's block, the type of what's left of it, from here on.
        val whole: Type = u.fresh()
        var rest = whole
        // Without a bind: each `by` line's types, and its names once lines after them can see them.
        val steps = mutableListOf<Pair<Type, Type>>()
        val pending = mutableListOf<Pair<Statement.Bind, Type>>()
        var after: Statement? = null
        fun bindSteps() {
            pending.forEach { (statement, type) -> bindPattern(statement, statement.pattern, type, inner) }
            pending.clear()
        }
        val independent = monad != null && monad.bind == null
        for (statement in e.statements) {
            if (independent && statement !is Statement.Bind && steps.isNotEmpty()) {
                bindSteps()
                after = after ?: statement
            }
            when (statement) {
                is Statement.Effect -> infer(statement.expr, null, inner)
                is Statement.Destructure -> bindPattern(statement, statement.pattern, infer(statement.value, null, inner), inner)
                is Statement.Bind -> {
                    if (monad == null) throw CompileError(statement.pos, "'by' needs a block to bind in, such as Options { ... }, directly around it")
                    val name = dotted(monad.module, null)
                    if (independent) {
                        after?.let {
                            throw CompileError(
                                it.pos,
                                "$name has no bind, so its 'by' lines go together: move this line above the first 'by' or below the last",
                            )
                        }
                        val refs = mutableSetOf<String>()
                        collectRefs(statement.value, emptySet(), refs)
                        pending.flatMap { patternNames(it.first.pattern) }.firstOrNull { it in refs }?.let {
                            throw CompileError(statement.value.pos, "$name has no bind, so a step can't use $it from an earlier step")
                        }
                        val step = step(monad, statement.value, inner)
                        steps += step
                        pending += statement to step.second
                        continue
                    }
                    val (value, next, result) = bindType(monad)
                    expect(result, rest, statement.pos)
                    val wanted = generic(value)
                    val actual = infer(statement.value, value, inner)
                    if (!subtype(actual, value)) {
                        throw CompileError(statement.value.pos, "${dotted(monad.module, null)} binds $wanted, but this is ${actual.zonk()}")
                    }
                    bindPattern(statement, statement.pattern, next.params[0], inner)
                    rest = next.result
                }
                is Statement.Binding -> {
                    // `x = value` for a var in scope changes it, as in Kotlin.
                    val existing = inner.lookup(statement.name)
                    if (!statement.isVar && statement.params == null && statement.returnType == null && existing != null && existing.isVar) {
                        statement.assigns = true
                        statement.ocamlName = existing.ocamlName
                        check(statement.value, existing.type, inner)
                        perform(mutating(existing.region!!), statement.pos)
                        continue
                    }
                    if (inner.vars.containsKey(statement.name)) {
                        throw CompileError(statement.pos, "'${statement.name}' is already defined in this block; to change it, declare it with var ${statement.name} = ...")
                    }
                    statement.ocamlName = snake(statement.name)
                    val params = statement.params
                    if (params == null) {
                        val declared = statement.returnType?.let { resolve(it) }
                        val type = infer(statement.value, declared, inner)
                        declared?.let { assign(statement.value, type, it) }
                        inner.vars[statement.name] = Local(type, statement.ocamlName, region = if (statement.isVar) u.fresh() else null)
                    } else {
                        checkDistinct(params.map { it.name to it.pos })
                        val row = u.fresh()
                        val fn = Type.Fn(
                            withArrowEffects(row) { params.map { p -> p.type?.let { resolve(it) } ?: u.fresh() } },
                            statement.returnType?.let { resolve(it) } ?: u.fresh(),
                            row,
                        )
                        inner.vars[statement.name] = Local(fn, statement.ocamlName)
                        val body = Scope(inner)
                        params.zip(fn.params).forEach { (p, t) -> body.vars[p.name] = Local(t, snake(p.name)) }
                        statement.paramNames = params.map { snake(it.name) }
                        val refs = mutableSetOf<String>()
                        collectRefs(statement.value, params.map { it.name }.toSet(), refs)
                        doing(row) { check(statement.value, fn.result, body) }
                        // Checked, a built-in method of the same name no longer counts as a call to it.
                        collectRefs(statement.value, params.map { it.name }.toSet(), refs.apply { clear() })
                        statement.recursive = statement.name in refs
                    }
                }
            }
        }
        e.endsWith?.let { if (!it.assigns) throw CompileError(it.pos, "a block must end with an expression, not a binding") }
        if (monad == null) return infer(e.result, expected, inner)
        if (independent && steps.isNotEmpty()) {
            bindSteps()
            val (result, block) = combine(monad, steps, e.pos)
            check(e.result, result, inner)
            return block
        }
        if (monad.pure == null) throw CompileError(monad.module.pos, "${dotted(monad.module, null)} has no pure, so its block needs a 'by' line")
        val pure = globalType(monad.pure!!).prune() as? Type.Fn
        if (pure == null || pure.params.size != 1) {
            throw CompileError(monad.module.pos, "${monad.pure!!.name} should take one value, as Monad's pure does")
        }
        expect(pure.result, rest, e.result.pos)
        check(e.result, pure.params[0], inner)
        return whole
    }
}
