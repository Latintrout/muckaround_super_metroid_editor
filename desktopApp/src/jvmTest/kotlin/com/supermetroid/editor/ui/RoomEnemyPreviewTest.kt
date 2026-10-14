package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class RoomEnemyPreviewTest {
    @Test
    fun `all boss room previews are complete native portraits`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            for (id in RoomBossPreview.SPECIES) {
                val expected = requireNotNull(RoomBossPreview(parser).render(id)) { id.toString(16) }
                val actual = requireNotNull(EnemySpriteCache.getPreview(RomParser.EnemyEntry(id, 128, 128, 0, 0)))
                assertTrue(expected.width > 24 && expected.height > 24, id.toString(16))
                assertTrue(expected.pixels.count { it ushr 24 != 0 } > 200, id.toString(16))
                assertArrayEquals(expected.pixels, actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width))
                ImageIO.write(actual.image, "png", File(TestRomHelper.outputDir(), "room-boss-${id.toString(16)}.png"))
            }
        } finally { EnemySpriteCache.setRomParser(null) }
        for (id in RoomBossPreview.SPECIES) assertNull(EnemySpriteCache.get(id.toString(16).uppercase()), "Bosses must not fall back to legacy PNGs")
    }

    @Test
    fun `Sova and related crawler previews use normalized source orientations`() {
        val parser = TestRomHelper.requireRomParser()
        val maps = EnemySpritemap(parser)
        EnemySpriteCache.setRomParser(parser)
        try {
            val records = listOf(0xA3E260, 0xA3E27C, 0xA3E298, 0xA3E2B4)
            for (id in CrawlerSpritemap.SPECIES) for (orientation in 0..3) {
                val pointer = 0xA30000 or parser.readUInt16At(parser.snesToPc(records[orientation] + 2))
                val map = requireNotNull(maps.parseSpritemap(pointer))
                val tiles = requireNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, id))
                val palette = requireNotNull(EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, id)).colors
                val expected = requireNotNull(maps.renderSpritemap(map, tiles, palette))
                val actual = requireNotNull(EnemySpriteCache.getPreview(RomParser.EnemyEntry(id, 128, 128, orientation + 4, 0)))
                assertArrayEquals(expected.pixels, actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width))
                assertTrue(expected.pixels.any { it ushr 24 != 0 })
                if (id == 0xDCBF) ImageIO.write(actual.image, "png", File(TestRomHelper.outputDir(), "room-sova-$orientation.png"))
            }
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `out of room bosses are visually centered without changing population coordinates`() {
        val image = BufferedImage(80, 100, BufferedImage.TYPE_INT_ARGB)
        val preview = AnchoredEnemySprite(image, 15, 80)
        val enemy = RomParser.EnemyEntry(0xE17F, 0xFFFF, 0xFFFF, 0, 0)
        val (x, y) = roomEnemyDisplayPosition(enemy, 512, 256, preview)
        assertEquals(256, x - preview.originX + image.width / 2)
        assertEquals(128, y - preview.originY + image.height / 2)
        assertEquals(0xFFFF, enemy.x); assertEquals(0xFFFF, enemy.y)
        val ordinary = enemy.copy(id = 0xD93F)
        assertEquals(0xFFFF to 0xFFFF, roomEnemyDisplayPosition(ordinary, 512, 256, preview))
        val visible = enemy.copy(x = 200, y = 100)
        assertEquals(200 to 100, roomEnemyDisplayPosition(visible, 512, 256, preview))
    }

    @Test
    fun `companion slots are hidden only while their composed main actor is present`() {
        val head = RomParser.EnemyEntry(0xEC3F, 128, 128, 0, 0)
        val body = head.copy(id = 0xEC7F)
        assertTrue(0xEC7F in hiddenRoomEnemyCompanions(listOf(head, body)))
        assertFalse(0xEC3F in hiddenRoomEnemyCompanions(listOf(head, body)))
        assertFalse(0xEC7F in hiddenRoomEnemyCompanions(listOf(body)))
        assertTrue(0xE2FF in hiddenRoomEnemyCompanions(listOf(head.copy(id = 0xE2BF))))
    }

    @Test
    fun `Mother Brain phase two adds neither a second sprite nor an overlapping name`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            val main = RomParser.EnemyEntry(0xEC3F, 128, 128, 0, 0)
            fun render(entries: List<RomParser.EnemyEntry>, names: Boolean): IntArray {
                val data = RoomRenderData(256, 256, IntArray(256 * 256),
                    blocksWide = 16, blocksTall = 16, blockTypes = IntArray(256), btsData = ByteArray(256), enemyEntries = entries)
                val image = buildCompositeImage(data, activeOverlays = setOf(TileOverlay.ENEMIES), showGrid = false, showEnemyNames = names)
                return image.getRGB(0, 0, 256, 256, null, 0, 256)
            }
            val mainOnly = render(listOf(main), true)
            val both = render(listOf(main, main.copy(id = 0xEC7F)), true)
            assertArrayEquals(mainOnly, both)
            assertFalse(mainOnly.contentEquals(render(listOf(main), false)), "The main Mother Brain label is retained")
            assertTrue(both.any { it ushr 24 != 0 })
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `vanilla boss rooms produce visible portraits at their actual room bounds`() {
        val parser = TestRomHelper.requireRomParser()
        val seen = mutableSetOf<Int>()
        EnemySpriteCache.setRomParser(parser)
        try {
            for (info in RoomRepository().getAllRooms()) {
                val header = parser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
                for ((index, state) in parser.parseRoomStatesWithData(header.roomId).withIndex()) {
                    val enemies = parser.parseEnemyPopulation(state.enemySetPtr)
                    val bosses = enemies.filter { it.id in RoomBossPreview.SPECIES }
                    if (bosses.isEmpty()) continue
                    val room = header.copy(levelDataPtr = state.levelDataPtr, tileset = state.tileset,
                        enemySetPtr = state.enemySetPtr, enemyGfxPtr = state.enemyGfxPtr,
                        plmSetPtr = state.plmSetPtr, bgDataPtr = state.bgDataPtr, bgScrolling = state.bgScrolling)
                    val data = requireNotNull(MapRenderer(parser).renderRoom(room))
                    for (boss in bosses) {
                        seen += boss.id
                        val preview = requireNotNull(EnemySpriteCache.getPreview(boss))
                        val (x, y) = roomEnemyDisplayPosition(boss, data.width, data.height, preview)
                        assertTrue(x in 0 until data.width && y in 0 until data.height, "${room.roomId.toString(16)} ${boss.id.toString(16)}")
                        val left = x - preview.originX
                        val top = y - preview.originY
                        assertTrue(left < data.width && left + preview.image.width > 0)
                        assertTrue(top < data.height && top + preview.image.height > 0)
                    }
                    val image = buildCompositeImage(data, activeOverlays = setOf(TileOverlay.ENEMIES), showGrid = false)
                    ImageIO.write(image, "png", File(TestRomHelper.outputDir(), "boss-room-${room.roomId.toString(16)}-$index.png"))
                }
            }
            assertTrue(seen.containsAll(RoomBossPreview.SPECIES - 0xE13F), "Missing boss room coverage: ${(RoomBossPreview.SPECIES - seen).map { it.toString(16) }}")
        } finally { EnemySpriteCache.setRomParser(null) }
    }
}
