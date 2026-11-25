package io.legado.app.model.multisource.core

import java.text.Normalizer
import java.util.Locale

/** sourceFamily is an origin family, not a vote; unknown origins should share one family. */
data class ChapterCandidate(
    val sourceId: String,
    val sourceName: String,
    val sourceFamily: String,
    val book: BookIdentity,
    val chapter: ChapterIdentity,
    val text: String
)

enum class MergeConfidence { HIGH, MEDIUM, LOW }

data class MergeSegment(
    val text: String,
    val sourceIds: List<String>,
    val independentFamilies: Int
)

data class MergeResult(
    val text: String,
    val merged: Boolean,
    val confidence: MergeConfidence,
    val usedSourceIds: List<String>,
    val notes: List<String>,
    val segments: List<MergeSegment>,
    val conflictingVersions: Boolean = false
)

/**
 * Conservative, source-only reconstruction. Ordered unique text anchors establish overlap;
 * only one-sided gaps may be filled. Conflicting text is never replaced by a majority vote.
 * Normalization changes comparison keys only; all displayed characters come from a source.
 * Work is bounded to 8 candidates and 80k normalized characters per text, with O(n log n)
 * anchor ordering rather than a quadratic chapter-sized edit-distance matrix.
 */
class ChapterMerger(private val normalizeChinese: (String) -> String = { it }) {
    companion object {
        const val VERSION = 1
        private const val MAX_CANDIDATES = 8
        private const val MAX_RAW_LENGTH = 200_000
        private const val MAX_NORMALIZED_LENGTH = 80_000
        private const val ANCHOR = 12
        private const val MIN_SHARED = 32
        // Comparison emits this character only for an exact, standalone website notice.
        private const val NOTICE = '\u0000'
        private val advertisement = Regex(
            "(?i)^(?:請收藏(?:本站|本書|本网站|本網站)|请收藏(?:本站|本书|本网站)|(?:加入書籤|加入书签)\\s*[!！。.]?$|(?:立即|馬上|马上|點擊|点击)(?:下載|下载).{0,40}(?:app|客戶端|客户端)|本章未完[，,。\\s]*(?:請|请)?(?:點擊|点击)|(?:請|请)?(?:記住|记住)(?:本站|本書|本网站|本網站|本书).{0,60}(?:域名|網址|网址)).*$"
        )
        private val standaloneUrl = Regex("(?i)^(?:https?://|www\\.)[^\\s]+$")
        private val navigation = Regex(
            "^(?:[\\s|｜<>‹›«»《》\\[\\]]*(?:上一[章頁页]|下一[章頁页]|返回目錄|返回目录|章節目錄|章节目录|目錄|目录)[\\s|｜<>‹›«»《》\\[\\]]*)+$"
        )
        private val accessPage = Regex(
            "(?i)^(?:(?:(?:請|请)(?:先)?(?:登錄|登录|登入)(?:後|后)?(?:閱讀|阅读|繼續|继续)|(?:需要|需)(?:登錄|登录|登入)(?:後|后)?(?:閱讀|阅读)|(?:請|请)(?:輸入|输入).{0,12}(?:驗證碼|验证码)|(?:付費|付费)(?:後|后)(?:閱讀|阅读))(?:[，,。.!！:：\\s].*)?|(?:人機驗證|人机验证|安全驗證|安全验证)(?:\\s*[:：].*)?|(?:access denied|sign in to continue|cloudflare security verification)\\s*[.!。]?)$"
        )
        // Counters also begin ordinary narrative, including 第十回合結束時.
        // Generic labels need a separator; exact own/part/neighboring TOC
        // headings are recognized independently even when they omit spaces.
        private val heading = Regex("^\\s*(?:第\\s*)?[0-9零〇一二三四五六七八九十百千萬万兩两]+\\s*[章節节回卷集話话](?=\\s|[:：、.．-]|$).{0,70}$")
        private val numberedHeadingPrefix = Regex("^\\s*(?:第\\s*)?[0-9零〇一二三四五六七八九十百千萬万兩两]+\\s*[章節节回卷集話话]")
    }

