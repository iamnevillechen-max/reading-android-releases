package io.legado.app.model.aggregation

/** Successful menu metadata only; revisions prevent a late result from restoring removed rules. */
internal class RssCategoryMenuStore {
    private data class Entry(val token: String, val sorts: List<Pair<String, String>>)
    private var tokens = emptyMap<String, String>()
    private val entries = linkedMapOf<String, Entry>()
    var revision: Long = 0; private set

    @Synchronized fun synchronize(next: Map<String, String>): Boolean {
        if (tokens == next) return false
        entries.entries.removeAll { next[it.key] != it.value.token }
        tokens = next.toMap(); revision++
        return true
    }
    @Synchronized fun token(id: String): String? = tokens[id]
    @Synchronized fun menu(id: String): List<Pair<String, String>>? = entries[id]?.takeIf { tokens[id] == it.token }?.sorts
    @Synchronized fun publish(id: String, expectedToken: String, sorts: List<Pair<String, String>>): Boolean {
        if (tokens[id] != expectedToken) return false
        val menu = sorts.filter { it.second.isNotBlank() }.map { it.first.trim() to it.second }.distinct()
        if (menu.isEmpty()) return false // A failed/empty parse never replaces a known successful menu.
        val entry = Entry(expectedToken, menu)
        if (entries[id] == entry) return false
        entries[id] = entry; revision++
        return true
    }
    @Synchronized fun names(): List<String> = entries.values.flatMap { it.sorts.map { sort -> sort.first } }
        .map(String::trim).filter(String::isNotEmpty).distinct()
}
