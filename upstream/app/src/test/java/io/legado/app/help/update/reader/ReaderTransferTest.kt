package io.legado.app.help.update.reader

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback byte streams. Approved logical URLs remain policy-checked before transport injection. */
class ReaderTransferTest {
    private val payload = ByteArray(180_000) { (it % 251).toByte() }
    private fun release(bytes: ByteArray = payload, sha: String = hash(bytes)) = ReaderRelease.parse(
        ReaderReleaseTest.json(bytes.size.toLong(), sha), ReaderReleaseTest.PACKAGE)

    @Test fun realChunkedStreamReportsProgressAndChecksExactBytes() = fixture({ chunked(payload) }) { file, connections ->
        val progress = mutableListOf<Long>()
        ReaderTransfer.download(release(), file, "", { false }, { bytes, _ -> progress.add(bytes) }, connections = connections)
        assertArrayEquals(payload, file.readBytes())
        assertTrue(progress.any { it in 1 until payload.size.toLong() })
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test fun brokenConnectionLeavesBytesAndNextRequestResumesWithRange() {
        val calls = AtomicInteger()
        val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
        fixture({ session ->
            headers.add(session.headers["range"] to session.headers["if-range"])
            if (calls.getAndIncrement() == 0) {
                // Announce the full APK but close the actual socket after a short body.
                fixed(payload.copyOf(32_000), declaredBytes = payload.size.toLong())
            } else {
                val offset = requireNotNull(session.headers["range"]).removePrefix("bytes=").removeSuffix("-").toInt()
                fixed(payload.copyOfRange(offset, payload.size), NanoHTTPD.Response.Status.PARTIAL_CONTENT).apply {
                    addHeader("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
                }
            }.apply { addHeader("ETag", "\"owned-fixture\"") }
        }) { file, connections ->
            var validator = ""
            try {
                ReaderTransfer.download(release(), file, "", { false }, { _, value -> validator = value }, connections = connections)
                fail("Truncated response was accepted")
            } catch (_: IOException) { }
            val saved = file.length()
            assertTrue(saved in 1 until payload.size.toLong())
            ReaderTransfer.download(release(), file, validator, { false }, { _, _ -> }, connections = connections)
            assertEquals("bytes=$saved-", headers[1].first)
            assertEquals("\"owned-fixture\"", headers[1].second)
            assertArrayEquals(payload, file.readBytes())
        }
    }

    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = fixture({ fixed(payload) }) { file, connections ->
        file.writeBytes(payload.copyOf(1234))
        ReaderTransfer.download(release(), file, "", { false }, { _, _ -> }, connections = connections)
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun checksumFailureAndOversizeNeverReplaceExistingUsableFile() = fixture({ chunked(payload + byteArrayOf(7)) }) { file, connections ->
        val ready = File(file.parentFile, "previous-verified.apk").apply { writeText("usable old APK fixture") }
        try { ReaderTransfer.download(release(), file, "", { false }, { _, _ -> }, connections = connections); fail() }
        catch (_: SecurityException) { }
        assertFalse(file.exists())
        assertEquals("usable old APK fixture", ready.readText())
        file.writeBytes(payload)
        try { ReaderTransfer.verifyHash(file, payload.size.toLong(), "0".repeat(64)); fail() }
        catch (_: SecurityException) { }
    }

    @Test fun cancellationRetainsPartialAndRejectsLateSuccess() = fixture({ fixed(payload) }) { file, connections ->
        val stop = AtomicBoolean()
        try {
            ReaderTransfer.download(release(), file, "", { stop.get() }, { bytes, _ -> if (bytes > 0) stop.set(true) }, connections = connections)
            fail("Cancelled transfer returned success")
        } catch (_: InterruptedIOException) { }
        assertTrue(file.length() in 1..payload.size.toLong())
    }

    @Test fun unsafeRedirectIsRejectedBeforeAnotherConnection() {
        val calls = AtomicInteger()
        fixture({
            calls.incrementAndGet()
            fixed(byteArrayOf(), NanoHTTPD.Response.Status.FOUND).apply {
                addHeader("Location", "https://untrusted.example/reading.apk")
            }
        }) { file, connections ->
            try { ReaderTransfer.download(release(), file, "", { false }, { _, _ -> }, connections = connections); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, calls.get())
            assertFalse(file.exists())
        }
    }

    @Test fun streamedMetadataHasHardSizeLimit() = fixture({ chunked(ByteArray(70_000) { 32 }) }) { _, connections ->
        try { ReaderTransfer.fetchManifest(ReaderReleaseTest.PACKAGE, connections); fail() }
        catch (expected: IOException) { assertEquals("metadata_size", expected.message) }
    }

    @Test fun contentLengthHeaderKeepsUnknownAndOverflowOutOfByteAccounting() {
        assertEquals(0L, ReaderTransfer.contentLengthHeader("0"))
        assertEquals(180_000L, ReaderTransfer.contentLengthHeader(" 180000 "))
        assertEquals(Long.MAX_VALUE, ReaderTransfer.contentLengthHeader(Long.MAX_VALUE.toString()))
        listOf(null, "", "-1", "+1", "1, 2", "1.0", "9223372036854775808").forEach {
            assertEquals(-1L, ReaderTransfer.contentLengthHeader(it))
        }
    }

    @Test fun metadataCheckNeverCallsTheApi24ContentLengthMethod() {
        val json = ReaderReleaseTest.json(payload.size.toLong(), hash(payload))
        val bytes = json.toByteArray(Charsets.UTF_8)
        val connections = ReaderTransfer.Connections {
            object : HttpURLConnection(URL("https://github.com")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getHeaderField(name: String?) = if (name.equals("Content-Length", true)) bytes.size.toString() else null
                override fun getContentLengthLong(): Long = throw AssertionError("API 24 method called")
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        }
        assertEquals(payload.size.toLong(), ReaderTransfer.fetchManifest(ReaderReleaseTest.PACKAGE, connections).bytes)
    }

    private fun fixed(bytes: ByteArray, status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.OK,
        declaredBytes: Long = bytes.size.toLong()): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/octet-stream", ByteArrayInputStream(bytes), declaredBytes)

    private fun chunked(bytes: ByteArray): NanoHTTPD.Response = NanoHTTPD.newChunkedResponse(
        NanoHTTPD.Response.Status.OK, "application/octet-stream", object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 4096))
        })

    private fun fixture(handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response, test: (File, ReaderTransfer.Connections) -> Unit) {
        val directory = Files.createTempDirectory("reader-update-transfer").toFile()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = handler(session).apply {
                // Closing each response makes intentionally truncated bodies immediately observable.
                closeConnection(true)
                setKeepAlive(false)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val connections = ReaderTransfer.Connections { logical ->
            URI("http://127.0.0.1:${server.listeningPort}${logical.rawPath}").toURL().openConnection() as HttpURLConnection
        }
        try { test(File(directory, "candidate.part"), connections) }
        finally { server.stop(); directory.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
