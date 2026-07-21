package com.nomemmurrakh.documents.inspector

import com.android.tools.idea.projectsystem.AndroidModuleSystem
import com.android.tools.idea.projectsystem.getModuleSystem
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.nomemmurrakh.documents.inspector.source.AppIdCandidates
import org.jetbrains.android.facet.AndroidFacet
import java.io.File

/**
 * Discovers the app ids of the open project, so the Connect prompt can be prefilled instead of typed
 * from memory. See `docs/adr/0008-prefill-app-id-from-the-ide.md`.
 *
 * This is the only place that touches Android Studio's project-model APIs; the parsing it delegates
 * to ([AppIdCandidates]) stays IDE-free and directly testable. Every lookup is best-effort — an
 * unsynced Gradle model or a missing Xcode project yields no candidates, never an error, because the
 * prompt always remains typeable.
 *
 * Both entry points do file I/O or model reads and must not be called on the EDT.
 */
internal object AppIdDetector {

    /**
     * Application ids of the project's Android **application** modules (library modules are
     * skipped — they have no installed id to inspect).
     *
     * The id comes from the module's `ApplicationIdProvider`, which resolves what is actually
     * installed, applying `applicationIdSuffix`: a debug build of `com.example.app` installs as
     * `com.example.app.debug`, and only that resolved id works with `run-as`. Modules whose model is
     * not synced throw, so each is resolved independently and failures are skipped.
     */
    fun androidCandidates(project: Project): List<String> = runCatching {
        val compute = Computable<List<String>> {
            val facets: List<AndroidFacet> = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID)
            facets.filter { it.configuration.isAppProject }
                .mapNotNull { facet -> resolveApplicationId(facet) }
                .filter { it.isNotBlank() }
                .distinct()
        }
        ApplicationManager.getApplication().runReadAction(compute)
    }.getOrDefault(emptyList())

    private fun resolveApplicationId(facet: AndroidFacet): String? {
        val moduleSystem: AndroidModuleSystem =
            runCatching { facet.module.getModuleSystem() }.getOrNull() ?: return null
        val installed = runCatching { moduleSystem.getApplicationIdProvider().getPackageName() }.getOrNull()
        return installed ?: runCatching { moduleSystem.getPackageName() }.getOrNull()
    }

    /**
     * Bundle identifiers declared by Xcode projects inside the open project.
     *
     * Read from `PRODUCT_BUNDLE_IDENTIFIER` in each `*.xcodeproj/project.pbxproj`. The app's
     * `Info.plist` is not used: it holds `$(PRODUCT_BUNDLE_IDENTIFIER)`, an unresolved build
     * variable, whereas the pbxproj carries the literal.
     */
    fun iosCandidates(project: Project): List<String> = runCatching {
        val base = project.basePath?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
        findPbxprojFiles(base).flatMap { AppIdCandidates.parsePbxprojBundleIds(it.readText()) }.distinct()
    }.getOrDefault(emptyList())

    private fun findPbxprojFiles(base: File): List<File> {
        val found = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || found.size >= MAX_PROJECTS) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory || child.name in SKIPPED_DIRS || child.isHidden) continue
                if (child.name.endsWith(XCODEPROJ)) {
                    File(child, PBXPROJ).takeIf { it.isFile }?.let(found::add)
                } else {
                    walk(child, depth + 1)
                }
            }
        }

        walk(base, 0)
        return found
    }

    private const val XCODEPROJ = ".xcodeproj"
    private const val PBXPROJ = "project.pbxproj"
    private const val MAX_DEPTH = 6
    private const val MAX_PROJECTS = 10
    private val SKIPPED_DIRS = setOf("build", ".git", ".gradle", "DerivedData", "node_modules", ".idea")
}
