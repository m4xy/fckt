package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * fckt's compiler written in fckt compiles itself: built by this compiler, it compiles its own
 * source to OCaml, which, built, compiles it again to the same OCaml.
 */
class BootstrapTest {
    @Test
    fun `fckt's compiler, written in fckt, compiles itself to a compiler that compiles it the same`() {
        assumeTrue(Ocaml.path != null, "ocaml isn't installed")
        val dir = kotlin.io.path.createTempDirectory("fckt-bootstrap").toFile()
        try {
            val folders = listOf("parser/src", "checker/src", "lowering/src")
            linkWith(dir)
            // Built by this compiler.
            val first = File(dir, "first.ml").apply { writeText(compile(File("compiler/src/main.fk"), folders.map(::File), others = listOf(File("std/src")))) }
            build(dir, first.name, "first")
            // Compiling itself, and what that makes compiling itself again.
            run(File(dir, "first"), File(dir, "second.ml"), folders)
            build(dir, "second.ml", "second")
            run(File(dir, "second"), File(dir, "third.ml"), folders)
            assertEquals(File(dir, "second.ml").readText(), File(dir, "third.ml").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The core and the standard library, compiled on their own into [dir], as what fckt.compiler writes is linked with. */
    private fun linkWith(dir: File) {
        File(dir, "fckt_core.ml").writeText(compileCore())
        val std = File("std/src")
        val packages = File(std, "fckt").walk().filter { it.isDirectory && it.listFiles()!!.any { f -> f.extension == "fk" } }
            .map { it.relativeTo(std).path.replace('/', '.') }.sorted().toList()
        File(dir, "fckt_std.ml").writeText(compileLibrary(packages, listOf(std), emptyList(), "Fckt_std", File(dir, "std.fki")))
        ocamlopt(dir, listOf("-c", "fckt_core.ml"))
        ocamlopt(dir, listOf("-c", "fckt_std.ml"))
    }

    /** [ml] in [dir], linked with the core and the standard library, as [binary]. */
    private fun build(dir: File, ml: String, binary: String) = ocamlopt(dir, listOf("unix.cmxa", "fckt_core.cmx", "fckt_std.cmx", ml, "-o", binary))

    /** fckt.compiler, as [compiler], compiling itself to [out]. */
    private fun run(compiler: File, out: File, folders: List<String>) {
        val process = ProcessBuilder(listOf(compiler.path, "compiler/src/main.fk", out.path) + folders).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.MINUTES)
        assertEquals(0, process.exitValue(), "${compiler.name} failed:\n$output")
    }

    private fun ocamlopt(dir: File, args: List<String>) {
        val ocamlopt = File(File(Ocaml.path!!).parentFile, "ocamlopt").path
        val process = ProcessBuilder(listOf(ocamlopt, "-I", "+unix", "-w", "-a") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertEquals(0, process.exitValue(), "ocamlopt ${args.joinToString(" ")} failed:\n$output")
    }
}
