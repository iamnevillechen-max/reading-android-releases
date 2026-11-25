package io.legado.app.model.cloud

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudContentQualityTest {
    @Test fun sharedServerVectorsKeepMigrationAndReadingContractsAligned() {
        val policy = listOf("../../server/shared/content-quality-v1.json", "../server/shared/content-quality-v1.json", "server/shared/content-quality-v1.json")
            .map(::File).first { it.isFile }
        val data = JsonParser.parseString(policy.readText()).asJsonObject
        assertEquals(data["version"].asString,CloudMigrationPolicy.CONTENT_QUALITY_VERSION)
        for (entry in data.getAsJsonArray("cases")) {
            val vector=entry.asJsonObject;val name=vector["name"].asString;val text=vector["text"].asString
            assertEquals("$name readable",vector["readable"].asBoolean,CloudMigrationPolicy.canReadContent(text))
            assertEquals("$name migratable",vector["migratable"].asBoolean,CloudMigrationPolicy.canMigrateContent(text))
        }
    }
    @Test fun emptyAndPrivateGlyphBodiesNeverAuthorizeClearingRules() {
        assertFalse(CloudMigrationPolicy.canMigrateContent(null))
        assertFalse(CloudMigrationPolicy.canReadContent(" \n\t"))
        assertFalse(CloudMigrationPolicy.canMigrateContent("\uE001".repeat(100)))
    }
}
