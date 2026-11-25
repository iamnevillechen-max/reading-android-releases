package io.legado.app.model.webBook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory, independently authored HTML through the actual native rule engine. No HTTP. */
@RunWith(AndroidJUnit4::class)
class LocalSourceExecutionRepairNativeTest {
    private fun original(): BookSource = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("reader-verified-sources.json").bufferedReader().use {
            GSON.fromJson(it, Array<BookSource>::class.java)
                .single { source -> source.bookSourceUrl == LocalSourceExecutionRepair.HEIYAN_SOURCE_ID }
        }

    private val html = """
        <dl><dt><a href="/book/101"><img src="/cover.jpg"/>" width="120" height="168" alt="Owned title"/&gt;</a></dt>
        <dd><h3><a href="/book/101">Owned title</a></h3>
        <a href="/people/501">Owned author</a><div class="flex"><a href="/all/1">Owned kind</a></div></dd></dl>
        <li><a href="/book/101">Owned title</a></li>
        <li><a href="/book/102">Owned ranking A</a><a href="/book/103">Owned ranking B</a>
        <a href="/people/999">Shared author must not leak</a></li>
        <a href="/book/104">Owned standalone</a>
        <li><a href="/book/105">Owned authorless</a></li>
    """.trimIndent()

    @Test fun nativeExplorePrefersTheRealTitleAndItsAuthorAndKeepsOriginalSourceBytes() = runBlocking {
        val source = original().apply { bookSourceName = "Owned local source"; bookSourceGroup = "Owned group"; customOrder = 37 }
        val before = GSON.toJson(source)
        val execution = LocalSourceExecutionRepair.forExecution(source)
        val base = "https://www.heiyan.com/?reader_query=Owned%20title"
        val old = AnalyzeRule(RuleData(), source).setContent(html).setBaseUrl(base)
            .getElements(requireNotNull(source.ruleExplore?.bookList))
        val previousName = AnalyzeRule(RuleData(), source).setContent(old.first()).getString("text")
        assertTrue("The fixture must reproduce the old malformed-poster preference", previousName.contains("width="))
        val repairedRows = AnalyzeRule(RuleData(), execution).setContent(html).setBaseUrl(base)
            .getElements(requireNotNull(execution.ruleExplore?.bookList))
        assertEquals("The semantic card selector must return one matching Rhino row", 1, repairedRows.size)
        val row = AnalyzeRule(RuleData(), execution).setContent(repairedRows.single())
        assertEquals("Rhino rows do not interpret document JSONPath keys", "", row.getString("$.name"))
        assertEquals("Owned title", row.getString(requireNotNull(execution.ruleExplore?.name)))
        assertEquals("Owned author", row.getString(requireNotNull(execution.ruleExplore?.author)))
        val ruleData = RuleData()
        val address = AnalyzeUrl(mUrl = base, baseUrl = source.bookSourceUrl, source = execution, ruleData = ruleData)
        val books = BookList.analyzeBookList(execution, ruleData, address, base, html, isSearch = false)
        val book = books.single()
        assertEquals("Owned title", book.name); assertEquals("Owned author", book.author)
        assertEquals("https://www.heiyan.com/book/101", book.bookUrl)
        assertEquals(source.bookSourceUrl, book.origin); assertEquals("Owned local source", book.originName)
        assertFalse(book.name.contains("width=")); assertFalse(book.author.contains("alt="))
        assertEquals(before, GSON.toJson(source)); assertEquals("Owned group", execution.bookSourceGroup)
        assertEquals(37, execution.customOrder)
    }

    @Test fun nativeExploreDoesNotAssignOneRankingAuthorToDifferentBooks() = runBlocking {
        val source = LocalSourceExecutionRepair.forExecution(original())
        val base = "https://www.heiyan.com/"
        val data = RuleData()
        val address = AnalyzeUrl(mUrl = base, baseUrl = source.bookSourceUrl, source = source, ruleData = data)
        val books = BookList.analyzeBookList(source, data, address, base, html, isSearch = false)
        assertEquals(5, books.size)
        assertEquals(listOf("Owned title", "Owned ranking A", "Owned ranking B", "Owned standalone", "Owned authorless"), books.map { it.name })
        assertEquals(listOf("Owned author", "", "", "", ""), books.map { it.author })
    }
}
