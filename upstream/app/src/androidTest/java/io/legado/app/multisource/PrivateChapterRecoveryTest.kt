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
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in runtime case only. No user's book metadata or text belongs in an APK fixture. */
@RunWith(AndroidJUnit4::class)
class PrivateChapterRecoveryTest {
    @Test fun missingChapterIsRecoveredAndRenderedWithoutManualSourceSelection() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("privateChapterCase")
        assumeTrue("A private runtime case is required", path != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "io.multiread.reader.debug")
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 &&
            ReadBook.book == null && ReadBook.callBack == null && !BaseReadAloudService.isRun &&
            ReadBook.preDownloadTask?.isActive != true)
        val input = File(path!!)
        check(input.canonicalPath.startsWith("/data/local/tmp/") && input.length() in 1..3_000_000)
        val json = input.reader().use { GSON.fromJson(it, com.google.gson.JsonObject::class.java) }
        val targetIndex = json["targetIndex"].asInt
        val book = GSON.fromJson(json["book"], Book::class.java).copy(durChapterIndex = targetIndex, durChapterPos = 0)
        val chapters: List<BookChapter> = GSON.fromJson(json["chapters"], object : TypeToken<List<BookChapter>>() {}.type)
        val chapter = chapters.single { it.index == targetIndex }
        val expectedSource = json["expectedSourceId"].asString
        val expectedHash = json["expectedNormalizedBodySha256"].asString
        val primary = BookSource(bookSourceUrl = book.origin, bookSourceName = "Unavailable original", enabledExplore = false)
        val token = System.nanoTime()
        val decoys = (0 until 512).map { i -> BookSource(
            bookSourceUrl = "https://owned-discovery-$token.invalid/$i", bookSourceName = "Owned unrelated source $i",
            customOrder = 26 + i, weight = 1, enabledExplore = false) }
        val pack = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val oldPack = pack.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0)
        val hadPack = pack.contains(VerifiedSourceBootstrap.VERSION_KEY)
        val oldEnabled = MultiSourceManager.enabled
        val oldLimit = MultiSourceManager.candidateLimit
        val oldRecord = AppConfig.enableReadRecord
        val oldPreload = AppConfig.preDownloadNum
        val oldSource = ReadBook.bookSource
        val oldSize = ReadBook.chapterSize
        val oldSimulatedSize = ReadBook.simulatedChapterSize
        val oldIndex = ReadBook.durChapterIndex
        val oldPosition = ReadBook.durChapterPos
        val oldIsLocal = ReadBook.isLocalBook
        val oldInBookshelf = ReadBook.inBookshelf
        val oldTask = ReadBook.preDownloadTask
        val oldDownloaded = ReadBook.downloadedChapters.toSet()
        val oldFailed = ReadBook.downloadFailChapters.toMap()
        val oldRecords = appDb.readRecordDao.all
        val bundled: List<BookSource> = context.assets.open("reader-verified-sources.json").bufferedReader().use {
            GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type)
        }
        val ownedSourceIds = (bundled + primary + decoys).mapTo(hashSetOf()) { it.bookSourceUrl }
        val output = File(context.filesDir, "private-chapter-recovery").apply { mkdirs() }
        var owned = false
        try {
            clearOwnedMergeFiles(context, book, chapters, ownedSourceIds)
            check(pack.edit().remove(VerifiedSourceBootstrap.VERSION_KEY).commit())
            MultiSourceManager.enabled = true
            MultiSourceManager.candidateLimit = 3
            AppConfig.enableReadRecord = false
            AppConfig.preDownloadNum = 0
            appDb.runInTransaction {
                appDb.bookSourceDao.insert(primary)
                decoys.chunked(128).forEach { appDb.bookSourceDao.insert(*it.toTypedArray()) }
                appDb.bookDao.insert(book)
                chapters.chunked(128).forEach { appDb.bookChapterDao.insert(*it.toTypedArray()) }
            }
            owned = true
            withContext(Dispatchers.Main) {
                ChapterProvider.upViewSize(1080, 1920)
                ReadBook.resetData(book)
                ReadBook.bookSource = primary
                ReadBook.inBookshelf = true
            }
            val started = System.nanoTime()
            // The original request is already known to have failed. This is the same
            // production rescue entry used after that failure, with no manual match inserted.
            val rescued = withTimeout(40_000) { MultiSourceManager.enrich(primary, book, chapter, "") }
            output.resolve("actual-body.txt").writeText(rescued) // Private emulator diagnostic only.
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val hash = sha(rescued.filterNot(Char::isWhitespace))
            val saved = File(context.filesDir, "multisource-v1/${sha(book.bookUrl + "\n" + chapter.url)}.json")
                .takeIf { it.isFile }?.reader()?.use { GSON.fromJson(it, com.google.gson.JsonObject::class.java) }
            val used = saved?.getAsJsonObject("result")?.getAsJsonArray("usedSourceIds")?.map { it.asString }.orEmpty()
            output.resolve("results.json").writeText(GSON.toJson(mapOf(
                "automaticBodyCharacters" to rescued.length, "normalizedBodySha256" to hash,
                "expectedBodyHashMatches" to (expectedHash == hash), "expectedSourceUsed" to (expectedSource in used),
                "elapsedMillis" to elapsed, "manualMatchInserted" to false,
                "originalCacheAbsent" to (BookHelp.getContent(book, chapter, false) == null))))
            assertTrue("Automatic recovery must return a complete readable chapter", rescued.length >= json["minimumCharacters"].asInt)
            assertTrue("Recovery must use the newly repaired generic rules", expectedSource in used)
            assertEquals("Recovered text must match the independently fetched chapter", expectedHash, hash)
            assertNull("Rescue is separate from the original cache", BookHelp.getContent(book, chapter, false))
            withTimeout(10_000) { ReadBook.loadContentAwait(targetIndex, resetPageOffset = true) }
            assertTrue("Native reader must finish rendering the recovered chapter", ReadBook.curTextChapter?.isCompleted == true)
            assertEquals(targetIndex, ReadBook.durChapterIndex)
            assertEquals(0, ReadBook.durChapterPos)
            assertEquals(book.origin, ReadBook.book!!.origin)
            assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals("Recovery must retain existing reading history", oldRecords, appDb.readRecordDao.all)
            val rendered = ReadBook.curTextChapter!!.getContent()
            assertFalse(rendered.contains("获取正文失败"))
            assertFalse(rendered.contains("加载正文失败"))
            val report = GSON.fromJson(output.resolve("results.json").readText(), com.google.gson.JsonObject::class.java)
            report.addProperty("nativeReaderRendered", true)
            report.addProperty("originalBookIdentityAndNumericPositionPreserved", true)
            report.addProperty("renderedCharacters", rendered.length)
            output.resolve("results.json").writeText(GSON.toJson(report))
            println("PRIVATE_CHAPTER_RECOVERY passed=true characters=${rescued.length} rendered=true")
        } finally {
            try {
                // resetData is the public cancellation entry; releaseAndCancel is private.
                withContext(Dispatchers.Main) {
                    if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.resetData(book)
                    ReadBook.clearTextChapter()
                    ReadBook.book = null
                    ReadBook.bookSource = oldSource
                    ReadBook.chapterSize = oldSize
                    ReadBook.simulatedChapterSize = oldSimulatedSize
                    ReadBook.durChapterIndex = oldIndex
                    ReadBook.durChapterPos = oldPosition
                    ReadBook.isLocalBook = oldIsLocal
                    ReadBook.inBookshelf = oldInBookshelf
                    ReadBook.preDownloadTask = oldTask
                    ReadBook.downloadedChapters.clear()
                    ReadBook.downloadedChapters.addAll(oldDownloaded)
                    ReadBook.downloadFailChapters.clear()
                    ReadBook.downloadFailChapters.putAll(oldFailed)
                }
                ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
                if (owned) {
                    BookHelp.delContent(book, chapter)
                    clearOwnedMergeFiles(context, book, chapters, ownedSourceIds)
                    appDb.searchBookDao.clear(book.name, book.author)
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookDao.delete(book)
                    ownedSourceIds.toList().chunked(128).forEach { ids ->
                        ids.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
                            .takeIf { it.isNotEmpty() }?.let { appDb.bookSourceDao.delete(*it.toTypedArray()) }
                    }
                }
            } finally {
            MultiSourceManager.enabled = oldEnabled
            MultiSourceManager.candidateLimit = oldLimit
            AppConfig.enableReadRecord = oldRecord
            AppConfig.preDownloadNum = oldPreload
            check(pack.edit().apply { if (hadPack) putInt(VerifiedSourceBootstrap.VERSION_KEY, oldPack) else remove(VerifiedSourceBootstrap.VERSION_KEY) }.commit())
            }
        }
    }
    private fun clearOwnedMergeFiles(context: Context, book: Book, chapters: List<BookChapter>, sourceIds: Set<String>) {
        val directory = File(context.filesDir, "multisource-v1")
        val files = chapters.map { File(directory, sha(book.bookUrl + "\n" + it.url) + ".json") } +
            sourceIds.map { File(directory, "relation-" + sha(book.bookUrl + it) + ".json") }
        files.forEach { file -> check(!file.exists() || file.delete()) { "Unable to remove owned recovery fixture cache" } }
        MultiSourceManager.clearCandidateHistory(book)
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
