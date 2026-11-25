package io.legado.app.cloud

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.utils.GSON
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.cloud.LocalSourceRestoreRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only owned in-memory reader references; no Room writes, service, HTTP, player or page reload. */
@RunWith(AndroidJUnit4::class)
class LocalSourceRestoreRefreshIntegrationTest {
    private val sourceId = "https://owned-refresh.invalid"
    private fun book(label: String) = Book(bookUrl = "$sourceId/$label", origin = sourceId, name = "Owned $label",
        author = "Owned author", durChapterIndex = 7, durChapterPos = 321)
    private fun thin() = BookSource(bookSourceUrl = sourceId, bookSourceName = "Owned proxy", searchUrl = CloudMigrationPolicy.STUB)
    private suspend fun ownedReaders(test: suspend (Book, Book, Book, BookSource) -> Unit) {
        val text = book("text"); val manga = book("manga"); val audio = book("audio"); val stub = thin()
        val previous = withContext(Dispatchers.Main.immediate) {
            listOf(ReadBook.book to ReadBook.bookSource, ReadManga.book to ReadManga.bookSource, AudioPlay.book to AudioPlay.bookSource)
                .also { ReadBook.book = text; ReadBook.bookSource = stub; ReadManga.book = manga; ReadManga.bookSource = stub
                    AudioPlay.book = audio; AudioPlay.bookSource = stub }
        }
        val limiter = ReadManga.rateLimiter
        try { test(text, manga, audio, stub) } finally {
            withContext(Dispatchers.Main.immediate) {
                ReadBook.book = previous[0].first; ReadBook.bookSource = previous[0].second
                ReadManga.book = previous[1].first; ReadManga.bookSource = previous[1].second; ReadManga.rateLimiter = limiter
                AudioPlay.book = previous[2].first; AudioPlay.bookSource = previous[2].second
            }
        }
    }

    @Test(timeout = 15_000) fun fullRulesReplaceAllThreeSnapshotsWithOneIoReadAndNoReadingMutation() = runBlocking {
        ownedReaders { text, manga, audio, _ ->
            val full = thin().copy(searchUrl = "/owned-search", enabled = false)
            val beforeText = text.copy(); val beforeManga = manga.copy(); val beforeAudio = audio.copy()
            val playUrl = AudioPlay.durPlayUrl; val status = AudioPlay.status
            val index = AudioPlay.durChapterIndex; val milliseconds = AudioPlay.durChapterPos
            var reads = 0
            val refreshed = LocalSourceRestoreRefresh.refreshCurrentSources { id ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertEquals(sourceId, id); reads++; full
            }
            assertEquals(3, refreshed); assertEquals(1, reads)
            withContext(Dispatchers.Main.immediate) {
                assertSame(full, ReadBook.bookSource); assertSame(full, ReadManga.bookSource); assertSame(full, AudioPlay.bookSource)
                assertSame(text, ReadBook.book); assertSame(manga, ReadManga.book); assertSame(audio, AudioPlay.book)
                assertEquals(GSON.toJson(beforeText), GSON.toJson(text)); assertEquals(GSON.toJson(beforeManga), GSON.toJson(manga)); assertEquals(GSON.toJson(beforeAudio), GSON.toJson(audio))
                assertEquals(playUrl, AudioPlay.durPlayUrl); assertEquals(status, AudioPlay.status)
                assertEquals(index, AudioPlay.durChapterIndex); assertEquals(milliseconds, AudioPlay.durChapterPos)
            }
        }
    }

    @Test(timeout = 15_000) fun reopenSourceChangeAndNewerCachedRulesDuringIoRejectTheOldSnapshot() = runBlocking {
        ownedReaders { text, manga, audio, stub ->
            val newer = thin().copy(searchUrl = "/newer-owned-rule")
            val loaded = thin().copy(searchUrl = "/loaded-owned-rule")
            assertEquals(0, LocalSourceRestoreRefresh.refreshCurrentSources {
                withContext(Dispatchers.Main.immediate) {
                    ReadBook.book = text.copy() // same URL/origin, different open session
                    manga.origin = "https://other-owned.invalid" // same object, changed source
                    AudioPlay.bookSource = newer // a newer refresh already won
                }
                loaded
            })
            withContext(Dispatchers.Main.immediate) {
                assertSame(stub, ReadBook.bookSource); assertSame(stub, ReadManga.bookSource); assertSame(newer, AudioPlay.bookSource)
                assertSame(audio, AudioPlay.book)
            }
        }
    }

    @Test(timeout = 15_000) fun missingMismatchedAndStillThinRulesDoNotReplaceAnActiveSnapshot() = runBlocking {
        ownedReaders { _, _, _, stub ->
            for (candidate in listOf(null, thin(), thin().copy(bookSourceUrl = "https://other-owned.invalid", searchUrl = "/owned"))) {
                assertEquals(0, LocalSourceRestoreRefresh.refreshCurrentSources { candidate })
                withContext(Dispatchers.Main.immediate) {
                    assertSame(stub, ReadBook.bookSource); assertSame(stub, ReadManga.bookSource); assertSame(stub, AudioPlay.bookSource)
                }
            }
        }
    }
}
