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
import uk.m4xy.fckt.syntax.TypeRef
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.MetaRef
import uk.m4xy.fckt.types.Checker
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type
import uk.m4xy.fckt.types.effectiveAnnotations
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
fun compile(entry: File, dirs: List<File>, imports: List<String> = emptyList()): String = compile(entry.readText(), entry, dirs, imports)

/**
 * [text] as the contents of [entry], which an editor may not have saved yet, and the files it uses.
 * [imports] are imported into each of the program's files, but the standard library's, beside what
 * every file imports: `kiln.*` into a build script. With [libraries], interfaces, it's compiled
 * separately, as [compileSeparately] is.
 */
fun compile(text: String, entry: File, dirs: List<File>, imports: List<String> = emptyList(), libraries: List<File> = emptyList()): String =
    compile(link(text, packages(dirs, entry, libraries), imports).file)

/** Where packages are, from [dirs] and [libraries]: with any library, the standard library is compiled on its own too. */
private fun packages(dirs: List<File>, entry: File?, libraries: List<File>) =
    if (libraries.isEmpty()) Packages(dirs, entry) else Packages(dirs, entry, libraries.map(::Library), STANDARD_UNIT)

/**
 * [entry] and the files it uses, to OCaml that's linked with what's compiled already: the
 * standard library, as [STANDARD_UNIT] (fckt --standard), and [libraries], each a library's
 * interface (fckt --library), whose packages the program uses without their code.
 */
fun compileSeparately(entry: File, dirs: List<File>, libraries: List<File>, imports: List<String> = emptyList()): String =
    compile(link(entry.readText(), Packages(dirs, entry, libraries.map(::Library), STANDARD_UNIT), imports).file)

/**
 * The tests in [dir], to one program: each function in its lower-case files, or in modules in
 * them, its sections, marked with [marker], an annotation, as `uk.m4xy.fktests.api.Test`, run by
 * [runner], a module, from a main made for them. What tests are written with and what runs them are
 * apart: fckt knows neither, and a test can't use the runner, whose package only the main sees,
 * unless they're [runnerTests], the runner's own. The main gives the runner the tests as a plan,
 * which the runner makes, of its own types, with:
 *
 * - `test<@E>(file: String, sections: List<String>, name: String, line: Int, body: @Fail @E () -> Unit)`,
 *   a test, in the modules [sections] names, outermost first;
 * - `suite<@E>(file: String, tests: List<...>)`, a file's tests;
 * - `plan<@E>(suites: List<...>)`, a suite for each file with any;
 *
 * and runs them with `main<@E>(args: List<String>, plan: ...): Unit`, as the program's arguments
 * say. fk-tests-runner's `uk.m4xy.fktests.runner.Runner` is one.
 *
 * With [fixtures], an annotation whose one field lists functions, as `uk.m4xy.fktests.api.Fixtures`,
 * a test's body runs inside the functions it lists, each given the rest, the first outermost: those
 * of its sections, if the annotation `@Propagates`, then its own. A fixture is a function of one
 * parameter, what it runs, as `withDatabase<A>(test: @Database () -> A): A`, which can handle what
 * the test does.
 *
 * With [separate], it's compiled as [compileSeparately] compiles a program.
 */
fun compileTests(
    dir: File,
    marker: String,
    runner: String,
    dirs: List<File>,
    libraries: List<File>,
    imports: List<String> = emptyList(),
    separate: Boolean = false,
    runnerTests: Boolean = false,
    fixtures: String? = null,
): String {
    val entry = File(dir, "<tests>")
    val packages = { if (separate || libraries.isNotEmpty()) Packages(listOf(dir) + dirs, entry, libraries.map(::Library), STANDARD_UNIT) else packages(listOf(dir) + dirs, entry, libraries) }
    val main = testsMain(dir, marker, runner, runnerTests, fixtures) { stub -> Checker(link(stub, packages(), imports).file).check() }
    return compile(link(main, packages(), imports).file)
}

