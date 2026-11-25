package io.legado.app.local

import com.google.gson.Gson
import io.legado.app.model.local.SourceRestorePackCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SourceRestorePackCodecTest {
    private val gson = Gson()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun record(kind: String, id: String, raw: String) = SourceRestorePackCodec.Record(kind, id, raw,
        SourceRestorePackCodec.sha256(raw.toByteArray(Charsets.UTF_8)))
    private fun fixtureRecords() = listOf(
        record("book", "", " {\"bookSourceUrl\":\"\",\"bookSourceName\":\"Owned empty identity\",\"enabled\":false} "),
        record("book", "https://owned-pack.invalid/book", "{\"bookSourceUrl\":\"https://owned-pack.invalid/book\",\"bookSourceName\":\"Owned\u2028Unicode\",\"searchUrl\":\"/search?q={{key}}\"}"),
        record("rss", "", "{\"sourceUrl\":\"\",\"sourceName\":\"Owned empty feed\",\"enabled\":true}"),
        record("rss", "https://owned-pack.invalid/rss", "{\"sourceUrl\":\"https://owned-pack.invalid/rss\",\"sourceName\":\"Owned feed\",\"enabled\":false}")
    )
    private fun zip(records: List<SourceRestorePackCodec.Record>, manifestChange: (MutableMap<String, Any>) -> Unit = {}, extra: String? = null): ByteArray {
        val archive = records.joinToString("\n", postfix = "\n") { gson.toJson(it) }.toByteArray(Charsets.UTF_8)
        val manifest = linkedMapOf<String, Any>("format" to "reader-local-source-restore-1", "total" to records.size,
            "books" to records.count { it.kind == "book" }, "rss" to records.count { it.kind == "rss" },
            "archiveBytes" to archive.size, "archiveSha256" to SourceRestorePackCodec.sha256(archive))
        manifestChange(manifest)
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { output ->
            output.putNextEntry(ZipEntry("manifest.json")); output.write(gson.toJson(manifest).toByteArray()); output.closeEntry()
            output.putNextEntry(ZipEntry("sources.jsonl")); output.write(archive); output.closeEntry()
            extra?.let { output.putNextEntry(ZipEntry(it)); output.write("owned".toByteArray()); output.closeEntry() }
        } }.toByteArray()
    }
    private fun encrypted(zip: ByteArray, count: Int = 4): ByteArray {
        val iv = ByteArray(12) { (it + 3).toByte() }
        val header = ByteBuffer.allocate(64).put("RDRPK001".toByteArray()).put(iv)
            .put(java.security.MessageDigest.getInstance("SHA-256").digest(zip)).putLong(zip.size.toLong()).putInt(count).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(header)
        }
        return header + cipher.doFinal(zip)
    }
    private fun withFile(bytes: ByteArray, action: (File) -> Unit) {
        val root = Files.createTempDirectory("owned-pack-codec-").toFile()
        try { val file = root.resolve("owned.zip"); file.writeBytes(bytes); action(file) } finally { root.deleteRecursively() }
    }
    private fun rejects(action: () -> Unit) { assertTrue("Owned invalid package must reject", runCatching(action).isFailure) }

    @Test fun authenticatedMixedKindsPreserveEmptyIdentityUnicodeAndExactRawSha() {
        val records = fixtureRecords(); val bytes = encrypted(zip(records))
        withFile(byteArrayOf()) { file ->
            assertEquals(4, SourceRestorePackCodec.decrypt(ByteArrayInputStream(bytes), key, file))
            val actual = arrayListOf<SourceRestorePackCodec.Record>()
            val summary = SourceRestorePackCodec.scan(file) { actual.add(it) }
            assertEquals(records, actual); assertEquals(4, summary.total); assertEquals(2, summary.books); assertEquals(2, summary.rss)
            assertEquals(records[0].json, actual[0].json)
        }
    }

    @Test fun wrongKeyCiphertextHeaderAndTruncationNeverLeaveADecryptedPackage() {
        val bytes = encrypted(zip(fixtureRecords()))
        val damagedHeader = bytes.copyOf().apply { this[63] = (this[63].toInt() xor 1).toByte() }
        val damagedBody = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for ((input, secret) in listOf(bytes to ByteArray(32), damagedHeader to key, damagedBody to key, bytes.copyOf(bytes.size - 9) to key)) {
            withFile(byteArrayOf()) { file ->
                rejects { SourceRestorePackCodec.decrypt(ByteArrayInputStream(input), secret, file) }
                assertFalse(file.exists())
            }
        }
    }

    @Test fun cancellationRemovesThePartiallyDecryptedFile() {
        withFile(byteArrayOf()) { file ->
            rejects { SourceRestorePackCodec.decrypt(ByteArrayInputStream(encrypted(zip(fixtureRecords()))), key, file) { throw InterruptedException("owned cancellation") } }
            assertFalse(file.exists())
        }
    }

    @Test fun everyRecordShaIdentityKindAndDuplicateIdentityAreValidated() {
        val records = fixtureRecords()
        val invalid = listOf(
            records.mapIndexed { i, r -> if (i == 1) r.copy(sha256 = "0".repeat(64)) else r },
            records.mapIndexed { i, r -> if (i == 1) r.copy(id = "https://other-owned.invalid") else r },
            records.mapIndexed { i, r -> if (i == 1) r.copy(kind = "unknown") else r },
            records + records[1]
        )
        invalid.forEach { bad -> withFile(zip(bad)) { file -> rejects { SourceRestorePackCodec.scan(file) } } }
    }

    @Test fun manifestHashCountsAndExtraZipEntriesAreRejected() {
        val records = fixtureRecords()
        for (bytes in listOf(zip(records, { it["archiveSha256"] = "0".repeat(64) }),
            zip(records, { it["total"] = 5; it["books"] = 3 }),
            zip(records, extra = "../unexpected-owned-file"))) {
            withFile(bytes) { file -> rejects { SourceRestorePackCodec.scan(file) } }
        }
    }
}
