package io.legado.app.model.multisource.core

import java.text.Normalizer
import java.util.Locale

/** A title alone is never enough to combine books with unknown or different authors. */
data class BookIdentity(val title: String, val author: String)

data class ChapterIdentity(
    val title: String,
    val index: Int,
    val previousTitle: String? = null,
    val nextTitle: String? = null
)

object IdentityMatcher {
    private val chapterNumber = Regex("^(?:第\\s*)?([0-9零〇一二三四五六七八九十百千萬万兩两]+)\\s*[章節节回卷集話话](?:\\s*[:：、.．-])?\\s*")
    // Only explicit parenthesized or whitespace-separated part markers are recognized.
    // A normal title ending in 上/中/下 (e.g. 雨中) must remain unchanged.
    private val chapterPart = Regex("(?:\\((上|中|下|上半|下半)\\)|\\s+(上|中|下|上半|下半))$")

    fun normalize(value: String, normalizeChinese: (String) -> String = { it }): String =
        normalizeChinese(Normalizer.normalize(value, Normalizer.Form.NFKC))
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun sameBook(
        left: BookIdentity,
        right: BookIdentity,
        normalizeChinese: (String) -> String = { it }
    ): Boolean {
        val key = bookKey(left, normalizeChinese) ?: return false
        return key == bookKey(right, normalizeChinese)
    }

    fun bookKey(book: BookIdentity, normalizeChinese: (String) -> String = { it }): String? {
        val title = normalize(book.title, normalizeChinese)
        val author = author(book.author, normalizeChinese)
        return if (title.isBlank() || author.isBlank()) null else "$title\u0000$author"
    }

    private fun author(value: String, normalizeChinese: (String) -> String): String {
        // 著 can be part of a real author's name. Strip it only as a separated byline.
        val byline = titleText(value, normalizeChinese)
            .replace(Regex("^作者\\s*[:：]?\\s*"), "")
            .replace(Regex("(?:\\s+著|[（(]著[）)])$"), "")
        val normalized = normalize(byline)
        return normalized.takeUnless { it in setOf("佚名", "未知", "unknown", "anonymous", "暂无", "暫無", "不詳", "不详", "暫無作者", "暂无作者", "未提供", "無名氏", "无名氏") }.orEmpty()
    }

    /** The ordinal is deliberately removed: a shifted table of contents must still match. */
    fun chapterTitle(value: String, normalizeChinese: (String) -> String = { it }): String {
        val text = titleText(value, normalizeChinese)
        return normalize(text.replace(chapterNumber, "").replace(chapterPart, ""))
    }

    fun sameChapter(
        target: ChapterIdentity,
        candidate: ChapterIdentity,
        normalizeChinese: (String) -> String = { it }
    ): Boolean {
        // A split primary must never consume an entire chapter or a different part. A full
        // primary may use split sources only through a validated complete group below.
        if (part(target.title, normalizeChinese) != part(candidate.title, normalizeChinese)) return false
        val targetTitle = chapterTitle(target.title, normalizeChinese)
        val candidateTitle = chapterTitle(candidate.title, normalizeChinese)
        if (targetTitle.isNotBlank() && candidateTitle.isNotBlank()) return targetTitle == candidateTitle
        // Pure numbered titles need contextual evidence. Equal chapter numbers or indexes
        // alone are unsafe after inserted announcements, split chapters, or a changed TOC.
        if (targetTitle.isBlank() && candidateTitle.isBlank()) {
            val targetOrdinal = ordinal(target.title, normalizeChinese) ?: return false
            return targetOrdinal == ordinal(candidate.title, normalizeChinese) &&
                neighborMatches(target, candidate, normalizeChinese) > 0
        }
        return neighborMatches(target, candidate, normalizeChinese) == 2
    }

    fun matchChapter(
        target: ChapterIdentity,
        candidates: List<ChapterIdentity>,
        normalizeChinese: (String) -> String = { it }
    ): ChapterIdentity? {
        val matches = candidates.filter { sameChapter(target, it, normalizeChinese) }
        if (matches.size == 1) return matches.first()
        if (matches.isEmpty()) return null
        val scored = matches.map { it to neighborMatches(target, it, normalizeChinese) }
            .sortedByDescending { it.second }
        // Repeated semantic chapter titles are ambiguous unless neighbors disambiguate.
        return scored.first().takeIf { it.second > 0 && it.second > scored[1].second }?.first
    }

