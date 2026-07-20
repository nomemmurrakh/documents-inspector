package com.nomemmurrakh.documents.inspector.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.nomemmurrakh.documents.inspector.reader.Collection
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The tool-window content: a tree of collections → documents → fields, with a message card for
 * empty and error states. Swaps between the two via a [CardLayout]; [show] renders a loaded store,
 * [showMessage] renders text (no device, app not debuggable, no collections — T3.4).
 */
internal class InspectorPanel : JPanel(CardLayout()) {

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    private val message = JLabel("", SwingConstants.CENTER)

    private val treeCard = JPanel(BorderLayout()).apply { add(JBScrollPane(tree), BorderLayout.CENTER) }
    private val messageCard = JPanel(BorderLayout()).apply { add(message, BorderLayout.CENTER) }

    init {
        tree.isRootVisible = true
        add(treeCard, TREE)
        add(messageCard, MESSAGE)
        showMessage("Connect a debuggable app to inspect its Documents store.")
    }

    fun show(collections: List<Collection>) {
        if (collections.isEmpty()) {
            showMessage("No collections found.")
            return
        }
        tree.model = InspectorTreeModel.build(collections)
        card().show(this, TREE)
    }

    fun showMessage(text: String) {
        message.text = text
        card().show(this, MESSAGE)
    }

    private fun card(): CardLayout = layout as CardLayout

    private companion object {
        const val TREE = "tree"
        const val MESSAGE = "message"
    }
}
