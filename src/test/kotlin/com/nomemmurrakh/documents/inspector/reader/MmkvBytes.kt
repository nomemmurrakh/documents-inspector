package com.nomemmurrakh.documents.inspector.reader

import java.io.ByteArrayOutputStream

/** Test helpers that assemble MMKV-format bytes the way the reference implementation reads them. */
internal object MmkvBytes {

    /**
     * Builds a main-file buffer parseable by [Mmkv.parse]: `int32 LE bodyLength`, one leading
     * varint (`0x00`, matching the crate's skipped leading integer), then the entries, then
     * [padding] zero bytes to simulate page-aligned mmap padding. `bodyLength` counts the leading
     * varint plus all entries (everything after the 4-byte header).
     */
    fun mainFile(vararg entries: Pair<String, ByteArray>, padding: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00) // leading varint the parser skips
        for ((key, value) in entries) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            body.write(varint(keyBytes.size))
            body.write(keyBytes)
            body.write(varint(value.size))
            body.write(value)
        }
        val bodyBytes = body.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(int32LE(bodyBytes.size))
        out.write(bodyBytes)
        repeat(padding) { out.write(0) }
        return out.toByteArray()
    }

    private fun int32LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    fun varint(value: Int): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while (true) {
            val b = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.write(b)
                break
            }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }
}
