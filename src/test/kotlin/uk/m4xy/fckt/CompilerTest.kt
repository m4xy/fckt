package uk.m4xy.fckt

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import uk.m4xy.fckt.syntax.CompileError
import uk.m4xy.fckt.syntax.Decl
import uk.m4xy.fckt.syntax.Expr
import uk.m4xy.fckt.syntax.Meta
import uk.m4xy.fckt.syntax.StringPart
import uk.m4xy.fckt.types.effectiveAnnotations
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The standard library, fckt:std, as its source. */
private val STD = File("std/src")

/** What the tests' own programs import, beside the core. */
private val TEST_IMPORTS = listOf("fckt.io.Console", "fckt.effects.Fail", "fckt.effects.Failure.*", "fckt.collections.Maps.*", "fckt.collections.Sets.*")

class CompilerTest {
    /** Compiles [source], runs the OCaml with the toplevel, and returns its standard output. */
    private fun run(source: String, args: List<String> = emptyList(), dir: File? = null): String = runOcaml(compile(source), args, dir)

    /**
     * [source] to OCaml, with the standard library from std/src, and what most tests use of it
     * imported, as each test is about something else: an example says its imports itself.
     */
    private fun compile(source: String): String = uk.m4xy.fckt.compile(source, TEST_IMPORTS, listOf(STD))

    /** An example, as it is: with only what it imports, and the standard library. */
    private fun example(source: String): String = uk.m4xy.fckt.compile(source, others = listOf(STD))

    /** Runs an example, as it is. */
    private fun runExample(source: String, args: List<String> = emptyList()): String = runOcaml(example(source), args)

    private val ocaml: String? = Ocaml.path

    private fun excerpt(output: String, ml: String): String = Ocaml.excerpt(output, ml)

