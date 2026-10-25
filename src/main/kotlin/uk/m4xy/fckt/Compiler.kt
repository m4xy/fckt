package uk.m4xy.fckt

import uk.m4xy.fckt.ocaml.Lowering
import uk.m4xy.fckt.ocaml.Printer
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.CompileErrors
import uk.m4xy.fckt.syntax.Copier
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Import
import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.RawPart
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.syntax.Token
import uk.m4xy.fckt.types.Checker
import uk.m4xy.fckt.types.EFFECT_NAME
import uk.m4xy.fckt.types.MUTATING_NAME
import uk.m4xy.kastor.resolve
import java.io.File

/** fckt source to OCaml source, with the standard library. Throws [uk.m4xy.fckt.syntax.CompileError]. */
fun compile(source: String): String = compile(link(source, Packages(emptyList(), null)).file)

/**
 * [entry] and the files it uses, to one OCaml source. A file's folder, under one of [dirs], is its
 * package; a capitalised file is a module, `Ratios.fk` is Ratios, and a lower-case one holds
 * declarations of its package. A file uses its package's by name, and others' by importing them.
 */
fun compile(entry: File, dirs: List<File>): String = compile(entry.readText(), entry, dirs)

/** [text] as the contents of [entry], which an editor may not have saved yet, and the files it uses. */
fun compile(text: String, entry: File, dirs: List<File>): String = compile(link(text, Packages(dirs, entry)).file)

/** A checked program: [decls] are the entry file's own, with what each name refers to filled in. */
class Analysis(val decls: List<Decl>, val program: uk.m4xy.fckt.types.Program)

/**
 * [text], as [entry], checked with the files it uses, for tools: what each name in it refers to.
 * Each declaration's position names its file: null for [entry], a path, or `std/...` for the
 * standard library. Throws [uk.m4xy.fckt.syntax.CompileError].
 */
fun analyse(text: String, entry: File?, dirs: List<File>): Analysis {
    val linked = link(text, Packages(dirs, entry))
    // A when that misses some cases is still clear about what its names refer to.
    return Analysis(linked.main, Checker(linked.file, coverage = false).check())
}

/** The source of the standard library's [name] (`prelude`, `fckt/Options`, ...), for tools that read it. */
fun standardLibrary(name: String): String? =
    Source::class.java.getResourceAsStream("/std/$name.fk")?.use { it.reader().readText() }

/** The package the standard library's modules are in: `import fckt.Options`. */
const val STANDARD_PACKAGE = "fckt"

/** The standard library's files under `std/`, each a module: `fckt/io/Files.fk` is fckt.io.Files. */
val STANDARD_FILES = listOf(
    "Options",
    "Results",
    "build/Building",
    "build/Builds",
    "build/Project",
    "build/Task",
    "collections/Array",
    "collections/Arrays",
    "collections/Lists",
    "collections/Stream",
    "collections/Streams",
    "effects/Fail",
    "effects/Failure",
    "effects/Generators",
    "effects/Yielding",
    "io/Channel",
    "io/Channels",
    "io/Console",
    "io/Exit",
    "io/FileSystem",
    "io/Files",
    "io/InputHandle",
    "io/OutputHandle",
    "io/Path",
    "io/Paths",
    "io/Process",
    "io/Processes",
    "io/Reader",
    "io/Terminal",
    "io/Writer",
    "lang/Applicative",
    "lang/Effect",
    "lang/Encapsulates",
    "lang/Functions",
    "lang/HandledBy",
    "lang/Meta",
    "lang/Monad",
    "lang/Mutating",
    "lang/Pure",
    "lang/Repeatable",
    "math/Maths",
    "monads/Lists",
    "monads/Options",
    "monads/Results",
    "monads/Validated",
    "monads/Validation",
    "text/Bytes",
    "text/Encodings",
    "text/Strings",
    "values/Lazies",
    "values/Mutable",
).map { "$STANDARD_PACKAGE/$it.fk" }

/** The standard library's modules, as their files are named under `std/`. */
val STANDARD_MODULES = STANDARD_FILES.map { it.removeSuffix(".fk") }

/**
 * What every file imports, as Kotlin imports kotlin.* and others: what the language relies on,
 * the console, failing, and the methods on lists, strings, numbers, options and results.
 */
val DEFAULT_IMPORTS = listOf(
    "lang.*", "lang.Functions.*", "io.Console", "io.Terminal", "effects.Fail", "effects.Failure.*",
    "collections.Lists.*", "text.Strings.*", "math.Maths.*", "Options.*", "Results.*",
).map { "$STANDARD_PACKAGE.$it" }

