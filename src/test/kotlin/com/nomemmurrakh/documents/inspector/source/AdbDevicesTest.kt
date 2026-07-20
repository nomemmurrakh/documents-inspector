package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbDevicesTest {

    @Test
    fun `parses ready devices with model as description`() {
        val output = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64 model:Pixel_7 device:emu64a
            R58MABCDEF             device usb:1-1 product:a53x model:SM_A536E device:a53x
        """.trimIndent()

        val devices = AdbDevices.parse(output)

        assertEquals(2, devices.size)
        assertEquals(AdbDevice("emulator-5554", "Pixel 7"), devices[0])
        assertEquals(AdbDevice("R58MABCDEF", "SM A536E"), devices[1])
    }

    @Test
    fun `skips offline and unauthorized devices`() {
        val output = """
            List of devices attached
            emulator-5554   device model:Pixel_7
            R58MABCDEF      offline
            X99Z            unauthorized
        """.trimIndent()

        assertEquals(listOf("emulator-5554"), AdbDevices.parse(output).map { it.serial })
    }

    @Test
    fun `returns empty when nothing is attached`() {
        assertTrue(AdbDevices.parse("List of devices attached\n").isEmpty())
    }

    @Test
    fun `falls back to serial when no model is present`() {
        val output = "List of devices attached\nsomeserial device\n"
        val device = AdbDevices.parse(output).single()
        assertEquals("someserial", device.serial)
        assertEquals("someserial", device.displayName)
    }

    @Test
    fun `displayName combines serial and description`() {
        assertEquals("emulator-5554 (Pixel 7)", AdbDevice("emulator-5554", "Pixel 7").displayName)
    }

    @Test
    fun `list surfaces an adb failure`() {
        val adb = object : Adb {
            override fun run(args: List<String>) = AdbResult(1, ByteArray(0), "adb: command not found")
        }
        val ex = assertFailsWith<DeviceSourceException> { AdbDevices.list(adb) }
        assertTrue(ex.message!!.contains("command not found"))
    }

    @Test
    fun `list invokes adb devices -l`() {
        var seen: List<String>? = null
        val adb = object : Adb {
            override fun run(args: List<String>): AdbResult {
                seen = args
                return AdbResult(0, "List of devices attached\nemulator-5554 device\n".toByteArray(), "")
            }
        }
        AdbDevices.list(adb)
        assertEquals(listOf("devices", "-l"), seen)
    }
}
