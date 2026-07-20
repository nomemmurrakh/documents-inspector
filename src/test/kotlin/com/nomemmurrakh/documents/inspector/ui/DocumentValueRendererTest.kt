package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.DocumentValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentValueRendererTest {

    @Test
    fun `scalars render inline`() {
        assertEquals("\"hello\"", DocumentValueRenderer.summary(DocumentValue.Text("hello")))
        assertEquals("42", DocumentValueRenderer.summary(DocumentValue.Integer(42)))
        assertEquals("3.5", DocumentValueRenderer.summary(DocumentValue.Decimal(3.5)))
        assertEquals("true", DocumentValueRenderer.summary(DocumentValue.Bool(true)))
        assertEquals("null", DocumentValueRenderer.summary(DocumentValue.Null))
    }

    @Test
    fun `opaque and bytes render as labeled blobs`() {
        assertEquals("🔒 opaque, 16 bytes", DocumentValueRenderer.summary(DocumentValue.Opaque(ByteArray(16))))
        assertEquals("bytes, 1 byte", DocumentValueRenderer.summary(DocumentValue.Bytes(ByteArray(1))))
    }

    @Test
    fun `containers summarize their size`() {
        val listing = DocumentValue.Listing(listOf(DocumentValue.Integer(1), DocumentValue.Integer(2)))
        assertEquals("[2 items]", DocumentValueRenderer.summary(listing))

        val single = DocumentValue.Listing(listOf(DocumentValue.Null))
        assertEquals("[1 item]", DocumentValueRenderer.summary(single))

        val mapping = DocumentValue.Mapping(
            listOf(DocumentValue.Text("a") to DocumentValue.Integer(1)),
        )
        assertEquals("{1 entry}", DocumentValueRenderer.summary(mapping))
    }

    @Test
    fun `only containers have children`() {
        assertTrue(DocumentValueRenderer.hasChildren(DocumentValue.Listing(emptyList())))
        assertTrue(DocumentValueRenderer.hasChildren(DocumentValue.Mapping(emptyList())))
        assertFalse(DocumentValueRenderer.hasChildren(DocumentValue.Integer(1)))
        assertFalse(DocumentValueRenderer.hasChildren(DocumentValue.Opaque(ByteArray(4))))
    }

    @Test
    fun `listing children are labeled by index`() {
        val listing = DocumentValue.Listing(listOf(DocumentValue.Text("x"), DocumentValue.Text("y")))
        val children = DocumentValueRenderer.children(listing)
        assertEquals(listOf("[0]", "[1]"), children.map { it.first })
        assertEquals(DocumentValue.Text("x"), children[0].second)
    }

    @Test
    fun `mapping children are labeled by their key`() {
        val mapping = DocumentValue.Mapping(
            listOf(
                DocumentValue.Text("name") to DocumentValue.Text("Ada"),
                DocumentValue.Integer(7) to DocumentValue.Bool(false),
            ),
        )
        val children = DocumentValueRenderer.children(mapping)
        assertEquals(listOf("name", "7"), children.map { it.first })
        assertEquals(DocumentValue.Text("Ada"), children[0].second)
    }

    @Test
    fun `scalars have no children`() {
        assertTrue(DocumentValueRenderer.children(DocumentValue.Integer(1)).isEmpty())
    }
}
