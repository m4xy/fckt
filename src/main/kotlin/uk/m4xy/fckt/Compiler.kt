package uk.m4xy.fckt

import uk.m4xy.fckt.ocaml.Lowering
import uk.m4xy.fckt.ocaml.Printer
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Copier
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.RawPart
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.syntax.Token
import uk.m4xy.fckt.types.Checker
import uk.m4xy.fckt.types.EFFECT
import uk.m4xy.fckt.types.EFFECT
import uk.m4xy.fckt.types.MUTATING
import uk.m4xy.kastor.resolve
import java.io.File

/** fckt source to OCaml source, with the standard library. Throws [uk.m4xy.fckt.syntax.CompileError]. */
fun compile(source: String): String = compile(link(source, emptyMap()).file)

/**
 * [entry] and the files it uses, to one OCaml source. Each file is a module named after it:
 * `ratios.fk` is Ratios, and `shared-cases.fk` is SharedCases. A name a file uses but doesn't
 * declare brings in the file of that name from [dirs], or from the standard library, whose own
 * names bring in more, as OCaml finds a module's file from its name.
 */
fun compile(entry: File, dirs: List<File>): String = compile(entry.readText(), entry, dirs)

/** [text] as the contents of [entry], which an editor may not have saved yet, and the files it uses. */
fun compile(text: String, entry: File, dirs: List<File>): String = compile(link(text, available(entry, dirs)).file)

/** A checked program: [decls] are the entry file's own, with what each name refers to filled in. */
class Analysis(val decls: List<Decl>, val program: uk.m4xy.fckt.types.Program)

/**
 * [text], as [entry], checked with the files it uses, for tools: what each name in it refers to.
 * Each declaration's position names its file: null for [entry], a path, or `std/...` for the
 * standard library. Throws [uk.m4xy.fckt.syntax.CompileError].
 */
fun analyse(text: String, entry: File?, dirs: List<File>): Analysis {
    val linked = link(text, available(entry, dirs))
    // A when that misses some cases is still clear about what its names refer to.
    return Analysis(linked.main, Checker(linked.file, coverage = false).check())
}

/** The `.fk` files in [dirs] other than [entry], by module name; the first of a name wins. */
private fun available(entry: File?, dirs: List<File>): Map<String, Source> {
    val available = linkedMapOf<String, Source>()
    for (dir in dirs) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "fk" }?.sortedBy { it.name } ?: continue
        for (file in files) {
            if (file.canonicalFile != entry?.canonicalFile) available.putIfAbsent(moduleName(file), Source(file.path) { file.readText() })
        }
    }
    return available
}

/** The source of the standard library's [name] (`prelude`, `options`, ...), for tools that read it. */
fun standardLibrary(name: String): String? =
    Source::class.java.getResourceAsStream("/std/$name.fk")?.use { it.reader().readText() }

/** The standard library's modules, by file name. */
val STANDARD_MODULES = listOf("options", "results", "validated", "lists")

/** A file to read if it's used: one of the program's, or the standard library's. */
class Source(val path: String, val read: () -> String)

/** The standard library's modules, in `std/` among the compiler's resources. */
private val STD = STANDARD_MODULES.associate { name ->
    moduleName(File("$name.fk")) to Source("std/$name.fk") { resource("std/$name.fk") }
}

private fun resource(path: String): String =
    Source::class.java.getResourceAsStream("/$path")?.use { it.reader().readText() } ?: error("missing $path")

private val PRELUDE_TEXT by lazy { resource("std/prelude.fk") }

/**
 * The prelude's declarations, each with the names it uses, which may bring in more. Parsed for
 * each program, since checking one fills in its declarations.
 */
private fun prelude(): List<Pair<Decl, Set<String>>> {
    val path = "std/prelude.fk"
    val text = PRELUDE_TEXT
    val decls = Parser.parse(text, path).decls
    val lines = text.lines()
    return decls.mapIndexed { i, d ->
        // A declaration runs to the next one; the names in between are what it uses.
        val until = decls.getOrNull(i + 1)?.pos?.line?.minus(1) ?: lines.size
        d to mentioned(lines.subList(d.pos.line - 1, until).joinToString("\n"), null)
    }
}

