package uk.m4xy.fckt.types

import uk.m4xy.fckt.ocaml.snake
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
)

/** A case of a sealed type: [variant], declared by [origin], with [fields] typed in the sealed type's own terms. */
class CaseDecl(val origin: Decl.Sealed, val variant: VariantDef, val fields: List<FieldDef>) {
    val name get() = variant.name
    fun sameCase(other: CaseDecl) = origin === other.origin && variant === other.variant
}

class FunctionGroup(val decls: List<Decl.Function>, var recursive: Boolean)

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

class Checker(file: SourceFile) {
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
    private val moduleSpecs = mutableMapOf<String, Decl.Spec>()
    private val specSchemes = mutableMapOf<Decl.Spec, Map<String, Scheme>>()

    /** A spec'd module's functions as seen outside it: their spec's types, with its types abstract. */
    private val outsideSchemes = mutableMapOf<Decl.Function, Scheme>()
    private val variantsByName = mutableMapOf<String, MutableList<Pair<Decl.Sealed, VariantDef>>>()
    private val globals = linkedMapOf<String, Decl.Function>()

    /** Extensions by the name they're called with: `map` for `List$map` and `Option$map`. */
    private val extensions = mutableMapOf<String, MutableList<Decl.Function>>()
    private val caseDecls = mutableMapOf<String, List<CaseDecl>>()
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

    /** `IntSet.M` names `IntOrder` inside `module IntSet = SortedSet<IntOrder>`. */
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

