package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.types.Program
import java.io.File
import kotlin.test.assertEquals

/**
 * fckt.checker, written in fckt, takes each type's name to mean what this compiler's checker does:
 * the same declaration, by its full name. It's held to it over each conformance case, and over each
 * package of the standard library and of the fckt libraries here, its own among them.
 */
class NamesTest {
    private val std = File("std/src")

    private val cases = File("conformance").walk().filter { it.isFile && it.extension == "fk" }.sortedBy { it.path }.toList()

    /** Each package, by the folder it's under and whether that's the program's own or another project's. */
    private val packages: List<Package> =
        listOf(Root(std, own = false), Root(File("symbols/src")), Root(File("parser/src")), Root(File("checker/src"), uses = listOf(File("parser/src")))).flatMap { root ->
            root.dir.walk().filter { d -> d.isDirectory && d.listFiles().orEmpty().any { it.extension == "fk" } }.sortedBy { it.path }
                .map { Package(root, it.relativeTo(root.dir).path.replace('/', '.')) }.toList()
        }

    /** A folder of packages, the program's own or another project's, and the other projects' it uses. */
    private class Root(val dir: File, val own: Boolean = true, val uses: List<File> = emptyList())

    private class Package(val root: Root, val name: String) {
        val files: List<File> get() = File(root.dir, name.replace('.', '/')).listFiles().orEmpty().filter { it.extension == "fk" }.sortedBy { it.name }
    }

    /**
     * Cases whose names mean what they do through what's still to come in fckt.checker, by why: they
     * aren't compared until it's there.
     */
    private val later = mapOf(
        "conformance/a-spec-builds-on-another-and-its-defaults-are-what-a-module-leaves-out-1.fk" to
            "a spec's defaults, copied into each module that leaves them out, and a functor's parameters: milestone 5",
    )

    /** What fckt.checker makes of each case and package, by file. */
    private val written: Map<String, String> by lazy {
        val arguments = cases.flatMap { listOf("case", it.path) } +
            packages.flatMap { p ->
                listOf("package", p.root.dir.path, if (p.root.own) "own" else "other", p.name, p.root.uses.joinToString(",") { it.path }.ifEmpty { "-" })
            }
        SelfHosted.written("checker.fk", listOf("names") + arguments, listOf(File("parser/src"), File("checker/src")))
    }

    @TestFactory
    fun `in each conformance case, fckt's checker takes each type's name to mean what this one does`() = cases.map { file ->
        DynamicTest.dynamicTest(file.path) {
            assumeTrue(file.path !in later, later[file.path])
            val program = analysed(file.readText(), emptyList(), listOf(std))
            // What a program that doesn't compile names isn't compared yet.
            assumeTrue(program != null, "it doesn't compile")
            assertEquals(null, written["errors ${file.path}"], "errors")
            assertEquals(described(program!!, null), written[file.path])
        }
    }

    @TestFactory
    fun `in each package, fckt's checker takes each type's name to mean what this one does`() = packages.map { pkg ->
        DynamicTest.dynamicTest("${pkg.root.dir.path}: ${pkg.name}") {
            val program = analysed("import ${pkg.name}.*\n", if (pkg.root.own) listOf(pkg.root.dir) else emptyList(), listOf(std) + pkg.root.uses + if (pkg.root.own) emptyList() else listOf(pkg.root.dir))
            assertEquals(null, written["errors ${pkg.name}"], "errors")
            for (file in pkg.files) assertEquals(described(program!!, file.path), written[file.path], file.path)
        }
    }

    private fun analysed(text: String, dirs: List<File>, others: List<File>): Program? = try {
        analyse(text, null, dirs, others = others.distinct()).program
    } catch (e: CompileError) {
        null
    }

    /** As names.fk writes them: each name in the file that means a declared type or a spec, in order. */
    private fun described(program: Program, file: String?): String =
        program.typeNames.entries.filter { it.key.pos.file == file }.sortedWith(compareBy({ it.key.pos.line }, { it.key.pos.col }))
            .joinToString("") { (ref, name) -> "${ref.pos.line}:${ref.pos.col} ${ref.name} $name\n" }
}