/** The OCaml libraries beyond the standard one that compiled [ocaml] needs: `unix`, for fckt.io's processes. */
fun ocamlLibraries(ocaml: String): List<String> = listOfNotNull("unix".takeIf { "Unix." in ocaml })

/** A file to read if it's used: one of the program's, or the standard library's. */
class Source(val path: String, val read: () -> String)

private fun resource(path: String): String =
    Source::class.java.getResourceAsStream("/$path")?.use { it.reader().readText() } ?: error("missing $path")

/** Each of [decls], from [text], with the names it uses: those up to the next declaration. */
private fun withUses(text: String, decls: List<Decl>): List<Pair<Decl, Set<String>>> {
    val lines = text.lines()
    return decls.mapIndexed { i, d ->
        val until = decls.getOrNull(i + 1)?.pos?.line?.minus(1) ?: lines.size
        d to mentioned(lines.subList(d.pos.line - 1, until).joinToString("\n"), null)
    }
}

/**
 * Where a program's packages are: each a folder, under one of [roots], or the standard library's.
 * Names are matched against what's in a folder, so `ratios.fk` is never taken for `Ratios.fk`.
 */
private class Packages(private val roots: List<File>, private val entry: File?) {
    private val listings = mutableMapOf<List<String>, List<File>>()

    /** The files in [pkg]'s folders, in each root, but the program's own. */
    private fun files(pkg: List<String>): List<File> = listings.getOrPut(pkg) {
        roots.map { File(it, pkg.joinToString("/")) }.filter { it.isDirectory }.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.canonicalFile != entry?.canonicalFile }.sortedBy { it.name }
        }
    }

    /** Whether [pkg] is the standard library or in it, which no folder of the program's can be. */
    private fun standard(pkg: List<String>) = pkg.firstOrNull() == STANDARD_PACKAGE

    /** The standard library's files directly in [pkg], by their names. */
    private fun standardFiles(pkg: List<String>): List<String> =
        STANDARD_FILES.filter { it.substringBeforeLast('/') == pkg.joinToString("/") }.map { it.substringAfterLast('/') }

    /** Whether [pkg] is a package: a folder, named as a value is, or one of the standard library's. */
    fun isPackage(pkg: List<String>): Boolean =
        if (standard(pkg)) STANDARD_FILES.any { it.startsWith(pkg.joinToString("/", postfix = "/")) }
        else pkg.isNotEmpty() && pkg.all { IDENTIFIER.matches(it) } && roots.any { File(it, pkg.joinToString("/")).isDirectory }

    /** The module [name] in [pkg]: its file, `Name.fk`. */
    fun module(pkg: List<String>, name: String): Source? {
        if (!name[0].isUpperCase()) return null
        if (standard(pkg)) return "$name.fk".takeIf { it in standardFiles(pkg) }?.let { standardSource(pkg, it) }
        return files(pkg).firstOrNull { it.isFile && it.name == "$name.fk" }?.let { f -> Source(f.path) { f.readText() } }
    }

    /** The names of [pkg]'s modules. */
    fun modules(pkg: List<String>): List<String> =
        if (standard(pkg)) standardFiles(pkg).filter { it[0].isUpperCase() }.map { it.removeSuffix(".fk") }
        else files(pkg).filter { it.isFile && it.extension == "fk" && it.name[0].isUpperCase() }.map { it.nameWithoutExtension }

    /** [pkg]'s lower-case files, whose declarations are the package's own. */
    fun declarationFiles(pkg: List<String>): List<Source> =
        if (standard(pkg)) standardFiles(pkg).filter { it[0].isLowerCase() }.map { standardSource(pkg, it) }
        else files(pkg).filter { it.isFile && it.extension == "fk" && it.name[0].isLowerCase() }.map { f -> Source(f.path) { f.readText() } }

    private fun standardSource(pkg: List<String>, file: String): Source {
        val path = "std/${pkg.joinToString("/")}/$file"
        return Source(path) { resource(path) }
    }

    companion object {
        val IDENTIFIER = Regex("[a-z_][A-Za-z0-9_]*")
    }
}

/** A program with what it uses ([file]), and the program's own declarations ([main]). */
private class Linked(val file: SourceFile, val main: List<Decl>)

