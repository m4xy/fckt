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
import uk.m4xy.fckt.syntax.SpecValue
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

/** OCaml's name for fckt's Nothing, the type with no values. */
private const val NOTHING = "fk_nothing"

/** The type variable for what a handled block gives: a handler's answer. */
private const val ANSWER = "'fk_answer"

/** Type parameters OCaml has: all but effect parameters, `@E`, which only the checker uses. */
private fun List<String>.ocaml() = filter { !it.startsWith("@") }

/** Translates a checked program into the Kastor-built OCaml tree. */
class Lowering(private val program: Program) {
    private val records = program.records.associateBy { it.name }
    private val sealeds = program.sealeds.associateBy { it.name }
    private val aliases = program.aliases.associateBy { it.name }

    /** Each functor parameter, as the module it stands for: `SortedSet.M`. */
    private val parameterModules = program.functorParams.flatMap { (f, params) -> params.map { "$f.${it.first}" } }.toSet()

    private fun inApplication(path: List<String>) = (1..path.size).any { path.take(it).joinToString(".") in program.applications }

    /** User types whose show function is needed, and the `fk_` helpers that are. */
    private val showTypes = linkedSetOf<String>()
    private val helpers = linkedSetOf<String>()

    /** The module being lowered: names are written relative to it. */
    private var currentPath: List<String> = emptyList()

    /** Show functions for user types, by type, once generated. */
    private val showBodies = linkedMapOf<String, Pair<List<String>, Gen>>()

