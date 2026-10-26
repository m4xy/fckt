package uk.m4xy.fckt.intellij

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class HoverTest {
    /** What hovering over the [nth] [word] on [line] of [source] shows. */
    private fun hover(source: String, line: Int, word: String, nth: Int = 0, file: File? = null): String? {
        val lineStart = source.lines().take(line - 1).sumOf { it.length + 1 }
        val offset = Regex("\\b$word\\b").findAll(source.lines()[line - 1]).toList()[nth].range.first + lineStart
        return hovers(source, file)[offset]
    }

    @Test
    fun `functions show their inferred signature and what they do`() {
        val source = """
            import fckt.io.Console
            twice(f: (Int) -> Int, x: Int) = f(f(x))
            @Console greet(name: String) = {
              message = "hello ${'$'}name"
              println(message)
            }
            List<A>.second(): Option<A> = this.drop(1).firstOrNone()
            @Console main() = {
              doubled = [1, 2].map(n -> twice(x -> x * 2, n))
              greet("a")
              println(doubled.second().toString())
            }
        """.trimIndent()
        assertEquals("twice(f: (Int) -> Int, x: Int): Int\ndoes what f does", hover(source, 2, "twice"))
        assertEquals("greet(name: String): Unit\ndoes @Console", hover(source, 3, "greet"))
        assertEquals("List<A>.second(): Option<A>\npure", hover(source, 7, "second"))
        // Locals, parameters and lambdas' parameters: their types.
        assertEquals("message: String", hover(source, 4, "message"))
        assertEquals("doubled: List<Int>", hover(source, 9, "doubled"))
        assertEquals("n: Int", hover(source, 9, "n"))
        assertEquals("f: (Int) -> Int", hover(source, 2, "f"))
        // Where it's used, as where it's declared: map from the prelude, with its own names.
        assertEquals("List<A>.map(f: (A) -> B): List<B>\ndoes what f does", hover(source, 9, "map"))
        assertEquals("greet(name: String): Unit\ndoes @Console", hover(source, 10, "greet"))
        assertEquals("println: (String) -> Unit\nan operation of @Console", hover(source, 5, "println"))
    }

    @Test
    fun `effect parameters and annotations show what they pass on`() {
        val resume = File("../../examples/resume.fk")
        val text = resume.readText()
        val line = text.lines().indexOfFirst { it.startsWith("@Mutating runQueue") } + 1
        assertEquals("runQueue(queue: Queue<@E>): Unit\ndoes @Mutating, @E", hover(text, line, "runQueue", file = resume))
        val ordering = File("../../examples/ordering.fk")
        val orders = ordering.readText()
        val repository = orders.lines().indexOfFirst { it == "@Repository" } + 1
        assertEquals("@Repository: an annotation, which passes on @Persistence", hover(orders, repository, "Repository", file = ordering))
        assertEquals("@Console: an effect, handled: print, println, readLine", hover("import fckt.io.Console\n@Console main() = println(\"x\")", 2, "Console"))
    }
}
