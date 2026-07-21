package com.nomemmurrakh.documents.inspector.source

import java.io.File

/**
 * A [DeviceSource] for a development-signed app on a **physical iOS device** (macOS + Xcode only).
 *
 * `devicectl` has no equivalent of `simctl get_app_container` — a device sandbox is not a directory
 * on the Mac — so files cannot be read in place. Instead the app's container is listed
 * (`device info files`), each collection is copied to a local staging directory
 * (`device copy from`), and the bytes are read from there. See
 * `docs/adr/0009-ios-device-via-devicectl.md` and `docs/adr/0007-device-source-per-platform.md`.
 *
 * @param devicectl the `xcrun devicectl` seam (real: [ProcessDevicectl]).
 * @param pullTarget supplies the staging directory (real: [TempPullTarget]).
 * @param deviceId the target device's `devicectl` identifier.
 * @param bundleId the app's bundle identifier.
 */
internal class IosDeviceSource(
    private val devicectl: Devicectl,
    private val pullTarget: PullTarget,
    private val deviceId: String,
    private val bundleId: String,
) : DeviceSource {

    override fun pull(): List<PulledCollection> {
        val entries = IosDevices.listFiles(devicectl, deviceId, bundleId, MMKV_SUBPATH).toSet()
        val mainNames = entries
            .filterNot { it.endsWith(CRC_SUFFIX) }
            .filter { "$it$CRC_SUFFIX" in entries }
            .sorted()
        if (mainNames.isEmpty()) return emptyList()

        val staging = pullTarget.createStagingDirectory()
        try {
            return mainNames.map { name ->
                PulledCollection(
                    name = name,
                    mainFile = copy(staging, name),
                    crcFile = copy(staging, "$name$CRC_SUFFIX"),
                )
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copy(staging: File, name: String): ByteArray {
        val destination = File(staging, name)
        IosDevices.copyFrom(
            devicectl = devicectl,
            deviceId = deviceId,
            bundleId = bundleId,
            source = "$MMKV_SUBPATH/$name",
            destination = destination.absolutePath,
        )
        if (!destination.isFile) {
            throw DeviceSourceException("The device reported success but '$name' was not copied.")
        }
        return destination.readBytes()
    }

    private companion object {
        const val MMKV_SUBPATH = IosSimulatorSource.MMKV_SUBPATH
        const val CRC_SUFFIX = ".crc"
    }
}
