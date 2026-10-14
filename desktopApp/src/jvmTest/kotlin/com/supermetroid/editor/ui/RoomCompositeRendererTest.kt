package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class RoomCompositeRendererTest {
    @Test
    fun `room ship components use source anchors and select entrance pad separately`() {
        val parser = com.supermetroid.editor.rom.TestRomHelper.requireRomParser()
        EnemySpriteCache.setRomParser(parser)
        try {
            val ship = com.supermetroid.editor.rom.ShipSpritemap(parser)
            for ((id, pad) in listOf(0xD07F to false, 0xD0BF to false, 0xD0BF to true)) {
                val enemy = RomParser.EnemyEntry(id, 128, 128, 0, 0, extra3 = if (pad) 1 else 0)
                val expected = requireNotNull(ship.renderPart(id, entrancePad = pad))
                val actual = requireNotNull(EnemySpriteCache.getPreview(enemy))
                assertEquals(expected.originX, actual.originX)
                assertEquals(expected.originY, actual.originY)
                assertTrue(expected.pixels.contentEquals(actual.image.pixels()))
            }
        } finally {
            EnemySpriteCache.setRomParser(null)
        }
    }


    @Test
    fun `ship has one name label without bottom or entrance pad labels`() {
        fun render(entries: List<RomParser.EnemyEntry>, names: Boolean) = buildCompositeImage(
            data = roomRenderData(enemyEntries = entries),
            activeOverlays = setOf(TileOverlay.ENEMIES),
            showGrid = false,
            showEnemyNames = names,
        ).pixels()

        val components = listOf(
            RomParser.EnemyEntry(0xD0BF, 128, 128, 0, 0),
            RomParser.EnemyEntry(0xD0BF, 128, 128, 0, 0, extra3 = 1),
        )
        assertTrue(
            render(components, true).contentEquals(render(components, false)),
            "Bottom and entrance pad must still render without adding their own labels",
        )
        val ship = listOf(RomParser.EnemyEntry(0xD07F, 128, 128, 0, 0)) + components
        assertEquals("Samus' Ship", RomParser.enemyName(0xD07F))
        assertFalse(
            render(ship, true).contentEquals(render(ship, false)),
            "The complete ship must retain its Samus' Ship label",
        )
    }

    @Test
    fun `door cap badges use their actual PLM color`() {
        val expected = mapOf(
            0xC842 to RomParser.DOOR_CAP_GREY,
            0xC85A to RomParser.DOOR_CAP_YELLOW,
            0xC872 to RomParser.DOOR_CAP_GREEN,
            0xC88A to RomParser.DOOR_CAP_RED,
            0xC8A2 to RomParser.DOOR_CAP_BLUE,
        )

        for ((plmId, argb) in expected) {
            assertEquals(argb, doorCapOverlayBorderColor(plmId)?.rgb, "PLM 0x${plmId.toString(16)}")
        }
        assertNull(doorCapOverlayBorderColor(0xB703), "A scroll PLM must not receive a door color")
    }

    @Test
    fun `meta names hide non-item map text`() {
        val data = roomRenderData(
            plmEntries = listOf(
                RomParser.PlmEntry(0xC884, 4, 3, 0), // Green Down Door
                RomParser.PlmEntry(0xB703, 8, 3, 0), // Scroll trigger
            ),
            enemyEntries = listOf(
                RomParser.EnemyEntry(0xE23F, 168, 56, 0, 0), // Ceres Door FX
            ),
        )
        val activeOverlays = setOf(TileOverlay.ITEMS, TileOverlay.SCROLL_PLMS, TileOverlay.ENEMIES, TileOverlay.SCROLLS)

        val withMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            scrollData = intArrayOf(2),
            roomWidthScreens = 1,
            roomHeightScreens = 1,
            showItemNames = false,
            showMetaNames = true,
            showEnemyNames = true,
        )
        val withoutMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            scrollData = intArrayOf(2),
            roomWidthScreens = 1,
            roomHeightScreens = 1,
            showItemNames = false,
            showMetaNames = false,
            showEnemyNames = true,
        )

        assertFalse(
            withMetaNames.pixels().contentEquals(withoutMetaNames.pixels()),
            "Meta labels should affect the rendered image when enabled",
        )
    }

    @Test
    fun `meta names do not hide regular item or enemy names`() {
        val data = roomRenderData(
            itemBlocks = setOf(2 * 16 + 2),
            plmEntries = listOf(RomParser.PlmEntry(0xEEDB, 2, 2, 0)), // Missile
            enemyEntries = listOf(RomParser.EnemyEntry(0xD93F, 128, 64, 0, 0)), // Sidehopper
        )
        val activeOverlays = setOf(TileOverlay.ITEMS, TileOverlay.ENEMIES)

        val withMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            showItemNames = true,
            showMetaNames = true,
            showEnemyNames = true,
        )
        val withoutMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            showItemNames = true,
            showMetaNames = false,
            showEnemyNames = true,
        )

        assertTrue(
            withMetaNames.pixels().contentEquals(withoutMetaNames.pixels()),
            "Regular item and enemy labels should remain controlled by their existing switches",
        )
    }

    @Test
    fun `screen coordinates render independently from the grid`() {
        val data = roomRenderData()
        val plain = buildCompositeImage(
            data = data,
            activeOverlays = emptySet(),
            showGrid = false,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )
        val gridOnly = buildCompositeImage(
            data = data,
            activeOverlays = emptySet(),
            showGrid = true,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )
        val coordinatesOnly = buildCompositeImage(
            data = data,
            activeOverlays = setOf(TileOverlay.SCREEN_COORDINATES),
            showGrid = false,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )

        assertFalse(plain.pixels().contentEquals(gridOnly.pixels()), "Grid should draw screen boundaries")
        assertFalse(
            plain.pixels().contentEquals(coordinatesOnly.pixels()),
            "Screen Coords should draw labels even when Grid is off",
        )
        assertFalse(
            gridOnly.pixels().contentEquals(coordinatesOnly.pixels()),
            "Grid and Screen Coords should remain separate display options",
        )
    }

    private fun roomRenderData(
        itemBlocks: Set<Int> = emptySet(),
        plmEntries: List<RomParser.PlmEntry> = emptyList(),
        enemyEntries: List<RomParser.EnemyEntry> = emptyList(),
    ): RoomRenderData {
        val width = 256
        val height = 256
        return RoomRenderData(
            width = width,
            height = height,
            pixels = IntArray(width * height) { 0xFF202020.toInt() },
            blocksWide = 16,
            blocksTall = 16,
            blockTypes = IntArray(16 * 16),
            btsData = ByteArray(16 * 16),
            itemBlocks = itemBlocks,
            plmEntries = plmEntries,
            enemyEntries = enemyEntries,
        )
    }

    private fun BufferedImage.pixels(): IntArray =
        IntArray(width * height).also { getRGB(0, 0, width, height, it, 0, width) }
}
