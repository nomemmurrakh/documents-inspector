package com.nomemmurrakh.documents.inspector.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.nomemmurrakh.documents.inspector.InspectorController
import com.nomemmurrakh.documents.inspector.LoadResult
import com.nomemmurrakh.documents.inspector.source.AdbDevice
import com.nomemmurrakh.documents.inspector.source.AdbDevices
import com.nomemmurrakh.documents.inspector.source.DeviceSourceException
import com.nomemmurrakh.documents.inspector.source.ProcessAdb
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tool-window shell: the [InspectorPanel] tree plus a toolbar whose Connect / Refresh action
 * drives the acquisition flow — choose a device, enter the app package, pull + decode off the EDT,
 * then render the result (or a failure message). The last target is remembered so Refresh re-runs
 * without re-prompting.
 */
internal class InspectorView(private val project: Project) {

    private val panel = InspectorPanel(project)
    private val toolbar = createToolbar()
    private val statusLabel = com.intellij.ui.components.JBLabel(" ").apply {
        border = com.intellij.util.ui.JBUI.Borders.empty(0, 8)
        foreground = com.intellij.util.ui.JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.WEST)
                add(statusLabel, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(panel, BorderLayout.CENTER)
    }

    private var lastPackage: String? = null
    private var lastSerial: String? = null
    private var lastDeviceName: String? = null

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(ConnectAction())
            add(RefreshAction())
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("DocumentsInspector", group, true)
        actionToolbar.targetComponent = panel
        return actionToolbar.component
    }

    private inner class ConnectAction : AnAction("Connect", "Choose a device and app to inspect", AllIcons.Actions.Execute) {
        override fun actionPerformed(e: AnActionEvent) = connect()
    }

    private inner class RefreshAction : AnAction("Refresh", "Reload the current app", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            val pkg = lastPackage
            if (pkg == null) connect() else loadInBackground(pkg, lastSerial)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastPackage != null
        }
    }

    private fun connect() {
        val adb = ProcessAdb()
        val devices = try {
            AdbDevices.list(adb)
        } catch (ex: DeviceSourceException) {
            panel.showMessage(ex.message ?: "Could not list devices.")
            return
        }
        if (devices.isEmpty()) {
            panel.showMessage("No device or emulator is attached. Start one and try again.")
            return
        }

        chooseDevice(devices) { device ->
            val pkg = Messages.showInputDialog(
                project,
                "Application id of the debuggable app to inspect:",
                "Documents Inspector",
                null,
                lastPackage,
                null,
            )?.trim()
            if (pkg.isNullOrEmpty()) return@chooseDevice
            lastDeviceName = device.displayName
            loadInBackground(pkg, device.serial)
        }
    }

    private fun chooseDevice(devices: List<AdbDevice>, onChosen: (AdbDevice) -> Unit) {
        if (devices.size == 1) {
            onChosen(devices.single())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(devices)
            .setTitle("Select Device")
            .setItemChosenCallback(onChosen)
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<AdbDevice>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out AdbDevice>,
                    value: AdbDevice,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value.displayName)
                }
            })
            .createPopup()
            .showInFocusCenter()
    }

    private fun loadInBackground(pkg: String, serial: String?) {
        lastPackage = pkg
        lastSerial = serial
        panel.showMessage("Loading $pkg…")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading Documents store", true) {
            private var result: LoadResult = LoadResult.Failed("Cancelled.")

            override fun run(indicator: ProgressIndicator) {
                val source = InspectorController.androidSource(ProcessAdb(), pkg, serial)
                result = InspectorController.load(source)
            }

            override fun onSuccess() = render(result)
        })
    }

    private fun render(result: LoadResult) = ApplicationManager.getApplication().invokeLater {
        statusLabel.text = statusText()
        when (result) {
            is LoadResult.Loaded -> panel.show(result.collections)
            is LoadResult.Failed -> panel.showMessage(result.message)
        }
    }

    private fun statusText(): String {
        val pkg = lastPackage ?: return " "
        val device = lastDeviceName
        return if (device != null) "$pkg  •  $device" else pkg
    }
}
