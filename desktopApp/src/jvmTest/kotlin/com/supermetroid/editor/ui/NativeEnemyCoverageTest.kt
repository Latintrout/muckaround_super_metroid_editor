package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Exercises the real room cache, not just tile loading or optional legacy image comparisons. */
class NativeEnemyCoverageTest {
    // The two contiguous bank-A0 source header tables contain 164 64-byte records.
    private val sourceSpecies = (0xCEBF..0xF0FF step 0x40).toList() + (0xF153..0xF793 step 0x40).toList()
    // Sentinel, collision follower, Ridley graphics owner, projectile-graphics loader, source-unused header.
    private val nonvisual = setOf(0xDAFF, 0xE03F, 0xE1BF, 0xF03F, 0xF153)

    @Test
    fun `bundled enemy PNGs and no-ROM image fallbacks do not exist`() {
        EnemySpriteCache.setRomParser(null)
        for (species in sourceSpecies) {
            val hex = species.toString(16).uppercase()
            assertNull(javaClass.getResource("/enemies/$hex.png"), hex)
            assertNull(EnemySpriteCache.getPreview(RomParser.EnemyEntry(species, 128, 128, 0, 0)), hex)
        }
        assertNull(javaClass.getResource("/enemies/unknown.png"))
    }
    @Test
    fun `Fune and Namihe use their own complete source idle maps`() {
        val parser = TestRomHelper.requireRomParser()
        val maps = EnemySpritemap(parser)
        EnemySpriteCache.setRomParser(parser)
        try {
            for ((species, slot) in listOf(0xE6FF to 0xA896D7, 0xE73F to 0xA896DF)) {
                val list = 0xA80000 or parser.readUInt16At(parser.snesToPc(slot))
                val address = 0xA80000 or parser.readUInt16At(parser.snesToPc(list + 2))
                val map = requireNotNull(maps.parseSpritemap(address))
                val tiles = requireNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, species))
                val palette = requireNotNull(EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, species)).colors
                val expected = requireNotNull(maps.renderSpritemap(map, tiles, palette))
                val actual = requireNotNull(EnemySpriteCache.getPreview(RomParser.EnemyEntry(species, 128, 128, 0, 0)))
                assertArrayEquals(expected.pixels, actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width), species.toString(16))
                assertEquals(expected.originX, actual.originX)
                assertEquals(expected.originY, actual.originY)
            }
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `Phantoon behavior thumbnails are full native components and no ROM means no thumbnail`() {
        assertTrue(phantoonBehaviorSprites(null).isEmpty())
        val parser = TestRomHelper.requireRomParser()
        val actual = phantoonBehaviorSprites(parser)
        assertEquals(4, actual.size)
        val renderer = PhantoonSpritemap(parser)
        assertTrue(renderer.load())
        val full = requireNotNull(renderer.renderFullBody())
        assertArrayEquals(full.pixels, actual.first().getRGB(0, 0, actual.first().width, actual.first().height, null, 0, actual.first().width))
        assertTrue(actual.all { image -> image.getRGB(0, 0, image.width, image.height, null, 0, image.width).any { it ushr 24 != 0 } })
    }

    @Test
    fun `all source-visible species have nonblank native room previews without bundled images`() {
        val parser = TestRomHelper.requireRomParser()
        assertEquals(164, sourceSpecies.size)
        EnemySpriteCache.setRomParser(parser)
        try {
            val visible = sourceSpecies.filter { it !in nonvisual }
            assertEquals(159, visible.size)
            val sheet = BufferedImage(1300, ((visible.size + 12) / 13) * 120, BufferedImage.TYPE_INT_ARGB)
            val graphics = sheet.createGraphics().apply {
                color = Color(0x202028); fillRect(0, 0, sheet.width, sheet.height)
                font = Font("SansSerif", Font.PLAIN, 9)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            }
            val missing = visible.mapIndexedNotNull { index, id ->
                val preview = EnemySpriteCache.getPreview(RomParser.EnemyEntry(id, 128, 128, 0, 0))
                if (preview != null) {
                    val x = index % 13 * 100
                    val y = index / 13 * 120
                    val scale = minOf(1.0, 96.0 / preview.image.width, 94.0 / preview.image.height)
                    val width = (preview.image.width * scale).toInt().coerceAtLeast(1)
                    val height = (preview.image.height * scale).toInt().coerceAtLeast(1)
                    graphics.drawImage(preview.image, x + (100 - width) / 2, y + 94 - height, width, height, null)
                    graphics.color = Color.WHITE
                    graphics.drawString(id.toString(16).uppercase(), x + 3, y + 105)
                    graphics.drawString(RomParser.enemyName(id).take(18), x + 3, y + 116)
                }
                if (preview == null || preview.image.getRGB(0, 0, preview.image.width, preview.image.height, null, 0, preview.image.width).none { it ushr 24 != 0 })
                    "${id.toString(16)} ${RomParser.enemyName(id)}" else null
            }
            graphics.dispose()
            ImageIO.write(sheet, "png", File(TestRomHelper.outputDir(), "native-enemy-coverage.png"))
            assertTrue(missing.isEmpty(), "Missing native previews: ${missing.joinToString()}")
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `every visible vanilla room population renders natively including alternate states`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            val missing = mutableSetOf<String>()
            for (info in RoomRepository().getAllRooms()) for (state in parser.parseRoomStatesWithData(info.getRoomIdAsInt())) {
                for (entry in parser.parseEnemyPopulation(state.enemySetPtr)) {
                    if (entry.id in nonvisual) continue
                    val preview = EnemySpriteCache.getPreview(entry)
                    if (preview == null || preview.image.getRGB(0, 0, preview.image.width, preview.image.height, null, 0, preview.image.width).none { it ushr 24 != 0 })
                        missing += "${entry.id.toString(16)} ${RomParser.enemyName(entry.id)} (room ${info.getRoomIdAsInt().toString(16)})"
                }
            }
            assertTrue(missing.isEmpty(), "Missing native population previews: ${missing.joinToString()}")
        } finally { EnemySpriteCache.setRomParser(null) }
    }
}