/**
 * A file of the standard library's, [name].fk in [pkg], of which only what's used is brought in:
 * [members], each with the names it uses, of its [module] if it's one, or else of the file. Those
 * [included] so far, or all of it once its name is used.
 */
private class Partial(
    val pkg: List<String>,
    val name: String,
    val module: Decl.Module?,
    val members: List<Pair<Decl, Set<String>>>,
    val source: Source,
    /** The names the module's own line uses, as the spec it follows. */
    val header: Set<String> = emptySet(),
) {
    val included = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Decl, Boolean>())
    var whole = false

    /** What's been brought in, as the package's declarations: none if nothing has. */
    fun linked(): List<Decl> {
        val decls = members.map { it.first }.filter { whole || it in included }
        if (module == null) return decls
        return if (whole) listOf(module) else listOfNotNull(decls.takeIf { it.isNotEmpty() }?.let { module.copy(decls = it) })
    }
}

/**
 * The program [text] with what it uses: from its own package, the modules it names and the
 * declarations of its lower-case files; and what it imports, by name or by default. Each package
 * is a module named after its folder: `geometry.Shapes`. Of the standard library's modules, only
 * the declarations used are brought in, so only they go into the OCaml.
 */
private fun link(text: String, packages: Packages): Linked {
    val main = Parser.parse(text)
    val own = main.decls.map { it.name }.toSet()
    // What each package has, by its path; the program's own package is the root, [].
    val contents = linkedMapOf<List<String>, MutableList<Decl>>(emptyList<String>() to mutableListOf())
    val imports = mutableMapOf<String?, List<Import>>(null to main.imports[null].orEmpty())
    val included = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Decl, Boolean>())
    val loaded = mutableSetOf<String>()
    // The standard library's modules read so far, by their files, and their members by the names
    // that bring them in: their own, a method's, and a handled effect's operations'.
    val partials = linkedMapOf<String, Partial>()
    val partialMembers = mutableMapOf<String, MutableList<Pair<Partial, Pair<Decl, Set<String>>>>>()
    // Each package's lower-case files' declarations, once read.
    val packageDecls = mutableMapOf<List<String>, List<Triple<Decl, Set<String>, Source>>>()
    // Names to look up: where they're used, which names are declared there, and the names; and the
    // file they're in, whose private declarations they can use.
    class Use(val pkg: List<String>, val declared: Set<String>, val names: Set<String>, val file: String? = null)
    val pending = ArrayDeque<Use>()
    // Imports of the files brought in, still to bring in what they name.
    val pendingImports = ArrayDeque<Import>()
    val errors = mutableListOf<CompileError>()
    // Names used so far that the standard library's may be meant by: a module read later brings
    // in its members of these names.
    val used = mutableSetOf<String>()
    val usedMethods = mutableSetOf<String>()
    // Every file imports these, whose members it uses by name.
    fun defaults(file: String?) = DEFAULT_IMPORTS.map { Import(Pos(1, 1, file), it.removeSuffix(".*").split('.'), wildcard = it.endsWith(".*")) }

    fun packageContents(pkg: List<String>) = contents.getOrPut(pkg) { mutableListOf() }

    // A file with a main is a program of its own, so what it declares isn't its package's.
    fun declsOf(pkg: List<String>) = packageDecls.getOrPut(pkg) {
        packages.declarationFiles(pkg).flatMap { source ->
            val code = source.read()
            val parsed = Parser.parse(code, source.path)
            if (parsed.decls.any { it is Decl.Function && it.name == "main" }) return@flatMap emptyList()
            imports[source.path] = parsed.imports[source.path].orEmpty() + defaults(source.path)
            withUses(code, parsed.decls).map { (d, uses) -> Triple(d, uses, source) }
        }
    }

    /**
     * What [name].fk declares: [name], a module, type, spec or effect, with anything else beside it
     * private to the file.
     */
    fun declarationOf(decls: List<Decl>, name: String, source: Source): Decl? {
        val main = decls.firstOrNull { it.name == name && !it.isPrivate && !(it is Decl.Function && it.extension) }
        if (main == null) errors += CompileError(Pos(1, 1, source.path), "$name.fk must declare $name, which it's named for: a module, type, spec or effect")
        for (d in decls) {
            if (d === main || d.isPrivate) continue
            errors += CompileError(
                d.pos,
                if (d.name[0].isUpperCase()) "${d.name} belongs in its own file, ${d.name}.fk; or make it private to $name.fk"
                else "'${d.name}' is outside $name: a capitalised file declares only what it's named for, so put it in a module, or make it private to $name.fk",
            )
        }
        return main
    }

    fun include(partial: Partial, member: Decl, uses: Set<String>) {
        if (partial.included.isEmpty()) pending += Use(partial.pkg, setOf(partial.name), partial.header, partial.source.path)
        if (!partial.included.add(member)) return
        pending += Use(partial.pkg, declaredNames(listOf(member)) + partial.name, uses, partial.source.path)
    }

    fun includeWhole(partial: Partial) {
        if (partial.whole) return
        partial.whole = true
        for ((d, uses) in partial.members) include(partial, d, uses)
    }

    fun loadModule(pkg: List<String>, name: String, source: Source): Partial? {
        partials[source.path]?.let { return it }
        if (!loaded.add(source.path)) return null
        val code = source.read()
        val parsed = Parser.parse(code, source.path)
        val fileImports = parsed.imports[source.path].orEmpty() + defaults(source.path)
        imports[source.path] = fileImports
        pendingImports += fileImports
        val main = declarationOf(parsed.decls, name, source)
        if (main == null || !source.path.startsWith("std/")) {
            packageContents(pkg) += parsed.decls
            pending += Use(pkg, declaredNames(parsed.decls) + name, mentioned(code, source.path))
            return null
        }
        // The standard library's: each member is brought in when it's used.
        val module = main as? Decl.Module
        val header = module?.let { m ->
            val until = m.decls.firstOrNull()?.pos?.line ?: (m.pos.line + 1)
            mentioned(code.lines().subList(m.pos.line - 1, until - 1).joinToString("\n"), source.path) - name
        }.orEmpty()
        val partial = Partial(pkg, name, module, if (module != null) withUses(code, module.decls) else withUses(code, parsed.decls), source, header)
        partials[source.path] = partial
        if (module != null) packageContents(pkg) += parsed.decls.filter { it !== module }
        for (member in partial.members) {
            val (d, _) = member
            val names = listOf(d.name) + listOfNotNull((d as? Decl.Function)?.takeIf { it.extension }?.methodName) +
                ((d as? Decl.Spec)?.takeIf { s -> s.annotations.any { it.name == EFFECT_NAME } }?.values?.map { it.name }.orEmpty())
            for (n in names.toSet()) partialMembers.getOrPut(n) { mutableListOf() } += partial to member
            val wanted = !d.isPrivate && if (d is Decl.Function && d.extension) d.methodName in usedMethods && d.name !in own else names.any { it in used }
            if (wanted) include(partial, d, member.second)
        }
        if (name in used) includeWhole(partial)
        return partial
    }

    fun includeDecl(pkg: List<String>, decl: Decl, uses: Set<String>, source: Source) {
        if (!included.add(decl)) return
        packageContents(pkg) += decl
        pending += Use(pkg, declaredNames(listOf(decl)), uses, source.path)
        // The imports of the file it's in, for what it uses through them.
        if (loaded.add(source.path)) pendingImports += imports[source.path].orEmpty()
    }

    /** Brings in what [import] names; a wildcard, all of a package or module. */
    fun resolveImport(import: Import) {
        val path = import.path
        // The longest start of the path that's a package; what comes next is in it.
        val pkgLength = (path.size downTo 1).firstOrNull { packages.isPackage(path.take(it)) }
        if (pkgLength == null) {
            errors += CompileError(import.pos, "import ${path.joinToString(".")}${if (import.wildcard) ".*" else ""} names no package: a package is a folder, as geometry is for geometry/Shapes.fk")
            return
        }
        val pkg = path.take(pkgLength)
        val rest = path.drop(pkgLength)
        if (rest.isEmpty()) {
            if (!import.wildcard) {
                errors += CompileError(import.pos, "${path.joinToString(".")} is a package; import what's in it, as in import ${path.joinToString(".")}.*")
                return
            }
            for (m in packages.modules(pkg)) packages.module(pkg, m)?.let { loadModule(pkg, m, it) }
            for ((d, uses, source) in declsOf(pkg)) if (!d.isPrivate) includeDecl(pkg, d, uses, source)
            return
        }
        val first = rest.first()
        packages.module(pkg, first)?.let { source ->
            val partial = loadModule(pkg, first, source) ?: return
            // One member, by name: that's used. A wildcard or the module itself: what of it is used is found by name.
            val member = rest.getOrNull(1) ?: return
            val found = partial.members.filter { (d, _) -> d.simpleName == member || (d is Decl.Function && d.extension && d.methodName == member) }
            if (found.isEmpty()) return errors.plusAssign(CompileError(import.pos, "${path.take(pkgLength + 1).joinToString(".")} has no '$member'"))
            found.forEach { (d, uses) -> include(partial, d, uses) }
            return
        }
        val decls = declsOf(pkg).filter { (d, _, _) -> d.name == first || (d is Decl.Function && d.extension && d.methodName == first) }
        if (decls.isNotEmpty()) return decls.forEach { (d, uses, source) -> includeDecl(pkg, d, uses, source) }
        errors += CompileError(import.pos, "${pkg.joinToString(".")} has no '$first'")
    }

    imports[null] = main.imports[null].orEmpty() + defaults(null)
    pending += Use(emptyList(), declaredNames(main.decls), mentioned(text, null) + MUTATING_NAME + "get" + "set" + EFFECT_NAME)
    pendingImports += imports[null]!!
    while (pending.isNotEmpty() || pendingImports.isNotEmpty()) {
        // What's imported first, so the names used find it.
        while (pendingImports.isNotEmpty()) resolveImport(pendingImports.removeFirst())
        val use = pending.removeFirstOrNull() ?: continue
        for (name in use.names) {
            // The standard library's declarations of that name, or methods or operations: any may
            // be the one meant. The program's own take their place.
            // A method's name brings in the library's methods of that name, but those the program
            // declares for the same type itself; any other name, the library's of that name, unless
            // the program declares its own.
            val shadowed = use.file == null && name in own
            if (!shadowed) used += name
            usedMethods += name
            partialMembers[name]?.toList()?.forEach { (partial, member) ->
                val d = member.first
                // A private one only from its own file.
                if (d.isPrivate && use.file != partial.source.path) return@forEach
                val keep = if (d is Decl.Function && d.extension) d.name !in own else !shadowed
                if (keep) include(partial, d, member.second)
            }
            // A module of the standard library's, named outright: all of it, as a block may run in it.
            if (!(use.file == null && name in own)) partials.values.filter { it.name == name }.forEach { includeWhole(it) }
            if (name in use.declared || (use.pkg.isEmpty() && name in own)) continue
            // A module of the package it's used in, or a declaration of one of its lower-case files.
            packages.module(use.pkg, name)?.let { loadModule(use.pkg, name, it)?.let(::includeWhole) }
            val siblings = declsOf(use.pkg).filter { (d, _, s) -> (!d.isPrivate || s.path == use.file) && (d.name == name || (d is Decl.Function && d.extension && d.methodName == name)) }
            siblings.forEach { (d, uses, source) -> includeDecl(use.pkg, d, uses, source) }
            // A package, named in full, as geometry is in geometry.Shapes.area: the modules after it.
            if (packages.isPackage(listOf(name))) {
                fun reach(pkg: List<String>) {
                    for (next in use.names) {
                        packages.module(pkg, next)?.let { loadModule(pkg, next, it)?.let(::includeWhole) }
                        if (packages.isPackage(pkg + next)) reach(pkg + next)
                    }
                }
                reach(listOf(name))
            }
        }
    }
    CompileErrors.throwIfAny(errors)
    // The standard library's modules, with what's used of them; those with nothing used are left out.
    val omitted = mutableSetOf<String>()
    for (partial in partials.values) {
        val linked = partial.linked()
        if (linked.none { it.name == partial.name }) omitted += (partial.pkg + partial.name).joinToString(".")
        packageContents(partial.pkg) += linked
    }
    // Imports of what was left out name nothing used, so they go too.
    val linkedImports = imports.mapValues { (_, list) -> list.filter { i -> omitted.none { i.path.joinToString(".").let { p -> p == it || p.startsWith("$it.") } } } }
    // Each package a module named after its folder, inside its parent's.
    fun tree(pkg: List<String>): List<Decl> {
        val children = contents.keys.filter { it.size == pkg.size + 1 && it.take(pkg.size) == pkg }.distinct()
            .filter { child -> contents.keys.any { it.take(child.size) == child && contents[it].orEmpty().isNotEmpty() } }
        val packageModules = children.map { child -> Decl.Module(Pos(1, 1, null), child.last(), tree(child), isPackage = true) }
        val mine = contents[pkg].orEmpty()
        return packageModules + if (pkg.isEmpty()) mine.filter { it !in main.decls } else mine
    }
    // Every package on the way to one with contents has a module too.
    for (pkg in contents.keys.toList()) for (n in 1 until pkg.size) packageContents(pkg.take(n))
    val all = copyEffects(tree(emptyList()) + main.decls, linkedImports)
    return Linked(SourceFile(all, linkedImports), all.takeLast(main.decls.size))
}

