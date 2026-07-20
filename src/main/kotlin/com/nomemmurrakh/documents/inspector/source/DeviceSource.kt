package com.nomemmurrakh.documents.inspector.source

/**
 * The raw bytes of one collection as pulled off a device: the main MMKV file and its `.crc`
 * sibling. Both are required to read the collection — the authoritative body size lives in the
 * `.crc` (see `docs/adr/0005-body-size-from-crc.md`). Feed these straight into
 * `CollectionReader.read`.
 */
internal data class PulledCollection(
    val name: String,
    val mainFile: ByteArray,
    val crcFile: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PulledCollection &&
                name == other.name &&
                mainFile.contentEquals(other.mainFile) &&
                crcFile.contentEquals(other.crcFile))

    override fun hashCode(): Int =
        (name.hashCode() * 31 + mainFile.contentHashCode()) * 31 + crcFile.contentHashCode()
}

/**
 * Acquires an app's Documents collection files from a target — the only platform-specific layer.
 * A source is bound to a specific app on a specific target; [pull] copies that app's mmkv files
 * locally so the shared reader can decode them. Backends: `AndroidAdbSource`, and later
 * `IosSimulatorSource` / `IosDeviceSource`. See `docs/adr/0007-device-source-per-platform.md`.
 */
internal interface DeviceSource {

    /**
     * Copies every collection (main file + `.crc`) from the target app's mmkv directory.
     *
     * @return one [PulledCollection] per collection, in the order the target lists them.
     * @throws DeviceSourceException if the target is unreachable, the app is not inspectable
     * (e.g. not debuggable), or its files cannot be read.
     */
    fun pull(): List<PulledCollection>
}

/** A failure acquiring files from a target. [message] is safe to surface to the user. */
internal class DeviceSourceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
