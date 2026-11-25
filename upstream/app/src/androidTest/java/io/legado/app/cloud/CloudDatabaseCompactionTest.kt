package io.legado.app.cloud

import android.os.Build
import android.os.Bundle
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Emulator-only disposable databases. Never opens the reader's Room database. */
@RunWith(AndroidJUnit4::class)
class CloudDatabaseCompactionTest {
    private val counts = mapOf("books" to 2L, "readRecord" to 2L, "book_sources" to 4L, "rssSources" to 1L)

    private fun fixture(test: (SupportSQLiteDatabase, File) -> Unit) {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cloud-compaction-fixture-", ".db", context.cacheDir)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    // Android may default a newly created database to FULL
                    // auto-vacuum. Force this disposable fixture to retain free
                    // pages so the physical-compaction assertion is meaningful.
                    db.execSQL("PRAGMA auto_vacuum=NONE")
                    db.execSQL("VACUUM")
                }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE books(bookUrl TEXT PRIMARY KEY,name TEXT,durChapterIndex INTEGER,durChapterPos INTEGER,readConfig TEXT)")
                    db.execSQL("CREATE TABLE readRecord(deviceId TEXT,bookName TEXT,readTime INTEGER,lastRead INTEGER,PRIMARY KEY(deviceId,bookName))")
                    db.execSQL("CREATE TABLE book_sources(bookSourceUrl TEXT PRIMARY KEY,rule TEXT)")
                    db.execSQL("CREATE TABLE rssSources(sourceUrl TEXT PRIMARY KEY,rule TEXT)")
                    db.execSQL("CREATE TABLE typed_state(id INTEGER PRIMARY KEY AUTOINCREMENT,n,s TEXT,i INTEGER,f REAL,b BLOB)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture schema is fixed")
            }).build())
        try {
            helper.setWriteAheadLoggingEnabled(true)
            val db = helper.writableDatabase
            assertTrue("The fixture must use a real WAL database", db.isWriteAheadLoggingEnabled)
            db.query("PRAGMA auto_vacuum").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
            val large = "original private rule fixture ".repeat(40_000)
            for (id in listOf("", "  ", "https://fixture.invalid/\u0000book", "https://fixture.invalid/\nbook")) {
                db.execSQL("INSERT INTO book_sources VALUES(?,?)", arrayOf<Any?>(id, large))
            }
            db.execSQL("INSERT INTO rssSources VALUES(?,?)", arrayOf<Any?>("https://fixture.invalid/rss", large))
            db.execSQL("INSERT INTO books VALUES(?,?,?,?,?)", arrayOf<Any?>("fixture-book-a", "Owned fixture A", 450, 73, "{\"reverseToc\":true}"))
            db.execSQL("INSERT INTO books VALUES(?,?,?,?,?)", arrayOf<Any?>("fixture-book-b", "Owned fixture B", 12, 901, null))
            db.execSQL("INSERT INTO readRecord VALUES(?,?,?,?)", arrayOf<Any?>("fixture-device", "Owned fixture A", 12_345L, 1760000000000L))
            db.execSQL("INSERT INTO readRecord VALUES(?,?,?,?)", arrayOf<Any?>("fixture-device", "Owned fixture B", 987_654L, 1760000000001L))
            db.execSQL("INSERT INTO typed_state(n,s,i,f,b) VALUES(?,?,?,?,?)", arrayOf<Any?>(null, "\u0000\n中文", Long.MAX_VALUE, -1.25, byteArrayOf(0, 1, -1, 0)))
            db.execSQL("INSERT INTO typed_state(s) VALUES('deleted sequence fixture')")
            db.execSQL("DELETE FROM typed_state WHERE id=2")
            // Match the real migration: long columns become thin rules without
            // deleting a source identity or any reading-state row.
            db.execSQL("UPDATE book_sources SET rule='reader-cloud://source'")
            db.execSQL("UPDATE rssSources SET rule='reader-cloud://source'")
            test(db, file)
        } finally {
            helper.close()
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
        }
    }

    @Test fun walVacuumReclaimsPhysicalPagesAndPreservesAllTypedRowsAndNextIds() = fixture { db, file ->
        val before = OwnerDatabaseCompaction.snapshot(db)
        val result = OwnerDatabaseCompaction.compact(db, file, counts)
        assertEquals(before, OwnerDatabaseCompaction.snapshot(db))
        assertTrue("The fixture must actually exercise released database pages", result.beforeFreePages > 100)
        assertTrue("VACUUM must really shrink this fixture", result.beforeBytes - result.afterBytes > 1024 * 1024)
        assertEquals(0L, result.afterFreePages)
        assertEquals(0L, File(file.path + "-wal").length())
        assertEquals(4L, result.before.getValue("book_sources").count)
        db.execSQL("INSERT INTO typed_state(s) VALUES('next sequence fixture')")
        db.query("SELECT MAX(id) FROM typed_state").use { it.moveToFirst(); assertEquals(3L, it.getLong(0)) }
    }

    @Test fun wrongExpectedCountsAndInsufficientSpaceRefuseBeforeAnyCompaction() = fixture { db, file ->
        val before = OwnerDatabaseCompaction.snapshot(db)
        expectAssertion("Preflight books") { OwnerDatabaseCompaction.compact(db, file, counts + ("books" to 3L)) }
        assertEquals(before, OwnerDatabaseCompaction.snapshot(db))
        expectAssertion("two database sizes") { OwnerDatabaseCompaction.compact(db, file, counts, availableBytes = { 0 }) }
        assertEquals(before, OwnerDatabaseCompaction.snapshot(db))
    }

    @Test fun anExistingTransactionRefusesWithoutChangingRows() = fixture { db, file ->
        val before = OwnerDatabaseCompaction.snapshot(db)
        db.beginTransaction()
        try {
            expectAssertion("another transaction") { OwnerDatabaseCompaction.compact(db, file, counts) }
            assertEquals(before, OwnerDatabaseCompaction.snapshot(db))
        } finally { db.endTransaction() }
        assertEquals(before, OwnerDatabaseCompaction.snapshot(db))
    }

    @Test fun changedReadingStateBetweenPreflightAndVacuumAbortsBeforeShrinking() = fixture { db, file ->
        val pages = db.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
        expectAssertion("Application data changed") {
            OwnerDatabaseCompaction.compact(db, file, counts, afterPreflight = {
                db.execSQL("UPDATE books SET durChapterPos=durChapterPos+1 WHERE bookUrl='fixture-book-a'")
            })
        }
        db.query("PRAGMA page_count").use { it.moveToFirst(); assertEquals(pages, it.getLong(0)) }
        db.query("SELECT durChapterPos FROM books WHERE bookUrl='fixture-book-a'").use { it.moveToFirst(); assertEquals(74L, it.getLong(0)) }
    }

    @Test fun ownerEntryRejectsMissingOperationAndBackupProofBeforeOpeningTheReaderDatabase() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = InstrumentationRegistry.getArguments()
        try {
            InstrumentationRegistry.registerInstance(instrumentation, Bundle())
            try {
                CloudOwnerMigrationTest().compactOwnerDatabaseAfterVerifiedCloudMigration()
                throw AssertionError("Missing owner operation must skip maintenance")
            } catch (_: AssumptionViolatedException) { /* Expected explicit opt-in guard. */ }
            InstrumentationRegistry.registerInstance(instrumentation, Bundle().apply {
                putString("operation", "owner-source-cloud-compact")
            })
            try {
                CloudOwnerMigrationTest().compactOwnerDatabaseAfterVerifiedCloudMigration()
                throw AssertionError("Missing verified backup proof must reject maintenance")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("Required value was null"))
            }
        } finally { InstrumentationRegistry.registerInstance(instrumentation, original) }
    }

    private fun expectAssertion(part: String, action: () -> Unit) {
        try { action() } catch (error: AssertionError) {
            assertTrue("Expected the specific guard: $part", error.message.orEmpty().contains(part))
            return
        }
        throw AssertionError("The compaction guard did not reject: $part")
    }
}
