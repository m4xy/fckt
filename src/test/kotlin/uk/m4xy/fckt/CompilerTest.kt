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
import kotlin.test.assertFalse
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
    fun `a when must cover every value`() {
        val letters = "type Letter { X, Y, Z }\n"
        assertContains(error(letters + "f(a: Letter) = when (a) {\n  X -> 1\n}"), "2:16: error: this when doesn't cover Y, Z; add them, or an else")
        assertContains(error(letters + "f(a: Letter) = when (a) {\n  X, Y -> 1\n}"), "this when doesn't cover Z; add it, or an else")
        assertEquals("", run(letters + "f(a: Letter) = when (a) {\n  X -> 1\n  else -> 2\n}\nmain() = assertEquals(2, f(Z))"))
        // Inside cases, tuples and lists, with an example of what's missing.
        assertContains(
            error("f(o: Option<Result<Int, String>>) = when (o) {\n  Some(Ok(x)) -> x\n  None -> 0\n}"),
            "this when doesn't cover Some(Error(_)); add it, or an else",
        )
        assertContains(
            error("f(a: Boolean, b: Boolean) = when (a, b) {\n  (true, true) -> 1\n  (false, _) -> 2\n}"),
            "this when doesn't cover (true, false)",
        )
        assertContains(error("f(xs: List<Int>) = when (xs) {\n  [] -> 0\n  [x] -> x\n}"), "this when doesn't cover [_, _, *_]")
        assertEquals("", run("f(xs: List<Int>) = when (xs) {\n  [] -> 0\n  [x, *_] -> x\n}\nmain() = assertEquals(1, f([1, 2]))"))
        // Numbers and strings can't all be listed.
        assertContains(error("f(n: Int) = when (n) {\n  0 -> \"zero\"\n  1 -> \"one\"\n}"), "this when doesn't cover every Int; add an else")
        // `is Vowel` covers Vowel's cases, which are some of Alphabet's.
        val alphabet = "type Alphabet { A, B, E }\ntype Vowel { Alphabet.A, Alphabet.E }\n"
        compile(alphabet + "f(c: Alphabet) = when (c) {\n  is Vowel -> 1\n  Alphabet.B -> 2\n}")
        assertContains(error(alphabet + "f(c: Alphabet) = when (c) {\n  is Vowel -> 1\n}"), "this when doesn't cover B")
        // A record has one shape, so its fields decide.
        compile("type P(a: Boolean, b: Int)\nf(p: P) = when (p) {\n  P(true, n) -> n\n  P(false, _) -> 0\n}")
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
    fun `annotations are checked, then compiled away`() {
        val source = File("examples/annotations.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        listOf("Route", "Pure", "Codec", "route", "pure", "codec").forEach { assertFalse(it in ml, it) }
        // Every declaration in it, but main.
        val header = source.substringBefore("@Route(\"/hello\"")
        fun annotated(annotation: String, on: String = "f() = 1") = error("$header$annotation\n$on\nmain() = assert(true)")
        assertContains(annotated("@Unknown"), "unknown annotation 'Unknown'")
        assertContains(annotated("@Shape"), "'Shape' isn't an annotation; declare one with annotation type Shape(...)")
        assertContains(annotated("@Route(\"/a\", handler = 1)"), "expected (String) -> String but found Int")
        assertContains(annotated("@Route(path = \"/a\", greet)"), "an argument passed in order can't follow one passed by name")
        assertContains(annotated("@Route(\"/a\", x -> x)"), "so not a lambda: name a function instead")
        assertContains(
            annotated("annotation type Name(value: String)\n@Name(greet(\"x\"))"),
            "an annotation's arguments are constants and references, so not a call",
        )
        assertContains(annotated("@Codec(1)"), "this should be a type, like List<User>")
        assertContains(annotated("@Codec(List<Nope>)"), "unknown type 'Nope'")
        assertContains(annotated("@SortedBy(Other)", "module Other { x = 1 }"), "Other doesn't follow Ordered: declare it as module Other : Ordered")
        assertContains(annotated("@Default(Triangle)"), "'Triangle' isn't a case of Shape")
        assertContains(annotated("@Index(User.age)"), "User has no field 'age'")
        assertContains(annotated("@Index(name)"), "write a field with its type, like User.name")
        assertContains(annotated("annotation type Ages(field: Field<User, Int>)\n@Ages(User.name)"), "User.name is a String, not a Int")
        assertContains(annotated("@Derive([IntOrder])"), "unknown spec 'IntOrder'")
        assertContains(annotated("@Routes([Route(\"/a\")])"), "@Route takes 2 arguments, not 1")
        assertContains(annotated("", "f(r: Route) = 1"), "'Route' is an annotation, written @Route(...) before a declaration")
        assertContains(annotated("annotation type Bad<A>(x: A)"), "an annotation has no type parameters")
        assertContains(annotated("annotation type Bad(x: Case<Int>)"), "Case<...> takes a type with cases, not Int")
        // A case can be named alone, and a field's type left open.
        compile("$header@Default(Square)\nannotation type Any(field: Field<User, _>)\n@Any(User.name)\nf() = 1\nmain() = assert(true)")
    }

    @Test
    fun `opaque types are abstract outside their module`() {
        val source = File("examples/specs.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module type Counter = sig\n  type t\n  val show_t : t -> string\n  val zero : t")
        assertContains(ml, "val pop : 'a s -> ('a * 'a s) option")
        assertContains(ml, "module Tally : sig\n  type t\n  val show_t : t -> string\n  val zero : t\n  val inc : t -> t")
        assertContains(ml, "end = struct\n  type t = int")
        // A spec checks a module; it hides nothing, so without opaque types OCaml infers the signature.
        assertContains(ml, "module ListStack = struct")
        // Without a spec: a function's outside type is what's written, with the opaque type's name.
        val percent = """
            module Percent {
              opaque type T = Int
              of(n: Int): Option<T> = if (n >= 0 && n <= 100) Some(n) else None
              value(p: T): Int = p
              twice(n: Int) = n * 2
            }

        """.trimIndent()
        assertEquals("", run(percent + "main() = assertEquals(Some(42), Percent.of(42).map(Percent.value))"))
        assertContains(compile(percent), "val of_ : int -> t option")
        assertContains(error(percent + "x = Percent.of(1).map(p -> p + 1)"), "'+' can't be applied to Percent.T and Int")
        assertContains(error(percent + "x: Percent.T = 3"), "expected Percent.T but found Int")
        // Nothing written says twice takes a T, so it takes an Int.
        assertEquals("", run(percent + "main() = assertEquals(4, Percent.twice(2))"))
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
              opaque type T = Int
              zero = 0
              count(c: Int) = c
              helper(c: Int) = c
            }
            module Clicks : Counter {
              opaque type T(clicks: List<String>)
              zero = T([])
              count(c: T) = c.clicks.size
            }

        """.trimIndent()
        assertContains(error(counter + "x = Tally.zero + 1"), "'+' can't be applied to Tally.T and Int")
        assertContains(error(counter + "x: Tally.T = 3"), "expected Tally.T but found Int")
        assertContains(error(counter + "x = Tally.count(Clicks.zero)"), "expected Tally.T but found Clicks.T")
        assertContains(error(counter + "x = Clicks.T([])"), "T is opaque outside Clicks: only Clicks can build one or look into it")
        assertContains(error(counter + "x = Clicks.zero.clicks"), "T is opaque outside Clicks")
        assertContains(error(counter + "x = Clicks.zero.copy(clicks = [])"), "T is opaque outside Clicks")
        assertContains(error(counter + "f(c: Clicks.T) = when (c) {\n  Clicks.T(cs) -> cs\n}"), "T is opaque outside Clicks")
        // What a spec doesn't list is still visible; only private hides it.
        compile(counter + "x = Tally.helper(1)")
        // Without opaque, the type is visible.
        compile(counter.replace("opaque type T = Int", "type T = Int") + "x = Tally.zero + 1")
        assertContains(error("type T = Int\nopaque type U = Int"), "an opaque type is opaque outside its module, so it needs to be in one")
        assertContains(error("module M { private opaque type T = Int }"), "a private type is hidden already")
        assertContains(error("spec S { type T }\nmodule M : S<Int> { opaque type T = Int }"), "M says what its types are in its body, so write just : S")
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
        // Set is opaque, so only SortedSet's functions make one, in order.
        assertContains(error(source.replace("main() = {", "bad = IntSet.Set([3, 1])\nmain() = {")), "Set is opaque outside IntSet")
        assertContains(ml, "\"Set(items=[1, 3, 5])\" (IntSet.show_set numbers)")
    }

    @Test
    fun `functor errors`() {
        val sortedSet = """
            spec Ordered {
              type T
              compare(a: T, b: T): Int
            }
            module SortedSet(M: Ordered) {
              type Set(items: List<M.T>)
              empty = Set([])
              insert(s: Set, x: M.T): Set = Set([x, *s.items])
            }

        """.trimIndent()
        val intOrder = "module IntOrder {\n  type T = Int\n  compare(a: Int, b: Int) = a - b\n}\n"
        assertContains(error(sortedSet + "x = SortedSet.empty"), "'empty' is in the functor SortedSet")
        assertContains(error(sortedSet + "module A { type T = Int }\nmodule S = SortedSet(A)"), "A can't be SortedSet's M: it doesn't define 'compare'")
        assertContains(
            error(sortedSet + "module A {\n  type T = Int\n  compare(a: Int, b: Int) = \"x\"\n}\nmodule S = SortedSet(A)"),
            "A can't be SortedSet's M: 'compare' has type (Int, Int) -> String, but Ordered needs (T, T) -> Int",
        )
        assertContains(error(sortedSet + "spec Other { type T }\nmodule A : Other { type T = Int }\nmodule S = SortedSet(A)"), "it follows Other, not Ordered")
        assertContains(error(sortedSet + intOrder + "module S = IntOrder(IntOrder)"), "IntOrder isn't a functor")
        assertContains(error(sortedSet + intOrder + "module S = SortedSet(IntOrder, IntOrder)"), "SortedSet takes 1 module, not 2")
        assertContains(error(sortedSet + "module S = SortedSet(Missing)"), "unknown module 'Missing'")
        // Modules go in parentheses, like a constructor's arguments; <...> is for types.
        assertContains(error("spec S { type T }\nmodule F<M : S> { x = 1 }"), "a functor takes its modules in parentheses: module F(M: Spec)")
        assertContains(error(sortedSet + intOrder + "module S = SortedSet<IntOrder>"), "a functor takes its modules in parentheses: SortedSet(Module)")
        assertContains(error(sortedSet + "module S = SortedSet(List<Int>)"), "expected ')'")
        // The body works for any M, so it can't assume what M.T is.
        assertContains(error("spec S { type T }\nmodule F(M: S) {\n  f(x: M.T): Int = x\n}"), "expected Int but found F.M.T")
        // An opaque T stays abstract, so an IntSet takes only IntOrder.Ts.
        assertContains(
            error(sortedSet + intOrder.replace("type T = Int", "opaque type T = Int").replace("a: Int, b: Int", "a: T, b: T") + "module IntSet = SortedSet(IntOrder)\nx = IntSet.insert(IntSet.empty, 5)"),
            "expected IntOrder.T but found Int",
        )
        // Otherwise it shows T is Int, with a spec or without.
        compile(sortedSet + intOrder + "module IntSet = SortedSet(IntOrder)\nx = IntSet.insert(IntSet.empty, 5)")
        compile(sortedSet + intOrder.replace("module IntOrder {", "module IntOrder : Ordered {") + "module IntSet = SortedSet(IntOrder)\nx = IntSet.insert(IntSet.empty, 5)")
    }

    @Test
    fun `spec type arguments say what a functor's parameter's types are`() {
        val source = File("examples/monads.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module Options = struct\n  type 'a m = 'a option")
        assertContains(ml, "module Results = struct\n  type 'a m = ('a, string) result")
        val monad = "spec Monad {\n  type M<A>\n  pure<A>(x: A): M<A>\n}\n"
        // A module says what its types are in its body; type arguments are for a functor's parameters.
        assertContains(
            error(monad + "module O : Monad<Option> {\n  type M<A> = Option<A>\n  pure(x) = Some(x)\n}"),
            "O says what its types are in its body, so write just : Monad",
        )
        fun functor(arg: String) = monad + "module F(M: Monad<$arg>) {\n  one = M.pure(1)\n}\n"
        assertContains(error(functor("Int")), "its type argument must take 1 more")
        assertContains(error(functor("(Int) -> Int")), "it needs a named type, like Option")
        assertContains(error(monad + "module F(M: Monad<Option, Int>) { x = 1 }"), "Monad has 1 type, so it takes at most that many type arguments, not 2")
        // Inside, M's M<Int> is an Option<Int>.
        compile(monad + "module G(M: Monad<Option>) {\n  two: Option<Int> = M.pure(2)\n}")
        // `_` marks where M's parameter goes in a type that takes two.
        compile(monad + "module G(M: Monad<Result<_, String>>) {\n  two: Result<Int, String> = M.pure(2)\n}")
        assertContains(error(monad + "module G(M: Monad<Result<Int>>) {\n  two: Result<Int, String> = M.pure(2)\n}"), "expected Result<Int, String> but found Result<Int, Int>")
        assertContains(error(monad + "module G(M: Monad<Result<_, _>>) { x = 1 }"), "Monad's M takes 1 type parameter, so Result needs 1 '_', not 2")
        assertContains(error("f(x: List<_>): Int = 1"), "'_' only marks a spec type's parameters")
        val twice = "spec Ordered {\n  type T\n  compare(a: T, b: T): Int\n}\nmodule Twice(M: Ordered<Int>) {\n  f(a: Int, b: Int): Int = M.compare(a, b)\n}\n"
        assertContains(
            error(twice + "module S {\n  type T = String\n  compare(a: String, b: String) = 0\n}\nmodule X = Twice(S)"),
            "S can't be Twice's M: Twice needs its T to be Int, but here it's String",
        )
        assertContains(
            error(twice + "module I : Ordered {\n  opaque type T = Int\n  compare(a: Int, b: Int) = 0\n}\nmodule X = Twice(I)"),
            "Twice needs its T to be Int, but here it's I.T",
        )
        assertContains(compile(twice + "module I : Ordered {\n  type T = Int\n  compare(a: Int, b: Int) = 0\n}\nmodule X = Twice(I)"), "(M : Ordered with type t = int)")
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
        val source = File("examples/independent-steps.fk").readText()
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
    fun `the standard library comes in when it's used`() {
        val source = File("examples/stdlib.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "module Options = struct")
        assertContains(ml, "module type Monad = sig")
        // Only what's used: no Applicative, and nothing at all for a program that uses none of it.
        assertTrue("Applicative" !in ml)
        assertTrue("module" !in compile("main() = assertEquals(1, 1)"))
        assertTrue("module type" !in compile("v(): Validation<Int, String> = Valid(1)\nmain() = assert(v() == Valid(1))"))
        // The program's own declarations take the place of the library's.
        assertEquals("", run("module Options { one() = 1 }\nmain() = assertEquals(1, Options.one())"))
        assertEquals("", run("type Validation { Valid }\nmain() = assert(Valid == Valid)"))
        // Result is OCaml's, value first; Ok and Error are a program's own cases if it has them.
        assertContains(compile("f(): Result<Int, String> = Ok(1)"), "let f () : (int, string) result = Ok 1")
        assertEquals("", run("type Outcome { Fine, Error(why: String) }\nmain() = assertEquals(\"x\", when (Error(\"x\")) {\n  Error(why) -> why\n  Fine -> \"\"\n})"))
        // `xs.map(f)` inside a function called map is the list's, so map isn't recursive.
        assertContains(compile("module M {\n  map(xs: List<Int>): List<Int> = xs.map(x -> x + 1)\n}"), "  let map (xs")
    }

    @Test
    fun `extensions are chosen by their receiver's type`() {
        val source = File("examples/extensions.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "let list_int_total (this : int list) : int =")
        assertContains(ml, "let tuple_swap (this : 'a * 'b) : 'b * 'a =")
        // Externals are their OCaml, at each use; as a value, a function of their parameters.
        assertContains(ml, "(List.length [1; 2; 3])")
        assertContains(ml, "List.map (fun x0 -> Float.pow x0 3.) [1.0; 2.0]")
        assertContains(ml, "List.map (fun x0 -> ( / ) x0 2) [2; 4]")
        assertTrue("let halve" !in ml && "let cube" !in ml)
        assertContains(error("List<Int>.total(): Int = 0\nList<Int>.total(): Int = 1"), "'total' is declared twice for List<Int>")
        assertContains(error("List<Int>.f(): Int = 0\nList<Float>.f(): Int = 1\ng(xs) = xs.f()"), "the receiver's type isn't known")
        assertContains(error("List<Int>.f(): Int = 0\nList<Float>.f(): Int = 1\ng(): Int = [].f()"), "can't tell which 'f' this is: List<A> fits List<Int>.f and List<Float>.f")
        assertContains(error("String.twice: String = this + this\nf() = \"a\".twice()"), "'twice' is a property; write it without ()")
        assertContains(error("String.twice(): String = this + this\nf() = \"a\".twice"), "'twice' is a method; call it with ()")
        assertContains(error("external f(x): Int = \"g x\""), "an external needs the types of its parameters and result")
        assertContains(error("external f(x: Int): Int = x"), "an external's body is OCaml in a string")
        // A program's own extension takes the place of the prelude's.
        assertEquals("", run("List<A>.size: Int = 7\nmain() = assertEquals(7, [1].size)"))
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
