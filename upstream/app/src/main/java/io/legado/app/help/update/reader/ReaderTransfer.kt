package io.legado.app.help.update.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** The injectable socket factory changes transport only; every logical URL still passes policy. */
object ReaderTransfer {
    fun interface Connections { fun open(uri: URI): HttpURLConnection }
    val NETWORK = Connections { it.toURL().openConnection() as HttpURLConnection }
    private val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")

    fun fetchManifest(expectedPackage: String, connections: Connections = NETWORK): ReaderRelease {
        val deadline = System.nanoTime() + 45_000_000_000L
        val connection = open(URI(ReaderUpdatePolicy.MANIFEST_URL), connections, { false }, {}, deadline) {}
        try {
            if (connection.responseCode != 200) throw IOException("metadata_http")
            if (contentLengthHeader(connection.getHeaderField("Content-Length")) > ReaderRelease.MAX_METADATA_BYTES) throw IOException("metadata_size")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    checkCancelled { false }
                    if (System.nanoTime() > deadline) throw IOException("metadata_timeout")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > ReaderRelease.MAX_METADATA_BYTES) throw IOException("metadata_size")
                    output.write(buffer, 0, count)
                }
            }
            return ReaderRelease.parse(output.toString("UTF-8"), expectedPackage)
        } finally { connection.disconnect() }
    }

    fun download(
        release: ReaderRelease,
        partial: File,
        validator: String,
        cancelled: () -> Boolean,
        progress: (Long, String) -> Unit,
        connectionChanged: (HttpURLConnection?) -> Unit = {},
        connections: Connections = NETWORK
    ) {
        ReaderUpdatePolicy.apk(release.apkUrl)
        checkCancelled(cancelled)
        partial.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("download_directory") }
        var offset = if (partial.isFile) partial.length() else 0L
        if (offset > release.bytes) { partial.delete(); offset = 0 }
        if (offset == release.bytes) {
            try { verifyHash(partial, release.bytes, release.sha256); progress(offset, safeValidator(validator)); return }
            catch (_: SecurityException) { if (!partial.delete()) throw IOException("download_partial"); offset = 0 }
        }
        val savedValidator = safeValidator(validator)
        val deadline = System.nanoTime() + 15L * 60 * 1_000_000_000
        val connection = open(URI(release.apkUrl), connections, cancelled, connectionChanged) {
            if (offset > 0) {
                it.setRequestProperty("Range", "bytes=$offset-")
                if (savedValidator.isNotEmpty()) it.setRequestProperty("If-Range", savedValidator)
            }
        }
        try {
            val code = connection.responseCode
            checkCancelled(cancelled)
            if (code != 200 && code != 206) throw IOException("download_http")
            val responseBytes: Long
            if (code == 206) {
                val match = range.matchEntire(connection.getHeaderField("Content-Range") ?: "")
                    ?: throw IOException("download_range")
                val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: throw IOException("download_range") }
                if (values[0] != offset || values[1] != release.bytes - 1 || values[2] != release.bytes)
                    throw IOException("download_range")
                responseBytes = release.bytes - offset
            } else { offset = 0; responseBytes = release.bytes }
            val length = contentLengthHeader(connection.getHeaderField("Content-Length"))
            if (length >= 0 && length != responseBytes) throw IOException("download_length")
            val currentValidator = safeValidator(connection.getHeaderField("ETag")).ifEmpty {
                safeValidator(connection.getHeaderField("Last-Modified"))
            }.ifEmpty { if (code == 206) savedValidator else "" }
            connection.inputStream.use { input ->
                FileOutputStream(partial, code == 206 && offset > 0).use { output ->
                    var countSoFar = offset
                    progress(countSoFar, currentValidator)
                    val buffer = ByteArray(65_536)
                    try {
                        while (true) {
                            checkCancelled(cancelled)
                            if (System.nanoTime() > deadline) throw IOException("download_timeout")
                            val count = input.read(buffer)
                            if (count < 0) break
                            checkCancelled(cancelled)
                            if (countSoFar + count > release.bytes) throw SecurityException("download_oversize")
                            output.write(buffer, 0, count)
                            countSoFar += count
                            progress(countSoFar, currentValidator)
                        }
                        if (countSoFar != release.bytes) throw IOException("download_incomplete")
                    } finally { output.fd.sync() }
                }
            }
            checkCancelled(cancelled)
            verifyHash(partial, release.bytes, release.sha256)
            checkCancelled(cancelled)
            progress(release.bytes, currentValidator)
        } catch (invalid: SecurityException) {
            partial.delete()
            throw invalid
        } finally { connectionChanged(null); connection.disconnect() }
    }

    private fun open(
        start: URI, connections: Connections, cancelled: () -> Boolean,
        changed: (HttpURLConnection?) -> Unit,
        deadline: Long = System.nanoTime() + 45_000_000_000L,
        configure: (HttpURLConnection) -> Unit
    ): HttpURLConnection {
        var uri = start
        repeat(6) { redirects ->
            checkCancelled(cancelled)
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong())
            if (remainingMillis <= 0) throw IOException("connection_timeout")
            ReaderUpdatePolicy.redirect(uri)
            val connection = connections.open(uri)
            changed(connection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(15_000L, remainingMillis).toInt().coerceAtLeast(1)
                connection.readTimeout = minOf(20_000L, remainingMillis).toInt().coerceAtLeast(1)
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Reading-Updater/1")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                configure(connection)
                val code = connection.responseCode
                checkCancelled(cancelled)
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("redirect_location")
                    if (redirects == 5) throw IOException("redirect_limit")
                    uri = ReaderUpdatePolicy.redirect(uri.resolve(location))
                    changed(null)
                    connection.disconnect()
                } else return connection
            } catch (error: Exception) { changed(null); connection.disconnect(); throw error }
        }
        throw IOException("redirect_limit")
    }

    fun verifyHash(file: File, bytes: Long, sha256: String) {
        if (bytes !in 1..ReaderRelease.MAX_APK_BYTES || !file.isFile || file.length() != bytes ||
            !sha256.matches(Regex("[0-9a-f]{64}"))) throw SecurityException("apk_length")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var total = 0L
            while (true) {
                checkCancelled { false }
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > bytes) throw SecurityException("apk_length")
                digest.update(buffer, 0, count)
            }
            if (total != bytes || !MessageDigest.isEqual(hex(sha256), digest.digest())) throw SecurityException("apk_sha256")
        }
    }

    fun sameSigners(installed: List<ByteArray>, archive: List<ByteArray>): Boolean {
        if (installed.size !in 1..8 || installed.size != archive.size) return false
        fun hashes(values: List<ByteArray>): Set<String>? {
            if (values.any { it.isEmpty() || it.size > 32_768 }) return null
            return values.map { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }.toSet()
        }
        val expected = hashes(installed) ?: return false
        val actual = hashes(archive) ?: return false
        return expected.size == installed.size && actual.size == archive.size && expected == actual
    }

    // URLConnection.getContentLengthLong requires API 24. Checking metadata also runs on API 21–23.
    // Missing or malformed lengths remain unknown; the byte-count limits are enforced while reading.
    internal fun contentLengthHeader(value: String?): Long = value?.trim()?.takeIf {
        it.length in 1..19 && it.all { character -> character in '0'..'9' }
    }?.toLongOrNull() ?: -1L

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun safeValidator(value: String?) = value?.takeIf {
        it.length <= 512 && !it.startsWith("W/") && it.none { c -> c.code < 32 || c.code == 127 }
    } ?: ""
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedIOException("download_cancelled")
    }
}
