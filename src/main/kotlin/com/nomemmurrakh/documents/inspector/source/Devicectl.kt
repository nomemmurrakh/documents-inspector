package com.nomemmurrakh.documents.inspector.source

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The result of one `xcrun devicectl` invocation: its exit code and the JSON the command wrote.
 *
 * Unlike `adb` and `simctl`, `devicectl`'s stdout is explicitly documented as unstable and meant for
 * humans; JSON written to a file via `--json-output` is the only supported machine interface. So the
 * seam captures [json] (the file's contents) rather than stdout, and callers parse that.
 */
internal data class DevicectlResult(
    val exitCode: Int,
    val json: String,
    val stderr: String,
)

/**
 * The seam over `xcrun devicectl`, used to reach **physical** iOS devices. [IosDevices] and
 * [IosDeviceSource] build argument lists and interpret the JSON; this runs them. A real
 * implementation shells out ([ProcessDevicectl]); tests supply a fake, so device discovery and
 * pulling are verifiable without a paired iPhone.
 *
 * See `docs/adr/0009-ios-device-via-devicectl.md`.
 */
internal interface Devicectl {

    /**
     * Runs `xcrun devicectl` with [args], appending `--json-output` pointed at a temporary file, and
     * returns that file's contents. Never throws for a non-zero exit — that is in the result.
     */
    fun run(args: List<String>): DevicectlResult
}

/** Runs `xcrun devicectl` as a child process, reading its `--json-output` file back. */
internal class ProcessDevicectl(
    private val timeout: Long = 120,
    private val timeoutUnit: TimeUnit = TimeUnit.SECONDS,
) : Devicectl {

    override fun run(args: List<String>): DevicectlResult {
        val output = Files.createTempFile("documents-inspector-devicectl", ".json").toFile()
        try {
            val process = ProcessBuilder(
                listOf("xcrun", "devicectl") + args + listOf("--json-output", output.absolutePath),
            ).redirectErrorStream(false).start()

            val err = StringBuilder()
            val errReader = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            val outSink = Thread { process.inputStream.bufferedReader().forEachLine { } }
            errReader.start()
            outSink.start()

            if (!process.waitFor(timeout, timeoutUnit)) {
                process.destroyForcibly()
                throw DeviceSourceException(
                    "xcrun devicectl timed out after $timeout $timeoutUnit: ${args.joinToString(" ")}",
                )
            }
            errReader.join()
            outSink.join()

            val json = output.takeIf { it.isFile }?.readText().orEmpty()
            return DevicectlResult(process.exitValue(), json, err.toString().trim())
        } finally {
            output.delete()
        }
    }
}

/** Writes bytes pulled off a device into a local staging directory, and reads them back. */
internal interface PullTarget {

    /** A fresh, empty directory to copy files into. The caller deletes it when done. */
    fun createStagingDirectory(): File
}

/** [PullTarget] over the system temp directory. */
internal object TempPullTarget : PullTarget {
    override fun createStagingDirectory(): File =
        Files.createTempDirectory("documents-inspector-pull").toFile()
}
