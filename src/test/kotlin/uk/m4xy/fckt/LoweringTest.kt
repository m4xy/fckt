package uk.m4xy.fckt

import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * fckt.lowering, written in fckt, makes OCaml of each conformance case that does what this
 * compiler's does: linked with the core and the standard library as this compiler compiles them on
 * their own, it prints what the case says it prints. A case it can't lower yet is skipped, saying why.
 */
// One instance for every test, so what's worked out once, lazily, is shared by each factory's.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoweringTest {
    /** The cases that run and say what they print. */
    private val cases = File("conformance").listFiles { f -> f.extension == "fk" }!!.sortedBy { it.name }.filter { printed(it.readText()) != null }

    /** The examples, each a program of its own. */
    private val examples = File("examples").listFiles { f -> f.extension == "fk" }!!.sortedBy { it.name }

    /** What fckt.lowering makes of each case and example, by its path. */
    private val lowered: Map<String, String> by lazy {
        SelfHosted.written("lowered.fk", (cases + examples).map { it.path }, listOf(File("parser/src"), File("checker/src"), File("lowering/src")))
    }

    /** A folder with the core and the standard library in it, each compiled on its own, as OCaml's compiled units. */
    private val linked: File by lazy {
        val dir = kotlin.io.path.createTempDirectory("fckt-linked").toFile()
        File(dir, "fckt_core.ml").writeText(compileCore())
        val std = File("std/src")
        val packages = File(std, "fckt").walk().filter { it.isDirectory && it.listFiles()!!.any { f -> f.extension == "fk" } }
            .map { it.relativeTo(std).path.replace('/', '.') }.sorted().toList()
        File(dir, "fckt_std.ml").writeText(compileLibrary(packages, listOf(std), emptyList(), "Fckt_std", File(dir, "std.fki")))
        ocamlopt(dir, listOf("-c", "fckt_core.ml"))
        ocamlopt(dir, listOf("-c", "fckt_std.ml"))
        dir
    }

    @TestFactory
    fun `fckt's lowering, written in fckt, makes OCaml that prints what each case prints`() = cases.map { file ->
        DynamicTest.dynamicTest(file.path) {
            val ml = lowered[file.path] ?: error("nothing was written for ${file.path}")
            assumeTrue(!ml.startsWith("! "), ml.lineSequence().first())
            assertEquals(printed(file.readText())!!.joinToString("") { "$it\n" }, run(ml))
        }
    }

    @TestFactory
    fun `each example, lowered by fckt's lowering, prints what this compiler's does`() = examples.map { file ->
        DynamicTest.dynamicTest(file.path) {
            val ml = lowered[file.path] ?: error("nothing was written for ${file.path}")
            assumeTrue(!ml.startsWith("! "), ml.lineSequence().first())
            assertEquals(Ocaml.run(compile(file.readText(), others = listOf(File("std/src")))), run(ml))
        }
    }

    /** [ml] compiled to native code, linked with the core and the standard library, and run: what it prints. */
    private fun run(ml: String): String {
        val dir = kotlin.io.path.createTempDirectory("fckt-lowered").toFile()
        try {
            File(dir, "main.ml").writeText(ml)
            val compiled = ocamlopt(dir, listOf("unix.cmxa", "-I", linked.path, File(linked, "fckt_core.cmx").path, File(linked, "fckt_std.cmx").path, "main.ml", "-o", "main"), check = false)
            assertEquals(0, compiled.first, "OCaml failed:\n${compiled.second}\n${Ocaml.excerpt(compiled.second, ml)}")
            val running = ProcessBuilder(File(dir, "main").path).redirectErrorStream(true).start()
            val output = running.inputStream.bufferedReader().readText()
            running.waitFor(30, TimeUnit.SECONDS)
            return output
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Runs ocamlopt, with OCaml's unix library, in [dir]: its exit code and what it said, failing if [check] and it fails. */
    private fun ocamlopt(dir: File, args: List<String>, check: Boolean = true): Pair<Int, String> {
        assumeTrue(Ocaml.path != null, "ocaml isn't installed")
        val ocamlopt = File(File(Ocaml.path!!).parentFile, "ocamlopt").path
        val process = ProcessBuilder(listOf(ocamlopt, "-I", "+unix", "-w", "-a") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (check) assertEquals(0, process.exitValue(), "ocamlopt ${args.joinToString(" ")} failed:\n$output")
        return process.exitValue() to output
    }

    /** What a case says it prints, a line each; nothing, if it says nothing; or null if it fails to compile or only compiles. */
    private fun printed(source: String): List<String>? {
        val directives = source.lines().mapNotNull { Regex("^\\s*// (prints|error|compiles)(?:: ?(.*))?$").find(it) }.map { it.groupValues[1] to it.groupValues[2] }
        return if (directives.any { it.first != "prints" }) null else directives.map { it.second }
    }
}
