package uk.m4xy.fckt.intellij

import uk.m4xy.fckt.DEFAULT_IMPORTS
import uk.m4xy.fckt.STANDARD_FILES
import uk.m4xy.fckt.STANDARD_MODULES
import uk.m4xy.fckt.analyse
import uk.m4xy.fckt.moduleName
import uk.m4xy.fckt.standardLibrary
import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.CallRef
import uk.m4xy.fckt.syntax.ConstructorRef
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.MemberRef
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaRef
import uk.m4xy.fckt.syntax.NameRef
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pattern
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.Statement
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type
import java.io.File

/**
 * Where a name is declared: in [file] (null for the file being edited, a path, or `std/...` for the
 * standard library), at the first [name] from [pos] on. An empty [name] is the file itself.
 */
data class Target(val file: String?, val pos: Pos, val name: String)

/** Where the compiler's built-in types and functions are described: never compiled, only read. */
const val BUILTINS = "std/builtins.fk"

/** The standard library's source, by the path its positions name, or the built-ins' description. */
fun standardSource(path: String): String? =
    if (path == BUILTINS) Target::class.java.getResourceAsStream("/fckt/builtins.fk")?.use { it.reader().readText() }
    else standardLibrary(path.removePrefix("std/").removeSuffix(".fk"))

/** The offset of the [name] token at or after [pos] in [text], or of [pos] if there's none nearby. */
fun offsetOf(text: String, pos: Pos, name: String): Int {
    var line = 1
    var start = 0
    while (line < pos.line) {
        val next = text.indexOf('\n', start)
        if (next < 0) break
        start = next + 1
        line++
    }
    val at = (start + pos.col - 1).coerceIn(0, text.length)
    if (name.isEmpty()) return at
    return scan(text, at).asSequence().take(64).firstOrNull { it.kind == TokKind.IDENT && text.regionMatches(it.start, name, 0, name.length) && it.end - it.start == name.length }?.start ?: at
}

/**
 * Where each name in [text] is declared, by its token's offset: a local or parameter where it was
 * bound; anything else where the checker resolved it, so `xs.map(f)` goes to the `map` for `xs`'s
 * type; and, where the file doesn't check, every declaration of that name in reach.
 */
fun declarations(text: String, entry: File?): Map<Int, List<Target>> {
    val code = scan(text).filter { it.kind == TokKind.IDENT }
    val tokenAt = code.associateBy { it.start }
    fun word(t: Tok) = text.substring(t.start, t.end)
    val found = mutableMapOf<Int, List<Target>>()
    fun put(offset: Int, target: Target) {
        if (offset in tokenAt && offset !in found) found[offset] = listOf(target)
    }
    fun here(pos: Pos, name: String) = offsetOf(text, pos, name)
    fun of(decl: Decl): Target = Target(decl.pos.file, decl.pos, (decl as? Decl.Function)?.methodName ?: decl.simpleName)

    // Locals and parameters: where they were bound.
    val parsed = try { Parser.parse(text).decls } catch (e: Exception) { null }
    if (parsed != null) {
        val scopes = Scopes()
        scopes.decls(parsed)
        for ((use, bound) in scopes.bindings) {
            val name = code.firstOrNull { it.start == here(use, "") }?.let(::word) ?: continue
            put(here(use, name), Target(null, bound, name))
        }
    }

    // What each name on an import line is: a package's folder, a module's file, or what's in it.
    for ((offset, target) in importTargets(text, entry)) found[offset] = listOf(target)

    // Everything the checker resolved.
    val paths = pathsOf(entry)
    val checked = try { analyse(text, entry, paths.roots, paths.libraries) } catch (e: Exception) { null } catch (e: StackOverflowError) { null }
    if (checked != null) Resolutions(text, checked.program, ::put, ::of).decls(checked.decls)

    // What's left, by name, in the scopes the compiler would look in.
    val outline = parsed?.let { Outline(text, it) }
    val reach = Reach.of(entry)
    for (t in code) {
        val name = word(t)
        // A declaration's own name goes nowhere.
        if (outline != null && t.start in outline.declared) {
            found.remove(t.start)
            continue
        }
        if (t.start in found) continue
        val before = text.substring(0, t.start).trimEnd()
        val qualifier = if (before.endsWith(".")) Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$").find(before.dropLast(1))?.groupValues?.get(1) else null
        val targets = when {
            qualifier != null && qualifier[0].isUpperCase() ->
                outline?.member(qualifier, name).orEmpty().ifEmpty { reach.member(qualifier, name) }
            qualifier != null -> outline?.methods(name).orEmpty() + reach.methods(name)
            else -> outline?.typeParameter(t.start, name)?.let(::listOf)
                ?: outline?.visible(t.start, name).orEmpty().ifEmpty { reach.visible(name) }
        }
        if (targets.isNotEmpty()) found[t.start] = targets
    }
    return found
}

