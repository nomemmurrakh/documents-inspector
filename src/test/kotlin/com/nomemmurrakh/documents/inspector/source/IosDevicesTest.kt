package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosDevicesTest {

    private fun devicectl(responder: (List<String>) -> DevicectlResult) = object : Devicectl {
        override fun run(args: List<String>) = responder(args)
    }

    private val devicesJson = """
        {
          "info" : { "outcome" : "success" },
          "result" : {
            "devices" : [
              {
                "identifier" : "08752C7F-00F0-525E-B374-49E9BFC190C8",
                "connectionProperties" : { "pairingState" : "paired", "tunnelState" : "connected" },
                "deviceProperties" : { "name" : "iPhone", "osVersionNumber" : "26.5.2" },
                "hardwareProperties" : { "marketingName" : "iPhone 13", "platform" : "iOS" }
              },
              {
                "identifier" : "AE99CCBA-CFF8-5C99-B2F1-36A87758D41F",
                "connectionProperties" : { "pairingState" : "paired", "tunnelState" : "unavailable" },
                "deviceProperties" : { "name" : "Arbab's iPhone" },
                "hardwareProperties" : { "marketingName" : "iPhone 12" }
              }
            ]
          }
        }
    """.trimIndent()

    /** devicectl reports failures as an `error` object while still exiting 0. */
    private val errorJson = """
        {
          "error" : {
            "code" : 3,
            "domain" : "CoreDevice.ActionError",
            "userInfo" : {
              "NSDebugDescription" : {
                "string" : "This operation could not be performed due to an error in StreamingAction: The system failed to get a list of files on the remote device."
              }
            }
          },
          "info" : { "outcome" : "failed" }
        }
    """.trimIndent()

    @Test
    fun `parseDevices reads identifier name and model`() {
        val devices = IosDevices.parseDevices(devicesJson)
        assertEquals(2, devices.size)
        assertEquals(IosDevice("08752C7F-00F0-525E-B374-49E9BFC190C8", "iPhone", "iPhone 13"), devices[0])
        assertEquals("iPhone (iPhone 13)", devices[0].displayName)
    }

    @Test
    fun `parseDevices tolerates malformed json`() {
        assertTrue(IosDevices.parseDevices("{ not json").isEmpty())
        assertTrue(IosDevices.parseDevices("").isEmpty())
    }

    @Test
    fun `list invokes the list devices command`() {
        var seen: List<String>? = null
        IosDevices.list(devicectl { args -> seen = args; DevicectlResult(0, devicesJson, "") })
        assertEquals(listOf("list", "devices"), seen)
    }

    @Test
    fun `list surfaces an error reported despite a zero exit`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosDevices.list(devicectl { DevicectlResult(0, errorJson, "") })
        }
        assertTrue(ex.message!!.contains("failed to get a list of files"))
    }

    @Test
    fun `list fails clearly when no json was written`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosDevices.list(devicectl { DevicectlResult(1, "", "xcrun: error: unable to find utility") })
        }
        assertTrue(ex.message!!.contains("unable to find utility"))
    }

    private val appsJson = """
        {
          "result" : {
            "apps" : [
              { "bundleIdentifier" : "com.apple.Maps", "builtByDeveloper" : false, "name" : "Maps" },
              { "bundleIdentifier" : "com.nomemmurrakh.documents.sample", "builtByDeveloper" : true, "name" : "sample" },
              { "bundleIdentifier" : "com.sentra.gymifo", "builtByDeveloper" : true, "name" : "Gymifo" }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `listApps keeps only developer built apps`() {
        val seen = mutableListOf<List<String>>()
        val apps = IosDevices.listApps(
            devicectl { args ->
                synchronized(seen) { seen += args }
                if (args.take(3) == listOf("device", "info", "apps")) DevicectlResult(0, appsJson, "")
                else DevicectlResult(0, """{ "result" : { "files" : [] } }""", "")
            },
            "the-device",
        )
        assertEquals(listOf("device", "info", "apps", "--device", "the-device"), seen.first())
        assertEquals(listOf("com.nomemmurrakh.documents.sample", "com.sentra.gymifo"), apps)
    }

    @Test
    fun `listApps yields no candidates on an error`() {
        assertTrue(IosDevices.listApps(devicectl { DevicectlResult(0, errorJson, "") }, "d").isEmpty())
    }

    /** Answers the app listing, then a per-app store probe for whichever bundle ids [withStore] names. */
    private fun devicectlWithStores(vararg withStore: String) = devicectl { args ->
        when {
            args.take(3) == listOf("device", "info", "apps") -> DevicectlResult(0, appsJson, "")
            else -> {
                val bundleId = args[args.indexOf("--domain-identifier") + 1]
                if (bundleId in withStore) {
                    DevicectlResult(
                        0,
                        """{ "result" : { "files" : [
                            { "name" : "documents.default", "relativePath" : "documents.default",
                              "resources" : { "isDirectory" : false } } ] } }""",
                        "",
                    )
                } else {
                    DevicectlResult(0, """{ "result" : { "files" : [] } }""", "")
                }
            }
        }
    }

    @Test
    fun `listApps ranks apps holding a Documents store first`() {
        val apps = IosDevices.listApps(
            devicectlWithStores("com.nomemmurrakh.documents.sample"),
            "the-device",
        )
        assertEquals(listOf("com.nomemmurrakh.documents.sample", "com.sentra.gymifo"), apps)
    }

    @Test
    fun `listApps keeps listing order when no app holds a store`() {
        val apps = IosDevices.listApps(devicectlWithStores(), "the-device")
        assertEquals(listOf("com.nomemmurrakh.documents.sample", "com.sentra.gymifo"), apps)
    }

    @Test
    fun `listApps does not probe when only one app is installed`() {
        val singleAppJson = """
            {
              "result" : {
                "apps" : [
                  { "bundleIdentifier" : "com.example.only", "builtByDeveloper" : true }
                ]
              }
            }
        """.trimIndent()
        var calls = 0
        val apps = IosDevices.listApps(
            devicectl { calls++; DevicectlResult(0, singleAppJson, "") },
            "the-device",
        )
        assertEquals(listOf("com.example.only"), apps)
        assertEquals(1, calls)
    }

    @Test
    fun `listApps treats a failing probe as no store rather than dropping the app`() {
        val apps = IosDevices.listApps(
            devicectl { args ->
                if (args.take(3) == listOf("device", "info", "apps")) DevicectlResult(0, appsJson, "")
                else DevicectlResult(0, errorJson, "")
            },
            "the-device",
        )
        assertEquals(listOf("com.nomemmurrakh.documents.sample", "com.sentra.gymifo"), apps)
    }

    /**
     * The real shape when `--subdirectory` is given: `relativePath` is already relative to it, so
     * entries are bare names. Captured from a physical iPhone.
     */
    private val filesJson = """
        {
          "result" : {
            "files" : [
              { "name" : "cache", "relativePath" : "cache", "resources" : { "isDirectory" : false } },
              { "name" : "cache.crc", "relativePath" : "cache.crc", "resources" : { "isDirectory" : false } },
              {
                "name" : "documents.default",
                "relativePath" : "documents.default",
                "resources" : { "isDirectory" : false }
              },
              {
                "name" : "documents.default.crc",
                "relativePath" : "documents.default.crc",
                "resources" : { "isDirectory" : false }
              },
              { "name" : "nested", "relativePath" : "nested", "resources" : { "isDirectory" : true } },
              {
                "name" : "deep.txt",
                "relativePath" : "nested/deep.txt",
                "resources" : { "isDirectory" : false }
              }
            ]
          }
        }
    """.trimIndent()

    /** Listing a whole domain (no `--subdirectory`) instead reports full paths. */
    private val wholeDomainJson = """
        {
          "result" : {
            "files" : [
              {
                "name" : "documents.default",
                "relativePath" : "Documents/MMKV/documents.default",
                "resources" : { "isDirectory" : false }
              },
              {
                "name" : "deep.txt",
                "relativePath" : "Documents/MMKV/nested/deep.txt",
                "resources" : { "isDirectory" : false }
              },
              {
                "name" : "elsewhere.txt",
                "relativePath" : "Library/Caches/elsewhere.txt",
                "resources" : { "isDirectory" : false }
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parseFileNames reads bare names when the listing is already subdirectory relative`() {
        assertEquals(
            listOf("cache", "cache.crc", "documents.default", "documents.default.crc"),
            IosDevices.parseFileNames(filesJson, "Documents/MMKV"),
        )
    }

    @Test
    fun `parseFileNames strips the prefix when the listing spans the whole domain`() {
        assertEquals(
            listOf("documents.default"),
            IosDevices.parseFileNames(wholeDomainJson, "Documents/MMKV"),
        )
    }

    @Test
    fun `parseFileNames tolerates malformed json`() {
        assertTrue(IosDevices.parseFileNames("{ not json", "Documents/MMKV").isEmpty())
    }

    @Test
    fun `listFiles builds the app data container query`() {
        var seen: List<String>? = null
        IosDevices.listFiles(
            devicectl { args -> seen = args; DevicectlResult(0, filesJson, "") },
            "the-device",
            "com.example.app",
            "Documents/MMKV",
        )
        assertEquals(
            listOf(
                "device", "info", "files",
                "--device", "the-device",
                "--domain-type", "appDataContainer",
                "--domain-identifier", "com.example.app",
                "--subdirectory", "Documents/MMKV",
            ),
            seen,
        )
    }

    @Test
    fun `listFiles explains a missing store rather than leaking the raw error`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosDevices.listFiles(
                devicectl { DevicectlResult(0, errorJson, "") },
                "the-device",
                "com.example.app",
                "Documents/MMKV",
            )
        }
        assertTrue(ex.message!!.contains("development build"))
        assertTrue(ex.message!!.contains("com.example.app"))
    }

    @Test
    fun `copyFrom builds the copy command`() {
        var seen: List<String>? = null
        IosDevices.copyFrom(
            devicectl { args -> seen = args; DevicectlResult(0, """{"result":{}}""", "") },
            "the-device",
            "com.example.app",
            "Documents/MMKV/documents.default",
            "/tmp/staging/documents.default",
        )
        assertEquals(
            listOf(
                "device", "copy", "from",
                "--device", "the-device",
                "--domain-type", "appDataContainer",
                "--domain-identifier", "com.example.app",
                "--source", "Documents/MMKV/documents.default",
                "--destination", "/tmp/staging/documents.default",
            ),
            seen,
        )
    }

    @Test
    fun `copyFrom surfaces a copy failure`() {
        val ex = assertFailsWith<DeviceSourceException> {
            IosDevices.copyFrom(
                devicectl { DevicectlResult(0, errorJson, "") },
                "d",
                "com.example.app",
                "Documents/MMKV/x",
                "/tmp/x",
            )
        }
        assertTrue(ex.message!!.contains("Failed to copy"))
    }
}
