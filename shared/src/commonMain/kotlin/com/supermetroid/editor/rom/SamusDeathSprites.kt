package com.supermetroid.editor.rom

/** Separate death OBJ transfers and palette tracks from $9B:B7BF/$B823 and $92:EDBE. */
class SamusDeathSprites(private val parser: RomParser) {
    fun render(suit: Int = 0, facingLeft: Boolean = false): SpriteAnimation? = runCatching {
        require(suit in 0..2)
        val tiles = RomVisualAssets.raw(parser, 0x9B8000, 0x1400)
        val maps = EnemySpritemap(parser)
        val frames = (0 until 9).map { index ->
            val stage = parser.readByteAt(parser.snesToPc(0x9BB824 + index * 2))
            val suitPalette = word(0x9BB7D3 + suit * 20 + stage * 2)
            val suitlessPalette = word(0x9BB80F + stage * 2)
            val palettes = mapOf(
                4 to RomVisualAssets.palette(parser, 0x9B0000 or suitPalette, 16),
                7 to RomVisualAssets.palette(parser, 0x9B0000 or suitlessPalette, 16),
            )
            val mapIndex = (if (facingLeft) 0x825 else 0x81C) + index
            val map = maps.parseSpritemap(0x920000 or word(0x92808D + mapIndex * 2)) ?: return null
            val sprite = maps.renderSpritemap(map, tiles, palettes.getValue(4), palettes) ?: return null
            val ticks = parser.readByteAt(parser.snesToPc(0x9BB823 + index * 2))
            ticks to sprite
        }
        anchoredSpriteAnimation("Samus suit explosion · ${listOf("Power", "Varia", "Gravity")[suit]} · ${if (facingLeft) "left" else "right"}", frames, loop = false)
    }.getOrNull()

    private fun word(address: Int) = parser.readUInt16At(parser.snesToPc(address))
}
