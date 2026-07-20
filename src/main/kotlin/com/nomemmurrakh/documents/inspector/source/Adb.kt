package com.nomemmurrakh.documents.inspector.source

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** The result of one `adb` invocation: its exit code and captured stdout/stderr. */
internal data class AdbResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AdbResult &&
                exitCode == other.exitCode &&
                stdout.contentEquals(other.stdout) &&
                stderr == other.stderr)

    override fun hashCode(): Int =
        (exitCode * 31 + stdout.contentHashCode()) * 31 + stderr.hashCode()
}

/**
 * The seam over the `adb` executable. [AndroidAdbSource] builds argument lists and interprets
 * results; this runs them. A real implementation shells out ([ProcessAdb]); tests supply a fake
 * that returns canned [AdbResult]s, so the source is verifiable without a device.
 */
internal interface Adb {

    /** Runs `adb` with [args] (not including the `adb` program itself). Never throws for a
     * non-zero exit — that is reported via [AdbResult.exitCode]. */
    fun run(args: List<String>): AdbResult
}

/** Runs `adb` as a child process, capturing stdout as bytes and stderr as text. */
internal class ProcessAdb(
    private val adbPath: String = "adb",
    private val timeout: Long = 30,
    private val timeoutUnit: TimeUnit = TimeUnit.SECONDS,
) : Adb {

    override fun run(args: List<String>): AdbResult {
        val process = ProcessBuilder(listOf(adbPath) + args)
            .redirectErrorStream(false)
            .start()

        val stdout = ByteArrayOutputStream()
        val outReader = Thread { process.inputStream.copyTo(stdout) }
        val errText = StringBuilder()
        val errReader = Thread {
            process.errorStream.bufferedReader().forEachLine { errText.appendLine(it) }
        }
        outReader.start()
        errReader.start()

        val finished = process.waitFor(timeout, timeoutUnit)
        if (!finished) {
            process.destroyForcibly()
            throw DeviceSourceException("adb timed out after $timeout $timeoutUnit: ${args.joinToString(" ")}")
        }
        outReader.join()
        errReader.join()

        return AdbResult(process.exitValue(), stdout.toByteArray(), errText.toString().trim())
    }
}
