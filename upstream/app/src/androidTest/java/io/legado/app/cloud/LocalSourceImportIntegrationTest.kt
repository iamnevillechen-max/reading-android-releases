package io.legado.app.cloud

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.utils.GSON
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite semantics, using only a disposable in-memory database and owned fixtures. */
@RunWith(AndroidJUnit4::class)
class LocalSourceImportIntegrationTest {
    private lateinit var db: AppDatabase
    private val sourceId = "https://owned-import.invalid/source"
    private val feedId = "https://owned-import.invalid/feed"

    @Before fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
    }

    @After fun closeDatabase() { if (::db.isInitialized) db.close() }

    private fun fullSource(id: String = sourceId) = BookSource(
        bookSourceUrl = id, bookSourceName = "Owned restored source", bookSourceGroup = "Owned group",
        customOrder = 43, enabled = true, enabledExplore = true, lastUpdateTime = 101,
        searchUrl = "/search?q={{key}}", ruleSearch = SearchRule(bookList = ".owned-book", name = "h2@text", bookUrl = "a@href"),
        ruleContent = ContentRule(content = "#owned-body")
    )

    private fun fullFeed(id: String = feedId) = RssSource(
        sourceUrl = id, sourceName = "Owned restored feed", sourceGroup = "Owned feeds", customOrder = 17,
        enabled = true, lastUpdateTime = 101, sortUrl = "Owned::https://owned-import.invalid/articles",
        ruleArticles = "item", ruleTitle = "title@text", ruleLink = "link@text", ruleContent = "article@html"
    )

    private fun rowId(table: String, column: String, id: String): Long =
        db.openHelper.readableDatabase.query("SELECT rowid FROM $table WHERE $column = ?", arrayOf(id)).use {
            check(it.moveToFirst()); it.getLong(0)
        }

    private fun assertStored(expected: Any, actual: Any?) {
        assertNotNull(actual)
        assertEquals(GSON.toJson(expected), GSON.toJson(actual))
    }

    @Test fun sameIdFullRuleImportPreservesSearchReadingAndSourceState() {
        val incoming = fullSource()
        val thin = incoming.copy(enabled = false, enabledExplore = false, searchUrl = "reader-cloud://source",
            ruleSearch = null, ruleContent = null, bookSourceComment = "Owned archived rules")
        db.bookSourceDao.insert(thin)
        val sourceRowId = rowId("book_sources", "bookSourceUrl", sourceId)
        val candidate = SearchBook(bookUrl = "https://owned-import.invalid/candidate", origin = sourceId,
            originName = "Owned old label", name = "Owned candidate", author = "Owned author", time = 712,
            variable = "{\"owned\":\"search-state\"}", respondTime = 83)
        db.searchBookDao.insert(candidate)
        val book = Book(bookUrl = "https://owned-import.invalid/book", origin = sourceId,
            name = "Owned reading fixture", author = "Owned author", durChapterIndex = 6,
            durChapterPos = 821, durChapterTime = 123456, durChapterTitle = "Owned seventh chapter")
        db.bookDao.insertRaw(book)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://owned-import.invalid/chapter/7",
            title = "Owned seventh chapter", index = 6, variable = "{\"owned\":\"chapter-state\"}")
        db.bookChapterDao.insert(chapter)
        val bookmark = Bookmark(time = 12345, bookName = book.name, bookAuthor = book.author,
            chapterIndex = 6, chapterPos = 810, chapterName = chapter.title, bookText = "Owned excerpt", content = "Owned note")
        db.bookmarkDao.insertRaw(bookmark)
        val history = ReadRecord(deviceId = "owned-device", bookName = book.name, readTime = 4321, lastRead = 6789)
        db.readRecordDao.insert(history)
        val sourceVariable = Cache(key = "sourceVariable_$sourceId", value = "Owned local variable", deadline = 0)
        val cookie = Cookie(url = sourceId, cookie = "owned_fixture=value")
        db.cacheDao.insert(sourceVariable); db.cookieDao.insert(cookie)

        db.bookSourceDao.insert(incoming)

        assertEquals(sourceRowId, rowId("book_sources", "bookSourceUrl", sourceId))
        assertStored(incoming, db.bookSourceDao.getBookSource(sourceId))
        assertStored(candidate, db.searchBookDao.getSearchBook(candidate.bookUrl))
        assertStored(book, db.bookDao.getBook(book.bookUrl))
        assertStored(chapter, db.bookChapterDao.getChapter(book.bookUrl, chapter.index))
        assertStored(bookmark, db.bookmarkDao.getByTime(bookmark.time))
        assertStored(history, db.readRecordDao.all.single())
        assertStored(sourceVariable, db.cacheDao.get(sourceVariable.key))
        assertStored(cookie, db.cookieDao.get(cookie.url))
    }

    @Test fun sameIdFeedImportPreservesArticlesStarsAndReadRecords() {
        val incoming = fullFeed()
        db.rssSourceDao.insert(incoming.copy(enabled = false, ruleArticles = "reader-cloud://source", ruleContent = null))
        val sourceRowId = rowId("rssSources", "sourceUrl", feedId)
        val article = RssArticle(origin = feedId, link = "https://owned-import.invalid/article/1",
            title = "Owned saved article", sort = "Owned category", order = 9, read = true,
            content = "Owned stored article body", variable = "{\"owned\":\"article-state\"}")
        val star = article.toStar().copy(starTime = 9988)
        val record = RssReadRecord(record = article.link, title = article.title, readTime = 1234, read = true)
        db.rssArticleDao.insert(article); db.rssStarDao.insert(star); db.rssReadRecordDao.insertRecord(record)

        db.rssSourceDao.insert(incoming)

        assertEquals(sourceRowId, rowId("rssSources", "sourceUrl", feedId))
        assertStored(incoming, db.rssSourceDao.getByKey(feedId))
        assertStored(article, db.rssArticleDao.get(feedId, article.link))
        assertStored(star, db.rssStarDao.get(feedId, article.link))
        assertStored(record, db.rssReadRecordDao.getRecords().single())
    }

    @Test fun mixedExistingNewAndRepeatedIdsUseIncomingRulesWithoutReplacingIdentity() {
        val original = fullSource().copy(bookSourceName = "Owned original", enabled = false)
        val originalFeed = fullFeed().copy(sourceName = "Owned original feed", enabled = false)
        db.bookSourceDao.insert(original); db.rssSourceDao.insert(originalFeed)
        val sourceRowId = rowId("book_sources", "bookSourceUrl", sourceId)
        val feedRowId = rowId("rssSources", "sourceUrl", feedId)
        val candidate = SearchBook(bookUrl = "https://owned-import.invalid/repeated-candidate", origin = sourceId)
        db.searchBookDao.insert(candidate)
        val last = fullSource().copy(bookSourceName = "Owned last version", enabled = false, customOrder = 91)
        val lastFeed = fullFeed().copy(sourceName = "Owned last feed version", enabled = false, customOrder = 92)
        val newSource = fullSource("https://owned-import.invalid/new-source")
        val newFeed = fullFeed("https://owned-import.invalid/new-feed")

        db.bookSourceDao.insert(fullSource(), newSource, last)
        db.rssSourceDao.insert(fullFeed(), newFeed, lastFeed)

        assertEquals(2, db.bookSourceDao.allCount()); assertEquals(2, db.rssSourceDao.size)
        assertEquals(sourceRowId, rowId("book_sources", "bookSourceUrl", sourceId))
        assertEquals(feedRowId, rowId("rssSources", "sourceUrl", feedId))
        assertStored(last, db.bookSourceDao.getBookSource(sourceId))
        assertStored(lastFeed, db.rssSourceDao.getByKey(feedId))
        assertStored(newSource, db.bookSourceDao.getBookSource(newSource.bookSourceUrl))
        assertStored(newFeed, db.rssSourceDao.getByKey(newFeed.sourceUrl))
        assertFalse(requireNotNull(db.bookSourceDao.getBookSource(sourceId)).enabled)
        assertFalse(requireNotNull(db.rssSourceDao.getByKey(feedId)).enabled)
        assertStored(candidate, db.searchBookDao.getSearchBook(candidate.bookUrl))
    }

    @Test fun bookImportRollsBackPriorUpdatesAndInsertsWhenLaterInsertIsRejected() {
        val original = fullSource().copy(bookSourceName = "Owned original")
        db.bookSourceDao.insert(original)
        val candidate = SearchBook(bookUrl = "https://owned-import.invalid/rollback-candidate", origin = sourceId)
        db.searchBookDao.insert(candidate)
        val sourceRowId = rowId("book_sources", "bookSourceUrl", sourceId)
        // A real SQLite constraint failure after both an UPDATE and a new INSERT, not a fake DAO.
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER owned_book_import_abort BEFORE INSERT ON book_sources
            WHEN NEW.bookSourceName = 'Owned rejected fixture'
            BEGIN SELECT RAISE(ABORT, 'owned_import_rejected'); END""")
        val added = fullSource("https://owned-import.invalid/rollback-added")
        val rejected = fullSource("https://owned-import.invalid/rollback-rejected").copy(bookSourceName = "Owned rejected fixture")

        expectConstraintFailure { db.bookSourceDao.insert(original.copy(bookSourceName = "Owned changed"), added, rejected) }

        assertEquals(1, db.bookSourceDao.allCount())
        assertEquals(sourceRowId, rowId("book_sources", "bookSourceUrl", sourceId))
        assertStored(original, db.bookSourceDao.getBookSource(sourceId))
        assertStored(candidate, db.searchBookDao.getSearchBook(candidate.bookUrl))
        assertNull(db.bookSourceDao.getBookSource(added.bookSourceUrl))
        assertNull(db.bookSourceDao.getBookSource(rejected.bookSourceUrl))
    }

    @Test fun feedImportRollsBackPriorUpdatesAndInsertsWhenLaterInsertIsRejected() {
        val original = fullFeed().copy(sourceName = "Owned original feed")
        db.rssSourceDao.insert(original)
        val article = RssArticle(origin = feedId, link = "https://owned-import.invalid/rollback-article", content = "Owned retained body")
        db.rssArticleDao.insert(article)
        val feedRowId = rowId("rssSources", "sourceUrl", feedId)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER owned_feed_import_abort BEFORE INSERT ON rssSources
            WHEN NEW.sourceName = 'Owned rejected fixture'
            BEGIN SELECT RAISE(ABORT, 'owned_import_rejected'); END""")
        val added = fullFeed("https://owned-import.invalid/rollback-added-feed")
        val rejected = fullFeed("https://owned-import.invalid/rollback-rejected-feed").copy(sourceName = "Owned rejected fixture")

        expectConstraintFailure { db.rssSourceDao.insert(original.copy(sourceName = "Owned changed feed"), added, rejected) }

        assertEquals(1, db.rssSourceDao.size)
        assertEquals(feedRowId, rowId("rssSources", "sourceUrl", feedId))
        assertStored(original, db.rssSourceDao.getByKey(feedId))
        assertStored(article, db.rssArticleDao.get(feedId, article.link))
        assertNull(db.rssSourceDao.getByKey(added.sourceUrl))
        assertNull(db.rssSourceDao.getByKey(rejected.sourceUrl))
    }

    private fun expectConstraintFailure(action: () -> Unit) {
        try {
            action()
            fail("Owned SQLite trigger must reject the final INSERT")
        } catch (error: SQLiteException) {
            assertTrue(error.message.orEmpty().contains("owned_import_rejected"))
        }
    }
}
