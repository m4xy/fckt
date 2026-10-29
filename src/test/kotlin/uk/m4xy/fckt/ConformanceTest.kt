package uk.m4xy.fckt

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The conformance suite, conformance/: what any implementation of fckt does with each case, which
 * this one is held to. A case is a program in a file of its own, with what's expected of it in its
 * comments, as conformance/README.md says.
 */
class ConformanceTest {
    private val std = File("std/src")

    @TestFactory
    fun cases() = File("conformance").walk().filter { it.isFile && it.extension == "fk" }.sortedBy { it.path }.map { file ->
        DynamicTest.dynamicTest(file.path.removePrefix("conformance/")) { check(Case.of(file.readText())) }
    }.toList()

    private fun check(case: Case) {
        when (case) {
            is Case.Prints -> assertEquals(case.lines.joinToString("") { "$it\n" }, Ocaml.run(compile(case.source)))
            is Case.Fails -> {
                val error = assertFailsWith<CompileError> { compile(case.source) }.toString()
                case.errors.forEach { assertContains(error, it) }
            }
            is Case.Compiles -> compile(case.source)
        }
    }

    private fun compile(source: String): String = compile(source, others = listOf(std))

    /** A case, as its comments say: it prints these lines, fails to compile with these errors, or only compiles. */
    private sealed interface Case {
        val source: String

        data class Prints(override val source: String, val lines: List<String>) : Case
        data class Fails(override val source: String, val errors: List<String>) : Case
        data class Compiles(override val source: String) : Case

        companion object {
            private val directive = Regex("^\\s*// (prints|error|compiles)(?:: ?(.*))?$")

            fun of(source: String): Case {
                val found = source.lines().mapNotNull { directive.find(it) }.map { it.groupValues[1] to it.groupValues[2] }
                val kinds = found.map { it.first }.toSet()
                require(kinds.size <= 1) { "a case says one of prints, error or compiles, not ${kinds.joinToString(" and ")}" }
                return when (kinds.firstOrNull()) {
                    "error" -> Fails(source, found.map { it.second })
                    "compiles" -> Compiles(source)
                    else -> Prints(source, found.map { it.second })
                }
            }
        }
    }
}
