package io.legado.app.multisource

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.reflect.TypeToken
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the real importer with the shipped identifiers; no source request or book text. */
@RunWith(AndroidJUnit4::class)
class VerifiedSourceImportTest {
    @Test(timeout = 20_000)
    fun safeThirteenSourceImportPreservesCanonicalRulesAndReadingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "io.multiread.reader.debug" &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated emulator installation required" }
        check(appDb.bookSourceDao.allPart.isEmpty() && appDb.bookDao.allBookCount == 0 &&
            appDb.readRecordDao.all.isEmpty() && ReadBook.book == null &&
            ReadBook.callBack == null && !BaseReadAloudService.isRun) {
            "Run this storage-only fixture in an empty, idle source/book installation"
        }
        val safeSources: List<BookSource> = InstrumentationRegistry.getInstrumentation().context.assets
            .open("verified-combined-sources.json").bufferedReader().use {
                GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type)
            }
        assertEquals(13, safeSources.size)
        assertEquals(13, safeSources.map { it.bookSourceUrl }.toSet().size)
        assertTrue(safeSources.all { "/#reader-verified-" in it.bookSourceUrl })
        val canonicalIds = safeSources.map { it.bookSourceUrl.substringBefore("/#reader-verified-") }
        assertEquals(13, canonicalIds.toSet().size)
        assertTrue(canonicalIds.none { id -> safeSources.any { it.bookSourceUrl == id } })

        val token = UUID.randomUUID().toString()
        val fixtureBase = "https://reader-import-fixture.invalid/$token"
        val originals = canonicalIds.mapIndexed { index, id ->
            BookSource(bookSourceUrl = id, bookSourceName = "自写原规则 $index $token",
                bookSourceGroup = "自写保留组-$index", customOrder = 100_000 + index,
                enabled = index % 2 == 0, enabledExplore = index % 3 == 0,
                searchUrl = "$fixtureBase/search/$index?key={{key}}",
                ruleSearch = SearchRule(bookList = ".owned-$index", name = ".title@text",
                    author = ".author@text", bookUrl = "a@href"),
                ruleToc = TocRule(chapterList = ".owned-toc-$index a", chapterName = "text", chapterUrl = "href"),
                ruleContent = ContentRule(content = "#owned-original-$index"))
        }
        val book = Book(bookUrl = "$fixtureBase/book", tocUrl = "$fixtureBase/toc",
            origin = originals.first().bookSourceUrl, originName = originals.first().bookSourceName,
            name = "自写导入保留测试 $token", author = "自写测试作者", canUpdate = false,
            totalChapterNum = 15, durChapterIndex = 7, durChapterPos = 321,
            durChapterTime = 1_700_000_000_123L)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "$fixtureBase/chapter/7",
            baseUrl = book.bookUrl, index = 7, title = "第八章 自写保留进度测试")
        val record = ReadRecord(deviceId = "owned-source-import-$token", bookName = book.name,
            readTime = 456_789L, lastRead = 1_700_000_000_123L)
        var ownsFixtures = false
        try {
            ownsFixtures = true // Initial guards ensure every inserted identifier belongs to this case.
            appDb.bookSourceDao.insert(*originals.toTypedArray())
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(chapter)
            appDb.readRecordDao.insert(record)
            val beforeSources = originals.associate { source ->
                source.bookSourceUrl to persistedRow("book_sources", "bookSourceUrl", source.bookSourceUrl,
                    setOf("customOrder"))
            }
            val beforeBook = persistedRow("books", "bookUrl", book.bookUrl)
            val beforeChapter = persistedRow("chapters", "url", chapter.url)
            val beforeHistory = appDb.readRecordDao.all.toList()

            // This is the same 18+ filter and insertion path used by normal source import.
            // Large original sort numbers force its asynchronous adjustment to run as well.
            SourceHelp.insertBookSource(*safeSources.toTypedArray())
            withTimeout(5_000) {
                while (appDb.bookSourceDao.hasDuplicateOrder || appDb.bookSourceDao.maxOrder > 99_999 ||
                    appDb.bookSourceDao.minOrder < -99_999) delay(25)
            }
            val stableOrder = appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder }
            delay(75)
            assertEquals("The import's asynchronous sorting must have settled", stableOrder,
                appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder })
            assertEquals(26, appDb.bookSourceDao.allPart.size)
            safeSources.forEach { incoming ->
                val retained = appDb.bookSourceDao.getBookSource(incoming.bookSourceUrl)
                assertNotNull("Every safe identifier must survive the real import filter", retained)
                assertEquals(incoming.bookSourceName, retained!!.bookSourceName)
            }
            originals.forEach { original ->
                assertEquals("Import must preserve every canonical field except normal sorting",
                    beforeSources[original.bookSourceUrl],
                    persistedRow("book_sources", "bookSourceUrl", original.bookSourceUrl, setOf("customOrder")))
            }
            assertEquals(beforeBook, persistedRow("books", "bookUrl", book.bookUrl))
            assertEquals(beforeChapter, persistedRow("chapters", "url", chapter.url))
            assertEquals(beforeHistory, appDb.readRecordDao.all)
            val retainedBook = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(originals.first().bookSourceUrl, retainedBook.origin)
            assertEquals(7, retainedBook.durChapterIndex)
            assertEquals(321, retainedBook.durChapterPos)
        } finally {
            if (ownsFixtures) appDb.runInTransaction {
                appDb.readRecordDao.delete(record)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookDao.delete(book)
                (originals + safeSources).forEach { appDb.bookSourceDao.delete(it.bookSourceUrl) }
            }
        }
    }

    private fun persistedRow(table: String, keyColumn: String, key: String,
        omit: Set<String> = emptySet()): Map<String, String?> =
        appDb.openHelper.readableDatabase.query("select * from $table where $keyColumn = ?", arrayOf<Any?>(key)).use { cursor ->
            check(cursor.moveToFirst()) { "Owned fixture row is missing" }
            val row = linkedMapOf<String, String?>()
            cursor.columnNames.forEachIndexed { index, name ->
                if (name !in omit) row[name] = if (cursor.isNull(index)) null else cursor.getString(index)
            }
            check(!cursor.moveToNext()) { "Owned fixture key must identify one row" }
            row
        }
}
