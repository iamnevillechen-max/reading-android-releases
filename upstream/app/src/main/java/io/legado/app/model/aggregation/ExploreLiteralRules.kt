package io.legado.app.model.aggregation

import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/** Literal menus do not require Rhino, network slots, full-rule caches or disk writes. */
internal object ExploreLiteralRules {
    fun dynamic(rule: String): Boolean = rule.trimStart().let { it.startsWith("<js>", true) || it.startsWith("@js:", true) }
    fun parse(rule: String): List<ExploreKind> {
        require(!dynamic(rule)) { "Dynamic menus must use the native source parser" }
        val value = rule.trim()
        if (value.isEmpty()) return emptyList()
        if (value.startsWith("[")) return GSON.fromJsonArray<ExploreKind>(value).getOrThrow()
        return value.split(Regex("(&&|\\n)+")).filter { it.isNotBlank() }.map {
            val delimiter = it.indexOf("::")
            if (delimiter < 0) ExploreKind(it.trim(), null)
            else ExploreKind(it.substring(0, delimiter).trim(), it.substring(delimiter + 2).trim())
        }
    }
}
