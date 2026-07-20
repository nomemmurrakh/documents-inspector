package com.nomemmurrakh.documents.inspector.ui

import com.nomemmurrakh.documents.inspector.reader.Collection
import com.nomemmurrakh.documents.inspector.reader.Document
import com.nomemmurrakh.documents.inspector.reader.DocumentValue
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/** What a tree node represents, and the label the cell shows (via [toString]). */
internal sealed interface InspectorNode {
    val label: String

    data class Root(val collectionCount: Int) : InspectorNode {
        override val label: String =
            if (collectionCount == 1) "1 collection" else "$collectionCount collections"
    }

    data class CollectionNode(val collection: Collection) : InspectorNode {
        override val label: String = collection.label
    }

    data class DocumentNode(val document: Document) : InspectorNode {
        override val label: String = document.key
    }

    /** A field, or a nested element inside a container field. [value] is the node's own value;
     * [name] is its field name or container key/index. */
    data class ValueNode(val name: String, val value: DocumentValue) : InspectorNode {
        override val label: String = "$name: ${DocumentValueRenderer.summary(value)}"
    }
}

/** Builds the Swing tree for the inspector: root → collections → documents → fields → nested values. */
internal object InspectorTreeModel {

    fun build(collections: List<Collection>): DefaultTreeModel =
        DefaultTreeModel(rootNode(collections))

    fun rootNode(collections: List<Collection>): TreeNode {
        val root = node(InspectorNode.Root(collections.size))
        for (collection in collections) root.add(collectionNode(collection))
        return root
    }

    private fun collectionNode(collection: Collection): DefaultMutableTreeNode {
        val node = node(InspectorNode.CollectionNode(collection))
        for (document in collection.documents) node.add(documentNode(document))
        return node
    }

    private fun documentNode(document: Document): DefaultMutableTreeNode {
        val node = node(InspectorNode.DocumentNode(document))
        for (field in document.fields) node.add(valueNode(field.name, field.value))
        return node
    }

    private fun valueNode(name: String, value: DocumentValue): DefaultMutableTreeNode {
        val node = node(InspectorNode.ValueNode(name, value))
        for ((childName, childValue) in DocumentValueRenderer.children(value)) {
            node.add(valueNode(childName, childValue))
        }
        return node
    }

    private fun node(payload: InspectorNode): DefaultMutableTreeNode =
        object : DefaultMutableTreeNode(payload) {
            override fun toString(): String = payload.label
        }
}
