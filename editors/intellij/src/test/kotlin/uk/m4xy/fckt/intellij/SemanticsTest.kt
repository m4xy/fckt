package uk.m4xy.fckt.intellij

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

    @Test
    fun `every example scans and colours without failing`() {
        val dir = java.io.File("../../examples")
        val files = dir.walk().filter { it.extension == "fk" }.toList()
        check(files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            val toks = scan(text)
            // Every character is in exactly one token.
            assertEquals(text.length, toks.sumOf { it.end - it.start }, file.path)
            semantics(text, neighbourRoles(file.parentFile, file))
        }
    }
}
