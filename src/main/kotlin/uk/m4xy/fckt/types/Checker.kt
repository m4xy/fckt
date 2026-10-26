package uk.m4xy.fckt.types

import uk.m4xy.fckt.ocaml.snake
import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.Application
import uk.m4xy.fckt.syntax.Arg
import uk.m4xy.fckt.syntax.BinaryRef
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.CompileErrors
import uk.m4xy.fckt.syntax.Copier
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.FieldDef
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.ModuleArg
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
    /** The effects each annotation type or effect passes on to what it's used on, by its full name. */
    val annotationEffects: Map<String, Set<String>> = emptyMap(),
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
/** A local, which [hides] another of its name if it's a block's `it` inside another's. */
private class Local(val type: Type, val ocamlName: String, val cast: Cast? = null, val region: Type? = null, val hides: Boolean = false) {
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
    private val projectOf = file.projectOf
    private val importsFor = file.importsFor

    private val u = Unifier()

    /** Functors called in expressions, each by the module the call makes. */
    private val lifted = mutableMapOf<Expr.Call, String>()

    /** The declarations with their full names: `make` in `module Ratios` is `Ratios.make`. */
    private var root: List<Decl> = withDefaults(file.decls).map { qualify(it, "") }
    private val modules = linkedMapOf<String, Decl.Module>()
    private val decls: MutableList<Decl> = flatten(root).toMutableList()

    /** The module being checked, which decides what names mean and what's visible. */
    private var currentPath: List<String> = emptyList()

    /** The file being checked, null for the program's own, whose imports name things too. */
    private var currentFile: String? = null

    /** Each file's imports, as written. */
    private val fileImports = file.imports

    /** What each name a file imports means: the full name of what it names, by file. */
    private val imported = mutableMapOf<String?, Map<String, String>>()

