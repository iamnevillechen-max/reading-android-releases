package io.legado.app.model.cloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Replace only active parsing snapshots after a local import; never reopen, replay or save a book. */
object LocalSourceRestoreRefresh {
    private enum class Reader { TEXT, MANGA, AUDIO }
    private data class Snapshot(val reader: Reader, val book: Book, val source: BookSource?,
                                val bookUrl: String, val sourceId: String)

    suspend fun refreshCurrentSources(): Int = refreshCurrentSources { id -> appDb.bookSourceDao.getBookSource(id) }

    /** Injectable IO reader permits deterministic identity-race tests without a server or a production DB. */
    internal suspend fun refreshCurrentSources(readSource: suspend (String) -> BookSource?): Int {
        val active = withContext(Dispatchers.Main.immediate) {
            listOfNotNull(
                capture(Reader.TEXT, ReadBook.book, ReadBook.bookSource),
                capture(Reader.MANGA, ReadManga.book, ReadManga.bookSource),
                capture(Reader.AUDIO, AudioPlay.book, AudioPlay.bookSource)
            )
        }
        if (active.isEmpty()) return 0
        val restored = withContext(Dispatchers.IO) {
            active.map { it.sourceId }.distinct().mapNotNull { id ->
                readSource(id)?.takeIf { it.bookSourceUrl == id && !CloudReader.isRemote(it) }?.let { id to it }
            }.toMap()
        }
        return withContext(Dispatchers.Main.immediate) {
            active.count { snapshot ->
                val source = restored[snapshot.sourceId] ?: return@count false
                val current = when (snapshot.reader) {
                    Reader.TEXT -> ReadBook.book to ReadBook.bookSource
                    Reader.MANGA -> ReadManga.book to ReadManga.bookSource
                    Reader.AUDIO -> AudioPlay.book to AudioPlay.bookSource
                }
                // The same URL can be reopened or changed to an alias while IO is running.
                if (current.first !== snapshot.book || current.second !== snapshot.source ||
                    current.first?.bookUrl != snapshot.bookUrl || current.first?.origin != snapshot.sourceId ||
                    current.second?.bookSourceUrl?.let { it != snapshot.sourceId } == true) return@count false
                when (snapshot.reader) {
                    Reader.TEXT -> ReadBook.bookSource = source
                    Reader.MANGA -> {
                        ReadManga.bookSource = source
                        ReadManga.rateLimiter = ConcurrentRateLimiter(source)
                    }
                    Reader.AUDIO -> AudioPlay.bookSource = source
                }
                true
            }
        }
    }

    private fun capture(reader: Reader, book: Book?, source: BookSource?): Snapshot? {
        book ?: return null
        if (book.origin.isBlank() || source?.bookSourceUrl?.let { it != book.origin } == true) return null
        return Snapshot(reader, book, source, book.bookUrl, book.origin)
    }
}
