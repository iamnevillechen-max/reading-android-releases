package io.legado.app.multisource

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.reflect.TypeToken
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real bundled asset, real additive importer, self-written reading data, no network. */
@RunWith(AndroidJUnit4::class)
class VerifiedSourceBootstrapTest {
    @Test(timeout = 20_000)
    fun bundledRulesAddOnceWithoutOverwritingCanonicalOrEditedRulesAndProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "io.multiread.reader.debug" &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated emulator installation required" }
        check(appDb.bookSourceDao.allPart.isEmpty() && appDb.bookDao.allBookCount == 0 &&
            appDb.readRecordDao.all.isEmpty() && ReadBook.book == null && ReadBook.callBack == null &&
            !BaseReadAloudService.isRun) { "Empty idle source/book fixture required" }
        val sources: List<BookSource> = context.assets.open("reader-verified-sources.json")
            .bufferedReader().use { GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type) }
        assertTrue(sources.size in 14..32)
        assertEquals(sources.size, sources.map { it.bookSourceUrl }.toSet().size)
        assertTrue(sources.all { "/#reader-verified-" in it.bookSourceUrl })
        val canonicalIds = sources.map { it.bookSourceUrl.substringBefore("/#reader-verified-") }
        assertEquals(sources.size, canonicalIds.toSet().size)
        val preferences = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val hadVersion = preferences.contains(VerifiedSourceBootstrap.VERSION_KEY)
        val oldVersion = preferences.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0)
        val token = UUID.randomUUID().toString()
        val base = "https://owned-bootstrap-fixture.invalid/$token"
        val originals = canonicalIds.mapIndexed { index, id ->
            BookSource(bookSourceUrl = id, bookSourceName = "自写原规则 $index $token",
                bookSourceGroup = "自写保留组-$index", customOrder = 100_000 + index,
                enabled = index % 2 == 0, enabledExplore = index % 3 == 0,
                searchUrl = "$base/search/$index?key={{key}}",
                ruleSearch = SearchRule(bookList = ".owned-$index", name = ".title@text",
                    author = ".author@text", bookUrl = "a@href"),
                ruleToc = TocRule(chapterList = ".owned-toc-$index a", chapterName = "text", chapterUrl = "href"),
                ruleContent = ContentRule(content = "#owned-original-$index"))
        }
        val book = Book(bookUrl = "$base/book", tocUrl = "$base/toc",
            origin = originals.first().bookSourceUrl, originName = originals.first().bookSourceName,
            name = "自写自动添加保留测试 $token", author = "自写测试作者", canUpdate = false,
            totalChapterNum = 15, durChapterIndex = 7, durChapterPos = 321,
            durChapterTime = 1_700_000_000_123L)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "$base/chapter/7", baseUrl = book.bookUrl,
            index = 7, title = "第八章 自写保留进度测试")
        val record = ReadRecord(deviceId = "owned-bootstrap-$token", bookName = book.name,
            readTime = 456_789L, lastRead = 1_700_000_000_123L)
        var ownsFixtures = false
        try {
            check(preferences.edit().remove(VerifiedSourceBootstrap.VERSION_KEY).commit())
            ownsFixtures = true
            appDb.bookSourceDao.insert(*originals.toTypedArray())
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(chapter)
            appDb.readRecordDao.insert(record)
            val beforeSources = originals.associate { it.bookSourceUrl to row("book_sources", "bookSourceUrl", it.bookSourceUrl) }
            val beforeBook = row("books", "bookUrl", book.bookUrl)
            val beforeChapter = row("chapters", "url", chapter.url)
            val beforeHistory = appDb.readRecordDao.all.toList()

            assertTrue(VerifiedSourceBootstrap.ensureSeeded())
            assertEquals(VerifiedSourceBootstrap.PACK_VERSION, preferences.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0))
            assertEquals(sources.size * 2, appDb.bookSourceDao.allPart.size)
            sources.forEach { assertNotNull(appDb.bookSourceDao.getBookSource(it.bookSourceUrl)) }
            originals.forEach {
                assertEquals("Every old field, including sort order, must survive automatic addition",
                    beforeSources[it.bookSourceUrl], row("book_sources", "bookSourceUrl", it.bookSourceUrl))
            }

            val edited = appDb.bookSourceDao.getBookSource(sources.first().bookSourceUrl)!!.apply {
                bookSourceName = "用户修改后的独立规则 $token"
                bookSourceGroup = "用户保留分组"
                enabled = false
                customOrder = 654_321
                ruleContent = ContentRule(content = "#owned-user-edited")
            }
            appDb.bookSourceDao.update(edited)
            val editedBefore = row("book_sources", "bookSourceUrl", edited.bookSourceUrl)
            // Simulate an interrupted earlier bundle application: a missing last ID must
            // be added while a user's existing edited/disabled first ID remains untouched.
            appDb.bookSourceDao.delete(sources.last().bookSourceUrl)
            check(preferences.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION - 1).commit())
            assertTrue(VerifiedSourceBootstrap.ensureSeeded())
            assertEquals(sources.size * 2, appDb.bookSourceDao.allPart.size)
            assertEquals(editedBefore, row("book_sources", "bookSourceUrl", edited.bookSourceUrl))
            val afterRetry = (originals + sources).associate { it.bookSourceUrl to row("book_sources", "bookSourceUrl", it.bookSourceUrl) }
            assertTrue(VerifiedSourceBootstrap.ensureSeeded())
            assertEquals(afterRetry, (originals + sources).associate { it.bookSourceUrl to row("book_sources", "bookSourceUrl", it.bookSourceUrl) })
            assertEquals(sources.size * 2, appDb.bookSourceDao.allPart.size)
            originals.forEach { assertEquals(beforeSources[it.bookSourceUrl], row("book_sources", "bookSourceUrl", it.bookSourceUrl)) }
            assertEquals(beforeBook, row("books", "bookUrl", book.bookUrl))
            assertEquals(beforeChapter, row("chapters", "url", chapter.url))
            assertEquals(beforeHistory, appDb.readRecordDao.all)
        } finally {
            if (ownsFixtures) appDb.runInTransaction {
                appDb.readRecordDao.delete(record)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookDao.delete(book)
                (originals + sources).forEach { appDb.bookSourceDao.delete(it.bookSourceUrl) }
            }
            check(preferences.edit().apply {
                if (hadVersion) putInt(VerifiedSourceBootstrap.VERSION_KEY, oldVersion)
                else remove(VerifiedSourceBootstrap.VERSION_KEY)
            }.commit())
        }
    }

    private fun row(table: String, keyColumn: String, key: String): Map<String, String?> =
        appDb.openHelper.readableDatabase.query("select * from $table where $keyColumn = ?", arrayOf<Any?>(key)).use { cursor ->
            check(cursor.moveToFirst())
            val result = linkedMapOf<String, String?>()
            cursor.columnNames.forEachIndexed { index, name ->
                result[name] = if (cursor.isNull(index)) null else cursor.getString(index)
            }
            check(!cursor.moveToNext())
            result
        }
}
