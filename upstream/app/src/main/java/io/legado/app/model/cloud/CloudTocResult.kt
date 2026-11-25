package io.legado.app.model.cloud

import io.legado.app.data.entities.BookChapter

/** A response belongs to one exact local source/configuration snapshot. */
internal class CloudTocResult(private val rows: List<BookChapter>, val sourceId: String,
    val sourceJson: String, val configuration: String) : AbstractList<BookChapter>() {
    override val size get() = rows.size
    override fun get(index: Int) = rows[index]
}
