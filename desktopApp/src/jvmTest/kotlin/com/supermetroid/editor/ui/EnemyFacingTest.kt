package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnemyFacingTest {
    @Test
    fun `displayed pirate facing matches the exact initial runtime pose`() {
        val parser = TestRomHelper.requireRomParser()
        val maps = EnemySpritemap(parser)
        EnemySpriteCache.setRomParser(parser)
        try {
            val records = mapOf(0xF353 to (0xB2ED3E to 0xB2EDB4), 0xF593 to (0xB2F2DE to 0xB2F4D0), 0xF653 to (0xB2FB68 to 0xB2FBEA))
            for ((id, pair) in records) for (direction in 0..1) {
                val record = if (direction == 0) pair.first else pair.second
                val pointer = 0xB20000 or parser.readUInt16At(parser.snesToPc(record + 2))
                val map = maps.flattenRenderableFrame(requireNotNull(maps.parseRenderableFrame(pointer)))
                val tiles = requireNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, id))
                val palette = requireNotNull(EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, id)).colors
                val expected = requireNotNull(maps.renderSpritemap(map, tiles, palette))
                val enemy = RomParser.EnemyEntry(id, 128, 128, 0, 0, extra2 = direction)
                val preview = requireNotNull(EnemySpriteCache.getPreview(enemy))
                val image = preview.image
                val actual = IntArray(image.width * image.height) { index ->
                    val x = index % image.width
                    image.getRGB(if (shouldFlipEnemySprite(enemy)) image.width - 1 - x else x, index / image.width)
                }
                assertArrayEquals(expected.pixels, actual, "${id.toString(16)} init0=$direction")
                assertEquals(expected.originX, if (shouldFlipEnemySprite(enemy)) image.width - preview.originX else preview.originX)
                assertEquals(expected.originY, preview.originY)
            }
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `wall pirate facing is opposite to ninja and walking pirate encoding`() {
        for (id in listOf(0xF353, 0xF393, 0xF3D3, 0xF413, 0xF453, 0xF493)) {
            val info = requireNotNull(enemyFacingFieldInfo(id))
            assertTrue(info.isFacingRight("0000", "8000"), id.toString(16))
            assertFalse(info.isFacingRight("0000", "8001"), id.toString(16))
            assertEquals("2468" to "8001", info.applyFacing("2468", "8000", false))
            assertEquals("2468" to "8000", info.applyFacing("2468", "8001", true))
        }
        for (id in listOf(0xF4D3, 0xF513, 0xF553, 0xF593, 0xF5D3, 0xF613, 0xF653, 0xF693, 0xF6D3, 0xF713, 0xF753, 0xF793)) {
            val info = requireNotNull(enemyFacingFieldInfo(id))
            assertFalse(info.isFacingRight("0000", "8000"), id.toString(16))
            assertTrue(info.isFacingRight("0000", "8001"), id.toString(16))
            assertEquals("2468" to "8000", info.applyFacing("2468", "8001", false))
            assertEquals("2468" to "8001", info.applyFacing("2468", "8000", true))
        }
    }

    @Test
    fun `Fune and Namihe facing encoding remains unchanged`() {
        for (id in listOf(0xE6FF, 0xE73F)) {
            val info = requireNotNull(enemyFacingFieldInfo(id))
            assertTrue(info.isFacingRight("0000", "8003"))
            assertFalse(info.isFacingRight("0000", "8013"))
            assertEquals("2468" to "8013", info.applyFacing("2468", "8003", false))
            assertEquals("2468" to "8003", info.applyFacing("2468", "8013", true))
            assertFalse(shouldFlipEnemySprite(RomParser.EnemyEntry(id, 128, 128, 0, 0, extra2 = 0x8003)))
            assertTrue(shouldFlipEnemySprite(RomParser.EnemyEntry(id, 128, 128, 0, 0, extra2 = 0x8013)))
        }
    }

    @Test
    fun `pirate room previews are assembled from ROM tiles maps and palettes`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            for (id in listOf(0xF353, 0xF593, 0xF653)) {
                val expected = requireNotNull(com.supermetroid.editor.rom.PirateSpritemap(parser).renderInitial(id, 0))
                val preview = requireNotNull(EnemySpriteCache.getPreview(RomParser.EnemyEntry(id, 128, 128, 0, 0)))
                val pixels = preview.image.getRGB(0, 0, preview.image.width, preview.image.height, null, 0, preview.image.width)
                assertArrayEquals(expected.pixels, pixels, id.toString(16))
                assertEquals(expected.originX, preview.originX)
                assertEquals(expected.originY, preview.originY)
                assertTrue(pixels.any { it ushr 24 != 0 })
                // Source OAM already includes facing; the room renderer must not mirror it again.
                assertFalse(shouldFlipEnemySprite(RomParser.EnemyEntry(id, 128, 128, 0, 0)))
                assertFalse(shouldFlipEnemySprite(RomParser.EnemyEntry(id, 128, 128, 0, 0, extra2 = 1)))
            }
        } finally {
            EnemySpriteCache.setRomParser(null)
        }
    }
}
