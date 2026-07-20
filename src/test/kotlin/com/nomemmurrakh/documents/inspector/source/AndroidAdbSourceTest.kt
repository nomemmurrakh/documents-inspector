package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidAdbSourceTest {

    private class FakeAdb(private val responder: (List<String>) -> AdbResult) : Adb {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>): AdbResult {
            calls.add(args)
            return responder(args)
        }
    }

    private fun ok(stdout: ByteArray = ByteArray(0)) = AdbResult(0, stdout, "")
    private fun ok(stdout: String) = ok(stdout.toByteArray())
    private fun err(stderr: String, exit: Int = 1) = AdbResult(exit, ByteArray(0), stderr)

    private val pkg = "com.nomemmurrakh.documents.sample"

    @Test
    fun `lists mmkv dir and pulls each main file with its crc`() {
        val defaultMain = byteArrayOf(1, 2, 3)
        val defaultCrc = byteArrayOf(4, 5, 6)
        val sessionMain = byteArrayOf(7, 8)
        val sessionCrc = byteArrayOf(9)

        val adb = FakeAdb { args ->
            when {
                args.contains("ls") -> ok("documents.default\ndocuments.default.crc\nsession\nsession.crc\n")
                args.last() == "files/mmkv/documents.default" -> ok(defaultMain)
                args.last() == "files/mmkv/documents.default.crc" -> ok(defaultCrc)
                args.last() == "files/mmkv/session" -> ok(sessionMain)
                args.last() == "files/mmkv/session.crc" -> ok(sessionCrc)
                else -> err("unexpected: $args")
            }
        }

        val pulled = AndroidAdbSource(adb, pkg).pull()

        assertEquals(listOf("documents.default", "session"), pulled.map { it.name })
        assertContentEquals(defaultMain, pulled[0].mainFile)
        assertContentEquals(defaultCrc, pulled[0].crcFile)
        assertContentEquals(sessionMain, pulled[1].mainFile)
        assertContentEquals(sessionCrc, pulled[1].crcFile)
    }

    @Test
    fun `uses run-as and exec-out for the listing command`() {
        val adb = FakeAdb { ok("") }
        AndroidAdbSource(adb, pkg).pull()

        assertEquals(
            listOf("exec-out", "run-as", pkg, "ls", "-1", "files/mmkv"),
            adb.calls.single(),
        )
    }

    @Test
    fun `parses a whitespace-separated ls listing`() {
        val adb = FakeAdb { args ->
            if (args.contains("ls")) ok("documents.default documents.default.crc session session.crc\n")
            else ok(byteArrayOf(0))
        }
        val pulled = AndroidAdbSource(adb, pkg).pull()

        assertEquals(listOf("documents.default", "session"), pulled.map { it.name })
    }

    @Test
    fun `pins the device with -s when a serial is given`() {
        val adb = FakeAdb { args ->
            if (args.contains("ls")) ok("a\na.crc\n") else ok(byteArrayOf(0))
        }
        AndroidAdbSource(adb, pkg, deviceSerial = "emulator-5554").pull()

        adb.calls.forEach { assertEquals(listOf("-s", "emulator-5554"), it.take(2)) }
    }

    @Test
    fun `omits -s when no serial is given`() {
        val adb = FakeAdb { ok("") }
        AndroidAdbSource(adb, pkg).pull()

        assertEquals("exec-out", adb.calls.single().first())
    }

    @Test
    fun `skips a main file that has no crc sibling`() {
        val adb = FakeAdb { args ->
            if (args.contains("ls")) ok("orphan\nsession\nsession.crc\n")
            else ok(byteArrayOf(0))
        }
        val pulled = AndroidAdbSource(adb, pkg).pull()

        assertEquals(listOf("session"), pulled.map { it.name })
    }

    @Test
    fun `handles an empty mmkv directory`() {
        val adb = FakeAdb { ok("") }
        assertTrue(AndroidAdbSource(adb, pkg).pull().isEmpty())
    }

    @Test
    fun `maps a not-debuggable failure to a clear message`() {
        val adb = FakeAdb { err("run-as: package not debuggable: $pkg") }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("not debuggable"))
        assertTrue(ex.message!!.contains(pkg))
    }

    @Test
    fun `maps more-than-one-device to a select-a-device message`() {
        val adb = FakeAdb { err("adb: more than one device/emulator") }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("device serial"))
    }

    @Test
    fun `maps an unknown package to a not-installed message`() {
        val adb = FakeAdb { err("run-as: unknown package: $pkg") }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("not installed"))
    }

    @Test
    fun `maps no-devices to a no-device message`() {
        val adb = FakeAdb { err("adb: no devices/emulators found") }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("No device"))
    }

    @Test
    fun `surfaces a raw stderr when the failure is unrecognized`() {
        val adb = FakeAdb { err("some other adb explosion") }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("some other adb explosion"))
    }

    @Test
    fun `fails when a crc sibling cannot be read`() {
        val adb = FakeAdb { args ->
            when {
                args.contains("ls") -> ok("session\nsession.crc\n")
                args.last().endsWith(".crc") -> err("run-as: cat: permission denied")
                else -> ok(byteArrayOf(1))
            }
        }
        val ex = assertFailsWith<DeviceSourceException> { AndroidAdbSource(adb, pkg).pull() }
        assertTrue(ex.message!!.contains("permission denied"))
    }
}
