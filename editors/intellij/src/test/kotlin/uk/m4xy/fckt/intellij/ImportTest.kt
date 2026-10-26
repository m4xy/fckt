package uk.m4xy.fckt.intellij

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The names on an import line go to what they name, and say what it is on hover. */
class ImportTest {
    private val dir = kotlin.io.path.createTempDirectory("fckt").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val main = """
        import geometry.Shapes
        import geometry.Shapes.area as size
        import geometry.Shapes.Shape.Circle as Round
        import fckt.monads.Options
        import fckt.io.FileSystem
        import fckt.io.Files.readLines

        main() = assertEquals(3.0, size(Round(1.0)))
    """.trimIndent()

    private fun setUp(): File {
        File(dir, "geometry").mkdirs()
        File(dir, "geometry/Shapes.fk").writeText(
            "module Shapes {\n  type Shape { Circle(radius: Float), Square(side: Float) }\n  area(s: Shape): Float = when (s) {\n    Circle(r) -> 3.0 * r * r\n    Square(side) -> side * side\n  }\n}\n",
        )
        return File(dir, "main.fk").apply { writeText(main) }
    }

    /** The offset of the [nth] [word] on [line]. */
    private fun at(line: Int, word: String, nth: Int = 0): Int =
        main.lines().take(line - 1).sumOf { it.length + 1 } + Regex("\\b$word\\b").findAll(main.lines()[line - 1]).toList()[nth].range.first

    private fun where(t: Target) = (t.file?.let { File(it).let { f -> if (f.isDirectory) f.name + "/" else f.name } } ?: "here") + if (t.name.isEmpty()) "" else ":${t.pos.line}"

    @Test
    fun `each name goes to what it names`() {
        val file = setUp()
        val found = declarations(main, file)
        fun goto(line: Int, word: String, nth: Int = 0) = found[at(line, word, nth)]?.map(::where)
        assertEquals(listOf("geometry/"), goto(1, "geometry"))
        assertEquals(listOf("Shapes.fk:1"), goto(1, "Shapes"))
        assertEquals(listOf("Shapes.fk:3"), goto(2, "area"))
        // The `as` name is what it renames.
        assertEquals(listOf("Shapes.fk:3"), goto(2, "size"))
        assertEquals(listOf("Shapes.fk:2"), goto(3, "Shape"))
        assertEquals(listOf("Shapes.fk:2"), goto(3, "Circle"))
        assertEquals(listOf("Options.fk:2"), goto(4, "Options"))
        // A package of the standard library's, and what's in its lower-case file.
        val fileSystem = File(System.getProperty("fckt.std"), "fckt/io/FileSystem.fk").readLines()
        val files = File(System.getProperty("fckt.std"), "fckt/io/Files.fk").readLines()
        assertEquals(listOf("FileSystem.fk:${fileSystem.indexOfFirst { "effect FileSystem" in it } + 1}"), goto(5, "FileSystem"))
        assertEquals(listOf("Files.fk:${files.indexOfFirst { ".readLines(" in it } + 1}"), goto(6, "readLines"))
    }

    @Test
    fun `each name says what it is`() {
        val file = setUp()
        val hovers = hovers(main, file)
        assertEquals("package geometry", hovers[at(1, "geometry")])
        assertEquals("module geometry.Shapes", hovers[at(1, "Shapes")])
        assertEquals("geometry.Shapes.area(s: geometry.Shapes.Shape): Float\npure", hovers[at(2, "area")])
        assertEquals("geometry.Shapes.area(s: geometry.Shapes.Shape): Float\npure", hovers[at(2, "size")])
        assertEquals("type geometry.Shapes.Shape", hovers[at(3, "Shape")])
        assertEquals("Circle: a case of geometry.Shapes.Shape", hovers[at(3, "Circle")])
        assertEquals("module fckt.monads.Options", hovers[at(4, "Options")])
        assertEquals("effect fckt.io.FileSystem", hovers[at(5, "FileSystem")])
        assertEquals("fckt.io.Path.readLines(): List<String>\ndoes @FileSystem, @Fail, @Channel", hovers[at(6, "readLines")])
    }
}
