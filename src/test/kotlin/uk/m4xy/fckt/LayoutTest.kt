package uk.m4xy.fckt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LayoutTest {
    private val long = "\"" + "x".repeat(60) + "\""

    @Test
    fun `a call too wide for its line has an argument to a line`() {
        val source = """project = Project(name = "compiler", group = "fckt", version = "0.1.0", dependencies = [projectAt("../lowering")])"""
        assertEquals(
            """
            project = Project(
              name = "compiler",
              group = "fckt",
              version = "0.1.0",
              dependencies = [projectAt("../lowering")],
            )
            """.trimIndent(),
            Layout.formatted(source),
        )
        assertEquals(listOf(1), Layout.unformatted(source))
    }

    @Test
    fun `what's laid out stays as it is`() {
        val source = "f(a: Int, b: String): String = describe(a, $long, b, $long)"
        val once = Layout.formatted(source)
        assertEquals(once, Layout.formatted(once))
        assertEquals(emptyList(), Layout.unformatted(once))
    }

    @Test
    fun `a line that fits is left alone`() {
        val source = "  total(format: Format<T = Int>, texts: List<String>): Int = 1"
        assertEquals(source, Layout.formatted(source))
    }

    @Test
    fun `a type's arguments aren't items of the list`() {
        val source = "  find(entries: Map<String, Symbol>, other: Map<String, List<Int>>, more: Option<String>, last: Boolean): Int = 1"
        assertEquals(
            """
              find(
                entries: Map<String, Symbol>,
                other: Map<String, List<Int>>,
                more: Option<String>,
                last: Boolean,
              ): Int = 1
            """.trimIndent().prependIndent("  ").replace("\n  )", "\n  )"),
            Layout.formatted(source),
        )
    }

    @Test
    fun `patterns, conditions, lambdas' parameters and what's inside a lambda are left alone`() {
        for (source in listOf(
            "  [first, second, *rest] -> describe(first, second, rest, $long, $long)".let { "when (xs) {\n$it\n}" },
            "if (alphabet.contains(letter) && digits.contains(digit) && $long.isNotEmpty()) 1 else 2",
            "xs.map { item -> describe(item, $long, $long) }",
        )) assertEquals(source, Layout.formatted(source))
    }

    @Test
    fun `a list inside one that's already split is split on its own line`() {
        val source = "call(\n  first,\n  describe(alpha, beta, gamma, $long, delta),\n)"
        assertEquals(
            "call(\n  first,\n  describe(\n    alpha,\n    beta,\n    gamma,\n    $long,\n    delta,\n  ),\n)",
            Layout.formatted(source),
        )
    }

    @Test
    fun `a declaration whose result has type arguments is split too, its annotations kept on its first line`() {
        val source = "  @Abandoning private mapIn(declarations: List<Declaration>, prefix: String, file: LoadedFile, f: @Abandoning (Symbol) -> Declaration): List<Declaration> ="
        assertEquals(
            listOf(
                "  @Abandoning private mapIn(",
                "    declarations: List<Declaration>,",
                "    prefix: String,",
                "    file: LoadedFile,",
                "    f: @Abandoning (Symbol) -> Declaration,",
                "  ): List<Declaration> =",
            ).joinToString("\n"),
            Layout.formatted(source),
        )
    }

    @Test
    fun `annotations that make a declaration's first line too wide go on a line of their own`() {
        val source = "  @Console @Network @Clock @FileSystem @Process @Abandoning @Reporting private describedEverythingThatHappened(first: String, second: String): Unit = Unit"
        assertEquals(
            listOf(
                "  @Console @Network @Clock @FileSystem @Process @Abandoning @Reporting",
                "  private describedEverythingThatHappened(",
                "    first: String,",
                "    second: String,",
                "  ): Unit = Unit",
            ).joinToString("\n"),
            Layout.formatted(source),
        )
    }
}
