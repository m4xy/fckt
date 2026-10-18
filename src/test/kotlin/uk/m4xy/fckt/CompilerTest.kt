package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerTest {
    private val ocaml: String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "ocaml") }.firstOrNull { it.canExecute() }?.path
        ?: File("/opt/homebrew/bin/ocaml").takeIf { it.canExecute() }?.path

    /** Compiles [source], runs the OCaml with the toplevel, and returns its standard output. */
    private fun run(source: String): String {
        val ml = compile(source)
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val file = File.createTempFile("fckt", ".ml").apply { writeText(ml) }
        try {
            val process = ProcessBuilder(ocaml, "-w", "+a-4-9-27-40-41-42-44-45-70", "-warn-error", "+8", file.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue(), "OCaml failed:\n$output\n--- generated ---\n$ml")
            assertNoWarnings(output, ml)
            return output
        } finally {
            file.delete()
        }
    }

    private fun assertNoWarnings(output: String, ml: String) {
        if ("Warning" in output || "Error" in output) throw AssertionError("OCaml reported:\n$output\n--- generated ---\n$ml")
    }

    private fun error(source: String): String = assertFailsWith<CompileError> { compile(source) }.toString()

    @Test
    fun `the orders example runs`() {
        val source = File("examples/orders.fk").readText()
        assertEquals(
            """
            Initiating charge for Money(value=1125)
            paid 1125 for order-1
            Initiating charge for Money(value=1250)
            paid 1250 for order-1

            """.trimIndent(),
            run(source),
        )
    }

    @Test
    fun `records, sealed interfaces and when`() {
        val ml = compile(File("examples/orders.fk").readText())
        assertContains(ml, "type user_id = { value : string }")
        assertContains(ml, "| Success of { order_id : string; total : money }")
        assertContains(ml, "| Some \"X\" -> int_of_float (float_of_int subtotal *. 0.1)")
        assertContains(ml, "let gateway = fake_gateway |> with_logging in")
    }

    @Test
    fun `generics, recursion and local functions`() {
        val output = run(
            """
            interface Tree<T> {
              Leaf
              Node(left: Tree<T>, value: T, right: Tree<T>)
            }

            size<T>(t: Tree<T>): Int = when (t) {
              Leaf -> 0
              Node(l, _, r) -> size(l) + 1 + size(r)
            }

            isEven(n: Int): Boolean = if (n == 0) true else isOdd(n - 1)
            isOdd(n: Int): Boolean = if (n == 0) false else isEven(n - 1)

            main() = {
              fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)
              t = Node(Node(Leaf, 1, Leaf), 2, Leaf)
              println("${'$'}{size(t)} ${'$'}{fact(5)} ${'$'}{isEven(10)}")
              println(t.toString())
            }
            """.trimIndent(),
        )
        assertEquals("2 120 true\nNode(left=Node(left=Leaf, value=1, right=Leaf), value=2, right=Leaf)\n", output)
    }

    @Test
    fun `pipes pass the value as the last argument`() {
        val output = run(
            """
            add(a: Int, b: Int) = a + b
            label(x: Int) = "n=${'$'}x"
            main() = println(3 |> add(4) |> (x -> x * 2) |> label)
            """.trimIndent(),
        )
        assertEquals("n=14\n", output)
    }

    @Test
    fun `tilde makes a thunk`() {
        val source = """
            twice(f: () -> Int) = f() + f()
            main() = println(twice(~21).toString())
        """.trimIndent()
        assertContains(compile(source), "twice (fun () -> 21)")
        assertEquals("42\n", run(source))
    }

    @Test
    fun `tilde ignores as many arguments as the expected type has`() {
        val source = """
            type Gateway(refund: (String, Int) -> Boolean, ping: () -> Int)
            main() = {
              g = Gateway(refund = ~true, ping = ~7)
              always: (Int) -> String = ~"same"
              println("${'$'}{g.refund("a", 1)} ${'$'}{g.ping()} ${'$'}{always(3)}")
            }
        """.trimIndent()
        val ml = compile(source)
        assertContains(ml, "refund = (fun _ _ -> true)")
        assertContains(ml, "ping = (fun () -> 7)")
        assertEquals("true 7 same\n", run(source))
    }

    @Test
    fun `numbers promote to float like Kotlin`() {
        val output = run(
            """
            average(xs: List<Float>) = xs.sum() / xs.size
            sign(x: Float) = when {
              x > 0 -> "positive"
              x < 0 -> "negative"
              else -> "zero"
            }
            main() = println("${'$'}{average([1.0, 2.5])} ${'$'}{sign(-2)} ${'$'}{7 % 3} ${'$'}{2 * 1.5}")
            """.trimIndent(),
        )
        assertEquals("1.75 negative 1 3.0\n", output)
    }

    @Test
    fun `lists and options`() {
        val output = run(
            """
            main() = {
              xs = [1, 2, 3].map(x -> x * 2).filter(x -> x > 2)
              println(xs.joinToString(", "))
              println("${'$'}{Some(1.5)} ${'$'}{[Some("a"), None]} ${'$'}{xs.fold(0, (acc, x) -> acc + x)}")
              println(xs.find(x -> x > 10).getOrElse(-1).toString())
            }
            """.trimIndent(),
        )
        assertEquals("4, 6\nSome(1.5) [Some(a), None] 10\n-1\n", output)
    }

    @Test
    fun `smart cast after is reads the variant's fields`() {
        val output = run(
            """
            interface Shape {
              Circle(radius: Float)
              Square(side: Float)
            }
            area(s: Shape) = when (s) {
              is Circle -> 3.0 * s.radius * s.radius
              is Square -> s.side * s.side
            }
            main() = println(area(Square(3)).toString())
            """.trimIndent(),
        )
        assertEquals("9.0\n", output)
    }

    @Test
    fun `shared labels and constructors are disambiguated`() {
        val output = run(
            """
            type Meters(Int)
            type Name(String)
            interface A { Done(x: Int) }
            interface B { Done(y: String) }
            pick(a: A): B = when (a) {
              Done(x) -> Done("got ${'$'}x")
            }
            main() = {
              m = Meters(3)
              n = Name("ada")
              b: B = Done("direct")
              println("${'$'}{m.value} ${'$'}{n.value} ${'$'}{pick(Done(1))} ${'$'}b")
            }
            """.trimIndent(),
        )
        assertEquals("3 ada Done(y=got 1) Done(y=direct)\n", output)
    }

    @Test
    fun `extension-style calls`() {
        val output = run(
            """
            type Box(value: Int)
            double(b: Box) = Box(b.value * 2)
            main() = println(Box(3).double().double().toString())
            """.trimIndent(),
        )
        assertEquals("Box(value=12)\n", output)
    }

    @Test
    fun `type errors`() {
        assertContains(error("f(x: Int) = x + \"a\""), "expected String but found Int")
        assertContains(error("id<T>(x: T): Int = x"), "type parameter T of 'id' is always Int")
        assertContains(error("f() = y"), "unknown name 'y'")
        assertContains(error("f(xs: List<Int>) = xs.size()"), "'size' is a property")
        assertContains(error("g(x) = x.foo"), "the receiver's type isn't known")
        assertContains(error("type P(a: Int)\nf() = P(b = 1)"), "'P' has no parameter 'b'")
        assertContains(error("x = x + 1"), "value 'x' is defined in terms of itself")
        assertContains(
            error("interface A { E(x: Int) }\ninterface B { E(y: Int) }\nf() = E(1)"),
            "'E' is a variant of A and B",
        )
    }

    @Test
    fun `syntax errors`() {
        assertContains(error("f(x: Int) = if (x > 0) 1"), "'if' needs an 'else'")
        assertContains(error("f(x: Int) = when { x > 0 -> 1 }"), "needs an 'else' branch")
        assertContains(error("f() = {\n  y = 1\n  y = 2\n  y\n}"), "'y' is already defined in this block")
        assertContains(error("f() = {\n  y = 1\n}"), "must end with an expression")
    }
}
