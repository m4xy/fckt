package uk.m4xy.fckt.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What needs a running IDE: names resolve to their declarations, and Find Usages finds them. */
class IdeTest : BasePlatformTestCase() {
    private val source = """
        type Shape { Circle(radius: Float), Square(side: Float) }
        area(s: Shape): Float = when (s) {
          Circle(r) -> 3.0 * r * r
          Square(side) -> side * side
        }
        main() = {
          c = Circle(1.0)
          assertEquals(3.0, area(c))
          assertEquals(4.0, area(Square(2.0)))
        }
    """.trimIndent()

    private fun at(word: String, nth: Int = 0): Int = Regex("\\b$word\\b").findAll(source).toList()[nth].range.first

    fun `test a use resolves to its declaration`() {
        myFixture.configureByText("shapes.fk", source)
        val use = myFixture.file.findElementAt(at("area", 1))!!.parent as FcktName
        val declaration = use.reference.resolve() as FcktName
        assertEquals(at("area", 0), declaration.textRange.startOffset)
        // A built-in, in the built-ins' description.
        val float = myFixture.file.findElementAt(at("Float"))!!.parent as FcktName
        assertEquals("builtins.fk", float.reference.resolve()!!.containingFile.name)
    }

    fun `test find usages finds every use`() {
        myFixture.configureByText("shapes.fk", source)
        val declaration = myFixture.file.findElementAt(at("area", 0))!!.parent as FcktName
        val usages = myFixture.findUsages(declaration).map { it.element!!.textRange.startOffset }.sorted()
        assertEquals(listOf(at("area", 1), at("area", 2)), usages)
        // Of a local, only its own uses.
        val local = myFixture.file.findElementAt(at("c", 0))!!.parent as FcktName
        assertEquals(listOf(at("c", 1)), myFixture.findUsages(local).map { it.element!!.textRange.startOffset })
        // Of a case, its pattern and its constructions.
        val square = myFixture.file.findElementAt(at("Square", 0))!!.parent as FcktName
        assertEquals(listOf(at("Square", 1), at("Square", 2)), myFixture.findUsages(square).map { it.element!!.textRange.startOffset }.sorted())
    }

    fun `test a case goes to the one of the type it's matched against`() {
        val text = """
            type NotTheAlphabet { A, B }
            type Alphabet { A, B }
            magic(a: Alphabet) = when (a) {
              A -> "d"
            }
            other(n: NotTheAlphabet) = when (n) {
              A -> "e"
            }
        """.trimIndent()
        myFixture.configureByText("letters.fk", text)
        fun resolve(nth: Int) = ((myFixture.file.findElementAt(Regex("\\bA\\b").findAll(text).toList()[nth].range.first)!!.parent as FcktName)
            .reference as FcktReference).multiResolve(false).map { it.element!!.textRange.startOffset }
        val notTheAlphabet = text.indexOf("{ A") + 2
        val alphabet = text.indexOf("{ A", text.indexOf("type Alphabet")) + 2
        assertEquals(listOf(alphabet), resolve(2))
        assertEquals(listOf(notTheAlphabet), resolve(3))
    }
}
