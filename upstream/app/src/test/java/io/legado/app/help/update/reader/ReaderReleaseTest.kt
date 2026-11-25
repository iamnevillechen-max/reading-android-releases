package io.legado.app.help.update.reader

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ReaderReleaseTest {
    @Test fun versionsUseIntegerCodesAndNotNames() {
        val release = ReaderRelease.parse(json(), PACKAGE)
        assertTrue(release.newerThan(9))
        assertFalse(release.newerThan(10))
        assertFalse(release.newerThan(11))
        assertTrue(release.supports(35, arrayOf("arm64-v8a")))
        assertFalse(release.supports(23, arrayOf("arm64-v8a")))
    }

    @Test fun numericFieldsRejectStringsFractionsExponentsOverflowAndOutOfBounds() {
        for (field in listOf("versionCode", "bytes", "minSdk")) {
            for (bad in listOf("\"10\"", "10.0", "1e1", "-1", "0", "9223372036854775808")) {
                val objectValue = JsonParser.parseString(json()).asJsonObject
                objectValue.add(field, JsonParser.parseString(bad))
                rejected { ReaderRelease.parse(objectValue.toString(), PACKAGE) }
            }
        }
        rejected { ReaderRelease.parse(json(bytes = ReaderRelease.MAX_APK_BYTES + 1), PACKAGE) }
        rejected { ReaderRelease.parse(json(), "other.package") }
        rejected { ReaderRelease.parse(json().replace("test release", "a".repeat(70_000)), PACKAGE) }
    }

    @Test fun onlyOwnedPublicHttpsReleasePathsAreAccepted() {
        val good = "https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/v1.2/reading.apk"
        assertEquals(good, ReaderUpdatePolicy.apk(good).toASCIIString())
        for (bad in listOf(good.replace("https:", "http:"), good.replace("github.com", "github.com.evil.test"),
            good.replace("github.com", "user@github.com"), "$good?token=x", "$good#fragment",
            good.replace("reading.apk", "%2e%2e/reading.apk"), good.replace("reading.apk", "../reading.apk"),
            good.replace("reading-android-releases", "shengjing-android-releases"), good.replace("github.com", "github.com:444"))) {
            rejected { ReaderUpdatePolicy.apk(bad) }
        }
        ReaderUpdatePolicy.redirect(URI("https://release-assets.githubusercontent.com/owned/asset?signature=test"))
        rejected { ReaderUpdatePolicy.redirect(URI("https://example.test/asset.apk")) }
        rejected { ReaderUpdatePolicy.redirect(URI("https://github.com/gedoor/legado/releases/download/v1/reading.apk")) }
        rejected { ReaderUpdatePolicy.redirect(URI("http://objects.githubusercontent.com/asset.apk")) }
    }

    @Test fun actualArchiveMetadataAndSigningIdentityMustBothMatch() {
        val release = ReaderRelease.parse(json(), PACKAGE)
        val actual = ReaderApkVerifier.Identity(PACKAGE, 10, "1.0", 24, setOf("arm64-v8a"), listOf(byteArrayOf(1)))
        ReaderApkVerifier.requireMatching(release, actual, 9, 35, arrayOf("arm64-v8a"))
        for (wrong in listOf(actual.copy(packageName = "other"), actual.copy(versionCode = 11),
            actual.copy(versionName = "wrong"), actual.copy(minSdk = 25), actual.copy(abis = setOf("x86")))) {
            rejected { ReaderApkVerifier.requireMatching(release, wrong, 9, 35, arrayOf("arm64-v8a")) }
        }
        rejected { ReaderApkVerifier.requireMatching(release, actual, 10, 35, arrayOf("arm64-v8a")) }
        assertTrue(ReaderTransfer.sameSigners(listOf(byteArrayOf(1), byteArrayOf(2)), listOf(byteArrayOf(2), byteArrayOf(1))))
        assertFalse(ReaderTransfer.sameSigners(listOf(byteArrayOf(1)), listOf(byteArrayOf(2))))
        assertFalse(ReaderTransfer.sameSigners(emptyList(), emptyList()))
        assertFalse(ReaderTransfer.sameSigners(listOf(byteArrayOf(1), byteArrayOf(1)), listOf(byteArrayOf(1), byteArrayOf(1))))
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Unsafe metadata was accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: SecurityException) { }
    }

    companion object {
        const val PACKAGE = "io.multiread.reader.debug"
        fun json(bytes: Long = 12, sha: String = "a".repeat(64)) = """{
          "packageName":"$PACKAGE","versionCode":10,"versionName":"1.0","minSdk":24,
          "bytes":$bytes,"sha256":"$sha","abi":"universal",
          "apkUrl":"https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/v1/reading.apk",
          "releasePageUrl":"${ReaderUpdatePolicy.RELEASE_PAGE}","notes":"test release"
        }"""
    }
}
