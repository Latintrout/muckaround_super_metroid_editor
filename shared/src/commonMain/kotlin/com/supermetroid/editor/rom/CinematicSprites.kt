package com.supermetroid.editor.rom

/** Cinematic OAM objects have bank-$8B lists and bank-$8C maps. */
class CinematicSprites(private val parser: RomParser) {
    data class Sequence(val name: String, val list: Int, val graphics: Int, val palette: Int, val end: Int? = null, val paletteBase: Int = 0)
    companion object {
        val SEQUENCES = listOf(
            Sequence("Title · 1994", 0x8BA03D, 0x9580D8, 0x8CE1E9, 0x8BA051, 1),
            Sequence("Title · Nintendo", 0x8BA055, 0x9580D8, 0x8CE1E9, 0x8BA075, 1),
            Sequence("Title · Presents", 0x8BA079, 0x9580D8, 0x8CE1E9, 0x8BA099, 1),
            Sequence("Title · Metroid 3", 0x8BA09D, 0x9580D8, 0x8CE1E9, 0x8BA0C1, 1),
            Sequence("Intro · Mother Brain", 0x8BCB05, 0x95E4C2, 0x8CE3E9, paletteBase = 7),
            Sequence("Intro · Metroid egg", 0x8BCB33, 0x95E4C2, 0x8CE3E9, paletteBase = 7),
            Sequence("Intro · Egg hatching", 0x8BCB3B, 0x95E4C2, 0x8CE3E9, 0x8BCB91, 7),
            Sequence("Intro · Baby Metroid delivered", 0x8BCBB9, 0x95E4C2, 0x8CE3E9, paletteBase = 6),
            Sequence("Intro · Baby Metroid examined", 0x8BCBE7, 0x95E4C2, 0x8CE3E9, paletteBase = 6),
            Sequence("Ending · Samus jump", 0x8BED7F, 0x97B957, 0x8CE7E9, 0x8BED87, 5),
            Sequence("Ending · Samus landing", 0x8BED9D, 0x97D7FC, 0x8CE7E9, 0x8BEDA9, 6),
        )
    }

    fun render(sequence: Sequence): SpriteAnimation? = runCatching {
        val sourceTiles = parser.decompressLZ2(sequence.graphics)
        val tiles = if (sequence.graphics == 0x95E4C2) {
            // Intro loads common OBJ tiles at $6000, then overlays its payload at $6E00.
            ByteArray(0x4000).also {
                RomVisualAssets.raw(parser, 0x9AD200, 0x2000).copyInto(it)
                sourceTiles.copyInto(it, 0x1C00)
            }
        } else sourceTiles
        val colors = RomVisualAssets.palette(parser, sequence.palette)
        val palettes = (0..7).associateWith { colors.copyOfRange(128 + it * 16, 144 + it * 16) }
        val maps = EnemySpritemap(parser)
        var address = sequence.list
        var timer = 0
        var loop = false
        val seen = mutableSetOf<Pair<Int, Int>>()
        val frames = mutableListOf<Pair<Int, EnemySpritemap.AssembledSprite>>()
        for (operation in 0 until 1024) {
            if (sequence.end != null && address >= sequence.end) break
            if (!seen.add(address to timer)) { loop = true; break }
            val code = word(address)
            when {
                code in 1..0x7FFF -> {
                    val pointer = word(address + 2)
                    val map = if (pointer == 0) EnemySpritemap.Spritemap(emptyList(), 0) else maps.parseSpritemap(0x8C0000 or pointer) ?: return null
                    val coloredMap = map.copy(entries = map.entries.map { it.copy(palRow = it.palRow or sequence.paletteBase) })
                    val sprite = if (map.entries.isEmpty()) EnemySpritemap.AssembledSprite(1, 1, intArrayOf(0), 0, 0, map)
                    else maps.renderSpritemap(coloredMap, tiles, palettes.getValue(0), palettes, EnemySpritemap.OamTileNumberMode.LOW_9) ?: return null
                    frames += code to sprite
                    address += 4
                }
                code == 0x94BC -> address = 0x8B0000 or word(address + 2)
                code == 0x94D6 -> { timer = word(address + 2); address += 4 }
                code == 0x94C3 -> { timer--; address = if (timer != 0) 0x8B0000 or word(address + 2) else address + 4 }
                code == 0x9438 || code == 0x9442 -> break
                // This callback spawns a separate particle object; the egg's track continues.
                code == 0xA918 -> address += 2
                else -> return null
            }
        }
        if (frames.none { (_, sprite) -> sprite.pixels.any { it ushr 24 != 0 } }) return null
        anchoredSpriteAnimation(sequence.name, frames, loop)
    }.getOrNull()

    private fun word(address: Int) = parser.readUInt16At(parser.snesToPc(address))
}
