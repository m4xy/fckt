package uk.m4xy.fckt.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunningTest {
    private val source = """
        import uk.m4xy.fktests.api.Test

        main() = Unit

        @Test sums() = assertEquals(2, 1 + 1)

        @Test
        @Network fetches() = Unit

        helper() = 1

        mainly = "not a program"
    """.trimIndent()

    private fun at(word: String) = runnableAt(source, Regex("\\b$word\\b").find(source)!!.range.first)

    @Test
    fun `main runs the program and a test runs itself`() {
        assertEquals(Triple("main", source.indexOf("main()"), false), at("main"))
        assertEquals(Triple("sums", source.indexOf("sums()"), true), at("sums"))
        assertEquals(Triple("fetches", source.indexOf("fetches()"), true), at("fetches"))
        assertNull(at("helper"))
        assertNull(at("mainly"))
    }

    @Test
    fun `what kiln test prints is what the tests did`() {
        assertEquals(KilnTestEvent.Suite("prices.fk"), kilnTestEvent("  prices.fk\n"))
        assertEquals(KilnTestEvent.Passed("sums"), kilnTestEvent("    passed sums"))
        assertEquals(KilnTestEvent.Failed("rounds", "prices.fk line 4: expected 2"), kilnTestEvent("    FAILED rounds: prices.fk line 4: expected 2"))
        assertEquals(KilnTestEvent.Done, kilnTestEvent("  3 passed, 1 failed"))
        assertNull(kilnTestEvent("> Task :test"))
    }
}
