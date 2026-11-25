package io.legado.app.model.cloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import splitties.init.appCtx
import java.io.File

/** Complete cloud bodies and old native fallbacks have separate, URL-bound private records. */
object CloudContentCache {
    const val CONTRACT = CloudContentStore.CONTRACT
    private val store by lazy { CloudContentStore(File(appCtx.filesDir, "cloud-content-v1")) }
    private fun chapterIdentity(chapter: BookChapter) = if (chapter.isVolume) "volume:" + chapter.url else chapter.getAbsoluteURL()
    private fun identity(book: Book, chapter: BookChapter) = CloudContentStore.Identity(
        CloudReader.configurationRevision(appCtx), book.origin, book.bookUrl, chapterIdentity(chapter))
    private fun nativeFile(book: Book, chapter: BookChapter) = File(File(BookHelp.cachePath, book.getFolderName()), chapter.getFileName())

    internal fun isSealed(book: Book): Boolean = store.isSealed(CloudContentStore.Identity(
        CloudReader.configurationRevision(appCtx), book.origin, book.bookUrl, ""))

    /** URL and body-digest checks remain useful offline after the remote service is retired. */
    fun retained(book: Book, chapter: BookChapter): String? = store.locallyReadable(identity(book, chapter))

    fun complete(book: Book, chapter: BookChapter): String? =
        store.complete(identity(book, chapter), CloudReader.contentContractVersion())

    internal fun completeFileNames(book: Book): Set<String> {
        val urls = store.completeChapterUrls(CloudReader.configurationRevision(appCtx), book.origin, book.bookUrl,
            CloudReader.contentContractVersion())
        if (urls.isEmpty()) return emptySet()
        return appDb.bookChapterDao.getChapterList(book.bookUrl).filter { chapterIdentity(it) in urls }
            .mapTo(hashSetOf()) { it.getFileName() }
    }

    /** A deliberate local edit is separate from the server's complete contract. */
    fun edited(book: Book, chapter: BookChapter): String? = store.edited(identity(book, chapter))

    /** Offline fallback is deliberately not returned by BookHelp's complete-cache path. */
    fun fallback(book: Book, chapter: BookChapter): String? = store.fallback(identity(book, chapter))
        ?.takeIf { CloudMigrationPolicy.canReadContent(it) }

    fun preserveChapter(book: Book, chapter: BookChapter) = store.preserve(identity(book, chapter), nativeFile(book, chapter))

    /** Must finish before installing the new TOC. Original files stay intact, including collisions. */
    @Synchronized fun preserveToc(book: Book, oldChapters: List<BookChapter>, newChapters: List<BookChapter>) {
        require(newChapters.isNotEmpty() && newChapters.all { it.bookUrl == book.bookUrl }) { "新目錄身份不完整" }
        val reference = oldChapters.firstOrNull() ?: newChapters.first()
        val bookIdentity = identity(book, reference)
        if (store.isSealed(bookIdentity)) return // Later cloud writes use URL records, never the old index files.
        require(oldChapters.size <= 100_000) { "舊目錄過大，保留原目錄" }
        val started = System.nanoTime()
        var bytes = 0L
        var files = 0
        val seen = hashSetOf<String>()
        require(oldChapters.all { it.bookUrl == book.bookUrl && seen.add(chapterIdentity(it)) }) { "舊目錄身份不完整，保留原目錄" }
        val folder = File(BookHelp.cachePath, book.getFolderName())
        val names = folder.list().orEmpty().filter { it.endsWith(".nb") }.toHashSet()
        require(names.size <= 20_000) { "正文保全需分批處理，保留原目錄" }
        val cachedIndexes = names.mapNotNull { it.substringBefore('-').toIntOrNull() }.toHashSet()
        // Stat/hash only cached chapters, so a large mostly uncached TOC stays cheap.
        for (chapter in oldChapters.filter { it.index in cachedIndexes }) {
            check(System.nanoTime() - started < 2_000_000_000L) { "正文保全尚未完成，保留原目錄後再試" }
            val filename = chapter.getFileName()
            if (filename !in names) continue
            val original = File(folder, filename)
            if (original.isFile) {
                bytes += original.length(); files++
                check(bytes <= 128L * 1024 * 1024 && files <= 20_000) { "正文保全需分批處理，保留原目錄" }
                store.preserve(identity(book, chapter), original, ignoreSeal = true)
            }
        }
        store.sealToc(bookIdentity)
    }

    internal fun saveEdit(book: Book, chapter: BookChapter, body: String) = store.saveEdit(identity(book, chapter), body)
    fun invalidate(book: Book, chapter: BookChapter) {
        preserveChapter(book, chapter)
        store.invalidate(identity(book, chapter))
    }
    fun invalidateBook(book: Book) = store.invalidateBook(CloudReader.configurationRevision(appCtx), book.origin, book.bookUrl)
    internal fun clear() = store.clear()

    internal fun install(book: Book, chapter: BookChapter, body: String, complete: Boolean, contractVersion: String,
                         sourceId: String, bookUrl: String, chapterUrl: String, bodySha256: String) {
        preserveChapter(book, chapter)
        require(chapterUrl == chapter.url) { "雲端正文與請求章節不符" }
        val cacheIdentity = identity(book, chapter)
        store.install(cacheIdentity, body, complete, contractVersion, sourceId, bookUrl, cacheIdentity.chapterUrl, bodySha256)
    }
}
