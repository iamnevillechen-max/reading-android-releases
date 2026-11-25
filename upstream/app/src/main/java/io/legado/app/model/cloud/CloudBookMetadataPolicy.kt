package io.legado.app.model.cloud

/** Metadata is plain text. A parser failure is not a new title or author. */
internal object CloudBookMetadataPolicy {
    private val tag = Regex("</?[a-z][a-z0-9:_-]*(?:\\s[^<>]*|/?)>", RegexOption.IGNORE_CASE)
    private val imageAttribute = Regex("(?:^|[\\s\"'])(?:src|alt|width|height)\\s*=", RegexOption.IGNORE_CASE)
    private val control = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    fun hasMarkup(value: String): Boolean {
        // Decode only for recognition; never manufacture a title from img.alt or
        // by removing arbitrary markup. The server must supply a fresh value.
        val recognized = value.replace(Regex("&(?:lt|#0*60|#x0*3c);", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&(?:gt|#0*62|#x0*3e);", RegexOption.IGNORE_CASE), ">")
            .replace("&quot;", "\"", ignoreCase = true)
        return tag.containsMatchIn(recognized) ||
            (recognized.contains("/>") && imageAttribute.containsMatchIn(recognized))
    }

    fun isPlain(value: String?): Boolean = value != null && value.isNotBlank() &&
        value.length <= 1024 && !control.containsMatchIn(value) && !hasMarkup(value)

    fun sameBook(bookUrl: String, origin: String, requestedSource: String,
                 responseUrl: String?, responseOrigin: String?): Boolean =
        bookUrl.isNotBlank() && origin.isNotBlank() && requestedSource == origin &&
            responseUrl == bookUrl && (responseOrigin.isNullOrBlank() || responseOrigin == origin)

    fun identityValue(current: String, candidate: String?, canRename: Boolean,
                      exactBook: Boolean): String =
        if (isPlain(candidate) && (if (hasMarkup(current)) exactBook else canRename)) candidate!!.trim() else current
}
