package com.nomemmurrakh.documents.inspector

import com.nomemmurrakh.documents.inspector.reader.Collection
import com.nomemmurrakh.documents.inspector.reader.CollectionReader
import com.nomemmurrakh.documents.inspector.source.AndroidAdbSource
import com.nomemmurrakh.documents.inspector.source.Adb
import com.nomemmurrakh.documents.inspector.source.CollectionFiles
import com.nomemmurrakh.documents.inspector.source.DeviceSource
import com.nomemmurrakh.documents.inspector.source.DeviceSourceException
import com.nomemmurrakh.documents.inspector.source.Devicectl
import com.nomemmurrakh.documents.inspector.source.IosDeviceSource
import com.nomemmurrakh.documents.inspector.source.IosSimulatorSource
import com.nomemmurrakh.documents.inspector.source.LocalFiles
import com.nomemmurrakh.documents.inspector.source.PullTarget
import com.nomemmurrakh.documents.inspector.source.Simctl
import com.nomemmurrakh.documents.inspector.source.TempPullTarget

/** The outcome of a load: decoded collections, or a message safe to show the user. */
internal sealed interface LoadResult {
    data class Loaded(val collections: List<Collection>) : LoadResult
    data class Failed(val message: String) : LoadResult
}

/**
 * Orchestrates one inspection load, independent of Swing: acquire the app's files from a
 * [DeviceSource], decode each collection with [CollectionReader], and return a [LoadResult].
 * Acquisition failures ([DeviceSourceException]) and decode failures are turned into a
 * [LoadResult.Failed] message rather than propagated, so the caller only ever renders a result.
 */
internal object InspectorController {

    /** Builds an Android source for [packageName] on [deviceSerial] (null = the only device). */
    fun androidSource(adb: Adb, packageName: String, deviceSerial: String?): DeviceSource =
        AndroidAdbSource(adb, packageName, deviceSerial)

    /** Builds an iOS-simulator source for [bundleId] on simulator [udid]. */
    fun iosSimulatorSource(
        simctl: Simctl,
        udid: String,
        bundleId: String,
        files: CollectionFiles = LocalFiles,
    ): DeviceSource =
        IosSimulatorSource(simctl, files, udid, bundleId)

    /** Builds a physical-iOS source for [bundleId] on the device identified by [deviceId]. */
    fun iosDeviceSource(
        devicectl: Devicectl,
        deviceId: String,
        bundleId: String,
        pullTarget: PullTarget = TempPullTarget,
    ): DeviceSource =
        IosDeviceSource(devicectl, pullTarget, deviceId, bundleId)

    fun load(source: DeviceSource): LoadResult =
        try {
            val pulled = source.pull()
            LoadResult.Loaded(pulled.map { CollectionReader.read(it.name, it.mainFile, it.crcFile) })
        } catch (e: DeviceSourceException) {
            LoadResult.Failed(e.message ?: "Failed to read the device.")
        } catch (e: Exception) {
            LoadResult.Failed("Could not decode the store: ${e.message ?: e::class.simpleName}")
        }
}