/**
 * Each name on [text]'s import lines, by its offset, with what it names: `import geometry.Shapes.area
 * as size` goes to geometry's folder, Shapes.fk, area there, and, for size, area too. Found from
 * the files themselves, so it works in a file that doesn't check.
 */
fun importTargets(text: String, entry: File?): Map<Int, Target> {
    val imports = try { Parser.parse(text).imports[null].orEmpty() } catch (e: Exception) { return emptyMap() }
    val roots = pathsOf(entry).let { it.roots + it.libraries }
    val idents = scan(text).filter { it.kind == TokKind.IDENT }
    val found = mutableMapOf<Int, Target>()
    for (import in imports) {
        val start = offsetOf(text, import.pos, "import")
        val line = idents.filter { it.start > start }.takeWhile { t -> text.substring(start, t.start).none { it == '\n' } }
        val segments = line.take(import.path.size)
        val targets = importPath(import.path, roots)
        segments.zip(targets).forEach { (tok, target) -> if (target != null) found[tok.start] = target }
        // The `as` name is what the last name is.
        if (import.alias != null) line.lastOrNull()?.takeIf { it !in segments }?.let { tok -> targets.lastOrNull()?.let { found[tok.start] = it } }
    }
    return found
}

/** What each name of an import's [path] is, from [roots]: packages' folders, then a module's file and what's in it. */
private fun importPath(path: List<String>, roots: List<File>): List<Target?> {
    val targets = mutableListOf<Target?>()
    var folders = roots
    // The standard library's package so far, `fckt` or `fckt/io`, which has no folders.
    var std: String? = null
    var i = 0
    // Packages, while the names are folders: `fckt` is the standard library's.
    while (i < path.size && path[i][0].isLowerCase()) {
        val name = path[i]
        val stdNext = (if (i == 0) name else std?.let { "$it/$name" })?.takeIf { pkg -> STANDARD_FILES.any { it.startsWith("$pkg/") } }
        if (stdNext != null) {
            std = stdNext
            targets += null
            i++
            continue
        }
        val next = folders.map { File(it, name) }.filter { it.isDirectory }
        if (next.isEmpty() || std != null) break
        targets += Target(next.first().path, Pos(1, 1), "")
        folders = next
        i++
    }
    if (i == path.size) return targets
    // A module's file, or a declaration of one of the package's lower-case files.
    val name = path[i]
    val (file, text) = if (std != null) {
        val inPackage = STANDARD_FILES.filter { it.substringBeforeLast('/') == std }
        val found = inPackage.firstOrNull { it == "$std/$name.fk" }
            ?: inPackage.filter { it.substringAfterLast('/')[0].isLowerCase() }
                .firstOrNull { f -> parseOrNull(standardSource("std/$f").orEmpty(), "std/$f")?.any { it.name == name || (it is Decl.Function && it.methodName == name) } == true }
        found?.let { "std/$it" }.let { it to it?.let(::standardSource) }
    } else {
        // A program's file, or a library's interface's.
        val f = folders.flatMap { it.listFiles().orEmpty().toList() }.firstOrNull { it.isFile && (it.name == "$name.fk" || it.name == "$name.fki") }
            ?: folders.flatMap { it.listFiles().orEmpty().toList() }.filter { it.isFile && (it.extension == "fk" || it.extension == "fki") && it.name[0].isLowerCase() }
                .firstOrNull { f -> parseOrNull(f.readText(), f.path)?.any { it.name == name || (it is Decl.Function && it.methodName == name) } == true }
        (f?.path) to f?.readText()
    }
    val decls = text?.let { parseOrNull(it, file) } ?: return targets + List(path.size - i) { null }
    var scope: List<Decl> = decls
    while (i < path.size) {
        val segment = path[i]
        // Nothing of that name: the rest of the path goes nowhere.
        val d = scope.firstOrNull { it.name == segment || (it is Decl.Function && it.methodName == segment) } ?: break
        targets += Target(file, d.pos, (d as? Decl.Function)?.methodName ?: d.simpleName)
        scope = when (d) {
            is Decl.Module -> d.decls
            is Decl.Sealed -> {
                // What follows a type with cases is one of them.
                path.getOrNull(i + 1)?.let { case -> d.variants.firstOrNull { it.name == case } }?.let { v ->
                    targets += Target(file, v.pos, v.name)
                    i++
                }
                emptyList()
            }
            else -> emptyList()
        }
        i++
    }
    return targets + List(maxOf(0, path.size - targets.size)) { null }
}