    private fun runOcaml(ml: String, args: List<String> = emptyList(), dir: File? = null, env: Map<String, String> = emptyMap(), code: Int = 0): String =
        Ocaml.run(ml, args, dir, env, code)

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
            runExample(source),
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
                assertEquals(expected, runExample(source, args))
            }
        }

    @Test
    fun `a when must cover every value`() {
        val letters = "type Letter { X, Y, Z }\n"
        assertEquals("", run(letters + "f(a: Letter) = when (a) {\n  X -> 1\n  else -> 2\n}\nmain() = assertEquals(2, f(Z))"))
        // Inside cases, tuples and lists, with an example of what's missing.
        assertEquals("", run("f(xs: List<Int>) = when (xs) {\n  [] -> 0\n  [x, *_] -> x\n}\nmain() = assertEquals(1, f([1, 2]))"))
        // Numbers and strings can't all be listed.
        // `is Vowel` covers Vowel's cases, which are some of Alphabet's.
    }

    @Test
    fun `records, types with cases and when`() {
        val ml = example(File("examples/orders.fk").readText())
        assertContains(ml, "type user_id = { value : string }")
        assertContains(ml, "| Success of { order_id : string; total : money }")
        assertContains(ml, "| Some \"X\" -> int_of_float (float_of_int subtotal *. 0.1)")
        assertContains(ml, "let gateway = fake_gateway |> with_logging in")
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
        assertEquals("", runExample(File("examples/shared-cases.fk").readText()))
    }

    @Test
    fun `modules group declarations and hide private ones`() {
        assertEquals("", runExample(File("examples/modules.fk").readText()))
    }


    @Test
    fun `annotations are checked, then compiled away`() {
        val source = File("examples/annotations.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        listOf("Route", "Pure", "Codec", "route", "pure", "codec").forEach { assertFalse(it in ml, it) }
        // Every declaration in it, but main.
    }

    @Test
    fun `a function says what it does besides work out its result`() {
        assertEquals("hello fckt\nhello Ada\nhello Grace\ntotal: 3\nhi, there\ntransferred 10\n", runExample(File("examples/effects.fk").readText()))
        // A function type says what calling it may do; unmarked, it's pure.
        // A spec that says what its functions may do is a promise its modules must keep.
        // One that says nothing takes what its module does, which shows wherever that module is used.
    }

    @Test
    fun `vars change, and changing one someone else can see is an effect`() {
        val source = File("examples/mutation.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "mutable balance : int")
        assertContains(ml, "let n = ref 0 in")
        val account = "type Account(owner: String, var balance: Int)\n"
        fun program(source: String) = error("$account$source\nmain() = assert(true)")
        // Whatever holds it, it's the same account: in an option, a box, a case, or passed back.
        // A top-level value lives as long as the program, so changing it is always seen.
        // A function that changes a var can't escape as a pure one.
    }

    @Test
    fun `arrays, and types OCaml implements, indexed with get and set`() {
        val source = File("examples/arrays.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "Array.set xs 0 (Array.get xs 0 * 2)")
        assertContains(ml, "Buffer.add_string b word")
        // A type of OCaml's own, with its type parameters in place.
        assertContains(compile("external type Table<K, V> = \"('k, 'v) Hashtbl.t\"\nexternal table<K, V>(): Table<K, V> = \"Hashtbl.create 8\"\nt: Table<String, List<Int>> = table()\nmain() = assert(true)"), "(string, int list) Hashtbl.t")
    }

    @Test
    fun `annotations pass on what they carry, and a module's effects pass to what's in it`() {
        val ordering = File("examples/ordering.fk").readText()
        assertEquals("UPSERT INTO orders ...\nSELECT * FROM orders WHERE id = 'order-1'\nUPSERT INTO orders ...\n", runExample(ordering))
        // What a repository does underneath stays inside it: its callers see @Persistence, not @Console.
        // And the domain can't reach one at all.
        // A module with effects is a ceiling: nothing in it can add more, and @Pure allows nothing.
        // The same annotation by two ways with different values must be chosen between.
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
        // Handling Database doesn't handle a copy.
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
    }

    @Test
    fun `a case's fields can be var`() {
        val tree = "type Tree { Leaf, Node(var left: Tree, value: Int, var right: Tree) }\n"
        assertContains(compile(tree + "@Mutating f(t: Tree) = when (t) {\n  is Node -> { t.left = Leaf }\n  else -> Unit\n}\nmain() = Unit"), "| Node of { mutable left : tree; value : int; mutable right : tree }")
        // A case shared with another type is an OCaml polymorphic variant, whose fields can't change.
    }

    @Test
    fun `an if without an else is a statement`() {
        assertEquals("big\n", run("@Console main() = {\n  if (2 > 1) println(\"big\")\n  if (1 > 2) println(\"small\")\n}"))
        // fail gives Nothing, which fits as Unit.
        assertEquals("", run("@Fail check(n: Int): Int = {\n  if (n < 0) fail(\"negative\")\n  n\n}\nmain() = assertEquals(Error(\"negative\"), attempt { check(-1) })"))
    }

    @Test
    fun `a lambda takes a tuple apart, and a call can say its function's type arguments`() {
        val source = """
            List<A>.firstAs<B>(f: (A) -> B): Option<B> = this.firstOrNone().map(f)
            main() = {
              pairs = [("a", 1), ("b", 2)]
              assertEquals(["a1", "b2"], pairs.map((k, v) -> k + v.toString()))
              assertEquals([("b", 2)], pairs.filter((_, v) -> v > 1))
              assertEquals([6], [(1, 2, 3)].map((a, b, c) -> a + b + c))
              // Two parameters where two are expected are two, as ever.
              assertEquals(3, [1, 2].fold(0, (sum, x) -> sum + x))
              m = emptyMap<String, Int>()
              assertEquals("{a=1}", m.put("a", 1).toString())
              assertEquals(Some("n1"), [1].firstAs<String>(x -> "n${'$'}x"))
              // Otherwise < compares.
              a = 1
              assert(a < 2 && 2 > a)
            }
        """.trimIndent()
        assertEquals("", run(source))
        fun error(code: String) = assertFailsWith<CompileError> { compile("List<A>.firstAs<B>(f: (A) -> B): Option<B> = this.firstOrNone().map(f)\ntype Box<A>(item: A)\nmain() = $code") }.message
        assertContains(error("[(1, 2)].map((a, b, c) -> a)").orEmpty(), "this function is given a tuple of 2, so it takes 2 parameters, not 3")
        assertContains(error("emptyMap<String>()").orEmpty(), "'emptyMap' takes 2 type arguments, not 1")
        assertContains(error("[1].firstAs<Int, Int>(x -> x)").orEmpty(), "'firstAs' takes 1 type argument, not 2")
        assertContains(error("Box<Int>(1)").orEmpty(), "only a declared function is given type arguments")
        assertContains(error("{\n  m: Map<String, Int> = emptyMap<Int, Int>()\n  m\n}").orEmpty(), "type mismatch")
    }

    @Test
    fun `a block given last says its parameters, or a function of one calls it it`() {
        val source = """
            twice(f: (Int) -> Int): Int = f(f(1))
            maker(make: () -> (Int) -> Int): Int = make()(10)
            main() = {
              xs = [1, 2, 3]
              assertEquals([2, 3], xs.filter { x -> x > 1 })
              assertEquals([2, 3], xs.filter { it > 1 })
              assertEquals(6, xs.fold(0) { sum, x -> sum + x })
              assertEquals(["a1", "b2"], [("a", 1), ("b", 2)].map { k, v -> "${'$'}k${'$'}v" })
              assertEquals([11, 21, 31], xs.map { x ->
                y = x * 10
                y + 1
              })
              assertEquals(9, twice { it * 3 })
              // In parentheses, a lambda is what the block gives back.
              assertEquals(11, maker { (x -> x + 1) })
              assertEquals([[2, 3], [3], []], xs.map { x -> xs.filter { it > x } })
            }
        """.trimIndent()
        assertEquals("", run(source))
        fun error(code: String) = assertFailsWith<CompileError> { compile("maker(make: () -> (Int) -> Int): Int = make()(10)\nmain() = {\n  xs = [1]\n  $code\n}") }.message.orEmpty()
        assertContains(error("maker { x -> x + 1 }"), "this block takes no parameters, as it's run with none: to give back a function, write { (x -> ...) }")
        assertContains(error("xs.map { xs.filter { it > 1 } + [it] }"), "this block's 'it' hides the 'it' outside it: name this block's parameter, { x -> ... }")
        assertContains(error("xs.fold(0) { it }"), "this block is given 2 parameters, so it has no 'it': name them, { a, b -> ... }")
        assertContains(error("maker { it }"), "there's no 'it' here")
    }

    @Test
    fun `an operator is an extension marked operator, of one of the operators' names`() {
        fun error(source: String) = assertFailsWith<CompileError> { compile(source.trimIndent()) }.message!!
        assertContains(
            error("type Money(pence: Int)\nMoney.plus(other: Money): Money = Money(this.pence + other.pence)\nmain() = assertEquals(Money(2), Money(1) + Money(1))"),
            "its plus isn't an operator; mark it operator",
        )
        assertContains(error("type Money(pence: Int)\nmain() = assertEquals(Money(2), Money(1) * 2)"), "'*' can't be applied to Money and Int: there's no operator times")
        assertContains(error("type Money(pence: Int)\noperator Money.add(other: Money): Money = this\nmain() = Unit"), "there's no operator 'add'")
        assertContains(error("type Money(pence: Int)\noperator plus(a: Money, b: Money): Money = a\nmain() = Unit"), "an operator is a type's, so it's an extension")
        assertContains(error("type Money(pence: Int)\noperator Money.plus(): Money = this\nmain() = Unit"), "plus takes one parameter")
        // A soft keyword: still a name.
        assertEquals("", run("operator(n: Int): Int = n\nmain() = assertEquals(1, operator(1))"))
    }

    @Test
    fun `a handler can resume the rest of the block now, later, or never`() {
        assertEquals("a: started\nb: started\na: finished\nb: finished\n", runExample(File("examples/resume.fk").readText()))
        val fail = "effect Fail { fail(reason: String): Nothing }\neffect Choose { choose(): Boolean }\n"
        // Nothing can't be answered, or resumed with.
        // What resume gives is what the block gives.
        // A handler that takes resume can also call it straight away, as one that doesn't would.
        assertEquals("3\n", run("$fail@Console main() = println(Choose(choose = resume -> resume(false)) { if (choose()) 1 else 3 }.toString())"))
    }

    @Test
    fun `a handled effect's operations are answered by whatever handles it`() {
        val source = File("examples/handlers.fk").readText()
        assertEquals("from the terminal\n", runExample(source))
        val ml = example(source)
        assertContains(ml, "| Console_println : string -> unit Effect.t")
        assertContains(ml, "| effect (Clock_now ()), k -> h.now (fun v -> Effect.Deep.continue k v)")
        val clock = "effect Clock { now(): Int }\neffect Yielding<A> { yield(value: A): Unit }\n"
        fun program(source: String) = error("$clock$source")
        // A generic effect's type arguments are what it does.
        // Two effects with an operation of the same name: say which.
        // A program's own operation stands in for the prelude's of the same name, unless both are named.
        // A repository hides what its module does, but not an effect that needs a handler.
    }

    @Test
    fun `opaque types are abstract outside their module`() {
        val source = File("examples/specs.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
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
        // Nothing written says twice takes a T, so it takes an Int.
        assertEquals("", run(percent + "main() = assertEquals(4, Percent.twice(2))"))
    }


    @Test
    fun `functors make modules from modules`() {
        val source = File("examples/functors.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "module SortedSet (M : Ordered) : sig")
        assertContains(ml, "module IntSet = SortedSet (IntOrder)")
        // Set is opaque, so only SortedSet's functions make one, in order.
        assertContains(ml, "\"Set(items=[1, 3, 5])\" (IntSet.show_set numbers)")
    }

    @Test
    fun `functor errors`() {
        // Modules go in parentheses, like a constructor's arguments; <...> is for types.
        // The body works for any M, so it can't assume what M.T is.
    }

    @Test
    fun `a functor's modules can have defaults, and be given by name`() {
        val source = """
            spec Shout { loud(s: String): String }
            spec Greeting { hello(name: String): String }
            module Plain : Shout { loud(s: String): String = s }
            module Caps : Shout { loud(s: String): String = s.uppercase() }
            module English : Greeting { hello(name: String): String = "hello ${'$'}name" }
            module French : Greeting { hello(name: String): String = "bonjour ${'$'}name" }
            module Greeter(S: Shout = Plain, G: Greeting = English) {
              greet(name: String): String = S.loud(G.hello(name))
            }
            module Standard = Greeter()
            module Loud = Greeter(Caps)
            module Polite = Greeter(G = French)
            module Both = Greeter(Caps, G = French)

            @Console main() = println("${'$'}{Standard.greet("a")}, ${'$'}{Loud.greet("b")}, ${'$'}{Polite.greet("c")}, ${'$'}{Both.greet("d")}")
        """.trimIndent()
        assertEquals("hello a, HELLO B, bonjour c, BONJOUR D\n", run(source))
        assertContains(compile(source), "module Polite = Greeter (Plain) (French)")

        // Without a default, a module must be given.
        // A default must follow the spec too.
    }

    @Test
    fun `a module is a value of the spec it follows`() {
        val source = """
            spec Reporting { shown(line: String): Boolean = true }
            module Everything : Reporting {}
            module Quiet : Reporting { shown(line: String): Boolean = line.startsWith("!") }

            spec Plugin {
              name(): String
              tasks(project: String): List<String>
            }
            module Docs : Plugin {
              name(): String = "docs"
              tasks(project: String): List<String> = ["docs ${'$'}project"]
            }
            module Tests(R: Reporting = Everything) : Plugin {
              name(): String = "tests"
              tasks(project: String): List<String> = ["test ${'$'}project", "! check ${'$'}project"].filter(line -> R.shown(line))
            }

            // A module, and what functors make, called where they're wanted.
            plugins: List<Plugin> = [Docs, Tests(), Tests(R = Quiet)]

            @Console main() = plugins.forEach(p -> println("${'$'}{p.name()}: ${'$'}{p.tasks("shop").joinToString(", ")}"))
        """.trimIndent()
        assertEquals("docs: docs shop\ntests: test shop, ! check shop\ntests: ! check shop\n", run(source))
        assertContains(compile(source), "(module Docs : Plugin)")

        // A value shows only its spec, so what it does must be what the spec says it may.
    }

    @Test
    fun `a value of a spec with types of its own says what they are`() {
        val source = """
            spec Format {
              type T
              of(value: T): String
              read(text: String): Result<T, String>
              both<B>(other: B, value: T): (B, T) = (other, value)
            }

            type Point(x: Int, y: Int)

            module PointText : Format {
              type T = Point
              of(value: Point): String = "${'$'}{value.x},${'$'}{value.y}"
              read(text: String): Result<Point, String> = when (text.split(",")) {
                [x, y] -> Ok(Point(x.toInt(), y.toInt()))
                else -> Error("not a point: ${'$'}text")
              }
            }

            module Upper : Format {
              type T = String
              of(value: String): String = value.uppercase()
              read(text: String): Result<String, String> = Ok(text.lowercase())
            }

            // Written once, for any format, whatever its T.
            readsBack<A>(format: Format<A>, value: A): Boolean = format.read(format.of(value)) == Ok(value)

            main() = {
              assert(readsBack(PointText, Point(1, 2)))
              assert(readsBack(Upper, "shout"))
              assert(!readsBack(Upper, "Shout"))
              formats: List<Format<Point>> = [PointText]
              assertEquals(["3,4"], formats.map { it.of(Point(3, 4)) })
              assertEquals(("a", Point(5, 6)), PointText.both("a", Point(5, 6)))
              f: Format<String> = Upper
              assertEquals((1, "x"), f.both(1, "x"))
            }
        """.trimIndent()
        assertEquals("", run(source))
        assertContains(compile(source), "(module PointText : Format with type t = PointText.t)")
    }

    @Test
    fun `spec type arguments say what a functor's parameter's types are`() {
        val source = File("examples/monads.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "module Options = struct\n  type 'a m = 'a option")
        assertContains(ml, "module Results = struct\n  type 'a m = ('a, string) result")
        // A module says what its types are in its body; type arguments are for a functor's parameters.
        val twice = "spec Ordered {\n  type T\n  compare(a: T, b: T): Int\n}\nmodule Twice(M: Ordered<Int>) {\n  f(a: Int, b: Int): Int = M.compare(a, b)\n}\n"
        assertContains(compile(twice + "module I : Ordered {\n  type T = Int\n  compare(a: Int, b: Int) = 0\n}\nmodule X = Twice(I)"), "(M : Ordered with type t = int)")
    }

    @Test
    fun `a module's block binds with by`() {
        val ml = example(File("examples/monads.fk").readText())
        assertContains(ml, "let ( let* ) = Options.bind in\n  let* h = half n in\n  let* q = half h in\n  Options.pure q")
        assertContains(ml, "let ( let* ) = M.bind in")
        assertContains(ml, "let* (a, b) = p in")
        val options = "module Options {\n  pure(x) = Some(x)\n  bind(m, f) = when (m) {\n    Some(x) -> f(x)\n    None -> None\n  }\n}\n" +
            "half(n: Int): Option<Int> = if (n % 2 == 0) Some(n / 2) else None\n"
        // No `by` line, so no let*: OCaml would warn that it's unused.
        assertEquals("let f (n : int) : int option = Options.pure n", compile(options + "f(n: Int): Option<Int> = Options { n }").trim().lines().last())
        assertEquals("", run(options + "by(n: Int) = { by = n; by + 1 }\nmain() = assertEquals(Some(2), Options { x by half(4); by(x) - 1 })"))
    }

    @Test
    fun `a module without bind runs its by lines together`() {
        val source = File("examples/independent-steps.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "let ( let+ ) = Validated.map in\n  let ( and+ ) = Validated.product in")
        assertContains(ml, "let+ n = check_name trimmed\n  and+ a = check_age age\n  and+ e = check_email email in")
        assertContains(ml, "let+ n = check_name name in\n  String.uppercase_ascii n")
    }

    @Test
    fun `the core and the standard library come in when they're used`() {
        val source = File("examples/stdlib.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "module Options = struct")
        assertContains(ml, "module type Monad = sig")
        // Only what's used: Monad, with Applicative, which it builds on, but no Streams; and nothing at
        // all for a program that uses none of it.
        assertTrue("Streams" !in ml)
        assertTrue("module" !in example("main() = assertEquals(1, 1)"))
        assertTrue("module type" !in example("import fckt.monads.Validation\nv(): Validation<Int, String> = Valid(1)\nmain() = assert(v() == Valid(1))"))
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
    fun `every file has the core, and imports the standard library as any library`() {
        // The methods on the built-in types need no import.
        assertEquals("", runOcaml(example("main() = assertEquals([2, 1], [2, 1, 2].distinct().filter { it > 0 })")))
        // What the standard library has does, and an unknown name says where it is.
        val missing = assertFailsWith<CompileError> { example("main() = println(\"hi\")") }.toString()
        assertContains(missing, "unknown name 'println'; import fckt.io.Console has one")
        assertContains(assertFailsWith<CompileError> { example("f(): Map<Int, Int> = emptyMap()") }.toString(), "unknown type 'Map'; import fckt.collections.Maps.Map has one")
        assertEquals("hi\n", runOcaml(example("import fckt.io.Console\nmain() = println(\"hi\")")))
        // A module's methods come with anything imported from it, as a type's do with it.
        assertEquals("", runOcaml(example("import fckt.collections.Sets.Set\nimport fckt.collections.Sets.setOf\nmain() = assert(setOf([1]).plus(2).contains(2))")))
        assertContains(
            assertFailsWith<CompileError> { example("import fckt.collections.Maps.*\nmain() = assert([1].toSet().isEmpty())") }.toString(),
            "List<Int> has no method 'toSet'; import fckt.collections.Sets.* has one",
        )
        // A library's code is its own: a program's Error is no part of it.
        assertEquals("", runOcaml(example("import fckt.effects.Failure.attempt\nimport fckt.effects.Fail\ntype Outcome { Fine, Error(why: String) }\n" +
            "main() = {\n  r: Result<Int, String> = attempt { fail(\"no\") }\n  assertEquals(\"Error(no)\", r.toString())\n}")))
    }

    @Test
    fun `extensions are chosen by their receiver's type`() {
        val source = File("examples/extensions.fk").readText()
        assertEquals("", runExample(source))
        val ml = example(source)
        assertContains(ml, "let list_int_total (this : int list) : int =")
        assertContains(ml, "let tuple_swap (this : 'a * 'b) : 'b * 'a =")
        // Externals are their OCaml, at each use; as a value, a function of their parameters.
        assertContains(ml, "(List.length [1; 2; 3])")
        assertContains(ml, "List.map (fun x0 -> Float.pow x0 3.) [1.0; 2.0]")
        assertContains(ml, "List.map (fun x0 -> ( / ) x0 2) [2; 4]")
        assertTrue("let halve" !in ml && "let cube" !in ml)
        // A program's own extension takes the place of the prelude's.
        assertEquals("", run("List<A>.size: Int = 7\nmain() = assertEquals(7, [1].size)"))
    }

    @Test
    fun `fckt io reads and writes files, and runs programs`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-io").toFile()
        try {
            val source = """
                import fckt.io.*
                import fckt.io.Channels.*
                import fckt.io.Files.*
                import fckt.io.Paths.*
                import fckt.io.Processes.*
                import fckt.text.Encodings.*

                shown(result: Result<String, String>): String = when (result) {
                  Ok(value) -> "ok ${'$'}value"
                  Error(reason) -> "failed: ${'$'}reason"
                }

                @Console @FileSystem @Process main() = {
                  out = path("out")
                  out.resolve("a").makeDirs()
                  x = out.resolve("a/x.txt")
                  x.writeText("one\ntwo\n")
                  x.appendText("three\n")
                  println(x.readLines().joinToString(","))
                  out.resolve("y.txt").writing {
                    writeLine("hello")
                    write("world")
                  }
                  println(out.resolve("y.txt").reading { [nextBytes(2).decodeToString(), nextLine().getOrElse("?"), remainingText()] }.joinToString("|"))
                  x.copyTo(out.resolve("z.txt"))
                  println(out.listFiles().map(p -> p.name).joinToString(",") + " " + out.resolve("a").isDirectory().toString() + " " + out.resolve("q").exists().toString())
                  exit = streaming("sh", ["-c", "echo first; echo second >&2; exit 3"]) {
                    forEachLine(line -> println("> ${'$'}line"))
                  }
                  println("exit ${'$'}{exit.code}: ${'$'}{run("echo", ["a b", "c"]).value.trim()}")
                  println(shown(attempt { out.resolve("missing").readText() }))
                  println(shown(attempt { out.resolve("y.txt").reading { line = nextLine().getOrElse(""); if (line == "hello") fail("stopped at ${'$'}line") else line } }))
                  out.delete()
                  println(out.exists().toString())
                  // Paths are tidied, and taken apart without looking at the disk.
                  p = path("a//b/./c/../d.fk")
                  println("${'$'}{p.value} ${'$'}{p.name} ${'$'}{p.extension} ${'$'}{p.parent.map(f -> f.value).getOrElse("-")} ${'$'}{p.withExtension("ml").value}")
                  println("${'$'}{path("/x/../..").value} ${'$'}{path("../a/..").value} ${'$'}{path("").value} ${'$'}{path("src/a/b.fk").relativeTo(path("src")).map(f -> f.value).getOrElse("-")}")
                  // Bytes, and text as UTF-8.
                  println("${'$'}{bytesOf([104.toByte(), 105.toByte()]).decodeToString()} ${'$'}{"ü".encodeToBytes().size} ${'$'}{bytesOf([255.toByte()]).decodeUtf8().isOk()}")
                }
            """.trimIndent()
            assertEquals(
                "one,two,three\nhe|llo|world\na,y.txt,z.txt true false\n> first\n> second\nexit 3: a b c\n" +
                    "failed: out/missing: No such file or directory\nfailed: stopped at hello\nfalse\n" +
                    "a/b/d.fk d.fk fk a/b a/b/d.ml\n/ .. . a/b.fk\nhi 2 false\n",
                run(source, dir = dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `main fails with the reason, and file system operations can be answered`() {
        val ml = compile("main() = {\n  println(\"a\")\n  fail(\"boom\")\n}")
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val file = File.createTempFile("fckt", ".ml").apply { writeText(ml) }
        try {
            val process = ProcessBuilder(ocaml, file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(1, process.exitValue(), output)
            assertEquals("a\nboom\n", output)
        } finally {
            file.delete()
        }
        // A test can answer FileSystem itself: only what it says is there is.
        val fake = """
            import fckt.io.FileSystem
            import fckt.io.Files.*
            import fckt.io.Paths.*
            import fckt.text.Encodings.*

            @FileSystem config(): String = {
              conf = path("build.conf")
              if (conf.exists()) conf.readText() else "default"
            }

            main() = {
              files = [path("build.conf")]
              found = attempt {
                FileSystem(
                  tryReadBytes = file -> if (files.contains(file)) Ok("from ${'$'}{file.value}".encodeToBytes()) else Error("no ${'$'}{file.value}"),
                  tryWriteBytes = (file, bytes, append) -> Error("read only"),
                  tryCopy = (source, target) -> Error("read only"),
                  exists = file -> files.contains(file),
                  isDirectory = file -> false,
                  tryListFiles = file -> Ok(files),
                  tryMakeDirs = file -> Error("read only"),
                  tryDelete = file -> Error("read only"),
                  tryOpenForReading = file -> Error("not here"),
                  tryOpenForWriting = (file, append) -> Error("not here"),
                  tryLastModified = file -> Error("not here"),
                  tryCanonical = file -> Ok(file),
                  workingDirectory = ~path("."),
                  tryChangeDirectory = file -> Error("read only")
                ) { config() }
              }
              println(found.toString())
            }
        """.trimIndent()
        assertEquals("Ok(from build.conf)\n", run(fake))
        // A trailing block is the last parameter, so those with defaults before it can be left out.
        assertEquals("hi\nhi\n", run("@Console repeat(times: Int = 2, f: @Console () -> Unit): Unit = if (times > 0) {\n  f()\n  repeat(times - 1, f)\n}\nmain() = repeat { println(\"hi\") }"))
    }

    @Test
    fun `a folder's tests are run each on its own, by the runner given`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-tests").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        try {
            write("src/pricing/Prices.fk", "module Prices {\n  total(items: List<Int>): Int = items.fold(0, (a, b) -> a + b)\n}\n")
            // What tests are written with, and, apart, what runs them, with what fckt --test calls.
            write("lib/check/Test.fk", "annotation type Test\n")
            // Its types are its own, as it likes them: fckt makes them only through the runner.
            writeRunner(::write)
            val test = File(dir, "test")
            write(
                "test/prices.fk",
                "import check.Test\nimport pricing.Prices\n\n@Test sums() = assert(Prices.total([1, 2]) == 3)\n" +
                    "@Test empty() = assertEquals(1, Prices.total([]))\n@Test divides() = assert(1 / Prices.total([]) == 0)\n" +
                    "@Test @Fail fails() = fail(\"on purpose\")\n",
            )
            // Effects a test performs are main's, handled as main's are.
            write("test/files.fk", "import check.*\nimport fckt.io.FileSystem\nimport fckt.io.Paths.*\nimport fckt.io.Files.*\n\n@Test @FileSystem missing() = assert(!path(\"/nonexistent/x\").exists())\n")
            // What the tests share, and a test that's a program of its own, aren't run.
            write("test/support.fk", "two(): Int = 2\n")
            write("test/own.fk", "main() = println(\"own\")\n")
            val roots = listOf(File(dir, "src"), File(dir, "lib"))
            val ml = compileTests(test, "check.Test", "runs.Runner", roots, emptyList(), TEST_IMPORTS, others = listOf(STD))
            assertEquals(
                "files.fk\n  passed missing at line 6\nprices.fk\n  passed sums at line 4\n  FAILED empty: prices.fk line 5: expected:<1> but was:<0>\n" +
                    "  FAILED divides: Division_by_zero\n  FAILED fails: on purpose\n3 failed\n",
                runOcaml(ml),
            )
            assertEquals("files.fk\nprices.fk\n  passed sums at line 4\n0 failed\n", runOcaml(ml, listOf("sums")))
            // The runner's own tests use it.
            write("test/runner.fk", "import check.Test\nimport runs.plan.Case\n@Test casesAreValues() = assert(Case(\"a.fk\", [], \"b\", 1, () -> Unit).line == 1)\n")
            assertContains(runOcaml(compileTests(test, "check.Test", "runs.Runner", roots, emptyList(), TEST_IMPORTS, runnerTests = true, others = listOf(STD)), listOf("casesAreValues")), "  passed casesAreValues")
            File(test, "runner.fk").delete()
            write("test/bad.fk", "import check.Test\n@Test private a() = Unit\n@Test b(x: Int) = Unit\n@Test c = Unit\n")
            write("test/unmarked.fk", "@Test d() = Unit\n")
            write("test/sneaky.fk", "import check.Test\nimport runs.plan.Case\n@Test e() = Unit\n")
            val errors = assertFailsWith<CompileError> { compileTests(test, "check.Test", "runs.Runner", roots, emptyList(), TEST_IMPORTS, others = listOf(STD)) }.errors.map { it.message }
            assertEquals(
                listOf(
                    "a test is run from outside its file, so it can't be private", "a test takes no parameters, as it's run as b()",
                    "a test is a function, run as c(): write c() = ...",
                    "runs runs the tests, so they can't use it: a test uses what it's written with, as check.Test",
                    "a test is marked with check.Test: import check.Test",
                ),
                errors,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A runner, runs.Runner, in lib, as fckt --test calls one, which prints how each test went. */
    private fun writeRunner(write: (String, String) -> Unit) {
        // Its types are its own, as it likes them: fckt makes them only through the runner.
        write("lib/runs/plan/Case.fk", "type Case<@E>(file: String, sections: List<String>, name: String, line: Int, body: @Fail @E () -> Unit)\n")
        write(
            "lib/runs/Runner.fk",
            """
            import runs.plan.Case

            module Runner {
              type Outcome { Passed, Failed }
              test<@E>(file: String, sections: List<String>, name: String, line: Int, body: @Fail @E () -> Unit): Case<@E> = Case(file, sections, name, line, body)
              suite<@E>(file: String, tests: List<Case<@E>>): List<Case<@E>> = tests
              plan<@E>(suites: List<List<Case<@E>>>): List<List<Case<@E>>> = suites
              @Console main<@E>(args: List<String>, plan: List<List<Case<@E>>>): Unit = {
                outcomes = plan.flatMap(suite -> {
                  suite.firstOrNone().map(c -> println(c.file)).getOrElse(Unit)
                  suite.filter(c -> args.isEmpty() || args.contains(c.name)).map(c -> check(c))
                })
                println("${'$'}{outcomes.filter(o -> o == Failed).size} failed")
              }
              @Console check<@E>(c: Case<@E>): Outcome = {
                name = (c.sections + [c.name]).joinToString(".")
                when (attempt { caught(c.body).map(reason -> fail(reason)).getOrElse(Unit) }) {
                  Ok(_) -> { println("  passed ${'$'}name at line ${'$'}{c.line}"); Passed }
                  Error(reason) -> { println("  FAILED ${'$'}name: ${'$'}reason"); Failed }
                }
              }
              private external caught(body: () -> Unit): Option<String> =
                "(fun f -> try f (); None with Failure m -> Some m | e -> Some (Printexc.to_string e)) body"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a test runs in its sections, and inside the fixtures they and it list`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-fixtures").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        try {
            write("lib/check/Test.fk", "annotation type Test\n")
            write("lib/check/Fixtures.fk", "@Propagates @Repeatable annotation type Fixtures(vararg fixtures: Function)\n")
            writeRunner(::write)
            write(
                "test/counts.fk",
                """
                import check.*

                effect Tally {
                  count(): Int
                }

                // A fixture: it runs a test, handling what it does.
                counting<A>(test: @Tally () -> A): A = {
                  var n = 0
                  Tally(count = () -> {
                    n = n + 1
                    n
                  }) { test() }
                }

                @Console noisy<A>(test: () -> A): A = {
                  println("  (noisy)")
                  test()
                }

                @Test plain() = assert(true)

                @Fixtures(counting)
                module Counting {
                  @Test @Tally once() = assertEquals(1, count())

                  @Test @Fixtures(noisy) @Tally twice() = {
                    count()
                    assertEquals(2, count())
                  }

                  module Deeper {
                    @Test @Tally deep() = assertEquals(1, count())
                  }
                }
                """.trimIndent(),
            )
            val test = File(dir, "test")
            val roots = listOf(File(dir, "lib"))
            fun tests() = compileTests(test, "check.Test", "runs.Runner", roots, emptyList(), TEST_IMPORTS, fixtures = "check.Fixtures", others = listOf(STD))
            assertEquals(
                "counts.fk\n  passed plain at line 21\n  passed Counting.once at line 25\n  (noisy)\n  passed Counting.twice at line 27\n  passed Counting.Deeper.deep at line 33\n0 failed\n",
                runOcaml(tests()),
            )
            // Each fixture runs the rest, the section's outermost.
            assertContains(tests(), "counting (fun () -> noisy (fun () -> Counting.twice ()))")
            fun errors() = assertFailsWith<CompileError> { tests() }.errors.joinToString("\n") { it.message.orEmpty() }
            val counts = File(test, "counts.fk").readText()
            // A fixture runs the test: it takes it, and is seen from outside its file.
            File(test, "counts.fk").writeText(counts.replace("@Fixtures(noisy)", "@Fixtures(plain)"))
            assertContains(errors(), "a fixture takes what it runs, as its one parameter: plain<A>(test: () -> A): A")
            File(test, "counts.fk").writeText(counts.replace("@Console noisy", "private @Console noisy"))
            assertContains(errors(), "noisy runs a test from outside its file, so it can't be private")
            File(test, "counts.fk").writeText(counts.replace("@Fixtures(noisy)", "@Fixtures(nothing)"))
            assertContains(errors(), "unknown function 'nothing'")
            File(test, "counts.fk").writeText(counts.replace("module Counting {", "private module Counting {"))
            assertContains(errors(), "a section is run from outside its file, so it can't be private")
            // What a test or fixture does, something it runs in handles.
            File(test, "counts.fk").writeText(counts.replace("@Fixtures(counting)\n", ""))
            assertContains(errors(), "'once' does @Tally, which nothing it runs in handles: list a fixture that does, as @Fixtures(...)")
            File(test, "counts.fk").writeText(counts.replace("@Fixtures(counting)\n", "@Fixtures(noisy)\n"))
            assertContains(errors(), "'deep' does @Tally, which neither the runner nor its fixtures, noisy, handle: list a fixture that does, as @Fixtures(...)")
            File(test, "counts.fk").writeText(counts.replace("@Console noisy", "@Tally noisy").replace("println(\"  (noisy)\")", "count()").replace("@Test plain()", "@Test @Fixtures(noisy) plain()"))
            assertEquals("the fixture noisy does @Tally, which nothing it runs in handles: list a fixture that does before it", errors())
        } finally {
            dir.deleteRecursively()
        }
    }


    @Test
    fun `an annotation that propagates is on what's in the modules it's on`() {
        val analysis = analyse(
            """
            @Propagates annotation type Tag(name: String)
            @Propagates @Repeatable annotation type Tags(name: String)
            annotation type Plain(name: String)

            @Tag("outer") @Tags("outer") @Plain("outer")
            module Outer {
              @Tag("inner") @Tags("inner")
              module Inner {
                f() = 1
                @Tag("own") g() = 2
              }
            }
            """.trimIndent(),
            null,
            emptyList(),
        )
        val outer = analysis.decls.filterIsInstance<Decl.Module>().single()
        val inner = outer.decls.filterIsInstance<Decl.Module>().single()
        fun on(name: String) = effectiveAnnotations(inner.decls.first { it.name.endsWith(name) }, listOf(outer, inner)).joinToString(", ") { a ->
            a.name + "(" + ((a.args.single().value as Meta.Literal).expr as Expr.StringLit).parts.joinToString("") { (it as StringPart.Text).text } + ")"
        }
        // A repeatable one from each, outermost first; another, the nearest's; one that doesn't propagate, nowhere.
        assertEquals("Tags(outer), Tag(inner), Tags(inner)", on("f"))
        assertEquals("Tags(outer), Tags(inner), Tag(own)", on("g"))
    }

    @Test
    fun `a lambda wanted to give Unit drops what its body gives`() {
        val source = """
            import fckt.values.Mutable
            main() = {
              seen: Mutable<List<Int>> = Mutable([])
              add = (n: Int) -> {
                seen.value = seen.value + [n]
                n * 2
              }
              [1, 2, 3].forEach { add(it) }
              [4].forEach { n -> (m: Int) -> m + n }
              [5].forEach { n -> add(n) }
              nothing: () -> Unit = () -> 6
              nothing()
              assertEquals([1, 2, 3, 5], seen.value)
              assertEquals([2, 4], [1, 2].map { it * 2 })
            }
        """.trimIndent()
        assertEquals("", run(source))
    }

    @Test
        fun `a function called by name doesn't use extensions of the same name`() {
        // Parsing's group is a function of its own; Api's Match.group, which calls parse, isn't it, so
        // the modules don't use each other.
        val source = """
            module Parsing {
              private group(): Int = 1
              parse(): Int = group() + 1
            }
            module Api {
              type Match(n: Int)
              Match.group(): Int = Parsing.parse() + this.n
            }
            main() = assertEquals(3, Match(1).group())
        """.trimIndent()
        assertEquals("", run(source.replace("Match(1)", "Api.Match(1)")))
    }

    @Test
        fun `a package's type can hold a type of a package inside it`() {
        val dir = kotlin.io.path.createTempDirectory("fckt").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        try {
            write("shop/items/Item.fk", "type Item(name: String)\n")
            // A private declaration gives the package a signature, which must show Item before Order.
            write("shop/Order.fk", "import shop.items.Item\n\ntype Order(items: List<Item>)\n")
            write("shop/totals.fk", "private secret(): Int = 1\n\ncount(o: Order): Int = o.items.size + secret() - 1\n")
            val main = File(dir, "main.fk")
            main.writeText("import shop.*\nimport shop.items.Item\n\nmain() = assertEquals(2, count(Order([Item(\"a\"), Item(\"b\")])))\n")
            assertEquals("", runOcaml(compile(main, listOf(dir), others = listOf(STD))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
        fun `functions in different files that call each other each read their types with their own file's imports`() {
        val dir = kotlin.io.path.createTempDirectory("fckt").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        try {
            write("shapes/Circle.fk", "type Circle(radius: Int)\n")
            // Each calls the other, and only the second imports what its type names.
            write("walk/ping.fk", "ping(n: Int): Int = if (n == 0) 0 else pong(n - 1).radius\n")
            write("walk/pong.fk", "import shapes.Circle\n\npong(n: Int): Circle = Circle(ping(n))\n")
            val main = File(dir, "main.fk")
            main.writeText("import walk.*\n\nmain() = assertEquals(0, ping(3))\n")
            assertEquals("", runOcaml(compile(main, listOf(dir), others = listOf(STD))))
        } finally {
            dir.deleteRecursively()
        }
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
            fun compileMain(source: String) = main.writeText(source).let { compile(main, listOf(dir, lib), others = listOf(STD)) }
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
            assertContains(errorIn("main() = assertEquals(Some(1), Options.pure(1))"), "unknown name 'Options'; import fckt.monads.Options has one")
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
            assertContains(errorIn("main() = assertEquals(1, Loose.f())"), "Loose.fk must declare Loose, which it's named for: a module, type, spec or effect")
            write("Two.fk", "module Two { f() = 1 }\nmodule Other { g() = 2 }\n")
            assertContains(errorIn("main() = assertEquals(1, Two.f())"), "Other belongs in its own file, Other.fk; or make it private to Two.fk")
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
    fun `examples import what they use, and don't pick up their neighbours`() = File("examples").walk().filter { it.extension == "fk" }.map { file ->
        DynamicTest.dynamicTest(file.path) { assertEquals(example(file.readText()), compile(file, listOf(file.parentFile), others = listOf(STD))) }
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
    }


    @Test
    fun `a module with type parameters is generic in them, as each of its functions is`() {
        assertEquals(
            "",
            run(
                """
                import fckt.monads.Results
                import fckt.monads.Validated
                import fckt.monads.Validation

                parse(s: String): Result<Int, String> = when (s.toIntOrNone()) {
                  Some(n) -> Ok(n)
                  None -> Error("not a number: ${'$'}s")
                }

                positive(n: Int): Validation<Int, Int> = if (n > 0) Valid(n) else Invalid([n])

                main() = {
                  assertEquals(Ok(3), Results { a by parse("1"); b by parse("2"); a + b })
                  // map and product are Monad's, from bind and pure, for any error type.
                  assertEquals(Error("not a number: x"), Results.map(parse("x"), n -> n * 2))
                  assertEquals(Ok((1, 2)), Results.product(parse("1"), parse("2")))
                  assertEquals(Error(15), Results.mapError(parse("y"), e -> e.length))
                  assertEquals(Invalid([-1, -2]), Validated { a by positive(-1); b by positive(-2); a + b })
                }
                """.trimIndent(),
            ),
        )
    }

    /** Compiles [ml] with ocamlc, beside it, with the modules in [dirs]: as kiln compiles a library once. */
    private fun ocamlc(ml: File, vararg dirs: File) {
        val ocamlc = File(File(ocaml!!).parentFile, "ocamlc").path
        val process = ProcessBuilder(listOf(ocamlc, "-c", "-w", "-a", "-I", "+unix") + dirs.flatMap { listOf("-I", it.path) } + ml.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), "ocamlc failed on ${ml.name}:\n$output\n${excerpt(output, ml.readText())}")
    }

    @Test
    fun `a core file open in an editor is checked as part of the core`() {
        val lists = File("src/main/resources/std/fckt/lang/Lists.fk")
        // Not as a program beside the core's own copy of it, whose methods it'd clash with.
        compile(lists.readText(), lists, listOf(lists.parentFile))
        val broken = lists.readText().replace("List<A>.isNotEmpty(): Boolean = !this.isEmpty()", "List<A>.isNotEmpty(): Boolean = \"no\"")
        val error = assertFailsWith<CompileError> { compile(broken, lists, listOf(lists.parentFile)) }
        // Its errors are the file's own, as the editor has it.
        assertEquals(null, error.pos?.file)
        assertContains(error.message!!, "expected Boolean but found String")
    }

    @Test
    fun `what's internal is seen only in its project, not another's compiled with it`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-internal").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        try {
            write("shop/src/shop/Prices.fk", "module Prices {\n  total(items: List<Int>): Int = Rounding.whole(items.sum())\n  internal tax(n: Int): Int = n / 5\n}")
            write("shop/src/shop/Rounding.fk", "internal module Rounding {\n  whole(n: Int): Int = n\n}")
            write("shop/src/main.fk", "import shop.*\nmain() = assertEquals(2, Prices.tax(Rounding.whole(10)))")
            val shop = File(dir, "shop/src")
            // In its own project, it's as any other declaration.
            compile(File(shop, "main.fk"), listOf(shop))
            // As in the project's tests, a root of its own, given as the program's.
            write("shop/test/checks.fk", "import shop.*\nmain() = assertEquals(2, Rounding.whole(Prices.tax(10)))")
            compile(File(dir, "shop/test/checks.fk"), listOf(File(dir, "shop/test"), shop))
            // In another, built with the shop's sources as another project's, it's not there to use.
            write("till/src/main.fk", "import shop.*\nmain() = assertEquals(2, Prices.tax(10))")
            write("till/src/whole.fk", "import shop.*\nmain() = assertEquals(10, Rounding.whole(10))")
            val till = File(dir, "till/src")
            val tax = assertFailsWith<CompileError> { compile(File(till, "main.fk"), listOf(till), others = listOf(shop)) }
            assertContains(tax.message!!, "'tax' is internal to its project")
            val whole = assertFailsWith<CompileError> { compile(File(till, "whole.fk"), listOf(till), others = listOf(shop)) }
            assertContains(whole.message!!, "'Rounding' is internal to its project")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `what a file beside the program declares is the program's, unless it has a main of its own`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-apart").toFile()
        try {
            File(dir, "build.fk").writeText("project: String = \"shop\"\n")
            File(dir, "main.fk").writeText("main() = assertEquals(\"shop\", project)\n")
            compile(File(dir, "main.fk"), listOf(dir))
            File(dir, "build.fk").writeText("project: String = \"shop\"\nmain() = Unit\n")
            assertContains(
                assertFailsWith<CompileError> { compile(File(dir, "main.fk"), listOf(dir)) }.message!!,
                "unknown name 'project'; ${File(dir, "build.fk").path} declares it, but it has a main of its own, so it's a program apart from this one",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a package named as one further out still names that one`() {
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val dir = kotlin.io.path.createTempDirectory("fckt-hidden").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        try {
            write("util/Word.fk", "type Word(text: String)")
            write("util/Words.fk", "module Words {\n  shout(w: Word): String = w.text.uppercase()\n}")
            // app.util, which OCaml would take app's code's util to be.
            write("app/util/Local.fk", "module Local {\n  here: Int = 1\n}")
            write("app/Shouting.fk", "import util.Words\nimport util.Word\nimport app.util.Local\n\nmodule Shouting {\n  loud(text: String): String = Words.shout(Word(text)) + \"\${Local.here}\"\n}")
            write("main.fk", "import app.Shouting\nmain() = assertEquals(\"HI1\", Shouting.loud(\"hi\"))")
            assertEquals("", runOcaml(compile(File(dir, "main.fk"), listOf(dir))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a folder given twice, however it's written, is one folder`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-twice").toFile()
        try {
            File(dir, "src/p").mkdirs()
            File(dir, "src/p/answer.fk").writeText("answer: Int = 42\n")
            File(dir, "src/main.fk").writeText("import p.answer\nmain() = assertEquals(42, answer)\n")
            val src = File(dir, "src")
            compile(File(src, "main.fk"), listOf(src, File(dir, "src/../src"), src.absoluteFile))
            compile(File(src, "main.fk"), listOf(src), others = listOf(File(dir, "src/p/..")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a spec's default names what it does where the spec is, in a module elsewhere that follows it`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-spec-defaults").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        try {
            write("src/testing/Root.fk", "type Root(folder: String)")
            write("src/testing/Configuration.fk", "spec Configuration {\n  roots(): List<Root> = [Root(\"test\")]\n  extra(): List<String> = []\n}")
            write("src/testing/Runs.fk", "module Runs {\n  folders(roots: List<Root>): List<String> = roots.map { it.folder }\n}")
            // Root is beside Configuration, but not imported here, where the default is copied.
            write("src/main.fk", "import testing.Configuration\nimport testing.Runs\nmodule Mine : Configuration {\n  extra(): List<String> = [\"more\"]\n}\nmain() = assertEquals([\"test\", \"more\"], Runs.folders(Mine.roots()) + Mine.extra())")
            val src = File(dir, "src")
            compile(File(src, "main.fk"), listOf(src))
            // What the module itself has sees only what its own file does.
            write("src/main.fk", "import testing.Configuration\nmodule Mine : Configuration {\n  extra(): List<String> = [Root(\"x\").folder]\n}\nmain() = assertEquals([\"x\"], Mine.extra())")
            assertContains(assertFailsWith<CompileError> { compile(File(src, "main.fk"), listOf(src)) }.message!!, "Root")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a library is compiled once, and programs are checked against its interface`() {
        assumeTrue(ocaml != null, "ocaml isn't installed")
        val dir = kotlin.io.path.createTempDirectory("fckt-libraries").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        try {
            val core = File(dir, "core").apply { mkdirs() }
            File(core, "fckt_core.ml").writeText(compileCore())
            ocamlc(File(core, "fckt_core.ml"))
            // The standard library, a library like the others here, compiled with the core.
            val std = File(dir, "lib/std").apply { mkdirs() }
            val stdPackages = File(STD, "fckt").listFiles()!!.filter { it.isDirectory }.map { "fckt.${it.name}" }.sorted()
            File(std, "fckt_std.ml").writeText(compileLibrary(stdPackages, listOf(STD), emptyList(), "Fckt_std", File(std, "interface")))
            ocamlc(File(std, "fckt_std.ml"), core)

            write("units/uk/m4xy/units/Money.fk", "type Money(pence: Int)")
            write("units/uk/m4xy/units/Tagged.fk", "annotation type Tagged(vararg tags: String)")
            write("units/uk/m4xy/units/Shape.fk", "type Shape {\n  Circle(radius: Float),\n  Square(side: Float),\n}")
            write("units/uk/m4xy/units/Ask.fk", "effect Ask {\n  ask(question: String): String\n}")
            // A spec with a default, whose code a program's module that follows it has, and a generic module.
            write("units/uk/m4xy/units/Measure.fk", "spec Measure {\n  type T\n  amount(m: T): Int\n  doubled(m: T): Int = amount(m) * 2\n}")
            write("units/uk/m4xy/units/Boxes.fk", "module Boxes<E> {\n  type M<A> = Result<A, E>\n  ok<A>(x: A): Result<A, E> = Ok(x)\n}")
            // A spec whose modules are values, and a function that takes one.
            write("units/uk/m4xy/units/Pricing.fk", "spec Pricing {\n  label(): String\n}")
            write("units/uk/m4xy/units/Pricings.fk", "module Pricings {\n  labelled(p: Pricing): String = \"[${'$'}{p.label()}]\"\n}")
            write("units/uk/m4xy/units/helpers.fk", "@Ask greeting(): String = \"hello ${'$'}{ask(\"name\")}\"\nexternal double(x: Int): Int = \"x * 2\"")
            write(
                "units/uk/m4xy/units/Moneys.fk",
                """
                import fckt.io.Console

                module Moneys {
                  zero: Money = Money(0)
                  operator Money.plus(other: Money): Money = Money(this.pence + other.pence)
                  Money.pounds(): String = "£${'$'}{this.pence / 100}.${'$'}{pad(this.pence % 100)}"
                  sum(items: List<Money>, start: Money = zero): Money = items.fold(start, (a, b) -> Money(a.pence + b.pence))
                  twice<A>(f: (A) -> A, x: A): A = f(f(x))
                  List<A>.second(): Option<A> = this.drop(1).firstOrNone()
                  @Console report(m: Money, label: String = "total"): Unit = println("${'$'}label: ${'$'}{m.pounds()} (${'$'}m)")
                  private pad(n: Int): String = if (n < 10) "0${'$'}n" else "${'$'}n"
                  internal pence(m: Money): Int = Rounding.whole(m.pence)
                }
                """,
            )
            write("units/uk/m4xy/units/Rounding.fk", "internal module Rounding {\n  whole(n: Int): Int = n\n}")
            val units = File(dir, "lib/units").apply { mkdirs() }
            File(units, "uk_m4xy_units.ml").writeText(
                compileLibrary(listOf("uk.m4xy.units"), listOf(File(dir, "units")), listOf(File(std, "interface")), "Uk_m4xy_units", File(units, "interface")),
            )
            ocamlc(File(units, "uk_m4xy_units.ml"), core, std)
            // The interface is the declarations without their code, or what's private.
            val moneys = File(units, "interface/uk/m4xy/units/Moneys.fki").readText()
            assertContains(moneys, "  sum(items: List<Money>, start: Money = compiled): Money\n")
            assertContains(moneys, "  @Console report(m: Money, label: String = compiled): Unit\n")
            assertFalse("pad" in moneys)
            // A method's receiver's type parameters are the receiver's, as reading it finds them again.
            assertContains(moneys, "  List<A>.second(): Option<A>\n")
            // An operator is still one.
            assertContains(moneys, "  operator Money.plus(other: Money): Money\n")
            // Nor what's internal: no other project sees it.
            assertFalse("pence(" in moneys)
            assertFalse(File(units, "interface/uk/m4xy/units/Rounding.fki").exists())
            assertContains(File(units, "interface/uk/m4xy/units/Tagged.fki").readText(), "annotation type Tagged(vararg tags: String)")
            assertContains(File(units, "interface/uk/m4xy/units/Measure.fki").readText(), "  doubled(m: T): Int = amount(m) * 2\n}")
            assertContains(File(units, "interface/uk/m4xy/units/Boxes.fki").readText(), "module Boxes<E> {\n  type M<A> = Result<A, E>\n  ok<A>(x: A): Result<A, E>\n}")
            assertEquals("Uk_m4xy_units\n", File(units, "interface/unit").readText())

            // A library that uses it, which programs use with it.
            write("money/uk/m4xy/money/Wallet.fk", "import uk.m4xy.units.Money\n\nopaque type Wallet(owner: String, cash: Money)")
            write(
                "money/uk/m4xy/money/Wallets.fk",
                """
                import fckt.effects.Fail
                import uk.m4xy.units.Money
                import uk.m4xy.units.Moneys.*

                module Wallets {
                  open(owner: String): Wallet = Wallet(owner, zero)
                  Wallet.balance: Money = this.cash
                  Wallet.deposit(amount: Money): Wallet = Wallet(this.owner, this.cash + amount)
                  @Fail Wallet.spend(amount: Money): Wallet =
                    if (amount.pence > this.cash.pence) fail("${'$'}{this.owner} can't spend ${'$'}{amount.pounds()}")
                    else Wallet(this.owner, Money(this.cash.pence - amount.pence))
                }
                """,
            )
            write("money/uk/m4xy/money/Ordered.fk", "spec Ordered {\n  type T\n  compare(a: T, b: T): Int\n}")
            write("money/uk/m4xy/money/ByPence.fk", "import uk.m4xy.units.Money\n\nmodule ByPence : Ordered {\n  type T = Money\n  compare(a: Money, b: Money): Int = a.pence - b.pence\n}")
            write("money/uk/m4xy/money/Code.fk", "type Code private(value: String)")
            // A functor whose module has a default, which a program gets without naming it.
            write("money/uk/m4xy/money/Ranked.fk", "module Ranked(O: Ordered = ByPence) {\n  higher(a: O.T, b: O.T): O.T = if (O.compare(a, b) >= 0) a else b\n}")
            // A spec in units, a functor that follows it here, and a program that makes it a value.
            // Its body names what's beside it in its package, wherever it's applied.
            write("money/uk/m4xy/money/Sale.fk", "import uk.m4xy.units.Pricing\n\nmodule Sale(O: Ordered = ByPence) : Pricing {\n  label(): String = Labels.sale\n}")
            write("money/uk/m4xy/money/Labels.fk", "module Labels {\n  sale: String = \"sale\"\n}")
            val money = File(dir, "lib/money").apply { mkdirs() }
            File(money, "uk_m4xy_money.ml").writeText(
                compileLibrary(listOf("uk.m4xy.money"), listOf(File(dir, "money")), listOf(File(std, "interface"), File(units, "interface")), "Uk_m4xy_money", File(money, "interface")),
            )
            ocamlc(File(money, "uk_m4xy_money.ml"), core, std, units)
            assertContains(File(money, "interface/uk/m4xy/money/Ranked.fki").readText(), "module Ranked(O: Ordered = ByPence) {")

            val main = File(dir, "shop/main.fk")
            fun compileMain(source: String) = main.apply { parentFile.mkdirs() }.writeText(source.trimIndent()).let {
                compileSeparately(main, listOf(main.parentFile), listOf(File(std, "interface"), File(units, "interface"), File(money, "interface")))
            }
            val ml = compileMain(
                """
                import fckt.io.Console
                import fckt.effects.Failure.attempt
                import uk.m4xy.units.*
                import uk.m4xy.units.Moneys.*
                import uk.m4xy.money.*
                import uk.m4xy.money.Wallets.*

                @Tagged("units", "pence")
                module Pence : Measure {
                  type T = Money
                  amount(m: Money): Int = m.pence
                }

                @Console main() = {
                  total = sum([Money(150), Money(275)])
                  boxed: Result<Int, String> = Boxes.ok(Pence.doubled(Money(21)))
                  println("${'$'}boxed")
                  report(total)
                  report(twice(m -> Money(m.pence * 2), total), label = "quadrupled")
                  shape: Shape = Circle(2.0)
                  println("${'$'}shape ${'$'}{double(21)} ${'$'}zero")
                  println(Ask(ask = q -> "world (${'$'}q)") { greeting() })
                  w = open("max").deposit(Money(500))
                  // The library fails, and the program handles it: both use the one standard library.
                  when (attempt { w.spend(Money(900)) }) {
                    Ok(_) -> println("spent")
                    Error(reason) -> println("failed: ${'$'}reason")
                  }
                  println(attempt { w.spend(Money(100)).balance.pounds() }.getOrElse("?"))
                  println("${'$'}{ByPence.compare(Money(3), Money(1))}")
                  println("${'$'}{Richest.higher(Money(3), Money(9))}")
                  println(Pricings.labelled(Sale()))
                  assertEquals(Some(2), [1, 2].second())
                }

                module Richest = Ranked()
                """,
            )
            // The core and the standard library are each compiled once, not into the program: fckt.io is the library's.
            assertContains(ml, "module Io = Fckt_std.Fckt.Io")
            assertFalse("module Lang = struct" in ml)
            assertContains(ml, "module Units = Uk_m4xy_units.Uk.M4xy.Units")
            assertFalse("pad" in ml)
            val program = File(main.parentFile, "main.ml").apply { writeText(ml) }
            val process = ProcessBuilder(ocaml, "-I", "+unix", "-I", core.path, "-I", std.path, "-I", units.path, "-I", money.path, "unix.cma",
                File(core, "fckt_core.cmo").path, File(std, "fckt_std.cmo").path, File(units, "uk_m4xy_units.cmo").path, File(money, "uk_m4xy_money.cmo").path, program.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(
                "Ok(42)\ntotal: £4.25 (Money(pence=425))\nquadrupled: £17.00 (Money(pence=1700))\nCircle(radius=2.0) 42 Money(pence=0)\nhello world (name)\n" +
                    "failed: max can't spend £9.00\n£4.00\n2\nMoney(pence=9)\n[sale]\n",
                output,
            )

            fun errorIn(source: String) = assertFailsWith<CompileError> { compileMain(source) }.toString()
            assertContains(errorIn("import uk.m4xy.money.*\nmain() = Code(\"abc\")"), "Code's constructor is private to uk.m4xy.money")
            assertContains(errorIn("import uk.m4xy.money.*\nimport uk.m4xy.units.Money\nmain() = Wallet(\"x\", Money(1))"), "Wallet is opaque")
            // A package is one library's.
            write("shop/uk/m4xy/units/Other.fk", "type Other(x: Int)")
            assertContains(errorIn("import uk.m4xy.units.*\nmain() = Unit"), "package uk.m4xy.units is library Uk_m4xy_units's")

            // What a library shows says its types.
            write("bad/uk/m4xy/bad/Bad.fk", "module Bad {\n  twice(f, x) = f(f(x))\n  version = \"1\"\n  ok(x: Int) = x\n  private fine(x: Int) = x\n}")
            val untyped = assertFailsWith<CompileError> {
                compileLibrary(listOf("uk.m4xy.bad"), listOf(File(dir, "bad")), emptyList(), "Uk_m4xy_bad", File(dir, "lib/bad"))
            }.toString()
            assertContains(untyped, "'twice' is in a library's interface, which programs are checked against, so its parameters need types: f, x")
            assertContains(untyped, "'version' is in a library's interface, which programs are checked against, so it needs a type")
            assertContains(untyped, "'ok' is in a library's interface, which programs are checked against, so it needs a result type")
            assertFalse("fine" in untyped)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a project's symbols are its types, their fields and cases, and what's annotated on them, by full names`() {
        val dir = kotlin.io.path.createTempDirectory("fckt-symbols").toFile()
        fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        try {
            write("shop/Named.fk", "annotation type Named(name: String)")
            write("shop/Tagged.fk", "annotation type Tagged(of: Type, vararg tags: String)")
            write("shop/User.fk", """
                import fckt.collections.Maps.*
                @Tagged(List<Address>, "a", "b\"c") type User(
                  @Named("full_name") name: String,
                  age: Int = 0,
                  var scores: Map<String, Double>,
                  home: Option<Address>,
                )
            """)
            write("shop/Address.fk", "type Address private(line: String)")
            write("shop/Shape.fk", "type Shape<T> { Circle(radius: Double, tag: T), @Named(\"pt\") Point }")
            write("shop/Rounding.fk", "internal module Rounding {\n  internal type Level { Low, High }\n  private type Hidden(n: Int)\n}")
            // Code isn't checked, only what it declares, so it can use what's generated from the symbols.
            write("shop/Generated.fk", "import fckt.text.Format\nmodule Generated {\n  user: Format<User> = UserSerializer\n}")
            // A program apart isn't the project's types.
            write("shop/tool.fk", "type Tool(n: Int)\nmain() = println(Tool(1))")
            val symbols = compileSymbols(emptyList(), listOf(dir), emptyList(), others = listOf(STD))
            val file = { name: String -> File(dir, "shop/$name").path }
            assertEquals(
                """
                fckt-symbols 1
                record shop.Address
                  at 1 1 ${file("Address.fk")}
                  private-constructor
                  field line String
                cases shop.Rounding.Level
                  at 2 12 ${file("Rounding.fk")}
                  internal
                  case Low
                    at 2 25 ${file("Rounding.fk")}
                  case High
                    at 2 30 ${file("Rounding.fk")}
                cases shop.Shape
                  at 1 1 ${file("Shape.fk")}
                  parameter T
                  case Circle
                    at 1 17 ${file("Shape.fk")}
                    field radius Float
                    field tag T
                  case Point
                    at 1 62 ${file("Shape.fk")}
                    annotation shop.Named
                      argument name "pt"
                record shop.User
                  at 2 37 ${file("User.fk")}
                  annotation shop.Tagged
                    argument of type List<shop.Address>
                    argument tags ["a", "b\"c"]
                  field name String
                    annotation shop.Named
                      argument name "full_name"
                  field age Int
                    default
                  field scores fckt.collections.Maps.Map<String, Float>
                    var
                  field home Option<shop.Address>
                """.trimIndent() + "\n",
                symbols,
            )
            val missing = assertFailsWith<CompileError> { compileSymbols(listOf("till"), listOf(dir), emptyList(), others = listOf(STD)) }
            assertContains(missing.message!!, "till isn't a package")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tasks take turns, launched, paused, joined, parked and woken, and sleep while others go on`() {
        val output = run(
            """
            import fckt.concurrent.*
            import fckt.io.Polls
            import fckt.io.Polls.*
            import fckt.time.Duration
            import fckt.values.Mutable

            @Console main() = {
              log = Mutable([])
              said = (line: String) -> { log.value = log.value + [line] }
              println("${'$'}{attempt {
                InTurn.run {
                  a = launch { said("a1"); pause(); said("a2") }
                  b = launch { said("b1"); join(a); said("b after a") }
                  said("main")
                  join(b)
                  said("main after b")
                  42
                }
              }}")
              println("${'$'}{log.value}")
              // A task parks until another wakes it.
              println("${'$'}{attempt {
                InTurn.run {
                  waker = Mutable(None)
                  launch { park { waker.value = Some(it) }; println("woken") }
                  launch { pause(); waker.value.forEach { wake(it) }; println("waking") }
                  "done"
                }
              }}")
              // What nothing will wake fails, rather than waiting for ever.
              println(when (attempt { InTurn.run { park { _ -> Unit } } }) { Ok(_) -> "ended"; Error(reason) -> "stopped: ${'$'}reason" })
              println(when (attempt { InTurn.run { join(launch { park { _ -> Unit } }) } }) { Ok(_) -> "ended"; Error(reason) -> "stopped: ${'$'}reason" })
              // Sleeping, the others go on: the shortest wakes first.
              println("${'$'}{attempt {
                Polls.running {
                  launch { sleep(Duration(60)); println("slow") }
                  launch { sleep(Duration(10)); println("fast") }
                  println("started")
                  "slept"
                }
              }}")
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Ok(42)", "[main, a1, b1, a2, b after a, main after b]",
                "waking", "woken", "Ok(done)",
                "stopped: every task is waiting, for what nothing will bring",
                "stopped: every task is waiting, for what nothing will bring",
                "started", "fast", "slow", "Ok(slept)",
            ).joinToString("\n") + "\n",
            output,
        )
    }

}