    private data class Prepared(val candidate: ChapterCandidate, val text: String, val normalized: Normalized)
    private data class Normalized(val value: String, val offsets: IntArray) {
        fun boundary(raw: String, position: Int): Int {
            if (position == 0) return 0
            if (position == value.length) return raw.length
            var offset = offsets[position]
            val previousEnd = offsets[position - 1] + 1
            // Opening punctuation belongs to the following source characters. Keeping
            // it with that span avoids dropping 「 or moving it before an inserted gap.
            val opening = "“‘「『（([《〈【"
            while (offset > previousEnd && (raw[offset - 1].isWhitespace() || raw[offset - 1] in opening)) offset--
            return offset
        }
        fun slice(raw: String, start: Int, end: Int): String {
            if (start >= end) return ""
            return raw.substring(boundary(raw, start), boundary(raw, end)).trim()
        }
    }
    private data class Match(val a: Int, val b: Int, val length: Int)
    private data class Edit(val start: Int, val end: Int, val text: String)
    private data class PairMerge(val text: String?, val reason: String, val conflict: Boolean = false)

    fun merge(primary: ChapterCandidate, others: List<ChapterCandidate>): MergeResult {
        val notes = mutableListOf<String>()
        val prepared = mutableListOf<Prepared>()
        val charCache = HashMap<Char, String>()
        fun prepare(candidate: ChapterCandidate): Prepared? {
            if (candidate.text.length > MAX_RAW_LENGTH) {
                notes += "${candidate.sourceName}：正文超過安全處理上限，保留原來源。"
                return null
            }
            val clean = clean(candidate, notes, primary = candidate === primary)
            if (clean.isBlank()) return null
            val normalized = normalizeWithOffsets(clean, charCache)
            if (candidate !== primary && NOTICE in normalized.value) {
                notes += "${candidate.sourceName}：此來源也有缺段或試讀提示，繼續比對其他來源。"
                return null
            }
            if (normalized.value.length > MAX_NORMALIZED_LENGTH) {
                notes += "${candidate.sourceName}：正文超過比對上限，未自動拼接。"
                return null
            }
            return Prepared(candidate, clean, normalized).takeIf { it.normalized.value.isNotBlank() }
        }
        prepare(primary)?.let(prepared::add)
        others.take(MAX_CANDIDATES - 1).forEach { candidate ->
            when {
                !IdentityMatcher.sameBook(primary.book, candidate.book, normalizeChinese) ->
                    notes.add("${candidate.sourceName}：書名或作者不匹配，已排除。")
                !IdentityMatcher.sameChapter(primary.chapter, candidate.chapter, normalizeChinese) ->
                    notes.add("${candidate.sourceName}：章節身份不明，已排除。")
                prepared.any { it.candidate.sourceId == candidate.sourceId } -> Unit
                else -> prepare(candidate)?.let(prepared::add)
            }
        }
        if (prepared.isEmpty()) return MergeResult(primary.text, false, MergeConfidence.LOW,
            listOf(primary.sourceId), notes + "沒有可安全比對的候選，保留原流程。", emptyList())
        val base = prepared.first()
        if (base.candidate.sourceId != primary.sourceId) notes += "原來源無正文，使用已匹配章節的可用單一來源。"
        var output = base.text
        val accepted = mutableListOf(base)
        var changed = false
        var conflictingVersions = false
        for (candidate in prepared.drop(1)) {
            val pair = mergePair(output, candidate, charCache)
            notes += "${candidate.candidate.sourceName}：${pair.reason}"
            if (pair.conflict) {
                // If supplements disagree, an earlier arrival must not win by timing.
                output = base.text
                changed = false
                conflictingVersions = true
                accepted.clear()
                accepted += base
                notes += "來源版本互相衝突，本章退回原有可靠單源，沒有依先到或票數選擇補文。"
                break
            }
            if (pair.text != null) {
                changed = changed || pair.text != output
                output = pair.text
                accepted += candidate
            }
        }
        val segments = provenance(output, accepted, charCache)
        val families = accepted.map { it.candidate.sourceFamily.ifBlank { "unknown" } }.distinct()
        val confidence = when {
            accepted.size < 2 -> MergeConfidence.LOW
            families.size > 1 && segments.isNotEmpty() && segments.all { it.independentFamilies > 1 } -> MergeConfidence.HIGH
            else -> MergeConfidence.MEDIUM
        }
        if (families.size == 1 && accepted.size > 1) notes += "來源屬同一或未知家族，不視為獨立多票。"
        if (!changed) notes += "本章沒有足夠證據需要拼接，使用可靠單源正文。"
        return MergeResult(output, changed, confidence,
            accepted.map { it.candidate.sourceId }.distinct(), notes.distinct(), segments, conflictingVersions)
    }