private fun parseOrNull(text: String, file: String?): List<Decl>? = try {
    (if (file?.endsWith(".fki") == true) Parser.parseInterface(text, file) else Parser.parse(text, file)).decls
} catch (e: Exception) {
    null
}

/**
 * The entry file's declarations, with where each is visible: its module's (or spec's) range. A
 * name used somewhere means the declaration in the innermost of those around it, as the compiler
 * looks from the inside out; a type parameter means the one its declaration has.
 */
private class Outline(val text: String, decls: List<Decl>) {
    private class Entry(val name: String, val offset: Int, val within: IntRange, val method: Boolean, val module: String?)

    private val entries = mutableListOf<Entry>()
    private val typeParams = mutableListOf<Pair<IntRange, Set<String>>>()
    private val modules = mutableMapOf<String, IntRange>()
    private val cases = mutableMapOf<String, MutableList<Pair<String, Int>>>()

    /** The offsets of the names declarations declare. */
    val declared = mutableSetOf<Int>()

    private fun offset(pos: Pos) = offsetOf(text, pos, "")

    init {
        walk(decls, 0 until text.length + 1, null)
    }

    private fun add(name: String, pos: Pos, within: IntRange, method: Boolean = false, module: String? = null) {
        val at = offsetOf(text, pos, name)
        declared += at
        entries += Entry(name, at, within, method, module)
    }

