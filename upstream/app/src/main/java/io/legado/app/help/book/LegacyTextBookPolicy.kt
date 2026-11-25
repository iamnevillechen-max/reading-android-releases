package io.legado.app.help.book

import io.legado.app.constant.BookType
import java.net.URI

/** Read-only compatibility for old online books that predate the text type bit. */
object LegacyTextBookPolicy {
    private const val metadataFlags = BookType.updateError or BookType.notShelf

    fun isNetworkText(type: Int, origin: String, bookUrl: String): Boolean =
        type and metadataFlags.inv() == 0 && isHttpUrl(origin) && isHttpUrl(bookUrl)

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
