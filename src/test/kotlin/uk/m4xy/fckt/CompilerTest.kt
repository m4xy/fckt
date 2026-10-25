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
    fun `a function says what it does besides work out its result`() {
        assertEquals("hello fckt\nhello Ada\nhello Grace\ntotal: 3\nhi, there\ntransferred 10\n", run(File("examples/effects.fk").readText()))
        fun program(source: String) = error("$source\nmain() = assert(true)")
        assertContains(program("greet(n: String) = println(n)"), "'greet' does @Console, so it must say so: @Console greet(...)")
        // What a function declares is what it may do: doing less is fine.
        compile("@Console f() = 1\nmain() = assert(true)")
        assertContains(program("x = println(\"hi\")"), "a top-level value runs when the program starts; make it a function")
        // Through what a function is given: map does what its function does.
        assertContains(program("shout(xs: List<String>) = xs.forEach(println)"), "'shout' does @Console")
        assertContains(program("apply(f: (Int) -> Int, x: Int) = f(x)\nq() = apply(x -> { println(\"a\"); x }, 1)"), "'q' does @Console")
        compile("apply(f: (Int) -> Int, x: Int) = f(x)\np() = apply(x -> x + 1, 1)\ntwice(f, x) = f(f(x))\nt() = twice(x -> x * 2, 1)\nmain() = assert(true)")
        // A function type says what calling it may do; unmarked, it's pure.
        assertContains(program("type H(f: (String) -> Unit)\nh() = H(println)"), "expected (String) -> Unit but found @Console (String) -> Unit")
        assertContains(program("mk(): (String) -> Unit = s -> println(s)"), "this does @Console, but the type of the function it's in says it's pure")
        assertContains(program("f(g: @Console () -> Unit) = g()"), "'f' does @Console")
        assertContains(program("annotation type Pure\nf(g: @Pure () -> Unit) = 1"), "'Pure' isn't an effect; declare one with effect Pure")
        // A spec that says what its functions may do is a promise its modules must keep.
        assertContains(
            program("spec Logger { @Pure log(s: String): Unit }\nmodule L : Logger { @Console log(s: String) = println(s) }"),
            "'log' does @Console, but Logger's log doesn't say it can: declare it there, as @Console log(...)",
        )
        // One that says nothing takes what its module does, which shows wherever that module is used.
        val logger = "spec Logger { log(s: String): Unit }\nmodule L : Logger { @Console log(s: String) = println(s) }\n"
        assertContains(program("${logger}f() = L.log(\"x\")"), "'f' does @Console")
        // A functor may do what its parameters do, which shows where it's given real modules.
        assertContains(program("${logger}module F(M: Logger) { run() = M.log(\"x\") }\nmodule G = F(L)\ng() = G.run()"), "'g' does @Console")
        compile("${logger}module F(M: Logger) { run() = M.log(\"x\") }\nmodule G = F(L)\n@Console g() = G.run()\nmain() = assert(true)")
        // Calls inside a lambda happen when it's called, not where it's written.
        compile("f() = { g = (s: String) -> println(s); 1 }\nmain() = assert(true)")
    }

    @Test
    fun `vars change, and changing one someone else can see is an effect`() {
        val source = File("examples/mutation.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "mutable balance : int")
        assertContains(ml, "let n = ref 0 in")
        val account = "type Account(owner: String, var balance: Int)\n"
        fun program(source: String) = error("$account$source\nmain() = assert(true)")
        assertContains(program("f(a: Account) = { a.balance = 1 }"), "'f' does @Mutating, so it must say so: @Mutating f(...)")
        assertContains(program("f(a: Account) = { a.owner = \"x\" }"), "Account's owner can't be changed; declare it with var owner: ...")
        assertContains(program("f(n: Int) = { n += 1 }"), "'n' can't be changed; declare it with var n = ...")
        assertContains(program("f() = { n = 1; n = 2; n }"), "to change it, declare it with var n = ...")
        assertContains(program("f() = { n = 5 }"), "a block must end with an expression, not a binding")
        // Whatever holds it, it's the same account: in an option, a box, a case, or passed back.
        assertContains(program("f(o: Option<Account>) = when (o) {\n  Some(a) -> { a.balance = 1 }\n  None -> Unit\n}"), "'f' does @Mutating")
        assertContains(program("type Box<T>(value: T)\nf(b: Box<Account>) = { b.value.balance = 1 }"), "'f' does @Mutating")
        assertContains(program("type Holder { H(acc: Account) }\nf(h: Holder) = when (h) {\n  H(acc) -> { acc.balance = 1 }\n}"), "'f' does @Mutating")
        assertContains(program("g(a: Account) = a\nf(x: Account) = { y = g(x); y.balance = 2 }"), "'f' does @Mutating")
        // A top-level value lives as long as the program, so changing it is always seen.
        assertContains(program("bank = Account(\"b\", 0)\nf() = { bank.balance = 1 }"), "'f' does @Mutating")
        // A function that changes a var can't escape as a pure one.
        assertContains(program("counter(): () -> Int = { var n = 0; ~{ n += 1; n } }"), "this does @Mutating, but the type of the function it's in says it's pure")
        // What's made and changed in a function, and doesn't leave it, doesn't count.
        compile("$account" + "f() = { a = Account(\"x\", 0); b = a; b.balance = 3; a.balance }\ng() = [Account(\"a\", 1)].forEach(a -> { a.balance = 0 })\nh() = { var n = 0; n = 5 }\nmain() = assert(true)")
    }

    @Test
    fun `arrays, and types OCaml implements, indexed with get and set`() {
        val source = File("examples/arrays.fk").readText()
        assertEquals("", run(source))
        val ml = compile(source)
        assertContains(ml, "Array.set xs 0 (Array.get xs 0 * 2)")
        assertContains(ml, "Buffer.add_string b word")
        fun program(source: String) = error("$source\nmain() = assert(true)")
        assertContains(program("f(xs: Array<Int>) = { xs[0] = 1 }"), "'f' does @Mutating, so it must say so")
        assertContains(program("f() = Array(1)"), "Array is an OCaml type, made by its functions, not by Array(...)")
        assertContains(program("type Box(n: Int)\nf(b: Box) = { b[0] = 1 }"), "Box has no method 'set'")
        assertContains(program("f() = { 1 + 1 = 2; 3 }"), "only a var, a var field or an index can be assigned to")
        // A type of OCaml's own, with its type parameters in place.
        assertContains(compile("external type Table<K, V> = \"('k, 'v) Hashtbl.t\"\nexternal table<K, V>(): Table<K, V> = \"Hashtbl.create 8\"\nt: Table<String, List<Int>> = table()\nmain() = assert(true)"), "(string, int list) Hashtbl.t")
    }

    @Test
    fun `annotations pass on what they carry, and a module's effects pass to what's in it`() {
        val ordering = File("examples/ordering.fk").readText()
        assertEquals("UPSERT INTO orders ...\nSELECT * FROM orders WHERE id = 'order-1'\nUPSERT INTO orders ...\n", run(ordering))
        // What a repository does underneath stays inside it: its callers see @Persistence, not @Console.
        val withoutMain = ordering.substringBefore("main() =")
        fun ordering(source: String) = error("$withoutMain$source\nmain() = assert(true)")
        assertContains(ordering("f() = Live.run(\"x\")"), "'f' does @Persistence, so it must say so")
        compile("$withoutMain@Persistence f() = Live.run(\"x\")\n@Persistence g(id: Ordering.OrderId) = PostgresOrders.find(id)\nmain() = assert(true)")
        // And the domain can't reach one at all.
        assertContains(
            error(withoutMain.replace("  private total(order: Order)", "  peek(customer: String) = LoyalCustomers.discountFor(customer)\n  private total(order: Order)") + "main() = assert(true)"),
            "'peek' does @Persistence, but it's in Ordering, which may only be pure",
        )
        val io = "effect Network\n@Console @Network annotation type Io\n"
        fun program(source: String) = error("$io$source\nmain() = assert(true)")
        compile("$io@Io f() = println(\"x\")\n@Io module M {\n  g() = println(\"y\")\n}\nh(k: @Io () -> Unit) = 1\nmain() = assert(true)")
        // A module with effects is a ceiling: nothing in it can add more, and @Pure allows nothing.
        assertContains(program("@Pure module D {\n  f() = println(\"x\")\n}"), "'f' does @Console, but it's in D, which may only be pure")
        assertContains(program("@Io module M {\n  @Mutating f(a: Mutable<Int>) = { a.value = 1 }\n}"), "'f' is in M, which may only do @Console @Network, so it can't be @Mutating")
        assertContains(program("@Pure module Outer {\n  @Console module Inner {\n    f() = 1\n  }\n}"), "Inner is in Outer, which may only be pure, so it can't be @Console")
        // The same annotation by two ways with different values must be chosen between.
        val a = "annotation type A(v: String)\n@A(\"X\") annotation type C\n@A(\"Y\") annotation type D\n"
        assertContains(
            program("$a@C @D annotation type Z"),
            "this gets @A more than once, with different values: @A(\"X\") through C, and @A(\"Y\") through D; choose one by declaring @A here",
        )
        compile("$io$a@A(\"Z\") @C @D annotation type Z\nmain() = assert(true)")
        compile("$io" + a.replace("annotation type A", "@Repeatable annotation type A") + "@C @D annotation type Z\nmain() = assert(true)")
        assertContains(program("@D annotation type C\n@C annotation type D"), "annotations can't be annotated in a circle")
        // Console is an effect because it's marked @Effect, which isn't passed on: @Loud carries Console but isn't an effect itself.
        compile("$io@Console annotation type Loud\n@Loud f() = println(\"x\")\nmain() = assert(true)")
    }

    @Test
    fun `parameters and fields can have defaults`() {
        val source = """
            type Project(name: String, version: String = "0.1.0", sources: List<String> = ["src"], tests: List<String> = defaultTests())
            defaultTests(): List<String> = ["test"]
            greet(name: String, greeting: String = "hello", punctuation: String = "!") = "${'$'}greeting ${'$'}name${'$'}punctuation"
            type Shape { Circle(radius: Float = 1.0), Square(side: Float) }
            List<Int>.scaled(by: Int = 2): List<Int> = this.map(x -> x * by)
            @Console loud(s: String = readLine()) = s.uppercase()
            main() = {
              assertEquals(Project("shop", "0.1.0", ["src"], ["test"]), Project(name = "shop"))
              assertEquals(Project("x", "2.0", ["src"], ["test"]), Project("x", version = "2.0"))
              assertEquals("hello Ada!", greet("Ada"))
              assertEquals("hi Ada!", greet("Ada", "hi"))
              assertEquals("hello Ada?", greet("Ada", punctuation = "?"))
              assertEquals(Circle(1.0), Circle())
              assertEquals([2, 4], [1, 2].scaled())
              assertEquals([3, 6], [1, 2].scaled(3))
              // What a default does is done where the call leaves it out.
              assertEquals("ADA", Console(print = s -> Unit, println = s -> Unit, readLine = ~"ada") { loud() })
            }
        """.trimIndent()
        assertEquals("", run(source))
        assertContains(error("greet(name: String, greeting: String = \"hello\") = name\nmain() = assertEquals(\"\", greet())"), "'greet' is missing argument 'name'")
        assertContains(error("f(n: Int = \"x\") = n\nmain() = Unit"), "expected Int but found String")
        assertContains(error("f(a: Int, b: Int = a) = b\nmain() = Unit"), "a default can't use the other parameters, as 'a' here")
        assertContains(error("@Console f(s: String = readLine()) = s\ng() = f()\nmain() = Unit"), "'g' does @Console")
        assertContains(error("main() = {\n  f = (x: Int = 1) -> x\n  f(2)\n}"), "only a declared function's parameters can have a default")
        assertContains(error("f(a: Int, b: Int) = a\nmain() = assertEquals(1, f(b = 2, 1))"), "an argument passed in order can't follow one passed by name")
    }

    @Test
    fun `a copy of an effect is a separate effect`() {
        val database = "effect Database { query(sql: String): List<String> }\neffect OrdersDb = Database\neffect UsersDb = Database\n"
        val source = database + """
            @OrdersDb @UsersDb report(): List<String> = OrdersDb.query("orders") + UsersDb.query("users")
            main() = {
              result = OrdersDb(query = sql -> ["pg ${'$'}sql"]) {
                UsersDb(query = sql -> ["dynamo ${'$'}sql"]) { report() }
              }
              assertEquals(["pg orders", "dynamo users"], result)
            }
        """.trimIndent()
        assertEquals("", run(source))
        assertContains(compile(source), "| OrdersDb_query : string -> string list Effect.t")
        fun program(source: String) = error("$database$source")
        assertContains(program("@OrdersDb f() = query(\"x\")\nmain() = Unit"), "'query' is an operation of Database and OrdersDb and UsersDb; write Database.query or OrdersDb.query or UsersDb.query")
        // Handling Database doesn't handle a copy.
        assertContains(program("@OrdersDb f() = OrdersDb.query(\"x\")\nmain() = Database(query = s -> [s]) { f() }"), "main does @OrdersDb, but nothing handles it")
        assertContains(program("effect Loop = Loop\nmain() = Unit"), "effect 'Loop' is a copy of itself")
        assertContains(program("type T(x: Int)\neffect X = T\nmain() = Unit"), "'T' isn't an effect, so there's nothing to copy")
        assertContains(program("effect X = Nope\nmain() = Unit"), "unknown effect 'Nope'")
        // A module that follows a copy handles it, and the prelude's effects can be copied.
        assertEquals("", run("${database}module Pg : OrdersDb {\n  query(sql: String) = [\"pg ${'$'}sql\"]\n}\nmain() = assertEquals([\"pg x\"], Pg { OrdersDb.query(\"x\") })"))
        val errors = "effect Errors = Console\n@Errors warn(s: String) = Errors.println(\"warning: ${'$'}s\")\n" +
            "@Console main() = Errors(print = Console.print, println = s -> Console.println(\"[err] ${'$'}s\"), readLine = Console.readLine) { warn(\"disk\") }"
        assertEquals("[err] warning: disk\n", run(errors))
    }

    @Test
    fun `a when branch can have a guard`() {
        val source = """
            type Shape { Circle(radius: Float), Square(side: Float) }
            describe(x: Option<Int>): String = when (x) {
              Some(n) if n > 0 -> "positive"
              Some(n) if n < 0 -> "negative"
              Some(_) -> "zero"
              None -> "none"
            }
            size(s: Shape): String = when (s) {
              is Circle if s.radius > 10.0 -> "big circle"
              Square(side) if side > 10.0 -> "big square"
              else -> "small"
            }
            grade(score: Int): String = when (score) {
              100 -> "perfect"
              else if score >= 50 -> "pass"
              else -> "fail"
            }
            main() = {
              assertEquals(["positive", "negative", "zero", "none"], [Some(3), Some(-1), Some(0), None].map(describe))
              assertEquals(["big circle", "small", "big square"], [Circle(11.0), Circle(1.0), Square(12.0)].map(size))
              assertEquals(["perfect", "pass", "fail"], [100, 70, 10].map(grade))
            }
        """.trimIndent()
        assertEquals("", run(source))
        assertContains(compile(source), "| Some n when n > 0 -> \"positive\"")
        // A guarded branch may not match, so it covers nothing for certain.
        assertContains(error("f(x: Option<Int>): Int = when (x) {\n  Some(n) if n > 0 -> 1\n  None -> 0\n}\nmain() = Unit"), "this when doesn't cover Some(_)")
        assertContains(error("f(x: Option<Int>): Int = when (x) {\n  Some(n) if n -> 1\n  else -> 0\n}\nmain() = Unit"), "expected Boolean but found Int")
        assertContains(error("f(x: Int): Int = when {\n  x > 0 -> 1\n  else if x < 0 -> 2\n  else -> 0\n}\nmain() = Unit"), "a 'when' without a subject has conditions already")
    }

    @Test
    fun `a case's fields can be var`() {
        val tree = "type Tree { Leaf, Node(var left: Tree, value: Int, var right: Tree) }\n"
        fun program(source: String) = error("$tree$source\nmain() = assert(true)")
        assertContains(compile(tree + "@Mutating f(t: Tree) = when (t) {\n  is Node -> { t.left = Leaf }\n  else -> Unit\n}\nmain() = Unit"), "| Node of { mutable left : tree; value : int; mutable right : tree }")
        assertContains(program("@Mutating f(t: Tree) = when (t) {\n  is Node -> { t.value = 1 }\n  else -> Unit\n}"), "Node's value can't be changed; declare it with var value: ...")
        assertContains(program("f(t: Tree) = when (t) {\n  is Node -> { t.left = Leaf }\n  else -> Unit\n}"), "'f' does @Mutating")
        assertContains(program("@Mutating f(t: Tree) = { t.left = Leaf }"), "only Node of Tree has left: check which it is first, as in when (t) { is Node -> ... t.left ... }")
        // A case shared with another type is an OCaml polymorphic variant, whose fields can't change.
        assertContains(
            error("type Cell { Empty, Full(var n: Int) }\ntype Slot { Cell.Full, Spare }\n@Mutating f(s: Slot) = when (s) {\n  is Full -> { s.n = 2 }\n  else -> Unit\n}\nmain() = Unit"),
            "n can't be changed here: its case is shared with another type",
        )
    }

    @Test
    fun `effect declares an effect, with operations or without`() {
        val source = """
            effect Clock {
              now(): Int
            }
            effect Database
            @Database @Console annotation type Repository
            @Database save(x: Int) = Unit
            @Clock stamp() = now()
            // Still a name for anything else.
            effect(x: Int) = x + 1
            @Console main() = Clock(now = ~7) { println((stamp() + effect(1)).toString()) }
        """.trimIndent()
        assertEquals("9\n", run(source))
        // What it means, written out, works as before.
        assertEquals("7\n", run("@Effect spec Clock { now(): Int }\n@Console main() = Clock(now = ~7) { println(now().toString()) }"))
        assertContains(error("effect Clock(at: Int)\nmain() = Unit"), "an effect has no fields: its operations go in braces, as in effect Clock { op(x: Int): Unit }")
        assertContains(error("effect Tagged<A>\nmain() = Unit"), "only an effect with operations takes type parameters")
        assertContains(error("annotation type Route\n@Route f() = 1\ng(h: @Route () -> Unit) = h()\nmain() = Unit"), "'Route' isn't an effect; declare one with effect Route")
    }

    @Test
    fun `every error is reported, not just the first`() {
        val types = """
            double(x: Int) = x * 2
            broken(s: String): Int = s
            usesBroken() = broken("a") + 1
            alsoBad() = println("x")
            wrongCall() = double("three")
            main() = assert(double(3) == 6)
        """.trimIndent()
        // usesBroken is fine: broken's error is reported where it is, not again where it's used.
        assertEquals(
            "2:26: error: type mismatch: expected Int but found String\n" +
                "4:1: error: 'alsoBad' does @Console, so it must say so: @Console alsoBad(...)\n" +
                "5:22: error: type mismatch: expected Int but found String",
            error(types),
        )
        val syntax = "a() = (1 +\nb() = 2\nc( = 3\nd() = {\n  x = )\n}\ne() = 1\nmain() = Unit"
        assertEquals(
            "2:5: error: expected ')' but found '='\n3:4: error: expected a parameter name but found '='\n5:7: error: expected an expression but found ')'",
            error(syntax),
        )
    }

    @Test
    fun `a type can take effects as a parameter`() {
        val box = "effect Clock { now(): Int }\ntype Box<@E>(run: @E () -> Unit)\n"
        fun program(source: String) = error("$box$source")
        // Given, as several effects, or one with a parameter's; left out, it's whatever's put in.
        val source = box + """
            @Console @Clock both(b: Box<@Console @Clock>) = b.run()
            @Console logged<@E>(b: Box<@Console @E>) = {
              println("running")
              b.run()
            }
            runIt(b: Box) = b.run()
            @Console main() = Clock(now = ~7) {
              both(Box(~println("tick ${'$'}{now()}")))
              logged(Box(~println("inside")))
              runIt(Box(~println("anything")))
            }
        """.trimIndent()
        assertEquals("tick 7\nrunning\ninside\nanything\n", run(source))
        assertContains(program("@Console main() = {\n  b: Box<@Console> = Box(~println(now().toString()))\n  b.run()\n}"), "this does @Clock")
        assertContains(program("f(b: Box<Int>) = Unit\nmain() = assert(true)"), "Box's E is effects, like Box<@Console>, not a type")
        assertContains(program("f<@E>(b: Box<@E>): Box<@E> = Box(~println(\"x\"))\nmain() = assert(true)"), "effect parameter @E of 'f' always does @Console here")
        assertContains(program("f<@E>(x: E) = x\nmain() = assert(true)"), "'E' is an effect parameter, written @E")
        assertContains(program("f<@E, @F>(b: Box<@E @F>) = Unit\nmain() = assert(true)"), "effects can name only one effect parameter")
        assertContains(program("pureRun(b: Box<@Pure>) = b.run()\n@Console main() = pureRun(Box(~println(\"no\")))"), "says it's pure")
    }

    @Test
    fun `an if without an else is a statement`() {
        assertEquals("big\n", run("@Console main() = {\n  if (2 > 1) println(\"big\")\n  if (1 > 2) println(\"small\")\n}"))
        // fail gives Nothing, which fits as Unit.
        assertEquals("", run("@Fail check(n: Int): Int = {\n  if (n < 0) fail(\"negative\")\n  n\n}\nmain() = assertEquals(Error(\"negative\"), attempt { check(-1) })"))
        assertContains(error("f(b: Boolean) = {\n  if (b) 1\n  2\n}\nmain() = assert(true)"), "an 'if' without 'else' gives Unit, but this branch gives Int: add an 'else' to give a value")
    }

    @Test
    fun `a block after a call is its last argument`() {
        val source = """
            @Console prefixed<A>(prefix: String, block: @Console () -> A): A = Console(
              print = s -> print("${'$'}prefix${'$'}s"),
              println = line -> println("${'$'}prefix${'$'}line"),
              readLine = readLine,
            ) { block() }
            twice(f: () -> Int): Int = f() + f()
            @Console main() = {
              prefixed("> ") { println("hello") }
              println(twice { 21 }.toString())
            }
        """.trimIndent()
        assertEquals("> hello\n42\n", run(source))
    }

    @Test
    fun `the prelude's Fail and sequence pass on what their blocks do`() {
        val source = """
            @Console main() = {
              tried = attempt {
                println("trying")
                if (true) fail("nope")
                1
              }
              assertEquals(Error("nope"), tried)
              numbers = sequence {
                println("making 1")
                yield(1)
                println("making 2")
                yield(2)
              }
              println(numbers.take(1).toString())
            }
        """.trimIndent()
        assertEquals("trying\nmaking 1\n[1]\n", run(source))
    }

    @Test
    fun `lists have Kotlin's everyday functions`() {
        val source = """
            type Person(name: String, age: Int)
            main() = {
              xs = [3, 1, 2, 3, 1]
              assertEquals([3, 1, 2], xs.distinct())
              assertEquals([1, 1, 2, 3, 3], xs.sorted())
              assertEquals([3, 3, 2, 1, 1], xs.sortedDescending())
              assertEquals(Some(1), xs.lastOrNull())
              assertEquals(Some(2), xs.getOrNull(2))
              assertEquals(None, xs.getOrNull(-1))
              assertEquals(2, xs.count(x -> x == 3))
              assertEquals([(1, "a"), (2, "b")], [1, 2, 3].zip(["a", "b"]))
              assertEquals([(0, "a"), (1, "b")], ["a", "b"].withIndex())
              assertEquals([3, 1], xs.take(2))
              assertEquals([3, 1], xs.drop(3))
              assertEquals(["b", "a"], [Person("a", 30), Person("b", 20)].sortedBy(p -> p.age).map(p -> p.name))
            }
        """.trimIndent()
        assertEquals("", run(source))
    }

    @Test
    fun `arguments run left to right, as in Kotlin`() {
        val source = """
            @Console f(s: String): Int = {
              print(s)
              1
            }
            g(a: Int, b: Int) = a + b
            @Console main() = {
              pair = (f("a"), f("b"))
              sum = f("c") + g(b = f("d"), a = f("e"))
              items = [f("f"), f("g")]
              println("${'$'}{f("h")}${'$'}{f("i")} ${'$'}pair ${'$'}sum ${'$'}items")
            }
        """.trimIndent()
        assertEquals("abcdefghi11 (1, 1) 3 [1, 1]\n", run(source))
    }

    @Test
    fun `a handler can resume the rest of the block now, later, or never`() {
        assertEquals("a: started\nb: started\na: finished\nb: finished\n", run(File("examples/resume.fk").readText()))
        val fail = "effect Fail { fail(reason: String): Nothing }\neffect Choose { choose(): Boolean }\n"
        fun program(source: String) = error("$fail$source")
        // Nothing can't be answered, or resumed with.
        assertContains(
            program("f(): Int = Fail(fail = r -> 0) { fail(\"x\") }\nmain() = assert(true)"),
            "'fail' gives Nothing, so it can't be answered: take resume and don't call it, as fail = (reason, resume) -> ...",
        )
        assertContains(program("f(): Int = Fail(fail = (r, resume) -> resume(0)) { fail(\"x\") }\nmain() = assert(true)"), "expected Nothing but found Int")
        // What resume gives is what the block gives.
        assertContains(program("f(): Int = Choose(choose = resume -> resume(true) == 1) { 1 }\nmain() = assert(true)"), "expected Int but found Boolean")
        // A handler that takes resume can also call it straight away, as one that doesn't would.
        assertEquals("3\n", run("$fail@Console main() = println(Choose(choose = resume -> resume(false)) { if (choose()) 1 else 3 }.toString())"))
    }

    @Test
    fun `a handled effect's operations are answered by whatever handles it`() {
        val source = File("examples/handlers.fk").readText()
        assertEquals("from the terminal\n", run(source))
        val ml = compile(source)
        assertContains(ml, "| Console_println : string -> unit Effect.t")
        assertContains(ml, "| effect (Clock_now ()), k -> h.now (fun v -> Effect.Deep.continue k v)")
        val clock = "effect Clock { now(): Int }\neffect Yielding<A> { yield(value: A): Unit }\n"
        fun program(source: String) = error("$clock$source")
        // main must handle what it does, unless the effect has a default.
        assertContains(program("@Clock stamp() = now()\nmain() = println(\"${'$'}{stamp()}\")"), "main does @Clock, but nothing handles it: run it in a handler, like Clock(...) { ... }")
        compile("$clock@Clock stamp() = now()\nmain() = Clock(now = ~42) { println(\"${'$'}{stamp()}\") }")
        // Handling takes the effect away: a fake console makes a pure function.
        compile("$clock@Console greet() = println(\"hi\")\nquiet(): Int = { Console(print = s -> Unit, println = s -> Unit, readLine = ~\"\") { greet() }; 1 }\nmain() = assert(true)")
        // A generic effect's type arguments are what it does.
        assertContains(program("@Yielding<String> bad() = yield(1)\nmain() = assert(true)"), "'bad' does @Yielding<Int>, but says <String>")
        assertContains(program("module Gen : Yielding { yield(value: Int) = Unit }\nmain() = assert(true)"), "Gen can't follow Yielding, which takes type parameters")
        // Two effects with an operation of the same name: say which.
        // A program's own operation stands in for the prelude's of the same name, unless both are named.
        assertContains(program("effect Log { println(s: String): Unit }\nf() = println(\"x\")\nmain() = assert(true)"), "'f' does @Log")
        assertContains(
            program("effect Log { println(s: String): Unit }\n@Console f() = Console.println(\"x\")\ng() = println(\"x\")\nmain() = assert(true)"),
            "'println' is an operation of Console and Log; write Console.println or Log.println",
        )
        assertContains(program("main() = Clock(now = 1) { 1 }"), "expected () -> Int but found Int")
        assertContains(program("main() = {\n  h = 1\n  h { 2 }\n}"), "this isn't a handler")
        // A repository hides what its module does, but not an effect that needs a handler.
        val repository = "effect Persistence\n@Persistence @Encapsulates annotation type Repository\n@Repository spec Store { load(): Int }\n"
        assertContains(
            program("${repository}module Timed : Store { @Clock load() = now() }\nmain() = assert(true)"),
            "'load' does @Clock, which needs a handler and has no default, so Store can't hide it: handle it inside Timed, or give Clock a default with @HandledBy",
        )
        compile("$clock${repository}module Timed : Store { load() = Clock(now = ~1) { now() } }\nmain() = assert(true)")
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
    fun `folders are packages, and capitalised files are modules`() {
        val dir = kotlin.io.path.createTempDirectory("fckt").toFile()
        val lib = File(dir.parentFile, dir.name + "-lib").apply { mkdir() }
        fun write(path: String, text: String, root: File = dir) = File(root, path).apply { parentFile.mkdirs() }.writeText(text)
        try {
            write("Ratios.fk", "module Ratios {\n  type Ratio(n: Int, d: Int)\n  private gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)\n  make(n: Int, d: Int): Ratio = {\n    g = gcd(n, d)\n    Ratio(n / g, d / g)\n  }\n}\n")
            // Halves uses Ratios, in the same package, so needs no import, and comes in after it.
            write("Halves.fk", "module Halves {\n  half(r: Ratios.Ratio): Ratios.Ratio = Ratios.make(r.n, r.d * 2)\n}\n")
            write("geometry/Shapes.fk", "module Shapes {\n  type Shape { Circle(radius: Float), Square(side: Float) }\n  area(s: Shape): Float = when (s) {\n    Circle(r) -> 3.0 * r * r\n    Square(side) -> side * side\n  }\n}\n")
            // A lower-case file's declarations are its package's.
            write("geometry/units.fk", "cm(m: Float): Float = m * 100.0\nprivate secret(): Int = 1\n")
            write("extras/ListExtras.fk", "module ListExtras {\n  sumBy<A>(xs: List<A>, f: (A) -> Int): Int = xs.map(f).sum()\n}\n", lib)
            // Not used, so never read, though it doesn't compile.
            write("unused.fk", "bad(): Int = \"x\"\n")
            val main = File(dir, "main.fk")
            fun compileMain(source: String) = main.writeText(source).let { compile(main, listOf(dir, lib)) }
            fun errorIn(source: String) = assertFailsWith<CompileError> { compileMain(source) }.toString()
            val ml = compileMain(
                """
                import geometry.Shapes
                import geometry.Shapes.Shape.Circle as Round
                import geometry.cm
                import extras.ListExtras

                main() = {
                  assertEquals(Ratios.Ratio(1, 4), Halves.half(Ratios.make(2, 4)))
                  assertEquals(12.0, Shapes.area(Circle(2.0)))
                  assertEquals(3.0, Shapes.area(Round(1.0)))
                  assertEquals(250.0, cm(2.5))
                  assertEquals(6, ListExtras.sumBy(["a", "bb", "ccc"], s -> s.length))
                  // A full name needs no import.
                  assertEquals(4.0, geometry.Shapes.area(geometry.Shapes.Square(2.0)))
                }
                """.trimIndent(),
            )
            assertEquals("", runOcaml(ml))
            assertContains(ml, "module Geometry = struct")
            assertTrue(ml.indexOf("module Ratios") < ml.indexOf("module Halves"))
            assertContains(errorIn("main() = Ratios.gcd(2, 4)"), "'gcd' is private to Ratios")
            assertContains(errorIn("main() = Shapes.area(Shapes.Circle(1.0))"), "unknown name 'Shapes'")
            assertContains(errorIn("import geometry.Nope\nmain() = Unit"), "geometry has no 'Nope'")
            assertContains(errorIn("import nowhere.Thing\nmain() = Unit"), "import nowhere.Thing names no package")
            assertContains(errorIn("import geometry.secret\nmain() = assertEquals(1, secret())"), "'secret' is private to")
            assertContains(errorIn("main() = assertEquals(Some(1), Options.pure(1))"), "unknown name 'Options'; it's in the standard library: import fckt.Options")
            // .* brings in all of a package, or of a module; as renames; two imports' names clash only where used.
            write("geometry/Units.fk", "module Units {\n  mm(m: Float): Float = m * 1000.0\n}\n")
            write("shop/Items.fk", "module Items {\n  type Shape(name: String)\n}\n")
            assertEquals("", runOcaml(compileMain("import geometry.*\nmain() = assertEquals(4000.0, Units.mm(Shapes.area(Shapes.Square(2.0))))")))
            assertEquals("", runOcaml(compileMain("import geometry.Shapes.*\nmain() = assertEquals(12.0, area(Circle(2.0)))")))
            assertEquals("", runOcaml(compileMain("import geometry.Shapes.area as size\nimport geometry.Shapes\nmain() = assertEquals(4.0, size(Square(2.0)))")))
            assertContains(errorIn("import geometry.Shapes\nimport shop.Items\nf(s: Shape) = 1\nmain() = Unit"), "'Shape' is imported from geometry.Shapes.Shape and shop.Items.Shape; import the one you mean by name")
            compileMain("import geometry.Shapes\nimport shop.Items\nimport geometry.Shapes.Shape\nf(s: Shape) = 1\nmain() = Unit")
            assertContains(errorIn("import geometry.Shapes.area\nimport geometry.Units.mm as area\nmain() = Unit"), "'area' is imported already, as geometry.Shapes.area")
            // A capitalised file is its module, written out; another module in it must be private.
            write("Loose.fk", "f() = 1\n")
            assertContains(errorIn("main() = assertEquals(1, Loose.f())"), "Loose.fk must declare module Loose { ... }")
            write("Two.fk", "module Two { f() = 1 }\nmodule Other { g() = 2 }\n")
            assertContains(errorIn("main() = assertEquals(1, Two.f())"), "module Other belongs in its own file, Other.fk; or make it private to Two.fk")
            write("Pair.fk", "module Pair { f() = Helper.g() }\nprivate module Helper { g() = 2 }\n")
            assertEquals("", runOcaml(compileMain("main() = assertEquals(2, Pair.f())")))
            // A program's own declarations come first: this Ratios is the program's, not the file.
            assertEquals("", runOcaml(compileMain("module Ratios { one() = 1 }\nmain() = assertEquals(1, Ratios.one())")))
        } finally {
            dir.deleteRecursively()
            lib.deleteRecursively()
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
        assertContains(error("f(x: Int) = if (x > 0) 1"), "an 'if' without 'else' gives Unit, but this branch gives Int")
        assertContains(error("f(x: Int) = when { x > 0 -> 1 }"), "needs an 'else' branch")
        assertContains(error("interface Shape { Circle }"), "write a type with cases as 'type Shape { ... }'")
        assertContains(error("f() = {\n  y = 1\n  y = 2\n  y\n}"), "'y' is already defined in this block")
        assertContains(error("f() = {\n  y = 1\n}"), "must end with an expression")
        // A line break after '>' still continues a comparison.
        compile("f(x: Int) = {\n  big = x >\n    10\n  big\n}")
    }
}