    private fun walk(decls: List<Decl>, within: IntRange, module: String?) {
        decls.forEachIndexed { i, d ->
            val start = offset(d.pos)
            val until = (decls.getOrNull(i + 1)?.let { offset(it.pos) } ?: (within.last + 1)).coerceAtMost(text.length)
            val range = start until until
            fun params(names: List<String>) {
                if (names.isEmpty()) return
                typeParams += range to names.toSet()
                // Declared where each first appears: in the <...> after the name, or the receiver.
                for (n in names) scan(text, start, until).firstOrNull { it.kind == TokKind.IDENT && text.substring(it.start, it.end) == n }?.let { declared += it.start }
            }
            when (d) {
                is Decl.Function -> {
                    add(d.methodName, d.pos, within, method = d.extension, module = module)
                    d.params.orEmpty().filter { it.name != "this" }.forEach { declared += offsetOf(text, it.pos, it.name) }
                    params(d.typeParams)
                }
                is Decl.Record -> {
                    add(d.simpleName, d.pos, within, module = module)
                    d.fields.forEach { declared += offsetOf(text, it.pos, it.name) }
                    params(d.typeParams)
                }
                is Decl.Sealed -> {
                    add(d.simpleName, d.pos, within, module = module)
                    d.variants.forEach { v ->
                        add(v.name, v.pos, within, module = module)
                        cases.getOrPut(d.simpleName) { mutableListOf() } += v.name to offsetOf(text, v.pos, v.name)
                        v.fields.forEach { declared += offsetOf(text, it.pos, it.name) }
                    }
                    params(d.typeParams)
                }
                is Decl.Alias -> {
                    add(d.simpleName, d.pos, within, module = module)
                    params(d.typeParams)
                }
                is Decl.Spec -> {
                    add(d.simpleName, d.pos, within, module = module)
                    val members = (d.types.map { it.pos to it.typeParams } + d.values.map { it.pos to it.typeParams }).sortedBy { offset(it.first) }
                    members.forEachIndexed { j, (pos, names) ->
                        val memberEnd = members.getOrNull(j + 1)?.let { offset(it.first) } ?: until
                        if (names.isNotEmpty()) {
                            typeParams += (offset(pos) until memberEnd) to names.toSet()
                            for (n in names) scan(text, offset(pos), memberEnd).firstOrNull { it.kind == TokKind.IDENT && text.substring(it.start, it.end) == n }?.let { declared += it.start }
                        }
                    }
                    // A spec's members are visible inside it, like a module's.
                    d.types.forEach { add(it.name, it.pos, range, module = d.simpleName) }
                    d.values.forEach { v ->
                        add(v.name, v.pos, range, module = d.simpleName)
                        v.params.orEmpty().forEach { declared += offsetOf(text, it.pos, it.name) }
                    }
                }
                is Decl.Module -> {
                    add(d.simpleName, d.pos, within, module = module)
                    modules[d.simpleName] = range
                    d.params.forEach { add(it.name, it.pos, range, module = d.simpleName) }
                    walk(d.decls, range, d.simpleName)
                }
            }
        }
    }

    /** The type parameter [name] at [offset] means, if it's one there: where it's declared. */
    fun typeParameter(offset: Int, name: String): Target? {
        if (!name[0].isUpperCase()) return null
        val range = typeParams.filter { (range, names) -> offset in range && name in names }.minByOrNull { it.first.last - it.first.first }?.first ?: return null
        val decl = scan(text, range.first, range.last + 1).firstOrNull { it.kind == TokKind.IDENT && text.substring(it.start, it.end) == name } ?: return null
        return target(decl.start, name)
    }

    /** What [name] at [offset] means: the declaration of it in the innermost module or spec around it. */
    fun visible(offset: Int, name: String): List<Target> {
        val candidates = entries.filter { it.name == name && !it.method && offset in it.within }
        val innermost = candidates.minOfOrNull { it.within.last - it.within.first } ?: return emptyList()
        return candidates.filter { it.within.last - it.within.first == innermost }.map { target(it.offset, it.name) }
    }

    /** [name] in this file's module [module], or a case of its type [module]. */
    fun member(module: String, name: String): List<Target> =
        entries.filter { it.module == module && it.name == name && !it.method }.map { target(it.offset, it.name) } +
            cases[module].orEmpty().filter { it.first == name }.map { target(it.second, it.first) }

    /** This file's extensions called [name]. */
    fun methods(name: String): List<Target> = entries.filter { it.method && it.name == name }.map { target(it.offset, it.name) }

    private fun target(offset: Int, name: String): Target {
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val col = offset - (text.lastIndexOf('\n', offset - 1) + 1) + 1
        return Target(null, Pos(line, col), name)
    }
}

