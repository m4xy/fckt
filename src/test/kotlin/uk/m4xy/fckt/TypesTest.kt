package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.types.Program
import uk.m4xy.fckt.types.Type
import java.io.File
import kotlin.test.assertEquals

/**
 * fckt.checker, written in fckt, finds each function and value to have the type this compiler's
 * checker does, over each conformance case, and each package of the standard library and of the
 * fckt libraries here, its own among them. Types are compared without what functions do, which
 * comes after.
 */
class TypesTest {
    private val std = File("std/src")

    private val cases = File("conformance").walk().filter { it.isFile && it.extension == "fk" }.sortedBy { it.path }.toList()

    private class Root(val dir: File, val own: Boolean = true, val uses: List<File> = emptyList())

    private class Package(val root: Root, val name: String) {
        val files: List<File> get() = File(root.dir, name.replace('.', '/')).listFiles().orEmpty().filter { it.extension == "fk" }.sortedBy { it.name }
    }

    private val packages: List<Package> =
        listOf(Root(std, own = false), Root(File("symbols/src")), Root(File("parser/src")), Root(File("checker/src"), uses = listOf(File("parser/src")))).flatMap { root ->
            root.dir.walk().filter { d -> d.isDirectory && d.listFiles().orEmpty().any { it.extension == "fk" } }.sortedBy { it.path }
                .map { Package(root, it.relativeTo(root.dir).path.replace('/', '.')) }.toList()
        }

    /**
     * Cases whose types come from what's still to come in fckt.checker, by why: they aren't compared
     * until it's there.
     */
    private val later = mapOf(
        "conformance/a-spec-builds-on-another-and-its-defaults-are-what-a-module-leaves-out-1.fk" to
            "a spec's defaults, copied into each module that leaves them out, and a functor's parameters: milestone 5",
    )

    /** What fckt.checker makes of each case and package, by file, and its errors, by what was checked. */
    private val written: Map<String, String> by lazy {
        val arguments = cases.flatMap { listOf("case", it.path) } +
            packages.flatMap { p -> listOf("package", p.root.dir.path, if (p.root.own) "own" else "other", p.name, p.root.uses.joinToString(",") { it.path }.ifEmpty { "-" }) }
        SelfHosted.written("checker.fk", listOf("types") + arguments, listOf(File("parser/src"), File("checker/src")))
    }

    @TestFactory
    fun `in each conformance case, fckt's checker finds each function's type as this one does`() = cases.map { file ->
        DynamicTest.dynamicTest(file.path) {
            assumeTrue(file.path !in later, later[file.path])
            val program = analysed(file.readText(), emptyList(), listOf(std))
            assumeTrue(program != null, "it doesn't compile")
            assertEquals(null, written["errors ${file.path}"], "errors")
            assertEquals(described(program!!, null), written[file.path])
        }
    }

    @TestFactory
    fun `in each package, fckt's checker finds each function's type as this one does`() = packages.map { pkg ->
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

    /** As checker.fk writes them: each function and value in the file, "name: type", by name. */
    private fun described(program: Program, file: String?): String =
        program.functionTypes.entries.filter { it.key.pos.file == file }.map { (decl, type) -> "${decl.name}: ${text(type, mutableMapOf())}\n" }.sorted().joinToString("")

    /** A type as checker.fk writes it: unknowns T1, T2, in the order they're met; without what functions do or regions. */
    private fun text(type: Type, names: MutableMap<Type.Var, String>): String = when (val t = type.prune()) {
        is Type.Var -> names.getOrPut(t) { "T${names.size + 1}" }
        is Type.Con -> if (t.name == Type.EFFECTS) "effects" else if (t.args.isEmpty()) t.name else "${t.name}<${t.args.joinToString(", ") { text(it, names) }}>"
        is Type.Fn -> "(${t.params.joinToString(", ") { text(it, names) }}) -> ${text(t.result, names)}"
    }
}
