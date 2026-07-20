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
import com.nomemmurrakh.documents.inspector.source.AdbDevices
import com.nomemmurrakh.documents.inspector.source.DeviceSource
import com.nomemmurrakh.documents.inspector.source.DeviceSourceException
import com.nomemmurrakh.documents.inspector.source.IosSimulator
import com.nomemmurrakh.documents.inspector.source.IosSimulators
import com.nomemmurrakh.documents.inspector.source.ProcessAdb
import com.nomemmurrakh.documents.inspector.source.ProcessSimctl
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

    private var lastTarget: Target? = null

    private class Target(
        val appLabel: String,
        val deviceName: String,
        val sourceFactory: () -> DeviceSource,
    )

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
            val target = lastTarget
            if (target == null) connect() else loadInBackground(target)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastTarget != null
        }
    }

    private fun connect() {
        choosePlatform { platform ->
            when (platform) {
                Platform.ANDROID -> connectAndroid()
                Platform.IOS_SIMULATOR -> connectIosSimulator()
            }
        }
    }

    private fun connectAndroid() {
        val devices = try {
            AdbDevices.list(ProcessAdb())
        } catch (ex: DeviceSourceException) {
            panel.showMessage(ex.message ?: "Could not list devices.")
            return
        }
        if (devices.isEmpty()) {
            panel.showMessage("No device or emulator is attached. Start one and try again.")
            return
        }
        chooseFrom(devices, "Select Device", { it.displayName }) { device ->
            val pkg = promptForApp("Application id of the debuggable app to inspect:") ?: return@chooseFrom
            loadInBackground(
                Target(pkg, device.displayName) {
                    InspectorController.androidSource(ProcessAdb(), pkg, device.serial)
                },
            )
        }
    }

    private fun connectIosSimulator() {
        val simulators = try {
            IosSimulators.listBooted(ProcessSimctl())
        } catch (ex: DeviceSourceException) {
            panel.showMessage(ex.message ?: "Could not list simulators.")
            return
        }
        if (simulators.isEmpty()) {
            panel.showMessage("No booted iOS simulator found. Boot one in Simulator and try again.")
            return
        }
        chooseFrom(simulators, "Select Simulator", { it.displayName }) { sim ->
            val bundleId = promptForApp("Bundle identifier of the app to inspect:") ?: return@chooseFrom
            loadInBackground(
                Target(bundleId, sim.displayName) {
                    InspectorController.iosSimulatorSource(ProcessSimctl(), sim.udid, bundleId)
                },
            )
        }
    }

    private fun promptForApp(prompt: String): String? =
        Messages.showInputDialog(project, prompt, "Documents Inspector", null, lastTarget?.appLabel, null)?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun choosePlatform(onChosen: (Platform) -> Unit) =
        chooseFrom(Platform.entries.toList(), "Select Platform", { it.label }, onChosen)

    private fun <T> chooseFrom(items: List<T>, title: String, label: (T) -> String, onChosen: (T) -> Unit) {
        if (items.size == 1) {
            onChosen(items.single())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(title)
            .setItemChosenCallback(onChosen)
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<T>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(label(value))
                }
            })
            .createPopup()
            .showInFocusCenter()
    }

    private fun loadInBackground(target: Target) {
        lastTarget = target
        panel.showMessage("Loading ${target.appLabel}…")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading Documents store", true) {
            private var result: LoadResult = LoadResult.Failed("Cancelled.")

            override fun run(indicator: ProgressIndicator) {
                result = InspectorController.load(target.sourceFactory())
            }

            override fun onSuccess() = render(result)
        })
    }

    private enum class Platform(val label: String) {
        ANDROID("Android device / emulator"),
        IOS_SIMULATOR("iOS simulator"),
    }

    private fun render(result: LoadResult) = ApplicationManager.getApplication().invokeLater {
        statusLabel.text = statusText()
        when (result) {
            is LoadResult.Loaded -> panel.show(result.collections)
            is LoadResult.Failed -> panel.showMessage(result.message)
        }
    }

    private fun statusText(): String {
        val target = lastTarget ?: return " "
        return "${target.appLabel}  •  ${target.deviceName}"
    }
}
