package io.legado.app.model.multisource.core

/** A bounded description of the exact rendered text to which one native saved offset belongs. */
data class ReadingProgressAnchor(
    val version: Int,
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val position: Int,
    val renderedHash: String,
    val quote: String,
    val quotePosition: Int
)

object ReadingProgressAnchors {
    private const val VERSION = 1
    private const val MAX_TEXT = 250_000
    private const val WINDOW = 160
    private val digestPattern = Regex("[0-9a-f]{64}")

    /** Caller has proved the old/new URL identify the same chapter in this book's TOC. */
    fun rebindExact(anchor: ReadingProgressAnchor?, bookUrl: String, oldUrl: String,
        oldIndex: Int, position: Int, newUrl: String, newIndex: Int): ReadingProgressAnchor? =
        anchor?.takeIf { matches(it, bookUrl, oldUrl, oldIndex, position) }
            ?.copy(chapterId = hash(newUrl), chapterIndex = newIndex)

    fun capture(bookUrl: String, chapterUrl: String, chapterIndex: Int,
        renderedText: String, position: Int): ReadingProgressAnchor? {
        if (chapterIndex < 0 || renderedText.isEmpty() || renderedText.length > MAX_TEXT ||
            position !in 0..renderedText.length) return null
        val safePosition = keepOffset(renderedText, position)
        var start = maxOf(0, safePosition - 48)
        var end = minOf(renderedText.length, start + WINDOW)
        if (start > 0 && renderedText[start].isLowSurrogate() && renderedText[start - 1].isHighSurrogate()) start--
        if (end < renderedText.length && renderedText[end - 1].isHighSurrogate() && renderedText[end].isLowSurrogate()) end++
        return ReadingProgressAnchor(VERSION, hash(bookUrl), hash(chapterUrl), chapterIndex,
            safePosition, hash(renderedText), renderedText.substring(start, end), safePosition - start)
    }

    fun matches(anchor: ReadingProgressAnchor?, bookUrl: String, chapterUrl: String,
        chapterIndex: Int, position: Int): Boolean = anchor != null && anchor.version == VERSION &&
        anchor.chapterIndex == chapterIndex && anchor.position == position && position >= 0 &&
        anchor.quote.length in 1..WINDOW + 2 && anchor.quotePosition in 0..anchor.quote.length &&
        digestPattern.matches(anchor.renderedHash) && anchor.bookId == hash(bookUrl) &&
        anchor.chapterId == hash(chapterUrl)

    fun restore(anchor: ReadingProgressAnchor?, bookUrl: String, chapterUrl: String,
        chapterIndex: Int, newRenderedText: String, position: Int): Int {
        if (!matches(anchor, bookUrl, chapterUrl, chapterIndex, position) ||
            newRenderedText.length > MAX_TEXT) return keepOffset(newRenderedText, position)
        anchor!!
        // The numeric offset already refers to this derived version. Never map it from raw again.
        if (anchor.renderedHash == hash(newRenderedText)) return keepOffset(newRenderedText, position)
        return ReadingPosition.relocateQuote(anchor.quote, newRenderedText, anchor.quotePosition, position)
    }

    private fun hash(text: String) = StreamingHash.sha256 { it.write(text) }
    private fun keepOffset(text: String, position: Int) = ReadingPosition.relocate(text, text, position)
}
