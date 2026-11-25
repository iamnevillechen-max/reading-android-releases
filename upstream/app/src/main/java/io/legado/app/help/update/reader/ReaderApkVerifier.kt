package io.legado.app.help.update.reader

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

object ReaderApkVerifier {
    data class Identity(val packageName: String, val versionCode: Long, val versionName: String,
        val minSdk: Int, val abis: Set<String>, val signers: List<ByteArray>)

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong() and 0xffffffffL

    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags())

    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): List<ByteArray> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toByteArray() } ?: emptyList()

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    fun inspect(context: Context, file: File): Identity {
        // API 21–23 do not expose archive minSdk through PackageManager: fail closed.
        if (Build.VERSION.SDK_INT < 24) throw SecurityException("apk_sdk_inspection")
        val abis = mutableSetOf<String>()
        ZipFile(file).use { zip ->
            if (zip.size() > 30_000) throw SecurityException("apk_entries")
            var expanded = 0L
            val names = hashSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!names.add(name) || name.startsWith('/') || '\\' in name || ".." in name.split('/') ||
                    entry.size < 0 || entry.size > 512L * 1024 * 1024) throw SecurityException("apk_zip")
                expanded += entry.size
                if (expanded > 512L * 1024 * 1024) throw SecurityException("apk_expanded")
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    val pieces = name.split('/')
                    if (pieces.size != 3) throw SecurityException("apk_abi")
                    abis.add(pieces[1])
                }
            }
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: throw SecurityException("apk_parse")
        val app = info.applicationInfo ?: throw SecurityException("apk_parse")
        return Identity(info.packageName, versionCode(info), info.versionName ?: "", app.minSdkVersion, abis, signers(info))
    }

    fun requireMatching(release: ReaderRelease, actual: Identity, installedCode: Long, deviceSdk: Int,
        supportedAbis: Array<String>) {
        if (actual.packageName != release.packageName || actual.versionCode != release.versionCode ||
            actual.versionName != release.versionName || actual.minSdk != release.minSdk ||
            !release.newerThan(installedCode) || !release.supports(deviceSdk, supportedAbis) ||
            (actual.abis.isNotEmpty() && actual.abis.none { it in supportedAbis }) ||
            (release.abi != "universal" && release.abi !in actual.abis)) throw SecurityException("apk_metadata")
    }

    fun validate(context: Context, file: File, release: ReaderRelease) {
        ReaderTransfer.verifyHash(file, release.bytes, release.sha256)
        val installed = installed(context)
        if (release.packageName != context.packageName) throw SecurityException("apk_package")
        val archive = inspect(context, file)
        requireMatching(release, archive, versionCode(installed), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)
        if (!ReaderTransfer.sameSigners(signers(installed), archive.signers)) throw SecurityException("apk_signer")
    }
}
