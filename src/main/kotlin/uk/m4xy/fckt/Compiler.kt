package uk.m4xy.fckt

import uk.m4xy.fckt.ocaml.Lowering
import uk.m4xy.fckt.ocaml.Printer
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Lexer
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.syntax.Pos
import uk.m4xy.fckt.syntax.RawPart
import uk.m4xy.fckt.syntax.SourceFile
import uk.m4xy.fckt.syntax.T
import uk.m4xy.fckt.syntax.Token
import uk.m4xy.fckt.types.Checker
import uk.m4xy.kastor.resolve
import java.io.File

/** fckt source to OCaml source, with the standard library. Throws [uk.m4xy.fckt.syntax.CompileError]. */
fun compile(source: String): String = link(source, emptyMap())

/**
 * [entry] and the files it uses, to one OCaml source. Each file is a module named after it:
 * `ratios.fk` is Ratios, and `shared-cases.fk` is SharedCases. A name a file uses but doesn't
 * declare brings in the file of that name from [dirs], or from the standard library, whose own
 * names bring in more, as OCaml finds a module's file from its name.
 */
fun compile(entry: File, dirs: List<File>): String {
    val available = linkedMapOf<String, Source>()
    for (dir in dirs) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "fk" }?.sortedBy { it.name } ?: continue
        for (file in files) {
            if (file.canonicalFile != entry.canonicalFile) available.putIfAbsent(moduleName(file), Source(file.path) { file.readText() })
        }
    }
    return link(entry.readText(), available)
}

/** A file to read if it's used: one of the program's, or the standard library's. */
class Source(val path: String, val read: () -> String)

/** The standard library's modules, in `std/` among the compiler's resources. */
private val STD = listOf("options", "results", "validated", "lists").associate { name ->
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

/**
 * The program [text] with what it uses: files from [available] then the standard library, as
 * modules, and the prelude's declarations, at the top level. The program's own come first.
 */
private fun link(text: String, available: Map<String, Source>): String {
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
        prelude += decl
        pending += uses to declaredNames(listOf(decl))
    }
    pending += mentioned(text, null) to declaredNames(main.decls)
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
            preludeDecls.firstOrNull { (d, _) -> d.name == name && !(d is Decl.Function && d.extension) }?.let { (d, uses) -> include(d, uses) }
        }
    }
    return compile(SourceFile(prelude + modules + main.decls))
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
        is Decl.Spec -> d.types.map { it.name }
        else -> emptyList()
    }
}.toSet()
