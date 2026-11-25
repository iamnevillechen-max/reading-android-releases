package io.legado.app.model.cloud

import android.os.Looper
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.ContentProcessor
import io.legado.app.constant.BookType
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.core.ReadingProgressAnchor
import io.legado.app.model.multisource.core.ReadingProgressAnchors
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

/** One installation boundary for an existing cloud book. No source/book/history replacement. */
object CloudTocStore {
    private fun <T> main(action: () -> T): T = runBlocking(Dispatchers.Main.immediate) { action() }
    private fun identity(chapter: BookChapter) = CloudTocPolicy.Chapter(
        if (chapter.isVolume && chapter.url.startsWith(chapter.title)) "volume:" + chapter.url else chapter.getAbsoluteURL(),
        chapter.title, chapter.index)

    /** User-selected source changes/imports need a current response too, before replacing any book row. */
    fun verifyBeforeSave(book: Book, chapters: List<BookChapter>) {
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        val proof = chapters as? CloudTocResult
        if (proof != null) require(source != null && proof.sourceId == book.origin &&
            proof.sourceJson == GSON.toJson(source) && proof.configuration == CloudReader.configurationRevision(appCtx)) { "目錄回覆已過期，請重新取得" }
        if (source?.let { CloudReader.isRemote(it, book) } == true) require(proof != null) { "雲端目錄缺少完整回覆證明" }
    }

    /** Ordinary metadata/settings saves never own the cloud TOC or reading cursor. */
    fun preserveReaderStateForEdit(edit: Book) {
        val current = appDb.bookDao.getBook(edit.bookUrl) ?: return
        val currentSource = appDb.bookSourceDao.getBookSource(current.origin)
        val editSource = appDb.bookSourceDao.getBookSource(edit.origin)
        if (currentSource?.let { CloudReader.isRemote(it, current) } != true &&
            editSource?.let { CloudReader.isRemote(it, edit) } != true) return
        require(current.origin == edit.origin) { "書源已變更，請重新開啟編輯" }
        edit.durChapterIndex = current.durChapterIndex; edit.durChapterPos = current.durChapterPos
        edit.durChapterTitle = current.durChapterTitle; edit.durChapterTime = current.durChapterTime
        edit.totalChapterNum = current.totalChapterNum; edit.latestChapterTitle = current.latestChapterTitle
        edit.latestChapterTime = current.latestChapterTime; edit.lastCheckTime = current.lastCheckTime
        edit.lastCheckCount = current.lastCheckCount
        edit.readConfig = edit.readConfig?.copy() ?: current.readConfig?.copy()
        edit.readConfig?.multiSourcePositionAnchor = current.readConfig?.multiSourcePositionAnchor
        edit.cloudTocGeneration = ReadBook.cloudTocGenerationFor(current)
        edit.cloudTocInstalled = edit.cloudTocGeneration != 0L
    }

    /** Validated reader saves own progress; concurrent metadata/group/cover edits remain intact. */
    fun readerProgress(progress: Book): Book {
        val current = appDb.bookDao.getBook(progress.bookUrl) ?: return progress
        val source = appDb.bookSourceDao.getBookSource(current.origin) ?: return progress
        if (!CloudReader.isRemote(source, current)) return progress
        require(current.origin == progress.origin) { "閱讀書源已變更" }
        return current.copy(durChapterIndex=progress.durChapterIndex,durChapterPos=progress.durChapterPos,
            durChapterTitle=progress.durChapterTitle,durChapterTime=progress.durChapterTime,
            readConfig=progress.readConfig?.copy() ?: current.readConfig?.copy())
    }

