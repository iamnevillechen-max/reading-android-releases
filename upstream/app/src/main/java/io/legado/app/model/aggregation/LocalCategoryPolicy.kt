package io.legado.app.model.aggregation

/** Site labels are identities. Only surrounding whitespace and exact duplicates are removed. */
object LocalCategoryPolicy {
    /** Keep the existing signature, but source groups and retired overrides never rename a label. */
    @Suppress("UNUSED_PARAMETER")
    fun category(label: String?, group: String? = null, override: String? = null): String =
        label.orEmpty().trim()

    /** Preserve the first occurrence and every distinct site label, without a category limit. */
    fun mergeLabels(values: Iterable<String>): List<String> {
        val result = linkedSetOf<String>()
        for (value in values) category(value).takeIf { it.isNotEmpty() }?.let(result::add)
        return result.toList()
    }
}
