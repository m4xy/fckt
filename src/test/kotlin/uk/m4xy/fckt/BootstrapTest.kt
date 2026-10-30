package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * fckt's compiler written in fckt compiles itself: built by this compiler, it compiles its own
 * source whole, the core and the standard library with it, to OCaml, which, built with nothing but
 * OCaml's own libraries, compiles it again to the same OCaml.
 */
class BootstrapTest {
    @Test
    fun `fckt's compiler, written in fckt, compiles itself whole to a compiler that compiles it the same`() {
        assumeTrue(Ocaml.path != null, "ocaml isn't installed")
        val dir = kotlin.io.path.createTempDirectory("fckt-bootstrap").toFile()
        try {
            val folders = listOf("parser/src", "checker/src", "lowering/src", "compiler/src")
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

    /** [ml] in [dir], as [binary], with OCaml's unix library. */
    private fun build(dir: File, ml: String, binary: String) = ocamlopt(dir, listOf("unix.cmxa", ml, "-o", binary))

    /** fckt.compiler, as [compiler], compiling itself whole to [out]. */
    private fun run(compiler: File, out: File, folders: List<String>) {
        val process = ProcessBuilder(listOf(compiler.path, "--whole", "compiler/src/main.fk", out.path) + folders).redirectErrorStream(true).start()
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
