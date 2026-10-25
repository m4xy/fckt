package uk.m4xy.fckt.intellij

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

/** In a kiln project, a file sees the project's packages and its libraries, through their interfaces. */
class ProjectTest {
    private val dir = kotlin.io.path.createTempDirectory("fckt-project").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")

    private val main = """
        import pricing.Prices
        import uk.m4xy.units.Moneys

        main() = println(Prices.label(Moneys.pence(3)))
    """.trimIndent()

    @Test
    fun `a library is seen through its interface`() {
        // A library kiln compiled, which has only its interface here.
        write("cache/units/interface/unit", "Uk_m4xy_units")
        write("cache/units/interface/uk/m4xy/units/Moneys.fki", "module Moneys {\n  pence(pounds: Int): Int\n}")
        write("shop/build.fk", "project = Project(name = \"shop\")")
        write("shop/src/pricing/Prices.fk", "module Prices {\n  label(pence: Int): String = \"costs ${'$'}{pence}p\"\n}")
        write("shop/.kiln/paths", "source src\nlibrary ${File(dir, "cache/units/interface").path}")
        val file = File(dir, "shop/src/main.fk").apply { writeText(main) }

        assertEquals(listOf(File(dir, "shop/src").canonicalPath), pathsOf(file).roots.map { it.canonicalPath })
        uk.m4xy.fckt.compile(main, file, pathsOf(file).roots, libraries = pathsOf(file).libraries)
        val found = declarations(main, file)
        val pence = main.indexOf("pence(")
        assertEquals(listOf("Moneys.fki:2"), found[pence]?.map { "${File(it.file!!).name}:${it.pos.line}" })
        assertContains(hovers(main, file)[pence].orEmpty(), "pence(pounds: Int): Int")
        // The interface's names on its import line go there too.
        assertEquals("Moneys.fki", found[main.indexOf("Moneys\n")]?.single()?.file?.let { File(it).name })
    }

    @Test
    fun `only the tests see what only they use`() {
        write("cache/checks/interface/unit", "Uk_m4xy_checks")
        write("shop/build.fk", "project = Project(name = \"shop\")")
        write("shop/.kiln/paths", "source src")
        write("shop/test/prices.fk", "import uk.m4xy.checks.Test")
        write("shop/.kiln/test-paths", "tests test\nlibrary ${File(dir, "cache/checks/interface").path}")
        val checks = File(dir, "cache/checks/interface").canonicalPath
        assertEquals(listOf(checks), pathsOf(File(dir, "shop/test/prices.fk")).libraries.map { it.canonicalPath })
        assertEquals(emptyList(), pathsOf(File(dir, "shop/src/main.fk")).libraries)
    }

    @Test
    fun `without kiln's paths, a file sees its own folder`() {
        write("shop/build.fk", "project = Project(name = \"shop\")")
        val file = File(dir, "shop/src/main.fk").apply { parentFile.mkdirs(); writeText(main) }
        assertEquals(listOf(file.parentFile), pathsOf(file).roots)
        assertEquals(emptyList(), pathsOf(file).libraries)
    }
}
