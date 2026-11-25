package io.legado.app.model.aggregation

import org.junit.Assert.*
import org.junit.Test

class ExploreLiteralRulesTest {
    @Test fun literalMenuKeepsTheEntireUrlAfterItsFirstSeparator() {
        val routes = ExploreLiteralRules.parse("都市::https://books.example.org/a::b&&玄幻::/fantasy\n排行::/rank")
        assertEquals(listOf("都市", "玄幻", "排行"), routes.map { it.title })
        assertEquals("https://books.example.org/a::b", routes.first().url)
    }
    @Test fun jsonMenusRetainEveryConfiguredRoute() {
        val routes = ExploreLiteralRules.parse("""[{"title":"Owned one","url":"/one"},{"title":"Owned two","url":"/two"}]""")
        assertEquals(listOf("/one", "/two"), routes.map { it.url })
    }
    @Test fun blankEntriesDoNotCreateEmptyCategoriesAndLabelsWithoutLinksStayLabels() {
        val routes = ExploreLiteralRules.parse("\n&& Owned heading &&\nOwned route::/route\n")
        assertEquals(2, routes.size); assertNull(routes.first().url)
        assertTrue(ExploreLiteralRules.parse("   ").isEmpty())
    }
    @Test fun sourceJavascriptCannotAccidentallyEnterTheLiteralPath() {
        for (rule in listOf("@js:anything()", "  <JS>anything()</JS>")) {
            assertTrue(ExploreLiteralRules.dynamic(rule))
            assertTrue(runCatching { ExploreLiteralRules.parse(rule) }.isFailure)
        }
    }
    @Test fun largeLiteralMenusAreNotSilentlyReducedToTheFirstScreen() {
        val routes = ExploreLiteralRules.parse((0 until 1000).joinToString("&&") { "Owned $it::/route/$it" })
        assertEquals(1000, routes.size); assertEquals("/route/999", routes.last().url)
    }
}
