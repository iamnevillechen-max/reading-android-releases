package io.legado.app.ui.accessibility

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook

/** One navigable item for rows whose children only illustrate the same book or article. */
object ContentRowAccessibility {
    private val whitespace = Regex("[\\s\\u3000]+")

    /** Configure once when inflating; retain the row's ordinary click and long-click actions. */
    fun prepare(row: ViewGroup) {
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        row.isFocusable = true
        row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        ViewCompat.setScreenReaderFocusable(row, true)
        for (index in 0 until row.childCount) {
            // Hiding each passive subtree also covers dynamically created category labels.
            row.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    fun bindBook(row: ViewGroup, book: SearchBook, inBookshelf: Boolean) {
        val context = row.context
        row.contentDescription = summary(
            book.name,
            book.author.takeIf { it.isNotBlank() }?.let { context.getString(R.string.author_show, it) },
            book.getKindList().take(3).joinToString(" · "),
            book.latestChapterTitle?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.lasted_show, it) },
            book.originName.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.content_row_source, it) },
            book.origins.size.takeIf { it > 1 }
                ?.let { context.getString(R.string.content_row_source_count, it) },
            context.getString(R.string.content_row_in_bookshelf).takeIf { inBookshelf },
            book.intro?.let(::excerpt)?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.intro_show, it) }
        )
    }

    fun bindArticle(row: ViewGroup, article: RssArticle, sourceLabel: String?) {
        val context = row.context
        row.contentDescription = summary(
            article.title,
            article.pubDate,
            sourceLabel?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.content_row_source, it) },
            context.getString(R.string.content_row_read).takeIf { article.read }
        )
    }

    private fun summary(vararg parts: String?): String = parts.mapNotNull {
        it?.replace(whitespace, " ")?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().joinToString("，")

    /** Keep list navigation brief; the full introduction remains available in book details. */
    private fun excerpt(value: String): String {
        val text = value.replace(whitespace, " ").trim()
        val characters = text.codePointCount(0, text.length)
        return if (characters <= 80) text else text.substring(0, text.offsetByCodePoints(0, 80)) + "…"
    }
}
