package com.nomemmurrakh.documents.inspector.source

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosDeviceSourceTest {

    private val staging: File = Files.createTempDirectory("inspector-test-staging").toFile()

    private val pullTarget = object : PullTarget {
        override fun createStagingDirectory(): File = staging
    }

    @AfterTest
    fun cleanUp() {
        staging.deleteRecursively()
    }

    private fun filesJson(vararg relativePaths: String): String {
        val entries = relativePaths.joinToString(",") { path ->
            """{ "name" : "${path.substringAfterLast('/')}", "relativePath" : "$path",
                 "resources" : { "isDirectory" : false } }"""
        }
        return """{ "result" : { "files" : [ $entries ] } }"""
    }

    /**
     * A fake that answers the listing with [listing] and, for each copy, writes [contents] for the
     * requested source so the source's read-back path is exercised for real.
     */
    private fun devicectl(listing: String, contents: Map<String, ByteArray> = emptyMap()) =
        object : Devicectl {
            val copied = mutableListOf<String>()

            override fun run(args: List<String>): DevicectlResult {
                if (args.take(3) == listOf("device", "info", "files")) {
                    return DevicectlResult(0, listing, "")
                }
                val source = args[args.indexOf("--source") + 1]
                val destination = args[args.indexOf("--destination") + 1]
                copied += source
                contents[source]?.let { File(destination).writeBytes(it) }
                return DevicectlResult(0, """{ "result" : {} }""", "")
            }
        }

    @Test
    fun `pull pairs each main file with its crc and reads the copied bytes`() {
        val fake = devicectl(
            filesJson("documents.default", "documents.default.crc"),
            mapOf(
                "Documents/MMKV/documents.default" to byteArrayOf(1, 2, 3),
                "Documents/MMKV/documents.default.crc" to byteArrayOf(4, 5),
            ),
        )
        val pulled = IosDeviceSource(fake, pullTarget, "the-device", "com.example.app").pull()

        assertEquals(1, pulled.size)
        assertEquals("documents.default", pulled[0].name)
        assertTrue(pulled[0].mainFile.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(pulled[0].crcFile.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun `pull skips a main file with no crc sibling`() {
        val fake = devicectl(
            filesJson("orphan", "paired", "paired.crc"),
            mapOf(
                "Documents/MMKV/paired" to byteArrayOf(1),
                "Documents/MMKV/paired.crc" to byteArrayOf(2),
            ),
        )
        val pulled = IosDeviceSource(fake, pullTarget, "the-device", "com.example.app").pull()

        assertEquals(listOf("paired"), pulled.map { it.name })
        assertFalse(fake.copied.any { it.contains("orphan") })
    }

    @Test
    fun `pull returns nothing when the store is empty`() {
        val fake = devicectl(filesJson())
        assertTrue(IosDeviceSource(fake, pullTarget, "the-device", "com.example.app").pull().isEmpty())
    }

    @Test
    fun `pull fails clearly when a copy reports success but writes nothing`() {
        val fake = devicectl(
            filesJson("documents.default", "documents.default.crc"),
        )
        val ex = assertFailsWith<DeviceSourceException> {
            IosDeviceSource(fake, pullTarget, "the-device", "com.example.app").pull()
        }
        assertTrue(ex.message!!.contains("was not copied"))
    }

    @Test
    fun `pull cleans up its staging directory`() {
        val fake = devicectl(
            filesJson("documents.default", "documents.default.crc"),
            mapOf(
                "Documents/MMKV/documents.default" to byteArrayOf(1),
                "Documents/MMKV/documents.default.crc" to byteArrayOf(2),
            ),
        )
        IosDeviceSource(fake, pullTarget, "the-device", "com.example.app").pull()
        assertFalse(staging.exists())
    }
}
