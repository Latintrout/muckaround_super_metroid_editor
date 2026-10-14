package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class ProjectileSpritesTest {
    @Test
    fun `all equipped beam combinations and weapon effects render in every direction`() {
        val renderer = ProjectileSprites(TestRomHelper.requireRomParser())
        for (weapon in ProjectileSprites.WEAPONS) for (direction in ProjectileSprites.DIRECTIONS.indices) {
            val animation = assertNotNull(renderer.render(weapon, direction), "${weapon.name} direction $direction")
            assertTrue(animation.frames.any { frame -> frame.pixels.any { it ushr 24 != 0 } }, weapon.name)
            assertTrue(animation.frames.all { it.durationTicks > 0 })
            assertEquals(1, animation.frames.map { it.width to it.height }.distinct().size)
        }
    }

    @Test
    fun `Power beam follows its eight byte frame and goto timer contract`() {
        val animation = assertNotNull(ProjectileSprites(TestRomHelper.requireRomParser()).render(ProjectileSprites.WEAPONS.first(), 2))
        assertEquals(1, animation.frames.size)
        assertEquals(15, animation.totalTicks)
        assertTrue(animation.loop)
        assertEquals(8 to 8, animation.frames.single().width to animation.frames.single().height)
    }
}
