package com.nomemmurrakh.documents.inspector.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A read-only editor for the content pane that renders text with syntax highlighting when a file
 * type is known (JSON in the [ViewMode.JSON] view), falling back to plain text otherwise. Uses a
 * real IntelliJ editor, so the JSON view gets coloring, brace matching, folding, and find — not a
 * bare text area.
 *
 * The JSON [com.intellij.openapi.fileTypes.FileType] is resolved by extension at runtime rather than
 * compiled against, so the plugin has no hard dependency on the JSON language plugin; without it, the
 * view still works as plain, uncolored text.
 */
internal class ContentEditor(private val project: Project) {

    private val editorFactory = EditorFactory.getInstance()
    private val document = editorFactory.createDocument("")
    private val editor = (editorFactory.createEditor(document, project) as EditorEx).apply {
        isViewer = true
        settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = true
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isRightMarginShown = false
        }
    }

    val component: JComponent = JPanel(BorderLayout()).apply { add(editor.component, BorderLayout.CENTER) }

    /** Replaces the text and applies highlighting appropriate to [mode]. */
    fun setText(text: String, mode: ViewMode) {
        applyHighlighter(mode)
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            document.setText(text.replace("\r\n", "\n"))
        }
        editor.caretModel.moveToOffset(0)
        editor.scrollingModel.scrollVertically(0)
    }

    fun releaseEditor() {
        editorFactory.releaseEditor(editor)
    }

    private fun applyHighlighter(mode: ViewMode) {
        val fileType = when (mode) {
            ViewMode.JSON -> FileTypeManager.getInstance().getFileTypeByExtension("json")
                .takeUnless { it is UnknownFileType }
            ViewMode.PLAIN -> PlainTextFileType.INSTANCE
        } ?: PlainTextFileType.INSTANCE

        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null)
        if (highlighter != null) {
            editor.highlighter = com.intellij.openapi.editor.ex.util.LexerEditorHighlighter(
                highlighter,
                editor.colorsScheme,
            )
        }
    }
}
