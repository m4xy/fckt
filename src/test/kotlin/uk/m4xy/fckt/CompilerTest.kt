package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompilerTest {
    private val ocaml: String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "ocaml") }.firstOrNull { it.canExecute() }?.path
        ?: File("/opt/homebrew/bin/ocaml").takeIf { it.canExecute() }?.path

    /** Compiles [source], runs the OCaml with the toplevel, and returns its standard output. */
    private fun run(source: String, args: List<String> = emptyList()): String = runOcaml(compile(source), args)

    /** Runs [ml] with the OCaml toplevel, and returns its standard output. */
    private fun runOcaml(ml: String, args: List<String> = emptyList()): String {
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val file = File.createTempFile("fckt", ".ml").apply { writeText(ml) }
        try {
            val process = ProcessBuilder(listOf(ocaml, "-w", "+a-4-9-27-40-41-42-44-45-70", "-warn-error", "+8", file.path) + args)
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

    /**
     * The OCaml manual's tour (examples/manual). Each section asserts its results, so it must run
     * silently, unless a `.out` file gives the output it should print.
     */
    @TestFactory
    fun `the OCaml manual examples`(): List<DynamicTest> =
        File("examples/manual").listFiles { f -> f.extension == "fk" }!!.sorted().map { file ->
            DynamicTest.dynamicTest(file.name) {
                val source = file.readText()
                val args = source.lines().firstOrNull { it.startsWith("// args:") }
                    ?.removePrefix("// args:")?.trim()?.split(" ") ?: emptyList()
                val expected = File(file.path.removeSuffix(".fk") + ".out").takeIf { it.exists() }?.readText() ?: ""
                assertEquals(expected, run(source, args))
            }
        }

    @Test
    fun `records, types with cases and when`() {
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
            type Tree<T> {
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
            type Shape {
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
            type A { Done(x: Int) }
            type B { Done(y: String) }
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
    fun `a failed assertion stops the program with both values`() {
        val ml = compile("main() = {\n  assert(1 < 2)\n  assertEquals([1, 2], [2, 1].reversed())\n  assertEquals(55, 54)\n}")
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val file = File.createTempFile("fckt", ".ml").apply { writeText(ml) }
        try {
            val process = ProcessBuilder(ocaml, file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(2, process.exitValue(), output)
            assertContains(output, "line 4: expected:<55> but was:<54>")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `types can share cases`() {
        assertEquals("", run(File("examples/shared-cases.fk").readText()))
    }

    @Test
    fun `shared case errors`() {
        val letters = "type Alphabet { A, B, E }\ntype Digit { Zero }\ntype Vowel { Alphabet.A, Alphabet.E }\n"
        assertContains(error(letters + "f(flag: Boolean) = if (flag) Alphabet.A else Digit.Zero"), "no type has all of Alphabet.A, Digit.Zero")
        assertContains(error(letters + "f(v: Vowel) = when (v) {\n  is Alphabet -> 1\n}"), "Alphabet isn't part of Vowel: Vowel has no Alphabet.B")
        assertContains(error(letters + "f(v: Vowel) = when (v) {\n  Alphabet.B -> 1\n  else -> 2\n}"), "Vowel has no case 'Alphabet.B'")
        assertContains(error(letters + "f(a: Alphabet): Vowel = a"), "expected Vowel but found Alphabet")
        assertContains(error("type A { X }\ntype B { *A, Y }\ntype C { *B, A.Z }"), "A has no case 'Z'")
        assertContains(error("type L<T> { Nil }\ntype M { L.Nil }"), "can't take single cases from the generic type L")
        assertContains(error("type A { *B }\ntype B { *A }"), "includes itself")
    }

    @Test
    fun `modules group declarations and hide private ones`() {
        assertEquals("", run(File("examples/modules.fk").readText()))
    }

    @Test
    fun `module errors`() {
        val ratios = """
            module Ratios {
              type Ratio private(num: Int, denom: Int)
              make(n: Int, d: Int) = Ratio(n, d)
              private gcd(a: Int, b: Int): Int = a
              private type Secret(x: Int)
            }

        """.trimIndent()
        assertContains(error(ratios + "f() = Ratios.gcd(1, 2)"), "'gcd' is private to Ratios")
        assertContains(error(ratios + "f() = Ratios.Ratio(1, 2)"), "Ratio's constructor is private to Ratios")
        assertContains(error(ratios + "f(r: Ratios.Ratio) = r.copy(num = 3)"), "only it can copy one")
        assertContains(error(ratios + "f() = Ratios.Secret(1)"), "'Secret' is private to Ratios")
        assertContains(error(ratios + "f() = make(1, 2)"), "unknown name 'make'")
        assertContains(
            error("module M {\n  private type T(x: Int)\n  leak() = T(1)\n}"),
            "'leak' is public, but its type uses T, which is private to M",
        )
        assertContains(
            error("module A {\n  f(): Int = B.g()\n}\nmodule B {\n  g(): Int = A.f()\n}"),
            "call each other but are in different modules",
        )
        // Taking a record apart doesn't need its constructor.
        compile(ratios + "f(r: Ratios.Ratio) = when (r) {\n  Ratios.Ratio(n, d) -> n + d\n}")
    }

    @Test
    fun `specs make a module's types abstract`() {
        val source = File("examples/specs.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module type Counter = sig\n  type t\n  val show_t : t -> string\n  val zero : t")
        assertContains(ml, "val pop : 'a s -> ('a * 'a s) option")
        assertContains(ml, "module Tally : Counter = struct\n  type t = int")
    }

    @Test
    fun `spec errors`() {
        val counter = """
            spec Counter {
              type T
              zero: T
              count(c: T): Int
            }
            module Tally : Counter {
              type T = Int
              zero = 0
              count(c: Int) = c
              helper(c: Int) = c
            }
            module Clicks : Counter {
              type T(clicks: List<String>)
              zero = T([])
              count(c: T) = c.clicks.size
            }

        """.trimIndent()
        assertContains(error(counter + "x = Tally.zero + 1"), "'+' can't be applied to Tally.T and Int")
        assertContains(error(counter + "x: Tally.T = 3"), "expected Tally.T but found Int")
        assertContains(error(counter + "x = Tally.count(Clicks.zero)"), "expected Tally.T but found Clicks.T")
        assertContains(error(counter + "x = Clicks.T([])"), "T is abstract outside Clicks: its spec Counter hides what it is")
        assertContains(error(counter + "x = Clicks.zero.clicks"), "Clicks.T has no field 'clicks'")
        assertContains(error(counter + "x = Tally.helper(1)"), "'helper' isn't in Counter, the spec of Tally")
        assertContains(error("spec S {\n  type T\n  f(x: T): Int\n}\nmodule M : S { type T = Int }"), "M doesn't define 'f', which its spec S needs")
        assertContains(error("spec S {\n  type T\n}\nmodule M : S { x = 1 }"), "M doesn't define type T")
        assertContains(
            error("spec S {\n  type T\n  f(x: T): Int\n}\nmodule M : S {\n  type T = Int\n  f(x: Int) = \"s\"\n}"),
            "'f' has type (Int) -> String, but S needs (T) -> Int, which here is (Int) -> Int",
        )
        assertContains(
            error("spec S {\n  type T<A>\n  f<A>(x: A): T<A>\n}\nmodule M : S {\n  type T<A> = List<A>\n  f(x: Int) = [x]\n}"),
            "but S needs (A) -> T<A>",
        )
        assertContains(error("spec S {\n  type T<A>\n}\nmodule M : S { type T = Int }"), "S says T takes 1 type parameter, not 0")
        assertContains(error("spec S {\n  type T\n}\nmodule M : S { private type T = Int }"), "so it can't be private")
        assertContains(error("module M : Nope { x = 1 }"), "unknown spec 'Nope'")
        assertContains(error("spec S { type T = Int }"), "a spec's types are abstract")
        assertContains(error("spec S { f(x): Int }"), "a spec's parameters need types")
    }

    @Test
    fun `functors make modules from modules`() {
        val source = File("examples/functors.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module SortedSet (M : Ordered) : sig")
        assertContains(ml, "module IntSet = SortedSet (IntOrder)")
        assertContains(ml, "\"Set(items=[1, 3, 5])\" (IntSet.show_set numbers)")
    }

    @Test
    fun `functor errors`() {
        val sortedSet = """
            spec Ordered {
              type T
              compare(a: T, b: T): Int
            }
            module SortedSet<M : Ordered> {
              type Set(items: List<M.T>)
              empty = Set([])
              insert(s: Set, x: M.T): Set = Set([x, *s.items])
            }

        """.trimIndent()
        val intOrder = "module IntOrder {\n  type T = Int\n  compare(a: Int, b: Int) = a - b\n}\n"
        assertContains(error(sortedSet + "x = SortedSet.empty"), "'empty' is in the functor SortedSet")
        assertContains(error(sortedSet + "module A { type T = Int }\nmodule S = SortedSet<A>"), "A can't be SortedSet's M: it doesn't define 'compare'")
        assertContains(
            error(sortedSet + "module A {\n  type T = Int\n  compare(a: Int, b: Int) = \"x\"\n}\nmodule S = SortedSet<A>"),
            "A can't be SortedSet's M: 'compare' has type (Int, Int) -> String, but Ordered needs (T, T) -> Int",
        )
        assertContains(error(sortedSet + "spec Other { type T }\nmodule A : Other { type T = Int }\nmodule S = SortedSet<A>"), "it follows Other, not Ordered")
        assertContains(error(sortedSet + intOrder + "module S = IntOrder<IntOrder>"), "IntOrder isn't a functor")
        assertContains(error(sortedSet + intOrder + "module S = SortedSet<IntOrder, IntOrder>"), "SortedSet takes 1 module, not 2")
        assertContains(error(sortedSet + "module S = SortedSet<Missing>"), "unknown module 'Missing'")
        // The body works for any M, so it can't assume what M.T is.
        assertContains(error("spec S { type T }\nmodule F<M : S> {\n  f(x: M.T): Int = x\n}"), "expected Int but found F.M.T")
        // A module that follows Ordered keeps T abstract, as in OCaml.
        assertContains(
            error(sortedSet + intOrder.replace("module IntOrder {", "module IntOrder : Ordered {") + "module IntSet = SortedSet<IntOrder>\nx = IntSet.insert(IntSet.empty, 5)"),
            "expected IntOrder.T but found Int",
        )
        // Unsealed, it shows T is Int.
        compile(sortedSet + intOrder + "module IntSet = SortedSet<IntOrder>\nx = IntSet.insert(IntSet.empty, 5)")
    }

    @Test
    fun `spec type arguments say what a module's types are`() {
        val source = File("examples/monads.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module Options : Monad with type 'a m = 'a option = struct")
        assertContains(ml, "module Results : Monad with type 'a m = (string, 'a) result = struct")
        val monad = "spec Monad {\n  type M<A>\n  pure<A>(x: A): M<A>\n}\n"
        val options = "  type M<A> = Option<A>\n  pure(x) = Some(x)\n}"
        assertContains(error(monad + "module O : Monad<Int> {\n$options"), "its type argument must take 1 more")
        assertContains(error(monad + "module O : Monad<(Int) -> Int> {\n$options"), "it needs a named type, like Option")
        assertContains(error(monad + "module O : Monad<List> {\n$options"), "O follows Monad with M as List<A>, but its M is Option<A>")
        assertContains(error(monad + "module O : Monad<Option, Int> {\n$options"), "Monad has 1 type, so it takes at most that many type arguments, not 2")
        val twice = "spec Ordered {\n  type T\n  compare(a: T, b: T): Int\n}\nmodule Twice<M : Ordered<Int>> {\n  f(a: Int, b: Int): Int = M.compare(a, b)\n}\n"
        assertContains(
            error(twice + "module S {\n  type T = String\n  compare(a: String, b: String) = 0\n}\nmodule X = Twice<S>"),
            "S can't be Twice's M: Twice needs its T to be Int, but here it's String",
        )
        assertContains(
            error(twice + "module I : Ordered {\n  type T = Int\n  compare(a: Int, b: Int) = 0\n}\nmodule X = Twice<I>"),
            "Twice needs its T to be Int, but here it's I.T",
        )
        assertContains(compile(twice + "module I : Ordered<Int> {\n  type T = Int\n  compare(a: Int, b: Int) = 0\n}\nmodule X = Twice<I>"), "(M : Ordered with type t = int)")
    }

    @Test
    fun `a module's block binds with by`() {
        val ml = compile(File("examples/monads.fk").readText())
        assertContains(ml, "let ( let* ) = Options.bind in\n  let* h = half n in\n  let* q = half h in\n  Options.pure q")
        assertContains(ml, "let ( let* ) = M.bind in")
        assertContains(ml, "let* (a, b) = p in")
        val options = "module Options {\n  pure(x) = Some(x)\n  bind(m, f) = when (m) {\n    Some(x) -> f(x)\n    None -> None\n  }\n}\n" +
            "half(n: Int): Option<Int> = if (n % 2 == 0) Some(n / 2) else None\n"
        // No `by` line, so no let*: OCaml would warn that it's unused.
        assertEquals("let f (n : int) : int option = Options.pure n", compile(options + "f(n: Int): Option<Int> = Options { n }").trim().lines().last())
        assertEquals("", run(options + "by(n: Int) = { by = n; by + 1 }\nmain() = assertEquals(Some(2), Options { x by half(4); by(x) - 1 })"))
        assertContains(error(options + "f(n: Int) = Options { x by Ok(n); x }\ntype R<A> { Ok(a: A) }"), "Options binds Option<A>, but this is R<Int>")
        assertContains(error(options + "f(n: Int) = Some { x by half(n); x }"), "Some isn't a module, so Some { ... } has nothing to bind with")
        assertContains(error(options + "module P { g() = 1 }\nf(n: Int) = P { x by half(n); x }"), "P has neither bind and pure nor map and product, so it can't run a block")
        assertContains(error(options + "f(n: Int) = { x by half(n); x }"), "'by' needs a block to bind in")
        assertContains(error(options + "f(n: Int) = Options { y = [1].map(x -> { z by half(x); z }); y }"), "'by' needs a block to bind in")
        assertContains(error(options + "f(n: Int) = Options { Some(x) by Some(half(n)); x }"), "'by' takes only names and '_'")
        assertContains(error(options + "f(n: Int) = Options { x by half(n) }"), "a block must end with an expression")
    }

    @Test
    fun `a module without bind runs its by lines together`() {
        val source = File("examples/validation.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "let ( let+ ) = Validated.map in\n  let ( and+ ) = Validated.product in")
        assertContains(ml, "let+ n = check_name trimmed\n  and+ a = check_age age\n  and+ e = check_email email in")
        assertContains(ml, "let+ n = check_name name in\n  String.uppercase_ascii n")
        val valid = "module V {\n  map(m, f) = when (m) {\n    Some(x) -> Some(f(x))\n    None -> None\n  }\n" +
            "  product(a, b) = when (a, b) {\n    (Some(x), Some(y)) -> Some((x, y))\n    else -> None\n  }\n}\n"
        assertContains(error(valid + "f(n: Option<Int>) = V { x by n; y by Some(x); y }"), "V has no bind, so a step can't use x from an earlier step")
        assertContains(error(valid + "f(n: Option<Int>) = V { x by n; z = 1; y by n; y }"), "V has no bind, so its 'by' lines go together")
        assertContains(error(valid + "f(n: Int) = V { n }"), "V has no pure, so its block needs a 'by' line")
        assertContains(error(valid + "f(n: Int) = V { x by [n]; x }"), "V maps Option<A>, but this is List<Int>")
    }

    @Test
    fun `files are modules`() {
        val dir = kotlin.io.path.createTempDirectory("fckt").toFile()
        val lib = File(dir, "lib").apply { mkdir() }
        try {
            File(dir, "ratios.fk").writeText(
                "type Ratio(n: Int, d: Int)\nprivate gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)\n" +
                    "make(n: Int, d: Int): Ratio = {\n  g = gcd(n, d)\n  Ratio(n / g, d / g)\n}\n",
            )
            // Halves uses Ratios, so it comes in after it; list-extras.fk is ListExtras.
            File(dir, "halves.fk").writeText("half(r: Ratios.Ratio): Ratios.Ratio = Ratios.make(r.n, r.d * 2)\n")
            File(lib, "list-extras.fk").writeText("sumBy<A>(xs: List<A>, f: (A) -> Int): Int = xs.map(f).sum()\ncheck() = assert(1 == 2)\n")
            // Not mentioned, so not loaded, though it doesn't compile.
            File(dir, "unused.fk").writeText("bad(): Int = \"x\"\n")
            val main = File(dir, "main.fk")
            fun compileMain(source: String, dirs: List<File> = listOf(dir, lib)) = main.writeText(source).let { compile(main, dirs) }
            val ml = compileMain(
                "main() = {\n  assertEquals(Ratios.Ratio(1, 4), Halves.half(Ratios.make(2, 4)))\n" +
                    "  assertEquals(6, ListExtras.sumBy([\"a\", \"bb\", \"ccc\"], s -> s.length))\n}",
            )
            assertEquals("", runOcaml(ml))
            assertContains(ml, "module Ratios : sig")
            assertTrue(ml.indexOf("module Ratios") < ml.indexOf("module Halves"))
            assertContains(runCatching { runOcaml(compileMain("main() = ListExtras.check()")) }.exceptionOrNull()?.message.orEmpty(), "list-extras.fk line 2: assertion failed")
            assertContains(assertFailsWith<CompileError> { compileMain("main() = Ratios.gcd(2, 4)") }.toString(), "'gcd' is private to Ratios")
            assertContains(assertFailsWith<CompileError> { compileMain("main() = ListExtras.check()", listOf(dir)) }.toString(), "unknown name 'ListExtras'")
            File(lib, "broken.fk").writeText("bad(): Int = \"x\"\n")
            assertEquals("${File(lib, "broken.fk").path}:1:14: error: type mismatch: expected Int but found String", assertFailsWith<CompileError> { compileMain("main() = Broken.bad()") }.toString())
            // A program's own declarations come first: this Ratios is the program's, not the file.
            assertEquals("", runOcaml(compileMain("module Ratios { one() = 1 }\nmain() = assertEquals(1, Ratios.one())")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @TestFactory
    fun `examples don't pick up their neighbours`() = File("examples").walk().filter { it.extension == "fk" }.map { file ->
        DynamicTest.dynamicTest(file.path) { assertEquals(compile(file.readText()), compile(file, listOf(file.parentFile))) }
    }.toList()

    @Test
    fun `type aliases`() {
        val source = """
            type Pair<A> = (A, A)
            type Names = List<String>
            swap(p: Pair<Int>): Pair<Int> = {
              (a, b) = p
              (b, a)
            }
            main() = {
              assertEquals((2, 1), swap((1, 2)))
              names: Names = ["a", "b"]
              assertEquals(2, names.size)
            }
        """.trimIndent()
        assertEquals("", run(source))
        assertContains(compile(source), "type 'a pair = 'a * 'a")
        assertContains(error("type A = List<B>\ntype B = A"), "type 'A' is defined in terms of itself")
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
            error("type A { E(x: Int) }\ntype B { E(y: Int) }\nf() = E(1)"),
            "'E' is a case of A and B; write A.E",
        )
    }

    @Test
    fun `syntax errors`() {
        assertContains(error("f(x: Int) = if (x > 0) 1"), "'if' needs an 'else'")
        assertContains(error("f(x: Int) = when { x > 0 -> 1 }"), "needs an 'else' branch")
        assertContains(error("interface Shape { Circle }"), "write a type with cases as 'type Shape { ... }'")
        assertContains(error("f() = {\n  y = 1\n  y = 2\n  y\n}"), "'y' is already defined in this block")
        assertContains(error("f() = {\n  y = 1\n}"), "must end with an expression")
        // A line break after '>' still continues a comparison.
        compile("f(x: Int) = {\n  big = x >\n    10\n  big\n}")
    }
}