    fun readableBody(candidate: ChapterCandidate): String {
        if (candidate.text.length > MAX_RAW_LENGTH) return ""
        return clean(candidate, mutableListOf()).takeUnless(ChapterContentPolicy::hasRepairNotice).orEmpty()
    }

    private fun clean(candidate: ChapterCandidate, notes: MutableList<String>, primary: Boolean = false): String {
        if (ChapterContentPolicy.missing(candidate.text, candidate.chapter.title)) return ""
        val nonemptyLines = candidate.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6).toList()
        if (candidate.text.length < 2_000 && nonemptyLines.size <= 4 &&
            nonemptyLines.firstOrNull()?.let { accessPage.matches(it) && !(primary && ChapterContentPolicy.isRepairNotice(it)) } == true) {
            notes += "${candidate.sourceName}：返回登入、付費或驗證頁，已排除。"
            return ""
        }
        val adjacentTitles = listOfNotNull(candidate.chapter.previousTitle, candidate.chapter.nextTitle)
            .map { IdentityMatcher.chapterTitle(it, normalizeChinese) }.filter { it.isNotBlank() }
        val ownTitle = IdentityMatcher.chapterTitle(candidate.chapter.title, normalizeChinese)
        val kept = mutableListOf<String>()
        var started = false
        var removed = false
        for (line in candidate.text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (kept.lastOrNull()?.isNotBlank() == true) kept += ""
                continue
            }
            val title = IdentityMatcher.chapterTitle(trimmed, normalizeChinese)
            // Equal narrative words alone do not make the opening sentence a header.
            // Require a numeric chapter label as well as the known semantic title.
            val ownTitleAtStart = !started && title == ownTitle &&
                numberedHeadingPrefix.containsMatchIn(trimmed)
            val partHeading = title == ownTitle && IdentityMatcher.isPartHeadingOf(candidate.chapter, trimmed, normalizeChinese)
            if (heading.matches(trimmed) || title in adjacentTitles || ownTitleAtStart || partHeading) {
                if (partHeading || ownTitleAtStart && IdentityMatcher.isOwnHeading(candidate.chapter, trimmed, normalizeChinese)) {
                    removed = true
                    continue
                }
                if (started) {
                    removed = true
                    break // A neighboring heading is a hard boundary, never content to append.
                }
                if (!IdentityMatcher.isOwnHeading(candidate.chapter, trimmed, normalizeChinese)) {
                    notes += "${candidate.sourceName}：正文起始章名不符，已排除。"
                    return ""
                }
            }
            if (trimmed.length < 240 && (advertisement.matches(trimmed) || navigation.matches(trimmed) || standaloneUrl.matches(trimmed))) {
                removed = true
                continue
            }
            started = true
            kept += line.trimEnd()
        }
        if (removed) notes += "${candidate.sourceName}：已隔離導航、廣告或相鄰章節標題。"
        return kept.joinToString("\n").trim()
    }

    private fun normalizeWithOffsets(raw: String, charCache: MutableMap<Char, String>): Normalized {
        val text = StringBuilder(raw.length)
        val offsets = ArrayList<Int>(raw.length)
        var start = 0
        while (start < raw.length) {
            val newline = raw.indexOf('\n', start)
            val end = if (newline >= 0) newline else raw.length
            if (end - start <= 180 && ChapterContentPolicy.isRepairNotice(raw.substring(start, end))) {
                text.append(NOTICE)
                offsets.add(start)
            } else {
                for (index in start until end) {
                    val character = raw[index]
                    val normalized = charCache.getOrPut(character) {
                        normalizeChinese(Normalizer.normalize(character.toString(), Normalizer.Form.NFKC))
                            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                    }
                    normalized.forEach { text.append(it); offsets.add(index) }
                }
            }
            start = end + 1
        }
        return Normalized(text.toString(), offsets.toIntArray())
    }

    private fun mergePair(raw: String, incoming: Prepared, charCache: MutableMap<Char, String>): PairMerge {
        val a = normalizeWithOffsets(raw, charCache)
        val b = incoming.normalized
        if (a.value == b.value) return PairMerge(raw, "正文一致。")
        if (minOf(a.value.length, b.value.length) < MIN_SHARED) return PairMerge(null, "有效正文太短，未強行拼接。")
        val matches = matchingBlocks(a.value, b.value)
        val shared = matches.sumOf { it.length }
        if (shared < MIN_SHARED || shared.toDouble() / minOf(a.value.length, b.value.length) < .28) {
            return PairMerge(null, "缺少可靠連續重疊，可能錯章或版本不同；保留單源。")
        }
        // A source claiming the same chapter but mostly containing unrelated text is unsafe.
        if (maxOf(a.value.length, b.value.length) > shared * 6) {
            return PairMerge(null, "來源差異過大，未強行拼接。")
        }
        val edits = mutableListOf<Edit>()
        fun noticeOnly(start: Int, end: Int) = (start until end).all { a.value[it] == NOTICE }
        val existingBlocks by lazy {
            HashSet<String>().apply {
                if (a.value.length >= MIN_SHARED) for (position in 0..a.value.length - MIN_SHARED) {
                    add(a.value.substring(position, position + MIN_SHARED))
                }
            }
        }
        fun reused(addition: String): Boolean {
            if (addition.length >= ANCHOR && a.value.contains(addition)) return true
            if (addition.length >= MIN_SHARED) {
                var position = 0
                while (position <= addition.length - MIN_SHARED) {
                    if (addition.substring(position, position + MIN_SHARED) in existingBlocks) return true
                    position += 4
                }
            }
            return false
        }
        var aEnd = 0
        var bEnd = 0
        matches.forEachIndexed { index, match ->
            val gapB = match.b - bEnd
            if (gapB > 0 && !noticeOnly(aEnd, match.a)) {
                return PairMerge(null, "重疊之間存在互相衝突的版本，保留單源。", true)
            }
            if (gapB > 0) {
                val leftLength = if (index == 0) 0 else matches[index - 1].length
                if (index > 0 && (leftLength < ANCHOR || match.length < ANCHOR)) {
                    return PairMerge(null, "缺段兩端證據不足，未拼接。")
                }
                if (gapB > maxOf(1_500, (leftLength + match.length) * 3)) {
                    return PairMerge(null, "新增片段長度異常，未拼接。")
                }
                val additionKey = b.value.substring(bEnd, match.b)
                if (reused(additionKey)) {
                    return PairMerge(null, "新增片段已在其他位置出現，疑似順序錯亂；未拼接。")
                }
                edits += Edit(aEnd, match.a, b.slice(incoming.text, bEnd, match.b))
            }
            aEnd = match.a + match.length
            bEnd = match.b + match.length
        }
        val gapB = b.value.length - bEnd
        if (gapB > 0 && !noticeOnly(aEnd, a.value.length)) return PairMerge(null, "章尾存在互相衝突的版本，保留單源。", true)
        if (gapB > 0) {
            if (gapB > maxOf(1_500, matches.last().length * 3)) return PairMerge(null, "章尾新增片段異常，未拼接。")
            val additionKey = b.value.substring(bEnd)
            if (reused(additionKey)) {
                return PairMerge(null, "章尾片段已在其他位置出現，疑似順序錯亂；未拼接。")
            }
            edits += Edit(aEnd, a.value.length, b.slice(incoming.text, bEnd, b.value.length))
        }
        if (edits.isEmpty()) return PairMerge(raw, "已確認重疊；現有正文已包含可用內容。")
        val result = StringBuilder(raw.length + edits.sumOf { it.text.length + 2 })
        var cursor = 0
        for (edit in edits) {
            val position = a.boundary(raw, edit.start)
            result.append(raw.substring(cursor, position))
            if (result.isNotEmpty() && result.last() != '\n') result.append('\n')
            result.append(edit.text.trim())
            result.append('\n')
            cursor = a.boundary(raw, edit.end)
        }
        result.append(raw.substring(cursor))
        if (result.length > MAX_RAW_LENGTH) return PairMerge(null, "重建結果超過安全上限，保留單源。")
        return PairMerge(result.toString().trim(), "使用可靠重疊補回缺段，原有順序與重疊保持一次。")
    }

    /** Sparse unique 12-character anchors + longest increasing subsequence + exact expansion. */
    private fun matchingBlocks(a: String, b: String): List<Match> {
        if (a.length < ANCHOR || b.length < ANCHOR) return emptyList()
        val bIndex = HashMap<String, Int>()
        for (i in 0..b.length - ANCHOR) {
            val key = b.substring(i, i + ANCHOR)
            bIndex[key] = if (bIndex.containsKey(key)) -1 else i
        }
        val anchors = mutableListOf<Pair<Int, Int>>()
        val aSeen = HashSet<String>()
        var i = 0
        while (i <= a.length - ANCHOR) {
            val key = a.substring(i, i + ANCHOR)
            val position = bIndex[key]
            if (position != null && position >= 0 && aSeen.add(key)) anchors += i to position
            i += 4
        }
        if (anchors.isEmpty()) return emptyList()
        val tails = IntArray(anchors.size)
        val previous = IntArray(anchors.size) { -1 }
        var size = 0
        anchors.forEachIndexed { index, pair ->
            var low = 0
            var high = size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (anchors[tails[mid]].second < pair.second) low = mid + 1 else high = mid
            }
            if (low > 0) previous[index] = tails[low - 1]
            tails[low] = index
            if (low == size) size++
        }
        val ordered = mutableListOf<Pair<Int, Int>>()
        var current = tails[size - 1]
        while (current >= 0) { ordered += anchors[current]; current = previous[current] }
        ordered.reverse()
        val result = mutableListOf<Match>()
        ordered.forEachIndexed { index, pair ->
            var x = pair.first
            var y = pair.second
            val previousMatch = result.lastOrNull()
            val lowerA = previousMatch?.let { it.a + it.length } ?: 0
            val lowerB = previousMatch?.let { it.b + it.length } ?: 0
            if (x < lowerA || y < lowerB) return@forEachIndexed
            while (x > lowerA && y > lowerB && a[x - 1] == b[y - 1]) { x--; y-- }
            var endA = pair.first + ANCHOR
            var endB = pair.second + ANCHOR
            val next = ordered.getOrNull(index + 1)
            // Expansion can cross later same-diagonal anchors; those are skipped above.
            val upperA = if (next != null && next.first - pair.first != next.second - pair.second) next.first else a.length
            val upperB = if (next != null && next.first - pair.first != next.second - pair.second) next.second else b.length
            while (endA < upperA && endB < upperB && a[endA] == b[endB]) { endA++; endB++ }
            val block = Match(x, y, endA - x)
            if (previousMatch != null && previousMatch.a + previousMatch.length == x && previousMatch.b + previousMatch.length == y) {
                result[result.lastIndex] = previousMatch.copy(length = previousMatch.length + block.length)
            } else result += block
        }
        return result
    }

    private fun provenance(text: String, accepted: List<Prepared>, charCache: MutableMap<Char, String>): List<MergeSegment> =
        text.split(Regex("(?<=[。！？.!?])\\s*|\\n+"))
            .filter { it.isNotBlank() }.map { part ->
                val key = normalizeWithOffsets(part, charCache).value
                val supports = accepted.filter { key.isNotBlank() && it.normalized.value.contains(key) }
                MergeSegment(part, supports.map { it.candidate.sourceId }.distinct(),
                    supports.map { it.candidate.sourceFamily.ifBlank { "unknown" } }.distinct().size)
            }
}
