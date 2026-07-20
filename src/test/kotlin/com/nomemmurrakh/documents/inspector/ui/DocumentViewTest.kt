package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.Document
import com.nomemmurrakh.documents.inspector.reader.DocumentValue
import com.nomemmurrakh.documents.inspector.reader.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentViewTest {

    private fun doc(vararg fields: Field) = Document("k", fields.toList())

    @Test
    fun `json renders scalars in field order`() {
        val document = doc(
            Field("title", DocumentValue.Text("Buy milk")),
            Field("count", DocumentValue.Integer(3)),
            Field("ratio", DocumentValue.Decimal(1.5)),
            Field("done", DocumentValue.Bool(false)),
            Field("note", DocumentValue.Null),
        )

        assertEquals(
            """
            {
              "title": "Buy milk",
              "count": 3,
              "ratio": 1.5,
              "done": false,
              "note": null
            }
            """.trimIndent(),
            DocumentView.render(document, ViewMode.JSON),
        )
    }

    @Test
    fun `json nests arrays and objects`() {
        val document = doc(
            Field("tags", DocumentValue.Listing(listOf(DocumentValue.Text("a"), DocumentValue.Text("b")))),
            Field(
                "meta",
                DocumentValue.Mapping(
                    listOf(
                        DocumentValue.Text("author") to DocumentValue.Text("Ada"),
                        DocumentValue.Text("stars") to DocumentValue.Integer(5),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            {
              "tags": [
                "a",
                "b"
              ],
              "meta": {
                "author": "Ada",
                "stars": 5
              }
            }
            """.trimIndent(),
            DocumentView.render(document, ViewMode.JSON),
        )
    }

    @Test
    fun `opaque and bytes are masked with length-hinted asterisks`() {
        val document = doc(
            Field("authToken", DocumentValue.Opaque(ByteArray(12))),
            Field("small", DocumentValue.Bytes(ByteArray(2))),
            Field("huge", DocumentValue.Opaque(ByteArray(100))),
        )

        val json = DocumentView.render(document, ViewMode.JSON)
        assertTrue(json.contains("\"authToken\": \"${"*".repeat(12)}\""))
        assertTrue(json.contains("\"small\": \"${"*".repeat(6)}\""), "min mask is 6")
        assertTrue(json.contains("\"huge\": \"${"*".repeat(24)}\""), "max mask is 24")
    }

    @Test
    fun `json escapes special characters in strings`() {
        val document = doc(Field("path", DocumentValue.Text("a\"b\\c\nd")))
        assertEquals(
            "{\n  \"path\": \"a\\\"b\\\\c\\nd\"\n}",
            DocumentView.render(document, ViewMode.JSON),
        )
    }

    @Test
    fun `empty document is an empty json object`() {
        assertEquals("{}", DocumentView.render(doc(), ViewMode.JSON))
    }

    @Test
    fun `plain text renders one field per line`() {
        val document = doc(
            Field("title", DocumentValue.Text("Buy milk")),
            Field("done", DocumentValue.Bool(true)),
            Field("token", DocumentValue.Opaque(ByteArray(8))),
        )

        assertEquals(
            "title: Buy milk\ndone: true\ntoken: ${"*".repeat(8)}",
            DocumentView.render(document, ViewMode.PLAIN),
        )
    }

    @Test
    fun `plain text inlines containers`() {
        val document = doc(
            Field("tags", DocumentValue.Listing(listOf(DocumentValue.Text("a"), DocumentValue.Integer(1)))),
        )
        assertEquals("tags: [a, 1]", DocumentView.render(document, ViewMode.PLAIN))
    }

    @Test
    fun `empty plain document is labeled`() {
        assertEquals("(no fields)", DocumentView.render(doc(), ViewMode.PLAIN))
    }

    @Test
    fun `hasMaskedField detects opaque or bytes fields`() {
        assertTrue(DocumentView.hasMaskedField(doc(Field("t", DocumentValue.Opaque(ByteArray(4))))))
        assertTrue(DocumentView.hasMaskedField(doc(Field("b", DocumentValue.Bytes(ByteArray(4))))))
        assertTrue(!DocumentView.hasMaskedField(doc(Field("n", DocumentValue.Text("x")))))
    }
}
