package uk.m4xy.fckt.intellij

import uk.m4xy.fckt.standardLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticsTest {
    /** [source] with each coloured name marked by its role: `Some` becomes `[DATA_CLASS:Some]`. */
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
    fun `a spec and a module that follows it`() {
        val source = """
            spec Monad {
              type M<A>
              bind<A, B>(m: M<A>, f: (A) -> M<B>): M<B>
            }
            module Options : Monad {
              type M<A> = Option<A>
              bind(m, f) = when (m) {
                Some(x) -> f(x)
                None -> None
              }
            }
        """.trimIndent()
        assertEquals(
            """
            [KEYWORD:spec] [INTERFACE:Monad] {
              type [TYPE_ALIAS:M]<[TYPE_PARAMETER:A]>
              [FUNCTION_DECLARATION:bind]<[TYPE_PARAMETER:A], [TYPE_PARAMETER:B]>([PARAMETER:m]: [TYPE_ALIAS:M]<[TYPE_PARAMETER:A]>, [PARAMETER:f]: ([TYPE_PARAMETER:A]) -> [TYPE_ALIAS:M]<[TYPE_PARAMETER:B]>): [TYPE_ALIAS:M]<[TYPE_PARAMETER:B]>
            }
            module [OBJECT:Options] : [INTERFACE:Monad] {
              type [TYPE_ALIAS:M]<[TYPE_PARAMETER:A]> = [INTERFACE:Option]<[TYPE_PARAMETER:A]>
              [FUNCTION_DECLARATION:bind]([PARAMETER:m], [PARAMETER:f]) = when ([PARAMETER:m]) {
                [DATA_CLASS:Some]([PROPERTY:x]) -> [FUNCTION_CALL:f]([PROPERTY:x])
                [DATA_CLASS:None] -> [DATA_CLASS:None]
              }
            }
            """.trimIndent(),
            marked(source),
        )
    }

    @Test
    fun `types, by, named arguments and fields`() {
        val source = """
            type Validation<A, E> { Valid(value: A), Invalid(errors: List<E>) }
            type Ratio(n: Int, d: Int)
            f(s: String): Validation<Int, String> = Validated {
              n by check(s)
              r = Ratio(n = 1, d = 2)
              r.n + n
            }
        """.trimIndent()
        assertEquals(
            """
            type [INTERFACE:Validation]<[TYPE_PARAMETER:A], [TYPE_PARAMETER:E]> { [DATA_CLASS:Valid]([PROPERTY:value]: [TYPE_PARAMETER:A]), [DATA_CLASS:Invalid]([PROPERTY:errors]: [CLASS:List]<[TYPE_PARAMETER:E]>) }
            type [DATA_CLASS:Ratio]([PROPERTY:n]: [CLASS:Int], [PROPERTY:d]: [CLASS:Int])
            [FUNCTION_DECLARATION:f]([PARAMETER:s]: [CLASS:String]): [INTERFACE:Validation]<[CLASS:Int], [CLASS:String]> = [OBJECT:Validated] {
              [LOCAL_VARIABLE:n] [KEYWORD:by] [FUNCTION_CALL:check]([PARAMETER:s])
              [LOCAL_VARIABLE:r] = [DATA_CLASS:Ratio]([NAMED_ARGUMENT:n] = 1, [NAMED_ARGUMENT:d] = 2)
              [LOCAL_VARIABLE:r].[PROPERTY:n] + [LOCAL_VARIABLE:n]
            }
            """.trimIndent(),
            marked(source),
        )
    }

    @Test
    fun `a parenthesis on the next line isn't a call`() {
        val source = "pair<A, B>(ma: M.M<A>, mb: M.M<B>): M.M<(A, B)> = M {\n  a by ma\n  b by mb\n  (a, b)\n}"
        assertEquals(
            "[FUNCTION_DECLARATION:pair]<[TYPE_PARAMETER:A], [TYPE_PARAMETER:B]>([PARAMETER:ma]: [OBJECT:M].[TYPE_ALIAS:M]<[TYPE_PARAMETER:A]>, " +
                "[PARAMETER:mb]: [OBJECT:M].[TYPE_ALIAS:M]<[TYPE_PARAMETER:B]>): [OBJECT:M].[TYPE_ALIAS:M]<([TYPE_PARAMETER:A], [TYPE_PARAMETER:B])> = [OBJECT:M] {\n" +
                "  [LOCAL_VARIABLE:a] [KEYWORD:by] [PARAMETER:ma]\n  [LOCAL_VARIABLE:b] [KEYWORD:by] [PARAMETER:mb]\n  ([LOCAL_VARIABLE:a], [LOCAL_VARIABLE:b])\n}",
            marked(source),
        )
    }

    @Test
    fun `a block's bindings are locals for the lines after them`() {
        val source = """
            user(name: String, age: Int): Validation<User, String> = Validated {
              trimmed = name.trim()
              n by checkName(trimmed)
              adult = age >= 18
              User(n, if (adult) age else 18)
            }
            other(n: Int) = n
        """.trimIndent()
        assertEquals(
            """
            [FUNCTION_DECLARATION:user]([PARAMETER:name]: [CLASS:String], [PARAMETER:age]: [CLASS:Int]): [INTERFACE:Validation]<[CLASS:User], [CLASS:String]> = [OBJECT:Validated] {
              [LOCAL_VARIABLE:trimmed] = [PARAMETER:name].[FUNCTION_CALL:trim]()
              [LOCAL_VARIABLE:n] [KEYWORD:by] [FUNCTION_CALL:checkName]([LOCAL_VARIABLE:trimmed])
              [LOCAL_VARIABLE:adult] = [PARAMETER:age] >= 18
              [CLASS:User]([LOCAL_VARIABLE:n], if ([LOCAL_VARIABLE:adult]) [PARAMETER:age] else 18)
            }
            [FUNCTION_DECLARATION:other]([PARAMETER:n]: [CLASS:Int]) = [PARAMETER:n]
            """.trimIndent(),
            marked(source),
        )
    }

    /** Where the [nth] [word] on [line] of [example] goes: `file:line`, one per target. */
    private fun goto(example: String, line: Int, word: String, nth: Int = 0): List<String> {
        val file = java.io.File("../../examples/$example.fk").canonicalFile
        val text = file.readText()
        val lineStart = text.lines().take(line - 1).sumOf { it.length + 1 }
        val offset = Regex("\\b$word\\b").findAll(text.substring(lineStart, lineStart + text.lines()[line - 1].length)).toList()[nth].range.first + lineStart
        return declarations(text, file)[offset].orEmpty().map { "${it.file?.substringAfterLast('/') ?: "here"}:${it.pos.line}" }
    }

    @Test
    fun `names go to their declarations`() {
        // The checker's choice: total for a list of Ints, and for one of Floats.
        assertEquals(listOf("here:21"), goto("extensions", 42, "total"))
        assertEquals(listOf("here:22"), goto("extensions", 43, "total"))
        // The prelude's externals.
        val fold = standardLibrary("prelude")!!.lines().indexOfFirst { "List<A>.fold" in it } + 1
        assertEquals(listOf("prelude.fk:$fold"), goto("extensions", 21, "fold"))
        // Through modules, each segment to its own: Shapes.Units.twice
        assertEquals(listOf("here:19"), goto("modules", 47, "Shapes", 1))
        assertEquals(listOf("here:27"), goto("modules", 47, "Units"))
        assertEquals(listOf("here:28"), goto("modules", 47, "twice"))
        // A field, and a local where it was bound.
        assertEquals(listOf("here:7"), goto("modules", 43, "num"))
        assertEquals(listOf("here:44"), goto("modules", 45, "r"))
        // In a functor, M.pure is the spec's pure, and M.M<A> the spec's M; A is the function's own.
        assertEquals(listOf("here:6"), goto("monads", 45, "pure"))
        assertEquals(listOf("here:32"), goto("monads", 33, "M", 0))
        assertEquals(listOf("here:5"), goto("monads", 33, "M", 1))
        assertEquals(listOf("here:33"), goto("monads", 33, "A", 1))
        // Cases through their type, in a declaration and in code.
        assertEquals(listOf("here:6"), goto("shared-cases", 9, "A"))
        assertEquals(listOf("here:6"), goto("shared-cases", 35, "A"))
        assertEquals(listOf("here:7"), goto("shared-cases", 35, "Zero"))
        // What's built in is described in builtins.fk.
        val result = standardSource(BUILTINS)!!.lines().indexOfFirst { "type Result" in it } + 1
        assertEquals(listOf("builtins.fk:$result"), goto("stdlib", 9, "Ok"))
        assertEquals(listOf("builtins.fk:5"), goto("stdlib", 6, "Int"))
        // A declaration's own name goes nowhere.
        assertEquals(emptyList(), goto("monads", 33, "map"))
    }

    @Test
    fun `every example and library file scans, colours and resolves without failing`() {
        val dir = java.io.File("../../examples")
        val files = dir.walk().filter { it.extension == "fk" }.toList()
        check(files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            val toks = scan(text)
            // Every character is in exactly one token.
            assertEquals(text.length, toks.sumOf { it.end - it.start }, file.path)
            semantics(text, neighbourRoles(file.parentFile, file))
            declarations(text, file)
        }
        // The standard library's files, which a search goes through too, and the built-ins'.
        for (name in uk.m4xy.fckt.STANDARD_MODULES + "prelude" + "builtins") {
            val text = standardSource("std/$name.fk")!!
            assertEquals(text.length, scan(text).sumOf { it.end - it.start }, name)
            semantics(text)
            declarations(text, null)
        }
    }

    @Test
    fun `annotations`() {
        val source = """
            annotation type Route(path: String, handler: (String) -> String)
            @Route("/a", handler = greet)
            greet(@Pure s: String) = s
        """.trimIndent()
        assertEquals(
            """
            [KEYWORD:annotation] type [ANNOTATION:Route]([PROPERTY:path]: [CLASS:String], [PROPERTY:handler]: ([CLASS:String]) -> [CLASS:String])
            @[ANNOTATION:Route]("/a", [NAMED_ARGUMENT:handler] = greet)
            [FUNCTION_DECLARATION:greet](@[ANNOTATION:Pure] [PARAMETER:s]: [CLASS:String]) = [PARAMETER:s]
            """.trimIndent(),
            marked(source),
        )
    }

    @Test
    fun `var is a keyword in place, and changing a var refers to it`() {
        val source = """
            type Account(var balance: Int)
            count() = {
              var n = 0
              n = n + 1
              n
            }
        """.trimIndent()
        assertEquals(
            """
            type [DATA_CLASS:Account]([KEYWORD:var] [PROPERTY:balance]: [CLASS:Int])
            [FUNCTION_DECLARATION:count]() = {
              [KEYWORD:var] [LOCAL_VARIABLE:n] = 0
              [LOCAL_VARIABLE:n] = [LOCAL_VARIABLE:n] + 1
              [LOCAL_VARIABLE:n]
            }
            """.trimIndent(),
            marked(source),
        )
    }

    @Test
    fun `a type OCaml implements is a class`() {
        assertEquals(
            "[KEYWORD:external] type [CLASS:Array]<[TYPE_PARAMETER:A]> = \"'a array\"",
            marked("external type Array<A> = \"'a array\""),
        )
    }
}
