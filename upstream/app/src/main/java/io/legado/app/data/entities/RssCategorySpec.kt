package io.legado.app.data.entities

/** Lightweight labels and identity only; dynamic rules are resolved lazily by source ID. */
data class RssCategorySpec(val sourceGroup: String?, val sortUrl: String?, val sourceId: String = "",
                           val lastUpdateTime: Long = 0, val sortLength: Int = 0, val dynamicSort: Boolean = false)