    /** `module IntSet = SortedSet<IntOrder>`: a copy of SortedSet's declarations, with its `M` naming IntOrder. */
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
                val inside = specView(wanted.type, names.associate { (tv, n) -> tv to Type.Con("#$n") as Type }) { t, targs ->
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
            root = root,
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
            return "'${d.simpleName}' is in the functor ${it.simpleName}, which makes modules: use it through one, made with module Name = ${it.simpleName}<...>"
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
            if (typeDecl(decl.name) != null) throw CompileError(decl.pos, "type '${decl.simpleName}' is declared twice")
            when (decl) {
                is Decl.Alias -> aliases[decl.name] = decl
                is Decl.Record -> records[decl.name] = decl
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
        // Which spec each module follows; that decides what's visible outside it, so it comes first.
        for (m in modules.values) {
            val ref = m.spec ?: continue
            currentPath = m.path
            val spec = find(ref.name, specs, ref.pos) ?: throw CompileError(ref.pos, "unknown spec '${ref.name}'")
            moduleSpecs[m.name] = spec
            fixTypes(m.name, m.path, spec, ref)
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
        is TypeRef.Function -> Type.Fn(ref.params.map { resolve(it) }, resolve(ref.result))
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
                ?: throw CompileError(ref.pos, if (ref.name == "_") "'_' only marks a spec type's parameters, as in Monad<Result<_, String>>" else "unknown type '${ref.name}'")
        }
    }

    /** The type declaration [d] applied to [args], as seen from the current module. */
    private fun typeOf(d: Decl, args: List<Type>): Type = when {
        abstractHere(d) -> outsideType(d.path.joinToString("."), d.simpleName, args)
        d is Decl.Alias -> expand(d, args)
        else -> Type.Con(d.name, args)
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

    // ---- Specs ----

    /** Resolves each spec's values' types, with the spec's own types as `@T`. */
    private fun checkSpecs() {
        for (spec in specs.values) {
            currentPath = spec.path
            checkDistinct(spec.types.map { it.name to it.pos })
            checkDistinct(spec.values.map { it.name to it.pos })
            spec.types.firstOrNull { it.name in BUILTIN_TYPES }?.let { throw CompileError(it.pos, "'${it.name}' is a built-in type") }
            specTypes = spec.types.associate { it.name to it.typeParams.size }
            specSchemes[spec] = spec.values.associate { v ->
                val vars = v.typeParams.associateWith { u.fresh() }
                typeParams = vars
                val result = resolve(v.type)
                val type = v.params?.let { params ->
                    checkDistinct(params.map { it.name to it.pos })
                    Type.Fn(params.map { resolve(it.type!!) }, result)
                } ?: result
                v.name to Scheme(vars.values.toList(), type)
            }
            typeParams = emptyMap()
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
                if (d.isOpaque && "$name.${t.name}" in fixedTypes && name !in parameterModules) {
                    throw CompileError(m.spec!!.pos, "${t.name} is opaque, so ${spec.simpleName}'s type arguments can't say what it is")
                }
                val n = typeParamsOf(d).size
                if (n != t.typeParams.size) {
                    throw CompileError(d.pos, "${spec.simpleName} says ${t.name} takes ${t.typeParams.size} type parameter${if (t.typeParams.size == 1) "" else "s"}, not $n")
                }
                // Ordered<Int> says T is Int: inside the module, it must be.
                if ("$name.${t.name}" in fixedTypes && name !in parameterModules) {
                    val rigid = t.typeParams.map { Type.Con("#$it") }
                    currentPath = m.name.split('.')
                    val inside = typeOf(d, rigid)
                    currentPath = m.path
                    val said = outsideType(name, t.name, rigid)
                    if (!u.unify(inside, said)) {
                        throw CompileError(d.pos, "${m.simpleName} follows ${spec.simpleName} with ${t.name} as $said, but its ${t.name} is $inside")
                    }
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
        val rigid = names.associate { (v, n) -> v to Type.Con("#$n") as Type }
        val inside = specView(wanted.type, rigid) { name, args -> typeOf(typeDecl("$module.$name")!!, args) }
        val actual = u.instantiate(schemes.getValue(decl))
        val shown = actual.zonk()
        if (!u.unify(actual, inside)) {
            throw CompileError(decl.pos, "'${decl.simpleName}' has type $shown, but ${spec.simpleName} needs ${Type.render(wanted.type, names.toMap().toMutableMap())}, which here is ${inside.zonk()}")
        }
        outsideSchemes[decl] = Scheme(wanted.vars, specView(wanted.type, emptyMap()) { name, args -> outsideType(module, name, args) })
    }

    /** A spec's [type] with its type variables replaced from [vars], and each of its own types by [own]. */
    private fun specView(type: Type, vars: Map<Type.Var, Type>, own: (String, List<Type>) -> Type): Type = when (val t = type.prune()) {
        is Type.Var -> vars[t] ?: t
        is Type.Fn -> Type.Fn(t.params.map { specView(it, vars, own) }, specView(t.result, vars, own))
        is Type.Con -> t.args.map { specView(it, vars, own) }.let { args ->
            if (t.name.startsWith(Type.SPEC_TYPE)) own(t.name.removePrefix(Type.SPEC_TYPE), args) else Type.Con(t.name, args)
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
        for (decl in group.decls) {
            val vars = decl.typeParams.associateWith { u.fresh() }
            declaredParams[decl] = vars
            typeParams = vars
            val result = decl.returnType?.let { resolve(it) } ?: u.fresh()
            val inside = decl.params?.let { params ->
                checkDistinct(params.map { it.name to it.pos })
                Type.Fn(params.map { p -> p.type?.let { resolve(it) } ?: u.fresh() }, result)
            } ?: result
            provisional[decl] = inside
            // Inside, an opaque type is what it stands for; callers outside see the type as written.
            if (decl !in parameterMembers && opaqueAround(decl.path)) {
                outsideView = true
                try {
                    val shownResult = decl.returnType?.let { resolve(it) } ?: result
                    outside[decl] = if (inside is Type.Fn) {
                        Type.Fn(decl.params!!.zip(inside.params).map { (p, t) -> p.type?.let { resolve(it) } ?: t }, shownResult)
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
            // An external's body is OCaml, trusted to have its declared type.
            if (decl.external != null) continue
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
            outside[decl]?.zonk()?.let { shown -> outsideSchemes[decl] = if (decl.params == null) Scheme.mono(shown) else Scheme(shown.freeVars().toList(), shown) }
        }
        for (decl in group.decls) conform(decl)
    }

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

    /** Every case of sealed type [x] is a case of [y], with the same field types. */
    private fun caseSubset(x: Type.Con, y: Type.Con): Boolean {
        val ys = cases(y.name, y.args)
        return cases(x.name, x.args).all { (case, types) ->
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
            e.resolved = NameRef.Local(it.ocamlName)
            return it.type
        }
        lookupGlobal(e.name, e.pos)?.let {
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
            if (building && record.privateConstructor && !inside(record.path)) {
                throw CompileError(pos, "${record.simpleName}'s constructor is private to ${record.path.joinToString(".")}")
            }
            val args = record.typeParams.map { u.fresh() }
            return ConstructorShape(ConstructorRef.Record(record), record.fields.map { it.name }, fieldTypes(record.typeParams, record.fields, args), Type.Con(record.name, args))
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
        val fieldTypes = cases(home, args).first { it.first.sameCase(case) }.second
        return ConstructorShape(
            ConstructorRef.Variant(case.origin, case.variant, name),
            case.variant.fields.map { it.name },
            fieldTypes,
            Type.Con(home, args),
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
            val order = argOrder(e.args, global.params.map { it.name }, e.pos, "'${global.simpleName}'")
            checkArgs(e.args, order, fn.params, scope)
            e.resolved = CallRef.Function(order)
            return fn.result
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
            if (abstractHere(record)) abstractError(record, member.pos)
            if (record.privateConstructor && !inside(record.path)) {
                throw CompileError(member.pos, "${record.simpleName}'s constructor is private to ${record.path.joinToString(".")}, so only it can copy one")
            }
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
        extension(member, receiver)?.let { decl ->
            if (decl.property) throw CompileError(member.pos, "'${member.name}' is a property; write it without ()")
            val fn = globalType(decl) as Type.Fn
            if (fn.params.size - 1 != e.args.size) {
                throw CompileError(e.pos, "'${member.name}' takes ${fn.params.size - 1} argument${if (fn.params.size == 2) "" else "s"}, not ${e.args.size}")
            }
            assign(member.target, receiver, fn.params[0])
            checkArgs(e.args, e.args.indices.toList(), fn.params.drop(1), scope)
            e.resolved = CallRef.Extension(decl)
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
        val type = fieldTypes(record.typeParams, record.fields, receiver.args)[index]
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
            return if (shape.fieldTypes.isEmpty()) shape.result else Type.Fn(shape.fieldTypes, shape.result)
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
                lookupGlobal(callee.name, null) == null && constructor(callee.name, null, callee.pos) != null
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
                    if (abstractHere(record)) abstractError(record, p.pos)
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

    /** `Options { ... }`: checks the block with `by` going through the module's bind, and the result through its pure. */
    private fun inferMonadic(e: Expr.Monadic, scope: Scope): Type {
        val name = dotted(e.module, scope)
        val module = name?.let { lookupModule(it, null) }
            ?: throw CompileError(e.module.pos, "${name ?: "this"} isn't a module, so $name { ... } has nothing to bind with")
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
                        check(statement.value, fn.result, body)
                        // Checked, a built-in method of the same name no longer counts as a call to it.
                        collectRefs(statement.value, params.map { it.name }.toSet(), refs.apply { clear() })
                        statement.recursive = statement.name in refs
                    }
                }
            }
        }
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
