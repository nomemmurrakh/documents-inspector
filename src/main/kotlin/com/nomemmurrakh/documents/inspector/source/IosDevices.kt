package com.nomemmurrakh.documents.inspector.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A paired physical iOS device, addressed by its `devicectl` identifier. */
internal data class IosDevice(val identifier: String, val name: String, val model: String) {
    val displayName: String get() = if (model.isBlank()) name else "$name ($model)"
}

/**
 * Enumerates paired iOS devices and their apps and files, via `xcrun devicectl`.
 *
 * Every helper here treats **the JSON as the source of truth, never the exit code**: `devicectl`
 * exits 0 even when an operation fails, reporting the failure as an `error` object in its
 * `--json-output` file. See `docs/adr/0009-ios-device-via-devicectl.md`.
 */
internal object IosDevices {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lists paired devices via [devicectl]. Devices Xcode merely remembers are included — being
     * paired does not mean being reachable — so a failure to reach one surfaces later, when its
     * files are actually requested.
     */
    fun list(devicectl: Devicectl): List<IosDevice> {
        val result = devicectl.run(listOf("list", "devices"))
        failOnError(result, "list devices")
        return parseDevices(result.json)
    }

    /** Parses `devicectl list devices --json-output`. */
    fun parseDevices(jsonText: String): List<IosDevice> =
        runCatching {
            val devices = root(jsonText)?.get("devices")?.jsonArray ?: return emptyList()
            devices.mapNotNull { entry ->
                val device = entry.jsonObject
                val identifier = device["identifier"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val properties = device["deviceProperties"]?.jsonObject
                val hardware = device["hardwareProperties"]?.jsonObject
                IosDevice(
                    identifier = identifier,
                    name = properties?.get("name")?.jsonPrimitive?.content ?: identifier,
                    model = hardware?.get("marketingName")?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())

    /**
     * Bundle identifiers of developer-built apps installed on [deviceId], for prefilling the Connect
     * prompt. Only `builtByDeveloper` apps are returned: App Store and system apps cannot have their
     * container read, so offering them would only produce failures.
     *
     * Apps that already hold a Documents store are returned **first**, matching the simulator
     * ([IosSimulators.listApps]). The device listing carries no container path, so unlike the
     * simulator this cannot be answered locally — each app's store is probed with its own
     * `info files` call. Those probes run **concurrently** because each is a round trip to the
     * device (~1s serially, ~1.7s for five in parallel). Ranking only: every developer-built app
     * stays in the list, since an app that has not written its store yet is still a valid target.
     */
    fun listApps(devicectl: Devicectl, deviceId: String): List<String> =
        runCatching {
            val result = devicectl.run(listOf("device", "info", "apps", "--device", deviceId))
            val apps = root(result.json)?.get("apps")?.jsonArray ?: return emptyList()
            val bundleIds = apps.mapNotNull { entry ->
                val app = entry.jsonObject
                if (app["builtByDeveloper"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
                app["bundleIdentifier"]?.jsonPrimitive?.content
            }
            if (bundleIds.size < 2) return bundleIds

            val withStore = bundleIds.probeInParallel { hasDocumentsStore(devicectl, deviceId, it) }
            val (holding, rest) = bundleIds.partition { withStore[it] == true }
            holding + rest
        }.getOrDefault(emptyList())

    private fun List<String>.probeInParallel(probe: (String) -> Boolean): Map<String, Boolean> {
        val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val threads = map { bundleId ->
            Thread { results[bundleId] = runCatching { probe(bundleId) }.getOrDefault(false) }
                .apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(PROBE_TIMEOUT_MS) }
        return results
    }

    private fun hasDocumentsStore(devicectl: Devicectl, deviceId: String, bundleId: String): Boolean {
        val result = devicectl.run(
            listOf(
                "device", "info", "files",
                "--device", deviceId,
                "--domain-type", "appDataContainer",
                "--domain-identifier", bundleId,
                "--subdirectory", IosSimulatorSource.MMKV_SUBPATH,
            ),
        )
        if (errorMessage(result.json) != null) return false
        return parseFileNames(result.json, IosSimulatorSource.MMKV_SUBPATH).isNotEmpty()
    }

    private const val PROBE_TIMEOUT_MS = 10_000L

    /**
     * File names directly inside [subdirectory] of [bundleId]'s data container on [deviceId].
     *
     * `devicectl` lists the domain **recursively** and reports each entry's `relativePath`, so
     * results are filtered back down to the immediate children of [subdirectory] and directories are
     * dropped — the mmkv directory holds only files.
     */
    fun listFiles(devicectl: Devicectl, deviceId: String, bundleId: String, subdirectory: String): List<String> {
        val result = devicectl.run(
            listOf(
                "device", "info", "files",
                "--device", deviceId,
                "--domain-type", "appDataContainer",
                "--domain-identifier", bundleId,
                "--subdirectory", subdirectory,
            ),
        )
        errorMessage(result.json)?.let { throw DeviceSourceException(describeFileFailure(it, bundleId)) }
        return parseFileNames(result.json, subdirectory)
    }

    /**
     * Parses `device info files --json-output` down to the immediate file children of [subdirectory].
     *
     * `relativePath` is reported relative to the **requested subdirectory**, so with
     * `--subdirectory Documents/MMKV` an entry reads `cache`, not `Documents/MMKV/cache`. A listing
     * of the whole domain does include the leading directories, so the prefix is stripped when
     * present and the path is taken as-is when it is not — both listings then yield plain names.
     * Entries below the requested level (and directories) are dropped.
     */
    fun parseFileNames(jsonText: String, subdirectory: String): List<String> =
        runCatching {
            val files = root(jsonText)?.get("files")?.jsonArray ?: return emptyList()
            val prefix = subdirectory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            files.mapNotNull { entry ->
                val file = entry.jsonObject
                if (file["resources"]?.jsonObject?.get("isDirectory")?.jsonPrimitive?.booleanOrNull == true) {
                    return@mapNotNull null
                }
                val relativePath = file["relativePath"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val withinSubdirectory =
                    if (prefix.isNotEmpty() && relativePath.startsWith(prefix)) {
                        relativePath.removePrefix(prefix)
                    } else {
                        relativePath
                    }
                withinSubdirectory.takeIf { it.isNotEmpty() && !it.contains('/') }
            }
        }.getOrDefault(emptyList())

    /** Copies [source] (relative to the app's container) off [deviceId] to [destination]. */
    fun copyFrom(
        devicectl: Devicectl,
        deviceId: String,
        bundleId: String,
        source: String,
        destination: String,
    ) {
        val result = devicectl.run(
            listOf(
                "device", "copy", "from",
                "--device", deviceId,
                "--domain-type", "appDataContainer",
                "--domain-identifier", bundleId,
                "--source", source,
                "--destination", destination,
            ),
        )
        errorMessage(result.json)?.let {
            throw DeviceSourceException("Failed to copy '$source' from the device: $it")
        }
    }

    private fun root(jsonText: String) =
        runCatching { json.parseToJsonElement(jsonText).jsonObject["result"]?.jsonObject }.getOrNull()

    private fun errorMessage(jsonText: String): String? =
        runCatching {
            val error = json.parseToJsonElement(jsonText).jsonObject["error"]?.jsonObject ?: return null
            error["userInfo"]?.jsonObject
                ?.get("NSLocalizedDescription")?.jsonObject
                ?.get("string")?.jsonPrimitive?.content
                ?: error["userInfo"]?.jsonObject
                    ?.get("NSDebugDescription")?.jsonObject
                    ?.get("string")?.jsonPrimitive?.content
                ?: "devicectl reported error ${error["code"]?.jsonPrimitive?.content} " +
                "(${error["domain"]?.jsonPrimitive?.content})"
        }.getOrNull()

    private fun failOnError(result: DevicectlResult, action: String) {
        errorMessage(result.json)?.let { throw DeviceSourceException("xcrun devicectl failed to $action: $it") }
        if (result.json.isBlank()) {
            val detail = result.stderr.takeIf { it.isNotEmpty() } ?: "exit ${result.exitCode}"
            throw DeviceSourceException("xcrun devicectl failed to $action ($detail).")
        }
    }

    private fun describeFileFailure(error: String, bundleId: String): String = when {
        "failed to get a list of files" in error ->
            "Could not read '$bundleId' on the device. The app must be a development build, " +
                "and it must have written a Documents store at least once."
        "not found" in error || "no such" in error.lowercase() ->
            "'$bundleId' is not installed on the selected device."
        else -> "Could not list the app's files on the device: $error"
    }
}
