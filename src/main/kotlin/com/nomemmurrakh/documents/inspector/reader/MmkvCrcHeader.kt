package com.nomemmurrakh.documents.inspector.reader

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.CRC32

/**
 * Port of the Rust crate `mmkv-parser` 0.1.2 `cipher.rs` (`MmkvCrcHeader`) by Jixun Wu.
 *
 * The sibling `<collectionName>.crc` metafile carries the CRC32 of the (possibly enciphered) body,
 * the AES-128-CFB IV, and the authoritative body size. Layout, from the Rust source:
 *
 * ```
 * crc32     : u32 LE  @ 0x00
 * iv        : 16 bytes @ 0x0C..0x1C   (AES-128-CFB)
 * realSize  : u32 LE  @ 0x1C          (body size, excluding the 4-byte header)
 * ```
 */
internal data class MmkvCrcHeader(
    val crc32: Long,
    val iv: ByteArray,
    val realSize: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MmkvCrcHeader && crc32 == other.crc32 && iv.contentEquals(other.iv) && realSize == other.realSize)

    override fun hashCode(): Int =
        (crc32.hashCode() * 31 + iv.contentHashCode()) * 31 + realSize

    companion object {
        private const val MIN_SIZE = 32

        /** Parses the `.crc` metafile bytes. Port of Rust `MmkvCrcHeader::from_bytes`. */
        fun fromBytes(bytes: ByteArray): MmkvCrcHeader {
            if (bytes.size < MIN_SIZE) throw MmkvError.UnexpectedEof
            return MmkvCrcHeader(
                crc32 = readU32LE(bytes, 0x00),
                iv = bytes.copyOfRange(0x0C, 0x1C),
                realSize = readU32LE(bytes, 0x1C).toInt(),
            )
        }

        private fun readU32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}

/**
 * Decrypts an MMKV-encrypted main file in place with AES-128-CFB, verifying the size and CRC first.
 * Port of Rust `cipher::decrypt`. [mmkvFileBody] must be sized to `realSize + 4`; the returned value
 * is `realSize`. The plaintext occupies `mmkvFileBody[4 until realSize + 4]`.
 *
 * MMKV-level encryption is out of scope for the inspector's v1, but this is ported faithfully so the
 * capability exists and matches the reference implementation.
 */
internal fun decrypt(header: MmkvCrcHeader, key: ByteArray, mmkvFileBody: ByteArray): Int {
    val realSize = header.realSize
    if (mmkvFileBody.size < realSize + 4) throw MmkvError.BufferTooSmall(realSize)

    val expectedSize = (mmkvFileBody[0].toInt() and 0xFF) or
        ((mmkvFileBody[1].toInt() and 0xFF) shl 8) or
        ((mmkvFileBody[2].toInt() and 0xFF) shl 16) or
        ((mmkvFileBody[3].toInt() and 0xFF) shl 24)
    if (expectedSize != realSize) throw MmkvError.FileSizeMismatch

    val cipherText = mmkvFileBody.copyOfRange(4, realSize + 4)
    val crc = CRC32().apply { update(cipherText) }.value
    if (crc != header.crc32) throw MmkvError.ChecksumMismatch

    val cipher = Cipher.getInstance("AES/CFB/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(header.iv))
    val plain = cipher.doFinal(cipherText)
    plain.copyInto(mmkvFileBody, destinationOffset = 4)

    return realSize
}