/** The checker's resolutions in the entry file's declarations, each put at the offset of its name. */
private class Resolutions(
    val text: String,
    val program: Program,
    val put: (Int, Target) -> Unit,
    val plain: (Decl) -> Target,
) {
    /** A functor's parameters, by their full names, with the specs they follow. */
    private val parameters: Map<String, uk.m4xy.fckt.syntax.Decl.Spec> =
        program.functorParams.flatMap { (functor, params) -> params.map { (name, spec) -> "$functor.$name" to spec } }.toMap()

    /** Where [decl] is declared; a functor parameter's members are declared by its spec. */
    private fun of(decl: Decl): Target {
        val spec = parameters[decl.path.joinToString(".")] ?: return plain(decl)
        val member = spec.types.firstOrNull { it.name == decl.simpleName }?.let { it.pos to it.name }
            ?: spec.values.firstOrNull { it.name == decl.simpleName }?.let { it.pos to it.name }
            ?: return plain(decl)
        return Target(member.first.file ?: spec.pos.file, member.first, member.second)
    }

    private val types: Map<String, Decl> = (program.records + program.sealeds + program.aliases + program.annotationTypes).associateBy { it.name }
    private val modules = mutableMapOf<String, Decl.Module>().also { m ->
        fun deep(ds: List<Decl>): Unit = ds.forEach {
            if (it is Decl.Module) {
                m[it.name] = it
                deep(it.decls)
            }
        }
        deep(program.root)
    }

    private fun at(pos: Pos, name: String) = offsetOf(text, pos, name)

    private fun constructor(ref: ConstructorRef): Target? = when (ref) {
        is ConstructorRef.Record -> of(ref.decl)
        is ConstructorRef.Variant -> ref.variant?.let { Target(it.pos.file ?: ref.sealed?.pos?.file, it.pos, it.name) }
    }

    /** `Shapes.Units.twice`: the modules named before a member, from where [e] starts. */
    private fun qualifiers(e: Expr) {
        val path = generateSequence(e) { (it as? Expr.Member)?.target }.toList().reversed()
        val names = path.mapNotNull { (it as? Expr.Name)?.name ?: (it as? Expr.Member)?.name }
        if (names.isEmpty() || !names[0][0].isUpperCase()) return
        for (n in names.indices) {
            val full = names.take(n + 1).joinToString(".")
            val module: Decl = modules[full] ?: modules.values.firstOrNull { it.name.endsWith(".$full") }
                // Alphabet.A: a type with cases names its cases too.
                ?: types[full]?.takeIf { it is Decl.Sealed } ?: types.values.firstOrNull { it is Decl.Sealed && it.name.endsWith(".$full") }
                ?: return
            val node = path[n]
            val name = names[n]
            put(at(node.pos, name).let { start -> if (node is Expr.Member) memberOffset(node) else start }, of(module))
        }
    }

    /** Where a member's name is: the parser puts its position there. */
    private fun memberOffset(e: Expr.Member): Int = at(e.pos, e.name)

    private fun type(ref: TypeRef?) {
        when (ref) {
            null -> {}
            is TypeRef.Named -> {
                program.typeNames[ref]?.let { full ->
                    // Ratios.Ratio: each segment's own token, the modules it's named through, then the type.
                    val parts = ref.name.split('.')
                    val offsets = scan(text, at(ref.pos, "")).filter { it.kind == TokKind.IDENT }.take(parts.size).map { it.start }
                    types[full]?.let { decl -> offsets.lastOrNull()?.let { put(it, of(decl)) } }
                    for (n in 0 until parts.size - 1) {
                        val module = modules[parts.take(n + 1).joinToString(".")] ?: modules.values.firstOrNull { it.simpleName == parts[n] }
                        if (module != null) offsets.getOrNull(n)?.let { put(it, of(module)) }
                    }
                }
                ref.args.forEach(::type)
            }
            is TypeRef.Function -> {
                annotations(ref.effects)
                ref.params.forEach(::type)
                type(ref.result)
            }
            is TypeRef.Tuple -> ref.elements.forEach(::type)
            is TypeRef.Effects -> annotations(ref.effects)
        }
    }

    fun decls(decls: List<Decl>) {
        for (d in decls) when (d) {
            is Decl.Function -> {
                annotations(d.annotations)
                d.params.orEmpty().forEach {
                    annotations(it.annotations)
                    type(it.type)
                    it.default?.let(::expr)
                }
                type(d.returnType)
                if (d.external == null) expr(d.body)
            }
            is Decl.Record -> {
                annotations(d.annotations)
                d.fields.forEach {
                    annotations(it.annotations)
                    type(it.type)
                    it.default?.let(::expr)
                }
            }
            is Decl.Sealed -> {
                annotations(d.annotations)
                d.variants.forEach { v ->
                    annotations(v.annotations)
                    v.fields.forEach {
                        annotations(it.annotations)
                        type(it.type)
                    }
                }
                d.includes.forEach(::type)
                // Alphabet.A: a case taken from another type.
                for (ref in d.caseRefs) {
                    val owner = types.values.filterIsInstance<Decl.Sealed>().firstOrNull { it.name == ref.owner || it.simpleName == ref.owner } ?: continue
                    val offsets = scan(text, at(ref.pos, "")).filter { it.kind == TokKind.IDENT }.take(2).map { it.start }
                    offsets.getOrNull(0)?.let { put(it, of(owner)) }
                    owner.variants.firstOrNull { it.name == ref.case }?.let { v -> offsets.getOrNull(1)?.let { put(it, Target(v.pos.file ?: owner.pos.file, v.pos, v.name)) } }
                }
            }
            is Decl.Alias -> {
                annotations(d.annotations)
                type(d.target)
            }
            is Decl.Spec -> {
                annotations(d.annotations)
                d.values.forEach { v ->
                    annotations(v.annotations)
                    v.params.orEmpty().forEach { type(it.type) }
                    type(v.type)
                }
            }
            is Decl.Module -> {
                annotations(d.annotations)
                decls(d.decls)
            }
        }
    }

    /** `@Route("/a", handler = greet)`: the annotation's declaration, and what its arguments refer to. */
    private fun annotations(annotations: List<Annotation>) {
        for (a in annotations) {
            a.decl?.let { put(at(a.pos, a.name.substringAfterLast('.')), of(it)) }
            a.args.forEach { meta(it.value) }
        }
    }

    private fun meta(m: Meta) {
        when (m) {
            is Meta.ListOf -> m.items.forEach(::meta)
            is Meta.TupleOf -> (m.resolved as? MetaRef.Type)?.let { metaType(m, it.type) } ?: m.items.forEach(::meta)
            is Meta.Literal -> {}
            is Meta.Ref -> {
                // The last name is what's referred to; one before it, its type or module.
                val last = m.name.substringAfterLast('.')
                val owner = m.name.substringBeforeLast('.', "").substringAfterLast('.')
                fun ownedBy(decl: Decl) {
                    if (owner.isNotEmpty()) put(at(m.pos, owner), of(decl))
                }
                when (val r = m.resolved) {
                    is MetaRef.Value -> expr(r.expr)
                    is MetaRef.Type -> metaType(m, r.type)
                    is MetaRef.Spec -> put(at(m.pos, last), of(r.spec))
                    is MetaRef.Module -> put(at(m.pos, last), of(r.module))
                    is MetaRef.Function -> put(at(m.pos, last), of(r.function))
                    is MetaRef.Case -> {
                        put(at(m.pos, last), Target(r.variant.pos.file ?: r.sealed.pos.file, r.variant.pos, r.variant.name))
                        ownedBy(r.sealed)
                    }
                    is MetaRef.Field -> {
                        put(at(m.pos, last), Target(r.field.pos.file ?: r.record.pos.file, r.field.pos, r.field.name))
                        ownedBy(r.record)
                    }
                    is MetaRef.Annotation -> annotations(listOf(r.annotation))
                    null -> {}
                }
            }
        }
    }

    /** [m], written as a type, and the [type] it is: `List<(User, Shape)>`. */
    private fun metaType(m: Meta, type: Type) {
        val t = type.prune() as? Type.Con ?: return
        when (m) {
            is Meta.TupleOf -> m.items.zip(t.args).forEach { (item, arg) -> metaType(item, arg) }
            is Meta.Ref -> {
                types[t.name]?.let { put(at(m.pos, m.name.substringAfterLast('.')), of(it)) }
                m.typeArgs?.forEach(::type)
            }
            else -> {}
        }
    }

    private fun pattern(p: Pattern) {
        when (p) {
            is Pattern.Constructor -> {
                p.resolved?.let { ref -> constructor(ref)?.let { put(at(p.pos, p.name), it) } }
                p.args?.forEach(::pattern)
            }
            is Pattern.Tuple -> p.elements.forEach(::pattern)
            is Pattern.ListPattern -> {
                p.elements.forEach(::pattern)
                p.rest?.let(::pattern)
            }
            is Pattern.Literal -> expr(p.expr)
            is Pattern.Bind, is Pattern.Wildcard -> {}
        }
    }

    private fun expr(e: Expr) {
        when (e) {
            is Expr.Name -> when (val r = e.resolved) {
                is NameRef.Global -> put(at(e.pos, e.name), of(r.decl))
                is NameRef.Constructor -> constructor(r.ref)?.let { put(at(e.pos, e.name), it) }
                is NameRef.Operation -> put(at(e.pos, e.name), Target(r.operation.pos.file ?: r.spec.pos.file, r.operation.pos, r.operation.name))
                is NameRef.Handler -> put(at(e.pos, e.name), of(r.module))
                else -> {}
            }
            is Expr.Call -> {
                val callee = e.callee
                val name = (callee as? Expr.Name)?.name ?: (callee as? Expr.Member)?.name
                val offset = if (callee is Expr.Member) memberOffset(callee) else name?.let { at(callee.pos, it) }
                val target = when (val r = e.resolved) {
                    is CallRef.Construct -> constructor(r.ref)
                    is CallRef.Extension -> of(r.decl)
                    else -> null
                }
                if (target != null && offset != null) put(offset, target)
                expr(callee)
                e.args.forEach { expr(it.value) }
            }
            is Expr.Member -> {
                val target = when (val r = e.resolved) {
                    is MemberRef.Extension -> of(r.decl)
                    is MemberRef.Global -> of(r.decl)
                    is MemberRef.Case -> constructor(r.ref)
                    is MemberRef.Field -> (types[r.record] as? Decl.Record)?.fields?.firstOrNull { it.name == e.name }
                        ?.let { Target(it.pos.file ?: types[r.record]?.pos?.file, it.pos, it.name) }
                    else -> null
                }
                if (target != null) put(memberOffset(e), target)
                qualifiers(e)
                expr(e.target)
            }
            is Expr.Binary -> {
                expr(e.left)
                expr(e.right)
            }
            is Expr.Unary -> expr(e.operand)
            is Expr.Assign -> {
                expr(e.target)
                expr(e.value)
            }
            is Expr.Handle -> {
                expr(e.handler)
                block(e.block)
            }
            is Expr.Lambda -> {
                e.params.forEach { type(it.type) }
                expr(e.body)
            }
            is Expr.Pipe -> {
                expr(e.value)
                expr(e.function)
            }
            is Expr.If -> {
                expr(e.condition)
                expr(e.then)
                expr(e.otherwise)
            }
            is Expr.When -> {
                e.subject?.let(::expr)
                e.branches.forEach { b ->
                    b.patterns.forEach(::pattern)
                    b.condition?.let(::expr)
                    b.guard?.let(::expr)
                    expr(b.body)
                }
            }
            is Expr.Block -> block(e)
            is Expr.Monadic -> {
                qualifiers(e.module)
                block(e.block)
            }
            is Expr.StringLit -> e.parts.forEach { if (it is StringPart.Interpolated) expr(it.expr) }
            is Expr.Tuple -> e.elements.forEach(::expr)
            is Expr.ListLit -> e.items.forEach(::expr)
            is Expr.Spread -> expr(e.list)
            is Expr.Compiled, is Expr.IntLit, is Expr.FloatLit, is Expr.BoolLit, is Expr.CharLit -> {}
        }
    }

    private fun block(b: Expr.Block) {
        for (st in b.statements) when (st) {
            is Statement.Binding -> {
                st.params.orEmpty().forEach { type(it.type) }
                type(st.returnType)
                expr(st.value)
            }
            is Statement.Effect -> expr(st.expr)
            is Statement.Destructure -> {
                pattern(st.pattern)
                expr(st.value)
            }
            is Statement.Bind -> {
                pattern(st.pattern)
                expr(st.value)
            }
        }
        expr(b.result)
    }
}

