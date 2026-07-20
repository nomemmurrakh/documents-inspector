package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.Document
import com.nomemmurrakh.documents.inspector.reader.DocumentValue

/** The ways the content pane can render a document. */
internal enum class ViewMode(val label: String) {
    JSON("JSON"),
    PLAIN("Plain text"),
}

/**
 * Renders a whole [Document] to the text shown in the content pane, in a chosen [ViewMode].
 *
 * A document's fields form the top-level object. `Listing` becomes a JSON array, `Mapping` a JSON
 * object, scalars their natural JSON forms, `Null` becomes `null`. `Opaque`/`Bytes` values cannot
 * be decoded (a `FieldDecorator` transformed them — see
 * `docs/adr/0004-opaque-decorated-and-encrypted-fields.md`), so they are masked as asterisks rather
 * than shown; the mask length hints at the byte size. Both views keep insertion order.
 */
internal object DocumentView {

    private const val INDENT = "  "
    private const val MIN_MASK = 6
    private const val MAX_MASK = 24

    fun render(document: Document, mode: ViewMode): String = when (mode) {
        ViewMode.JSON -> json(document)
        ViewMode.PLAIN -> plain(document)
    }

    private fun json(document: Document): String {
        if (document.fields.isEmpty()) return "{}"
        val body = document.fields.joinToString(",\n") { field ->
            "$INDENT${quote(field.name)}: ${jsonValue(field.value, 1)}"
        }
        return "{\n$body\n}"
    }

    private fun jsonValue(value: DocumentValue, depth: Int): String = when (value) {
        is DocumentValue.Text -> quote(value.value)
        is DocumentValue.Integer -> value.value.toString()
        is DocumentValue.Decimal -> value.value.toString()
        is DocumentValue.Bool -> value.value.toString()
        DocumentValue.Null -> "null"
        is DocumentValue.Bytes -> quote(mask(value.value.size))
        is DocumentValue.Opaque -> quote(mask(value.value.size))
        is DocumentValue.Listing -> jsonArray(value.items, depth)
        is DocumentValue.Mapping -> jsonObject(value.entries, depth)
    }

    private fun jsonArray(items: List<DocumentValue>, depth: Int): String {
        if (items.isEmpty()) return "[]"
        val pad = INDENT.repeat(depth + 1)
        val close = INDENT.repeat(depth)
        val body = items.joinToString(",\n") { "$pad${jsonValue(it, depth + 1)}" }
        return "[\n$body\n$close]"
    }

    private fun jsonObject(entries: List<Pair<DocumentValue, DocumentValue>>, depth: Int): String {
        if (entries.isEmpty()) return "{}"
        val pad = INDENT.repeat(depth + 1)
        val close = INDENT.repeat(depth)
        val body = entries.joinToString(",\n") { (key, value) ->
            "$pad${quote(keyText(key))}: ${jsonValue(value, depth + 1)}"
        }
        return "{\n$body\n$close}"
    }

    private fun plain(document: Document): String {
        if (document.fields.isEmpty()) return "(no fields)"
        return document.fields.joinToString("\n") { field ->
            "${field.name}: ${plainValue(field.value)}"
        }
    }

    private fun plainValue(value: DocumentValue): String = when (value) {
        is DocumentValue.Text -> value.value
        is DocumentValue.Integer -> value.value.toString()
        is DocumentValue.Decimal -> value.value.toString()
        is DocumentValue.Bool -> value.value.toString()
        DocumentValue.Null -> "null"
        is DocumentValue.Bytes -> mask(value.value.size)
        is DocumentValue.Opaque -> mask(value.value.size)
        is DocumentValue.Listing -> "[" + value.items.joinToString(", ") { plainValue(it) } + "]"
        is DocumentValue.Mapping ->
            "{" + value.entries.joinToString(", ") { (k, v) -> "${keyText(k)}: ${plainValue(v)}" } + "}"
    }

    private fun keyText(key: DocumentValue): String = when (key) {
        is DocumentValue.Text -> key.value
        is DocumentValue.Integer -> key.value.toString()
        is DocumentValue.Bool -> key.value.toString()
        else -> plainValue(key)
    }

    private fun mask(byteSize: Int): String =
        "*".repeat(byteSize.coerceIn(MIN_MASK, MAX_MASK))

    private fun quote(text: String): String {
        val escaped = buildString {
            for (ch in text) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        return "\"$escaped\""
    }
}
