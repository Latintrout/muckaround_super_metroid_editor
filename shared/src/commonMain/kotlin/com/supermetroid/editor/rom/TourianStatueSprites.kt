package com.supermetroid.editor.rom

/** G4 mixes room BG tiles with three enemy projectiles; the enemy instruction maps are unused. */
class TourianStatueSprites(private val parser: RomParser) {
    companion object {
        const val SPECIES = 0xEFFF
        const val ROOM_ID = 0xA66A
        const val TILES = 0x87B364
        const val TILE_BYTES = 0x1600
        const val PALETTE = 0xAAD745
        const val ORIGIN_X = 0x78
        const val ORIGIN_Y = 0xB8
        data class Part(val name: String, val map: Int, val x: Int, val y: Int, val palette: Int)
        val PARTS = listOf(
            Part("Ridley statue", 0x8D9192, 0x8E, 0x55, 0xAAD745),
            Part("Phantoon statue", 0x8D9207, 0x84, 0x88, 0xAAD765),
            Part("Base decoration", 0x8D916D, 0x78, 0xB8, 0xAAD785),
        )
    }

    fun renderObjects(part: Part? = null): EnemySpritemap.AssembledSprite? {
        val maps = EnemySpritemap(parser)
        val rows = mapOf(1 to RomVisualAssets.palette(parser, 0xAAD745, 16),
            2 to RomVisualAssets.palette(parser, 0xAAD765, 16), 7 to RomVisualAssets.palette(parser, 0xAAD785, 16))
        val parts = if (part == null) PARTS else listOf(part)
        val entries = parts.flatMap { definition ->
            val map = maps.parseSpritemap(definition.map) ?: return null
            map.entries.map { entry ->
                if (part != null) entry else entry.copy(xOffset = entry.xOffset + definition.x - ORIGIN_X,
                    yOffset = entry.yOffset + definition.y - ORIGIN_Y)
            }
        }
        return maps.renderSpritemap(EnemySpritemap.Spritemap(entries, parts.first().map),
            RomVisualAssets.raw(parser, TILES, TILE_BYTES), rows.getValue(1), rows)
    }

    /** Include the BG Kraid/Draygon sculpture, without importing room terrain into the sprite sheet. */
    fun renderComplete(): SpriteAnimationFrame? {
        val room = parser.readRoomHeader(ROOM_ID) ?: return null
        val graphics = TileGraphics(parser)
        if (!graphics.loadTileset(room.tileset)) return null
        val layout = parser.readBgTilemapLayout(room.bgDataPtr) ?: return null
        val (pixels, width, height) = graphics.renderBgTilemap(layout.words, columns = layout.widthTiles) ?: return null
        val objects = renderObjects() ?: return null
        for (y in 0 until objects.height) for (x in 0 until objects.width) {
            val px = ORIGIN_X - objects.originX + x
            val py = ORIGIN_Y - objects.originY + y
            val color = objects.pixels[y * objects.width + x]
            if (px in 0 until width && py in 0 until height && color ushr 24 != 0) pixels[py * width + px] = color
        }
        return SpriteAnimationFrame(pixels, width, height, label = "Golden Four statue")
    }
}
