package uk.m4xy.fckt.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import uk.m4xy.fckt.compile
import java.io.File

/** Reformat Code lays fckt out as ktlint would Kotlin, and Enter puts the cursor where code goes next. */
class FormatterTest : BasePlatformTestCase() {
    private fun reformat(text: String): String {
        myFixture.configureByText("f.fk", text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        return myFixture.editor.document.text
    }

    /** [text], with `<caret>` where the cursor is, after pressing Enter there. */
    private fun enter(text: String): String {
        myFixture.configureByText("f.fk", text)
        myFixture.type('\n')
        val document = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return document.substring(0, caret) + "<caret>" + document.substring(caret)
    }

    fun `test indents two spaces a level, and continuing lines one more`() {
        val messy = """
            module Boards {
              opaque type Board<X>(size : Int, values: ImmutableLists.ImmutableList<X>)


              make<X>(size:Int, default: X) = Board(size,ImmutableLists.of(default, size*size))

              set<X>(board: Board<X>, x: Int) = Options {

                _ by checkBounds( x, -1 )
                res by ImmutableLists.set(board.values, x+y * board.size, !value)
                res

              }

              private bounded(x: Int, size: Int) =
              if (x > 0) Some(x)
              else None
            }
            module Results : Monad {
              bind(m, f) = when(m) {
                Ok(x) -> f(x)
                [hd, *tl] ->
                  if (i == 0) Some([e, *tl])
                  else Options {
                    rest by set(tl, i - 1, e)
                    [hd, *rest]
                  }
              }
            }
        """.trimIndent()
        val formatted = """
            module Boards {
              opaque type Board<X>(size: Int, values: ImmutableLists.ImmutableList<X>)

              make<X>(size: Int, default: X) = Board(size, ImmutableLists.of(default, size * size))

              set<X>(board: Board<X>, x: Int) = Options {
                _ by checkBounds(x, -1)
                res by ImmutableLists.set(board.values, x + y * board.size, !value)
                res
              }

              private bounded(x: Int, size: Int) =
                if (x > 0) Some(x)
                else None
            }
            module Results : Monad {
              bind(m, f) = when (m) {
                Ok(x) -> f(x)
                [hd, *tl] ->
                  if (i == 0) Some([e, *tl])
                  else Options {
                    rest by set(tl, i - 1, e)
                    [hd, *rest]
                  }
              }
            }
        """.trimIndent()
        assertEquals(formatted, reformat(messy))
        assertEquals(formatted, reformat(formatted))
    }

    fun `test leaves strings alone, and tells type arguments from comparisons`() {
        val messy = """
            pair<A, B>(ma: M.M<A>, mb : M.M<List<B>>) : M.M<(A, B)> = M {
                a by ma // the first
                b by mb
                (a,b)
            }
            module SortedSet(M: Ordered) {
                less(a: Int, b: Int) = a<b || a >= b
            }
            greeting(name: String, n: Int) = "hello  ${'$'}{name}, ${'$'}{ n+1 }:${'$'}n" + ""
            lazyTwo = lazy(~(1 + 1))
            shapes = Some({}) |> map(x -> x)
            @Route("/a") type Logger(log: @Console (String) -> Unit)
            f(xs: Array<Int>) = Lists { x by [1, 2]; xs[x] += ys[0][1] }
            type Queue<@E>(var tasks: List<@Console @E () -> Unit>)
            run<@E>(q: Queue<@Console  @E>) = Unit
        """.trimIndent()
        val formatted = """
            pair<A, B>(ma: M.M<A>, mb: M.M<List<B>>): M.M<(A, B)> = M {
              a by ma // the first
              b by mb
              (a, b)
            }
            module SortedSet(M: Ordered) {
              less(a: Int, b: Int) = a < b || a >= b
            }
            greeting(name: String, n: Int) = "hello  ${'$'}{name}, ${'$'}{ n+1 }:${'$'}n" + ""
            lazyTwo = lazy(~(1 + 1))
            shapes = Some({}) |> map(x -> x)
            @Route("/a") type Logger(log: @Console (String) -> Unit)
            f(xs: Array<Int>) = Lists { x by [1, 2]; xs[x] += ys[0][1] }
            type Queue<@E>(var tasks: List<@Console @E () -> Unit>)
            run<@E>(q: Queue<@Console @E>) = Unit
        """.trimIndent()
        assertEquals(formatted, reformat(messy))
    }

    fun `test a list across lines has an item on each, and one too long for a line is chopped down`() {
        val messy = """
            set<T>(
              lst: ImmutableList<T>,
              i: Int, e: T): Option<ImmutableList<T>> = None
            type Number { Int(Int), // whole
              Float(Float) }
            x = Options { a by b
              a }
            f(m: Map<String, Int>, n: Int) = (1, 2)
            s(a) = when (a) {
              A, B -> true
              else -> false
            }
            type Ratio private(num: Int, denom: Int)
            type E {
              Sum(l: Int)   // e1 + e2
              Diff(l: Int)  // e1 - e2
            }
            total = add(firstNumberInTheList, secondNumberInTheList, thirdNumberInTheList, fourthNumberInTheList, fifthNumberInTheList, sixthNumberInTheList)
        """.trimIndent()
        val formatted = """
            set<T>(
              lst: ImmutableList<T>,
              i: Int,
              e: T
            ): Option<ImmutableList<T>> = None
            type Number {
              Int(Int), // whole
              Float(Float)
            }
            x = Options {
              a by b
              a
            }
            f(m: Map<String, Int>, n: Int) = (1, 2)
            s(a) = when (a) {
              A, B -> true
              else -> false
            }
            type Ratio private(num: Int, denom: Int)
            type E {
              Sum(l: Int)   // e1 + e2
              Diff(l: Int)  // e1 - e2
            }
            total = add(
              firstNumberInTheList,
              secondNumberInTheList,
              thirdNumberInTheList,
              fourthNumberInTheList,
              fifthNumberInTheList,
              sixthNumberInTheList
            )
        """.trimIndent()
        assertEquals(formatted, reformat(messy))
        assertEquals(formatted, reformat(formatted))
    }

    fun `test reformatting the examples changes no program`() {
        val examples = File("../../examples").walk().filter { it.extension == "fk" }.toList()
        assertTrue(examples.size > 10)
        // The same program, but for the line numbers in what assertEquals says when it fails.
        fun program(text: String, file: File) = compile(text, file, emptyList()).replace(Regex("line \\d+"), "line")
        for (example in examples) {
            val before = runCatching { program(example.readText(), example) }.getOrNull() ?: continue
            val formatted = reformat(example.readText())
            assertEquals(example.name, before, program(formatted, example))
            assertEquals(example.name, formatted, reformat(formatted))
        }
    }

    fun `test enter puts the cursor where the next line goes`() {
        assertEquals("main() = {\n  <caret>\n}", enter("main() = {<caret>}"))
        assertEquals("f(x: Int) =\n  <caret>", enter("f(x: Int) =<caret>"))
        assertEquals("module M {\n  g() = 1\n  <caret>\n}", enter("module M {\n  g() = 1<caret>\n}"))
        assertEquals(
            "h(m) = when (m) {\n  Some(x) ->\n    <caret>\n}",
            enter("h(m) = when (m) {\n  Some(x) -><caret>\n}"),
        )
        assertEquals(
            "h(m) =\n  if (a) b\n  else Options {\n    <caret>\n  }",
            enter("h(m) =\n  if (a) b\n  else Options {<caret>}"),
        )
    }
}
