package com.nomemmurrakh.documents.inspector.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CborDecoderTest {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    @Test
    fun `decodes a string`() {
        assertEquals(DocumentValue.Text("hello"), CborDecoder.decode(cbor.encodeToByteArray("hello")))
    }

    @Test
    fun `decodes a positive integer`() {
        assertEquals(DocumentValue.Integer(1234L), CborDecoder.decode(cbor.encodeToByteArray(1234L)))
    }

    @Test
    fun `decodes a negative integer`() {
        assertEquals(DocumentValue.Integer(-1000L), CborDecoder.decode(cbor.encodeToByteArray(-1000L)))
    }

    @Test
    fun `decodes booleans`() {
        assertEquals(DocumentValue.Bool(true), CborDecoder.decode(cbor.encodeToByteArray(true)))
        assertEquals(DocumentValue.Bool(false), CborDecoder.decode(cbor.encodeToByteArray(false)))
    }

    @Test
    fun `decodes a double`() {
        val value = CborDecoder.decode(cbor.encodeToByteArray(3.14159))
        assertIs<DocumentValue.Decimal>(value)
        assertEquals(3.14159, value.value, 1e-9)
    }

    @Test
    fun `decodes a list of ints`() {
        val value = CborDecoder.decode(cbor.encodeToByteArray(listOf(1L, 2L, 3L)))
        assertIs<DocumentValue.Listing>(value)
        assertEquals(
            listOf(DocumentValue.Integer(1), DocumentValue.Integer(2), DocumentValue.Integer(3)),
            value.items,
        )
    }

    @Serializable
    private data class Note(val title: String, val done: Boolean, val order: Int)

    @Test
    fun `decodes a data class as a map of fields`() {
        val value = CborDecoder.decode(cbor.encodeToByteArray(Note("buy milk", done = false, order = 7)))
        assertIs<DocumentValue.Mapping>(value)
        val byKey = value.entries.associate { (k, v) -> assertIs<DocumentValue.Text>(k); k.value to v }
        assertEquals(DocumentValue.Text("buy milk"), byKey["title"])
        assertEquals(DocumentValue.Bool(false), byKey["done"])
        assertEquals(DocumentValue.Integer(7), byKey["order"])
    }

    @Test
    fun `decodes a null`() {
        assertEquals(DocumentValue.Null, CborDecoder.decode(cbor.encodeToByteArray<String?>(null)))
    }

    @Test
    fun `multiple top-level items become a sequence`() {
        // the real on-disk enum framing: uint 6 then text "LIGHT"
        val bytes = byteArrayOf(0x06, 0x65, 0x4c, 0x49, 0x47, 0x48, 0x54)
        val value = CborDecoder.decode(bytes)
        assertIs<DocumentValue.Listing>(value)
        assertEquals(
            listOf(DocumentValue.Integer(6), DocumentValue.Text("LIGHT")),
            value.items,
        )
    }

    @Test
    fun `non-cbor bytes surface as opaque`() {
        val garbage = byteArrayOf(0x9F.toByte(), 0x01, 0x02)
        val value = CborDecoder.decode(garbage)
        assertIs<DocumentValue.Opaque>(value)
        assertContentEquals(garbage, value.value)
    }

    @Test
    fun `empty input is opaque`() {
        assertTrue(CborDecoder.decode(byteArrayOf()) is DocumentValue.Opaque)
    }
}