    fun lower(): StructureElement<Unit> {
        // A spec gives each of its types a show function, so every module that follows it has one,
        // as does every module given to a functor.
        for ((module, spec) in program.moduleSpecs) {
            if (module !in parameterModules) spec.types.forEach { showTypes += "$module.${it.name}" }
        }
        for ((functor, args) in program.applications.values) {
            program.functorParams.getValue(functor).zip(args).forEach { (param, arg) -> param.second.types.forEach { showTypes += "$arg.${it.name}" } }
        }
        // Lower every function first; that finds which show functions are needed. A module made by a
        // functor is the functor's OCaml, so its own copies aren't.
        val functionItems = program.groups.filter { g -> !inApplication(g.decls.first().path) }.associateWith { group ->
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
        val main = program.main?.let {
            StructureElement.build<Unit> {
                let(false) {
                    binding("()") {
                        // The command-line arguments, without the program name.
                        val args = if (it.params!!.isEmpty()) Gen.Lit("()") else Gen.App(Gen.Id("List.tl"), listOf(Gen.App(Gen.Id("Array.to_list"), listOf(Gen.Id("Sys.argv")))))
                        // In the default handler of each effect it does that needs one.
                        val run = program.mainHandlers.fold<Pair<Decl.Spec, Decl.Module>, Gen>(Gen.App(Gen.Id("main"), listOf(args))) { inner, (spec, module) ->
                            Gen.App(Gen.Id(nextTo(spec, handleName(spec))), listOf(moduleHandler(module, spec), Gen.Fun(listOf("()"), inner)))
                        }
                        gen(run)
                    }
                }
            }.children
        }.orEmpty()
        // After the rest, which says which helpers it needs.
        val items = moduleItems(emptyList(), program.root, functionItems)
        val helperItems = StructureElement.build<Unit> { if (helpers.isNotEmpty()) helperFunctions() }.children
        return StructureElement(helperItems + items + main)
    }

    // ---- Handled effects ----

    /** An operation's OCaml effect constructor: `Console_println`. */
    private fun effectName(spec: Decl.Spec, op: SpecValue) = "${spec.simpleName}_${op.name}"

    /** [local], declared next to [spec], as named from here. */
    private fun nextTo(spec: Decl.Spec, local: String) = ref((spec.path + local).joinToString("."), { it })

    private fun handlerType(spec: Decl.Spec) = snake(spec.simpleName).removeSuffix("_") + "_handler"
    private fun handleName(spec: Decl.Spec) = "handle_" + snake(spec.simpleName).removeSuffix("_")

    /** The handler record's type, as named from here: `_ console_handler`, or `(_, _) yielding_handler`. */
    private fun handlerTypeText(spec: Decl.Spec): String =
        (if (spec.typeParams.isEmpty()) "_ " else (spec.typeParams + ANSWER).joinToString(", ", "(", ") ") { "_" }) + nextTo(spec, handlerType(spec))

    private fun operationParams(spec: Decl.Spec, op: SpecValue): List<Pair<String, Boolean>> =
        op.params.orEmpty().map { typeText(it.type!!, spec.typeParams + op.typeParams) to compound(it.type!!) }

    private fun operationResult(spec: Decl.Spec, op: SpecValue) = typeText(op.type, spec.typeParams + op.typeParams)

    /**
     * A handled effect in OCaml 5: an effect constructor for each operation, a record type for its
     * handlers, and a function that runs a block with one. The record has a function for each
     * operation, which takes the operation's arguments and then `resume`, and gives what the block
     * does: its [ANSWER]. One that doesn't take resume in fckt is resumed with what it gives.
     */
    private fun StructureScope<Unit>.handledEffect(spec: Decl.Spec) {
        val generic = spec.typeParams.isNotEmpty()
        effects {
            constructors = spec.values.map { op ->
                val params = operationParams(spec, op)
                val result = operationResult(spec, op).let { if (compound(op.type)) "($it)" else it }
                "${effectName(spec, op)} : ${if (params.isEmpty()) "unit" else tupleText(params)} -> $result Effect.t"
            }
        }
        types {
            typeDef(handlerType(spec)) {
                params = spec.typeParams.map(::typeVar) + ANSWER
                for (op in spec.values) {
                    val result = operationResult(spec, op)
                    val type = if (op.params == null) result else (operationParams(spec, op) + ("$result -> $ANSWER" to true)).joinToString(" -> ") { (text, paren) -> if (paren) "($text)" else text } + " -> $ANSWER"
                    fieldDecl(snake(op.name), type)
                }
            }
        }
        let(false) {
            binding(handleName(spec)) {
                val vars = (spec.typeParams.map(::typeVar) + ANSWER).let { if (it.size == 1) it[0] + " " else it.joinToString(", ", "(", ") ") }
                params = listOf("(h : $vars${handlerType(spec)})", "(f : unit -> $ANSWER)")
                returnType = ANSWER
                // A generic effect's values are what the checker made sure they are, but OCaml can't see that.
                fun known(g: Gen) = if (generic) Gen.App(Gen.Id("Obj.magic"), listOf(g)) else g
                val cases = spec.values.map { op ->
                    val names = op.params.orEmpty().indices.map { "x$it" }
                    val pattern = when (names.size) {
                        0 -> "()"
                        1 -> names[0]
                        else -> names.joinToString(", ", "(", ")")
                    }
                    val field = Gen.Field(Gen.Id("h"), snake(op.name))
                    val answer = if (op.params == null) {
                        Gen.App(Gen.Id("Effect.Deep.continue"), listOf(Gen.Id("k"), known(field)))
                    } else {
                        val resume = Gen.Fun(listOf("v"), Gen.App(Gen.Id("Effect.Deep.continue"), listOf(Gen.Id("k"), known(Gen.Id("v")))))
                        Gen.App(field, names.map { known(Gen.Id(it)) } + resume)
                    }
                    "effect (${effectName(spec, op)} $pattern), k" to answer
                }
                gen(Gen.Match(Gen.App(Gen.Id("f"), listOf(Gen.Lit("()"))), listOf("v" to Gen.Id("v")) + cases))
            }
        }
    }

    /** Performing [op] with [args]: whatever handles its effect answers. */
    private fun perform(spec: Decl.Spec, op: SpecValue, args: List<Gen>): Gen {
        val arg = when (args.size) {
            0 -> Gen.Lit("()")
            1 -> args[0]
            else -> Gen.Tuple(args)
        }
        return Gen.App(Gen.Id("Effect.perform"), listOf(Gen.Ctor(nextTo(spec, effectName(spec, op)), arg)))
    }

    /** An operation as a value: a function that performs it, or, for one that isn't a function, performing it. */
    private fun operationValue(spec: Decl.Spec, op: SpecValue): Gen {
        val params = op.params ?: return perform(spec, op, emptyList())
        val names = params.indices.map { "x$it" }
        return Gen.Fun(names.ifEmpty { listOf("()") }, perform(spec, op, names.map { Gen.Id(it) }))
    }

    /** A module following a handled effect's spec, as a handler: a record of its functions, each resumed with what it gives. */
    private fun moduleHandler(module: Decl.Module, spec: Decl.Spec): Gen =
        Gen.Ann(Gen.Record(spec.values.map { op -> snake(op.name) to answering(op, Gen.Id(ref("${module.name}.${op.name}", ::snake)), false) }), handlerTypeText(spec))

    /**
     * [answer], as [op]'s field of a handler record: as it is if it [resumes], or if [op] is a
     * value; otherwise a function that resumes with what it gives.
     */
    private fun answering(op: SpecValue, answer: Gen, resumes: Boolean): Gen {
        if (resumes || op.params == null) return answer
        val names = op.params.indices.map { "x$it" }
        val given = Gen.App(answer, names.ifEmpty { listOf("()") }.map { Gen.Id(it) })
        return Gen.Fun(names + "k", Gen.App(Gen.Id("k"), listOf(given)))
    }

    /** Running [block] with [handler] handling [spec]. */
    private fun handle(spec: Decl.Spec, handler: Gen, block: Expr): Gen =
        Gen.App(Gen.Id(nextTo(spec, handleName(spec))), listOf(handler, Gen.Fun(listOf("()"), Gen.Src(block))))

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
        val types = decls.filter { isType(it) }
        val specs = decls.filterIsInstance<Decl.Spec>()
        val modules = decls.filterIsInstance<Decl.Module>()
        val groups = program.groups.filter { it.decls.first().path == path }

        // Graph nodes, in the order they'd be emitted given no constraints: types, specs, modules, functions.
        val typeGroups = typeGroups(types)
        val nodes: List<Any> = typeGroups + specs + modules + groups
        fun insideOf(m: Decl.Module, name: String) = name.startsWith(m.name + ".")
        val edges = nodes.associateWith { node ->
            when (node) {
                is Decl.Module -> {
                    // A module comes after the functions and modules here that its own functions
                    // use, and the specs it and the modules in it follow.
                    val used = functionsIn(node).flatMap { program.uses[it].orEmpty() }
                    val followed = modulesIn(node).mapNotNull { program.moduleSpecs[it.name] }
                    // A module made by a functor comes after the functor and the modules it's given.
                    val made = program.applications[node.name]?.let { (functor, args) -> listOf(functor) + args }.orEmpty()
                    groups.filter { g -> g.decls.any { it in used } } +
                        modules.filter { m -> m !== node && (used.any { insideOf(m, it.name) } || made.any { it == m.name || insideOf(m, it) }) } +
                        specs.filter { it in followed }
                }
                is Decl.Spec -> {
                    // A spec comes after the modules whose types it uses.
                    val names = specTypeRefs(node)
                    modules.filter { m -> names.any { insideOf(m, it) } }
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
                is Decl.Spec -> StructureElement.build<Unit> {
                    moduleType(node.simpleName) { specItems(node) }
                    if (node in program.effects) handledEffect(node)
                }.children
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
        program.applications[m.name]?.let { (functor, args) ->
            currentPath = path.dropLast(1)
            val applies = (listOf(ref(functor) { it }) + args.map { "(${ref(it) { name -> name }})" }).joinToString(" ")
            return ModuleDefElement(name = uk.m4xy.kastor.Attribute.Value(moduleName(m.simpleName)), applies = uk.m4xy.kastor.Attribute.Value(applies))
        }
        currentPath = path.dropLast(1)
        val params = program.functorParams[m.name]?.joinToString(" ") { (name, spec) -> "($name : ${specText("${m.name}.$name", spec)})" }
        val items = moduleItems(path, m.decls, functionItems).map { child ->
            when (child) {
                is StructureChild.TypeGroup -> ModuleDefChild.TypeGroup(child.element)
                is StructureChild.LetGroup -> ModuleDefChild.LetGroup(child.element)
                is StructureChild.Comment -> ModuleDefChild.Comment(child.element)
                is StructureChild.ModuleDef -> ModuleDefChild.ModuleDef(child.element)
                is StructureChild.ModuleType -> ModuleDefChild.ModuleType(child.element)
                is StructureChild.Effects -> ModuleDefChild.Effects(child.element)
            }
        }
        // A module with private members or opaque types states what it shows; otherwise OCaml
        // infers it. A spec is checked already, so it hides nothing.
        currentPath = path
        val signature = if (hasHidden(m)) {
            listOf(ModuleDefChild.Signature(SignatureElement.build<Unit> { signatureItems(m) }))
        } else {
            emptyList()
        }
        return ModuleDefElement(
            name = uk.m4xy.kastor.Attribute.Value(moduleName(m.simpleName)),
            params = params?.let { uk.m4xy.kastor.Attribute.Value(it) },
            children = signature + items,
        )
    }

    /**
     * The spec [module] follows, as written from here: `Ordered`, or for `Ordered<Int>`,
     * `Ordered with type t = int`.
     */
    private fun specText(module: String, spec: Decl.Spec): String {
        val fixed = spec.types.mapNotNull { t ->
            program.fixedTypes["$module.${t.name}"]?.let { alias ->
                val params = when (t.typeParams.size) {
                    0 -> ""
                    1 -> typeVar(t.typeParams[0]) + " "
                    else -> t.typeParams.joinToString(", ", "(", ") ") { typeVar(it) }
                }
                "type $params${snake(t.name)} = ${typeText(alias.target, alias.typeParams)}"
            }
        }
        val name = ref(spec.name) { it }
        return if (fixed.isEmpty()) name else "$name with ${fixed.joinToString(" and ")}"
    }

    /** `module type Spec = sig ... end`: the spec's types, abstract, with their show functions, then its values. */
    private fun <S> S.specItems(spec: Decl.Spec) where S : TypeGroupParent<Unit>, S : SigValueParent<Unit> {
        for (t in spec.types) types { typeDef(snake(t.name)) { params = t.typeParams.map(::typeVar) } }
        for (t in spec.types) {
            val shown = applied(Type.SPEC_TYPE + t.name, t.typeParams.map { typeVar(it) to false })
            sigValue(showName(t.name), (t.typeParams.map { "(${typeVar(it)} -> string)" } + shown + "string").joinToString(" -> "))
        }
        val types = program.specTypes.getValue(spec)
        for (v in spec.values) sigValue(snake(v.name), typeText(types.getValue(v.name), mutableMapOf()))
    }

    /** The full names of the user types a spec's values use. */
    private fun specTypeRefs(spec: Decl.Spec): List<String> =
        spec.values.flatMap { v -> typeRefNames(v.type) + v.params.orEmpty().flatMap { p -> p.type?.let(::typeRefNames).orEmpty() } }

    /** A type declaration to write out: an external type is OCaml's already. */
    private fun isType(d: Decl) = (d is Decl.Record && d.external == null) || d is Decl.Sealed || d is Decl.Alias

    private fun modulesIn(m: Decl.Module): List<Decl.Module> = listOf(m) + m.decls.filterIsInstance<Decl.Module>().flatMap { modulesIn(it) }

    private fun hasHidden(m: Decl.Module): Boolean = m.decls.any {
        it.isPrivate || it.isOpaque || (it is Decl.Record && it.privateConstructor) || (it is Decl.Module && hasHidden(it))
    }

    /** The functions in [m], and in the modules inside it, including those a functor made for it. */
    private fun functionsIn(m: Decl.Module): List<Decl.Function> =
        program.groups.flatMap { it.decls }.filter { it.name.startsWith(m.name + ".") }

    /** The full names of the user types written in [ref]. */
    private fun typeRefNames(ref: TypeRef): List<String> = when (ref) {
        is TypeRef.Named -> listOfNotNull(program.typeNames[ref]) + ref.args.flatMap(::typeRefNames)
        is TypeRef.Function -> ref.params.flatMap(::typeRefNames) + typeRefNames(ref.result)
        is TypeRef.Tuple -> ref.elements.flatMap(::typeRefNames)
        is TypeRef.Effects -> emptyList()
    }

    /** The full names of the user types a type declaration's fields use. */
    private fun typeRefsOf(d: Decl): List<String> {
        if (d is Decl.Alias) return typeRefNames(d.target)
        val fields = when (d) {
            is Decl.Record -> d.fields
            is Decl.Sealed -> program.cases.getValue(d.name).flatMap { it.fields }
            else -> emptyList()
        }
        val origins = (d as? Decl.Sealed)?.let { s -> program.cases.getValue(s.name).map { it.origin.name } }.orEmpty()
        return fields.flatMap { typeRefNames(it.type) } + origins
    }

    /** What module [m] shows outside: its public types, nested modules, show functions and values. */
    private fun <S> S.signatureItems(m: Decl.Module) where
        S : TypeGroupParent<Unit>, S : SigValueParent<Unit>, S : SigModuleParent<Unit>, S : ModuleTypeParent<Unit> {
        val path = m.name.split('.')
        currentPath = path
        val types = m.decls.filter { isType(it) && !it.isPrivate }
        for (group in typeGroups(types)) types { typeDefinitions(group, forSignature = true) }
        for (spec in m.decls.filterIsInstance<Decl.Spec>().filter { !it.isPrivate }) moduleType(spec.simpleName) { specItems(spec) }
        for (inner in m.decls.filterIsInstance<Decl.Module>().filter { !it.isPrivate }) {
            sigModule(moduleName(inner.simpleName)) { signatureItems(inner) }
            currentPath = path
        }
        for (type in types) {
            if (type.name !in showBodies) continue
            val params = typeParamsOf(type)
            val shown = applied(type.name, params.map { typeVar(it) to false })
            sigValue(showName(type.name), (params.map { "(${typeVar(it)} -> string)" } + shown + "string").joinToString(" -> "))
        }
        for (f in m.decls.filterIsInstance<Decl.Function>().filter { !it.isPrivate && it.external == null }) {
            val type = program.outsideTypes[f] ?: program.functionTypes.getValue(f)
            sigValue(snake(f.simpleName), typeText(type, mutableMapOf()))
        }
    }

    /** [full] as written from the current module: `Ratios.make` is `make` inside Ratios, and `Ratios.make` outside. */
    private fun ref(full: String, transform: (String) -> String): String {
        val parts = full.split('.')
        val module = parts.dropLast(1)
        val common = module.zip(currentPath).takeWhile { (a, b) -> a == b }.size
        return (module.drop(common).map(::moduleName) + transform(parts.last())).joinToString(".")
    }

    /** A module's OCaml name, which must be capitalised: a package's folder, `geometry`, is `Geometry`. */
    private fun moduleName(name: String) = name.replaceFirstChar { it.uppercaseChar() }

    /** What to put before a record's labels from here: `Ratios.` outside Ratios, else nothing. */
    private fun labelPrefix(record: Decl.Record): String = ref((record.path + "x").joinToString("."), { "" })

    // ---- Types ----

    /** [d]'s type parameters in OCaml, or [all] of them, with its effect parameters. */
    private fun typeParamsOf(d: Decl, all: Boolean = false): List<String> = when (d) {
        is Decl.Record -> d.typeParams
        is Decl.Sealed -> d.typeParams
        is Decl.Alias -> d.typeParams
        else -> emptyList()
    }.let { if (all) it else it.ocaml() }

    /**
     * A group of type definitions; [forSignature] makes a record with a private constructor
     * `private`, and an opaque type abstract.
     */
    private fun TypeGroupScope<Unit>.typeDefinitions(group: List<Decl>, forSignature: Boolean) {
        for (decl in group) {
            if (forSignature && decl.isOpaque) {
                typeDef(snake(decl.simpleName)) { params = typeParamsOf(decl).map(::typeVar) }
                continue
            }
            when (decl) {
                is Decl.Record -> typeDef(snake(decl.simpleName)) {
                    params = decl.typeParams.ocaml().map(::typeVar)
                    if (forSignature && decl.privateConstructor) isPrivate = true
                    for (field in decl.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams.ocaml())) { if (field.isVar) isMutable = true }
                }
                is Decl.Sealed -> if (decl.name in program.polymorphic) {
                    typeDef(snake(decl.simpleName)) {
                        params = decl.typeParams.ocaml().map(::typeVar)
                        polymorphic = true
                        for (case in program.cases.getValue(decl.name)) {
                            variant(tagName(case.origin, case.variant)) {
                                for (field in case.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams.ocaml()))
                            }
                        }
                    }
                } else typeDef(snake(decl.simpleName)) {
                    params = decl.typeParams.ocaml().map(::typeVar)
                    for (variant in decl.variants) {
                        variant(variant.name) {
                            for (field in variant.fields) fieldDecl(snake(field.name), typeText(field.type, decl.typeParams.ocaml())) { if (field.isVar) isMutable = true }
                        }
                    }
                }
                is Decl.Alias -> typeDef(snake(decl.simpleName)) {
                    params = decl.typeParams.ocaml().map(::typeVar)
                    alias = typeText(decl.target, decl.typeParams.ocaml())
                }
                is Decl.Function, is Decl.Module, is Decl.Spec -> {}
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
            else applied(program.typeNames[ref] ?: ref.name, ref.args.filter { it !is TypeRef.Effects }.map { typeText(it, typeParams) to compound(it) })
        is TypeRef.Effects -> error("effects aren't an OCaml type")
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
            else applied(t.name, ocamlArgs(t.name, t.args).map { typeText(it, vars) to compound(it) })
    }

    private fun functionText(params: List<Pair<String, Boolean>>, result: String): String {
        val ps = if (params.isEmpty()) listOf("unit") else params.map { (text, paren) -> if (paren) "($text)" else text }
        return (ps + result).joinToString(" -> ")
    }

    /** [args] of the type [name] without those for its effect parameters, which OCaml doesn't have. */
    private fun <T> ocamlArgs(name: String, args: List<T>): List<T> {
        val params = (records[name] ?: sealeds[name] ?: aliases[name])?.let { typeParamsOf(it, all = true) } ?: return args
        return if (params.size == args.size) args.filterIndexed { i, _ -> !params[i].startsWith("@") } else args
    }

    private fun applied(name: String, args: List<Pair<String, Boolean>>): String {
        // An external type is its OCaml, with each type parameter P, written 'p, replaced.
        records[name]?.external?.let { ocaml ->
            return records.getValue(name).typeParams.zip(args).fold(ocaml) { text, (param, arg) ->
                text.replace(Regex("'${Regex.escape(param.lowercase())}\\b"), Regex.escapeReplacement(if (arg.second) "(${arg.first})" else arg.first))
            }
        }
        val base = when (name) {
            "Int" -> "int"; "Float" -> "float"; "String" -> "string"; "Boolean" -> "bool"; "Unit" -> "unit"; "Nothing" -> helper(NOTHING)
            "Char" -> "char"; "List" -> "list"; "Option" -> "option"; "Lazy" -> "Lazy.t"; "Result" -> "result"
            // A spec's own type, inside its module type.
            else -> if (name.startsWith(Type.SPEC_TYPE)) snake(name.removePrefix(Type.SPEC_TYPE)) else ref(name.removePrefix(Type.ABSTRACT), ::snake)
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
        bound[e]?.let { return ident(it) }
        // OCaml runs arguments right to left, and Kotlin left to right: where more than one runs
        // code, each is bound first, in order, so effects happen in the order they're written.
        val parts = runInOrder(e).filter { runs(it) && it !in bound }
        if (parts.size >= 2) return bindInOrder(e, parts, 0)
        // A narrower sealed type used as a wider one: (v :> alphabet).
        val widened = e.coerceTo ?: return exprUnwidened(e)
        annotated(typeText(widened)) {
            coerce = true
            exprUnwidened(e)
        }
    }

    /** Arguments already computed, in order, by [bindInOrder]: the name each is bound to. */
    private val bound = java.util.IdentityHashMap<Expr, String>()
    private var boundCount = 0

    /** `let fk_arg1 = a in let fk_arg2 = b in e`, with [e] using those names for [parts]. */
    private fun <S> S.bindInOrder(e: Expr, parts: List<Expr>, from: Int) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        if (from == parts.size) {
            expr(e)
            parts.forEach { bound.remove(it) }
            return
        }
        val name = "fk_arg${++boundCount}"
        letIn(name) {
            expr(parts[from])
            bound[parts[from]] = name
            bindInOrder(e, parts, from + 1)
        }
    }

    /** The parts of [e] Kotlin computes before it, in the order it does. */
    private fun runInOrder(e: Expr): List<Expr> = when (e) {
        is Expr.Call -> when (e.resolved) {
            is CallRef.Extension, is CallRef.Builtin, is CallRef.Copy -> listOf((e.callee as Expr.Member).target)
            else -> emptyList()
        } + e.args.map { it.value }
        is Expr.Tuple -> e.elements
        is Expr.ListLit -> e.items.map { if (it is Expr.Spread) it.list else it }
        is Expr.Binary -> if (e.op == "&&" || e.op == "||") emptyList() else listOf(e.left, e.right)
        is Expr.StringLit -> e.parts.filterIsInstance<StringPart.Interpolated>().map { it.expr }
        else -> emptyList()
    }

    /** Whether computing [e] may run code, which could do something: anything but values and what's made of them. */
    private fun runs(e: Expr): Boolean = when (e) {
        is Expr.Name, is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit, is Expr.Lambda -> false
        is Expr.StringLit -> e.parts.any { it is StringPart.Interpolated && runs(it.expr) }
        is Expr.Member -> e.resolved !is MemberRef.Field || runs(e.target)
        is Expr.Call -> e.resolved !is CallRef.Construct || e.args.any { runs(it.value) }
        is Expr.Tuple -> e.elements.any(::runs)
        is Expr.ListLit -> e.items.any { runs(if (it is Expr.Spread) it.list else it) }
        is Expr.Binary -> runs(e.left) || runs(e.right)
        is Expr.Unary -> runs(e.operand)
        is Expr.If -> runs(e.condition) || runs(e.then) || runs(e.otherwise)
        else -> true
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
                // A var is a ref: what it holds is `!x`.
                is NameRef.Local -> if (r.isVar) apply { ident("!"); ident(r.ocamlName) } else ident(r.ocamlName)
                is NameRef.Operation -> gen(operationValue(r.spec, r.operation))
                is NameRef.Handler -> gen(moduleHandler(r.module, r.spec))
                is NameRef.Global -> if (r.decl.external != null) gen(externalValue(r.decl)) else ident(ref(r.decl.name, ::snake))
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
            // A call that never returns: OCaml is told it gives whatever is wanted.
            is Expr.Call -> if (e.absurd) {
                match {
                    call(e)
                    case("_") { literal("assert false") }
                }
            } else {
                call(e)
            }
            is Expr.Member -> when (val r = e.resolved!!) {
                is MemberRef.Field -> fieldGet(labelPrefix(records.getValue(r.record)) + r.ocamlName) { gen(qualified(Gen.Src(e.target), r)) }
                is MemberRef.CastField -> fieldGet(r.ocamlName) { ident(r.recordVar) }
                is MemberRef.CastTagField -> ident(r.variable)
                is MemberRef.Operation -> gen(operationValue(r.spec, r.operation))
                is MemberRef.Global -> if (r.decl.external != null) gen(externalValue(r.decl)) else ident(ref(r.decl.name, ::snake))
                is MemberRef.Extension -> {
                    if (r.decl.external != null) gen(external(r.decl, listOf(Gen.Src(e.target))))
                    else apply {
                        ident(ref(r.decl.name, ::snake))
                        expr(e.target)
                    }
                }
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
            is Expr.Assign -> when (val target = e.target) {
                is Expr.Name -> infix(":=") {
                    ident((target.resolved as NameRef.Local).ocamlName)
                    expr(e.value)
                }
                else -> infix("<-") {
                    expr(target)
                    expr(e.value)
                }
            }
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
            is Expr.Block -> block(e, 0, null)
            is Expr.Handle -> gen(e.call?.let { Gen.Src(it) } ?: handle(e.handles!!, Gen.Src(e.handler), e.block))
            is Expr.Monadic -> if (e.handles != null) gen(handle(e.handles!!, moduleHandler(e.handler!!, e.handles!!), e.block)) else {
                // OCaml's binding operators: let* x = v in rest is bind v (fun x -> rest), and
                // let+ a = x and+ b = y in rest is map (product x y) (fun (a, b) -> rest).
                val steps = e.block.statements.count { it is Statement.Bind }
                when {
                    steps == 0 -> block(e.block, 0, e)
                    e.bind != null -> letIn("( let* )") {
                        ident(ref(e.bind!!.name, ::snake))
                        block(e.block, 0, e)
                    }
                    else -> letIn("( let+ )") {
                        ident(ref(e.map!!.name, ::snake))
                        if (steps == 1) {
                            block(e.block, 0, e)
                        } else {
                            letIn("( and+ )") {
                                ident(ref(e.product!!.name, ::snake))
                                block(e.block, 0, e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun <S> S.call(e: Expr.Call) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        val builtin = ((e.callee as? Expr.Name)?.resolved as? NameRef.Builtin)?.ocaml
        if (builtin == ASSERT || builtin == ASSERT_EQUALS) return gen(assertion(e, builtin))
        val global = ((e.callee as? Expr.Name)?.resolved as? NameRef.Global)?.decl ?: ((e.callee as? Expr.Member)?.resolved as? MemberRef.Global)?.decl
        if (global?.external != null) {
            return gen(external(global, (e.resolved as CallRef.Function).order.map { Gen.Src(e.args[it].value) }))
        }
        // An operation of a handled effect: performed, for whatever handles it to answer.
        val operation = ((e.callee as? Expr.Name)?.resolved as? NameRef.Operation)?.let { it.spec to it.operation }
            ?: ((e.callee as? Expr.Member)?.resolved as? MemberRef.Operation)?.let { it.spec to it.operation }
        if (operation != null) {
            return gen(perform(operation.first, operation.second, (e.resolved as CallRef.Function).order.map { Gen.Src(e.args[it].value) }))
        }
        when (val r = e.resolved!!) {
            is CallRef.Handler -> gen(
                Gen.Ann(
                    Gen.Record(r.spec.values.mapIndexed { i, op -> snake(op.name) to answering(op, Gen.Src(e.args[r.order[i]].value), r.resumes[i]) }),
                    handlerTypeText(r.spec),
                ),
            )
            is CallRef.Function -> apply {
                expr(e.callee)
                if (r.order.isEmpty()) literal("()")
                r.order.forEach { expr(e.args[it].value) }
            }
            is CallRef.Construct -> gen(construct(r.ref, e.type!!, r.order.map { Gen.Src(e.args[it].value) }))
            is CallRef.Extension -> {
                val args = listOf((e.callee as Expr.Member).target) + e.args.map { it.value }
                if (r.decl.external != null) return gen(external(r.decl, args.map { Gen.Src(it) }))
                apply {
                    ident(ref(r.decl.name, ::snake))
                    args.forEach { expr(it) }
                }
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
        // Another file's assertions say which file; the program's own just the line.
        val where = (e.pos.file?.let { java.io.File(it).name + " " } ?: "") + "line ${e.pos.line}"
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

    /**
     * An external's OCaml, [decl]'s template with its parameters replaced by [args]: `List.map f
     * this` applies List.map to the arguments for f and this. `~name` passes one as a labelled
     * argument; anything else is OCaml as written.
     */
    private fun external(decl: Decl.Function, args: List<Gen>): Gen {
        val params = decl.params.orEmpty().map { it.name }
        val atoms = externalAtoms(decl).flatMap { atom ->
            val label = atom.removePrefix("~")
            when {
                atom in params -> listOf(args[params.indexOf(atom)])
                atom.startsWith("~") && label in params -> listOf(Gen.Lit("~$label:"), args[params.indexOf(label)])
                else -> listOf(Gen.Lit(atom))
            }
        }
        val head = atoms.first().let { if (it is Gen.Lit) Gen.Id(it.text) else it }
        return if (atoms.size == 1) head else Gen.App(head, atoms.drop(1))
    }

    /** An external used as a value: `sqrt`, or `fun x -> List.length x` when its OCaml isn't just a function. */
    private fun externalValue(decl: Decl.Function): Gen {
        val params = decl.params ?: return external(decl, emptyList())
        val atoms = externalAtoms(decl)
        if (atoms.drop(1) == params.map { it.name }) return Gen.Id(atoms.first())
        val names = params.indices.map { "x$it" }
        return Gen.Fun(names.ifEmpty { listOf("()") }, external(decl, names.map { Gen.Id(it) }))
    }

    /** An external's OCaml split into what's applied: names, `( + )`, `"strings"` and `~labels`. */
    private fun externalAtoms(decl: Decl.Function): List<String> {
        val text = decl.external!!
        val atoms = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> {
                    var depth = 0
                    val start = i
                    do {
                        if (text[i] == '(') depth++ else if (text[i] == ')') depth--
                        i++
                    } while (depth > 0 && i < text.length)
                    atoms += text.substring(start, i)
                }
                c == '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    atoms += text.substring(start, minOf(++i, text.length))
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '(') i++
                    atoms += text.substring(start, i)
                }
            }
        }
        if (atoms.isEmpty()) throw CompileError(decl.pos, "an external needs some OCaml")
        return atoms
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

    /** A shared case's tag, named after the sealed type that declares it, in the functor if it's a copy: `Alphabet_A`. */
    private fun tagName(origin: Decl.Sealed, variant: VariantDef) =
        "${(program.copyOrigins[origin.name] ?: origin.name).replace('.', '_')}_${variant.name}"

    /** An ordinary constructor as written from here: `Circle` inside Shapes, `Shapes.Circle` outside. */
    private fun ctorName(ref: ConstructorRef.Variant): String =
        ref.sealed?.let { ref((it.path + ref.name).joinToString("."), { name -> name }) } ?: ref.name

    /** The record of a field access, annotated with its type when the label belongs to several records. */
    private fun qualified(record: Gen, field: MemberRef.Field): Gen {
        if (field.ocamlName !in program.ambiguousLabels) return record
        val decl = records.getValue(field.record)
        return Gen.Ann(record, applied(decl.name, decl.typeParams.ocaml().map { "_" to false }))
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
                case(pattern) {
                    branch.guard?.let {
                        guarded = true
                        expr(it)
                    }
                    expr(branch.body)
                }
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
            // Option's and Result's: Some, None, Ok and Error.
            ref.variant == null -> if (ref.name == "None") "None" else "${ref.name} ${args?.single()?.let(::atomPattern) ?: "_"}"
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

    /** The statements of [e] from [from] on; in [monad]'s block, the result goes through its pure. */
    private fun <S> S.block(e: Expr.Block, from: Int, monad: Expr.Monadic?) where
        S : IdentParent<Unit>, S : LiteralParent<Unit>, S : ApplyParent<Unit>, S : InfixParent<Unit>,
        S : LambdaParent<Unit>, S : LetInParent<Unit>, S : SequenceParent<Unit>, S : IfThenElseParent<Unit>,
        S : MatchParent<Unit>, S : FieldGetParent<Unit>, S : RecordLitParent<Unit>, S : ConstructParent<Unit>,
        S : AnnotatedParent<Unit>, S : ListLitParent<Unit>, S : TupleParent<Unit>, S : RecordUpdateParent<Unit> {
        if (from == e.statements.size) {
            // map applies the rest of the block, so only pure needs applying here.
            if (monad == null || monad.bind == null && e.statements.any { it is Statement.Bind }) return expr(e.result)
            return apply {
                ident(ref(monad.pure!!.name, ::snake))
                expr(e.result)
            }
        }
        when (val s = e.statements[from]) {
            // Changing a var: `x := value; ...`.
            is Statement.Binding -> if (s.assigns) {
                sequence {
                    infix(":=") {
                        ident(s.ocamlName)
                        expr(s.value)
                    }
                    block(e, from + 1, monad)
                }
            } else {
                letIn(s.ocamlName) {
                    if (s.params != null) {
                        params = s.paramNames.ifEmpty { listOf("()") }
                        rec = s.recursive
                    }
                    if (s.isVar) {
                        apply {
                            ident("ref")
                            expr(s.value)
                        }
                    } else {
                        expr(s.value)
                    }
                    block(e, from + 1, monad)
                }
            }
            is Statement.Destructure -> letIn(pattern(s.pattern)) {
                expr(s.value)
                block(e, from + 1, monad)
            }
            is Statement.Bind -> if (monad!!.bind != null) {
                letIn(pattern(s.pattern)) {
                    binder = "let*"
                    expr(s.value)
                    block(e, from + 1, monad)
                }
            } else {
                // The checker made sure the `by` lines are together.
                var end = from
                while (end < e.statements.size && e.statements[end] is Statement.Bind) end++
                val binds = e.statements.subList(from, end).map { it as Statement.Bind }
                letIn(pattern(s.pattern)) {
                    binder = "let+"
                    ands = binds.drop(1).map { pattern(it.pattern) }
                    binds.forEach { expr(it.value) }
                    block(e, end, monad)
                }
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
                    block(e, end, monad)
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
        is Type.Con -> showCon(t.name.removePrefix(Type.ABSTRACT), ocamlArgs(t.name, t.args).map { show(it, at) })
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
        "Nothing" -> Gen.Fun(listOf("_"), Gen.Lit("assert false"))
        "Float" -> Gen.Id(helper("fk_show_float"))
        "List" -> Gen.App(Gen.Id(helper("fk_show_list")), args)
        "Option" -> Gen.App(Gen.Id(helper("fk_show_option")), args)
        "Result" -> Gen.App(Gen.Id(helper("fk_show_result")), args)
        else -> records[name]?.takeIf { it.external != null }?.let { Gen.Fun(listOf("_"), Gen.Str("<${it.simpleName}>")) } ?: run {
            // A type's show function lives in the type's module. A module a functor made has the
            // functor's; a functor's parameter has what its spec lists.
            val module = name.substringBeforeLast('.', "")
            if (module !in parameterModules) showTypes += program.copyOrigins[name] ?: name
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
            else showCon(program.typeNames[ref] ?: if (ref.name == "Double") "Float" else ref.name, ref.args.filter { it !is TypeRef.Effects }.map { showRef(it, typeParams) })
        is TypeRef.Effects -> error("effects aren't shown")
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
            val params = r.typeParams.ocaml().map { "show_${it.lowercase()}" } +
                "(v : ${applied(r.name, r.typeParams.ocaml().map { typeVar(it) to false })})"
            return params to fieldsText(r.simpleName, r.fields, r.typeParams.ocaml(), v)
        }
        // An alias shows as what it stands for.
        aliases[name]?.let { a ->
            val params = a.typeParams.ocaml().map { "show_${it.lowercase()}" } +
                "(v : ${applied(a.name, a.typeParams.ocaml().map { typeVar(it) to false })})"
            return params to Gen.App(showRef(a.target, a.typeParams.ocaml()), listOf(v))
        }
        val s = sealeds.getValue(name)
        val params = s.typeParams.ocaml().map { "show_${it.lowercase()}" } + "(v : ${applied(s.name, s.typeParams.ocaml().map { typeVar(it) to false })})"
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
                                Gen.App(showRef(f.type, s.typeParams.ocaml()), listOf(Gen.Id(names[i]))),
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
                else "${variant.name} r" to fieldsText(variant.name, variant.fields, s.typeParams.ocaml(), Gen.Id("r"))
            },
        )
    }

    private fun StructureScope<Unit>.helperFunctions() {
        // The type with no values.
        if (NOTHING in helpers) types { typeDef(NOTHING) { alias = "|" } }
        if (helpers.any { it != NOTHING }) let(false) {
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
            if ("fk_show_result" in helpers) {
                binding("fk_show_result") {
                    params = listOf("show", "show_error", "r")
                    gen(
                        Gen.Match(
                            Gen.Id("r"),
                            listOf(
                                "Ok x" to concat(listOf(Gen.Str("Ok("), Gen.App(Gen.Id("show"), listOf(Gen.Id("x"))), Gen.Str(")"))),
                                "Error e" to concat(listOf(Gen.Str("Error("), Gen.App(Gen.Id("show_error"), listOf(Gen.Id("e"))), Gen.Str(")"))),
                            ),
                        ),
                    )
                }
            }
        }
    }
}
