package com.nomemmurrakh.documents.inspector.source

/**
 * A [DeviceSource] backed by `adb run-as`, for a debuggable app on an Android device or emulator.
 *
 * Collections live in `files/mmkv/` inside the app's private storage, reachable only via `run-as`
 * on a **debuggable** build (or a rooted device). This lists that directory, pairs each main file
 * with its `.crc` sibling, and pulls both with `adb exec-out run-as <pkg> cat …` — `exec-out`
 * keeps the byte stream free of the line-ending translation `adb shell` applies.
 *
 * When more than one device/emulator is attached, `adb` refuses to pick one; pass [deviceSerial]
 * (an `adb devices` serial) to pin the target with `-s`.
 *
 * See `docs/adr/0007-device-source-per-platform.md` and `docs/adr/0005-body-size-from-crc.md`.
 *
 * @param adb the execution seam (real: [ProcessAdb]).
 * @param packageName the target app's application id (e.g. `com.nomemmurrakh.documents.sample`).
 * @param deviceSerial optional `adb` serial to disambiguate multiple attached targets.
 */
internal class AndroidAdbSource(
    private val adb: Adb,
    private val packageName: String,
    private val deviceSerial: String? = null,
) : DeviceSource {

    override fun pull(): List<PulledCollection> {
        val names = listMainFileNames()
        return names.map { name ->
            PulledCollection(
                name = name,
                mainFile = readFile("$MMKV_DIR/$name"),
                crcFile = readFile("$MMKV_DIR/$name$CRC_SUFFIX"),
            )
        }
    }

    private fun listMainFileNames(): List<String> {
        val result = adb.run(target() + listOf("exec-out", "run-as", packageName, "ls", MMKV_DIR))
        if (!result.isSuccess) throw failure("list $MMKV_DIR", result)

        val entries = String(result.stdout, Charsets.UTF_8)
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return entries
            .filterNot { it.endsWith(CRC_SUFFIX) }
            .filter { "$it$CRC_SUFFIX" in entries }
            .sorted()
    }

    private fun readFile(remotePath: String): ByteArray {
        val result = adb.run(target() + listOf("exec-out", "run-as", packageName, "cat", remotePath))
        if (!result.isSuccess) throw failure("read $remotePath", result)
        return result.stdout
    }

    private fun target(): List<String> =
        if (deviceSerial != null) listOf("-s", deviceSerial) else emptyList()

    private fun failure(action: String, result: AdbResult): DeviceSourceException {
        val stderr = result.stderr
        val message = when {
            NOT_DEBUGGABLE in stderr ->
                "'$packageName' is not debuggable, so its files cannot be read. " +
                    "Install a debuggable build of the app."
            MULTIPLE_DEVICES in stderr ->
                "More than one device/emulator is attached. Pass a device serial to select one."
            UNKNOWN_PACKAGE in stderr ->
                "'$packageName' is not installed on the target."
            NO_DEVICES in stderr ->
                "No device or emulator is attached."
            stderr.isNotEmpty() -> "Failed to $action via adb: $stderr"
            else -> "Failed to $action via adb (exit ${result.exitCode})."
        }
        return DeviceSourceException(message)
    }

    private companion object {
        const val MMKV_DIR = "files/mmkv"
        const val CRC_SUFFIX = ".crc"
        const val NOT_DEBUGGABLE = "not debuggable"
        const val UNKNOWN_PACKAGE = "unknown package"
        const val MULTIPLE_DEVICES = "more than one device"
        const val NO_DEVICES = "no devices"
    }
}
