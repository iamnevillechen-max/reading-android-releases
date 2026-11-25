package io.legado.app.model.multisource.core

import java.text.Normalizer
import java.util.Locale

/**
 * Relocates UTF-16 character offsets using existing text, never a guessed percentage.
 * Punctuation/spacing and compatibility-width changes do not alter the anchor key.
 * Missing, repeated, conflicting or oversized evidence keeps the old offset, safely clamped.
 */
object ReadingPosition {
    private const val MAX_TEXT_LENGTH = 250_000
    private const val MAX_QUOTE_LENGTH = 8_192
    private const val MIN_POSITION_ANCHOR = 16
    private const val MIN_BOOKMARK_ANCHOR = 12
    private val windowLengths = intArrayOf(64, 40, 24, 16)

    fun relocate(oldText: String, newText: String, oldPosition: Int): Int {
        return findRelocation(oldText, newText, oldPosition) ?: clamp(newText, oldPosition)
    }

    /** A saved bounded quote has a local offset; failed evidence keeps the absolute offset. */
    fun relocateQuote(quote: String, newText: String, quotePosition: Int, oldPosition: Int): Int =
        findRelocation(quote, newText, quotePosition) ?: clamp(newText, oldPosition)

    private fun findRelocation(oldText: String, newText: String, oldPosition: Int): Int? {
        if (oldText == newText) return clamp(newText, oldPosition)
        if (oldText.length > MAX_TEXT_LENGTH || newText.length > MAX_TEXT_LENGTH ||
            oldText.isEmpty()) return null
        val old = index(oldText)
        val new = index(newText)
        if (old.key.length < MIN_POSITION_ANCHOR || new.key.isEmpty()) return null
        val position = old.keyPosition(clamp(oldText, oldPosition))
        val anchors = linkedSetOf<Pair<Int, Int>>()
        for (length in windowLengths) {
            if (old.key.length < length) continue
            // Include the character at the reading position in every candidate. A left-only
            // anchor ending just before it would land before an insertion at that boundary.
            val starts = intArrayOf(position - length / 2, position, position - length + 1)
            for (candidate in starts) {
                var start = candidate.coerceIn(0, old.key.length - length)
                var end = start + length
                if (start > 0 && old.key[start].isLowSurrogate() && old.key[start - 1].isHighSurrogate()) start--
                if (end < old.key.length && old.key[end - 1].isHighSurrogate() && old.key[end].isLowSurrogate()) end++
                if (position in start until end || position == old.key.length && end == position) {
                    anchors.add(start to end)
                }
            }
        }
        var matchedPosition: Int? = null
        for ((start, end) in anchors) {
            val anchor = old.key.substring(start, end)
            if (!reliable(anchor, MIN_POSITION_ANCHOR)) continue
            if (uniqueMatch(old.key, anchor) != start) continue
            val found = uniqueMatch(new.key, anchor) ?: continue
            val candidate = found + position - start
            if (matchedPosition != null && matchedPosition != candidate) return null
            matchedPosition = candidate
        }
        return matchedPosition?.let { clamp(newText, new.sourcePosition(it)) }
    }

    /**
     * Bookmark.bookText is a saved page quote. Prefer its complete unique match; if a new
     * paragraph was inserted inside that page, only a unique unchanged prefix can prove
     * where the page began. A suffix alone cannot establish a changed page's starting offset.
     */
    fun locateBookmark(newText: String, bookText: String, oldPosition: Int): Int {
        val fallback = clamp(newText, oldPosition)
        if (newText.length > MAX_TEXT_LENGTH || bookText.length > MAX_QUOTE_LENGTH) return fallback
        val new = index(newText)
        val quote = index(bookText).key
        if (!reliable(quote, MIN_BOOKMARK_ANCHOR)) return fallback
        uniqueMatch(new.key, quote)?.let { return clamp(newText, new.sourcePosition(it)) }
        for (length in intArrayOf(96, 64, 32, 16, MIN_BOOKMARK_ANCHOR)) {
            if (length >= quote.length) continue
            var end = length
            if (quote[end - 1].isHighSurrogate() && quote[end].isLowSurrogate()) end++
            val prefix = quote.substring(0, end)
            if (!reliable(prefix, MIN_BOOKMARK_ANCHOR)) continue
            uniqueMatch(new.key, prefix)?.let { return clamp(newText, new.sourcePosition(it)) }
        }
        return fallback
    }

    private data class IndexedText(val key: String, val sourceOffsets: IntArray, val sourceLength: Int) {
        fun keyPosition(sourcePosition: Int): Int {
            var low = 0
            var high = sourceOffsets.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sourceOffsets[middle] < sourcePosition) low = middle + 1 else high = middle
            }
            return low
        }

        fun sourcePosition(keyPosition: Int): Int = sourceOffsets.getOrNull(keyPosition) ?: sourceLength
    }

    private fun index(text: String): IndexedText {
        val key = StringBuilder(text.length)
        // Compatibility characters can expand, so grow only when necessary.
        var offsets = IntArray(text.length)
        var count = 0
        var source = 0
        while (source < text.length) {
            val codePoint = text.codePointAt(source)
            val normalized = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
            var cursor = 0
            while (cursor < normalized.length) {
                val value = normalized.codePointAt(cursor)
                if (Character.isLetterOrDigit(value)) {
                    val width = Character.charCount(value)
                    if (count + width > offsets.size) offsets = offsets.copyOf(maxOf(count + width, offsets.size * 2, 16))
                    key.appendCodePoint(value)
                    repeat(width) { offsets[count++] = source }
                }
                cursor += Character.charCount(value)
            }
            source += Character.charCount(codePoint)
        }
        return IndexedText(key.toString(), offsets.copyOf(count), text.length)
    }

    private fun reliable(anchor: String, minimum: Int): Boolean {
        if (anchor.length < minimum) return false
        val characters = hashSetOf<Int>()
        var cursor = 0
        while (cursor < anchor.length && characters.size < 4) {
            val value = anchor.codePointAt(cursor)
            characters.add(value)
            cursor += Character.charCount(value)
        }
        return characters.size >= 4
    }

    /** KMP scans once and stops as soon as a second (including overlapping) match appears. */
    private fun uniqueMatch(text: String, pattern: String): Int? {
        if (pattern.isEmpty() || pattern.length > text.length) return null
        val failure = IntArray(pattern.length)
        var matched = 0
        for (index in 1 until pattern.length) {
            while (matched > 0 && pattern[index] != pattern[matched]) matched = failure[matched - 1]
            if (pattern[index] == pattern[matched]) matched++
            failure[index] = matched
        }
        matched = 0
        var first: Int? = null
        for (index in text.indices) {
            while (matched > 0 && text[index] != pattern[matched]) matched = failure[matched - 1]
            if (text[index] == pattern[matched]) matched++
            if (matched == pattern.length) {
                if (first != null) return null
                first = index - pattern.length + 1
                matched = failure[matched - 1]
            }
        }
        return first
    }

    private fun clamp(text: String, position: Int): Int {
        val clamped = position.coerceIn(0, text.length)
        return if (clamped in 1 until text.length && text[clamped].isLowSurrogate() &&
            text[clamped - 1].isHighSurrogate()) clamped - 1 else clamped
    }
}
