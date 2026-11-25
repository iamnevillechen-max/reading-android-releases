package io.legado.app.model.cloud

/** Identity mapping only. Numeric position and fuzzy similarity never establish a chapter. */
object CloudTocPolicy {
    const val CONTRACT = "reader-toc-identity-1"
    data class Chapter(val url: String, val title: String, val index: Int)
    fun title(value: String) = value.trim().replace(Regex("\\s+"), " ")

    class Resolver(private val old: List<Chapter>, private val fresh: List<Chapter>) {
        private val oldIndex = old.associateBy { it.index }
        private val freshUrl = fresh.associateBy { it.url }
        private val oldTitle = old.groupBy { title(it.title) }
        private val freshTitle = fresh.groupBy { title(it.title) }
        init {
            require(fresh.isNotEmpty() && freshUrl.size == fresh.size && oldIndex.size == old.size &&
                old.map { it.url }.toSet().size == old.size && fresh.map { it.index }.toSet().size == fresh.size &&
                fresh.map { it.index } == fresh.indices.toList()) { "雲端目錄缺失或身份有歧義" }
        }
        val mapping: Map<Int, Int> = old.mapNotNull { chapter -> freshUrl[chapter.url]?.let { chapter.index to it.index } }.toMap()
        fun current(index: Int, fallbackTitle: String): Int {
            mapping[index]?.let { return it }
            if (old.isEmpty() && index == 0 && fallbackTitle.isBlank()) return 0
            val name = title(oldIndex[index]?.title ?: fallbackTitle)
            require(name.isNotBlank() && oldTitle[name].orEmpty().size <= 1) { "目前章名有歧義，保留舊目錄" }
            return freshTitle[name]?.singleOrNull()?.index ?: throw IllegalStateException("新目錄無法唯一對應章節，保留舊目錄")
        }
    }

    fun mapping(old: List<Chapter>, fresh: List<Chapter>): Map<Int, Int> {
        return Resolver(old, fresh).mapping
    }

    fun current(oldIndex: Int, oldTitle: String, old: List<Chapter>, fresh: List<Chapter>): Int {
        return Resolver(old, fresh).current(oldIndex, oldTitle)
    }
}
