package com.supermetroid.editor.rom

/** Gunship parts, shared OBJ graphics and landed positions from bank $A2. */
class ShipSpritemap(private val parser: RomParser) {
    companion object {
        const val TOP_SPECIES = 0xD07F
        const val BOTTOM_SPECIES = 0xD0BF
        const val TOP_MAP = 0xA2AD81
        const val BOTTOM_MAP = 0xA2ADDD
        const val CLOSED_PAD_MAP = 0xA2AFDD
        const val OPENING_LIST = 0xA2A5BE
        const val CLOSING_LIST = 0xA2A5EE
        const val TOP_Y = -25
        const val BOTTOM_Y = 15
        const val PAD_Y = TOP_Y - 1
    }

    private val maps = EnemySpritemap(parser)

    fun renderPart(
        speciesId: Int,
        entrancePad: Boolean = false,
        tileData: ByteArray? = null,
        palette: IntArray? = null,
    ): EnemySpritemap.AssembledSprite? {
        val (address, y) = when {
            speciesId == TOP_SPECIES -> TOP_MAP to TOP_Y
            speciesId == BOTTOM_SPECIES && entrancePad -> CLOSED_PAD_MAP to PAD_Y
            speciesId == BOTTOM_SPECIES -> BOTTOM_MAP to BOTTOM_Y
            else -> return null
        }
        val map = shiftedMap(address, y) ?: return null
        return render(map, tileData, palette)
    }

    fun renderLanded(
        padAddress: Int = CLOSED_PAD_MAP,
        tileData: ByteArray? = null,
        palette: IntArray? = null,
    ): EnemySpritemap.AssembledSprite? {
        // Earlier OAM slots win overlaps; renderSpritemap preserves this order.
        val parts = listOf(TOP_MAP to TOP_Y, BOTTOM_MAP to BOTTOM_Y, padAddress to PAD_Y)
        val entries = parts.flatMap { (address, y) -> shiftedMap(address, y)?.entries ?: return null }
        return render(EnemySpritemap.Spritemap(entries, TOP_MAP), tileData, palette)
    }

    fun animations(tileData: ByteArray? = null, palette: IntArray? = null): List<SpriteAnimation> = buildList {
        renderLanded(tileData = tileData, palette = palette)?.let { sprite ->
            add(SpriteAnimation("Landed", listOf(sprite.frame(1, "Landed")), loop = false))
        }
        for ((name, list) in listOf("Entrance pad opening" to OPENING_LIST, "Entrance pad closing" to CLOSING_LIST)) {
            val trace = maps.traceInstructionListAt(list)
            val frames = trace.frames.map { frame ->
                val sprite = renderLanded(frame.spritemap.snesAddress, tileData, palette) ?: return@buildList
                sprite.frame(frame.duration, name)
            }
            if (frames.isNotEmpty()) add(SpriteAnimation(name, frames, loop = false))
        }
    }

    private fun shiftedMap(address: Int, y: Int): EnemySpritemap.Spritemap? =
        maps.parseSpritemap(address)?.let { map ->
            map.copy(entries = map.entries.map { it.copy(yOffset = it.yOffset + y) })
        }

    private fun render(
        map: EnemySpritemap.Spritemap,
        tileData: ByteArray?,
        palette: IntArray?,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = tileData ?: EnemySpriteGraphics.loadEnemyTileData(parser, TOP_SPECIES) ?: return null
        val colors = palette ?: EnemySpriteGraphics.readEnemyPalette(parser, TOP_SPECIES) ?: return null
        return maps.renderSpritemap(map, tiles, colors)?.takeIf { it.pixels.any { pixel -> pixel ushr 24 != 0 } }
    }

    private fun EnemySpritemap.AssembledSprite.frame(ticks: Int, name: String) =
        SpriteAnimationFrame(pixels, width, height, ticks, name)
}