/**
 * What's in reach of a file besides its own declarations: what every file imports, which needs no
 * module's name; and modules, one for each of its directory's other files and the standard library's
 * modules, whose members are named through them.
 */
private class Reach {
    private class Module(val target: Target, val members: Map<String, List<Target>>)

    private val prelude = mutableMapOf<String, MutableList<Target>>()
    private val methods = mutableMapOf<String, MutableList<Target>>()
    private val modules = mutableMapOf<String, Module>()

    fun visible(name: String): List<Target> = prelude[name] ?: modules[name]?.let { listOf(it.target) }.orEmpty()
    fun member(module: String, name: String): List<Target> = modules[module]?.members?.get(name).orEmpty()
    fun methods(name: String): List<Target> = methods[name].orEmpty()

    /** [decls] by name, at any depth; extensions go in [methods] too. */
    private fun names(decls: List<Decl>, file: String, into: MutableMap<String, MutableList<Target>>) {
        for (d in decls) {
            val target = Target(file, d.pos, (d as? Decl.Function)?.methodName ?: d.simpleName)
            if (d is Decl.Function && d.extension) {
                methods.getOrPut(d.methodName) { mutableListOf() } += target
                continue
            }
            into.getOrPut(target.name) { mutableListOf() } += target
            when (d) {
                is Decl.Sealed -> {
                    val variants = d.variants.associate { it.name to listOf(Target(file, it.pos, it.name)) }
                    variants.forEach { (name, t) -> into.getOrPut(name) { mutableListOf() } += t }
                    // Option.Some: a type with cases names its cases.
                    modules.putIfAbsent(d.simpleName, Module(target, variants))
                }
                is Decl.Module -> names(d.decls, file, into)
                else -> {}
            }
        }
    }

