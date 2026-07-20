package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.Collection
import com.nomemmurrakh.documents.inspector.reader.Document
import com.nomemmurrakh.documents.inspector.reader.DocumentValue
import com.nomemmurrakh.documents.inspector.reader.Field
import javax.swing.tree.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorTreeModelTest {

    private fun TreeNode.label(): String = toString()
    private fun TreeNode.child(i: Int): TreeNode = getChildAt(i)
    private fun TreeNode.childLabels(): List<String> =
        (0 until childCount).map { getChildAt(it).label() }

    @Test
    fun `builds root, collection, document, field levels`() {
        val collection = Collection(
            label = "Default",
            documents = listOf(
                Document(
                    key = "note-1",
                    fields = listOf(
                        Field("title", DocumentValue.Text("Hi")),
                        Field("done", DocumentValue.Bool(false)),
                    ),
                ),
            ),
        )

        val root = InspectorTreeModel.rootNode(listOf(collection))

        assertEquals("1 collection", root.label())
        val collectionNode = root.child(0)
        assertEquals("Default", collectionNode.label())
        val documentNode = collectionNode.child(0)
        assertEquals("note-1", documentNode.label())
        assertEquals(listOf("title: \"Hi\"", "done: false"), documentNode.childLabels())
    }

    @Test
    fun `root pluralizes the collection count`() {
        val a = Collection("a", emptyList())
        val b = Collection("b", emptyList())
        assertEquals("2 collections", InspectorTreeModel.rootNode(listOf(a, b)).label())
        assertEquals("0 collections", InspectorTreeModel.rootNode(emptyList()).label())
    }

    @Test
    fun `a container field expands into child nodes`() {
        val field = Field(
            "tags",
            DocumentValue.Listing(listOf(DocumentValue.Text("x"), DocumentValue.Text("y"))),
        )
        val document = Document("d", listOf(field))
        val collection = Collection("c", listOf(document))

        val documentNode = InspectorTreeModel.rootNode(listOf(collection)).child(0).child(0)
        val tagsNode = documentNode.child(0)

        assertEquals("tags: [2 items]", tagsNode.label())
        assertEquals(listOf("[0]: \"x\"", "[1]: \"y\""), tagsNode.childLabels())
    }

    @Test
    fun `nested mapping expands recursively`() {
        val field = Field(
            "meta",
            DocumentValue.Mapping(
                listOf(
                    DocumentValue.Text("author") to DocumentValue.Text("Ada"),
                    DocumentValue.Text("stars") to DocumentValue.Integer(5),
                ),
            ),
        )
        val node = InspectorTreeModel.rootNode(listOf(Collection("c", listOf(Document("d", listOf(field))))))
            .child(0).child(0).child(0)

        assertEquals("meta: {2 entries}", node.label())
        assertEquals(listOf("author: \"Ada\"", "stars: 5"), node.childLabels())
    }

    @Test
    fun `opaque field is a leaf with a blob label`() {
        val field = Field("token", DocumentValue.Opaque(ByteArray(24)))
        val node = InspectorTreeModel.rootNode(listOf(Collection("c", listOf(Document("d", listOf(field))))))
            .child(0).child(0).child(0)

        assertEquals("token: 🔒 opaque, 24 bytes", node.label())
        assertTrue(node.isLeaf)
    }
}
