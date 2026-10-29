package uk.m4xy.fckt

import java.io.File

/** Running what fckt.parser, the compiler written in fckt, makes of this repository's sources. */
internal object SelfHosted {
    /** The conformance cases, the core, std, the examples, and the fckt libraries here, fckt.parser's and fckt.checker's own source among them. */
    val sources: List<File> = listOf("conformance", "src/main/resources/std", "std/src", "examples", "symbols/src", "parser/src", "checker/src")
        .flatMap { File(it).walk().filter { f -> f.isFile && f.extension == "fk" }.sortedBy { f -> f.path }.toList() }

    /**
     * What [driver], a program in src/test/fckt that writes to its first argument what it makes of
     * each other, with "# path" before each, makes of [sources], by path: run over all of them at once.
     */
    fun written(driver: String): Map<String, String> = written(driver, sources.map { it.path })

    /**
     * What [driver] writes, given [arguments] after the file it writes to, by the paths it writes
     * "# path" before: compiled [with] the fckt libraries it uses.
     */
    fun written(driver: String, arguments: List<String>, with: List<File> = listOf(File("parser/src"))): Map<String, String> {
        val ml = compile(File("src/test/fckt/$driver"), with, others = listOf(File("std/src")))
        val out = File.createTempFile("written", ".txt")
        return try {
            Ocaml.runNative(ml, listOf(out.path) + arguments)
            out.readText()
        } finally {
            out.delete()
        }.split(Regex("^# ", RegexOption.MULTILINE)).drop(1).associate { it.substringBefore('\n') to it.substringAfter('\n') }
    }

    /** Text as the drivers quote it. */
    fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""
}
