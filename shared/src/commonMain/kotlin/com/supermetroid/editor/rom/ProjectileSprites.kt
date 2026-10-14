package com.supermetroid.editor.rom

/** Bank-$93 projectile lists use eight-byte frames, unlike enemy instruction lists. */
class ProjectileSprites(private val parser: RomParser) {
    data class Weapon(val name: String, val beam: Int? = null, val charged: Boolean = false, val nonBeam: Int? = null)

    companion object {
        val DIRECTIONS = listOf("Up", "Up-right", "Right", "Down-right", "Down", "Down (left)", "Down-left", "Left", "Up-left", "Up (left)")
        val BEAMS = listOf("Power", "Wave", "Ice", "Ice + Wave", "Spazer", "Spazer + Wave", "Spazer + Ice", "Spazer + Ice + Wave", "Plasma", "Plasma + Wave", "Plasma + Ice", "Plasma + Ice + Wave")
        val WEAPONS = buildList {
            for (charged in listOf(false, true)) BEAMS.forEachIndexed { mask, name ->
                add(Weapon(if (charged) "Charged $name" else name, mask, charged))
            }
            add(Weapon("Missile", nonBeam = 0))
            add(Weapon("Super Missile", nonBeam = 2))
            add(Weapon("Bomb", nonBeam = 5))
            add(Weapon("Beam impact", nonBeam = 7))
            add(Weapon("Missile impact", nonBeam = 8))
        }
    }

    fun render(weapon: Weapon, direction: Int = 2): SpriteAnimation? = runCatching {
        require(direction in DIRECTIONS.indices)
        val beam = weapon.beam
        val table = when {
            beam != null -> (if (weapon.charged) 0x9383D9 else 0x9383C1) + beam * 2
            weapon.nonBeam != null -> 0x9383F1 + weapon.nonBeam * 2
            else -> return null
        }
        val definition = 0x930000 or word(table)
        val directional = beam != null || weapon.nonBeam in listOf(0, 2)
        var address = 0x930000 or word(definition + 2 + if (directional) direction * 2 else 0)
        val tiles = RomVisualAssets.raw(parser, 0x9AD200, 0x2000)
        val colors = RomVisualAssets.palette(parser, 0x9A8100, 128)
        val rows = (0..7).associateWith { colors.copyOfRange(it * 16, (it + 1) * 16) }.toMutableMap()
        if (beam != null) {
            val source = 0x9A0000 or word(0x90C3B1 + beam * 2)
            // Update_Beam_Tiles_and_Palette transfers $100 bytes to VRAM $6300.
            RomVisualAssets.raw(parser, source, 0x100).copyInto(tiles, 0x600)
            rows[6] = RomVisualAssets.palette(parser, 0x900000 or word(0x90C3C9 + beam * 2), 16)
        }
        val maps = EnemySpritemap(parser)
        val seen = mutableSetOf<Int>()
        val rendered = mutableListOf<Pair<Int, EnemySpritemap.AssembledSprite>>()
        var loop = false
        for (operation in 0 until 512) {
            if (!seen.add(address)) { loop = true; break }
            val ticks = word(address)
            when (ticks) {
                0x8239 -> address = 0x930000 or word(address + 2)
                0x822F -> break
                in 1..0x7FFF -> {
                    val map = maps.parseSpritemap(0x930000 or word(address + 2)) ?: return null
                    val sprite = if (map.entries.isEmpty()) {
                        EnemySpritemap.AssembledSprite(1, 1, intArrayOf(0), 0, 0, map)
                    } else maps.renderSpritemap(map, tiles, rows.getValue(6), rows) ?: return null
                    rendered += ticks to sprite
                    address += 8
                }
                else -> return null
            }
        }
        if (rendered.none { (_, sprite) -> sprite.pixels.any { it ushr 24 != 0 } }) return null
        anchoredSpriteAnimation("${weapon.name} · ${DIRECTIONS[direction]}", rendered, loop)
    }.getOrNull()

    private fun word(address: Int): Int = parser.readUInt16At(parser.snesToPc(address))
}
