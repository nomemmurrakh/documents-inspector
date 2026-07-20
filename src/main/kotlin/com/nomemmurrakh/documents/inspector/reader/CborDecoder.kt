package com.nomemmurrakh.documents.inspector.reader

/**
 * Schema-free decoder for a single CBOR data item ([RFC 8949](https://www.rfc-editor.org/rfc/rfc8949)).
 *
 * The Documents library encodes each field value as CBOR before writing it to MMKV. Because CBOR
 * carries type information in its major-type tags, a value can be decoded into a [DocumentValue]
 * tree without the original data class. Bytes that are not valid CBOR — the expected case for
 * `FieldDecorator`-transformed (encrypted/compressed) values — surface as [DocumentValue.Opaque]
 * rather than throwing.
 *
 * A field value may hold more than one top-level CBOR item: kotlinx-serialization-cbor encodes an
 * enum as two items (its ordinal followed by its name, e.g. `06 65 "LIGHT"` for a `Theme.LIGHT`).
 * A single item is returned as-is; multiple items are returned as a [DocumentValue.Listing] so
 * nothing is dropped and no specific framing is assumed.
 *
 * This is the inspector's own layer, not part of the ported MMKV reader; the MMKV port stops at raw
 * value bytes.
 */
internal object CborDecoder {

    fun decode(bytes: ByteArray): DocumentValue {
        return try {
            val reader = Reader(bytes)
            val items = ArrayList<DocumentValue>()
            while (reader.hasRemaining()) items.add(reader.readItem())
            when (items.size) {
                0 -> DocumentValue.Opaque(bytes)
                1 -> items[0]
                else -> DocumentValue.Listing(items)
            }
        } catch (_: MalformedCbor) {
            DocumentValue.Opaque(bytes)
        }
    }

    private class MalformedCbor : Exception()

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasRemaining(): Boolean = pos < bytes.size

        fun readItem(): DocumentValue {
            val initial = nextByte()
            val major = (initial.toInt() and 0xFF) shr 5
            val minor = initial.toInt() and 0x1F
            return when (major) {
                0 -> DocumentValue.Integer(readUnsigned(minor))
                1 -> DocumentValue.Integer(-1L - readUnsigned(minor))
                2 -> DocumentValue.Bytes(readByteString(minor))
                3 -> DocumentValue.Text(String(readByteString(minor), Charsets.UTF_8))
                4 -> readArray(minor)
                5 -> readMap(minor)
                6 -> readTagged(minor)
                7 -> readSimpleOrFloat(minor)
                else -> throw MalformedCbor()
            }
        }

        private fun readArray(minor: Int): DocumentValue {
            if (minor == INDEFINITE) {
                val items = ArrayList<DocumentValue>()
                while (!atBreak()) items.add(readItem())
                consumeBreak()
                return DocumentValue.Listing(items)
            }
            val count = readUnsigned(minor)
            requireCount(count)
            val items = ArrayList<DocumentValue>(count.toInt())
            repeat(count.toInt()) { items.add(readItem()) }
            return DocumentValue.Listing(items)
        }

        private fun readMap(minor: Int): DocumentValue {
            if (minor == INDEFINITE) {
                val entries = ArrayList<Pair<DocumentValue, DocumentValue>>()
                while (!atBreak()) {
                    val key = readItem()
                    val value = readItem()
                    entries.add(key to value)
                }
                consumeBreak()
                return DocumentValue.Mapping(entries)
            }
            val count = readUnsigned(minor)
            requireCount(count)
            val entries = ArrayList<Pair<DocumentValue, DocumentValue>>(count.toInt())
            repeat(count.toInt()) {
                val key = readItem()
                val value = readItem()
                entries.add(key to value)
            }
            return DocumentValue.Mapping(entries)
        }

        private fun readTagged(minor: Int): DocumentValue {
            readUnsigned(minor)
            return readItem()
        }

        private fun readSimpleOrFloat(minor: Int): DocumentValue = when (minor) {
            20 -> DocumentValue.Bool(false)
            21 -> DocumentValue.Bool(true)
            22, 23 -> DocumentValue.Null
            25 -> DocumentValue.Decimal(halfToDouble(readBigEndian(2)))
            26 -> DocumentValue.Decimal(Float.fromBits(readBigEndian(4).toInt()).toDouble())
            27 -> DocumentValue.Decimal(Double.fromBits(readBigEndian(8)))
            24 -> {
                nextByte()
                DocumentValue.Integer(minor.toLong())
            }
            else -> DocumentValue.Integer(minor.toLong())
        }

        private fun readByteString(minor: Int): ByteArray {
            if (minor == INDEFINITE) {
                val out = ArrayList<Byte>()
                while (!atBreak()) {
                    val chunk = readItem()
                    val chunkBytes = when (chunk) {
                        is DocumentValue.Bytes -> chunk.value
                        is DocumentValue.Text -> chunk.value.toByteArray(Charsets.UTF_8)
                        else -> throw MalformedCbor()
                    }
                    chunkBytes.forEach { out.add(it) }
                }
                consumeBreak()
                return out.toByteArray()
            }
            val length = readUnsigned(minor)
            requireCount(length)
            return take(length.toInt())
        }

        private fun readUnsigned(minor: Int): Long = when {
            minor < 24 -> minor.toLong()
            minor == 24 -> readBigEndian(1)
            minor == 25 -> readBigEndian(2)
            minor == 26 -> readBigEndian(4)
            minor == 27 -> readBigEndian(8)
            else -> throw MalformedCbor()
        }

        private fun readBigEndian(count: Int): Long {
            var result = 0L
            repeat(count) { result = (result shl 8) or (nextByte().toLong() and 0xFF) }
            return result
        }

        private fun atBreak(): Boolean = hasRemaining() && (bytes[pos].toInt() and 0xFF) == BREAK

        private fun consumeBreak() {
            if (!atBreak()) throw MalformedCbor()
            pos += 1
        }

        private fun nextByte(): Byte {
            if (pos >= bytes.size) throw MalformedCbor()
            return bytes[pos++]
        }

        private fun take(count: Int): ByteArray {
            if (count < 0 || pos + count > bytes.size) throw MalformedCbor()
            val out = bytes.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        private fun requireCount(count: Long) {
            if (count < 0 || count > Int.MAX_VALUE) throw MalformedCbor()
        }
    }

    private const val INDEFINITE = 31
    private const val BREAK = 0xFF

    private fun halfToDouble(bits: Long): Double {
        val half = bits.toInt() and 0xFFFF
        val sign = (half shr 15) and 0x1
        val exponent = (half shr 10) and 0x1F
        val mantissa = half and 0x3FF
        val value = when (exponent) {
            0 -> mantissa.toDouble() * Math.pow(2.0, -24.0)
            0x1F -> if (mantissa == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (mantissa + 1024).toDouble() * Math.pow(2.0, (exponent - 25).toDouble())
        }
        return if (sign == 1) -value else value
    }
}
