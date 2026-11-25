package io.legado.app.model.multisource.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class StreamingHashTest {
    @Test fun matchesUtf8DigestAcrossWriterBoundaries() {
        val text = "來源𠮷\"\\\n".repeat(3000)
        val expected = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, StreamingHash.sha256 { writer ->
            text.forEach { writer.write(it.code) }
        })
    }

    @Test fun fieldBoundariesNullsAndEmptyValuesRemainDistinct() {
        fun hash(vararg fields: String?) = StreamingHash.sha256 { writer ->
            fields.forEach { StreamingHash.field(writer, it) }
        }
        assertNotEquals(hash("ab", "c"), hash("a", "bc"))
        assertNotEquals(hash(null), hash(""))
        assertNotEquals(hash("-1:"), hash(null))
        assertNotEquals(hash("a", "b"), hash("b", "a"))
    }

    @Test fun hashesLargeRuleLibraryWithoutCombiningItsDocuments() {
        // More than 160 MiB of rule text, generated lazily from one reusable 8 KiB
        // rule. This also runs in a small JVM heap; a combined String cannot fit.
        val rule = "0123456789abcdef".repeat(512)
        val ruleBytes = rule.toByteArray(Charsets.UTF_8)
        val expected = MessageDigest.getInstance("SHA-256")
        repeat(20_689) { expected.update(ruleBytes) }
        val actual = StreamingHash.sha256 { writer -> repeat(20_689) { writer.write(rule) } }
        assertEquals(expected.digest().joinToString("") { "%02x".format(it) }, actual)
    }
}