/**
 * The main that runs [dir]'s tests: the plan of them, which it gives [runner]. What's written is
 * looked at first, for what a test can't be; then [check] checks the tests with a main that does
 * nothing, so what marks a test, and what its fixtures are, is what the names mean where they're
 * written.
 */
internal fun testsMain(dir: File, marker: String, runner: String, runnerTests: Boolean = false, fixtures: String? = null, check: (String) -> Program): String {
    fun split(name: String, what: String, example: String): Pair<String, String> {
        val pkg = name.substringBeforeLast('.', "")
        val simple = name.substringAfterLast('.')
        if (pkg.isEmpty() || !simple[0].isUpperCase()) throw CompileError(null, "$what is named with its package: $example")
        return pkg to simple
    }
    val (markerPackage, markerName) = split(marker, "what marks a test", "uk.m4xy.fktests.api.Test")
    val (runnerPackage, module) = split(runner, "a test runner", "uk.m4xy.fktests.runner.Runner")
    fixtures?.let { split(it, "what lists a test's fixtures", "uk.m4xy.fktests.api.Fixtures") }
    val errors = mutableListOf<CompileError>()
    val files = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "fk" && it.name[0].isLowerCase() }.sortedBy { it.name }
    // Each test by name, through its sections, as the main calls it.
    val named = mutableListOf<String>()
    for (file in files) {
        val parsed = Parser.parse(file.readText(), file.path)
        // A file with a main is a program of its own, as it is in a package.
        if (parsed.decls.any { it is Decl.Function && it.name == "main" }) continue
        val fileImports = parsed.imports[file.path].orEmpty()
        for (i in fileImports) {
            val path = i.path.joinToString(".")
            if (!runnerTests && (path == runnerPackage || path.startsWith("$runnerPackage."))) {
                errors += CompileError(i.pos, "$runnerPackage runs the tests, so they can't use it: a test uses what it's written with, as $marker")
            }
        }
        val marks = fileImports.any { i -> i.alias == null && i.path.joinToString(".") == if (i.wildcard) markerPackage else marker }
        fun walk(decls: List<Decl>, sections: List<String>) {
            for (d in decls) when (d) {
                is Decl.Module -> {
                    if (d.isPrivate && hasTests(d.decls, markerName)) errors += CompileError(d.pos, "a section is run from outside its file, so it can't be private")
                    walk(d.decls, sections + d.name)
                }
                is Decl.Function -> if (d.annotations.any { it.name == markerName }) {
                    named += (sections + d.name).joinToString(".")
                    when {
                        !marks -> errors += CompileError(d.pos, "a test is marked with $marker: import $marker")
                        d.params == null -> errors += CompileError(d.pos, "a test is a function, run as ${d.name}(): write ${d.name}() = ...")
                        d.extension || d.params.isNotEmpty() || d.typeParams.isNotEmpty() -> errors += CompileError(d.pos, "a test takes no parameters, as it's run as ${d.name}()")
                        d.isPrivate -> errors += CompileError(d.pos, "a test is run from outside its file, so it can't be private")
                    }
                }
                else -> {}
            }
        }
        walk(parsed.decls, emptyList())
    }
    CompileErrors.throwIfAny(errors)
    if (named.isEmpty()) throw CompileError(Pos(1, 1, dir.path), "no tests here: a test is a function marked @$markerName, as in @$markerName sums() = assert(1 + 1 == 2)")

    // What's marked, as the names mean once checked: with a main that names the tests, so their
    // files are brought in, as the main that runs them does, but runs nothing.
    // It names the runner's test too, for what the runner handles around a test.
    val program = check("import $runner\n\nmain(args: List<String>) = {\n  named = () -> {\n${named.joinToString("") { "    $it()\n" }}  }\n  runs = $module.test\n  Unit\n}\n")
    class Fixture(val pos: Pos, val decl: Decl.Function)
    class Found(val sections: List<String>, val decl: Decl.Function, val fixtures: List<Fixture>)
    val found = linkedMapOf<String, MutableList<Found>>()
    fun walk(decls: List<Decl>, enclosing: List<Decl.Module>) {
        for (d in decls) when (d) {
            is Decl.Module -> if (!d.isPackage) walk(d.decls, enclosing + d)
            is Decl.Function -> {
                val file = d.pos.file?.let(::File)?.takeIf { it.parentFile == dir } ?: continue
                val annotations = effectiveAnnotations(d, enclosing)
                if (annotations.none { it.decl?.name == marker }) continue
                val around = annotations.filter { fixtures != null && it.decl?.name == fixtures }.flatMap { a ->
                    a.args.flatMap { arg -> (arg.value as? Meta.ListOf)?.items ?: listOf(arg.value) }.map { m ->
                        val f = (m.resolved as? MetaRef.Function)?.function ?: throw CompileError(m.pos, "a fixture is a function, by name")
                        when {
                            f.isPrivate -> errors += CompileError(m.pos, "${f.simpleName} runs a test from outside its file, so it can't be private")
                            f.extension || f.params?.size != 1 -> errors += CompileError(m.pos, "a fixture takes what it runs, as its one parameter: ${f.simpleName}<A>(test: () -> A): A")
                        }
                        Fixture(m.pos, f)
                    }
                }
                found.getOrPut(file.name) { mutableListOf() } += Found(enclosing.map { it.simpleName }, d, around)
            }
            else -> {}
        }
    }
    walk(program.root, emptyList())
    CompileErrors.throwIfAny(errors)
    for (t in found.values.flatten()) errors += unhandled(program, "$runner.test", t.decl, t.fixtures.map { it.pos to it.decl })
    CompileErrors.throwIfAny(errors)
    fun string(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    val out = StringBuilder("import $runner\n\nmain(args: List<String>) = $module.main(args, $module.plan([\n")
    for ((file, tests) in found.toSortedMap()) {
        out.append("  $module.suite(${string(file)}, [\n")
        for (t in tests.sortedBy { it.decl.pos.line }) {
            // Each fixture runs the rest: the first, the second, ..., the test.
            val body = t.fixtures.foldRight("${t.decl.name}()") { f, inner -> "${f.decl.name}(() -> $inner)" }
            out.append("    $module.test(${string(file)}, [${t.sections.joinToString(", ") { string(it) }}], ${string(t.decl.simpleName)}, ${t.decl.pos.line}, () -> $body),\n")
        }
        out.append("  ]),\n")
    }
    out.append("]))\n")
    return out.toString()
}