    private fun parse(text: String?, file: String): List<Decl>? =
        text?.let { try { Parser.parse(it, file).decls } catch (e: Exception) { null } }

    private fun module(name: String, file: String, text: String?) {
        if (name in modules) return
        val members = mutableMapOf<String, MutableList<Target>>()
        parse(text, file)?.let { names(it, file, members) }
        modules[name] = Module(Target(file, Pos(1, 1, file), ""), members)
    }

    companion object {
        fun of(entry: File?): Reach {
            val reach = Reach()
            // The program's own package comes first: its folder's modules, each a capitalised file, and
            // the declarations of its lower-case files, but those that are programs, with a main.
            entry?.parentFile?.listFiles { f -> f.isFile && f.extension == "fk" && f.canonicalFile != entry.canonicalFile }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    val text = try { f.readText() } catch (e: Exception) { null }
                    if (f.name[0].isUpperCase()) return@forEach reach.module(f.nameWithoutExtension, f.path, text)
                    val decls = reach.parse(text, f.path) ?: return@forEach
                    if (decls.none { it is Decl.Function && it.name == "main" }) reach.names(decls, f.path, reach.prelude)
                }
            // What every file imports: everything in these modules and packages, and these declarations.
            for (import in DEFAULT_IMPORTS) {
                val path = import.removeSuffix(".*").replace('.', '/')
                val files = STANDARD_MODULES.filter { it == path || (import.endsWith(".*") && it.substringBeforeLast('/') == path) }
                for (name in files) reach.parse(standardSource("std/$name.fk"), "std/$name.fk")?.let { reach.names(it, "std/$name.fk", reach.prelude) }
            }
            reach.parse(standardSource(BUILTINS), BUILTINS)?.let { reach.names(it, BUILTINS, reach.prelude) }
            for (name in STANDARD_MODULES) reach.module(name.substringAfterLast('/'), "std/$name.fk", standardSource("std/$name.fk"))
            return reach
        }
    }
}
