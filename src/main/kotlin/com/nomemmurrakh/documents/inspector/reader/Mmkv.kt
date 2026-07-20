package com.nomemmurrakh.documents.inspector.reader

/**
 * Errors mirroring the Rust `mmkv-parser` crate's `Error` enum.
 *
 * @see <a href="https://crates.io/crates/mmkv-parser">mmkv-parser 0.1.2</a> (`src/lib.rs`)
 */
internal sealed class MmkvError(message: String) : Exception(message) {
    object UnexpectedEof : MmkvError("Unexpected End-of-File while parsing")
    data class BufferTooSmall(val required: Int) : MmkvError("buffer too small, at least $required bytes required")
    object FileSizeMismatch : MmkvError("File size mismatch (crc vs mmkv)")
    object ChecksumMismatch : MmkvError("Checksum mismatch")
}

/**
 * Port of the read path of the Rust crate
 * [`mmkv-parser` 0.1.2](https://crates.io/crates/mmkv-parser) (`src/mmkv.rs`) by Jixun Wu, an
 * independent read-only implementation of the Tencent MMKV on-disk format. The function names and
 * control flow follow the Rust source; see `docs/design.md` for the format and attribution.
 *
 * The MMKV main file is `[int32 LE size][one leading varint][entries]`, where each entry is a
 * length-prefixed key followed by a length-prefixed value, appended over time so the last write of
 * a key wins and an empty value marks a deletion. The `int32` at offset 0 is the body size in the
 * crate's model; on files this library writes it can be a stale `0`, in which case the authoritative
 * size comes from the sibling `.crc` file — see [MmkvCrcHeader] and [readFile].
 */
internal object Mmkv {

    /**
     * Reads a protobuf-style base-128 varint starting at [offset]. Returns the decoded value and
     * the index just past the varint. Port of Rust `read_u64`.
     */
    fun readU64(buffer: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < buffer.size) {
            val byte = buffer[i].toInt() and 0xFF
            result = result or ((byte.toLong() and 0x7F) shl shift)
            shift += 7
            if (byte and 0x80 == 0) return result to (i + 1)
            i += 1
        }
        throw MmkvError.UnexpectedEof
    }

    /**
     * Reads a length-prefixed byte container at [offset]: a varint length, then that many bytes.
     * Returns the container bytes and the index just past them. Port of Rust `read_container`
     * (whose `split_at` would panic past the end; here that surfaces as [MmkvError.UnexpectedEof]).
     */
    fun readContainer(buffer: ByteArray, offset: Int): Triple<ByteArray, Int, Int> {
        val (len, afterLen) = readU64(buffer, offset)
        val end = afterLen + len.toInt()
        if (len < 0 || end > buffer.size) throw MmkvError.UnexpectedEof
        return Triple(buffer.copyOfRange(afterLen, end), afterLen, end)
    }

    /**
     * Parses an MMKV main-file buffer into live key/value pairs, applying latest-value-wins and
     * removing deleted (empty-value) keys. Port of Rust `parse`/`parse_callback`.
     *
     * The body length is the `int32` at offset 0. When that is a stale `0` (as on files this
     * library writes), pass the true size via [readFile] instead, which patches offset 0 from the
     * `.crc` file the way the crate's CLI does.
     */
    fun parse(buffer: ByteArray): Map<String, ByteArray> {
        val mmkvLen = readInt32LE(buffer, 0)
        if (buffer.size < mmkvLen + 4) throw MmkvError.BufferTooSmall(mmkvLen + 4)

        val bodyEnd = 4 + mmkvLen
        val (_, afterLeadingVarint) = readU64(buffer, 4)

        val live = LinkedHashMap<String, ByteArray>()
        var pos = afterLeadingVarint
        while (pos < bodyEnd) {
            val (keyBytes, _, afterKey) = readContainer(buffer, pos)
            val (value, _, afterValue) = readContainer(buffer, afterKey)
            pos = afterValue

            val key = String(keyBytes, Charsets.UTF_8)
            if (value.isEmpty()) live.remove(key) else live[key] = value
        }
        return live
    }

    /**
     * Parses a plaintext MMKV file the way the crate's `mmkv` CLI does: the authoritative body size
     * comes from the sibling `.crc` file's `real_size`, and the buffer is resized to `real_size + 4`
     * (offset 0 set to that size) before parsing. Handles the stale-`0`-at-offset-0 case.
     *
     * @param mainFile the collection file bytes (`<collectionName>`).
     * @param crcFile the sibling `<collectionName>.crc` bytes.
     */
    fun readFile(mainFile: ByteArray, crcFile: ByteArray): Map<String, ByteArray> {
        val header = MmkvCrcHeader.fromBytes(crcFile)
        val bodyLength = header.realSize
        if (mainFile.size < bodyLength + 4) throw MmkvError.BufferTooSmall(bodyLength + 4)

        val sized = mainFile.copyOf(bodyLength + 4)
        writeInt32LE(sized, 0, bodyLength)
        return parse(sized)
    }

    /**
     * Unwraps MMKV's container around a value stored via `MMKV.encode(key, byte[])`. MMKV writes a
     * `byte[]` in protobuf length-delimited form — a varint length followed by that many bytes — so
     * a value read from [parse] is `varint(len)·payload`. Returns the inner payload (the Documents
     * CBOR). If the bytes are not a well-formed length-delimited container, returns them unchanged.
     */
    fun unwrapByteArrayValue(value: ByteArray): ByteArray {
        if (value.isEmpty()) return value
        return try {
            val (len, afterLen) = readU64(value, 0)
            if (len >= 0 && afterLen + len.toInt() == value.size) {
                value.copyOfRange(afterLen, value.size)
            } else {
                value
            }
        } catch (_: MmkvError) {
            value
        }
    }

    private fun readInt32LE(buffer: ByteArray, offset: Int): Int {
        if (offset + 4 > buffer.size) throw MmkvError.BufferTooSmall(offset + 4)
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt32LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
