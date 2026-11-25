package io.legado.app.model.cloud

import java.net.URI

/** A website entry is one approved static URL, never a source-program expression. */
object CloudWebEntryPolicy {
    const val EXTRA_CLOUD_WEBSITE = "readerCloudWebsite"
    const val EXTRA_URL = "readerCloudWebsiteUrl"

    fun requireUrl(value: String): String {
        val text = value.trim()
        require(text.length in 1..16_384 && !text.contains(Regex("[\\r\\n]|@js:|<js>|\\{\\{", RegexOption.IGNORE_CASE))) {
            "雲端網站入口網址無效"
        }
        val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException("雲端網站入口網址無效") }
        require(uri.scheme?.lowercase() in setOf("https", "http") && uri.rawUserInfo == null &&
            uri.port in setOf(-1, 80, 443)) { "雲端網站入口只接受公開 HTTP 網址" }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("雲端網站入口主機無效")
        require(!host.contains(':') && host.contains('.') &&
            !Regex("(?:^|\\.)(localhost|local|internal|test|invalid|onion)$").containsMatchIn(host)) {
            "雲端網站入口主機無效"
        }
        if (Regex("\\d+(?:\\.\\d+){3}").matches(host)) {
            val parts = host.split('.').map { it.toInt() }
            val a = parts[0]; val b = parts[1]
            require(host == parts.joinToString(".") && parts.all { it in 0..255 } && !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b in setOf(0, 168)) || (a == 198 && b in 18..19))) { "雲端網站入口主機無效" }
        } else require(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]*").matches(host)) {
            "雲端網站入口主機無效"
        }
        return text
    }
}
