package io.legado.app.help.update.reader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets

/** Public metadata is a claim to verify, never authority to bypass Android's installer. */
data class ReaderRelease private constructor(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val bytes: Long,
    val sha256: String,
    val apkUrl: String,
    val notes: String,
    val releasePageUrl: String,
    val abi: String,
    val json: String
) {
    fun newerThan(installed: Long) = versionCode > installed
    fun supports(sdk: Int, abis: Array<String>) = sdk >= minSdk && (abi == "universal" || abi in abis)

    companion object {
        const val MAX_METADATA_BYTES = 65_536
        const val MAX_APK_BYTES = 128L * 1024 * 1024

        fun parse(json: String, expectedPackage: String): ReaderRelease {
            require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_METADATA_BYTES) { "metadata_size" }
            val root = try { JsonParser.parseString(json).asJsonObject }
                catch (error: Exception) { throw IllegalArgumentException("metadata_format", error) }
            fun text(key: String, maximum: Int, optional: Boolean = false): String {
                val value = root.get(key)
                if (optional && value == null) return ""
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "metadata_$key" }
                val result = value.asString
                require(result.length <= maximum && (optional || result.isNotBlank())) { "metadata_$key" }
                require(result.none { it.code < 32 && !(optional && it in "\n\r\t") || it.code == 127 }) { "metadata_$key" }
                return result.trim()
            }
            val packageName = text("packageName", 200)
            require(packageName == expectedPackage) { "metadata_package" }
            val code = integer(root, "versionCode", 1, Int.MAX_VALUE.toLong())
            val version = text("versionName", 80)
            val sdk = integer(root, "minSdk", 21, 1000).toInt()
            val bytes = integer(root, "bytes", 1, MAX_APK_BYTES)
            val sha = text("sha256", 64).lowercase(java.util.Locale.ROOT)
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "metadata_sha256" }
            val apk = ReaderUpdatePolicy.apk(text("apkUrl", 4096)).toASCIIString()
            val page = ReaderUpdatePolicy.releasePage(text("releasePageUrl", 4096)).toASCIIString()
            val abi = text("abi", 40, true).ifEmpty { "universal" }
            require(abi in setOf("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "metadata_abi" }
            return ReaderRelease(packageName, code, version, sdk, bytes, sha, apk,
                text("notes", 16_000, true), page, abi, root.toString())
        }

        private fun integer(root: JsonObject, key: String, min: Long, max: Long): Long {
            val value = root.get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "metadata_$key" }
            val raw = value.toString()
            require(raw.matches(Regex("[0-9]+"))) { "metadata_$key" }
            val number = raw.toLongOrNull() ?: throw IllegalArgumentException("metadata_$key")
            require(number in min..max) { "metadata_$key" }
            return number
        }
    }
}

object ReaderUpdatePolicy {
    const val REPOSITORY = "/iamnevillechen-max/reading-android-releases"
    const val MANIFEST_URL = "https://github.com$REPOSITORY/releases/latest/download/update.json"
    const val RELEASE_PAGE = "https://github.com$REPOSITORY/releases/latest"
    private val tag = "[A-Za-z0-9._-]{1,100}"

    private fun https(value: String): URI {
        require(value.length <= 4096) { "url_size" }
        val uri = try { URI(value) } catch (error: Exception) { throw IllegalArgumentException("url_format", error) }
        require(uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null &&
            uri.rawFragment == null && uri.port in listOf(-1, 443)) { "url_https" }
        val path = uri.rawPath ?: ""
        require(path.isNotEmpty() && '%' !in path && '\\' !in path && uri.normalize().rawPath == path) { "url_path" }
        return uri
    }

    fun apk(value: String): URI = https(value).also {
        require(it.host.equals("github.com", true) && it.rawQuery == null &&
            it.rawPath.matches(Regex("$REPOSITORY/releases/download/$tag/[A-Za-z0-9][A-Za-z0-9._-]{0,155}\\.apk"))) { "url_apk_source" }
    }

    fun releasePage(value: String): URI = https(value).also {
        require(it.host.equals("github.com", true) && it.rawQuery == null &&
            (it.rawPath == "$REPOSITORY/releases/latest" ||
                it.rawPath.matches(Regex("$REPOSITORY/releases/tag/$tag")))) { "url_release_source" }
    }

    fun redirect(value: URI): URI = https(value.toASCIIString()).also {
        when (it.host.lowercase(java.util.Locale.ROOT)) {
            "github.com" -> require(it.rawQuery == null &&
                (it.toASCIIString() == MANIFEST_URL ||
                    it.rawPath.matches(Regex("$REPOSITORY/releases/download/$tag/[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))) { "url_repository" }
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> Unit
            else -> throw IllegalArgumentException("url_redirect_host")
        }
    }
}