/**
 * [decls] with each `effect OrdersDb = Database` made a copy of Database: the same operations,
 * under its own name, so a separate effect, handled on its own. What it copies is found as the
 * checker would find it, from the inside out.
 */
private fun copyEffects(decls: List<Decl>, imports: Map<String?, List<Import>>): List<Decl> {
    fun lookup(parts: List<String>, scope: List<Decl>): Decl? {
        var here = scope
        for ((i, part) in parts.withIndex()) {
            val d = here.firstOrNull { it.name == part && it !is Decl.Function } ?: return null
            if (i == parts.lastIndex) return d
            here = (d as? Decl.Module)?.decls ?: return null
        }
        return null
    }
    fun find(name: String, scopes: List<List<Decl>>, file: String?): Decl? {
        val parts = name.split('.')
        for (scope in scopes) lookup(parts, scope)?.let { return it }
        // What the file imports: by name, or everything in a package or module.
        for (i in imports[file].orEmpty()) {
            val found = if (i.wildcard) lookup(i.path + parts, decls)
            else if (i.name == parts[0]) lookup(i.path + parts.drop(1), decls)
            else null
            found?.let { return it }
        }
        return null
    }
    fun copy(d: Decl.Spec, scopes: List<List<Decl>>, seen: Set<Decl.Spec>): Decl {
        val ref = d.copyOf!!
        if (d in seen) throw CompileError(d.pos, "effect '${d.name}' is a copy of itself")
        val target = find(ref.name, scopes, d.pos.file) ?: throw CompileError(ref.pos, "unknown effect '${ref.name}'")
        val original = if (target is Decl.Spec && target.copyOf != null) copy(target, scopes, seen + d) else target
        if (original.annotations.none { it.name == EFFECT_NAME }) throw CompileError(ref.pos, "'${ref.name}' isn't an effect, so there's nothing to copy")
        return when (original) {
            is Decl.Spec -> (Copier({ it }).decl(original) as Decl.Spec)
                .copy(pos = d.pos, name = d.name, annotations = d.annotations, isPrivate = d.isPrivate, copyOf = null)
            is Decl.Record -> Decl.Record(d.pos, d.name, emptyList(), emptyList(), isAnnotation = true, annotations = d.annotations, isPrivate = d.isPrivate)
            else -> throw CompileError(ref.pos, "'${ref.name}' isn't an effect, so there's nothing to copy")
        }
    }
    fun walk(here: List<Decl>, outer: List<List<Decl>>): List<Decl> {
        val scopes = listOf(here) + outer
        return here.map { d ->
            when {
                d is Decl.Spec && d.copyOf != null -> copy(d, scopes, emptySet())
                d is Decl.Module -> d.copy(decls = walk(d.decls, scopes))
                else -> d
            }
        }
    }
    return walk(decls, emptyList())
}