    /**
     * Returns one complete chapter, or an explicit adjacent 上/下 or 上/中/下 group. The
     * caller fetches every returned part and joins their original text in this order, then
     * labels that composite with the UNSPLIT target identity before passing it to the merger.
     * Missing parts, mixed ordinals, unrelated neighbors, and repeated ambiguous groups are
     * rejected. Arbitrary adjacent chapters are never guessed to form one chapter.
     */
    fun matchChapterParts(
        target: ChapterIdentity,
        candidates: List<ChapterIdentity>,
        normalizeChinese: (String) -> String = { it }
    ): List<ChapterIdentity> {
        matchChapter(target, candidates, normalizeChinese)?.let { return listOf(it) }
        if (part(target.title, normalizeChinese) != null) return emptyList()
        val targetTitle = chapterTitle(target.title, normalizeChinese)
        val targetOrdinal = ordinal(target.title, normalizeChinese)
        val groups = mutableListOf<Pair<List<ChapterIdentity>, Int>>()
        for (start in candidates.indices) {
            if (part(candidates[start].title, normalizeChinese) != 1) continue
            for (size in 2..3) {
                if (start + size > candidates.size) continue
                val group = candidates.subList(start, start + size)
                val parts = group.map { part(it.title, normalizeChinese) }
                if (parts != if (size == 2) listOf(1, 3) else listOf(1, 2, 3)) continue
                if (group.indices.any { group[it].index != group.first().index + it }) continue
                val ordinals = group.map { ordinal(it.title, normalizeChinese) }
                val groupOrdinal = ordinals.first() ?: continue
                if (ordinals.any { it != groupOrdinal } || targetOrdinal != null && targetOrdinal != groupOrdinal) continue
                val keys = group.map { chapterTitle(it.title, normalizeChinese) }
                if (keys.any { it != targetTitle }) continue
                val context = ChapterIdentity(target.title, group.first().index,
                    group.first().previousTitle, group.last().nextTitle)
                val neighborScore = neighborMatches(target, context, normalizeChinese)
                // Number-only split labels need BOTH actual semantic outside neighbors.
                if (targetTitle.isBlank() && (targetOrdinal == null || neighborScore != 2)) continue
                if (!compatibleNeighbor(target.previousTitle, context.previousTitle, normalizeChinese) ||
                    !compatibleNeighbor(target.nextTitle, context.nextTitle, normalizeChinese)) continue
                groups += group.toList() to neighborScore
            }
        }
        if (groups.size == 1) return groups.first().first
        val ranked = groups.sortedByDescending { it.second }
        return ranked.firstOrNull()?.takeIf {
            it.second > 0 && it.second > ranked[1].second
        }?.first.orEmpty()
    }

    internal fun isPartHeadingOf(
        target: ChapterIdentity,
        heading: String,
        normalizeChinese: (String) -> String
    ): Boolean = part(target.title, normalizeChinese) == null && part(heading, normalizeChinese) != null &&
        ordinal(target.title, normalizeChinese)?.let { it == ordinal(heading, normalizeChinese) } == true &&
        chapterTitle(target.title, normalizeChinese) == chapterTitle(heading, normalizeChinese)

    internal fun isOwnHeading(
        target: ChapterIdentity,
        heading: String,
        normalizeChinese: (String) -> String
    ): Boolean {
        if (part(target.title, normalizeChinese) != part(heading, normalizeChinese)) return false
        val a = ordinal(target.title, normalizeChinese)
        val b = ordinal(heading, normalizeChinese)
        if (a != null && b != null && a != b) return false
        val title = chapterTitle(target.title, normalizeChinese)
        return title == chapterTitle(heading, normalizeChinese) && (title.isNotBlank() || a != null && a == b)
    }

    private fun titleText(value: String, normalizeChinese: (String) -> String): String =
        normalizeChinese(Normalizer.normalize(value, Normalizer.Form.NFKC)).trim()

    private fun part(value: String, normalizeChinese: (String) -> String): Int? {
        val match = chapterPart.find(titleText(value, normalizeChinese)) ?: return null
        return when (match.groupValues.drop(1).firstOrNull { it.isNotBlank() }) {
            "上", "上半" -> 1
            "中" -> 2
            "下", "下半" -> 3
            else -> null
        }
    }

    private fun ordinal(value: String, normalizeChinese: (String) -> String): Long? {
        val label = chapterNumber.find(titleText(value, normalizeChinese))?.groupValues?.get(1) ?: return null
        label.toLongOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '两' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (label.all { it in digits }) return label.map { digits.getValue(it) }.joinToString("").toLongOrNull()
        var result = 0L
        var section = 0L
        var number = 0L
        for (character in label) {
            val digit = digits[character]
            if (digit != null) { number = digit.toLong(); continue }
            when (character) {
                '十', '百', '千' -> {
                    val unit = when (character) { '十' -> 10; '百' -> 100; else -> 1_000 }
                    section += (if (number == 0L) 1L else number) * unit
                    number = 0L
                }
                '萬', '万' -> { result += (section + number) * 10_000L; section = 0L; number = 0L }
                else -> return null
            }
        }
        return result + section + number
    }

    private fun compatibleNeighbor(a: String?, b: String?, normalizeChinese: (String) -> String): Boolean {
        if (a == null || b == null) return true
        val left = chapterTitle(a, normalizeChinese)
        val right = chapterTitle(b, normalizeChinese)
        return left.isBlank() || right.isBlank() || left == right
    }

    private fun neighborMatches(
        left: ChapterIdentity,
        right: ChapterIdentity,
        normalizeChinese: (String) -> String
    ): Int = listOf(left.previousTitle to right.previousTitle, left.nextTitle to right.nextTitle)
        .count { (a, b) ->
            a != null && b != null && chapterTitle(a, normalizeChinese).let { key ->
                key.isNotBlank() && key == chapterTitle(b, normalizeChinese)
            }
        }
}
