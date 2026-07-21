package com.nomemmurrakh.documents.inspector.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsers that extract candidate app ids from project and tooling output, used to prefill the
 * Connect prompt. See `docs/adr/0008-prefill-app-id-from-the-ide.md`.
 *
 * Everything here is free of IDE and process types so it is testable directly. Every function is
 * best-effort: malformed input yields no candidates rather than an error, because a missing prefill
 * only costs the developer a typed id.
 */
/**
 * An app installed on a simulator: its bundle id and the local path of its data container, when
 * `simctl` reported one.
 */
internal data class InstalledApp(val bundleId: String, val dataContainer: String?) {

    /**
     * The container as a plain filesystem path. `simctl` reports it as a percent-encoded `file://`
     * URL, which cannot be used for file I/O as-is.
     */
    val containerPath: String?
        get() = dataContainer?.let { raw ->
            runCatching { java.io.File(java.net.URI(raw)).path }.getOrNull()
        }
}

internal object AppIdCandidates {

    private val json = Json { ignoreUnknownKeys = true }

    private val BUNDLE_ID = Regex("""PRODUCT_BUNDLE_IDENTIFIER\s*=\s*"?([\w.\-]+)"?\s*;""")

    /**
     * Extracts bundle identifiers from the text of an Xcode `project.pbxproj`.
     *
     * Values containing an unresolved build variable are skipped — only literals are usable. Test
     * targets are skipped too: they are installed as separate bundles but never hold the app's store.
     */
    fun parsePbxprojBundleIds(text: String): List<String> =
        BUNDLE_ID.findAll(text)
            .map { it.groupValues[1] }
            .filterNot { it.contains("$(") }
            .filterNot { it.endsWith(".tests", ignoreCase = true) || it.endsWith(".UITests", ignoreCase = true) }
            .distinct()
            .toList()

    /**
     * Extracts user-installed bundle identifiers from `simctl listapps` output that has been
     * converted to JSON (the command itself emits NeXTSTEP plist; see [IosSimulators.listApps]).
     *
     * Only `ApplicationType = User` entries are kept, which excludes the ~19 Apple system apps
     * present on every simulator. Returns no candidates if the text does not parse.
     */
    fun parseListappsJson(jsonText: String): List<String> =
        parseListappsEntries(jsonText).map { it.bundleId }

    /**
     * Same as [parseListappsJson] but keeps each app's data-container path, which
     * [IosSimulators.listApps] uses to rank apps that actually hold a Documents store first.
     */
    fun parseListappsEntries(jsonText: String): List<InstalledApp> =
        runCatching {
            json.parseToJsonElement(jsonText).jsonObject.mapNotNull { (bundleId, entry) ->
                val app = entry.jsonObject
                val type = app["ApplicationType"]?.jsonPrimitive?.content
                if (type != USER_APP) return@mapNotNull null
                InstalledApp(
                    bundleId = app["CFBundleIdentifier"]?.jsonPrimitive?.content ?: bundleId,
                    dataContainer = app["DataContainer"]?.jsonPrimitive?.content,
                )
            }
        }.getOrDefault(emptyList())

    /** Combines candidate lists, keeping [preferred] first and dropping duplicates and blanks. */
    fun merge(preferred: List<String>, others: List<String>): List<String> =
        (preferred + others).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private const val USER_APP = "User"
}
