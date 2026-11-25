package io.legado.app.update.reader

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import io.legado.app.help.update.reader.ReaderApkVerifier
import io.legado.app.help.update.reader.ReaderRelease
import io.legado.app.help.update.reader.ReaderTransfer
import io.legado.app.help.update.reader.ReaderUpdatePolicy
import io.legado.app.help.update.reader.ReaderUpdateStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ReaderUpdateIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun reopeningUsesRealPartialBytesAndCancelledGenerationCannotPublish() = ownedStore { store ->
        val release = release(10, "abcdef".toByteArray())
        val job = store.begin(release)
        store.partial(release).apply { parentFile!!.mkdirs(); writeText("abc") }
        store.update(job, "downloading", 1, "\"owned-etag\"")
        val reopened = ReaderUpdateStore(context.packageName,
            context.getSharedPreferences(store.directory.name, Context.MODE_PRIVATE), store.directory)
        reopened.reconcile(false)
        assertEquals("paused", reopened.snapshot().state)
        assertEquals(3L, reopened.snapshot().downloaded)
        assertEquals("\"owned-etag\"", reopened.snapshot().validator)
        reopened.cancel(job)
        assertFalse(reopened.update(job, "ready", 6))
        assertEquals("cancelled", reopened.snapshot().state)
        val resumed = reopened.begin(release)
        assertNotEquals(job, resumed)
        assertEquals(3L, reopened.snapshot().downloaded)
        assertEquals("\"owned-etag\"", reopened.snapshot().validator)
        assertFalse(reopened.update(job, "downloading", 0))
        assertEquals(resumed, reopened.snapshot().jobId)
    }

    @Test fun failedReplacementKeepsEarlierReadyFileAndPointer() = ownedStore { store ->
        val oldPayload = "owned previously verified fixture".toByteArray()
        val old = release(10, oldPayload)
        val oldJob = store.begin(old)
        store.partial(old).apply { parentFile!!.mkdirs(); writeBytes(oldPayload) }
        assertTrue(store.promote(oldJob, old))
        val newerPayload = "owned replacement fixture".toByteArray()
        val newer = release(11, newerPayload)
        val newJob = store.begin(newer)
        store.partial(newer).apply { parentFile!!.mkdirs(); writeBytes(newerPayload) }
        store.cancel(newJob)
        assertFalse(store.promote(newJob, newer))
        assertEquals(old.sha256, store.snapshot().ready?.sha256)
        assertArrayEquals(oldPayload, store.readyFile(old).readBytes())
        assertFalse(store.readyFile(newer).exists())
    }

    @Test fun actualPackageManagerArchiveAndSignerDataRejectReinstallOrWrongMetadata() {
        assumeTrue(Build.VERSION.SDK_INT >= 24)
        val apk = File(context.applicationInfo.sourceDir)
        val actual = ReaderApkVerifier.inspect(context, apk)
        val installed = ReaderApkVerifier.installed(context)
        assertEquals(context.packageName, actual.packageName)
        assertEquals(ReaderApkVerifier.versionCode(installed), actual.versionCode)
        assertTrue(ReaderTransfer.sameSigners(ReaderApkVerifier.signers(installed), actual.signers))
        val metadata = metadata(actual.versionCode, apk.length(), hashFile(apk), actual.versionName, actual.minSdk)
        ReaderApkVerifier.requireMatching(metadata, actual, actual.versionCode - 1,
            Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)
        try { ReaderApkVerifier.validate(context, apk, metadata); fail("Reinstall was offered as an update") }
        catch (_: SecurityException) { }
        val wrong = actual.copy(packageName = "owned.wrong.package")
        try {
            ReaderApkVerifier.requireMatching(metadata, wrong, actual.versionCode - 1, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)
            fail("Wrong package accepted")
        } catch (_: SecurityException) { }
    }

    @Test fun sameLengthCorruptReadyCanBeRevokedAndRecoveredThroughRealByteStream() = ownedStore { store ->
        val payload = "owned update stream ${System.nanoTime()}".repeat(200).toByteArray()
        val release = release(10, payload)
        val first = store.begin(release)
        store.partial(release).apply { parentFile!!.mkdirs(); writeBytes(payload) }
        assertTrue(store.promote(first, release))
        val saved = store.readyFile(release)
        val corrupt = payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        saved.writeBytes(corrupt)
        assertEquals(payload.size.toLong(), saved.length())
        try { ReaderTransfer.verifyHash(saved, release.bytes, release.sha256); fail("Equal-size damage was accepted") }
        catch (_: SecurityException) { }
        assertFalse(store.revokeReady("0".repeat(64)))
        assertTrue(store.revokeReady(release.sha256))
        assertNull(store.snapshot().ready)
        assertTrue(saved.exists())
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", ByteArrayInputStream(payload), payload.size.toLong())
        }
        server.start()
        try {
            val retry = store.begin(release)
            val connection = ReaderTransfer.Connections { logical ->
                URI("http://127.0.0.1:${server.listeningPort}${logical.rawPath}").toURL().openConnection() as HttpURLConnection
            }
            ReaderTransfer.download(release, store.partial(release), "", { false }, { _, _ -> }, connections = connection)
            assertTrue(store.promote(retry, release))
            assertEquals(release.sha256, store.snapshot().ready?.sha256)
            assertArrayEquals(payload, saved.readBytes())
        } finally { server.stop() }
    }

    private fun ownedStore(test: (ReaderUpdateStore) -> Unit) {
        val name = "reader-update-owned-${System.nanoTime()}"
        val directory = File(context.cacheDir, name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try { test(ReaderUpdateStore(context.packageName, prefs, directory)) }
        finally {
            directory.deleteRecursively()
            if (Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name) else prefs.edit().clear().commit()
        }
    }

    private fun release(code: Long, bytes: ByteArray) = metadata(code, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    private fun metadata(code: Long, bytes: Long, sha: String, version: String = "owned-test", sdk: Int = 24): ReaderRelease {
        val json = JsonObject().apply {
            addProperty("packageName", context.packageName)
            addProperty("versionCode", code)
            addProperty("versionName", version)
            addProperty("minSdk", sdk)
            addProperty("bytes", bytes)
            addProperty("sha256", sha)
            addProperty("apkUrl", "https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/owned-test/reading.apk")
            addProperty("releasePageUrl", ReaderUpdatePolicy.RELEASE_PAGE)
        }
        return ReaderRelease.parse(json.toString(), context.packageName)
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
