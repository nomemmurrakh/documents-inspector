package com.nomemmurrakh.documents.inspector.reader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the ported [Mmkv] primitives. The first cases mirror the Rust crate
 * `mmkv-parser` 0.1.2's own `#[test]` fixtures so the port is verified against the reference's
 * expectations, not just re-derived ones.
 */
internal class MmkvTest {

    @Test
    fun `readU64 matches the Rust test vectors`() {
        val (v1, next1) = Mmkv.readU64(byteArrayOf(0xff.toByte(), 0x81.toByte(), 0x01, 0x00), 0)
        assertEquals(16639L, v1)
        assertEquals(3, next1)

        val (v2, next2) = Mmkv.readU64(byteArrayOf(0x80.toByte(), 0x00, 0xff.toByte()), 0)
        assertEquals(0L, v2)
        assertEquals(2, next2)
    }

    @Test
    fun `readU64 throws on an unterminated varint (Rust UnexpectedEof)`() {
        assertFailsWith<MmkvError.UnexpectedEof> {
            Mmkv.readU64(byteArrayOf(0x81.toByte(), 0xAA.toByte()), 0)
        }
    }

    @Test
    fun `readContainer matches the Rust test vectors`() {
        val (bytes1, _, end1) = Mmkv.readContainer(byteArrayOf(0x03, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 0), 0)
        assertContentEquals("ABC".toByteArray(), bytes1)
        assertEquals(4, end1)

        val (bytes2, _, end2) = Mmkv.readContainer(byteArrayOf(0x00, 'A'.code.toByte()), 0)
        assertContentEquals(byteArrayOf(), bytes2)
        assertEquals(1, end2)
    }

    @Test
    fun `parse matches the Rust parse test vector`() {
        val buffer = byteArrayOf(
            19, 0, 0, 0,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07,
            0x03, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 0,
            0x03, 'D'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(),
            0x05, 0x04, '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte(),
        )
        val map = Mmkv.parse(buffer)
        // "ABC" has an empty value -> deletion in our live-map view (Rust keeps it as "" in a HashMap).
        assertContentEquals(byteArrayOf(0x04, '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte()), map.getValue("DEF"))
        assertEquals(false, map.containsKey("ABC"))
    }

    @Test
    fun `parse throws BufferTooSmall like Rust`() {
        assertFailsWith<MmkvError.BufferTooSmall> {
            Mmkv.parse(byteArrayOf(20, 0, 0, 0))
        }
    }

    @Test
    fun `parse handles the empty payload vector`() {
        val buffer = byteArrayOf(1, 0, 0, 0, 0, 0xff.toByte())
        assertEquals(emptyMap(), Mmkv.parse(buffer))
    }

    @Test
    fun `unwrapByteArrayValue strips MMKV's length prefix`() {
        // MMKV stores byte[] as varint(len) + payload; e.g. totalBytes = 05 · 1a001e8480.
        val wrapped = byteArrayOf(0x05, 0x1a, 0x00, 0x1e, 0x84.toByte(), 0x80.toByte())
        assertContentEquals(
            byteArrayOf(0x1a, 0x00, 0x1e, 0x84.toByte(), 0x80.toByte()),
            Mmkv.unwrapByteArrayValue(wrapped),
        )
    }

    @Test
    fun `unwrapByteArrayValue leaves a non-container value unchanged`() {
        // length prefix would claim 4 bytes but only 1 follows -> not a valid container.
        val notAContainer = byteArrayOf(0x04, 0xf4.toByte())
        assertContentEquals(notAContainer, Mmkv.unwrapByteArrayValue(notAContainer))
    }

    @Test
    fun `latest value wins across repeated keys`() {
        val buffer = MmkvBytes.mainFile(
            "k" to byteArrayOf(1),
            "k" to byteArrayOf(9, 9),
        )
        assertContentEquals(byteArrayOf(9, 9), Mmkv.parse(buffer).getValue("k"))
    }
}
