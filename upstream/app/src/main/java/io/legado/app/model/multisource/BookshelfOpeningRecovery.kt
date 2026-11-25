package io.legado.app.model.multisource

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.core.ChapterIdentity
import io.legado.app.model.multisource.core.IdentityMatcher
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.GSON
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Installs only a fully verified replacement opening. Original source caches stay intact. */
internal object BookshelfOpeningRecovery {
    suspend fun install(expected: Book, found: MultiSourceManager.OpeningRecovery, epoch: Long): Book? {
        currentCoroutineContext().ensureActive()
        val replacement = appDb.withTransaction {
            val current = appDb.bookDao.getBook(expected.bookUrl) ?: return@withTransaction null
            if (!MultiSourceManager.enabled || ReadBook.readingEpoch != epoch || ReadBook.book?.bookUrl != expected.bookUrl ||
                current.origin != expected.origin || current.name != expected.name || current.author != expected.author ||
                current.durChapterIndex != expected.durChapterIndex || current.durChapterPos != expected.durChapterPos ||
                ReadBook.durChapterIndex != expected.durChapterIndex || ReadBook.durChapterPos != expected.durChapterPos)
                return@withTransaction null
            if (appDb.bookChapterDao.getChapterCount(expected.bookUrl) != 0) return@withTransaction null
            val source = appDb.bookSourceDao.getBookSource(found.source.bookSourceUrl)
                ?.takeIf { it.enabled && it.bookSourceType == 0 } ?: return@withTransaction null
            if (GSON.toJson(source) != GSON.toJson(found.source)) return@withTransaction null
            // Another shelf entry is user data, never an automatic merge destination.
            if (found.book.bookUrl == current.bookUrl || appDb.bookDao.getBook(found.book.bookUrl) != null)
                return@withTransaction null
            val replacement = current.copy(bookUrl = found.book.bookUrl, origin = source.bookSourceUrl,
                originName = source.bookSourceName, originOrder = source.customOrder,
                tocUrl = found.book.tocUrl, variable = found.book.variable, type = found.book.type,
                totalChapterNum = found.chapters.size, latestChapterTitle = found.chapters.lastOrNull()?.title.orEmpty(),
                durChapterIndex = found.chapter.index, durChapterTitle = found.chapter.title,
                readConfig = current.readConfig?.copy(multiSourcePositionAnchor = null))
            val identities = found.chapters.mapIndexed { index, chapter ->
                ChapterIdentity(chapter.title, chapter.index, found.chapters.getOrNull(index - 1)?.title,
                    found.chapters.getOrNull(index + 1)?.title)
            }
            appDb.bookmarkDao.getByBook(current.name, current.author).forEach { mark ->
                // Keep ambiguous bookmarks unchanged; never scale their chapter indexes.
                IdentityMatcher.matchChapter(ChapterIdentity(mark.chapterName, mark.chapterIndex),
                    identities, ChineseUtils::t2s)?.let { match ->
                    appDb.bookmarkDao.update(mark.copy(chapterIndex = match.index))
                }
            }
            appDb.bookDao.replace(current, replacement)
            appDb.bookChapterDao.insert(*found.chapters.toTypedArray())
            // Roll back the transaction if this opening was cancelled while synchronous DAOs ran.
            currentCoroutineContext().ensureActive()
            check(ReadBook.readingEpoch == epoch) { "Reading session changed during opening recovery" }
            replacement
        }
        currentCoroutineContext().ensureActive()
        // Cache IO is intentionally outside the database transaction. No original cache is deleted.
        if (replacement != null) BookHelp.saveText(replacement, found.chapter, found.text)
        return replacement
    }
}
