package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** Running the OCaml fckt writes, with the toplevel, as the tests do. */
internal object Ocaml {
    val path: String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "ocaml") }.firstOrNull { it.canExecute() }?.path
        ?: File("/opt/homebrew/bin/ocaml").takeIf { it.canExecute() }?.path

    /** Runs [ml], in [dir] if given, and returns its standard output; it exiting otherwise than with [code], or OCaml warning, fails. */
    fun run(ml: String, args: List<String> = emptyList(), dir: File? = null, env: Map<String, String> = emptyMap(), code: Int = 0): String {
        assumeTrue(path != null, "ocaml isn't installed")
        val file = File.createTempFile("fckt", ".ml").apply { writeText(ml) }
        try {
            val libraries = ocamlLibraries(ml).flatMap { listOf("-I", "+$it", "$it.cma") }
            val process = ProcessBuilder(listOf(path, "-w", "+a-4-9-27-40-41-42-44-45-70", "-warn-error", "+8") + libraries + file.path + args)
                .directory(dir).redirectErrorStream(true).apply { environment().putAll(env) }.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(code, process.exitValue(), "OCaml failed:\n$output\n${excerpt(output, ml)}")
            if ("Warning" in output || "Error" in output) throw AssertionError("OCaml reported:\n$output\n${excerpt(output, ml)}")
            return output
        } finally {
            file.delete()
        }
    }

    /**
     * The generated OCaml around the first line OCaml's [output] names, numbered: enough to see what
     * it complains about, without the whole program.
     */
    fun excerpt(output: String, ml: String): String {
        val lines = ml.lines()
        val line = Regex("line (\\d+)").find(output)?.groupValues?.get(1)?.toInt() ?: return "--- generated: ${lines.size} lines, no line named ---"
        val from = maxOf(1, line - 8)
        val to = minOf(lines.size, line + 4)
        return "--- generated, lines $from-$to of ${lines.size} ---\n" + (from..to).joinToString("\n") { "%5d %s".format(it, lines[it - 1]) }
    }
}
