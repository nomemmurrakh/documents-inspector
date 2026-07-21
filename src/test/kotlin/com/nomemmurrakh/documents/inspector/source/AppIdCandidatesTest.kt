package com.nomemmurrakh.documents.inspector.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIdCandidatesTest {

    private val realPbxprojExcerpt = """
        		buildSettings = {
        			ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
        			CODE_SIGN_STYLE = Automatic;
        			LD_RUNPATH_SEARCH_PATHS = (
        				"$(inherited)",
        				"@executable_path/Frameworks",
        			);
        			PRODUCT_BUNDLE_IDENTIFIER = com.nomemmurrakh.documents.sample;
        			SWIFT_EMIT_LOC_STRINGS = YES;
        			TARGETED_DEVICE_FAMILY = "1,2";
        		};
        		name = Debug;
        		buildSettings = {
        			PRODUCT_BUNDLE_IDENTIFIER = com.nomemmurrakh.documents.sample;
        			SWIFT_VERSION = 5.0;
        		};
        		name = Release;
    """.trimIndent()

    @Test
    fun `parsePbxproj reads the literal bundle id and collapses debug and release`() {
        assertEquals(
            listOf("com.nomemmurrakh.documents.sample"),
            AppIdCandidates.parsePbxprojBundleIds(realPbxprojExcerpt),
        )
    }

    @Test
    fun `parsePbxproj reads quoted values`() {
        val text = """PRODUCT_BUNDLE_IDENTIFIER = "com.example.quoted";"""
        assertEquals(listOf("com.example.quoted"), AppIdCandidates.parsePbxprojBundleIds(text))
    }

    @Test
    fun `parsePbxproj skips unresolved build variables`() {
        val text = """
            PRODUCT_BUNDLE_IDENTIFIER = "${'$'}(inherited).suffix";
            PRODUCT_BUNDLE_IDENTIFIER = com.example.real;
        """.trimIndent()
        assertEquals(listOf("com.example.real"), AppIdCandidates.parsePbxprojBundleIds(text))
    }

    @Test
    fun `parsePbxproj skips test targets`() {
        val text = """
            PRODUCT_BUNDLE_IDENTIFIER = com.example.app;
            PRODUCT_BUNDLE_IDENTIFIER = com.example.app.tests;
            PRODUCT_BUNDLE_IDENTIFIER = com.example.app.UITests;
        """.trimIndent()
        assertEquals(listOf("com.example.app"), AppIdCandidates.parsePbxprojBundleIds(text))
    }

    @Test
    fun `parsePbxproj keeps distinct ids in order`() {
        val text = """
            PRODUCT_BUNDLE_IDENTIFIER = com.example.first;
            PRODUCT_BUNDLE_IDENTIFIER = com.example.second;
            PRODUCT_BUNDLE_IDENTIFIER = com.example.first;
        """.trimIndent()
        assertEquals(
            listOf("com.example.first", "com.example.second"),
            AppIdCandidates.parsePbxprojBundleIds(text),
        )
    }

    @Test
    fun `parsePbxproj returns nothing when the setting is absent`() {
        assertTrue(AppIdCandidates.parsePbxprojBundleIds("PRODUCT_NAME = Sample;").isEmpty())
    }

    private val listappsJson = """
        {
          "com.apple.Bridge" : {
            "ApplicationType" : "System",
            "CFBundleIdentifier" : "com.apple.Bridge",
            "CFBundleName" : "Watch"
          },
          "com.apple.mobilesafari" : {
            "ApplicationType" : "System",
            "CFBundleIdentifier" : "com.apple.mobilesafari"
          },
          "com.nexcore.bigbros" : {
            "ApplicationType" : "User",
            "CFBundleIdentifier" : "com.nexcore.bigbros",
            "CFBundleName" : "BigBros"
          },
          "com.nomemmurrakh.documents.sample" : {
            "ApplicationType" : "User",
            "CFBundleIdentifier" : "com.nomemmurrakh.documents.sample",
            "CFBundleName" : "iosApp"
          }
        }
    """.trimIndent()

    @Test
    fun `parseListapps keeps only user installed apps`() {
        assertEquals(
            listOf("com.nexcore.bigbros", "com.nomemmurrakh.documents.sample"),
            AppIdCandidates.parseListappsJson(listappsJson),
        )
    }

    @Test
    fun `parseListapps returns nothing for malformed input`() {
        assertTrue(AppIdCandidates.parseListappsJson("{ not json").isEmpty())
        assertTrue(AppIdCandidates.parseListappsJson("").isEmpty())
    }

    @Test
    fun `parseListapps returns nothing for the plist it is given unconverted`() {
        val plist = """
            {
                "com.apple.Bridge" =     {
                    ApplicationType = System;
                };
            }
        """.trimIndent()
        assertTrue(AppIdCandidates.parseListappsJson(plist).isEmpty())
    }

    @Test
    fun `merge keeps preferred first and drops duplicates and blanks`() {
        assertEquals(
            listOf("com.example.project", "com.example.installed"),
            AppIdCandidates.merge(
                listOf("com.example.project", "  ", "com.example.project"),
                listOf("com.example.installed", "com.example.project", ""),
            ),
        )
    }
}