    /** Names a file's imports bring in from more than one place, which it must import itself to use. */
    private val ambiguousImports = mutableMapOf<String?, Map<String, List<String>>>()
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
        // Before functors are applied, whose modules a file may name through its imports.
        collectImports()
        for (f in modules.values.filter { it.params.isNotEmpty() }) addParameters(f)
        for (app in modules.values.filter { it.application != null }) apply(app)
        liftApplications()
        currentPath = emptyList()
        currentFile = null
    }

    /**
     * Inside functor [f], each parameter `M : Spec` is a module with just what the spec lists, its
     * types abstract: the body must work for any module that follows the spec.
     */
    private fun addParameters(f: Decl.Module) {
        enclosingFunctor(f.path)?.let { throw CompileError(f.pos, "a functor can't be inside another functor (${it.simpleName}) yet") }
        currentPath = f.path
        currentFile = f.pos.file
        checkDistinct(f.params.map { it.name to it.pos })
        functorParams[f.name] = f.params.map { p ->
            val spec = find(p.spec.name, specs, p.spec.pos) ?: throw CompileError(p.spec.pos, "unknown spec '${p.spec.name}'${importHint(p.spec.name)}")
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
        currentFile = app.pos.file
        val functor = find(ref.functor.name, modules, ref.functor.pos) ?: throw CompileError(ref.functor.pos, "unknown module '${ref.functor.name}'")
        if (functor.params.isEmpty()) throw CompileError(ref.functor.pos, "${functor.simpleName} isn't a functor: it takes no modules")
        val positional = ref.args.takeWhile { it.name == null }
        if (positional.size > functor.params.size) {
            throw CompileError(ref.functor.pos, "${functor.simpleName} takes ${functor.params.size} module${if (functor.params.size == 1) "" else "s"}, not ${positional.size}")
        }
        val named = ref.args.drop(positional.size)
        for (arg in named) {
            val at = functor.params.indexOfFirst { it.name == arg.name }
            if (at < 0) throw CompileError(arg.module.pos, "${functor.simpleName} has no module ${arg.name}: it takes ${functor.params.joinToString { it.name }}")
            if (at < positional.size || named.count { it.name == arg.name } > 1) throw CompileError(arg.module.pos, "${functor.simpleName}'s ${arg.name} is given twice")
        }
        fun module(arg: TypeRef.Named): Decl.Module {
            val m = find(arg.name, modules, arg.pos) ?: throw CompileError(arg.pos, "unknown module '${arg.name}'")
            if (m.params.isNotEmpty()) throw CompileError(arg.pos, "${m.simpleName} is a functor; give it modules first")
            if (m.typeParams.isNotEmpty()) throw CompileError(arg.pos, "${m.simpleName} takes type parameters, ${m.typeParams.joinToString()}, so it can't be given to a functor yet")
            return m
        }
        val args = functor.params.mapIndexed { i, p ->
            val given = positional.getOrNull(i)?.module ?: named.find { it.name == p.name }?.module
            when {
                given != null -> module(given)
                // A default is named where the functor is declared, so it's the functor's own.
                p.default != null -> {
                    currentPath = functor.path
                    currentFile = functor.pos.file
                    val m = module(p.default)
                    currentPath = app.path
                    currentFile = app.pos.file
                    if (m.name in parameterModules) throw CompileError(p.default.pos, "${functor.simpleName}'s ${p.name} can't default to another of its modules")
                    m
                }
                else -> throw CompileError(ref.functor.pos, "${functor.simpleName} needs a module for ${p.name}, which follows ${p.spec.name}")
            }
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

    /** Whether [spec] builds on [other], or on a spec that does: `spec Monad : Applicative`. */
    private fun buildsOn(spec: Decl.Spec, other: Decl.Spec, seen: Set<Decl.Spec> = emptySet()): Boolean {
        if (spec in seen) return false
        val saved = currentPath to currentFile
        currentPath = spec.path
        currentFile = spec.pos.file
        val parents = try {
            spec.parents.mapNotNull { find(it.name, specs, it.pos) }
        } finally {
            currentPath = saved.first
            currentFile = saved.second
        }
        return parents.any { it === other || buildsOn(it, other, seen + spec) }
    }

    /** Every module given to a functor has what its parameter's spec lists. */
    private fun checkArgumentsDefine() {
        for ((app, made) in applications) {
            val (functor, args) = made
            currentPath = modules.getValue(app).path
            currentFile = modules.getValue(app).pos.file
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
                // A module whose spec builds on the one wanted has all it lists.
                if (argSpec != null && !buildsOn(argSpec, spec)) fail("it follows ${argSpec.simpleName}, not ${spec.simpleName}")
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
        currentFile = null
    }

    /**
     * The values of modules given to functors have the types their parameters' specs give them, once
     * they're checked. Modules that follow the spec were checked already.
     */
    private fun checkArgumentTypes(app: String) {
        val (functor, args) = applications.getValue(app)
        val saved = currentPath
        val savedFile = currentFile
        currentPath = modules.getValue(app).path
        currentFile = modules.getValue(app).pos.file
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
        currentFile = savedFile
    }

    fun check(): Program {
        collectTypes()
        checkSpecs()
        collectGlobals()
        checkModuleSpecs()
        checkArgumentsDefine()
        val groups = dependencyGroups()
        // Each group is checked whatever the others' errors, so a program's errors come together.
        val errors = mutableListOf<CompileError>()
        fun recover(block: () -> Unit) = try {
            block()
        } catch (e: CompileError) {
            errors += e
        }
        for (group in groups) {
            try {
                checkGroup(group)
            } catch (e: CompileError) {
                errors += e
                failed(group)
            }
        }
        for (app in applications.keys) recover { checkArgumentTypes(app) }
        recover { checkExposure() }
        recover { checkAnnotations() }
        CompileErrors.throwIfAny(errors)

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
            annotationEffects = (annotationTypes.values + specs.values.filter { isEffect(it) }).associate { d ->
                d.name to (runCatching { effectsOf(d.annotations, d.path, d.pos) }.getOrDefault(emptySet()) + listOfNotNull(d.name.takeIf { isEffect(d) }))
            },
            functionTypes = schemes.mapValues { it.value.type.zonk() },
            outsideTypes = outsideSchemes.filterKeys { it !in parameterMembers }.mapValues { it.value.type.zonk() },
            // Worked out again now each method call is to the one extension it resolved to.
            uses = uses.keys.associateWith { refsOf(it) },
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
            currentFile = d.pos.file
            withTypeParams(withParams) {
                fields.flatMap { privateTypes(resolve(it.type)) }.firstOrNull { exposes(d, it) }?.let { complain(d, it) }
            }
        }
        for (d in records.values + sealeds.values + aliases.values) {
            if (d.isOpaque && d.path.isEmpty()) throw CompileError(d.pos, "an opaque type is opaque outside its module, so it needs to be in one")
        }
        for (a in aliases.values) {
            currentPath = a.path
            currentFile = a.pos.file
            withTypeParams(a.typeParams + moduleTypeParams(a.path)) { privateTypes(resolve(a.target)).firstOrNull { exposes(a, it) }?.let { complain(a, it) } }
        }
        currentPath = emptyList()
        currentFile = null
    }

    // ---- Default values ----

    /**
     * [decls] with a function for each default value of a parameter or field, beside what it's a
     * default of: `greet$default$greeting()` for `greet(name, greeting = "hello")`. A call that
     * leaves the argument out calls it, so the default means what it does where it's written.
     */
    private fun withDefaults(decls: List<Decl>): List<Decl> = decls.flatMap { d ->
        fun defaultOf(owner: String, name: String, pos: Pos, type: TypeRef?, value: Expr, typeParams: List<String>) =
            Decl.Function(pos, defaultName(owner, name), typeParams, emptyList(), type, value, isPrivate = d.isPrivate)
        when (d) {
            is Decl.Module -> listOf(d.copy(decls = withDefaults(d.decls)))
            is Decl.Function -> listOf(d) + d.params.orEmpty().mapNotNull { p ->
                p.default?.let { value ->
                    // It's worked out where it's written, without the call's other arguments.
                    val refs = linkedSetOf<String>().also { collectRefs(value, emptySet(), it) }
                    d.params.orEmpty().firstOrNull { it.name in refs }?.let {
                        throw CompileError(value.pos, "a default can't use the other parameters, as '${it.name}' here: it's worked out on its own")
                    }
                    defaultOf(d.name, p.name, p.pos, p.type, value, d.typeParams)
                }
            }
            is Decl.Record -> {
                if (d.isAnnotation) d.fields.firstOrNull { it.default != null }?.let { throw CompileError(it.pos, "an annotation's fields can't have defaults") }
                listOf(d) + d.fields.mapNotNull { f -> f.default?.let { defaultOf(d.name, f.name, f.pos, f.type, it, d.typeParams) } }
            }
            is Decl.Sealed -> listOf(d) + d.variants.flatMap { v ->
                v.fields.mapNotNull { f -> f.default?.let { defaultOf("${d.name}$${v.name}", f.name, f.pos, f.type, it, d.typeParams) } }
            }
            else -> listOf(d)
        }
    }

    private fun defaultName(owner: String, name: String) = "$owner\$default\$$name"

    /**
     * [e] with a call to the default of each of [params] it leaves out that has one: those after
     * the ones passed in order, and not passed by name. [owner] is the full name defaults are named for.
     */
    private fun withDefaultArgs(e: Expr.Call, params: List<Pair<String, Boolean>>, owner: String, named: Boolean = true) {
        val inOrder = e.args.takeWhile { it.name == null }.size
        val byName = e.args.mapNotNull { it.name }.toSet()
        val missing = params.drop(inOrder).filter { (name, hasDefault) -> hasDefault && name !in byName }.map { it.first }
        if (missing.isEmpty()) return
        // A method call passes everything in order, so only those at the end can be left out.
        val defaults = missing.map { name ->
            Arg(if (named) name else null, Expr.Call(e.pos, Expr.Name(e.pos, defaultName(owner, name)), emptyList()))
        }
        if (!named && params.drop(inOrder).map { it.first } != missing) return
        e.args = e.args + defaults
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

    /** For a name that isn't found: the imports that would bring in one of that name, if any would. */
    private fun importHint(name: String?): String {
        val imports = name?.let(importsFor).orEmpty()
        return if (imports.isEmpty()) "" else "; " + imports.joinToString(", or ") { "import $it" } + " has one"
    }

    /** Whether [d] is directly in a package, the program's or a folder's, rather than in a module. */
    private fun inPackage(d: Decl): Boolean = d.path.isEmpty() || modules[d.path.joinToString(".")]?.isPackage == true

    /**
     * How many of the current path's names are packages: fckt.io in fckt.io.Files. A name is looked
     * for in the modules around it only so far out as its package, so a file never sees another
     * package's declarations, the program's own included, but through imports.
     */
    private fun packageDepth(): Int {
        var depth = 0
        while (depth < currentPath.size && modules[currentPath.take(depth + 1).joinToString(".")]?.isPackage == true) depth++
        return depth
    }

    /**
     * How far out from the current module [name] is looked for: to its package; and, written in
     * full from a package, as fckt.io.Files.readFile, from the root.
     */
    private fun reach(name: String): List<Int> {
        val depth = packageDepth()
        val full = depth > 0 && modules[name.substringBefore('.')]?.isPackage == true
        return (currentPath.size downTo depth).toList() + if (full) listOf(0) else emptyList()
    }

    /** Whether the current module is [path] or inside it. */
    private fun inside(path: List<String>) = currentPath.take(path.size) == path

    /** Whether [d], and every module around it, can be seen from the current module. */
    private fun visible(d: Decl): Boolean = whyHidden(d) == null

    /**
     * Whether [d] is the program's own, at its top level, and the current file isn't: the core's,
     * or another project's, which a program's declarations are no part of, as its Error is no
     * library's. Not its modules, which it gives a library's functors, or follows a library's specs with.
     */
    private fun foreign(d: Decl): Boolean =
        d.path.isEmpty() && d !is Decl.Module && projectOf(d.pos.file) == "program" && projectOf(currentFile) != "program"

    /** Why [d] can't be seen from the current module, or null if it can. */
    private fun whyHidden(d: Decl): String? {
        if (foreign(d)) return "'${d.simpleName}' is the program's own, which other projects' code can't see"
        if (d.isInternal && projectOf(d.pos.file) != projectOf(currentFile)) return "'${d.simpleName}' is internal to its project, ${projectOf(d.pos.file)}"
        if (d.isPrivate && !inside(d.path)) return "'${d.simpleName}' is private to ${d.path.joinToString(".").ifEmpty { "this file" }}"
        // Private in a package, out of any module: private to its file, as in Kotlin.
        if (d.isPrivate && d !is Decl.Module && inPackage(d) && d.pos.file != currentFile) return "'${d.simpleName}' is private to ${d.pos.file ?: "the program's file"}"
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
        for (n in reach(name)) {
            val d = table[canonical((currentPath.take(n) + name).joinToString("."))] ?: continue
            if (foreign(d)) continue
            val hidden = whyHidden(d)
            if (hidden != null) {
                if (pos == null) continue
                throw CompileError(pos, hidden)
            }
            return d
        }
        // In a module a functor made, what the functor's body names is what it names where the
        // functor is: Testing, beside it in its package, wherever it's applied. Not its modules,
        // which the made module has as what it was given.
        enclosingApplication(currentPath)?.let { app ->
            val saved = currentPath
            currentPath = applications.getValue(app).first.name.split('.') + currentPath.drop(app.split('.').size)
            try {
                find(name, table, pos)?.takeIf { d -> parameterModules.none { d.name == it || d.name.startsWith("$it.") } }?.let { return it }
            } finally {
                currentPath = saved
            }
        }
        // What the file imports: `Shapes.area`, with `import geometry.Shapes`, is geometry.Shapes.area.
        // Two imports bringing in the same name matter only if both mean something of this kind.
        val names = importedNames(name)
        val found = names.mapNotNull { table[canonical(it)] }.distinct()
        // A record and a type with cases of the same name are as alike as two records.
        val alike = if (table === records || table === sealeds || table === aliases) {
            names.mapNotNull { records[canonical(it)] ?: sealeds[canonical(it)] ?: aliases[canonical(it)] }.distinct()
        } else {
            found
        }
        if (alike.size > 1) {
            if (pos == null) return null
            throw CompileError(pos, "'${name.substringBefore('.')}' is imported from ${alike.joinToString(" and ") { it.name }}; import the one you mean by name")
        }
        if (found.size > 1) {
            if (pos == null) return null
            throw CompileError(pos, "'${name.substringBefore('.')}' is imported from ${found.joinToString(" and ") { it.name }}; import the one you mean by name")
        }
        val d = found.singleOrNull() ?: return null
        whyHidden(d)?.let { if (pos == null) return null else throw CompileError(pos, it) }
        return d
    }

    /** The full names [name] may mean through the current file's imports, if it starts with one. */
    private fun importedNames(name: String): List<String> {
        val first = name.substringBefore('.')
        val targets = imported[currentFile]?.get(first)?.let(::listOf) ?: ambiguousImports[currentFile]?.get(first) ?: return emptyList()
        return targets.map { it + name.removePrefix(first) }
    }

    /** The one full name [name] means through the current file's imports, if it starts with one. */
    private fun importedName(name: String, pos: Pos?): String? = importedNames(name).singleOrNull()

    /**
     * Each file's imports, as the names they bring in: a declaration, by its name or its `as`; a
     * module, with its types and their cases; and for `.*`, all of a package or module. One named
     * outright comes before one brought in with a module or a wildcard.
     */
    private fun collectImports() {
        // Everything that can be imported, by its full name.
        val known = mutableMapOf<String, Decl>()
        for (d in decls) known[d.name] = d
        for (m in modules.values) known[m.name] = m
        val cases = decls.filterIsInstance<Decl.Sealed>().flatMap { s -> s.variants.map { "${s.name}.${it.name}" to s } }.toMap()
        // A handled effect's operations, which come with it, by the names they're called by.
        fun operationsOf(full: String): List<Pair<String, String>> {
            val spec = known[full] as? Decl.Spec ?: return emptyList()
            if (spec.annotations.none { it.name == EFFECT_NAME }) return emptyList()
            val prefix = full.substringBeforeLast('.', "").let { if (it.isEmpty()) "" else "$it." }
            return spec.values.map { it.name to "$prefix${it.name}" }
        }
        fun members(of: String): List<Pair<String, String>> {
            val inside = known.values.filter { it.name.substringBeforeLast('.', "") == of }.map { it.simpleName to it.name }
            val itsCases = decls.filterIsInstance<Decl.Sealed>().filter { it.name.substringBeforeLast('.', "") == of }
                .flatMap { s -> s.variants.map { it.name to "${s.name}.${it.name}" } }
            return inside + itsCases + inside.flatMap { (_, full) -> operationsOf(full) }
        }
        fun types(of: String): List<Pair<String, String>> = members(of).filter { (_, full) ->
            val d = known[full]
            d == null || d is Decl.Record || d is Decl.Sealed || d is Decl.Alias || d is Decl.Spec
        }
        for ((file, imports) in fileImports) {
            val named = mutableMapOf<String, String>()
            val implied = mutableMapOf<String, MutableSet<String>>()
            for (i in imports) {
                val full = i.path.joinToString(".")
                if (i.wildcard) {
                    if (full !in modules) throw CompileError(i.pos, "import $full.* names no package or module")
                    members(full).forEach { (name, target) -> implied.getOrPut(name) { linkedSetOf() } += target }
                    continue
                }
                // A method, by its name: its module brings in its extensions, as importing the module would.
                val parent = i.path.dropLast(1).joinToString(".")
                if (full !in known && full !in cases && decls.any { it is Decl.Function && it.extension && it.methodName == i.path.last() && it.name.substringBeforeLast('.', "") == parent }) continue
                if (full !in known && full !in cases) throw CompileError(i.pos, "import $full names nothing")
                named[i.name]?.takeIf { it != full }?.let { throw CompileError(i.pos, "'${i.name}' is imported already, as $it; import this one as another name, with as") }
                named[i.name] = full
                // A module brings its types, and their cases, with it.
                if (known[full] is Decl.Module) types(full).forEach { (name, target) -> implied.getOrPut(name) { linkedSetOf() } += target }
                operationsOf(full).forEach { (name, target) -> implied.getOrPut(name) { linkedSetOf() } += target }
                // A type with cases brings them with it.
                (known[full] as? Decl.Sealed)?.variants?.forEach { implied.getOrPut(it.name) { linkedSetOf() } += "$full.${it.name}" }
            }
            imported[file] = implied.filterValues { it.size == 1 }.mapValues { it.value.single() } + named
            ambiguousImports[file] = implied.filter { (name, targets) -> targets.size > 1 && name !in named }.mapValues { it.value.toList() }
        }
    }

    private fun lookupGlobal(name: String, pos: Pos?) = find(name, globals, pos)
    private fun lookupModule(name: String, pos: Pos?) = find(name, modules, pos)

    /** Whether a module that follows [spec] can be a value of it: it has no types of its own. */
    private fun valueSpec(spec: Decl.Spec): Boolean = !isEffect(spec) && spec.types.isEmpty() && spec.typeParams.isEmpty()

    /** The type of a module that follows [spec], as a value: `Plugin`. */
    private fun moduleType(spec: Decl.Spec, pos: Pos): Type {
        if (isEffect(spec)) throw CompileError(pos, "'${spec.simpleName}' is an effect: a function does it, written @${spec.simpleName}, and a handler answers it; no value has its type")
        if (spec.types.isNotEmpty()) {
            throw CompileError(pos, "${spec.simpleName} has types of its own, ${spec.types.joinToString { it.name }}, which a value of it couldn't say, so it can't be a value's type yet")
        }
        if (spec.typeParams.isNotEmpty()) throw CompileError(pos, "${spec.simpleName} takes type parameters, so it can't be a value's type yet")
        return Type.Con(Type.MODULE + spec.name)
    }

    /**
     * [m] as a value of the spec it follows, which is all a value of it shows: so what it does must
     * be what the spec says it may.
     */
    private fun moduleValue(m: Decl.Module, pos: Pos): Pair<Decl.Spec, Type> {
        if (m.params.isNotEmpty()) throw CompileError(pos, "${m.simpleName} is a functor; give it modules first: ${m.simpleName}(...)")
        if (m.name in parameterModules) throw CompileError(pos, "${m.simpleName} is a functor's module, which can't be a value yet")
        val spec = moduleSpecs[m.name]
            ?: throw CompileError(pos, "${m.simpleName} is a module, which is a value only of a spec it follows: module ${m.simpleName} : Spec { ... }")
        val type = moduleType(spec, pos)
        for (value in spec.values) {
            val decl = globals["${m.name}.${value.name}"] ?: continue
            val done = (globalType(decl) as? Type.Fn)?.let { Type.effectNames(it.effects) }.orEmpty()
            val extra = done - specEffects(spec, value)
            if (extra.isNotEmpty() && !hides(spec, value)) {
                throw CompileError(
                    pos,
                    "${m.simpleName} can't be a ${spec.simpleName} value: its ${value.name} does ${shown(extra)}, which ${spec.simpleName}'s ${value.name} doesn't say it can, " +
                        "and a value shows only what the spec says",
                )
            }
        }
        return spec to type
    }

    /**
     * A functor called in an expression, `plugins = [FkTests.Plugin(Reporting = Quiet)]`, makes its
     * module beside the declaration it's in, as `module Plugin__1 = FkTests.Plugin(Reporting = Quiet)`
     * would; the call is that module, as a value of the functor's spec.
     */
    private fun liftApplications() {
        var count = 0
        for (d in decls.filterIsInstance<Decl.Function>()) {
            currentPath = d.path
            currentFile = d.pos.file
            for (call in (listOf(d.body) + d.params.orEmpty().mapNotNull { it.default }).flatMap { calls(it) }) {
                val name = dotted(call.callee, null) ?: continue
                val functor = lookupModule(name, null)?.takeIf { it.params.isNotEmpty() } ?: continue
                if (enclosingFunctor(d.path) != null) throw CompileError(call.pos, "a functor can't be applied inside another functor yet")
                if (enclosingApplication(d.path) != null) continue
                val args = call.args.map { arg ->
                    val module = dotted(arg.value, null)?.takeIf { lookupModule(it, null) != null }
                        ?: throw CompileError(arg.value.pos, "${functor.simpleName} takes modules, by name")
                    ModuleArg(arg.name, TypeRef.Named(arg.value.pos, module, emptyList()))
                }
                val app = Decl.Module(
                    call.pos,
                    (d.path + "${functor.simpleName}__${++count}").joinToString("."),
                    emptyList(),
                    isPrivate = true,
                    application = Application(TypeRef.Named(call.callee.pos, name, emptyList()), args),
                )
                modules[app.name] = app
                apply(app)
                lifted[call] = app.name
                root = withModule(root, d.path, app)
            }
        }
    }

    /** [ds] with [m] in the module at [path], or among them where [path] is empty. */
    private fun withModule(ds: List<Decl>, path: List<String>, m: Decl.Module): List<Decl> {
        if (path.isEmpty()) return ds + m
        val name = path.joinToString(".")
        return ds.map { d ->
            when {
                d !is Decl.Module -> d
                d.name == name -> d.copy(decls = d.decls + m).also { modules[name] = it }
                name.startsWith(d.name + ".") -> d.copy(decls = withModule(d.decls, path, m)).also { modules[d.name] = it }
                else -> d
            }
        }
    }

    /** The calls in [e], and in what it holds. */
    private fun calls(e: Expr): List<Expr.Call> {
        val found = mutableListOf<Expr.Call>()
        fun statement(s: Statement) = when (s) {
            is Statement.Binding -> listOf(s.value)
            is Statement.Effect -> listOf(s.expr)
            is Statement.Destructure -> listOf(s.value)
            is Statement.Bind -> listOf(s.value)
        }
        fun go(x: Expr) {
            if (x is Expr.Call) found += x
            val inner: List<Expr> = when (x) {
                is Expr.Call -> listOf(x.callee) + x.args.map { it.value }
                is Expr.Tuple -> x.elements
                is Expr.Spread -> listOf(x.list)
                is Expr.StringLit -> x.parts.filterIsInstance<StringPart.Interpolated>().map { it.expr }
                is Expr.ListLit -> x.items
                is Expr.Member -> listOf(x.target)
                is Expr.Binary -> listOf(x.left, x.right)
                is Expr.Unary -> listOf(x.operand)
                is Expr.Assign -> listOf(x.target, x.value)
                is Expr.Lambda -> listOf(x.body)
                is Expr.Pipe -> listOf(x.value, x.function)
                is Expr.If -> listOf(x.condition, x.then, x.otherwise)
                is Expr.When -> listOfNotNull(x.subject) + x.branches.flatMap { listOfNotNull(it.condition, it.guard, it.body) }
                is Expr.Block -> x.statements.flatMap { statement(it) } + x.result
                is Expr.Monadic -> listOf(x.module, x.block)
                is Expr.Handle -> x.call?.let { listOf(it) } ?: listOf(x.handler, x.block)
                else -> emptyList()
            }
            inner.forEach { go(it) }
        }
        go(e)
        return found
    }
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
            currentFile = m.pos.file
            val spec = find(ref.name, specs, ref.pos) ?: throw CompileError(ref.pos, "unknown spec '${ref.name}'${importHint(ref.name)}")
            if (ref.args.isNotEmpty() && m.name !in parameterModules) {
                throw CompileError(ref.args.first().pos, "${m.simpleName} says what its types are in its body, so write just : ${spec.simpleName}; type arguments are for a functor's parameters, like F(M: ${spec.simpleName}<...>)")
            }
            if (spec.typeParams.isNotEmpty()) {
                throw CompileError(ref.pos, "${m.simpleName} can't follow ${spec.simpleName}, which takes type parameters; handle it with a value instead: ${spec.simpleName}(...) { ... }")
            }
            moduleSpecs[m.name] = spec
        }
        // A module a functor makes follows the functor's spec, with its modules in place.
        for ((app, made) in applications) moduleSpecs[made.first.name]?.let { moduleSpecs[app] = it }
        currentPath = emptyList()
        currentFile = null
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
            currentFile = a.pos.file
            expand(a, a.typeParams.map { Type.Con("#$it") })
        }
        for (r in records.values) {
            currentPath = r.path
            currentFile = r.pos.file
            checkFieldNames(r.fields.map { it.name to it.pos })
            withTypeParams(r.typeParams) { r.fields.forEach { resolve(it.type) } }
        }
        for (s in sealeds.values) {
            currentPath = s.path
            currentFile = s.pos.file
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
            currentFile = a.pos.file
            checkFieldNames(a.fields.map { it.name to it.pos })
            annotationFields[a] = a.fields.map { if (it.isVararg) Type.list(resolveMeta(it.type)) else resolveMeta(it.type) }
        }
        currentPath = emptyList()
        currentFile = null
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
        val outerPathFile = currentFile
        currentPath = s.path
        currentFile = s.pos.file
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
        currentFile = outerPathFile
        return result
    }

    private fun substitute(ref: TypeRef, substitution: Map<String, TypeRef>): TypeRef = when (ref) {
        is TypeRef.Named ->
            if (ref.args.isEmpty() && ref.name in substitution) substitution.getValue(ref.name)
            else ref.copy(args = ref.args.map { substitute(it, substitution) }).also { copy -> typeNames[ref]?.let { typeNames[copy] = it } }
        is TypeRef.Function -> ref.copy(params = ref.params.map { substitute(it, substitution) }, result = substitute(ref.result, substitution))
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { substitute(it, substitution) })
        is TypeRef.Effects -> ref
    }

    /** Written type syntax as text, ignoring positions. */
    private fun text(ref: TypeRef): String = when (ref) {
        is TypeRef.Named -> if (ref.args.isEmpty()) ref.name else "${ref.name}<${ref.args.joinToString(", ") { text(it) }}>"
        is TypeRef.Function -> "(${ref.params.joinToString(", ") { text(it) }}) -> ${text(ref.result)}"
        is TypeRef.Tuple -> "(${ref.elements.joinToString(", ") { text(it) }})"
        is TypeRef.Effects -> ref.effects.joinToString(" ") { "@" + it.name }
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
        // An effect parameter stands for any effects, which nothing here needs to tell apart.
        typeParams = names.associateWith { if (it.startsWith("@")) u.fresh() else Type.Con("#$it") }
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
            // A parameter's `@Fail () -> A` may do Fail and more, which calling it passes on:
            // attempt { println("x"); fail("y") } is @Console where it's called.
            if (ref.effects.isEmpty()) arrowEffects ?: Type.effects(emptySet(), null) else effectRow(ref.effects, open = arrowEffects != null),
        )
        is TypeRef.Effects -> effectRow(ref.effects, open = false, pure = true)
        is TypeRef.Tuple -> Type.tuple(ref.elements.map { resolve(it) })
        is TypeRef.Named -> {
            var args = ref.args.map { resolve(it) }
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
                    args = effectArgs(ref, typeParamsOf(it), args)
                    arity(typeParamsOf(it).size)
                    typeNames[ref] = it.name
                    typeOf(it, args)
                }
                ?: find(ref.name, specs, null)?.let { spec ->
                    arity(0)
                    moduleType(spec, ref.pos).also { typeNames[ref] = Type.MODULE + spec.name }
                }
                ?: find(ref.name, annotationTypes, null)?.let {
                    throw CompileError(ref.pos, "'${ref.name}' is an annotation, written @${ref.name}(...) before a declaration; no value has its type")
                }
                ?: typeParams["@" + ref.name]?.let { throw CompileError(ref.pos, "'${ref.name}' is an effect parameter, written @${ref.name}, as in @${ref.name} () -> Unit") }
                ?: throw CompileError(ref.pos, if (ref.name == "_") "'_' only marks a spec type's parameters, as in Monad<Result<_, String>>" else "unknown type '${ref.name}'${importHint(ref.name)}")
        }
    }

    /**
     * [args], written for [ref] of a type with [params], with what goes to its effect parameters:
     * effects, as in Queue<@Console>, and any left out, which may be anything, as Queue is.
     */
    private fun effectArgs(ref: TypeRef.Named, params: List<String>, args: List<Type>): List<Type> {
        val effects = params.count { it.startsWith("@") }
        if (effects == 0 && ref.args.none { it is TypeRef.Effects }) return args
        if (args.size == params.size) {
            for ((i, p) in params.withIndex()) {
                val given = ref.args[i]
                if (p.startsWith("@") && given !is TypeRef.Effects) {
                    throw CompileError(given.pos, "${ref.name}'s ${p.removePrefix("@")} is effects, like ${ref.name}<@Console>, not a type")
                }
                if (!p.startsWith("@") && given is TypeRef.Effects) {
                    throw CompileError(given.pos, "${ref.name}'s $p is a type, not effects")
                }
            }
            return args
        }
        if (args.size != params.size - effects || ref.args.any { it is TypeRef.Effects }) return args
        val types = args.iterator()
        return params.map { if (it.startsWith("@")) u.fresh() else types.next() }
    }

    /**
     * The row [annotations] say: the effects they name, and an effect parameter's, whose rest is
     * its own. Without one, [open] leaves room for more. A row of effects alone may be [pure].
     */
    private fun effectRow(annotations: List<Annotation>, open: Boolean, pure: Boolean = false): Type {
        val (params, named) = annotations.partition { "@" + it.name in typeParams }
        if (params.size > 1) throw CompileError(params[1].pos, "effects can name only one effect parameter")
        val labels = effectLabels(named).filter { !pure || it.name != PURE }
        val param = params.firstOrNull()?.let { typeParams.getValue("@" + it.name) }
            ?: return Type.row(labels, if (open) u.fresh() else null)
        val (more, rest) = Type.row(param)
        return Type.row((labels + more).distinct(), rest)
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
     * Which types' values can change: a record, or a type with a case, with a `var` field, then,
     * until there are no more, the records and types with cases whose fields hold one.
     */
    private fun findRegioned() {
        // An OCaml type may change too: its own functions say if they do.
        records.values.filter { r -> r.external != null || r.fields.any { it.isVar } }.forEach { regioned += it.name }
        sealeds.values.filter { s -> s.variants.any { v -> v.fields.any { it.isVar } } }.forEach { regioned += it.name }
        fun holds(ref: TypeRef): Boolean = when (ref) {
            // A function isn't a value that changes, whatever it takes or gives.
            is TypeRef.Function -> false
            is TypeRef.Tuple -> ref.elements.any(::holds)
            is TypeRef.Effects -> false
            is TypeRef.Named -> ref.args.any(::holds) || (lookupRecord(ref.name, null) ?: lookupSealed(ref.name, null))?.name in regioned ||
                lookupAlias(ref.name, null)?.let { holds(it.target) } == true
        }
        do {
            val before = regioned.size
            for (d in records.values + sealeds.values) {
                if (d.name in regioned) continue
                currentPath = d.path
                currentFile = d.pos.file
                val fields = if (d is Decl.Record) d.fields else (d as Decl.Sealed).variants.flatMap { it.fields }
                if (fields.any { holds(it.type) }) regioned += d.name
            }
        } while (regioned.size != before)
        currentPath = emptyList()
        currentFile = null
    }

    /** The type parameters of the module at [path]: E, for `module Results<E>`. */
    private fun moduleTypeParams(path: List<String>): List<String> = modules[path.joinToString(".")]?.typeParams.orEmpty()

    /**
     * [block], seeing [module]'s types from inside it, with a type for each of its type parameters:
     * `M<A>`, in `module Results<E>`, is `Result<A, E>` for that E. Those types, and what it gives.
     */
    private inline fun <R> withModuleTypes(module: List<String>, block: () -> R): Pair<List<Type>, R> {
        val saved = typeParams to currentPath
        val vars = moduleTypeParams(module).associateWith { u.fresh() as Type }
        typeParams = vars
        currentPath = module
        try {
            return vars.values.toList() to block()
        } finally {
            typeParams = saved.first
            currentPath = saved.second
        }
    }

    /** What alias [a] stands for, applied to [args]; its target means what it does where it's written. */
    private fun expand(a: Decl.Alias, args: List<Type>): Type {
        if (!expanding.add(a.name)) throw CompileError(a.pos, "type '${a.simpleName}' is defined in terms of itself")
        val saved = Triple(typeParams, specTypes, currentPath)
        val savedFile = currentFile
        // In `module Results<E>`, `type M<A> = Result<A, E>` has the E of what uses it there; elsewhere, any.
        val inModule = currentPath.take(a.path.size) == a.path
        typeParams = moduleTypeParams(a.path).associateWith { (if (inModule) typeParams[it] else null) ?: u.fresh() } + a.typeParams.zip(args).toMap()
        specTypes = emptyMap()
        currentPath = a.path
        currentFile = a.pos.file
        try {
            return resolve(a.target)
        } finally {
            typeParams = saved.first
            specTypes = saved.second
            currentPath = saved.third
            currentFile = savedFile
            expanding.remove(a.name)
        }
    }

    // ---- Annotations ----

    /**
     * An annotation's field's type: an ordinary type, or a reference only the checker knows:
     * `Type`, `Spec`, `Function`, `Module` or `Module<Spec>`, `Case<T>`, `Field<R, T>` (`_` for any T), another
     * annotation, or lists and tuples of them. Those are [Type.Con]s named with [Type.META].
     */
    private fun resolveMeta(ref: TypeRef): Type {
        if (ref is TypeRef.Tuple) return Type.tuple(ref.elements.map { resolveMeta(it) })
        if (ref !is TypeRef.Named) return resolve(ref)
        fun arity(n: Int) {
            if (ref.args.size != n) throw CompileError(ref.pos, "'${ref.name}' takes $n type argument${if (n == 1) "" else "s"}, not ${ref.args.size}")
        }
        return when (ref.name) {
            "Type", "Spec", "Function" -> Type.Con(Type.META + ref.name).also { arity(0) }
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
            "Function" -> "a function, by name"
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
            annotations.firstOrNull()?.let { currentFile = it.pos.file }
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
        currentFile = null
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
                "unknown annotation '${a.name}'${importHint(a.name)}"
            },
        )
        a.decl = decl
        val types = annotationFields.getValue(decl)
        val vararg = decl.fields.lastOrNull()?.takeIf { it.isVararg }
        if (vararg == null) {
            val order = argOrder(a.args.map { it.name to it.value.pos }, decl.fields.map { it.name }, a.pos, "@${a.name}")
            types.forEachIndexed { i, type -> checkMeta(a.args[order[i]].value, type) }
            return
        }
        // Its items are what's given in order after the other fields, or, by its name, the list.
        val fixed = decl.fields.size - 1
        val inOrder = a.args.takeWhile { it.name == null }
        val listed = a.args.firstOrNull { it.name == vararg.name }
        if (listed != null && inOrder.size > fixed) throw CompileError(listed.value.pos, "'${vararg.name}' is passed twice")
        val others = inOrder.take(fixed) + a.args.drop(inOrder.size).filter { it !== listed }
        val order = argOrder(others.map { it.name to it.value.pos }, decl.fields.dropLast(1).map { it.name }, a.pos, "@${a.name}")
        types.dropLast(1).forEachIndexed { i, type -> checkMeta(others[order[i]].value, type) }
        if (listed != null) checkMeta(listed.value, types.last())
        else inOrder.drop(fixed).forEach { checkMeta(it.value, (types.last().prune() as Type.Con).args[0]) }
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
            // A function as declared, whatever its type: what reads the annotation says what it must be.
            Type.META + "Function" -> {
                val function = lookupGlobal(name(), m.pos) ?: throw CompileError(m.pos, "unknown function '${name()}'")
                if (function.params == null) throw CompileError(m.pos, "${function.simpleName} is a value, not a function")
                MetaRef.Function(function)
            }
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
                val (ref, _) = field(target, receiver, scope) ?: throw noField(target, receiver)
                target.resolved = ref
                // A record's field, or one of the case the value was smart-cast to: `if (n is Branch) n.left = ...`.
                val (owner, fields) = when (ref) {
                    is MemberRef.CastField -> scope.lookup((target.target as Expr.Name).name)!!.cast!!.variant.let { it.name to it.fields }
                    is MemberRef.CastTagField -> throw CompileError(
                        target.pos,
                        "${target.name} can't be changed here: its case is shared with another type, and OCaml can't change such a case's fields in place",
                    )
                    else -> records.getValue((receiver as Type.Con).name).let { it.simpleName to it.fields }
                }
                if (!fields.first { it.name == target.name }.isVar) {
                    throw CompileError(target.pos, "$owner's ${target.name} can't be changed; declare it with var ${target.name}: ...")
                }
                infer(target, null, scope)
                (receiver as Type.Con).region!!
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
        val savedFile = currentFile
        currentPath = path
        currentFile = a.pos.file
        try {
            return find(a.name, annotationTypes, null) ?: find(a.name, specs, null)?.takeIf { isEffect(it) }
        } finally {
            currentPath = saved
            currentFile = savedFile
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
            is Expr.CharLit -> "'${e.text}'"
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
        val decl = annotationType(a, currentPath) ?: throw CompileError(a.pos, "unknown effect '${a.name}'${importHint(a.name)}")
        a.decl = decl
        if (decl is Decl.Spec && a.typeArgs.isNotEmpty()) return@flatMap listOf(Type.Con(decl.name, a.typeArgs.map { resolve(it) }))
        effectsOf(listOf(a), currentPath, a.pos).map { Type.Con(it) }.ifEmpty {
            throw CompileError(a.pos, "'${a.name}' isn't an effect; declare one with effect ${a.name.substringAfterLast('.')}")
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
        for (n in reach(name)) {
            val found = operations[(currentPath.take(n) + name).joinToString(".")]?.filter { visible(it.first) }.orEmpty()
            if (found.isEmpty()) continue
            if (found.size > 1) {
                throw CompileError(pos, "'$name' is an operation of ${found.joinToString(" and ") { it.first.simpleName }}; write ${found.joinToString(" or ") { "${it.first.simpleName}.$name" }}")
            }
            return found.single()
        }
        // An imported effect's: import io.* or io.FileSystem brings in FileSystem's operations.
        val found = importedNames(name).flatMap { operations[it].orEmpty() }.filter { visible(it.first) }.distinct()
        if (found.size > 1) {
            throw CompileError(pos, "'$name' is an operation of ${found.joinToString(" and ") { it.first.simpleName }}; write ${found.joinToString(" or ") { "${it.first.simpleName}.$name" }}")
        }
        return found.singleOrNull()
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
     * each of [spec]'s operations. An operation's function may take one more parameter, `resume`:
     * the rest of the handled block, which it calls with the operation's result, later, or never.
     * What the block gives, and so what `resume` does, is the handler's answer, its type's
     * second-to-last argument; its answers run where it handles, so what they do is its last.
     */
    private fun makeHandler(e: Expr.Call, spec: Decl.Spec, scope: Scope): Type {
        val args = specTypeVars.getValue(spec).map { u.fresh() as Type }
        val answer = u.fresh()
        val answers = u.fresh()
        val order = argOrder(e.args.map { it.name to it.value.pos }, spec.values.map { it.name }, e.pos, spec.simpleName)
        val resumes = spec.values.mapIndexed { i, v ->
            val scheme = specSchemes.getValue(spec).getValue(v.name)
            val mapping = scheme.vars.associateWith { u.fresh() as Type } + specTypeVars.getValue(spec).zip(args).toMap()
            val t = u.substitute(scheme.type, mapping)
            val arg = e.args[order[i]].value
            if (t !is Type.Fn) {
                check(arg, t, scope)
                return@mapIndexed false
            }
            // A function with one more parameter than the operation takes resume. A lambda says by
            // how many it's written with; anything else, by its type.
            val given = if (arg is Expr.Lambda) null else infer(arg, null, scope).prune()
            val resuming = when {
                arg is Expr.Lambda -> !arg.ignoresArguments && arg.params.size == t.params.size + 1
                else -> (given as? Type.Fn)?.params?.size == t.params.size + 1
            }
            val field = if (resuming) {
                Type.Fn(t.params + Type.Fn(listOf(t.result), answer, answers), answer, answers)
            } else {
                Type.Fn(t.params, t.result, answers)
            }
            try {
                if (given != null) assign(arg, given, field) else check(arg, field, scope)
            } catch (error: CompileError) {
                if (resuming || t.result.prune() != Type.NOTHING) throw error
                throw CompileError(
                    arg.pos,
                    "'${v.name}' gives Nothing, so it can't be answered: take resume and don't call it, " +
                        "as ${v.name} = (${(v.params!!.map { it.name } + "resume").joinToString(", ")}) -> ...",
                )
            }
            resuming
        }
        e.resolved = CallRef.Handler(spec, order, resumes)
        return Type.Con(Type.HANDLER + spec.name, args + answer + answers)
    }

    /** A module following [spec], as a handler: what its functions do is what its answers do. */
    private fun moduleHandler(module: Decl.Module, spec: Decl.Spec): Type {
        val answers = u.fresh()
        for (v in spec.values) {
            val f = globals.getValue("${module.name}.${v.name}")
            (globalType(f) as? Type.Fn)?.let { subEffects(it.effects, answers) }
        }
        return Type.Con(Type.HANDLER + spec.name, listOf(u.fresh(), answers))
    }

    /** `handler { block }`. */
    private fun inferHandle(e: Expr.Handle, expected: Type?, scope: Scope): Type {
        // A function given its last argument as a block, as in Kotlin: `sequence { ... }` is
        // `sequence(~{ ... })`, and `retry(3) { ... }` is `retry(3, ~{ ... })`.
        val block = Arg(null, Expr.Lambda(e.block.pos, e.params.orEmpty(), e.block).apply { trailing = true })
        val h = e.handler
        val makesHandler = h is Expr.Call && h.callee is Expr.Name && scope.lookup(h.callee.name) == null &&
            lookupGlobal(h.callee.name, h.callee.pos) == null && find(h.callee.name, specs, null)?.let { isEffect(it) } == true
        // A method: `path.reading { ... }`, `xs.fold(0) { ... }`.
        if (h is Expr.Member) return infer(Expr.Call(e.pos, h, listOf(block)).also { e.call = it }, expected, scope)
        if (h is Expr.Call && h.callee is Expr.Member && h.callee.name[0].isLowerCase()) {
            return infer(Expr.Call(e.pos, h.callee, h.args + block, h.typeArgs).also { e.call = it }, expected, scope)
        }
        // A declared function's last parameter, by name, so those before it can be left to their defaults.
        val (callee, given) = when {
            h is Expr.Call && !makesHandler -> h.callee to h.args
            h is Expr.Name -> h to emptyList()
            else -> null to emptyList()
        }
        val declared = (callee as? Expr.Name)?.takeIf { scope.lookup(it.name) == null }?.let { lookupGlobal(it.name, null) }
        val last = declared?.params?.lastOrNull()?.name
        if (callee != null && last != null && declared.params!!.size > given.size + 1 && given.none { it.name == last } &&
            declared.params.dropLast(1).drop(given.takeWhile { it.name == null }.size).all { p -> p.default != null || given.any { it.name == p.name } }
        ) {
            return infer(Expr.Call(e.pos, callee, given + block.copy(name = last), (h as? Expr.Call)?.typeArgs.orEmpty()).also { e.call = it }, expected, scope)
        }
        if (h is Expr.Call && !makesHandler) {
            val callee = infer(h.callee, null, scope).prune()
            if (callee is Type.Fn && callee.params.size == h.args.size + 1) {
                return infer(Expr.Call(e.pos, h.callee, h.args + block, h.typeArgs).also { e.call = it }, expected, scope)
            }
        }
        val type = infer(e.handler, null, scope).prune()
        if (type is Type.Fn && type.params.size == 1) {
            return infer(Expr.Call(e.pos, e.handler, listOf(block)).also { e.call = it }, expected, scope)
        }
        val handler = type as? Type.Con
        val spec = handler?.name?.takeIf { it.startsWith(Type.HANDLER) }?.let { specs[it.removePrefix(Type.HANDLER)] }
            ?: throw CompileError(e.handler.pos, "this isn't a handler: make one from a handled effect, as Console(println = ...), or use a module that follows one")
        if (e.params != null) throw CompileError(e.block.pos, "a handler's block runs with the effect handled, so it takes no parameters")
        e.handles = spec
        val n = handler.args.size
        return handling(spec, handler.args.take(n - 2), handler.args[n - 2], handler.args[n - 1], e.block, expected, scope, e.pos)
    }

    /**
     * Runs [block] with [spec]<[args]> handled: inside, it may do the effect; outside, what's left
     * of what it does is done here, as is what the handler's [answers] do. The block gives the
     * handler's [answer], as does every handler that doesn't resume.
     */
    private fun handling(spec: Decl.Spec, args: List<Type>, answer: Type, answers: Type, block: Expr.Block, expected: Type?, scope: Scope, pos: Pos): Type {
        val rest = u.fresh()
        val type = doing(Type.row(listOf(Type.Con(spec.name, args)), rest)) { infer(block, expected ?: known(answer), scope) }
        assign(block, type, answer)
        perform(rest, pos)
        perform(answers, pos)
        return type
    }

    /** The module `@HandledBy` names as [spec]'s default, which `main` uses if it doesn't handle it. */
    private fun defaultHandler(spec: Decl.Spec): Decl.Module? {
        val annotation = spec.annotations.firstOrNull { annotationType(it, spec.path)?.name == HANDLED_BY } ?: return null
        val name = (annotation.args.singleOrNull()?.value as? Meta.Ref)?.name ?: return null
        val saved = currentPath
        val savedFile = currentFile
        currentPath = spec.path
        currentFile = spec.pos.file
        try {
            return lookupModule(name, null)
        } finally {
            currentPath = saved
            currentFile = savedFile
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
        val saved = effects to included
        effects = row
        included = mutableListOf()
        try {
            val result = block()
            include(row, included!!)
            return result
        } finally {
            effects = saved.first
            included = saved.second
        }
    }

    /**
     * Effects that are someone else's to say, each where it was done: a parameter's, as in a
     * `Queue<@E>`'s tasks. They're added once the code is checked, as the rest of its own, so the
     * code does what it does itself and E, rather than E being made to hold all it does.
     */
    private var included: MutableList<Pair<Type.Var, Pos>>? = null

    /** Adds each of [rests] to [row], once nothing more of the code's own will be. */
    private fun include(row: Type, rests: List<Pair<Type.Var, Pos>>) {
        for ((v, pos) in rests) {
            val (labels, rest) = Type.row(v)
            if (labels.isNotEmpty()) performIn(Type.row(labels, null), row, pos)
            val own = Type.row(row).second
            when {
                rest == null || rest === own -> {}
                // Closed: E may do only what the row says.
                own == null -> if (!u.unify(Type.row(emptyList(), rest), row)) performIn(rest, row, pos)
                // Open: the row's rest is E, with whatever else gets added to it.
                else -> u.unify(own, rest)
            }
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
        // A rest that's someone else's is added once the code is checked.
        if (rest != null && rest !== Type.row(effects).second && included != null) {
            included!! += rest to pos
            return performIn(Type.row(labels, null), effects, pos)
        }
        performIn(row, effects, pos)
    }

    private fun performIn(row: Type, effects: Type, pos: Pos) {
        val (labels, rest) = Type.row(row)
        // Doing what may include what the function does anyway, as a parameter's @Fail () -> A
        // does, is doing its own labels.
        val open = if (rest == null || rest === Type.row(effects).second) Type.row(labels, u.fresh()) else row
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
        // A default value's: what it does is done by the call that leaves the argument out.
        if ("\$default\$" in decl.simpleName) return
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
                    // A parameter's row can hold the function's own, which isn't seen through it.
                    val own = skip?.let { Type.row(it).first }.orEmpty()
                    Type.row(t).first.filter { label -> own.none { Type.sameEffect(it, label) } }.forEach { label -> label.args.firstOrNull()?.prune()?.let { into += it } }
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
            currentFile = spec.pos.file
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
        currentFile = null
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
        currentFile = null
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
        val inside = withModuleTypes(decl.path) { specView(wanted.type, rigid) { name, args -> typeOf(typeDecl("$module.$name")!!, args) } }.second
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
        // Generic, too, in the module's own type parameters.
        val (moduleVars, shownType) = withModuleTypes(decl.path) { specView(wanted.type, emptyMap()) { name, args -> outsideType(module, name, args) } }
        val vars = wanted.vars + moduleVars.filterIsInstance<Type.Var>()
        outsideSchemes[decl] = if (declared.isEmpty() && shownType is Type.Fn) {
            val rest = u.fresh()
            Scheme(vars + rest, Type.Fn(shownType.params, shownType.result, Type.effects(done, rest)))
        } else {
            Scheme(vars, shownType)
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

    /**
     * Whether the current file can call the extension [d] by its method's name: it's the core's, or
     * its file's, or in the package the file is in, as its modules are; or the file imports its
     * module, or anything in it, as its type, whose methods come with it.
     */
    private fun inScope(d: Decl.Function): Boolean = onlyAsPackageMate(d) != null

    /**
     * Whether [d] is in scope here only as a method of a module beside the file's in its package,
     * which the file doesn't import: true; in scope otherwise, false; or not, null.
     */
    private fun onlyAsPackageMate(d: Decl.Function): Boolean? {
        if (d.pos.file == currentFile || d.pos.file?.startsWith(uk.m4xy.fckt.CORE_PATH) == true) return false
        val module = d.path.joinToString(".")
        val here = currentPath.joinToString(".")
        if (module.isEmpty() || here == module || here.startsWith("$module.")) return false
        if (fileImports[currentFile].orEmpty().any { i -> i.path.joinToString(".").let { it == module || it.startsWith("$module.") } }) return false
        val pkg = (modules[module]?.takeIf { !it.isPackage }?.let { d.path.dropLast(1) } ?: d.path).joinToString(".")
        return if (here == pkg || here.startsWith("$pkg.")) true else null
    }

    /** For a method that isn't in scope here, but one of a module's that fits would be: the imports that bring it in. */
    private fun methodImportHint(name: String, receiver: Type): String {
        val linked = extensions[name].orEmpty().filter { visible(it) && !inScope(it) && fit(receiverType(it), receiver) != null }.map { it.path.joinToString(".") }
        // Or, of the modules not brought in at all, those that declare a method of that name.
        val modules = linked.ifEmpty { importsFor(name).filter { it.endsWith(".$name") }.map { it.removeSuffix(".$name") } }.distinct()
        return if (modules.isEmpty()) "" else "; " + modules.joinToString(", or ") { "import $it.*" } + " has one"
    }

    /** Of the functions each one's body may use, those it was taken to only because a `.name` might be a package-mate's method. */
    private val guessedRefs = mutableMapOf<Decl.Function, Set<Decl.Function>>()

    private fun dependencyGroups(): List<FunctionGroup> {
        val decls = globals.values.filter { it !in parameterMembers }
        val deps = decls.associateWith { decl -> refsOf(decl).also { uses[decl] = it }.filter { it !in parameterMembers } }
        // A call between modules goes one way, as OCaml's modules can't call each other: so where
        // `.name`s that are only maybe a package-mate's methods join functions of different
        // modules into a group, they're taken as what they may be, not as calls.
        val certain = decls.associateWith { decl ->
            val guessed = guessedRefs[decl].orEmpty()
            deps.getValue(decl).filter { it !in guessed || it.path == decl.path }
        }
        val components = stronglyConnected(decls) { deps.getValue(it) }.flatMap { component ->
            if (component.map { it.path }.distinct().size > 1) stronglyConnected(component) { d -> certain.getValue(d).filter { it in component } } else listOf(component)
        }
        return components.map { component ->
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

    /**
     * The default values' functions a use of [ref] may call: a function's or extension's, or the
     * fields' of a record or case it may build, as calls that leave arguments out call them.
     */
    private fun defaultsUsed(ref: String): List<Decl.Function> {
        val owners = listOfNotNull(lookupGlobal(ref, null)?.name, lookupRecord(ref, null)?.name) +
            extensions[ref].orEmpty().map { it.name } +
            variantsByName[ref.substringAfterLast('.')].orEmpty().map { (s, v) -> "${s.name}$${v.name}" }
        return owners.flatMap { owner -> globals.values.filter { it.name.startsWith("$owner\$default\$") } }
    }

    /** The functions and values [decl]'s body may use; once it's checked, only those it does. */
    private fun refsOf(decl: Decl.Function): List<Decl.Function> {
        val refs = linkedSetOf<String>()
        val bound = (decl.params?.map { it.name } ?: emptyList()).toSet()
        collectRefs(decl.body, bound, refs)
        // A name may be plain (`gcd`) or go through modules (`Ratios.make`), seen from the decl's module.
        val saved = currentPath
        val savedFile = currentFile
        currentPath = decl.path
        currentFile = decl.pos.file
        try {
            val guessed = mutableSetOf<Decl.Function>()
            val named = mutableSetOf<Decl.Function>()
            return refs.flatMap { ref ->
                // After a dot, only an extension of that name; one only of a module beside it, which
                // isn't imported, is taken for a call least surely.
                if (ref.startsWith(".")) return@flatMap (extensions[ref.drop(1)].orEmpty().filter { visible(it) && inScope(it) } + defaultsUsed(ref.drop(1))).also { found ->
                    guessed += found.filter { it.extension && onlyAsPackageMate(it) == true }
                }
                // A method's name may be any extension of that name.
                // A module used as a handler or a value: its functions.
                (lookupModule(ref, null)?.takeIf { m -> moduleSpecs[m.name]?.let { isEffect(it) || valueSpec(it) } == true }
                    ?.let { m -> globals.values.filter { it.path == m.name.split('.') } }.orEmpty() +
                extensions[ref].orEmpty().filter { visible(it) } + listOfNotNull(
                    lookupGlobal(ref, null) ?: ref.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { module ->
                        lookupModule(module, null)?.let { m -> globals[m.name + "." + ref.substringAfterLast('.')]?.takeIf { visible(it) } }
                    },
                ) + defaultsUsed(ref)).also { named += it }
            }.distinct().also { guessedRefs[decl] = guessed - named }
        } finally {
            currentPath = saved
            currentFile = savedFile
        }
    }

    /**
     * Names an expression may refer to that aren't bound inside it. Over-approximates, which is
     * harmless; once checked, a built-in method or a field isn't taken for a function of its name.
     */
    private fun collectRefs(e: Expr, bound: Set<String>, into: MutableSet<String>) {
        fun go(x: Expr, b: Set<String> = bound) = collectRefs(x, b, into)
        when (e) {
            is Expr.Name -> when (val r = e.resolved) {
                // Once checked, an operation isn't a function of its name.
                is NameRef.Operation -> {}
                else -> if (e.name !in bound) into += e.name
            }
            is Expr.Compiled, is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit -> {}
            is Expr.Tuple -> e.elements.forEach { go(it) }
            is Expr.Spread -> go(e.list)
            is Expr.StringLit -> e.parts.forEach { if (it is StringPart.Interpolated) go(it.expr) }
            is Expr.ListLit -> e.items.forEach { go(it) }
            is Expr.Call -> {
                val callee = e.callee
                // A functor's call is the module it makes.
                lifted[e]?.let { into += it }
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
                // Possibly an extension call or property: written `.name`, as only an extension can be reached so.
                if (!known) into += ".${e.name}"
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
            // Once checked, a function given a trailing block is the call it was made.
            is Expr.Handle -> e.call?.let { go(it) } ?: run {
                go(e.handler)
                go(e.block, bound + e.params.orEmpty().map { it.name } + listOfNotNull("it".takeIf { e.params == null }))
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
                    branch.guard?.let { go(it, bound + branch.patterns.flatMap { p -> patternNames(p) }) }
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
        currentFile = group.decls.first().pos.file
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
            // An external's body is OCaml, trusted to have its declared type and effects; a compiled
            // library's, in its interface, is trusted to do what its function or module says.
            if (decl.body is Expr.Compiled && decl.external == null) {
                // A default value's does what the call that leaves the argument out does.
                val does = if ("\$default\$" in decl.simpleName) emptySet() else allowed(decl)
                u.unify(row, Type.row(does.map { Type.Con(it) }, u.fresh()))
                continue
            }
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
            included = mutableListOf()
            if (type is Type.Fn) {
                decl.params!!.zip(type.params).forEach { (p, t) -> scope.vars[p.name] = Local(t, snake(p.name)) }
                check(decl.body, type.result, scope)
            } else {
                check(decl.body, type, scope)
            }
            include(row, included!!)
            included = null
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
        for (decl in group.decls) if (decl.external == null && decl.body !is Expr.Compiled) checkEffects(decl, done.getValue(decl))
        for (decl in group.decls) {
            // A declared type parameter must stay abstract: `id<T>(x: T): Int = x` is an error.
            val vars = declaredParams.getValue(decl)
            val seen = mutableSetOf<Type>()
            for ((name, v) in vars) {
                // An effect parameter's is a row, which must be only its own rest: any effects.
                if (name.startsWith("@")) {
                    val (labels, rest) = Type.row(v)
                    if (labels.isNotEmpty() || rest == null || !seen.add(rest)) {
                        throw CompileError(decl.pos, "effect parameter $name of '${decl.simpleName}' always does ${shown(labels.map { it.name }.toSet())} here")
                    }
                    continue
                }
                val t = v.prune()
                if (t !is Type.Var || !seen.add(t)) throw CompileError(decl.pos, "type parameter $name of '${decl.name}' is always $t here")
            }
            fun seen(t: Type) = (if (t is Type.Fn) Type.Fn(t.params, t.result, done.getValue(decl)) else t).zonk()
            val type = seen(provisional.remove(decl)!!)
            // A top-level value lives as long as the program, and so do the values it holds.
            if (decl.params == null) regionsIn(type).forEach { if (it is Type.Var) u.unify(it, Type.GLOBAL) }
            // Values aren't generalized: OCaml's value restriction wouldn't generalize most of them either.
            schemes[decl] = if (decl.params == null) Scheme.mono(type) else Scheme(type.freeVars().toList(), type)
            typeParamVars[decl] = vars.filterKeys { !it.startsWith("@") }.mapValues { it.value.prune() as Type.Var }
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
        // A guarded branch may not match what its patterns do, so it covers nothing for certain.
        val patterns = e.branches.filter { it.guard == null }.flatMap { b -> if (b.patterns.isEmpty()) listOf(Pat.ANY) else b.patterns.flatMap(::coverage) }
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
        is Expr.CharLit -> Pat(Ctor.Literal("'${e.text}'"))
        is Expr.StringLit -> Pat(Ctor.Literal("\"" + e.parts.joinToString("") { (it as? StringPart.Text)?.text ?: "\$" } + "\""))
        is Expr.Unary -> Pat(Ctor.Literal(e.op + ((literal(e.operand).ctor as? Ctor.Literal)?.text ?: "")))
        else -> Pat(Ctor.Literal(e.toString()))
    }

    /** Every way of picking one from each of [choices]. */
    private fun product(choices: List<List<Pat>>): List<List<Pat>> =
        choices.fold(listOf(emptyList())) { acc, options -> acc.flatMap { prefix -> options.map { prefix + it } } }

    private fun checkDistinct(names: List<Pair<String, Pos>>) {
        names.filter { it.first != "_" }.groupBy { it.first }.values.firstOrNull { it.size > 1 }?.let {
            throw CompileError(it[1].second, "'${it[1].first}' is declared twice")
        }
    }

    /** Whether a module that [path] is in has an opaque alias, which its functions' inferred types show as what it stands for. */
    private fun opaqueAround(path: List<String>): Boolean =
        aliases.values.any { it.isOpaque && it.path.isNotEmpty() && path.take(it.path.size) == it.path }

    /**
     * After an error in [group]: its functions may be anything, so what uses them is checked
     * without more errors about them, and the checking state is as it was before it.
     */
    private fun failed(group: FunctionGroup) {
        for (decl in group.decls) {
            provisional.remove(decl)
            if (decl in schemes) continue
            // A function of as many parameters, taking and giving anything, and doing anything.
            val type = decl.params?.let { ps -> Type.Fn(ps.map { u.fresh() }, u.fresh(), u.fresh()) } ?: u.fresh()
            schemes[decl] = Scheme(type.freeVars().toList(), type)
        }
        pendingWhens.clear()
        effects = u.fresh()
        included = null
        arrowEffects = null
        typeParams = emptyMap()
    }

    private fun globalType(decl: Decl.Function): Type {
        if (!inside(decl.path)) outsideSchemes[decl]?.let { return u.instantiate(it) }
        return provisional[decl] ?: u.instantiate(schemes[decl] ?: error("${decl.name} used before it was checked"))
    }

    /** The calls whose type arguments a function took. */
    private val typed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Expr.Call, Boolean>())

    /** Each function's type parameters, by name, as the variables its type is generic in. */
    private val typeParamVars = mutableMapOf<Decl.Function, Map<String, Type.Var>>()

    /**
     * [decl]'s type for [call], which may give types for the type parameters [decl] declares, after
     * those of a method's receiver: `emptyMap<String, Int>()`.
     */
    private fun globalType(decl: Decl.Function, call: Expr.Call): Type {
        if (call.typeArgs.isEmpty()) return globalType(decl)
        typed += call
        val declared = decl.typeParams.filter { !it.startsWith("@") }.let { all ->
            val receiver = if (decl.extension) typeNamesIn(decl.params!!.first().type!!) else emptySet()
            all.dropWhile { it in receiver }
        }
        if (declared.size != call.typeArgs.size) {
            throw CompileError(call.pos, "'${decl.methodName}' takes ${declared.size} type argument${if (declared.size == 1) "" else "s"}, not ${call.typeArgs.size}")
        }
        val given = call.typeArgs.map { resolve(it) }
        val vars = typeParamVars[decl]
        val scheme = (if (!inside(decl.path)) outsideSchemes[decl] else null) ?: schemes[decl]
        if (vars == null || scheme == null) throw CompileError(call.pos, "'${decl.methodName}' can't be given type arguments here")
        return u.instantiate(scheme, declared.zip(given).mapNotNull { (name, t) -> vars[name]?.let { it to t } }.toMap())
    }

    /** The names [ref] mentions, as a receiver's type parameters: `K` and `V` in `Map<K, V>`. */
    private fun typeNamesIn(ref: TypeRef): Set<String> = when (ref) {
        is TypeRef.Named -> setOf(ref.name) + ref.args.flatMap { typeNamesIn(it) }
        is TypeRef.Function -> (ref.params + ref.result).flatMap { typeNamesIn(it) }.toSet()
        is TypeRef.Tuple -> ref.elements.flatMap { typeNamesIn(it) }.toSet()
        is TypeRef.Effects -> emptySet()
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
        var type = inferInner(e, expected, scope)
        // A call that gives Nothing never returns, so it fits wherever it is.
        if (e is Expr.Call && type.prune() == Type.NOTHING) {
            e.absurd = true
            type = u.fresh()
        }
        e.type = type
        return type
    }

    private fun inferInner(e: Expr, expected: Type?, scope: Scope): Type = when (e) {
        // Only a declaration's body or default can be, and those are trusted to be what they say.
        is Expr.Compiled -> expected ?: u.fresh()
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
        is Expr.Call -> inferCall(e, expected, scope).also {
            if (e.typeArgs.isNotEmpty() && e !in typed) throw CompileError(e.pos, "only a declared function is given type arguments, as in emptyMap<String, Int>()")
        }
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
        is Expr.If -> if (!e.hasElse) {
            check(e.condition, Type.BOOLEAN, scope)
            val then = infer(e.then, null, scope).prune()
            if (then !is Type.Var && then != Type.UNIT) {
                throw CompileError(e.pos, "an 'if' without 'else' gives Unit, but this branch gives $then: add an 'else' to give a value")
            }
            assign(e.then, then, Type.UNIT)
            infer(e.otherwise, Type.UNIT, scope)
        } else {
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
            if (it.hides) throw CompileError(e.pos, "this block's 'it' hides the 'it' outside it: name this block's parameter, { x -> ... }")
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
        // A module following a handled effect's spec is a handler; following another, a value of it.
        lookupModule(e.name, null)?.let { m ->
            moduleSpecs[m.name]?.takeIf { isEffect(it) }?.let { spec ->
                e.resolved = NameRef.Handler(m, spec)
                return moduleHandler(m, spec)
            }
            val (spec, type) = moduleValue(m, e.pos)
            e.resolved = NameRef.Module(m, spec)
            return type
        }
        if (e.name == "it") throw CompileError(e.pos, "there's no 'it' here: only a block given as a function of one parameter has one, as in xs.filter { it > 1 }")
        throw CompileError(e.pos, "unknown name '${e.name}'${importHint(e.name)}")
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
                // Unqualified, a variant is in scope inside its module, or where it's imported; through a
                // module, it's that module's.
                val viaImport = if (qualifier == null) importedCase(name, pos) else null
                val candidates = viaImport?.let(::listOf) ?: variantsByName[name].orEmpty().filter { (s, _) ->
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
            ConstructorRef.Variant(case.origin, case.variant, case.name),
            case.variant.fields.map { it.name },
            fieldTypes,
            Type.Con(home, args, region),
        )
    }

    /** The case [name] means through the current file's imports, as `Round` does with `import geometry.Shape.Circle as Round`. */
    private fun importedCase(name: String, pos: Pos): Pair<Decl.Sealed, VariantDef>? {
        val full = importedName(name, pos) ?: return null
        val sealed = sealeds[full.substringBeforeLast('.', "")] ?: return null
        val variant = sealed.variants.firstOrNull { it.name == full.substringAfterLast('.') } ?: return null
        if (!visible(sealed) || abstractHere(sealed)) return null
        return sealed to variant
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
        lifted[e]?.let { name ->
            val m = modules.getValue(name)
            val (spec, type) = moduleValue(m, e.pos)
            e.resolved = CallRef.Module(m, spec)
            return type
        }
        // A declared function, by name or through its module, is called with its parameters' names.
        val global = when (callee) {
            is Expr.Name -> if (scope.lookup(callee.name) == null) lookupGlobal(callee.name, callee.pos) else null
            is Expr.Member -> moduleMember(callee, scope)
            else -> null
        }
        if (global?.params != null) {
            val fn = globalType(global, e) as Type.Fn
            when (callee) {
                is Expr.Name -> callee.resolved = NameRef.Global(global)
                is Expr.Member -> callee.resolved = MemberRef.Global(global)
                else -> {}
            }
            callee.type = fn
            withDefaultArgs(e, global.params.map { it.name to (it.default != null) }, global.name)
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
        when (val ref = shape.ref) {
            is ConstructorRef.Record -> withDefaultArgs(e, ref.decl.fields.map { it.name to (it.default != null) }, ref.decl.name)
            is ConstructorRef.Variant -> ref.variant?.let { v ->
                withDefaultArgs(e, v.fields.map { it.name to (it.default != null) }, "${ref.sealed!!.name}$${v.name}")
            }
        }
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
        // toString() is every value's; a type's own toString takes how to show its type parameters,
        // and is called with them.
        extension(member, receiver)?.takeIf { member.name != "toString" || e.args.isNotEmpty() }?.let { decl ->
            if (decl.property) throw CompileError(member.pos, "'${member.name}' is a property; write it without ()")
            val fn = globalType(decl, e) as Type.Fn
            withDefaultArgs(e, decl.params!!.drop(1).map { it.name to (it.default != null) }, decl.name, named = false)
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
            val fn = globalType(global, e) as Type.Fn
            assign(member.target, receiver, fn.params[0])
            checkArgs(e.args, e.args.indices.toList(), fn.params.drop(1), scope)
            e.resolved = CallRef.Extension(global)
            perform(fn.effects, e.pos)
            return fn.result
        }
        if (receiver is Type.Var) throw CompileError(member.pos, "can't tell what '${member.name}' is: the receiver's type isn't known here; annotate it")
        throw CompileError(member.pos, "$receiver has no method '${member.name}'${methodImportHint(member.name, receiver)}")
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
        // What a module given as a value has, as its spec says.
        if (receiver.name.startsWith(Type.MODULE)) {
            val spec = specs.getValue(receiver.name.removePrefix(Type.MODULE))
            val value = spec.values.firstOrNull { it.name == member.name } ?: return null
            return MemberRef.OfModule(spec, value) to u.instantiate(specSchemes.getValue(spec).getValue(value.name))
        }
        val record = records[receiver.name] ?: return null
        val index = record.fields.indexOfFirst { it.name == member.name }
        if (index < 0) return null
        if (abstractHere(record)) {
            // A field that can't be seen here isn't what the name means, when an extension of that name is.
            if (extension(member, receiver) != null) return null
            abstractError(record, member.pos)
        }
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
        // `Console.print` as a value, as in a handler made for a copy of Console.
        if (e.target is Expr.Name && scope.lookup((e.target as Expr.Name).name) == null) {
            qualifiedOperation(e, scope)?.let { (spec, op) ->
                e.resolved = MemberRef.Operation(spec, op)
                return operationValue(spec, op, e.pos)
            }
        }
        // `reporting.Quiet`: a module through its package, as a value.
        moduleOf(e, scope)?.takeIf { m -> moduleSpecs[m.name]?.let { isEffect(it) } != true }?.let { m ->
            val (spec, type) = moduleValue(m, e.pos)
            e.resolved = MemberRef.Module(m, spec)
            return type
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
        throw noField(e, receiver)
    }

    /** That [receiver] has no field [e] names; if some of its cases do, which, and how to get at it. */
    private fun noField(e: Expr.Member, receiver: Type): CompileError {
        val sealed = (receiver.prune() as? Type.Con)?.let { sealeds[it.name] }
        val having = sealed?.variants?.filter { v -> v.fields.any { it.name == e.name } }.orEmpty()
        if (having.isEmpty() || e.target !is Expr.Name) return CompileError(e.pos, "$receiver has no field '${e.name}'")
        val name = (e.target as Expr.Name).name
        return CompileError(
            e.pos,
            "only ${having.joinToString(" and ") { it.name }} of ${sealed!!.simpleName} has ${e.name}: check which it is first, " +
                "as in when ($name) { is ${having.first().name} -> ... $name.${e.name} ... }",
        )
    }

    /**
     * The extension [member] calls on a [receiver]: of those with its name, the one whose receiver
     * type fits, or the most specific, as `List<Int>.sum` is over `List<A>.sum`.
     */
    private fun extension(member: Expr.Member, receiver: Type): Decl.Function? {
        val candidates = extensions[member.name].orEmpty().filter { visible(it) && inScope(it) }
        // As in Kotlin, the receiver's type must be known to choose; the caller says so if it isn't.
        if (candidates.isEmpty() || receiver.prune() is Type.Var) return null
        val fitting = candidates.mapNotNull { d -> fit(receiverType(d), receiver)?.let { d to it } }
        if (fitting.isEmpty()) return null
        val best = fitting.maxOf { it.second }
        // As one's own declarations come before what's imported: the core's, which every file has, last.
        val chosen = fitting.filter { it.second == best }.let { tied ->
            tied.filter { (d, _) -> d.pos.file?.startsWith(uk.m4xy.fckt.CORE_PATH) != true }.ifEmpty { tied }
        }
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
        val savedFile = currentFile
        currentPath = d.path
        currentFile = d.pos.file
        typeParams = d.typeParams.associateWith { u.fresh() }
        try {
            return resolve(d.params!!.first().type!!)
        } finally {
            currentPath = saved.first
            currentFile = savedFile
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
        // A block given as a function's last argument: what it's written with must be what's wanted,
        // and with none, a function of one parameter calls it `it`.
        if (e.trailing && expectedFn != null) {
            val wanted = expectedFn.params.size
            if (e.params.isNotEmpty() && wanted == 0) {
                throw CompileError(e.pos, "this block takes no parameters, as it's run with none: to give back a function, write { (${e.params.joinToString(", ") { it.name }} -> ...) }")
            }
            if (e.params.isEmpty() && wanted > 1) {
                throw CompileError(e.pos, "this block is given $wanted parameters, so it has no 'it': name them, { ${('a'..'z').take(wanted).joinToString(", ")} -> ... }")
            }
            if (e.params.isEmpty() && wanted == 1) {
                val inner = Scope(scope)
                val type = expectedFn.params[0]
                inner.vars["it"] = Local(type, "it", hides = scope.lookup("it") != null)
                e.paramNames = listOf("it")
                doing(row) { check(e.body, expectedFn.result, inner) }
                return Type.Fn(listOf(type), expectedFn.result, row)
            }
        }
        checkDistinct(e.params.map { it.name to it.pos })
        // Several parameters where one is expected take apart a tuple, as `pairs.map((k, v) -> ...)`.
        if (expectedFn != null && expectedFn.params.size == 1 && e.params.size > 1) {
            val tuple = expectedFn.params[0].prune()
            val parts = if (tuple is Type.Con && tuple.name == Type.TUPLE) {
                if (tuple.args.size != e.params.size) throw CompileError(e.pos, "this function is given a tuple of ${tuple.args.size}, so it takes ${tuple.args.size} parameters, not ${e.params.size}")
                tuple.args
            } else {
                List(e.params.size) { u.fresh() }.also { expect(tuple, Type.Con(Type.TUPLE, it), e.pos) }
            }
            val inner = Scope(scope)
            e.params.forEachIndexed { i, p ->
                p.type?.let { withArrowEffects(row) { resolve(it) } }?.let { expect(parts[i], it, p.pos) }
                inner.vars[p.name] = Local(parts[i], snake(p.name))
            }
            e.paramNames = listOf(e.params.joinToString(", ", "(", ")") { snake(it.name) })
            doing(row) { check(e.body, expectedFn.result, inner) }
            return Type.Fn(listOf(tuple), expectedFn.result, row)
        }
        val hint = expectedFn?.takeIf { it.params.size == e.params.size }
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
                // The guard sees what the patterns bound, and what `is` narrowed.
                branch.guard?.let { check(it, Type.BOOLEAN, inner) }
                body(branch, inner)
                // `is Vowel` rebinds the subject only if the branch uses it.
                if (subject is Expr.Name) {
                    val refs = mutableSetOf<String>()
                    collectRefs(branch.body, emptySet(), refs)
                    branch.guard?.let { collectRefs(it, emptySet(), refs) }
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
            ?: throw CompileError(e.module.pos, "${name ?: "this"} isn't a module, so $name { ... } has nothing to bind with${importHint(name)}")
        // A module following a handled effect's spec handles the block instead.
        moduleSpecs[module.name]?.takeIf { isEffect(it) }?.let { spec ->
            e.handles = spec
            e.handler = module
            val handler = moduleHandler(module, spec) as Type.Con
            return handling(spec, emptyList(), handler.args[0], handler.args[1], e.block, null, scope, e.pos)
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
