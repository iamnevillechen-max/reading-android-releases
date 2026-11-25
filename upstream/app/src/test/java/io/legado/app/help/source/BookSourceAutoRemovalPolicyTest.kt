package io.legado.app.help.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceAutoRemovalPolicyTest {
    private val chapter = "https://books.example.org/chapter/1".toHttpUrl()

    @Test
    fun entryRequiresSameOriginAndDifferentPathAndStripsOnlyTheFragment() {
        assertEquals("https://books.example.org/", BookSourceAutoRemoval.entryFor(
            "https://books.example.org/#owned-source", chapter.toString())?.toString())
        assertEquals("https://books.example.org/entry", BookSourceAutoRemoval.entryFor(
            "https://books.example.org:443/entry#owned-source", chapter.toString())?.toString())
        for (entry in listOf("http://books.example.org/", "https://other.example.org/",
            "https://books.example.org:444/", "https://books.example.org/chapter/1#source",
            "https://books.example.org/entry?token=private", "https://user:secret@books.example.org/")) {
            assertNull(entry, BookSourceAutoRemoval.entryFor(entry, chapter.toString()))
        }
        assertNull(BookSourceAutoRemoval.entryFor("https://books.example.org/",
            "https://books.example.org/?different=query"))
    }

    @Test
    fun originalMissingAcceptsOnlyMatching404Or410FromTheExactPublicGetRequest() {
        val request = Request.Builder().url(chapter).header("User-Agent", "Owned fixture")
            .header("Accept", "text/html").header("Accept-Language", "zh")
            .header("Accept-Encoding", "gzip").header("Connection", "Keep-Alive")
            .header("Cache-Control", "no-cache").build()
        assertTrue(BookSourceAutoRemoval.originalMissing(networkResponse(request), chapter))
        assertFalse(BookSourceAutoRemoval.originalMissing(networkResponse(request),
            "https://books.example.org/chapter/2".toHttpUrl()))
        assertFalse(BookSourceAutoRemoval.originalMissing(networkResponse(request,
            network = bareResponse(request, 404)), chapter))
        assertTrue(BookSourceAutoRemoval.originalMissing(networkResponse(request, status = 404), chapter))
        val otherRequest = request.newBuilder().url("https://books.example.org/chapter/2").build()
        assertFalse(BookSourceAutoRemoval.originalMissing(networkResponse(request,
            network = bareResponse(otherRequest, 410)), chapter))
    }

    @Test
    fun syntheticCachedRedirectedAuthAndOtherFailuresCannotAuthorizeRemoval() {
        val request = Request.Builder().url(chapter).build()
        for (status in listOf(200, 410)) {
            assertFalse("synthetic $status", BookSourceAutoRemoval.originalMissing(bareResponse(request, status), chapter))
        }
        for (status in listOf(200, 403, 429, 500, 502, 503, 504)) {
            assertFalse("HTTP $status", BookSourceAutoRemoval.originalMissing(networkResponse(request, status), chapter))
        }
        val gone = networkResponse(request)
        assertFalse(BookSourceAutoRemoval.originalMissing(gone.newBuilder()
            .priorResponse(bareResponse(request, 301)).build(), chapter))
        assertFalse(BookSourceAutoRemoval.originalMissing(gone.newBuilder()
            .cacheResponse(bareResponse(request, 410)).build(), chapter))
        for (header in listOf("Authorization", "Proxy-Authorization", "Cookie", "X-Api-Key")) {
            val privateRequest = request.newBuilder().header(header, "owned-fixture-secret").build()
            assertFalse(header, BookSourceAutoRemoval.originalMissing(networkResponse(privateRequest), chapter))
            assertFalse("network $header", BookSourceAutoRemoval.originalMissing(networkResponse(request,
                network = bareResponse(privateRequest, 410)), chapter))
        }
        for (header in listOf("WWW-Authenticate", "Location")) {
            assertFalse(header, BookSourceAutoRemoval.originalMissing(gone.newBuilder()
                .header(header, "owned-fixture").build(), chapter))
            assertFalse("network $header", BookSourceAutoRemoval.originalMissing(networkResponse(request,
                network = bareResponse(request, 410).newBuilder()
                    .header(header, "owned-fixture").build()), chapter))
        }
        val head = request.newBuilder().method("HEAD", null).build()
        assertFalse(BookSourceAutoRemoval.originalMissing(networkResponse(head), chapter))
    }

    private fun bareResponse(request: Request, status: Int): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Owned fixture").build()

    private fun networkResponse(request: Request, status: Int = 410,
        network: Response = bareResponse(request, status)): Response = bareResponse(request, status)
        .newBuilder().networkResponse(network).build()
}
