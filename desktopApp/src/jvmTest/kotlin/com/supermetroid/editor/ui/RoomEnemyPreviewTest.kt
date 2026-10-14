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
    fun `static boss placement adjustments never change population data`() {
        val preview = AnchoredEnemySprite(BufferedImage(56, 88, BufferedImage.TYPE_INT_ARGB), 28, 64)
        val enemy = RomParser.EnemyEntry(0xE2BF, 200, 200, 0, 0)
        assertEquals(200 to 248, roomEnemyDisplayPosition(enemy, 512, 512, preview))
        assertEquals(200 to 184, roomEnemyDisplayPosition(enemy.copy(id = 0xEEFF), 512, 512, preview))
        assertEquals(432 to 184, roomEnemyDisplayPosition(enemy.copy(id = 0xEF7F), 512, 512, preview))
        assertEquals(200, enemy.x); assertEquals(200, enemy.y)
    }

    @Test
    fun `empty initial Phantoon and Draygon states still get centered overlay portraits`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            for ((roomId, species) in listOf(0xCD13 to 0xE4BF, 0xDA60 to 0xDE3F)) {
                val room = requireNotNull(parser.readRoomHeader(roomId))
                val original = parser.parseEnemyPopulation(room.enemySetPtr)
                assertFalse(original.any { it.id == species }, "The initial state is the boss-dead state")
                val entries = roomEnemyPreviewEntries(original, roomId)
                val boss = entries.single { it.id == species }
                val preview = requireNotNull(EnemySpriteCache.getPreview(boss))
                val data = requireNotNull(MapRenderer(parser).renderRoom(room))
                val (x, y) = roomEnemyDisplayPosition(boss, data.width, data.height, preview)
                assertEquals(data.width / 2, x - preview.originX + preview.image.width / 2)
                assertEquals(data.height / 2, y - preview.originY + preview.image.height / 2)
                assertEquals(original, data.enemyEntries, "The real room population stays unchanged")
                assertEquals(entries, roomEnemyPreviewEntries(entries, roomId), "No duplicate portrait")
                val image = buildCompositeImage(data, setOf(TileOverlay.ENEMIES), false, roomId = roomId)
                val withoutPortrait = buildCompositeImage(data, setOf(TileOverlay.ENEMIES), false)
                assertFalse(image.getRGB(0, 0, data.width, data.height, null, 0, data.width)
                    .contentEquals(withoutPortrait.getRGB(0, 0, data.width, data.height, null, 0, data.width)), "The fallback must actually paint into the empty-state room")
                ImageIO.write(image, "png", File(TestRomHelper.outputDir(), "boss-initial-${roomId.toString(16)}.png"))
            }
            assertTrue(roomEnemyPreviewEntries(emptyList(), 0x91F8).isEmpty())
        } finally { EnemySpriteCache.setRomParser(null) }
    }

    @Test
    fun `Golden Four uses real projectile pieces and hides the leftover enemy slots`() {
        val parser = TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            val room = requireNotNull(parser.readRoomHeader(TourianStatueSprites.ROOM_ID))
            val data = requireNotNull(MapRenderer(parser).renderRoom(room))
            assertEquals(3, data.enemyEntries.count { it.id == TourianStatueSprites.SPECIES })
            val single = data.copy(enemyEntries = data.enemyEntries.filter { it.id != TourianStatueSprites.SPECIES || it.extra2 == 0 })
            fun render(value: RoomRenderData) = buildCompositeImage(value, setOf(TileOverlay.ENEMIES), false)
            val allImage = render(data)
            val singleImage = render(single)
            assertArrayEquals(singleImage.getRGB(0, 0, data.width, data.height, null, 0, data.width), allImage.getRGB(0, 0, data.width, data.height, null, 0, data.width))
            val background = MapRenderer(parser).also { it.renderRoom(room) }.renderLayer2(room)
            val image = buildCompositeImage(data, setOf(TileOverlay.ENEMIES, TileOverlay.LAYER2), false, layer2Pixels = background)
            ImageIO.write(image, "png", File(TestRomHelper.outputDir(), "golden-four-room.png"))
            val full = requireNotNull(TourianStatueSprites(parser).renderComplete())
            val visible = full.pixels.indices.filter { full.pixels[it] ushr 24 != 0 }
            assertEquals(4, visible.minOf { it % full.width })
            assertEquals(4, visible.minOf { it / full.width })
            assertEquals(full.width - 5, visible.maxOf { it % full.width })
            assertEquals(full.height - 5, visible.maxOf { it / full.width })
            assertTrue(full.width < 256 && full.height < 256, "Empty BG canvas must not shrink the assembled preview")
            val scene = BufferedImage(full.width, full.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, full.pixels, 0, width) }
            ImageIO.write(scene, "png", File(TestRomHelper.outputDir(), "golden-four-full.png"))
        } finally { EnemySpriteCache.setRomParser(null) }
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
                    val image = buildCompositeImage(data, activeOverlays = setOf(TileOverlay.ENEMIES), showGrid = false, roomId = room.roomId)
                    ImageIO.write(image, "png", File(TestRomHelper.outputDir(), "boss-room-${room.roomId.toString(16)}-$index.png"))
                }
            }
            assertTrue(seen.containsAll(RoomBossPreview.SPECIES - 0xE13F), "Missing boss room coverage: ${(RoomBossPreview.SPECIES - seen).map { it.toString(16) }}")
        } finally { EnemySpriteCache.setRomParser(null) }
    }
}
