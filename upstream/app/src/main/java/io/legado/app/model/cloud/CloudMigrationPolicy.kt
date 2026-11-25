package io.legado.app.model.cloud

import java.security.MessageDigest
import java.text.Normalizer

/** Shared rules for receipt verification; no Android or database dependency. */
object CloudMigrationPolicy {
    const val STUB = "reader-cloud://source"
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun requireReceipt(id: String, hash: String, receiptId: String, receiptHash: String, stored: Boolean) {
        require(id == receiptId && hash == receiptHash && stored) { "雲端書源存檔核對不一致，原資料已保留" }
    }

    fun canConvert(supported: Boolean, status: String) = supported && status == "healthy"

    const val CONTENT_QUALITY_VERSION = CloudContentQualityRules.VERSION

    /** Same generated template policy as the server; short genuine prose remains readable. */
    fun canReadContent(content: String?): Boolean {
        if (content == null) return false
        val text = normalizeContent(content)
        if (text.isEmpty()) return false
        if (text.count { it in '\uE000'..'\uF8FF' } > maxOf(8.0, text.length * 0.05)) return false
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val short = text.count { !it.isWhitespace() } <= CloudContentQualityRules.SHORT_TEMPLATE_LIMIT
        return lines.take(if (short) 16 else 2).none { line ->
            line.length <= CloudContentQualityRules.TEMPLATE_LINE_LIMIT && gateLine(line)
        }
    }

    /** Clearing an old rule requires more evidence than simply displaying a short chapter. */
    fun canMigrateContent(content: String?): Boolean {
        if (!canReadContent(content)) return false
        val text = normalizeContent(content!!).filterNot { it.isWhitespace() }
        return text.codePointCount(0, text.length) >= CloudContentQualityRules.MIGRATION_MINIMUM
    }

    private fun normalizeContent(content: String): String = Normalizer.normalize(content, Normalizer.Form.NFKC)
        .replace(Regex("""</?(?:p|div|br|h[1-6]|section)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]*>"""), "")
        .replace(Regex("""&nbsp;|&#160;""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim()

    private fun gateLine(line: String): Boolean {
        val text = line.trim().replace(Regex("""^[【\[（(]\s*"""), "")
        if (CloudContentQualityRules.patterns.any { it.containsMatchIn(text) }) return true
        val heading = Regex("""^(?:第[^\s。！？!?“”"'「」\n]{1,24}[章回節节卷篇]|chapter\s+\d+)[^。！？!?“”"'「」\n]{0,100}?\s+""", RegexOption.IGNORE_CASE).find(text)
            ?: return false
        val tail = text.substring(heading.value.length)
        if (CloudContentQualityRules.patterns.any { it.containsMatchIn(tail) }) return true
        return Regex("""\s+""").findAll(tail).any { match ->
            val remaining = tail.substring(match.range.last + 1)
            CloudContentQualityRules.patterns.any { it.containsMatchIn(remaining) }
        }
    }
}
