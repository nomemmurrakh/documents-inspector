package com.nomemmurrakh.documents.inspector.source

import java.util.concurrent.TimeUnit

/** The result of one `xcrun simctl` invocation: exit code, stdout, stderr. */
internal data class SimctlResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * The seam over `xcrun simctl`. [IosSimulators] and [IosSimulatorSource] build argument lists and
 * interpret results; this runs them. A real implementation shells out ([ProcessSimctl]); tests
 * supply a fake, so simulator discovery and container resolution are verifiable without Xcode.
 *
 * Unlike Android's `adb`, simctl output is text/JSON only — once a container path is resolved the
 * files are read straight off the Mac filesystem, so there is no byte-streaming `cat` equivalent.
 */
internal interface Simctl {

    /** Runs `xcrun simctl` with [args]. Never throws for a non-zero exit — that is in the result. */
    fun run(args: List<String>): SimctlResult
}

/**
 * Converts NeXTSTEP plist text to JSON. `simctl listapps` emits plist regardless of the `-j` flag,
 * so its output has to be converted before it can be parsed. Behind a seam so app-id discovery is
 * testable without macOS.
 */
internal interface PlistToJson {

    /** Converts [plist] to JSON, or returns null if conversion fails. */
    fun convert(plist: String): String?
}

/** [PlistToJson] via `plutil`, reading the plist on stdin and writing JSON to stdout. */
internal object ProcessPlutil : PlistToJson {

    override fun convert(plist: String): String? = runCatching {
        val process = ProcessBuilder("plutil", "-convert", "json", "-r", "-o", "-", "--", "-")
            .redirectErrorStream(false)
            .start()

        val out = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        reader.start()
        process.outputStream.bufferedWriter().use { it.write(plist) }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join()
        out.toString().takeIf { process.exitValue() == 0 && it.isNotBlank() }
    }.getOrNull()
}

/** Runs `xcrun simctl` as a child process. */
internal class ProcessSimctl(
    private val timeout: Long = 30,
    private val timeoutUnit: TimeUnit = TimeUnit.SECONDS,
) : Simctl {

    override fun run(args: List<String>): SimctlResult {
        val process = ProcessBuilder(listOf("xcrun", "simctl") + args)
            .redirectErrorStream(false)
            .start()

        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val errReader = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        outReader.start()
        errReader.start()

        val finished = process.waitFor(timeout, timeoutUnit)
        if (!finished) {
            process.destroyForcibly()
            throw DeviceSourceException("xcrun simctl timed out after $timeout $timeoutUnit: ${args.joinToString(" ")}")
        }
        outReader.join()
        errReader.join()

        return SimctlResult(process.exitValue(), out.toString().trim(), err.toString().trim())
    }
}
