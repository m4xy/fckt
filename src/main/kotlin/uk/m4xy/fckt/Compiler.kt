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

/** fckt source to OCaml source. Throws [uk.m4xy.fckt.syntax.CompileError]. */
fun compile(source: String): String = compile(Parser.parse(source))

/**
 * [entry] and the files it uses, to one OCaml source. Each file is a module named after it:
 * `ratios.fk` is Ratios, and `shared-cases.fk` is SharedCases. A name a file uses but doesn't
 * declare brings in the file of that name from [dirs], whose own names bring in more, as OCaml
 * finds a module's file from its name.
 */
fun compile(entry: File, dirs: List<File>): String {
    val available = linkedMapOf<String, File>()
    for (dir in dirs) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "fk" }?.sortedBy { it.name } ?: continue
        for (file in files) if (file.canonicalFile != entry.canonicalFile) available.putIfAbsent(moduleName(file), file)
    }
    val text = entry.readText()
    val main = Parser.parse(text)
    val modules = mutableListOf<Decl>()
    val loaded = mutableSetOf<String>()
    val pending = ArrayDeque(listOf(Triple(text, null as String?, main)))
    while (pending.isNotEmpty()) {
        val (source, path, file) = pending.removeFirst()
        val declared = declaredNames(file.decls)
        for (name in mentioned(source, path)) {
            if (name in declared || !loaded.add(name)) continue
            val found = available[name] ?: continue
            val parsed = Parser.parse(found.readText(), found.path)
            modules += Decl.Module(Pos(1, 1, found.path), name, parsed.decls)
            pending += Triple(found.readText(), found.path, parsed)
        }
    }
    return compile(SourceFile(modules + main.decls))
}

private fun compile(file: SourceFile): String {
    val program = Checker(file).check()
    val tree = Lowering(program).lower()
    return Printer.print(tree.resolve(Unit))
}

/** The module a file is: its name in PascalCase, `shared-cases.fk` as SharedCases. */
fun moduleName(file: File): String =
    file.nameWithoutExtension.split('-', '_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

/** Every capitalised name in [source], including inside string templates: the modules it may use. */
private fun mentioned(source: String, path: String?): Set<String> {
    val names = linkedSetOf<String>()
    fun scan(tokens: List<Token>) {
        for (token in tokens) {
            if (token.kind == T.IDENT && token.text[0].isUpperCase()) names += token.text
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
