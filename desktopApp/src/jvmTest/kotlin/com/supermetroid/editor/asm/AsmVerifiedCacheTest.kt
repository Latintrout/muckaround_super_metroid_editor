package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmVerifiedCacheTest {
    @TempDir lateinit var directory: File
    @Serializable private data class Value(val number: Int)

    @Test fun `warm caches avoid decoding while changed dependencies use independent immutable keys`() {
        val cache = AsmVerifiedCache(directory)
        var builds = 0
        fun load(key: String, value: Int) = cache.loadOrBuild("test", key, Value.serializer()) { builds++; Value(value) }
        assertEquals(Value(1), load("source-one", 1))
        assertEquals(Value(1), load("source-one", 99))
        assertEquals(1, builds)
        assertEquals(Value(2), load("source-two", 2))
        assertEquals(Value(1), load("source-one", 99)) // A late old result cannot replace the new key.
        assertEquals(Value(2), load("source-two", 99))
        assertEquals(2, builds)
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun `torn wrong-version and checksum-invalid caches rebuild atomically`() {
        val cache = AsmVerifiedCache(directory)
        var builds = 0
        fun load() = cache.loadOrBuild("test", "source", Value.serializer()) { builds++; Value(7) }
        load()
        val file = directory.listFiles()!!.single()
        for (damage in listOf<(String) -> String>(
            { "{torn" }, { it.replace("\"version\":1", "\"version\":999") },
            { it.replace("number\\\":7", "number\\\":99") },
        )) {
            val valid = file.readText()
            val damaged = damage(valid)
            assertNotEquals(valid, damaged)
            file.writeText(damaged)
            assertEquals(Value(7), load())
            assertEquals(valid, file.readText())
        }
        assertEquals(4, builds)
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun `unwritable caches do not block previews and unsafe cache names are rejected`() {
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val cache = AsmVerifiedCache(blocked)
        assertEquals(Value(5), cache.loadOrBuild("test", "source", Value.serializer()) { Value(5) })
        assertEquals("not a directory", blocked.readText())
        assertFailsWith<IllegalArgumentException> { cache.loadOrBuild("../outside", "source", Value.serializer()) { Value(8) } }
    }

    @Test fun `old disposable generations are bounded without deleting the current snapshot`() {
        val cache = AsmVerifiedCache(directory)
        repeat(15) { key -> cache.loadOrBuild("test", "source-$key", Value.serializer()) { Value(key) } }
        assertEquals(4, directory.listFiles()!!.size)
        assertEquals(Value(14), cache.loadOrBuild("test", "source-14", Value.serializer()) { error("Current cache was lost") })
    }
}
