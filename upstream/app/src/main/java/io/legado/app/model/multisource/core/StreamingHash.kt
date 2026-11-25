package io.legado.app.model.multisource.core

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Hashes serialized configuration without retaining its complete text or UTF-8 byte array. */
object StreamingHash {
    fun sha256(write: (Writer) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8).use(write)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Length framing distinguishes nulls, empty strings and adjacent field boundaries. */
    fun field(writer: Writer, value: String?) {
        if (value == null) writer.write("-1:")
        else {
            writer.write(value.length.toString())
            writer.write(":")
            writer.write(value)
        }
    }
}
