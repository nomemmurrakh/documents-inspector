package com.nomemmurrakh.documents.inspector.reader

/** A field within a document: its name and decoded value. */
internal data class Field(val name: String, val value: DocumentValue)

/** A document within a collection: its key and its fields, in first-seen order. */
internal data class Document(val key: String, val fields: List<Field>)

/** A collection: the display label plus its documents. */
internal data class Collection(val label: String, val documents: List<Document>)

/**
 * Turns the raw bytes of one MMKV collection file (plus its `.crc` sibling) into a [Collection] tree.
 *
 * A collection file holds keys of the form `"{documentKey}::{fieldName}"`. Keys are grouped by the
 * document key (the substring before the first [SEPARATOR]) into [Document]s. Each raw value is a
 * `byte[]` MMKV stored via `MMKV.encode`, so it carries MMKV's length prefix; that is stripped with
 * [Mmkv.unwrapByteArrayValue] to recover the Documents CBOR, which is then decoded into a
 * [DocumentValue]. The MMKV bytes are read via the ported [Mmkv] parser, whose body size comes from
 * the `.crc` file — see [Mmkv.readFile]. Insertion order is preserved so the tree mirrors write order.
 */
internal object CollectionReader {

    const val SEPARATOR = "::"
    const val DEFAULT_STORE_ID = "documents.default"
    const val DEFAULT_LABEL = "Default"

    /** The display label for a collection file: [DEFAULT_LABEL] for the default store, else its name. */
    fun labelFor(fileName: String): String =
        if (fileName == DEFAULT_STORE_ID) DEFAULT_LABEL else fileName

    /**
     * @param fileName the collection file's name (e.g. `documents.default` or a named collection).
     * @param mainFile the collection file bytes.
     * @param crcFile the sibling `<fileName>.crc` bytes (holds the authoritative body size).
     */
    fun read(fileName: String, mainFile: ByteArray, crcFile: ByteArray): Collection {
        val live = Mmkv.readFile(mainFile, crcFile)
        val grouped = LinkedHashMap<String, MutableList<Field>>()

        for ((key, value) in live) {
            val separatorAt = key.indexOf(SEPARATOR)
            if (separatorAt < 0) continue
            val documentKey = key.substring(0, separatorAt)
            val fieldName = key.substring(separatorAt + SEPARATOR.length)
            val cborBytes = Mmkv.unwrapByteArrayValue(value)
            grouped.getOrPut(documentKey) { mutableListOf() }
                .add(Field(fieldName, CborDecoder.decode(cborBytes)))
        }

        val documents = grouped.map { (documentKey, fields) -> Document(documentKey, fields) }
        return Collection(labelFor(fileName), documents)
    }
}
