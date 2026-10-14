package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class ShipSpritemapTest {
    @Test
    fun `runtime tile placements render Ceres doors and shared Kihunter wings visibly`() {
        val parser = TestRomHelper.requireRomParser()
        val maps = EnemySpritemap(parser)
        for (id in listOf(0xE23F, 0xEAFF, 0xEB7F, 0xEBFF)) {
            val tiles = assertNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, id))
            val palette = assertNotNull(EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, id)).colors
            val animation = assertNotNull(maps.buildAnimation(id, tiles, palette), id.toString(16))
            assertTrue(animation.frames.any { frame -> frame.pixels.any { it ushr 24 != 0 } }, id.toString(16))
        }
    }

    @Test
    fun `gunship bottom borrows the complete top graphics without changing edit ownership`() {
        val parser = TestRomHelper.requireRomParser()
        assertEquals(0x200, EnemySpriteGraphics.loadEnemyTileData(parser, 0xD0BF)?.size)
        val source = assertNotNull(EnemySpriteGraphics.loadEnemyPreviewTileSource(parser, 0xD0BF))
        assertFalse(source.editable)
        assertEquals(0xD07F, source.sourceSpeciesId)
        assertContentEquals(EnemySpriteGraphics.loadEnemyTileData(parser, 0xD07F), source.bytes)
    }

    @Test
    fun `landed parts preserve initialization offsets and distinct bottom pad selection`() {
        val ship = ShipSpritemap(TestRomHelper.requireRomParser())
        val top = assertNotNull(ship.renderPart(0xD07F))
        val bottom = assertNotNull(ship.renderPart(0xD0BF))
        val pad = assertNotNull(ship.renderPart(0xD0BF, entrancePad = true))
        assertEquals(79 to 43, top.originX to top.originY)
        assertEquals(95 to 11, bottom.originX to bottom.originY)
        assertEquals(16 to 34, pad.originX to pad.originY)
        assertEquals(3656, top.pixels.count { it ushr 24 != 0 })
        assertEquals(7140, bottom.pixels.count { it ushr 24 != 0 })
        assertEquals(244, pad.pixels.count { it ushr 24 != 0 })
        val complete = assertNotNull(ship.renderLanded())
        assertEquals(192 to 80, complete.width to complete.height)
        assertEquals(95 to 43, complete.originX to complete.originY)
    }

    @Test
    fun `pad animations retain source frame timing and full ship context`() {
        val animations = ShipSpritemap(TestRomHelper.requireRomParser()).animations()
        assertEquals(listOf(1, 11, 9), animations.map { it.frames.size })
        assertEquals(listOf(1, 122, 78), animations.map { it.totalTicks })
        assertTrue(animations.all { !it.loop })
        assertTrue(animations.flatMap { it.frames }.all { frame ->
            frame.width == 192 && frame.height == 80 && frame.pixels.count { it ushr 24 != 0 } > 10000
        })
    }
}
