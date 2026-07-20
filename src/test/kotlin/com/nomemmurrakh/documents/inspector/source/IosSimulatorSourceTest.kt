package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosSimulatorSourceTest {

    private val container = "/sim/data/Containers/Data/Application/ABC"
    private val mmkvDir = "$container/Documents/mmkv"

    private fun simctlResolving(path: String = container) = object : Simctl {
        override fun run(args: List<String>): SimctlResult =
            if (args.firstOrNull() == "get_app_container") SimctlResult(0, path, "")
            else SimctlResult(1, "", "unexpected: $args")
    }

    private class FakeFiles(
        private val names: Map<String, List<String>>,
        private val bytes: Map<String, ByteArray>,
    ) : CollectionFiles {
        override fun listNames(dir: String): List<String> = names[dir].orEmpty()
        override fun read(dir: String, name: String): ByteArray =
            bytes["$dir/$name"] ?: error("no bytes for $dir/$name")
    }

    @Test
    fun `resolves the container and pulls each main file with its crc`() {
        val defaultMain = byteArrayOf(1, 2, 3)
        val defaultCrc = byteArrayOf(4)
        val sessionMain = byteArrayOf(5, 6)
        val sessionCrc = byteArrayOf(7)

        val files = FakeFiles(
            names = mapOf(mmkvDir to listOf("documents.default", "documents.default.crc", "session", "session.crc")),
            bytes = mapOf(
                "$mmkvDir/documents.default" to defaultMain,
                "$mmkvDir/documents.default.crc" to defaultCrc,
                "$mmkvDir/session" to sessionMain,
                "$mmkvDir/session.crc" to sessionCrc,
            ),
        )

        val pulled = IosSimulatorSource(simctlResolving(), files, "udid", "com.example.app").pull()

        assertEquals(listOf("documents.default", "session"), pulled.map { it.name })
        assertContentEquals(defaultMain, pulled[0].mainFile)
        assertContentEquals(defaultCrc, pulled[0].crcFile)
        assertContentEquals(sessionMain, pulled[1].mainFile)
        assertContentEquals(sessionCrc, pulled[1].crcFile)
    }

    @Test
    fun `skips a main file with no crc sibling`() {
        val files = FakeFiles(
            names = mapOf(mmkvDir to listOf("orphan", "session", "session.crc")),
            bytes = mapOf("$mmkvDir/session" to byteArrayOf(1), "$mmkvDir/session.crc" to byteArrayOf(2)),
        )
        val pulled = IosSimulatorSource(simctlResolving(), files, "udid", "com.example.app").pull()
        assertEquals(listOf("session"), pulled.map { it.name })
    }

    @Test
    fun `an empty or missing mmkv dir yields no collections`() {
        val files = FakeFiles(names = emptyMap(), bytes = emptyMap())
        assertTrue(IosSimulatorSource(simctlResolving(), files, "udid", "com.example.app").pull().isEmpty())
    }

    @Test
    fun `a not-installed app surfaces a clear failure`() {
        val simctl = object : Simctl {
            override fun run(args: List<String>) = SimctlResult(2, "", "No such file or directory")
        }
        val files = FakeFiles(emptyMap(), emptyMap())
        val ex = assertFailsWith<DeviceSourceException> {
            IosSimulatorSource(simctl, files, "udid", "com.example.app").pull()
        }
        assertTrue(ex.message!!.contains("not installed"))
    }
}
