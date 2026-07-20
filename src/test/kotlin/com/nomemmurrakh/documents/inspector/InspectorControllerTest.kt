package com.nomemmurrakh.documents.inspector

import com.nomemmurrakh.documents.inspector.source.DeviceSource
import com.nomemmurrakh.documents.inspector.source.DeviceSourceException
import com.nomemmurrakh.documents.inspector.source.PulledCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InspectorControllerTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mmkv/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun `decodes pulled collections into a Loaded result`() {
        val source = object : DeviceSource {
            override fun pull() = listOf(
                PulledCollection("documents.default", fixture("documents.default"), fixture("documents.default.crc")),
            )
        }

        val result = InspectorController.load(source)

        val loaded = assertIs<LoadResult.Loaded>(result)
        assertEquals(1, loaded.collections.size)
        assertEquals("Default", loaded.collections.single().label)
        assertTrue(loaded.collections.single().documents.isNotEmpty())
    }

    @Test
    fun `an empty pull yields Loaded with no collections`() {
        val source = object : DeviceSource {
            override fun pull() = emptyList<PulledCollection>()
        }
        assertEquals(LoadResult.Loaded(emptyList()), InspectorController.load(source))
    }

    @Test
    fun `a DeviceSourceException becomes a Failed with its message`() {
        val source = object : DeviceSource {
            override fun pull(): List<PulledCollection> =
                throw DeviceSourceException("'x' is not debuggable, so its files cannot be read.")
        }

        val result = InspectorController.load(source)

        val failed = assertIs<LoadResult.Failed>(result)
        assertTrue(failed.message.contains("not debuggable"))
    }

    @Test
    fun `an unexpected error becomes a Failed decode message`() {
        val source = object : DeviceSource {
            override fun pull(): List<PulledCollection> = throw IllegalStateException("boom")
        }

        val result = InspectorController.load(source)

        val failed = assertIs<LoadResult.Failed>(result)
        assertTrue(failed.message.contains("boom"))
    }
}
