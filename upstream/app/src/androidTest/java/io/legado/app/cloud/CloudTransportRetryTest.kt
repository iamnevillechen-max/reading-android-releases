package io.legado.app.cloud

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** A real socket consumes a POST and drops the connection before acknowledging it. */
@RunWith(AndroidJUnit4::class)
class CloudTransportRetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun fixture(server: DroppedAcknowledgement, action: suspend () -> Unit) {
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val preferences = context.getSharedPreferences("reader_cloud_v1", Context.MODE_PRIVATE)
        check(preferences.all.isEmpty()) { "Owned transport fixture requires unconfigured cloud preferences" }
        try {
            CloudReader.configure(context, "http://127.0.0.1:${server.port}", UUID.randomUUID().toString())
            action()
        } finally {
            server.close()
            preferences.edit().clear().commit()
        }
    }

    @Test(timeout = 15_000) fun consumedImportRetriesTheExactImmutableBodyAndReceivesAcknowledgement() = runBlocking {
        val server = DroppedAcknowledgement(dropFirst = true)
        fixture(server) {
            val body = JsonObject().apply { addProperty("kind", "book"); addProperty("owned", "self-written import body") }
            assertTrue(CloudReader.request("/v1/import", body).asJsonObject["received"].asBoolean)
            assertTrue(server.done.await(3, TimeUnit.SECONDS))
            server.failure.get()?.let { throw AssertionError("Owned socket fixture failed", it) }
            assertEquals(2, server.bodies.size)
            assertArrayEquals(server.bodies[0], server.bodies[1])
        }
    }

    @Test(timeout = 15_000) fun cancellationStopsThePendingRetry() = runBlocking {
        val server = DroppedAcknowledgement(dropFirst = true)
        fixture(server) {
            val job = launch(Dispatchers.IO) { CloudReader.request("/v1/import", JsonObject().apply { addProperty("owned", true) }) }
            assertTrue(server.firstConsumed.await(3, TimeUnit.SECONDS))
            delay(50)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(server.done.await(3, TimeUnit.SECONDS))
            assertEquals(1, server.bodies.size)
        }
    }

    @Test(timeout = 15_000) fun verificationMutationsAndHttpErrorsAreNeverRetried() = runBlocking {
        for ((path, status) in listOf("/v1/verify" to 200, "/v1/import" to 503)) {
            val server = DroppedAcknowledgement(dropFirst = status == 200, responseStatus = status)
            fixture(server) {
                var failed = false
                try { CloudReader.request(path, JsonObject().apply { addProperty("owned", true) }) }
                catch (_: IOException) { failed = true }
                assertTrue(failed)
                assertTrue(server.done.await(3, TimeUnit.SECONDS))
                assertEquals(1, server.bodies.size)
            }
        }
    }

    private class DroppedAcknowledgement(private val dropFirst: Boolean, private val responseStatus: Int = 200) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
        val port = server.localPort
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val firstConsumed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "owned-cloud-socket") {
            try {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { break }
                    socket.use {
                        it.soTimeout = 2_000
                        val input = it.getInputStream()
                        val header = ByteArrayOutputStream()
                        while (true) {
                            val value = input.read(); check(value >= 0)
                            header.write(value)
                            val data = header.toByteArray()
                            check(data.size <= 32_768)
                            if (data.size >= 4 && data.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                        }
                        val length = Regex("(?im)^content-length:\\s*(\\d+)").find(header.toString("ISO-8859-1"))?.groupValues?.get(1)?.toInt() ?: 0
                        check(length in 0..65_536)
                        val body = ByteArray(length)
                        var position = 0
                        while (position < length) {
                            val count = input.read(body, position, length - position); check(count > 0); position += count
                        }
                        bodies.add(body); firstConsumed.countDown()
                        if (dropFirst && bodies.size == 1) return@use
                        val response = "{\"data\":{\"received\":true}}".toByteArray(Charsets.UTF_8)
                        val output = it.getOutputStream()
                        output.write(("HTTP/1.1 $responseStatus Owned\r\nRetry-After: 0\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(response); output.flush()
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            } finally { done.countDown() }
        }
        override fun close() {
            server.close(); worker.join(2_500)
        }
    }
}
