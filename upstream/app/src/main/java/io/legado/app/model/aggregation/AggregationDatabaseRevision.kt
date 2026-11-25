package io.legado.app.model.aggregation

import io.legado.app.data.appDb
import java.util.concurrent.atomic.AtomicLong

/** Writer-connection-local metadata; no Room schema or user rules are changed. */
internal object AggregationDatabaseRevision {
    data class Changes(val revision: String, val sources: Set<String>?)
    private val epochs = HashMap<String, Long>()
    private val sequence = AtomicLong()
    fun bookSources(): String = bookChanges(null).revision
    fun rssSources(): String = read("rssSources", "rss", "sourceUrl", null).revision
    fun rssChanges(since: String?): Changes = read("rssSources", "rss", "sourceUrl", since)
    fun bookChanges(since: String?): Changes = read("book_sources", "book", "bookSourceUrl", since)

    /** The changed-ID ledger avoids invalidating twenty thousand unchanged sources on one edit. */
    @Synchronized private fun read(table: String, key: String, id: String, since: String?): Changes {
        val name = "reader_aggregation_${key}_revision_v2"
        val dirty = "${name}_sources"
        val database = appDb.openHelper.writableDatabase
        database.beginTransactionNonExclusive()
        try {
            val installed = database.query("select count(*) from sqlite_temp_master where type = 'table' and name = '$name'").use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            if (!installed) {
                database.execSQL("create temp table $name (id integer primary key, changes integer not null)")
                database.execSQL("insert into $name values (1, 0)")
                database.execSQL("create temp table $dirty (source text primary key not null, revision integer not null)")
                database.execSQL("create index temp.${dirty}_revision on $dirty(revision)")
                epochs[key] = sequence.incrementAndGet()
                val columns = database.query("pragma main.table_info($table)").use { cursor ->
                    buildList { while (cursor.moveToNext()) {
                        val column = cursor.getString(1)
                        if (key != "book" || column !in setOf("respondTime", "weight")) add(column)
                    } }
                }
                val meaningfulUpdate = columns.joinToString(" or ") { "new.\"$it\" is not old.\"$it\"" }
                for (operation in listOf("insert", "update", "delete")) {
                    val identities = when (operation) {
                        "insert" -> listOf("new.$id")
                        "delete" -> listOf("old.$id")
                        else -> listOf("old.$id", "new.$id")
                    }
                    val record = identities.joinToString(" ") { identity ->
                        "update $dirty set revision=(select changes from $name where id=1) where source=$identity; " +
                            "insert into $dirty(source,revision) select $identity,changes from $name where id=1 " +
                            "and not exists(select 1 from $dirty where source=$identity);"
                    }
                    val condition = if (operation == "update") "when $meaningfulUpdate " else ""
                    database.execSQL("create temp trigger ${name}_$operation after $operation on main.$table $condition begin " +
                        "update $name set changes = changes + 1 where id = 1; $record end")
                }
            }
            val changes = database.query("select changes from $name where id = 1").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val epoch = epochs[key]
            val old = since?.split(':', limit = 2)
            val previous = old?.getOrNull(1)?.toLongOrNull()
            val changed = if (old?.firstOrNull() == epoch.toString() && previous != null && previous in 0..changes) {
                database.query("select source from $dirty where revision > ?", arrayOf(previous)).use { cursor ->
                    buildSet<String> { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            } else null
            database.setTransactionSuccessful()
            return Changes("$epoch:$changes", changed)
        } finally { database.endTransaction() }
    }
}
