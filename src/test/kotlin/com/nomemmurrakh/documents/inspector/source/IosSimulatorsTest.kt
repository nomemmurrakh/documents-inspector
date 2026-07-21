package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosSimulatorsTest {

    private fun simctl(responder: (List<String>) -> SimctlResult) = object : Simctl {
        override fun run(args: List<String>) = responder(args)
    }

    private val bootedJson = """
        {
          "devices" : {
            "com.apple.CoreSimulator.SimRuntime.iOS-26-1" : [
              {
                "udid" : "A900261F-74D8-4D5B-A048-854DF2AAAEB6",
                "state" : "Booted",
                "name" : "iPhone 17 Pro",
                "isAvailable" : true
              },
              {
                "udid" : "SHUT-DOWN-ONE",
                "state" : "Shutdown",
                "name" : "iPhone SE"
              }
            ],
            "com.apple.CoreSimulator.SimRuntime.iOS-18-6" : [
              {
                "udid" : "OLD-BOOTED",
                "state" : "Booted",
                "name" : "iPhone 14"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parse returns only booted simulators with os label`() {
        val sims = IosSimulators.parse(bootedJson)
        assertEquals(2, sims.size)
        assertEquals(IosSimulator("A900261F-74D8-4D5B-A048-854DF2AAAEB6", "iPhone 17 Pro", "iOS 26.1"), sims[0])
        assertEquals(IosSimulator("OLD-BOOTED", "iPhone 14", "iOS 18.6"), sims[1])
    }

    @Test
    fun `parse handles an empty device list`() {
        val json = """{ "devices" : { "com.apple.CoreSimulator.SimRuntime.iOS-26-1" : [] } }"""
        assertTrue(IosSimulators.parse(json).isEmpty())
    }

    @Test
    fun `displayName combines name and os`() {
        assertEquals("iPhone 17 Pro (iOS 26.1)", IosSimulator("u", "iPhone 17 Pro", "iOS 26.1").displayName)
    }

    @Test
    fun `listBooted invokes the json listing command`() {
        var seen: List<String>? = null
        val sims = IosSimulators.listBooted(simctl { args -> seen = args; SimctlResult(0, bootedJson, "") })
        assertEquals(listOf("list", "devices", "booted", "-j"), seen)
        assertEquals(2, sims.size)
    }

    @Test
    fun `listBooted surfaces a simctl failure`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosSimulators.listBooted(simctl { SimctlResult(1, "", "xcrun: error: unable to find utility") })
        }
        assertTrue(ex.message!!.contains("unable to find utility"))
    }

    @Test
    fun `appContainer returns the resolved path`() {
        val path = IosSimulators.appContainer(
            simctl { SimctlResult(0, "/Users/x/.../data/Containers/Data/Application/ABC\n", "") },
            "udid",
            "com.example.app",
        )
        assertEquals("/Users/x/.../data/Containers/Data/Application/ABC", path)
    }

    @Test
    fun `appContainer maps a not-installed failure`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosSimulators.appContainer(
                simctl { SimctlResult(2, "", "No such file or directory") },
                "udid",
                "com.example.app",
            )
        }
        assertTrue(ex.message!!.contains("not installed"))
    }

    @Test
    fun `appContainer invokes get_app_container with data`() {
        var seen: List<String>? = null
        IosSimulators.appContainer(
            simctl { args -> seen = args; SimctlResult(0, "/path", "") },
            "the-udid",
            "com.example.app",
        )
        assertEquals(listOf("get_app_container", "the-udid", "com.example.app", "data"), seen)
    }

    private fun plistToJson(converted: String?) = object : PlistToJson {
        override fun convert(plist: String): String? = converted
    }

    private val userAppsJson = """
        {
          "com.apple.Bridge" : { "ApplicationType" : "System", "CFBundleIdentifier" : "com.apple.Bridge" },
          "com.nomemmurrakh.documents.sample" : {
            "ApplicationType" : "User",
            "CFBundleIdentifier" : "com.nomemmurrakh.documents.sample"
          }
        }
    """.trimIndent()

    @Test
    fun `listApps invokes listapps and returns user installed bundle ids`() {
        var seen: List<String>? = null
        val apps = IosSimulators.listApps(
            simctl { args -> seen = args; SimctlResult(0, "(plist text)", "") },
            "the-udid",
            plistToJson(userAppsJson),
        )
        assertEquals(listOf("listapps", "the-udid"), seen)
        assertEquals(listOf("com.nomemmurrakh.documents.sample"), apps)
    }

    @Test
    fun `listApps yields no candidates when the plist cannot be converted`() {
        val apps = IosSimulators.listApps(
            simctl { SimctlResult(0, "(plist text)", "") },
            "the-udid",
            plistToJson(null),
        )
        assertTrue(apps.isEmpty())
    }

    @Test
    fun `listApps yields no candidates when simctl reports an error while exiting zero`() {
        val shutdownError = "An error was encountered processing the command " +
            "(domain=com.apple.CoreSimulator.SimError, code=405):\nUnable to lookup in current state: Shutdown"
        val apps = IosSimulators.listApps(
            simctl { SimctlResult(0, shutdownError, "") },
            "the-udid",
            plistToJson(null),
        )
        assertTrue(apps.isEmpty())
    }

    private val twoUserAppsJson = """
        {
          "com.nexcore.bigbros" : {
            "ApplicationType" : "User",
            "CFBundleIdentifier" : "com.nexcore.bigbros",
            "DataContainer" : "file:///sim/bigbros/"
          },
          "com.nomemmurrakh.documents.sample" : {
            "ApplicationType" : "User",
            "CFBundleIdentifier" : "com.nomemmurrakh.documents.sample",
            "DataContainer" : "file:///sim/sample/"
          }
        }
    """.trimIndent()

    private fun filesWithStoreAt(vararg dirs: String) = object : CollectionFiles {
        override fun listNames(dir: String): List<String> =
            if (dirs.any { dir == "$it/Documents/mmkv" }) listOf("documents.default", "documents.default.crc")
            else emptyList()

        override fun read(dir: String, name: String) = ByteArray(0)
    }

    @Test
    fun `listApps ranks apps holding a Documents store first`() {
        val apps = IosSimulators.listApps(
            simctl { SimctlResult(0, "(plist)", "") },
            "the-udid",
            plistToJson(twoUserAppsJson),
            filesWithStoreAt("/sim/sample"),
        )
        assertEquals(listOf("com.nomemmurrakh.documents.sample", "com.nexcore.bigbros"), apps)
    }

    @Test
    fun `listApps keeps listing order when no app has a store`() {
        val apps = IosSimulators.listApps(
            simctl { SimctlResult(0, "(plist)", "") },
            "the-udid",
            plistToJson(twoUserAppsJson),
            filesWithStoreAt(),
        )
        assertEquals(listOf("com.nexcore.bigbros", "com.nomemmurrakh.documents.sample"), apps)
    }

    @Test
    fun `installed app decodes its percent encoded file url container`() {
        val app = InstalledApp("com.example.app", "file:///Users/x/My%20Sim/Data/")
        assertEquals("/Users/x/My Sim/Data", app.containerPath)
    }

    @Test
    fun `installed app tolerates a missing container`() {
        assertEquals(null, InstalledApp("com.example.app", null).containerPath)
    }

    @Test
    fun `listApps never throws when simctl itself fails`() {
        val apps = IosSimulators.listApps(
            simctl { throw DeviceSourceException("xcrun simctl timed out") },
            "the-udid",
            plistToJson(userAppsJson),
        )
        assertTrue(apps.isEmpty())
    }
}
