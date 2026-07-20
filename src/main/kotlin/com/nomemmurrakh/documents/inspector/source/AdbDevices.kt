package com.nomemmurrakh.documents.inspector.source

/** An attached Android target in the `device` state, ready for `run-as`. */
internal data class AdbDevice(val serial: String, val description: String) {
    val displayName: String get() = if (description.isBlank()) serial else "$serial ($description)"
}

/**
 * Parses and lists attached devices from `adb devices -l`. Only targets in the `device` state are
 * returned — `offline`, `unauthorized`, and other states cannot be inspected with `run-as`, so they
 * are skipped. A model name (from the `-l` long form) becomes the device [AdbDevice.description].
 */
internal object AdbDevices {

    /** Lists attached, ready devices via [adb]. Throws [DeviceSourceException] if `adb` itself fails. */
    fun list(adb: Adb): List<AdbDevice> {
        val result = adb.run(listOf("devices", "-l"))
        if (!result.isSuccess) {
            val stderr = result.stderr
            throw DeviceSourceException(
                if (stderr.isNotEmpty()) "adb failed to list devices: $stderr"
                else "adb failed to list devices (exit ${result.exitCode}).",
            )
        }
        return parse(String(result.stdout, Charsets.UTF_8))
    }

    /** Parses the text of `adb devices -l` into ready devices. */
    fun parse(output: String): List<AdbDevice> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(HEADER) }
            .mapNotNull(::parseLine)
            .toList()

    private fun parseLine(line: String): AdbDevice? {
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size < 2 || tokens[1] != STATE_DEVICE) return null
        val serial = tokens[0]
        val model = tokens.drop(2)
            .firstOrNull { it.startsWith(MODEL_PREFIX) }
            ?.removePrefix(MODEL_PREFIX)
            ?.replace('_', ' ')
            .orEmpty()
        return AdbDevice(serial, model)
    }

    private const val HEADER = "List of devices"
    private const val STATE_DEVICE = "device"
    private const val MODEL_PREFIX = "model:"
}
