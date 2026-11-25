package io.legado.app.help.update.reader

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReaderStartupPolicyTest {
    @Test fun firstOpenChecksAndActivitySwitchesOrRotationDoNotCreateNewChecks() {
        val policy = ReaderStartupPolicy()
        policy.started()
        assertTrue(policy.needsCheck())
        assertEquals(0L, policy.delayUntilCheck(1_000))
        policy.attempted(1_000)
        policy.started() // Book opens before Main stops.
        policy.stopped(false)
        assertFalse(policy.needsCheck())
        policy.stopped(true) // Recreating the only started activity.
        policy.started()
        assertFalse(policy.needsCheck())
    }

    @Test fun trueForegroundSessionsCheckAgainAfterOnlyAShortGap() {
        val policy = ReaderStartupPolicy()
        policy.started(); policy.attempted(1_000)
        policy.stopped(false); policy.started()
        assertTrue(policy.needsCheck())
        assertEquals(50_000L, policy.delayUntilCheck(11_000))
        assertEquals(0L, policy.delayUntilCheck(61_000))
        policy.attempted(61_000)
        assertFalse(policy.needsCheck())
    }

    @Test fun aCheckCompletingInTheNextForegroundSessionDoesNotImmediatelyDuplicateIt() {
        val policy = ReaderStartupPolicy()
        policy.started(); policy.attempted(1_000)
        policy.stopped(false); policy.started()
        assertTrue(policy.needsCheck())
        policy.completed()
        assertFalse(policy.needsCheck())
    }

    @Test fun promptsAreOncePerVersionPerForegroundAndClockRollbackDoesNotSuppressChecks() {
        val policy = ReaderStartupPolicy()
        policy.started(); policy.attempted(100_000)
        assertTrue(policy.mayPrompt(10))
        policy.prompted(10)
        assertFalse(policy.mayPrompt(10))
        assertTrue(policy.mayPrompt(11))
        policy.stopped(false); policy.started()
        assertTrue(policy.mayPrompt(10))
        assertEquals(0L, policy.delayUntilCheck(5))
    }

    @Test fun promptsWaitForMainAndNeverInterruptReaderOrSpeech() {
        assertTrue(ReaderUpdateUiPolicy.mayPrompt(onMain = true, readingAloud = false))
        assertFalse(ReaderUpdateUiPolicy.mayPrompt(onMain = false, readingAloud = false))
        assertFalse(ReaderUpdateUiPolicy.mayPrompt(onMain = true, readingAloud = true))
        assertFalse(ReaderUpdateUiPolicy.mayPrompt(onMain = false, readingAloud = true))
    }

    @Test fun olderReadyIsHiddenForANewerCompatibleReleaseButNeverMisrepresented() {
        val ready = release(10)
        val latest = release(11)
        assertNull(ReaderUpdateUiPolicy.installableReady(latest, ready, 9, 36, arrayOf("arm64-v8a")))
        assertEquals(ready, ReaderUpdateUiPolicy.installableReady(ready, ready, 9, 36, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.installableReady(ready, ready, 10, 36, arrayOf("arm64-v8a")))
        assertFalse(ReaderUpdateUiPolicy.compatibleNew(latest, 9, 23, arrayOf("arm64-v8a")))
        val unsupportedLatest = release(12, sdk = 100)
        assertEquals(ready, ReaderUpdateUiPolicy.installableReady(unsupportedLatest, ready, 9, 36, arrayOf("arm64-v8a")))
    }

    @Test fun delayedDownloadConsentCannotSelectADifferentReleaseOrAnInstalledVersion() {
        val selected = release(10)
        val changed = JsonParser.parseString(selected.json).asJsonObject.apply {
            addProperty("sha256", "b".repeat(64))
        }.let { ReaderRelease.parse(it.toString(), ReaderReleaseTest.PACKAGE) }
        assertEquals(selected, ReaderUpdateUiPolicy.requestedDownload(selected, selected.sha256,
            9, 36, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.requestedDownload(changed, selected.sha256,
            9, 36, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.requestedDownload(selected, null, 9, 36, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.requestedDownload(selected, selected.sha256, 10, 36, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.requestedDownload(selected, selected.sha256, 9, 23, arrayOf("arm64-v8a")))
        assertNull(ReaderUpdateUiPolicy.requestedDownload(null, selected.sha256, 9, 36, arrayOf("arm64-v8a")))
    }

    @Test fun promptShowsEveryPublishedFixWithoutInventingMissingNotes() {
        val notes = "1. Fix truncated chapter recovery.\n2. Preserve reading position.\n3. Keep the same background download."
        assertEquals("Version 1.2.6\n\nProblems fixed\n$notes",
            ReaderUpdateUiPolicy.description("Version 1.2.6", "Problems fixed", " $notes\n", "No notes provided"))
        assertEquals("Version 1.2.6\n\nProblems fixed\nNo notes provided",
            ReaderUpdateUiPolicy.description("Version 1.2.6", "Problems fixed", " \n", "No notes provided"))
    }

    private fun release(code: Long, sdk: Int = 24): ReaderRelease {
        val json = JsonParser.parseString(ReaderReleaseTest.json()).asJsonObject
        json.addProperty("versionCode", code)
        json.addProperty("minSdk", sdk)
        return ReaderRelease.parse(json.toString(), ReaderReleaseTest.PACKAGE)
    }
}