/** A program with what it uses ([file]), and the program's own declarations ([main]). */
private class Linked(val file: SourceFile, val main: List<Decl>)

/**
 * The program [text] with what it uses: files from [available] then the standard library, as
 * modules, and the prelude's declarations, at the top level. The program's own come first.
 */
private fun link(text: String, available: Map<String, Source>): Linked {
    val main = Parser.parse(text)
    val preludeDecls = prelude()
    val own = main.decls.map { it.name }.toSet()
    val prelude = mutableListOf<Decl>()
    val modules = mutableListOf<Decl>()
    val seen = mutableSetOf<String>()
    val seenMethods = mutableSetOf<String>()
    // Names to look up, each with the names declared where it's used.
    val pending = ArrayDeque<Pair<Set<String>, Set<String>>>()
    fun include(decl: Decl, uses: Set<String>) {
        // A declaration can be reached by several names: a handled effect by its own and its operations'.
        if (prelude.any { it === decl }) return
        prelude += decl
        pending += uses to declaredNames(listOf(decl))
    }
    // A var's effect, which assigning one has without naming it; what xs[i] and xs[i] = v call; and
    // what `effect Name` declares one with.
    pending += (mentioned(text, null) + MUTATING + "get" + "set" + EFFECT) to declaredNames(main.decls)
    while (pending.isNotEmpty()) {
        val (names, declared) = pending.removeFirst()
        for (name in names) {
            // A method name brings in each of the prelude's extensions by that name, as any of them
            // may be the one called, unless the program has its own for that type.
            if (seenMethods.add(name)) {
                for ((decl, uses) in preludeDecls) {
                    if (decl is Decl.Function && decl.extension && decl.methodName == name && decl.name !in own) include(decl, uses)
                }
            }
            if (name in declared || name in own || !seen.add(name)) continue
            val source = available[name] ?: STD[name]
            if (source != null) {
                val code = source.read()
                val parsed = Parser.parse(code, source.path)
                modules += Decl.Module(Pos(1, 1, source.path), name, parsed.decls)
                pending += mentioned(code, source.path) to declaredNames(parsed.decls)
                continue
            }
            // A declaration of that name, or the handled effect with an operation of that name.
            preludeDecls.firstOrNull { (d, _) ->
                (d.name == name && !(d is Decl.Function && d.extension)) || (d is Decl.Spec && d.annotations.any { it.name == EFFECT } && d.values.any { it.name == name })
            }?.let { (d, uses) -> include(d, uses) }
        }
    }
    val all = copyEffects(prelude + modules + main.decls)
    return Linked(SourceFile(all), all.takeLast(main.decls.size))
}

/**
 * [decls] with each `effect OrdersDb = Database` made a copy of Database: the same operations,
 * under its own name, so a separate effect, handled on its own. What it copies is found as the
 * checker would find it, from the inside out.
 */
private fun copyEffects(decls: List<Decl>): List<Decl> {
    fun find(name: String, scopes: List<List<Decl>>): Decl? {
        val parts = name.split('.')
        for (scope in scopes) {
            var here = scope
            for ((i, part) in parts.withIndex()) {
                val d = here.firstOrNull { it.name == part && it !is Decl.Function } ?: break
                if (i == parts.lastIndex) return d
                here = (d as? Decl.Module)?.decls ?: break
            }
        }
        return null
    }
    fun copy(d: Decl.Spec, scopes: List<List<Decl>>, seen: Set<Decl.Spec>): Decl {
        val ref = d.copyOf!!
        if (d in seen) throw CompileError(d.pos, "effect '${d.name}' is a copy of itself")
        val target = find(ref.name, scopes) ?: throw CompileError(ref.pos, "unknown effect '${ref.name}'")
        val original = if (target is Decl.Spec && target.copyOf != null) copy(target, scopes, seen + d) else target
        if (original.annotations.none { it.name == EFFECT }) throw CompileError(ref.pos, "'${ref.name}' isn't an effect, so there's nothing to copy")
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
