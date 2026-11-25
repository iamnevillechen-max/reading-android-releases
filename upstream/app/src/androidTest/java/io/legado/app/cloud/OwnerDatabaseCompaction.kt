package io.legado.app.cloud

import android.database.Cursor
import android.os.StatFs
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Shared only by owner opt-in maintenance and isolated instrumentation fixtures. */
internal object OwnerDatabaseCompaction {
    data class TableSnapshot(val count: Long, val sha256: String)
    data class Result(val before: Map<String, TableSnapshot>, val beforeBytes: Long, val afterBytes: Long,
        val beforePages: Long, val afterPages: Long, val beforeFreePages: Long, val afterFreePages: Long)

    fun compact(db: SupportSQLiteDatabase, file: File, expected: Map<String, Long>,
                availableBytes: () -> Long = { StatFs(file.parentFile!!.absolutePath).availableBytes },
                afterPreflight: () -> Unit = {}): Result {
        assertTrue("Compaction cannot run inside another transaction", !db.inTransaction())
        assertHealthy(db)
        val before = snapshot(db)
        expected.forEach { (table, count) -> assertEquals("Preflight $table", count, before.getValue(table).count) }
        val beforePages = pragmaLong(db, "page_count")
        val beforeFreePages = pragmaLong(db, "freelist_count")
        val logicalBytes = beforePages * pragmaLong(db, "page_size")
        val requiredFree = maxOf(file.length(), logicalBytes) * 2L + 64L * 1024 * 1024
        assertTrue("VACUUM needs two database sizes plus 64MiB free space", availableBytes() >= requiredFree)
        afterPreflight()
        checkpoint(db)
        assertEquals("Application data changed before compaction; stop background work first", before, snapshot(db))
        assertTrue("A background transaction started before compaction", !db.inTransaction())
        // Measure after the checkpoint: a large WAL can otherwise make the
        // original main file look smaller than its actual pre-VACUUM database.
        val beforeBytes = file.length()
        db.execSQL("VACUUM")
        checkpoint(db)
        assertHealthy(db)
        assertEquals("VACUUM must preserve all declared table columns and source identities", before, snapshot(db))
        return Result(before, beforeBytes, file.length(), beforePages, pragmaLong(db, "page_count"),
            beforeFreePages, pragmaLong(db, "freelist_count"))
    }

    fun snapshot(db: SupportSQLiteDatabase): Map<String, TableSnapshot> {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND (name NOT LIKE 'sqlite_%' OR name='sqlite_sequence') ORDER BY name")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            val columns = mutableListOf<Pair<String, Int>>()
            db.query("PRAGMA table_info(${quoted(table)})").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1) to cursor.getInt(5)
            }
            val primary = columns.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
            val order = primary.ifEmpty { columns.map { it.first } }.joinToString(",") { quoted(it) + " COLLATE BINARY" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            db.query("SELECT * FROM ${quoted(table)} ORDER BY $order").use { cursor ->
                while (cursor.moveToNext()) {
                    digest.update(0x7e.toByte())
                    for (index in 0 until cursor.columnCount) {
                        val type = cursor.getType(index)
                        digest.update(type.toByte())
                        val bytes = when (type) {
                            Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                            Cursor.FIELD_TYPE_INTEGER -> ByteBuffer.allocate(8).putLong(cursor.getLong(index)).array()
                            Cursor.FIELD_TYPE_FLOAT -> ByteBuffer.allocate(8).putLong(java.lang.Double.doubleToLongBits(cursor.getDouble(index))).array()
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                            else -> cursor.getString(index).toByteArray(Charsets.UTF_8)
                        }
                        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                        digest.update(bytes)
                    }
                    count++
                }
            }
            TableSnapshot(count, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }

    private fun checkpoint(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue("SQLite did not return checkpoint status", cursor.moveToFirst())
            assertEquals("A live reader/writer prevents safe WAL truncation", 0L, cursor.getLong(0))
            assertEquals("All WAL frames must be checkpointed", cursor.getLong(1), cursor.getLong(2))
        }
    }

    private fun assertHealthy(db: SupportSQLiteDatabase) {
        db.query("PRAGMA quick_check").use { cursor ->
            assertTrue("SQLite quick_check returned no result", cursor.moveToFirst())
            assertEquals("SQLite quick_check must pass", "ok", cursor.getString(0))
            assertTrue("SQLite quick_check reported extra failures", !cursor.moveToNext())
        }
    }

    private fun pragmaLong(db: SupportSQLiteDatabase, pragma: String): Long = db.query("PRAGMA $pragma")
        .use { it.moveToFirst(); it.getLong(0) }
    private fun quoted(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
