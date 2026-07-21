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
import com.nomemmurrakh.documents.inspector.AppIdDetector
import com.nomemmurrakh.documents.inspector.InspectorController
import com.nomemmurrakh.documents.inspector.LoadResult
import com.nomemmurrakh.documents.inspector.source.AdbDevices
import com.nomemmurrakh.documents.inspector.source.AppIdCandidates
import com.nomemmurrakh.documents.inspector.source.DeviceSource
import com.nomemmurrakh.documents.inspector.source.DeviceSourceException
import com.nomemmurrakh.documents.inspector.source.IosDevices
import com.nomemmurrakh.documents.inspector.source.IosSimulator
import com.nomemmurrakh.documents.inspector.source.IosSimulators
import com.nomemmurrakh.documents.inspector.source.ProcessAdb
import com.nomemmurrakh.documents.inspector.source.ProcessDevicectl
import com.nomemmurrakh.documents.inspector.source.ProcessSimctl
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tool-window shell: the [InspectorPanel] tree plus a toolbar whose Connect / Refresh action
 * drives the acquisition flow — choose a device, confirm the app id, pull + decode off the EDT,
 * then render the result (or a failure message). The last target is remembered so Refresh re-runs
 * without re-prompting.
 *
 * The app-id prompt is prefilled from the open project via [AppIdDetector] and stays editable, so
 * an app whose sources are not open can still be inspected. See
 * `docs/adr/0008-prefill-app-id-from-the-ide.md`.
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
                Platform.IOS_DEVICE -> connectIosDevice()
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
            withCandidates({ AppIdDetector.androidCandidates(project) }) { candidates ->
                val pkg = promptForApp(
                    "Application id of the debuggable app to inspect:",
                    candidates,
                ) ?: return@withCandidates
                loadInBackground(
                    Target(pkg, device.displayName) {
                        InspectorController.androidSource(ProcessAdb(), pkg, device.serial)
                    },
                )
            }
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
            val detect = {
                AppIdCandidates.merge(
                    AppIdDetector.iosCandidates(project),
                    IosSimulators.listApps(ProcessSimctl(), sim.udid),
                )
            }
            withCandidates(detect) { candidates ->
                val bundleId = promptForApp(
                    "Bundle identifier of the app to inspect:",
                    candidates,
                ) ?: return@withCandidates
                loadInBackground(
                    Target(bundleId, sim.displayName) {
                        InspectorController.iosSimulatorSource(ProcessSimctl(), sim.udid, bundleId)
                    },
                )
            }
        }
    }

    private fun connectIosDevice() {
        inBackground("Listing iOS devices", { runCatching { IosDevices.list(ProcessDevicectl()) } }) { listed ->
            val devices = listed.getOrElse { ex ->
                panel.showMessage((ex as? DeviceSourceException)?.message ?: "Could not list iOS devices.")
                return@inBackground
            }
            if (devices.isEmpty()) {
                panel.showMessage("No paired iOS device found. Connect one and trust this Mac, then try again.")
                return@inBackground
            }
            chooseFrom(devices, "Select Device", { it.displayName }) { device ->
                val detect = {
                    AppIdCandidates.merge(
                        AppIdDetector.iosCandidates(project),
                        IosDevices.listApps(ProcessDevicectl(), device.identifier),
                    )
                }
                withCandidates(detect) { candidates ->
                    val bundleId = promptForApp(
                        "Bundle identifier of the app to inspect:",
                        candidates,
                    ) ?: return@withCandidates
                    loadInBackground(
                        Target(bundleId, device.displayName) {
                            InspectorController.iosDeviceSource(ProcessDevicectl(), device.identifier, bundleId)
                        },
                    )
                }
            }
        }
    }

    private fun <T> inBackground(title: String, work: () -> T, onReady: (T) -> Unit) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, false) {
                private var outcome: Result<T> = Result.failure(IllegalStateException("Not run"))

                override fun run(indicator: ProgressIndicator) {
                    outcome = runCatching(work)
                }

                override fun onFinished() {
                    val value = outcome.getOrNull() ?: return
                    ApplicationManager.getApplication().invokeLater { onReady(value) }
                }
            },
        )
    }

    private fun withCandidates(detect: () -> List<String>, onReady: (List<String>) -> Unit) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Detecting app ids", false) {
                private var candidates: List<String> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    candidates = runCatching(detect).getOrDefault(emptyList())
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater { onReady(candidates) }
                }
            },
        )
    }

    private fun promptForApp(prompt: String, candidates: List<String>): String? {
        val entered = if (candidates.isEmpty()) {
            Messages.showInputDialog(project, prompt, DIALOG_TITLE, null, lastTarget?.appLabel, null)
        } else {
            val offered = AppIdCandidates.merge(listOfNotNull(lastTarget?.appLabel), candidates)
            Messages.showEditableChooseDialog(
                prompt,
                DIALOG_TITLE,
                null,
                offered.toTypedArray(),
                offered.first(),
                null,
            )
        }
        return entered?.trim()?.takeIf { it.isNotEmpty() }
    }

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
        IOS_DEVICE("iOS device"),
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

    private companion object {
        const val DIALOG_TITLE = "Documents Inspector"
    }
}
