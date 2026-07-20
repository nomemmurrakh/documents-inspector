package com.nomemmurrakh.documents.inspector

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.nomemmurrakh.documents.inspector.ui.InspectorView

internal class InspectorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = InspectorView(project)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
