package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.DocumentValue

/**
 * Turns a [DocumentValue] into the strings shown in the tree.
 *
 * Scalars ([DocumentValue.Text], [DocumentValue.Integer], [DocumentValue.Decimal],
 * [DocumentValue.Bool], [DocumentValue.Null]) render inline as `name: value`. Containers
 * ([DocumentValue.Listing], [DocumentValue.Mapping]) render a summary on their own node and expand
 * into children. [DocumentValue.Opaque] and [DocumentValue.Bytes] render as labeled blobs — an
 * opaque value is the expected shape for a `FieldDecorator`-encrypted field, per
 * `docs/adr/0004-opaque-decorated-and-encrypted-fields.md`.
 */
internal object DocumentValueRenderer {

    /** The one-line summary for a value, used as a node label (without any field-name prefix). */
    fun summary(value: DocumentValue): String = when (value) {
        is DocumentValue.Text -> "\"${value.value}\""
        is DocumentValue.Integer -> value.value.toString()
        is DocumentValue.Decimal -> value.value.toString()
        is DocumentValue.Bool -> value.value.toString()
        DocumentValue.Null -> "null"
        is DocumentValue.Bytes -> blob("bytes", value.value.size)
        is DocumentValue.Opaque -> blob("🔒 opaque", value.value.size)
        is DocumentValue.Listing -> "[${count(value.items.size, "item", "items")}]"
        is DocumentValue.Mapping -> "{${count(value.entries.size, "entry", "entries")}}"
    }

    /** Whether a value expands into child nodes (containers do; scalars and blobs do not). */
    fun hasChildren(value: DocumentValue): Boolean =
        value is DocumentValue.Listing || value is DocumentValue.Mapping

    /**
     * The child nodes of a container, each a (label, value) pair. A [DocumentValue.Listing] labels
     * children by index; a [DocumentValue.Mapping] labels them by their rendered key. Empty for
     * non-containers.
     */
    fun children(value: DocumentValue): List<Pair<String, DocumentValue>> = when (value) {
        is DocumentValue.Listing -> value.items.mapIndexed { index, item -> "[$index]" to item }
        is DocumentValue.Mapping -> value.entries.map { (key, entry) -> keyLabel(key) to entry }
        else -> emptyList()
    }

    private fun keyLabel(key: DocumentValue): String = when (key) {
        is DocumentValue.Text -> key.value
        else -> summary(key)
    }

    private fun blob(kind: String, size: Int): String = "$kind, ${count(size, "byte", "bytes")}"

    private fun count(n: Int, singular: String, plural: String): String =
        if (n == 1) "1 $singular" else "$n $plural"
}