private fun compile(file: SourceFile): String {
    val program = Checker(file).check()
    val tree = Lowering(program).lower()
    return Printer.print(tree.resolve(Unit))
}

/** The module a file is: its name in PascalCase, `shared-cases.fk` as SharedCases. */
fun moduleName(file: File): String =
    file.nameWithoutExtension.split('-', '_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

/** Every name in [source], including inside string templates: the modules, types and methods it may use. */
private fun mentioned(source: String, path: String?): Set<String> {
    val names = linkedSetOf<String>()
    fun scan(tokens: List<Token>) {
        for (token in tokens) {
            if (token.kind == T.IDENT) names += token.text
            for (part in token.parts) if (part is RawPart.Code) scan(Lexer(part.source, part.pos).tokenize())
        }
    }
    scan(Lexer(source, Pos(1, 1, path)).tokenize())
    return names
}

/** The names [decls] declare, at any depth, which a file of the same name mustn't stand in for. */
private fun declaredNames(decls: List<Decl>): Set<String> = decls.flatMap { d ->
    listOf(d.name) + when (d) {
        is Decl.Module -> declaredNames(d.decls) + d.params.map { it.name }
        is Decl.Sealed -> d.variants.map { it.name }
        // A handled effect's operations, which stand in for the prelude's of the same name.
        is Decl.Spec -> d.types.map { it.name } + d.values.map { it.name }
        else -> emptyList()
    }
}.toSet()