/**
 * What [test] and its [fixtures] do that nothing around them handles: each fixture runs inside the
 * runner's [runnerTest], which handles what its body may do, and the fixtures before it, which each
 * handle what they give what they run; then the test runs inside them all. What has a default
 * handler, as `@Console`, is handled anyway.
 */
private fun unhandled(program: Program, runnerTest: String, test: Decl.Function, fixtures: List<Pair<Pos, Decl.Function>>): List<CompileError> {
    fun effects(t: Type?): Set<String> = (t as? Type.Fn)?.let { fn -> Type.row(fn.effects).first.map { it.name }.toSet() }.orEmpty()
    fun type(f: Decl.Function) = program.functionTypes[f]?.prune() as? Type.Fn
    // Only an effect with operations needs handling.
    val needs = program.effects.filter { it.values.isNotEmpty() }.map { it.name }.toSet()
    val runs = program.functionTypes.entries.firstOrNull { it.key.name == runnerTest }?.value?.prune() as? Type.Fn
    var handled = program.mainHandlers.map { it.first.name }.toSet() + effects(runs?.params?.lastOrNull()?.prune())
    fun shown(names: Set<String>) = names.sorted().joinToString(" ") { "@" + it.substringAfterLast('.') }
    val errors = mutableListOf<CompileError>()
    for ((pos, f) in fixtures) {
        val missing = effects(type(f)) intersect needs - handled
        if (missing.isNotEmpty()) errors += CompileError(pos, "the fixture ${f.simpleName} does ${shown(missing)}, which nothing it runs in handles: list a fixture that does before it")
        handled = handled + effects(type(f)?.params?.firstOrNull()?.prune())
    }
    val missing = effects(type(test)) intersect needs - handled
    if (missing.isNotEmpty()) {
        val by = if (fixtures.isEmpty()) "nothing it runs in handles" else "neither the runner nor its fixtures, ${fixtures.joinToString(", ") { it.second.simpleName }}, handle"
        errors += CompileError(test.pos, "'${test.simpleName}' does ${shown(missing)}, which $by: list a fixture that does, as @Fixtures(...)")
    }
    return errors
}

