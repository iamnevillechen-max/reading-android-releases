package io.legado.app.model.aggregation

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssCategorySpec
import io.legado.app.utils.splitNotBlank

/** Source groups and original sort names remain exact labels; no semantic taxonomy is applied. */
object RssCategories {
    const val UNCLASSIFIED = "\u0000unclassified"

    private fun groups(value: String?) = value.orEmpty().splitNotBlank(AppPattern.splitGroupRegex)
        .map { it.trim() }.filter { it.isNotEmpty() }

    private fun literalSorts(value: String?): List<String> {
        val rule = value.orEmpty().trim()
        if (ExploreLiteralRules.dynamic(rule)) return emptyList()
        return rule.split(Regex("(&&|\n)+")).filter { it.contains("::") }
            .map { it.substringBefore("::").trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun names(sources: List<RssSource>): List<String> = namesFromMetadata(sources.asSequence()
        .filter { it.enabled }.map { RssCategorySpec(it.sourceGroup, it.sortUrl) }.toList())

    fun namesFromMetadata(sources: List<RssCategorySpec>, resolvedNames: Iterable<String> = emptyList()): List<String> {
        val names = linkedSetOf<String>()
        var unclassified = false
        sources.forEach {
            val labels = groups(it.sourceGroup) + literalSorts(it.sortUrl)
            if (labels.isEmpty()) unclassified = true else names.addAll(labels)
        }
        names.addAll(LocalCategoryPolicy.mergeLabels(resolvedNames))
        if (unclassified) names.add(UNCLASSIFIED)
        return names.toList()
    }

    /** Resolved native sort names let a successful dynamic menu participate in exact filtering. */
    fun includesSource(source: RssSource, category: String?, resolvedSortNames: Iterable<String>? = null): Boolean {
        if (category == null) return true
        val labels = groups(source.sourceGroup) +
            (resolvedSortNames?.let(LocalCategoryPolicy::mergeLabels) ?: literalSorts(source.sortUrl))
        return if (category == UNCLASSIFIED) labels.isEmpty() else category.trim() in labels
    }

    fun includesSort(source: RssSource, sort: String, category: String?): Boolean = when (category) {
        null -> true
        UNCLASSIFIED -> sort.trim().isEmpty() && groups(source.sourceGroup).isEmpty()
        else -> category.trim() in groups(source.sourceGroup) || sort.trim() == category.trim()
    }
}
