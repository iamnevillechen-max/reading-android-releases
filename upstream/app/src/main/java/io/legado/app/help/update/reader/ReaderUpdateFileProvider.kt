package io.legado.app.help.update.reader

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** Only the current verified private APK is readable; never accept a write-capable URI grant. */
class ReaderUpdateFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        require(!info.exported && info.grantUriPermissions && info.authority == "${context.packageName}.reader.update")
        super.attachInfo(context, info)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = context ?: throw FileNotFoundException()
        val ready = ReaderUpdateStore(app).snapshot().ready ?: throw FileNotFoundException()
        if (mode != "r" || uri.scheme != "content" || uri.authority != "${app.packageName}.reader.update" || uri.query != null ||
            uri.fragment != null || uri.encodedPath?.contains('%') == true ||
            uri.pathSegments != listOf("reader_ready", "${ready.sha256}.apk")) throw FileNotFoundException()
        return super.openFile(uri, mode) ?: throw FileNotFoundException()
    }
}
