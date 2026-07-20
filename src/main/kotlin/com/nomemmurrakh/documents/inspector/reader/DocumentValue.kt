package com.nomemmurrakh.documents.inspector.reader

/**
 * A decoded field value, rendered without knowledge of the original data class. CBOR is
 * self-describing, so a value's shape (number, text, list, map, …) is recoverable from the bytes
 * alone; the source type *name* is not stored and is never present here.
 */
internal sealed interface DocumentValue {

    data class Text(val value: String) : DocumentValue

    data class Integer(val value: Long) : DocumentValue

    data class Decimal(val value: Double) : DocumentValue

    data class Bool(val value: Boolean) : DocumentValue

    data object Null : DocumentValue

    data class Bytes(val value: ByteArray) : DocumentValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Listing(val items: List<DocumentValue>) : DocumentValue

    data class Mapping(val entries: List<Pair<DocumentValue, DocumentValue>>) : DocumentValue

    /**
     * Bytes that could not be interpreted as CBOR — e.g. a value written through a
     * `FieldDecorator` (encryption/compression), which is opaque on disk by design.
     */
    data class Opaque(val value: ByteArray) : DocumentValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Opaque && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }
}
