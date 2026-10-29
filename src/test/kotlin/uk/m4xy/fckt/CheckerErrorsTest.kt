package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.test.assertEquals

/**
 * fckt.checker, written in fckt, finds the errors this compiler's checker does in each conformance
 * case it rejects: each where it is, worded the same. What only turning a program into OCaml finds
 * isn't the checker's.
 */
class CheckerErrorsTest {
    private val std = File("std/src")

    private val cases = File("conformance").walk().filter { it.isFile && it.extension == "fk" }.sortedBy { it.path }.toList()

    /**
     * Cases whose errors come from what's still to come in fckt.checker, by why: they aren't compared
     * until it's there.
     */
    private val later = mapOf(
        "conformance/a-spec-builds-on-another-and-its-defaults-are-what-a-module-leaves-out-2.fk" to
            "a module's spec's values it must define, and a functor's parameters: milestone 5",
    )

    /** Each case's errors, as fckt.checker finds them, by the case's path. */
    private val written: Map<String, String> by lazy {
        SelfHosted.written("checker.fk", listOf("errors") + cases.flatMap { listOf("case", it.path) }, listOf(File("parser/src"), File("checker/src")))
    }

    @TestFactory
    fun `in each conformance case, fckt's checker finds the errors this one does`() = cases.map { file ->
        DynamicTest.dynamicTest(file.path) {
            assumeTrue(file.path !in later, later[file.path])
            val errors = errorsOf(file.readText())
            assumeTrue(errors != null, "it checks")
            assertEquals(errors, written["errors ${file.path}"])
        }
    }

    /** As checker.fk writes them: "error file line:column message", in order; null if it compiles. */
    private fun errorsOf(source: String): String? = try {
        analyse(source, null, emptyList(), others = listOf(std), coverage = true)
        null
    } catch (e: CompileError) {
        e.errors.joinToString("") { "error ${it.pos?.file ?: "-"} ${it.pos?.let { p -> "${p.line}:${p.col}" } ?: "-"} ${it.message}\n" }
    }
}
