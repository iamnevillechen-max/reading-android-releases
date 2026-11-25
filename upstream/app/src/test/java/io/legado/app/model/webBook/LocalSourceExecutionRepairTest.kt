package io.legado.app.model.webBook

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalSourceExecutionRepairTest {
    private val gson = Gson()
    private fun original(): BookSource {
        val asset = listOf(File("src/main/assets/reader-verified-sources.json"),
            File("app/src/main/assets/reader-verified-sources.json")).first { it.isFile }
        return gson.fromJson(asset.readText(), Array<BookSource>::class.java)
            .single { it.bookSourceUrl == LocalSourceExecutionRepair.HEIYAN_SOURCE_ID }
    }

    @Test fun reviewedRuleUsesAnExecutionCloneAndPreservesTheCompleteStoredEntity() {
        val original = original().apply {
            bookSourceName = "Owned custom source name"; bookSourceGroup = "Owned group"
            customOrder = 73; enabled = false; enabledExplore = false; weight = 19
        }
        val before = gson.toJson(original)
        val execution = LocalSourceExecutionRepair.forExecution(original)
        assertNotSame(original, execution); assertNotSame(original.ruleExplore, execution.ruleExplore)
        assertEquals(before, gson.toJson(original))
        val expected = JsonParser.parseString(before).asJsonObject
        expected.add("ruleExplore", gson.toJsonTree(execution.ruleExplore))
        assertEquals(expected, gson.toJsonTree(execution))
        assertEquals(original.bookSourceName, execution.bookSourceName)
        assertEquals(original.bookSourceGroup, execution.bookSourceGroup)
        assertEquals(73, execution.customOrder); assertFalse(execution.enabled); assertFalse(execution.enabledExplore)
        assertSame(original.ruleSearch, execution.ruleSearch); assertSame(original.ruleBookInfo, execution.ruleBookInfo)
        assertSame(original.ruleToc, execution.ruleToc); assertSame(original.ruleContent, execution.ruleContent)
        assertEquals("name", execution.ruleExplore!!.name)
        assertEquals("author", execution.ruleExplore!!.author)
    }

    @Test fun serializedRulePropertyOrderDoesNotChangeTheExactContentMatch() {
        val source = original()
        val json = JsonParser.parseString(gson.toJson(source)).asJsonObject
        val reordered = JsonObject()
        json.getAsJsonObject("ruleExplore").entrySet().toList().reversed().forEach { (key, value) -> reordered.add(key, value) }
        json.add("ruleExplore", reordered)
        val reorderedSource = gson.fromJson(json, BookSource::class.java)
        assertEquals(source.ruleExplore, reorderedSource.ruleExplore)
        assertNotSame(reorderedSource, LocalSourceExecutionRepair.forExecution(reorderedSource))
    }

    @Test fun foreignIdsNontextSourcesAndAnyChangedExploreFieldKeepTheirOwnRules() {
        val source = original()
        val rules = requireNotNull(source.ruleExplore)
        val candidates = listOf(
            source.copy(bookSourceUrl = "https://www.heiyan.com/"),
            source.copy(bookSourceUrl = "https://www.heiyan.com.other.example/#reader-verified-20261026"),
            source.copy(bookSourceType = 1),
            source.copy(ruleExplore = rules.copy(bookList = "@js: customOwnerProgram()")),
            source.copy(ruleExplore = rules.copy(name = "h3@text")),
            source.copy(ruleExplore = rules.copy(author = ".owner-author@text")),
            source.copy(ruleExplore = rules.copy(intro = "Owned custom intro")),
            source.copy(ruleExplore = rules.copy(bookUrl = "a@href")),
            source.copy(ruleExplore = rules.copy(kind = "Owned kind")),
            source.copy(ruleExplore = rules.copy(lastChapter = "Owned latest")),
            source.copy(ruleExplore = rules.copy(updateTime = "Owned update")),
            source.copy(ruleExplore = rules.copy(coverUrl = "Owned cover")),
            source.copy(ruleExplore = rules.copy(wordCount = "Owned count")),
            source.copy(ruleExplore = null)
        )
        for (candidate in candidates) assertSame(candidate, LocalSourceExecutionRepair.forExecution(candidate))
    }

    @Test fun aMutatedExecutionCloneCannotPoisonTheArchiveOrAnotherOperation() {
        val source = original(); val before = gson.toJson(source)
        LocalSourceExecutionRepair.forExecution(source).ruleExplore!!.name = "Owned temporary mutation"
        assertEquals(before, gson.toJson(source))
        assertEquals("name", LocalSourceExecutionRepair.forExecution(source).ruleExplore!!.name)
    }
}
