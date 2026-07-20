package com.nomemmurrakh.documents.inspector.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ports validated against real MMKV files pulled from a device (sample app
 * `com.nomemmurrakh.documents.sample`), with the expected output taken from the Rust reference
 * crate `mmkv-parser` 0.1.2 used as an oracle. The golden hex below is exactly what the Rust
 * `parse` produced (after applying the `.crc` `real_size`, as its CLI does); the Kotlin port must
 * reproduce it byte for byte.
 */
internal class RealFileOracleTest {

    private fun load(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/mmkv/$name")) { "missing test fixture: $name" }
            .use { it.readBytes() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun liveHex(name: String): Map<String, String> {
        val live = Mmkv.readFile(load(name), load("$name.crc"))
        return live.mapValues { hex(it.value) }
    }

    @Test
    fun `documents_default matches the Rust oracle output`() {
        val expected = mapOf(
            "active-download::bytesDownloaded" to "051a00061a80",
            "active-download::isComplete" to "01f4",
            "active-download::totalBytes" to "051a001e8480",
            "app-settings::theme" to "06654c49474854",
        )
        assertEquals(expected, liveHex("documents.default"))
    }

    @Test
    fun `session matches the Rust oracle output`() {
        val expected = mapOf(
            "current::authToken" to "292d1a38d8ffa5062e70bfdbb50088cc2d9d72a5f10770e1712b80851969ffc4b03a4bb65621abe97663",
            "current::displayName" to "21e6fdd88d99cd5a24634d0a0bf63a2f8f2279286c1149b8cd6de3c399578cccfbc5",
            "current::userId" to "23f008f37f5b5634d1c1f1875509ac9a0633928207ac65da891f30c5422bed41b3100f12",
        )
        assertEquals(expected, liveHex("session"))
    }

    @Test
    fun `crc header exposes the authoritative real_size`() {
        assertEquals(334, MmkvCrcHeader.fromBytes(load("documents.default.crc")).realSize)
        assertEquals(175, MmkvCrcHeader.fromBytes(load("session.crc")).realSize)
    }

    @Test
    fun `default collection reads into a labeled tree with decoded fields`() {
        val collection = CollectionReader.read(
            "documents.default",
            load("documents.default"),
            load("documents.default.crc"),
        )
        assertEquals(CollectionReader.DEFAULT_LABEL, collection.label)

        val settings = collection.documents.single { it.key == "app-settings" }
        val theme = settings.fields.single { it.name == "theme" }.value
        // MMKV's byte[] length prefix is stripped, leaving the enum's CBOR name.
        assertEquals(DocumentValue.Text("LIGHT"), theme)

        val download = collection.documents.single { it.key == "active-download" }
        val total = download.fields.single { it.name == "totalBytes" }.value
        assertEquals(DocumentValue.Integer(2_000_000L), total)
        val complete = download.fields.single { it.name == "isComplete" }.value
        assertEquals(DocumentValue.Bool(false), complete)
    }

    @Test
    fun `field-decorator-encrypted values decode as opaque blobs`() {
        val collection = CollectionReader.read("session", load("session"), load("session.crc"))
        val current = collection.documents.single { it.key == "current" }
        assertTrue(current.fields.isNotEmpty())
        assertTrue(
            current.fields.all { it.value is DocumentValue.Opaque },
            "session fields are FieldDecorator-encrypted and must be opaque, got: " +
                current.fields.map { it.name to it.value::class.simpleName },
        )
    }
}
