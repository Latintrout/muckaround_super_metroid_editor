package com.supermetroid.editor.rom

/** Population-facing previews use the initial lists selected by the three bank-$B2 init AIs. */
class PirateSpritemap(private val parser: RomParser) {
    companion object {
        fun supports(speciesId: Int): Boolean = speciesId in 0xF353..0xF793 && (speciesId - 0xF353) % 0x40 == 0
    }

    fun renderInitial(speciesId: Int, init0: Int): EnemySpritemap.AssembledSprite? {
        if (!supports(speciesId)) return null
        val alternate = init0 and 1 != 0
        // First timed records after the source initialization handlers, not a guessed default map.
        val record = when {
            speciesId <= 0xF493 -> if (alternate) 0xB2EDB4 else 0xB2ED3E
            speciesId <= 0xF613 -> if (alternate) 0xB2F4D0 else 0xB2F2DE
            else -> if (alternate) 0xB2FBEA else 0xB2FB68
        }
        val pc = parser.snesToPc(record)
        if (parser.readUInt16At(pc) !in 1..0x7FFF) return null
        val pointer = 0xB20000 or parser.readUInt16At(pc + 2)
        val maps = EnemySpritemap(parser)
        val frame = maps.parseRenderableFrame(pointer) ?: return null
        val tiles = EnemySpriteGraphics.loadStandardOamRenderTileData(parser, speciesId) ?: return null
        val palette = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId)?.colors ?: return null
        return maps.renderSpritemap(maps.flattenRenderableFrame(frame), tiles, palette)
            ?.takeIf { it.pixels.any { pixel -> pixel ushr 24 != 0 } }
    }
}