/** Whether [decls], or modules in them, have a function marked [marker]. */
private fun hasTests(decls: List<Decl>, marker: String): Boolean = decls.any { d ->
    (d is Decl.Function && d.annotations.any { it.name == marker }) || (d is Decl.Module && hasTests(d.decls, marker))
}

/**
 * A library: [packages], from [dirs], to OCaml, which is the OCaml module [unit], with the library's
 * interface written to [interfaceDir], for programs that use it to be checked against. It uses
 * [libraries] and the standard library compiled already, as a program does.
 */
fun compileLibrary(packages: List<String>, dirs: List<File>, libraries: List<File>, unit: String, interfaceDir: File): String {
    if (packages.isEmpty()) throw CompileError(null, "a library needs packages")
    val sources = Packages(dirs, null, libraries.map(::Library), STANDARD_UNIT)
    val ocaml = compile(link(packages.joinToString("") { "import $it.*\n" }, sources).file, showsAll = true)
    // Each of its files' public declarations, as they're written, without their code.
    val files = packages.flatMap { pkg ->
        val path = pkg.split('.')
        if (!sources.isPackage(path) || sources.unitOf(path) != null) throw CompileError(null, "$pkg isn't a package in ${dirs.joinToString(", ")}")
        sources.sourceFiles(path).filter { it.extension == "fk" }.map { f ->
            val parsed = Parser.parse(f.readText(), f.path)
            Triple(path, f, parsed)
        }.filter { (_, _, parsed) -> parsed.decls.none { it is Decl.Function && it.name == "main" } }
    }
    CompileErrors.throwIfAny(files.flatMap { (_, _, parsed) -> Interfaces.untyped(parsed) })
    interfaceDir.deleteRecursively()
    for ((path, f, parsed) in files) {
        val target = File(interfaceDir, path.joinToString("/") + "/" + f.nameWithoutExtension + ".fki")
        target.parentFile.mkdirs()
        target.writeText(Interfaces.write(parsed, f.path, f.readText()))
    }
    File(interfaceDir, Library.UNIT_FILE).writeText(unit + "\n")
    return ocaml
}

/** The whole standard library, to OCaml, as [STANDARD_UNIT]: what programs compiled separately are linked with. */
fun compileStandardLibrary(): String {
    val packages = STANDARD_FILES.map { it.substringBeforeLast('/').replace('/', '.') }.distinct()
    return compile(link(packages.joinToString("") { "import $it.*\n" }, Packages(emptyList(), null), whole = true).file, showsAll = true)
}

/** The OCaml module the standard library is, compiled on its own: fckt --standard writes fckt_std.ml. */
const val STANDARD_UNIT = "Fckt_std"

/**
 * A library compiled already: its interface, in [dir], a folder of packages whose files are its
 * declarations without their code, `.fki`; and the OCaml module its code is, [unit].
 */
private class Library(val dir: File) {
    val unit: String = File(dir, UNIT_FILE).takeIf { it.isFile }?.readText()?.trim()
        ?: throw CompileError(null, "${dir.path} isn't a library's interface: fckt --library writes one")

    companion object {
        /** The file in an interface that names the library's OCaml module. */
        const val UNIT_FILE = "unit"
    }
}

/** A checked program: [decls] are the entry file's own, with what each name refers to filled in. */
class Analysis(val decls: List<Decl>, val program: uk.m4xy.fckt.types.Program)

/**
 * [text], as [entry], checked with the files it uses, for tools: what each name in it refers to.
 * Each declaration's position names its file: null for [entry], a path, or `std/...` for the
 * standard library, and with [libraries], their interfaces' declarations. Throws [uk.m4xy.fckt.syntax.CompileError].
 */
