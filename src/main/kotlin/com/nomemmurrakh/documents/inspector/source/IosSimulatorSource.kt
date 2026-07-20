package com.nomemmurrakh.documents.inspector.source

import java.io.File

/**
 * Reads collection files from a local directory. Abstracted so [IosSimulatorSource]'s pairing and
 * reading logic is testable without a real simulator container. The real implementation ([LocalFiles])
 * is plain [File] I/O — once `simctl` resolves the container, the simulator sandbox is just a
 * directory on the Mac.
 */
internal interface CollectionFiles {

    /** File names directly under [dir], or empty if [dir] does not exist. */
    fun listNames(dir: String): List<String>

    /** Reads the bytes of [dir]/[name]. */
    fun read(dir: String, name: String): ByteArray
}

/** [CollectionFiles] over the local filesystem. */
internal object LocalFiles : CollectionFiles {
    override fun listNames(dir: String): List<String> =
        File(dir).takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    override fun read(dir: String, name: String): ByteArray = File(dir, name).readBytes()
}

/**
 * A [DeviceSource] for a development-signed app on a **booted iOS simulator** (macOS + Xcode only).
 *
 * `xcrun simctl get_app_container <udid> <bundleId> data` resolves the app's data container to a
 * directory on the Mac; the collections live in `Documents/mmkv/` inside it (the Documents library
 * writes to `NSDocumentDirectory`). From there it is plain file I/O: list the directory, pair each
 * main file with its `.crc` sibling, and read both. See
 * `docs/adr/0007-device-source-per-platform.md` and `docs/adr/0005-body-size-from-crc.md`.
 *
 * @param simctl the `xcrun simctl` seam (real: [ProcessSimctl]).
 * @param files the file-reading seam (real: [LocalFiles]).
 * @param udid the booted simulator's udid.
 * @param bundleId the app's bundle identifier.
 */
internal class IosSimulatorSource(
    private val simctl: Simctl,
    private val files: CollectionFiles,
    private val udid: String,
    private val bundleId: String,
) : DeviceSource {

    override fun pull(): List<PulledCollection> {
        val container = IosSimulators.appContainer(simctl, udid, bundleId)
        val mmkvDir = "$container/$MMKV_SUBPATH"

        val entries = files.listNames(mmkvDir).toSet()
        val mainNames = entries
            .filterNot { it.endsWith(CRC_SUFFIX) }
            .filter { "$it$CRC_SUFFIX" in entries }
            .sorted()

        return mainNames.map { name ->
            PulledCollection(
                name = name,
                mainFile = files.read(mmkvDir, name),
                crcFile = files.read(mmkvDir, "$name$CRC_SUFFIX"),
            )
        }
    }

    private companion object {
        const val MMKV_SUBPATH = "Documents/mmkv"
        const val CRC_SUFFIX = ".crc"
    }
}
