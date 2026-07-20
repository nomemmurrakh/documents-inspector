package com.nomemmurrakh.documents.inspector.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.nomemmurrakh.documents.inspector.reader.Collection
import com.nomemmurrakh.documents.inspector.reader.Document
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The tool-window content: a three-pane master-detail — collections, then the selected collection's
 * documents, then the selected document rendered per a view-mode dropdown (JSON or plain text). Each
 * pane has a header; the JSON view is syntax-highlighted via [ContentEditor]. A [CardLayout] swaps
 * between the content and a message card (empty text) for empty / error states.
 */
internal class InspectorPanel(project: Project) : JPanel(CardLayout()) {

    private val collectionsModel = DefaultListModel<Collection>()
    private val collectionsList = JBList(collectionsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = collectionRenderer()
        emptyText.text = "No collections"
    }

    private val documentsModel = DefaultListModel<Document>()
    private val documentsList = JBList(documentsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = documentRenderer()
        emptyText.text = "Select a collection"
    }

    private val viewMode = JComboBox(DefaultComboBoxModel(ViewMode.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val editor = ContentEditor(project)

    private val contentTitle = boldLabel("Document", null)

    private val message = com.intellij.ui.components.JBPanelWithEmptyText()

    init {
        collectionsList.addListSelectionListener { if (!it.valueIsAdjusting) onCollectionSelected() }
        documentsList.addListSelectionListener { if (!it.valueIsAdjusting) renderDocument() }
        viewMode.addActionListener { renderDocument() }

        add(buildContentCard(), CONTENT)
        add(message, MESSAGE)
        showMessage("Connect a debuggable app to inspect its Documents store.")
    }

    fun show(collections: List<Collection>) {
        if (collections.isEmpty()) {
            showMessage("No collections found for this app.")
            return
        }
        collectionsModel.clear()
        collections.forEach(collectionsModel::addElement)
        documentsModel.clear()
        contentTitle.text = "Document"
        editor.setText("", ViewMode.JSON)
        card().show(this, CONTENT)
        collectionsList.selectedIndex = 0
    }

    fun showMessage(text: String) {
        message.emptyText.text = text
        card().show(this, MESSAGE)
    }

    private fun onCollectionSelected() {
        documentsModel.clear()
        editor.setText("", currentMode())
        val collection = collectionsList.selectedValue ?: return
        collection.documents.forEach(documentsModel::addElement)
        if (!documentsModel.isEmpty) documentsList.selectedIndex = 0
    }

    private fun renderDocument() {
        val document = documentsList.selectedValue
        val mode = currentMode()
        contentTitle.text = document?.key ?: "Document"
        editor.setText(if (document == null) "" else DocumentView.render(document, mode), mode)
    }

    private fun currentMode(): ViewMode = viewMode.selectedItem as? ViewMode ?: ViewMode.JSON

    private fun buildContentCard(): JComponent {
        val collectionsPane = paneWithHeader(headerBar(boldLabel("Collections", null), null), JBScrollPane(collectionsList))
        val documentsPane = paneWithHeader(headerBar(boldLabel("Documents", null), null), JBScrollPane(documentsList))
        val contentPane = paneWithHeader(headerBar(contentTitle, viewMode), editor.component)

        val documentsAndContent = JBSplitter(false, 0.4f).apply {
            firstComponent = documentsPane
            secondComponent = contentPane
        }
        val panes = JBSplitter(false, 0.25f).apply {
            firstComponent = collectionsPane
            secondComponent = documentsAndContent
        }
        return panes
    }

    private fun paneWithHeader(header: JComponent, body: JComponent): JComponent =
        JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

    private fun headerBar(title: JComponent, trailing: JComponent?): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(2, 6),
            )
            add(title, BorderLayout.WEST)
            if (trailing != null) add(trailing, BorderLayout.EAST)
            preferredSize = java.awt.Dimension(0, HEADER_HEIGHT)
            minimumSize = java.awt.Dimension(0, HEADER_HEIGHT)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, HEADER_HEIGHT)
        }

    private fun boldLabel(text: String, icon: javax.swing.Icon?): JBLabel =
        JBLabel(text, icon ?: com.intellij.util.ui.EmptyIcon.ICON_0, JBLabel.LEFT).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }

    private fun collectionRenderer(): SimpleListCellRenderer<Collection> =
        object : SimpleListCellRenderer<Collection>() {
            override fun customize(list: javax.swing.JList<out Collection>, value: Collection?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                text = value.label
                icon = AllIcons.Nodes.DataTables
            }
        }

    private fun documentRenderer(): SimpleListCellRenderer<Document> =
        object : SimpleListCellRenderer<Document>() {
            override fun customize(list: javax.swing.JList<out Document>, value: Document?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                text = value.key
                icon = if (DocumentView.hasMaskedField(value)) AllIcons.Nodes.SecurityRole else AllIcons.FileTypes.Json
            }
        }

    private fun card(): CardLayout = layout as CardLayout

    private companion object {
        const val CONTENT = "content"
        const val MESSAGE = "message"
        val HEADER_HEIGHT = JBUI.scale(30)
    }
}
