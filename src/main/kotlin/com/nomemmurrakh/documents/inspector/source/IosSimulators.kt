package com.nomemmurrakh.documents.inspector.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A booted iOS simulator, ready for `get_app_container`. */
internal data class IosSimulator(val udid: String, val name: String, val os: String) {
    val displayName: String get() = if (os.isBlank()) name else "$name ($os)"
}

/**
 * Enumerates booted iOS simulators and resolves an app's data container, via `xcrun simctl`.
 *
 * Only **booted** simulators are returned — `get_app_container` resolves reliably against a running
 * device, and a shut-down simulator is not an inspection target. The runtime key that groups devices
 * (e.g. `com.apple.CoreSimulator.SimRuntime.iOS-26-1`) yields the [IosSimulator.os] label.
 */
internal object IosSimulators {

    private val json = Json { ignoreUnknownKeys = true }

    /** Lists booted simulators via [simctl]. Throws [DeviceSourceException] if `simctl` fails. */
    fun listBooted(simctl: Simctl): List<IosSimulator> {
        val result = simctl.run(listOf("list", "devices", "booted", "-j"))
        if (!result.isSuccess) {
            throw DeviceSourceException(
                if (result.stderr.isNotEmpty()) "xcrun simctl failed to list simulators: ${result.stderr}"
                else "xcrun simctl failed to list simulators (exit ${result.exitCode}).",
            )
        }
        return parse(result.stdout)
    }

    /** Parses `simctl list devices booted -j` into booted simulators. */
    fun parse(jsonText: String): List<IosSimulator> {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val devices = root["devices"]?.jsonObject ?: return emptyList()
        val result = mutableListOf<IosSimulator>()
        for ((runtimeKey, entry) in devices) {
            val os = osLabel(runtimeKey)
            for (device in entry.jsonArray) {
                val obj = device.jsonObject
                val state = obj["state"]?.jsonPrimitive?.content
                if (state != "Booted") continue
                val udid = obj["udid"]?.jsonPrimitive?.content ?: continue
                val name = obj["name"]?.jsonPrimitive?.content ?: udid
                result.add(IosSimulator(udid, name, os))
            }
        }
        return result
    }

    /**
     * Lists the bundle identifiers of **user-installed** apps on booted simulator [udid], for
     * prefilling the Connect prompt. See `docs/adr/0008-prefill-app-id-from-the-ide.md`.
     *
     * Apps that already hold a Documents store are returned **first**, so the prompt preselects a
     * plausible target rather than whichever bundle id happens to sort first. Without this, an
     * unrelated app installed on the simulator can win the preselection purely by name.
     *
     * Two quirks of `simctl listapps` are handled here. It emits NeXTSTEP plist even when asked for
     * JSON, so the output is converted with [plist] first. And it **exits 0 even on failure** (for
     * example against a shut-down simulator), so the exit code is deliberately ignored — output that
     * does not parse simply yields no candidates. This is best-effort discovery: it never throws.
     */
    fun listApps(
        simctl: Simctl,
        udid: String,
        plist: PlistToJson = ProcessPlutil,
        files: CollectionFiles = LocalFiles,
    ): List<String> =
        runCatching {
            val result = simctl.run(listOf("listapps", udid))
            val converted = plist.convert(result.stdout) ?: return emptyList()
            val apps = AppIdCandidates.parseListappsEntries(converted)
            val (withStore, rest) = apps.partition { hasDocumentsStore(it, files) }
            (withStore + rest).map { it.bundleId }
        }.getOrDefault(emptyList())

    private fun hasDocumentsStore(app: InstalledApp, files: CollectionFiles): Boolean {
        val container = app.containerPath ?: return false
        return files.listNames("$container/${IosSimulatorSource.MMKV_SUBPATH}").isNotEmpty()
    }

    /**
     * Resolves the data container path of [bundleId] on simulator [udid]
     * (`get_app_container <udid> <bundleId> data`). Throws [DeviceSourceException] with a clear
     * message when the app is not installed or the simulator is unavailable.
     */
    fun appContainer(simctl: Simctl, udid: String, bundleId: String): String {
        val result = simctl.run(listOf("get_app_container", udid, bundleId, "data"))
        if (!result.isSuccess) {
            val stderr = result.stderr
            val message = when {
                "No such file or directory" in stderr || "not installed" in stderr || "found nothing" in stderr ->
                    "'$bundleId' is not installed on the selected simulator."
                "Invalid device" in stderr || "Unable to find" in stderr ->
                    "The selected simulator is unavailable."
                stderr.isNotEmpty() -> "Failed to resolve the app container: $stderr"
                else -> "Failed to resolve the app container (exit ${result.exitCode})."
            }
            throw DeviceSourceException(message)
        }
        return result.stdout.trim()
    }

    private fun osLabel(runtimeKey: String): String {
        val tail = runtimeKey.substringAfterLast('.')
        val match = Regex("(iOS|watchOS|tvOS|xrOS)-(\\d+)-(\\d+)").find(tail) ?: return ""
        val (platform, major, minor) = match.destructured
        return "$platform $major.$minor"
    }
}
