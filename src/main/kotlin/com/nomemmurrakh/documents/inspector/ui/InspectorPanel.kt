package com.nomemmurrakh.documents.inspector.ui

import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.nomemmurrakh.documents.inspector.reader.Collection
import com.nomemmurrakh.documents.inspector.reader.Document
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The tool-window content: a three-pane master-detail — collections, then the selected collection's
 * documents, then the selected document rendered per a view-mode dropdown (JSON or plain text). A
 * message card covers empty and error states (no device, app not debuggable, no collections — T3.4);
 * a [CardLayout] swaps between the content and the message. [show] renders a loaded store,
 * [showMessage] renders text.
 */
internal class InspectorPanel : JPanel(CardLayout()) {

    private val collectionsModel = DefaultListModel<Collection>()
    private val collectionsList = JBList(collectionsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = listRenderer { it.label }
    }

    private val documentsModel = DefaultListModel<Document>()
    private val documentsList = JBList(documentsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = listRenderer { it.key }
    }

    private val viewMode = JComboBox(DefaultComboBoxModel(ViewMode.entries.toTypedArray())).apply {
        renderer = listRenderer { it.label }
    }
    private val content = JBTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    private val message = JLabel("", SwingConstants.CENTER)

    init {
        collectionsList.addListSelectionListener { if (!it.valueIsAdjusting) onCollectionSelected() }
        documentsList.addListSelectionListener { if (!it.valueIsAdjusting) renderDocument() }
        viewMode.addActionListener { renderDocument() }

        add(buildContentCard(), CONTENT)
        add(JPanel(BorderLayout()).apply { add(message, BorderLayout.CENTER) }, MESSAGE)
        showMessage("Connect a debuggable app to inspect its Documents store.")
    }

    fun show(collections: List<Collection>) {
        if (collections.isEmpty()) {
            showMessage("No collections found.")
            return
        }
        collectionsModel.clear()
        collections.forEach(collectionsModel::addElement)
        documentsModel.clear()
        content.text = ""
        card().show(this, CONTENT)
        collectionsList.selectedIndex = 0
    }

    fun showMessage(text: String) {
        message.text = text
        card().show(this, MESSAGE)
    }

    private fun onCollectionSelected() {
        documentsModel.clear()
        content.text = ""
        val collection = collectionsList.selectedValue ?: return
        collection.documents.forEach(documentsModel::addElement)
        if (!documentsModel.isEmpty) documentsList.selectedIndex = 0
    }

    private fun renderDocument() {
        val document = documentsList.selectedValue
        val mode = viewMode.selectedItem as? ViewMode ?: ViewMode.JSON
        content.text = if (document == null) "" else DocumentView.render(document, mode)
        content.caretPosition = 0
    }

    private fun buildContentCard(): JBSplitter {
        val documentsPane = JPanel(BorderLayout()).apply {
            add(JBScrollPane(documentsList), BorderLayout.CENTER)
        }
        val contentPane = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JLabel("View:"))
                    add(viewMode)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(content), BorderLayout.CENTER)
        }

        val documentsAndContent = JBSplitter(false, 0.4f).apply {
            firstComponent = documentsPane
            secondComponent = contentPane
        }
        return JBSplitter(false, 0.25f).apply {
            firstComponent = JBScrollPane(collectionsList)
            secondComponent = documentsAndContent
        }
    }

    private fun card(): CardLayout = layout as CardLayout

    private fun <T> listRenderer(text: (T) -> String): SimpleListCellRenderer<T> =
        SimpleListCellRenderer.create("") { text(it) }

    private companion object {
        const val CONTENT = "content"
        const val MESSAGE = "message"
    }
}
