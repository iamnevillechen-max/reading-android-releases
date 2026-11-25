package io.legado.app.model.local

import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, file-backed original-source restoration. No source program is executed. */
object SourceRestorePackCodec {
    const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 192L * 1024 * 1024
    private const val MAX_RECORD_CHARS = 4 * 1024 * 1024
    private val magic = "RDRPK001".toByteArray(Charsets.US_ASCII)
    data class Record(val kind: String, val id: String, val json: String, val sha256: String)
    data class Summary(val total: Int, val books: Int, val rss: Int, val payloadSha256: String)
    private val hashPattern = Regex("[a-f0-9]{64}")
    private fun hex(value: ByteArray): String {
        val digits = "0123456789abcdef"; val chars = CharArray(value.size * 2)
        value.forEachIndexed { index, byte -> val v = byte.toInt() and 255; chars[index * 2] = digits[v ushr 4]; chars[index * 2 + 1] = digits[v and 15] }
        return String(chars)
    }
    fun sha256(value: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(value))

    /** Writes only a temporary ZIP; callers cannot import until the final GCM tag is verified. */
    fun decrypt(input: InputStream, key: ByteArray, destination: File, checkCancelled: () -> Unit = {}): Int {
        require(key.size == 32) { "還原連結金鑰不完整" }
        val header = ByteArray(64)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            require(count > 0) { "還原包不完整" }; offset += count
        }
        val buffer = ByteBuffer.wrap(header)
        val signature = ByteArray(8).also { buffer.get(it) }
        require(signature.contentEquals(magic)) { "不是支援的還原包" }
        val iv = ByteArray(12).also { buffer.get(it) }
        val expectedHash = ByteArray(32).also { buffer.get(it) }
        val expectedBytes = buffer.long
        val records = buffer.int
        require(expectedBytes in 1..MAX_ZIP_BYTES && records in 1..50_000) { "還原包大小不正確" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var ciphertextBytes = 0L
            val ciphertextLimit = expectedBytes + 16 // The authenticated ciphertext includes its 128-bit GCM tag.
            // Android GCM may buffer plaintext until doFinal. Reuse one bounded output
            // buffer instead of CipherInputStream reallocating it for every small read.
            val plain = ByteArray(ciphertextLimit.toInt())
            try {
                destination.outputStream().buffered().use { output ->
                    val chunk = ByteArray(64 * 1024)
                    fun emit(count: Int) {
                        bytes += count
                        require(bytes <= expectedBytes) { "還原包大小不符" }
                        if (count > 0) { digest.update(plain, 0, count); output.write(plain, 0, count) }
                    }
                    while (true) {
                        checkCancelled()
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(count > 0) { "還原包讀取中斷" }
                        ciphertextBytes += count
                        require(ciphertextBytes <= ciphertextLimit) { "還原包大小不符" }
                        emit(cipher.update(chunk, 0, count, plain, 0))
                    }
                    require(ciphertextBytes == ciphertextLimit) { "還原包不完整" }
                    checkCancelled()
                    val finalBytes = cipher.doFinal(plain, 0) // Always verify the complete GCM tag explicitly.
                    checkCancelled()
                    emit(finalBytes)
                }
                require(bytes == expectedBytes && MessageDigest.isEqual(digest.digest(), expectedHash)) { "還原包校驗失敗" }
            } finally { plain.fill(0) }
        } catch (failure: Throwable) {
            destination.delete(); throw failure
        }
        return records
    }

    /** Two bounded entries only, no path extraction; the complete archive is checked before database writes. */
    fun scan(file: File, onRecord: (Record) -> Unit = {}): Summary {
        ZipFile(file).use { zip ->
            val names = hashSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory && entry.name in setOf("manifest.json", "sources.jsonl") && names.add(entry.name)) { "還原包檔案不正確" }
            }
            require(names.size == 2) { "還原包缺少資料" }
            val manifestEntry = zip.getEntry("manifest.json")
            require(manifestEntry.size in 1..8192) { "還原包說明不正確" }
            val manifest = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject
            }
            require(manifest.get("format")?.asString == "reader-local-source-restore-1") { "還原包版本不正確" }
            val expectedTotal = manifest.get("total").asInt
            val expectedBooks = manifest.get("books").asInt
            val expectedRss = manifest.get("rss").asInt
            val expectedBytes = manifest.get("archiveBytes").asLong
            val expectedHash = manifest.get("archiveSha256").asString
            require(expectedTotal in 1..50_000 && expectedBooks >= 0 && expectedRss >= 0 && expectedBooks + expectedRss == expectedTotal) { "還原包筆數不正確" }
            val archive = zip.getEntry("sources.jsonl")
            require(expectedBytes in 1..MAX_ARCHIVE_BYTES && archive.size == expectedBytes) { "還原資料大小不正確" }
            val ids = hashSetOf<String>()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0; var books = 0; var rss = 0
            DigestInputStream(zip.getInputStream(archive), digest).bufferedReader(Charsets.UTF_8).use { reader ->
                boundedLines(reader) { line ->
                    require(line.length <= MAX_RECORD_CHARS && line.isNotBlank()) { "還原資料格式不正確" }
                    val value = JsonParser.parseString(line).asJsonObject
                    val record = Record(value.get("kind").asString, value.get("id").asString,
                        value.get("json").asString, value.get("sha256").asString)
                    require(record.kind == "book" || record.kind == "rss") { "還原資料類型不正確" }
                    require(record.id.length <= 16384 && ids.add(record.kind + "\u0000" + record.id)) { "還原來源身份重複" }
                    require(record.json.length <= MAX_RECORD_CHARS && record.sha256.matches(hashPattern) && sha256(record.json.toByteArray(Charsets.UTF_8)) == record.sha256) { "原始規則校驗失敗" }
                    val original = JsonParser.parseString(record.json).asJsonObject
                    val identity = original.get(if (record.kind == "book") "bookSourceUrl" else "sourceUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    require(identity == record.id) { "原始來源身份不符" }
                    total++; if (record.kind == "book") books++ else rss++
                    require(total <= expectedTotal) { "還原資料筆數超出說明" }
                    onRecord(record)
                }
            }
            require(total == expectedTotal && books == expectedBooks && rss == expectedRss && hex(digest.digest()) == expectedHash) { "完整還原資料校驗失敗" }
            return Summary(total, books, rss, expectedHash)
        }
    }
    private fun boundedLines(reader: Reader, consume: (String) -> Unit) {
        val chars = CharArray(16 * 1024); val line = StringBuilder()
        while (true) {
            val count = reader.read(chars); if (count < 0) break
            val chunk = String(chars, 0, count)
            var start = 0
            while (start < chunk.length) {
                val newline = chunk.indexOf('\n', start)
                val end = if (newline < 0) chunk.length else newline
                require(line.length + end - start <= MAX_RECORD_CHARS) { "還原資料單筆過大" }
                line.append(chunk, start, end)
                if (newline < 0) break
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                consume(line.toString()); line.setLength(0)
                start = newline + 1
            }
        }
        if (line.isNotEmpty()) consume(line.toString())
    }
    fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return hex(digest.digest())
    }
}
