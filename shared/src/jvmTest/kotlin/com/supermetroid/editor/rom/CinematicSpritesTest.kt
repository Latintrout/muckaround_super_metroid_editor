package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class CinematicSpritesTest {
    @Test
    fun `ending Samus uses initialization palettes rather than the diagnostic placeholder row`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = CinematicSprites(parser)
        for ((name, address) in listOf("Ending · Samus jump" to 0x8CE989, "Ending · Samus landing" to 0x8CE9A9)) {
            val sequence = CinematicSprites.SEQUENCES.single { it.name == name }
            val animation = assertNotNull(renderer.render(sequence))
            val expectedColors = RomVisualAssets.palette(parser, address, 16).toSet()
            assertTrue(animation.frames.all { frame -> frame.pixels.all { it in expectedColors } }, name)
        }
    }

    @Test
    fun `cinematic sequences render their complete timed frame tracks`() {
        val renderer = CinematicSprites(TestRomHelper.requireRomParser())
        for (sequence in CinematicSprites.SEQUENCES) {
            val animation = assertNotNull(renderer.render(sequence), sequence.name)
            assertTrue(animation.frames.any { frame -> frame.pixels.any { it ushr 24 != 0 } }, sequence.name)
        }
        val hatch = assertNotNull(renderer.render(CinematicSprites.SEQUENCES.single { it.name.endsWith("Egg hatching") }))
        assertEquals(31, hatch.frames.size)
        assertEquals(632, hatch.totalTicks)
        assertFalse(hatch.loop)
    }

    @Test
    fun `death frames use separate graphics and both palette tracks for all suits`() {
        val renderer = SamusDeathSprites(TestRomHelper.requireRomParser())
        for (suit in 0..2) for (left in listOf(false, true)) {
            val animation = assertNotNull(renderer.render(suit, left), "suit $suit left $left")
            assertEquals(9, animation.frames.size)
            assertEquals(listOf(21, 6, 3, 4, 5, 5, 6, 6, 80), animation.frames.map { it.durationTicks })
            assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } })
            assertFalse(animation.loop)
        }
    }
}
