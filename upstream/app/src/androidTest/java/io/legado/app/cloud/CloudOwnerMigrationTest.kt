package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit owner maintenance operation. Never seeds fixtures or clears application data. */
@RunWith(AndroidJUnit4::class)
class CloudOwnerMigrationTest {
    @Test fun migrateOwnerSourcesWithPrivateConfiguration() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("operation") == "owner-source-cloud-migration")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.multiread.reader.debug", context.packageName)
        fun count(table: String): Long = appDb.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
        val expected = linkedMapOf("books" to "expectedShelf", "readRecord" to "expectedHistory",
            "book_sources" to "expectedBookSources", "rssSources" to "expectedRssSources")
            .mapValues { (_, key) -> requireNotNull(arguments.getString(key)).toLong() }
        expected.forEach { (table, value) -> assertEquals("Preflight $table", value, count(table)) }
        assertTrue("Private server configuration is required", CloudReader.isConfigured(context))
        CloudReader.request("/v1/status", context = context)
        val result = CloudReader.migrate(context) { progress ->
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "cloud ${progress.kind}: ${progress.processed}/${progress.total}; verified=${progress.verified}\n")
            })
        }
        // Conversion is UPDATE only. Every existing source and dependent row must survive.
        expected.forEach { (table, value) -> assertEquals("After migration $table", value, count(table)) }
        assertTrue("At least one verified cloud source must be available", result.converted > 0)
        assertTrue(CloudReader.isEnabled(context))
        val output = JsonObject().apply {
            addProperty("uploaded", result.uploaded); addProperty("converted", result.converted)
            addProperty("archived", result.archived); addProperty("archiveCleaned", result.archiveCleaned)
            addProperty("preserved", result.preserved); addProperty("skipped", result.skipped)
        }
        File(context.filesDir, "reader-cloud-migration/owner-result.json").writeText(output.toString())
    }

    /**
     * Opt-in physical compaction only, after the owner has exported and verified
     * the independent Mac backup. This method neither deletes backups nor seeds
     * or edits application rows. Never include its operation in a normal suite.
     * SQLite may change implicit ROWIDs during VACUUM; snapshots compare every
     * declared column ordered by the real primary key, never implicit ROWIDs.
     * https://www.sqlite.org/lang_vacuum.html
     */
    @Test fun compactOwnerDatabaseAfterVerifiedCloudMigration() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("operation") == "owner-source-cloud-compact")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.multiread.reader.debug", context.packageName)
        fun requiredSha(name: String): String = requireNotNull(arguments.getString(name))
            .also { require(it.matches(Regex("[0-9a-f]{64}"))) { "$name must be an explicitly verified SHA256" } }
        val backupSha = requiredSha("backupVerifiedSha256")
        val ownerSha = requiredSha("expectedOwnerResultSha256")
        val expected = linkedMapOf("books" to "expectedShelf", "readRecord" to "expectedHistory",
            "book_sources" to "expectedBookSources", "rssSources" to "expectedRssSources")
            .mapValues { (_, key) -> requireNotNull(arguments.getString(key)).toLong().also { require(it >= 0) } }
        assertTrue("An enabled private cloud configuration is required", CloudReader.isConfigured(context) && CloudReader.isEnabled(context))
        val resultFile = File(context.filesDir, "reader-cloud-migration/owner-result.json")
        assertTrue("A completed owner migration record must remain", resultFile.isFile && resultFile.length() in 1..65_536)
        val ownerBytes = resultFile.readBytes()
        assertEquals("The reviewed owner migration record changed", ownerSha, hash(ownerBytes))
        val migration = JsonParser.parseString(ownerBytes.toString(Charsets.UTF_8)).asJsonObject
        assertEquals("The full source archive must be acknowledged", expected.getValue("book_sources") + expected.getValue("rssSources"), migration.get("uploaded").asLong)
        assertEquals("The migration must have no skipped sources", 0L, migration.get("skipped").asLong)
        assertTrue("At least one source must be converted", migration.get("converted").asLong > 0)

        val db = appDb.openHelper.writableDatabase
        val database = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val compacted = OwnerDatabaseCompaction.compact(db, database, expected)
        assertTrue("Cloud mode changed during compaction", CloudReader.isEnabled(context))
        assertEquals("The migration marker changed during compaction", ownerSha, hash(resultFile.readBytes()))
        val output = JsonObject().apply {
            addProperty("logicalStateVerified", true)
            addProperty("backupVerifiedSha256", backupSha)
            addProperty("ownerResultSha256", ownerSha)
            addProperty("beforeBytes", compacted.beforeBytes); addProperty("afterBytes", compacted.afterBytes)
            addProperty("reclaimedBytes", compacted.beforeBytes - compacted.afterBytes)
            addProperty("beforePages", compacted.beforePages); addProperty("afterPages", compacted.afterPages)
            addProperty("beforeFreePages", compacted.beforeFreePages); addProperty("afterFreePages", compacted.afterFreePages)
            addProperty("verifiedTables", compacted.before.size)
            add("tables", JsonObject().apply {
                compacted.before.forEach { (table, state) -> add(table, JsonObject().apply {
                    addProperty("count", state.count); addProperty("sha256", state.sha256)
                }) }
            })
        }
        File(context.filesDir, "reader-cloud-migration/owner-compaction-result.json").writeText(output.toString())
    }

    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
