package com.nomemmurrakh.documents.inspector.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class CollectionReaderTest {

    private val cbor = Cbor

    @Serializable
    private data class Note(val title: String, val done: Boolean)

    /**
     * Builds a matching main-file + `.crc` pair so [CollectionReader.read] can be exercised. Each
     * value is wrapped in MMKV's `byte[]` length prefix, the way `MMKV.encode(key, byte[])` stores
     * it on disk, so the reader's unwrap path is exercised.
     */
    private fun store(vararg entries: Pair<String, ByteArray>): Pair<ByteArray, ByteArray> {
        val wrapped = entries.map { (k, v) ->
            k to (if (v.isEmpty()) v else MmkvBytes.varint(v.size) + v)
        }.toTypedArray()
        val main = MmkvBytes.mainFile(*wrapped)
        val realSize = main.size - 4
        val crc = ByteArray(32)
        crc[0x1C] = (realSize and 0xFF).toByte()
        crc[0x1D] = ((realSize shr 8) and 0xFF).toByte()
        crc[0x1E] = ((realSize shr 16) and 0xFF).toByte()
        crc[0x1F] = ((realSize shr 24) and 0xFF).toByte()
        return main to crc
    }

    @Test
    fun `groups keys by document prefix before the separator`() {
        val (main, crc) = store(
            "note-1::title" to cbor.encodeToByteArray("first"),
            "note-1::done" to cbor.encodeToByteArray(false),
            "note-2::title" to cbor.encodeToByteArray("second"),
        )
        val collection = CollectionReader.read("notes", main, crc)

        assertEquals("notes", collection.label)
        assertEquals(listOf("note-1", "note-2"), collection.documents.map { it.key })

        val first = collection.documents.first { it.key == "note-1" }
        assertEquals(listOf("title", "done"), first.fields.map { it.name })
        assertEquals(DocumentValue.Text("first"), first.fields.first { it.name == "title" }.value)
    }

    @Test
    fun `default store id is labeled Default`() {
        val (main, crc) = store("k::f" to cbor.encodeToByteArray("v"))
        assertEquals(CollectionReader.DEFAULT_LABEL, CollectionReader.read(CollectionReader.DEFAULT_STORE_ID, main, crc).label)
    }

    @Test
    fun `named collection keeps its file name as the label`() {
        val (main, crc) = store("k::f" to cbor.encodeToByteArray("v"))
        assertEquals("games", CollectionReader.read("games", main, crc).label)
    }

    @Test
    fun `keys without the separator are skipped`() {
        val (main, crc) = store(
            "orphan" to cbor.encodeToByteArray("x"),
            "doc::field" to cbor.encodeToByteArray("y"),
        )
        assertEquals(listOf("doc"), CollectionReader.read("c", main, crc).documents.map { it.key })
    }

    @Test
    fun `decodes a full document round-trip`() {
        val (main, crc) = store("todo::note" to cbor.encodeToByteArray(Note("buy milk", done = true)))
        val field = CollectionReader.read("c", main, crc).documents.single().fields.single()
        assertEquals("note", field.name)
        val mapping = field.value
        assertIs<DocumentValue.Mapping>(mapping)
        val byKey = mapping.entries.associate { (k, v) -> (k as DocumentValue.Text).value to v }
        assertEquals(DocumentValue.Text("buy milk"), byKey["title"])
        assertEquals(DocumentValue.Bool(true), byKey["done"])
    }

    @Test
    fun `deleted field does not appear`() {
        val (main, crc) = store(
            "doc::a" to cbor.encodeToByteArray("keep"),
            "doc::b" to cbor.encodeToByteArray("temp"),
            "doc::b" to byteArrayOf(),
        )
        val fields = CollectionReader.read("c", main, crc).documents.single().fields
        assertEquals(listOf("a"), fields.map { it.name })
    }
}
