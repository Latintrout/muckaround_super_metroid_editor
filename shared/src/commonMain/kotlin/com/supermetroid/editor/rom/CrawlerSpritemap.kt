package com.supermetroid.editor.rom

/** Zeela, Zoomer, stone Zoomer and Sova normalize the population orientation as (value & 3) * 2. */
class CrawlerSpritemap(private val parser: RomParser) {
    companion object { val SPECIES = setOf(0xDC7F, 0xDCBF, 0xDCFF, 0xDD3F) }

    fun renderInitial(speciesId: Int, orientation: Int): EnemySpritemap.AssembledSprite? {
        if (speciesId !in SPECIES) return null
        val table = parser.snesToPc(0xA3E2CC + (orientation and 3) * 2)
        val list = 0xA30000 or parser.readUInt16At(table)
        // The four-byte FunctionInY record is followed by the first timed pose.
        val pointer = 0xA30000 or parser.readUInt16At(parser.snesToPc(list + 6))
        val maps = EnemySpritemap(parser)
        val map = maps.parseSpritemap(pointer) ?: return null
        val tiles = EnemySpriteGraphics.loadStandardOamRenderTileData(parser, speciesId) ?: return null
        val palette = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId)?.colors ?: return null
        return maps.renderSpritemap(map, tiles, palette)
    }
}
