package com.nomemmurrakh.documents.inspector

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel
import javax.swing.SwingConstants

internal class InspectorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val placeholder = JLabel("Documents Inspector — scaffold", SwingConstants.CENTER)
        val content = ContentFactory.getInstance().createContent(placeholder, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