fun analyse(text: String, entry: File?, dirs: List<File>, libraries: List<File> = emptyList()): Analysis {
    val linked = link(text, packages(dirs, entry, libraries))
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
    "lang/Propagates",
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
    "time/Clock",
    "time/Duration",
    "time/Instant",
    "time/SystemClock",
    "time/Times",
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

/** A file to read if it's used: one of the program's, the standard library's, or a library's interface ([isInterface]). */
class Source(val path: String, val isInterface: Boolean = false, val read: () -> String) {
    fun parse(code: String) = if (isInterface) Parser.parseInterface(code, path) else Parser.parse(code, path)
}

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
 * Where a program's packages are: each a folder, under one of [roots] or in one of [libraries]'
 * interfaces, or the standard library's, which with [standardUnit] is compiled already, as that
 * OCaml module. Names are matched against what's in a folder, so `ratios.fk` is never taken for `Ratios.fk`.
 */
private class Packages(
    private val roots: List<File>,
    private val entry: File?,
    private val libraries: List<Library> = emptyList(),
    private val standardUnit: String? = null,
) {
    private val listings = mutableMapOf<List<String>, List<File>>()

    /** The files in [pkg]'s folders, in each root and interface, but the program's own. */
    private fun files(pkg: List<String>): List<File> = listings.getOrPut(pkg) {
        (roots + libraries.map { it.dir }).map { File(it, pkg.joinToString("/")) }.filter { it.isDirectory }.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.canonicalFile != entry?.canonicalFile && (it.isDirectory || isCode(it)) }.sortedBy { it.name }
        }
    }

    /** A file of code: a program's, `.fk`, or a library's interface's, `.fki`. */
    private fun isCode(f: File) = f.isFile && (f.extension == "fk" || f.extension == "fki")

    /** [pkg]'s own files in [roots], the program's: not a library's. */
    fun sourceFiles(pkg: List<String>): List<File> = roots.map { File(it, pkg.joinToString("/")) }.filter { it.isDirectory }
        .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "fk" }.sortedBy { it.name } }

    private fun source(f: File) = Source(f.path, f.extension == "fki") { f.readText() }

    /**
     * The OCaml module [pkg]'s code is in, if it's compiled already: the standard library's, or a
     * library's whose interface has its files. A package is one library's, or the program's.
     */
    fun unitOf(pkg: List<String>): String? {
        if (standard(pkg)) return standardUnit
        val owners = libraries.filter { lib -> File(lib.dir, pkg.joinToString("/")).listFiles().orEmpty().any { isCode(it) } }
        val own = roots.any { root -> File(root, pkg.joinToString("/")).listFiles().orEmpty().any { isCode(it) && it.canonicalFile != entry?.canonicalFile } }
        val name = pkg.joinToString(".")
        if (owners.size > 1) throw CompileError(null, "package $name is in two libraries, ${owners.joinToString(" and ") { it.unit }}, so it's unclear which is meant")
        if (owners.isNotEmpty() && own) throw CompileError(null, "package $name is library ${owners.single().unit}'s, so it can't have files of its own here too")
        return owners.singleOrNull()?.unit
    }

    /** Whether [pkg] is the standard library or in it, which no folder of the program's can be. */
    private fun standard(pkg: List<String>) = pkg.firstOrNull() == STANDARD_PACKAGE

    /** The standard library's files directly in [pkg], by their names. */
    private fun standardFiles(pkg: List<String>): List<String> =
        STANDARD_FILES.filter { it.substringBeforeLast('/') == pkg.joinToString("/") }.map { it.substringAfterLast('/') }

    /** Whether [pkg] is a package: a folder, named as a value is, or one of the standard library's. */
    fun isPackage(pkg: List<String>): Boolean =
        if (standard(pkg)) STANDARD_FILES.any { it.startsWith(pkg.joinToString("/", postfix = "/")) }
        else pkg.isNotEmpty() && pkg.all { IDENTIFIER.matches(it) } && (roots + libraries.map { it.dir }).any { File(it, pkg.joinToString("/")).isDirectory }

    /** The module [name] in [pkg]: its file, `Name.fk`. */
    fun module(pkg: List<String>, name: String): Source? {
        if (!name[0].isUpperCase()) return null
        if (standard(pkg)) return "$name.fk".takeIf { it in standardFiles(pkg) }?.let { standardSource(pkg, it) }
        return files(pkg).firstOrNull { it.isFile && it.nameWithoutExtension == name }?.let(::source)
    }

    /** The names of [pkg]'s modules. */
    fun modules(pkg: List<String>): List<String> =
        if (standard(pkg)) standardFiles(pkg).filter { it[0].isUpperCase() }.map { it.removeSuffix(".fk") }
        else files(pkg).filter { it.isFile && it.name[0].isUpperCase() }.map { it.nameWithoutExtension }

    /** [pkg]'s lower-case files, whose declarations are the package's own. */
    fun declarationFiles(pkg: List<String>): List<Source> =
        if (standard(pkg)) standardFiles(pkg).filter { it[0].isLowerCase() }.map { standardSource(pkg, it) }
        else files(pkg).filter { it.isFile && it.name[0].isLowerCase() }.map(::source)

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
private fun link(text: String, packages: Packages, extraImports: List<String> = emptyList(), whole: Boolean = false): Linked {
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
    fun defaults(file: String?) = (DEFAULT_IMPORTS + if (file?.startsWith("std/") == true) emptyList() else extraImports)
        .map { Import(Pos(1, 1, file), it.removeSuffix(".*").split('.'), wildcard = it.endsWith(".*")) }

    fun packageContents(pkg: List<String>) = contents.getOrPut(pkg) { mutableListOf() }

    // A file with a main is a program of its own, so what it declares isn't its package's.
    fun declsOf(pkg: List<String>) = packageDecls.getOrPut(pkg) {
        packages.declarationFiles(pkg).flatMap { source ->
            val code = source.read()
            val parsed = source.parse(code)
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
        val parsed = source.parse(code)
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
        // With [whole], all of the standard library, as it's compiled on its own.
        if (name in used || whole) includeWhole(partial)
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
    // A package compiled already is all one library's, with what's in it: its module is that library's.
    fun compiledUnit(pkg: List<String>): String? {
        val unit = packages.unitOf(pkg) ?: return null
        for (inner in contents.keys) {
            if (inner.size <= pkg.size || inner.take(pkg.size) != pkg || contents[inner].isNullOrEmpty()) continue
            if (packages.unitOf(inner) != unit) {
                throw CompileError(null, "package ${inner.joinToString(".")} is in ${pkg.joinToString(".")}, which library $unit compiled, so it must be that library's too")
            }
        }
        return unit
    }
    // Each package a module named after its folder, inside its parent's.
    fun tree(pkg: List<String>): List<Decl> {
        val children = contents.keys.filter { it.size == pkg.size + 1 && it.take(pkg.size) == pkg }.distinct()
            .filter { child -> contents.keys.any { it.take(child.size) == child && contents[it].orEmpty().isNotEmpty() } }
        val packageModules = children.map { child -> Decl.Module(Pos(1, 1, null), child.last(), tree(child), isPackage = true, compiled = compiledUnit(child)) }
        val mine = contents[pkg].orEmpty()
        return packageModules + if (pkg.isEmpty()) mine.filter { it !in main.decls } else mine
    }
    // Every package on the way to one with contents has a module too.
    for (pkg in contents.keys.toList()) for (n in 1 until pkg.size) packageContents(pkg.take(n))
    val all = withSpecs(copyEffects(tree(emptyList()) + main.decls, linkedImports), linkedImports)
    return Linked(SourceFile(all, linkedImports), all.takeLast(main.decls.size))
}

/**
 * [decls] with each `effect OrdersDb = Database` made a copy of Database: the same operations,
 * under its own name, so a separate effect, handled on its own. What it copies is found as the
 * checker would find it, from the inside out.
 */
private fun copyEffects(decls: List<Decl>, imports: Map<String?, List<Import>>): List<Decl> {
    val scoped = Scoped(decls, imports)
    fun copy(d: Decl.Spec, scopes: List<List<Decl>>, seen: Set<Decl.Spec>): Decl {
        val ref = d.copyOf!!
        if (d in seen) throw CompileError(d.pos, "effect '${d.name}' is a copy of itself")
        val target = scoped.find(ref.name, scopes, d.pos.file) ?: throw CompileError(ref.pos, "unknown effect '${ref.name}'")
        val original = if (target is Decl.Spec && target.copyOf != null) copy(target, scopes, seen + d) else target
        if (original.annotations.none { it.name == EFFECT_NAME }) throw CompileError(ref.pos, "'${ref.name}' isn't an effect, so there's nothing to copy")
        return when (original) {
            is Decl.Spec -> (Copier({ it }).decl(original) as Decl.Spec)
                .copy(pos = d.pos, name = d.name, annotations = d.annotations, isPrivate = d.isPrivate, copyOf = null)
            is Decl.Record -> Decl.Record(d.pos, d.name, emptyList(), emptyList(), isAnnotation = true, annotations = d.annotations, isPrivate = d.isPrivate)
            else -> throw CompileError(ref.pos, "'${ref.name}' isn't an effect, so there's nothing to copy")
        }
    }
    return scoped.map { d, scopes -> if (d is Decl.Spec && d.copyOf != null) copy(d, scopes, emptySet()) else d }
}

/**
 * [decls] with what specs and modules say filled in, from the inside out, as the checker finds names:
 *  - a spec that builds on others, `spec Monad : Applicative`, has their types and values too;
 *  - a module that follows a spec has each of the spec's defaults it doesn't define itself;
 *  - each function of a module with type parameters, `module Results<E>`, takes them too.
 */
private fun withSpecs(decls: List<Decl>, imports: Map<String?, List<Import>>): List<Decl> {
    // Specs first, with what they build on, which may be anywhere.
    val before = Scoped(decls, imports)
    val flat = java.util.IdentityHashMap<Decl.Spec, Decl.Spec>()
    fun flatten(d: Decl.Spec, seen: Set<Decl.Spec>): Decl.Spec = flat[d] ?: run {
        if (d in seen) throw CompileError(d.pos, "spec '${d.simpleName}' builds on itself")
        val inherited = d.parents.map { ref ->
            val parent = before.find(ref.name, before.scopesOf(d), d.pos.file) as? Decl.Spec ?: throw CompileError(ref.pos, "unknown spec '${ref.name}'")
            if (ref.args.isNotEmpty() || parent.typeParams.isNotEmpty()) throw CompileError(ref.pos, "a spec can't build on one with type arguments yet")
            if (parent.annotations.any { it.name == EFFECT_NAME } || d.annotations.any { it.name == EFFECT_NAME }) throw CompileError(ref.pos, "an effect can't build on another yet")
            flatten(parent, seen + d)
        }
        // Its own, in place of what it builds on has of the same name.
        val types = (inherited.flatMap { it.types } + d.types).associateBy { it.name }.values.toList()
        val own = d.values.map { it.name }.toSet()
        val values = inherited.flatMap { it.values }.filter { it.name !in own }.distinctBy { it.name } + d.values
        // What it builds on stays named, for the checker: a Monad can be given where an Applicative is wanted.
        d.copy(types = types, values = values).also { flat[d] = it }
    }
    val specced = before.map { d, _ -> if (d is Decl.Spec && d.parents.isNotEmpty()) flatten(d, emptySet()) else d }

    // Then the modules, against the specs as they are now.
    val after = Scoped(specced, imports)
    return after.map { d, scopes ->
        if (d !is Decl.Module) return@map d
        var decls = d.decls
        val ref = d.spec
        val spec = ref?.let { after.find(it.name, scopes, d.pos.file) as? Decl.Spec }
        if (spec != null && ref != null) {
            val defined = decls.filterIsInstance<Decl.Function>().map { it.name }.toSet()
            val fixed = fixedTypes(spec, ref)
            decls = decls + spec.values.filter { it.default != null && it.name !in defined }.map { v -> defaultOf(v, fixed) }
        }
        if (d.typeParams.isNotEmpty()) {
            decls = decls.map { inner ->
                if (inner !is Decl.Function) return@map inner
                inner.typeParams.firstOrNull { it in d.typeParams }?.let {
                    throw CompileError(inner.pos, "'${inner.methodName}' is in ${d.name}<${d.typeParams.joinToString()}>, so its own type parameters can't be named $it too")
                }
                inner.copy(typeParams = inner.typeParams + d.typeParams)
            }
        }
        d.copy(decls = decls)
    }
}

/**
 * For a module following [ref], what each of [spec]'s types it gives in type arguments stands
 * for: `Monad<Option>` makes `M<A>` an `Option<A>`, as the checker's aliases do.
 */
private fun fixedTypes(spec: Decl.Spec, ref: TypeRef.Named): Map<String, (List<TypeRef>) -> TypeRef> =
    spec.types.zip(ref.args).associate { (t, arg) ->
        t.name to { args: List<TypeRef> ->
            if (t.typeParams.isEmpty() || arg !is TypeRef.Named) arg
            else if (arg.args.none { it is TypeRef.Named && it.name == "_" }) arg.copy(args = arg.args + args)
            else {
                val fill = args.iterator()
                arg.copy(args = arg.args.map { if (it is TypeRef.Named && it.name == "_" && fill.hasNext()) fill.next() else it })
            }
        }
    }

/** A module's copy of a spec's default [v], with the types the module gave the spec's in [fixed]. */
private fun defaultOf(v: uk.m4xy.fckt.syntax.SpecValue, fixed: Map<String, (List<TypeRef>) -> TypeRef>): Decl.Function {
    val copier = Copier({ it })
    fun type(t: TypeRef): TypeRef = when (t) {
        is TypeRef.Named -> fixed[t.name]?.invoke(t.args.map(::type)) ?: t.copy(args = t.args.map(::type))
        is TypeRef.Function -> t.copy(params = t.params.map(::type), result = type(t.result))
        is TypeRef.Tuple -> t.copy(elements = t.elements.map(::type))
        is TypeRef.Effects -> t
    }
    return Decl.Function(
        v.pos, v.name, v.typeParams,
        v.params?.map { p -> copier.param(p).let { it.copy(type = it.type?.let(::type)) } },
        type(copier.type(v.type)), copier.expr(v.default!!), annotations = v.annotations,
    )
}

/**
 * Declarations with the scopes each is in, from the inside out, to find names as the checker
 * would: in the scopes, then through what the file imports.
 */
private class Scoped(private val decls: List<Decl>, private val imports: Map<String?, List<Import>>) {
    private val scopes = java.util.IdentityHashMap<Decl, List<List<Decl>>>()

    init {
        fun walk(here: List<Decl>, outer: List<List<Decl>>) {
            val inner = listOf(here) + outer
            for (d in here) {
                scopes[d] = inner
                if (d is Decl.Module) walk(d.decls, inner)
            }
        }
        walk(decls, emptyList())
    }

    fun scopesOf(d: Decl): List<List<Decl>> = scopes[d].orEmpty()

    private fun lookup(parts: List<String>, scope: List<Decl>): Decl? {
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

    /** Each declaration, given the scopes it's in, replaced by what [f] makes of it, from the inside out. */
    fun map(f: (Decl, List<List<Decl>>) -> Decl): List<Decl> {
        fun walk(here: List<Decl>, outer: List<List<Decl>>): List<Decl> {
            val inner = listOf(here) + outer
            return here.map { d -> f(if (d is Decl.Module) d.copy(decls = walk(d.decls, inner)) else d, inner) }
        }
        return walk(decls, emptyList())
    }
}

private fun compile(file: SourceFile, showsAll: Boolean = false): String {
    val program = Checker(file).check()
    val tree = Lowering(program, showsAll).lower()
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
