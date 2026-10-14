package com.supermetroid.editor.rom

/** Representative native room poses, not an emulation of boss AI or encounter movement. */
class RoomBossPreview(private val parser: RomParser) {
    companion object {
        val SPECIES = setOf(0xE2BF, 0xDDBF, 0xEEFF, 0xEF7F, 0xE13F, 0xE17F, 0xE4BF, 0xDE3F, 0xF293, 0xEC3F, 0xEC7F)
        val COMPANIONS = mapOf(
            0xE2BF to setOf(0xE2FF, 0xE33F, 0xE37F, 0xE3BF, 0xE3FF, 0xE43F, 0xE47F),
            0xDDBF to setOf(0xDDFF),
            0xE4BF to setOf(0xE4FF, 0xE53F, 0xE57F),
            0xDE3F to setOf(0xDE7F, 0xDEBF, 0xDEFF),
            0xEC3F to setOf(0xEC7F),
        )
    }

    fun render(speciesId: Int): EnemySpritemap.AssembledSprite? {
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, speciesId)
        val rendered = when (speciesId) {
            0xE2BF -> {
                val renderer = KraidSpritemap(parser)
                if (!renderer.load()) return null
                val sprite = renderer.renderCompleteBody(KraidSpritemap.HEAD_TILEMAPS.last()) ?: return null
                image(sprite.width, sprite.height, sprite.pixels)
            }
            0xDDBF -> {
                val renderer = CrocomireSpritemap(parser)
                if (!renderer.load(tiles)) return null
                renderer.renderComposition(CrocomireSpritemap.COMPOSITIONS.single { it.key == "mouth-open" })
            }
            0xEEFF, 0xEF7F -> {
                val renderer = TorizoSpritemap(parser)
                if (!renderer.load(tiles)) return null
                val golden = speciesId == 0xEF7F
                val palette = TorizoSpritemap.PALETTE_STAGES.single { it.key == if (golden) "gold-hp-full" else "bomb-normal" }
                // Preserve the source OAM anchor of the first walking pose.
                renderer.renderComponent(TorizoSpritemap.COMPONENTS.single { it.key == "walk-left-a" }, palette)
            }
            0xE13F, 0xE17F -> {
                val renderer = RidleySpritemap(parser)
                if (!renderer.load(tiles)) return null
                renderer.renderComposition(RidleySpritemap.COMPOSITIONS.single { it.key == "left-roar" })
            }
            0xE4BF -> {
                val renderer = PhantoonSpritemap(parser)
                if (!renderer.load()) return null
                val sprite = renderer.renderFullBody() ?: return null
                image(sprite.width, sprite.height, sprite.pixels)
            }
            0xDE3F -> {
                val renderer = DraygonSpritemap(parser)
                if (!renderer.load(tiles)) return null
                renderer.renderComposition(DraygonSpritemap.COMPOSITIONS.single { it.key == "left-idle" })
            }
            0xF293 -> {
                val renderer = BotwoonSpritemap(parser)
                if (!renderer.load(tiles)) return null
                renderer.renderComposition(BotwoonSpritemap.COMPOSITIONS.single { it.key == "turning" })
            }
            0xEC3F, 0xEC7F -> {
                val renderer = MotherBrainSpritemap(parser)
                if (!renderer.load()) return null
                val key = if (speciesId == 0xEC3F) "phase-1" else "phase-2"
                renderer.renderComposition(MotherBrainSpritemap.COMPOSITIONS.single { it.key == key })
            }
            else -> return null
        } ?: return null
        return trimTransparent(rendered)
    }

    private fun image(width: Int, height: Int, pixels: IntArray) =
        EnemySpritemap.AssembledSprite(width, height, pixels, width / 2, height / 2, EnemySpritemap.Spritemap(emptyList(), 0))

    private fun trimTransparent(sprite: EnemySpritemap.AssembledSprite): EnemySpritemap.AssembledSprite? {
        var left = sprite.width; var top = sprite.height; var right = -1; var bottom = -1
        for (y in 0 until sprite.height) for (x in 0 until sprite.width) if (sprite.pixels[y * sprite.width + x] ushr 24 != 0) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        if (right < left) return null
        val width = right - left + 1
        val height = bottom - top + 1
        val pixels = IntArray(width * height)
        for (y in 0 until height) sprite.pixels.copyInto(pixels, y * width, (top + y) * sprite.width + left, (top + y) * sprite.width + left + width)
        return sprite.copy(width = width, height = height, pixels = pixels, originX = sprite.originX - left, originY = sprite.originY - top)
    }
}
