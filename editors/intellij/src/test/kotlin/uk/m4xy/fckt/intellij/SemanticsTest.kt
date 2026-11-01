package uk.m4xy.fckt.intellij

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SemanticsTest {
    /** [source] with each word its tokens tell marked by what it is: `var` becomes `[KEYWORD:var]`. */
    private fun marked(source: String): String {
        val out = StringBuilder()
        var at = 0
        for (span in semantics(source).sortedBy { it.start }) {
            out.append(source, at, span.start).append("[${span.role}:").append(source, span.start, span.end).append("]")
            at = span.end
        }
        return out.append(source.substring(at)).toString()
    }

    @Test
    fun `var is a keyword in place`() {
        val marked = marked("type Account(var balance: Int)\ncount() = {\n  var n = 0\n  n = n + 1\n  n\n}")
        assertContains(marked, "([KEYWORD:var] balance")
        assertContains(marked, "  [KEYWORD:var] n = 0\n  n = n + 1")
    }

    @Test
    fun `external is a keyword before what OCaml implements`() {
        assertEquals("[KEYWORD:external] type Array<A> = \"'a array\"", marked("external type Array<A> = \"'a array\""))
    }

    @Test
    fun `effect is a keyword where it declares one`() {
        assertEquals(
            "[KEYWORD:effect] Clock { now(): Int }\n[KEYWORD:effect] Database\neffect(x: Int) = x",
            marked("effect Clock { now(): Int }\neffect Database\neffect(x: Int) = x"),
        )
    }

    @Test
    fun `internal is a keyword before what it declares`() {
        val marked = marked("internal module Rounding { whole(n: Int): Int = n }\ninternal external half(n: Int): Int = \"n / 2\"\ninternal(x: Int) = x")
        assertContains(marked, "[KEYWORD:internal] module")
        assertContains(marked, "[KEYWORD:internal] [KEYWORD:external]")
        assertContains(marked, "\ninternal(x: Int)")
    }

    @Test
    fun `import and as are keywords on an import line`() {
        assertEquals(
            "[KEYWORD:import] geometry.Shapes.Shape [KEYWORD:as] Form\nas(x: Int) = x",
            marked("import geometry.Shapes.Shape as Form\nas(x: Int) = x"),
        )
    }

    @Test
    fun `named arguments, annotations and by`() {
        val marked = marked("@Pure send(to = \"a\", body = f(x = 1))\nprinter: Printer = console by Printers\nsame = a == b")
        assertContains(marked, "[ANNOTATION:Pure] send([NAMED_ARGUMENT:to] = \"a\", [NAMED_ARGUMENT:body] = f([NAMED_ARGUMENT:x] = 1))")
        assertContains(marked, "console [KEYWORD:by] Printers")
        assertFalse(marked.contains("NAMED_ARGUMENT:same"))
    }

    @Test
    fun `every example and library file scans and colours without failing`() {
        val root = File("../..")
        val files = (File(root, "examples").walk() + File(root, "std/src").walk()).filter { it.extension == "fk" }.toList()
        assert(files.size > 20)
        for (f in files) semantics(f.readText())
    }
}