    fun prepareBookmark(bookmark: Bookmark) {
        ReadBook.requireCloudBookmarkMutation(bookmark.bookName, bookmark.bookAuthor)
        val book = bookmark.referenceBookUrl?.let { appDb.bookDao.getBook(it) }
            ?: appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor) ?: return
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        if (!CloudReader.isRemote(source, book)) return
        ReadBook.requireCloudBookMutation(book.bookUrl)
        val saved = appDb.bookmarkDao.getByTime(bookmark.time)
        if (saved != null && saved.bookName == bookmark.bookName && saved.bookAuthor == bookmark.bookAuthor) {
            bookmark.chapterIndex = saved.chapterIndex
            return
        }
        val toc = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val chapter = if (bookmark.referenceChapterUrl != null) {
            require(bookmark.referenceSource == book.origin && bookmark.referenceBookUrl == book.bookUrl) { "書籤來源已變更" }
            toc.singleOrNull { identity(it).url == bookmark.referenceChapterUrl }
        } else {
            val name = CloudTocPolicy.title(bookmark.chapterName)
            toc.singleOrNull { name.isNotBlank() && CloudTocPolicy.title(it.title) == name }
        }
        bookmark.chapterIndex = chapter?.index ?: error("書籤章節無法唯一核對，請重新建立")
    }

    fun installIfCloud(book: Book, chapters: List<BookChapter>): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "目錄安裝必須在背景執行" }
        return installOnWorker(book, chapters, false, null)
    }

    /** Explicit local reorder/save: every chapter's persisted metadata must equal the DB snapshot. */
    fun saveDisplayedIfCloud(book: Book, chapters: List<BookChapter>, reverse: Boolean? = null): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "目錄安裝必須在背景執行" }
        return installOnWorker(book, chapters, chapters !is CloudTocResult, reverse)
    }

    @Synchronized private fun installOnWorker(book: Book, chapters: List<BookChapter>, localReorder: Boolean, reverse: Boolean?): Boolean {
        val proof = chapters as? CloudTocResult
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        if (proof != null) require(source != null && proof.sourceId == book.origin &&
            proof.sourceJson == GSON.toJson(source) && proof.configuration == CloudReader.configurationRevision(appCtx)) {
            "雲端設定或書源已變更，保留舊目錄"
        }
        if (source == null) return false
        if (!CloudReader.isRemote(source, book)) {
            require(proof == null) { "書源模式已變更，保留舊目錄" }
            return false
        }
        require(proof != null || localReorder) { "雲端目錄缺少完整回覆證明，保留舊目錄" }
        val beforeBook = appDb.bookDao.getBook(book.bookUrl) ?: return false
        require(beforeBook.origin == book.origin) { "書源已變更，保留舊目錄" }
        require(chapters.isNotEmpty() && chapters.all { it.bookUrl == book.bookUrl }) { "雲端目錄與書籍不符" }
        val beforeToc = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (localReorder) {
            val originals = beforeToc.associateBy { it.url }
            require(chapters.size == beforeToc.size && originals.size == beforeToc.size && chapters.all { chapter ->
                originals[chapter.url]?.let { GSON.toJson(it.copy(index=0)) == GSON.toJson(chapter.copy(index=0)) } == true
            }) { "本機目錄已變更，請重新載入後排序" }
        }
        val beforeIdentity = beforeToc.map(::identity)
        val fresh = chapters.map(::identity)
        // Preflight every bookmark before private preservation can seal the legacy index namespace.
        bookmarkPlans(beforeBook, beforeToc, fresh)
        CloudTocPolicy.current(beforeBook.durChapterIndex, beforeBook.durChapterTitle.orEmpty(), beforeIdentity, fresh)
        CloudContentCache.preserveToc(beforeBook, beforeToc, chapters)
        val snapshot = main { ReadBook.beginCloudToc(beforeBook) }
        var installed: Book? = null
        try {
            appDb.runInTransaction {
                if (proof != null) require(proof.sourceJson == GSON.toJson(appDb.bookSourceDao.getBookSource(book.origin)) &&
                    proof.configuration == CloudReader.configurationRevision(appCtx)) { "書源已變更，保留舊目錄" }
                val current = appDb.bookDao.getBook(book.bookUrl) ?: error("書籍已變更，保留舊目錄")
                require(current.origin == book.origin) { "書源已變更，保留舊目錄" }
                val old = appDb.bookChapterDao.getChapterList(current.bookUrl)
                val oldIdentity = old.map(::identity)
                require(oldIdentity == beforeIdentity) { "目錄已變更，保留原資料後重試" }
                val oldIndex = if (snapshot.book != null) snapshot.index else current.durChapterIndex
                val position = if (snapshot.book != null) snapshot.position else current.durChapterPos
                val previous = old.singleOrNull { it.index == oldIndex }
                if (snapshot.rendered != null) require(previous != null &&
                    identity(snapshot.rendered.chapter).url == identity(previous).url) { "閱讀章節已變更，保留舊目錄" }
                val resolver = CloudTocPolicy.Resolver(oldIdentity, fresh)
                val index = resolver.current(oldIndex, current.durChapterTitle.orEmpty())
                val correspondence = resolver.mapping
                val bookmarks = bookmarkPlans(current, old, fresh)
                val updated = current.copy(readConfig = current.readConfig?.copy()).apply {
                    name = book.name; author = book.author; tocUrl = book.tocUrl
                    kind = book.kind; coverUrl = book.coverUrl; intro = book.intro; wordCount = book.wordCount
                    variable = book.variable
                    totalChapterNum = chapters.size; latestChapterTitle = book.latestChapterTitle
                    latestChapterTime = book.latestChapterTime; lastCheckTime = book.lastCheckTime
                    lastCheckCount = book.lastCheckCount
                    durChapterIndex = index; durChapterPos = position; durChapterTitle = chapters[index].title
                    removeType(BookType.updateError); cloudTocInstalled = true
                    if (reverse != null) config.reverseToc = reverse
                }
                updated.readConfig?.multiSourcePositionAnchor?.let { raw -> runCatching {
                    if (previous != null && correspondence[oldIndex] == index) {
                        ReadingProgressAnchors.rebindExact(GSON.fromJson(raw, ReadingProgressAnchor::class.java),
                            current.bookUrl, previous.url, oldIndex, position, chapters[index].url, index)
                            ?.let { updated.readConfig?.multiSourcePositionAnchor = GSON.toJson(it) }
                    }
                } }
                appDb.bookChapterDao.delByBook(current.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
                for ((bookmark, mapped) in bookmarks) if (mapped != bookmark.chapterIndex) {
                    bookmark.chapterIndex = mapped; appDb.bookmarkDao.updateForCloudToc(bookmark)
                }
                appDb.bookDao.updateForCloudToc(updated)
                installed = updated
            }
            // Room is closed before waiting for Main. Same-chapter movement made
            // during the transaction is flushed here, rather than lost to the snapshot.
            val result = main { ReadBook.finishCloudToc(snapshot, requireNotNull(installed),
                chapters[requireNotNull(installed).durChapterIndex], CloudTocPolicy.mapping(beforeIdentity, fresh)) }
            adopt(book, result); book.cloudTocInstalled = true
            return true
        } catch (error: Throwable) {
            main { ReadBook.abortCloudToc(snapshot) }
            throw error
        }
    }

    private fun bookmarkPlans(book: Book, old: List<BookChapter>, fresh: List<CloudTocPolicy.Chapter>): List<Pair<io.legado.app.data.entities.Bookmark, Int>> {
        val resolver = CloudTocPolicy.Resolver(old.map(::identity), fresh)
        val byIndex = old.associateBy { it.index }
        val rules = ContentProcessor.get(book).getTitleReplaceRules()
        fun names(chapter: BookChapter) = setOf(CloudTocPolicy.title(chapter.title),
            CloudTocPolicy.title(chapter.getDisplayTitle(rules, book.getUseReplaceRule())))
        val byTitle = old.flatMap { chapter -> names(chapter).map { it to chapter } }.groupBy({ it.first }, { it.second })
        return appDb.bookmarkDao.getByBook(book.name, book.author).map { bookmark ->
            val name = CloudTocPolicy.title(bookmark.chapterName)
            val indexed = byIndex[bookmark.chapterIndex]?.takeIf { name in names(it) }
            val previous = indexed ?: byTitle[name]?.singleOrNull()?.takeIf { name.isNotBlank() }
                ?: error("書籤章節無法核對，保留舊目錄與書籤")
            bookmark to resolver.current(previous.index, previous.title)
        }
    }

    /** Keep caller objects aligned so later native callbacks cannot write stale local preferences. */
    private fun adopt(target: Book, from: Book) {
        target.tocUrl = from.tocUrl; target.originName = from.originName
        target.name = from.name; target.author = from.author; target.kind = from.kind
        target.customTag = from.customTag; target.coverUrl = from.coverUrl; target.customCoverUrl = from.customCoverUrl
        target.intro = from.intro; target.customIntro = from.customIntro; target.charset = from.charset
        target.type = from.type; target.group = from.group; target.latestChapterTitle = from.latestChapterTitle
        target.latestChapterTime = from.latestChapterTime; target.lastCheckTime = from.lastCheckTime
        target.lastCheckCount = from.lastCheckCount; target.totalChapterNum = from.totalChapterNum
        target.durChapterTitle = from.durChapterTitle; target.durChapterIndex = from.durChapterIndex
        target.durChapterPos = from.durChapterPos; target.durChapterTime = from.durChapterTime
        target.wordCount = from.wordCount; target.canUpdate = from.canUpdate; target.order = from.order
        target.originOrder = from.originOrder; target.readConfig = from.readConfig; target.syncTime = from.syncTime
        target.variable = from.variable; target.variableMap.clear(); target.variableMap.putAll(from.variableMap)
        target.cloudTocGeneration = from.cloudTocGeneration
    }
}
