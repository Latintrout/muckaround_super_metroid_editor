package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState

/**
 * Auto edges for one ground tile, learned from the game: in the rooms using the tileset, the solid blocks
 * near that tile (the same mass of terrain), and which tile (metatile + flips) the game uses most for each
 * pattern of open neighbours (bit 1 up, 2 right, 4 down, 8 left). Patterns seen fewer than [MIN_SAMPLES]
 * times are left out, so they keep the painted tile. The ground tile itself fills the inside.
 */
class MakerEdgeTable(val ground: Int, val byMask: Map<Int, Int>) {
    /** Tiles this table places: blocks made of these are re-tiled; any other detail is left alone. */
    val family: Set<Int> = byMask.values.map { it and 0x3FF }.toSet() + ground

    companion object {
        const val MIN_SAMPLES = 4
        /** How far (blocks) from a ground tile a solid block still counts as the same terrain. */
        private const val NEAR = 2
        private val cache = HashMap<Triple<Int, Int, Int>, MakerEdgeTable?>()

        /** The table for [ground] in [tileset], swept once per ROM, tileset and tile. Null when the game never uses it. */
        fun forGround(parser: RomParser, tileset: Int, ground: Int): MakerEdgeTable? = synchronized(cache) {
            cache.getOrPut(Triple(System.identityHashCode(parser), tileset, ground)) { sweep(parser, tileset, ground) }
        }

        private fun sweep(parser: RomParser, tileset: Int, ground: Int): MakerEdgeTable? {
            val counts = HashMap<Int, HashMap<Int, Int>>()
            for (info in parser.roomCatalog.rooms) {
                val room = parser.readRoomHeader(info.getRoomIdAsInt())?.takeIf { it.tileset == tileset } ?: continue
                val data = runCatching { parser.decompressLZ2(room.levelDataPtr) }.getOrNull() ?: continue
                val w = room.width * 16; val h = room.height * 16
                if (data.size < 2 + w * h * 2) continue
                fun word(x: Int, y: Int) = (data[2 + (y * w + x) * 2].toInt() and 0xFF) or ((data[3 + (y * w + x) * 2].toInt() and 0xFF) shl 8)
                fun solid(x: Int, y: Int) = x !in 0 until w || y !in 0 until h || (word(x, y) shr 12) and 0xF == 0x8
                // Blocks near the ground tile.
                val near = BooleanArray(w * h)
                var any = false
                for (y in 0 until h) for (x in 0 until w) if ((word(x, y) and 0x3FF) == ground && solid(x, y)) {
                    any = true
                    for (dy in -NEAR..NEAR) for (dx in -NEAR..NEAR) { val nx = x + dx; val ny = y + dy; if (nx in 0 until w && ny in 0 until h) near[ny * w + nx] = true }
                }
                if (!any) continue
                for (y in 0 until h) for (x in 0 until w) {
                    if (!near[y * w + x] || !solid(x, y)) continue
                    val mask = edgeMask { dx, dy -> !solid(x + dx, y + dy) }
                    if (mask == 0) continue // the inside is the ground tile itself
                    counts.getOrPut(mask) { HashMap() }.merge(word(x, y) and 0x0FFF, 1, Int::plus)
                }
            }
            if (counts.isEmpty()) return null
            val byMask = counts.mapNotNull { (mask, words) ->
                words.maxByOrNull { it.value }?.takeIf { it.value >= MIN_SAMPLES }?.let { mask to it.key }
            }.toMap()
            return MakerEdgeTable(ground, byMask)
        }

        /** Open-neighbour pattern: bit 1 up, 2 right, 4 down, 8 left. */
        inline fun edgeMask(open: (Int, Int) -> Boolean): Int =
            (if (open(0, -1)) 1 else 0) or (if (open(1, 0)) 2 else 0) or (if (open(0, 1)) 4 else 0) or (if (open(-1, 0)) 8 else 0)
    }
}

/**
 * Re-tiles the solid blocks in [area] (grown by one block) made of [table]'s tiles so edges and corners match
 * their open neighbours, and the inside is the ground tile. Returns the edits.
 */
fun EditorState.autoEdgeEdits(area: MakerBox, table: MakerEdgeTable): List<TileEdit> {
    val w = workingBlocksWide; val h = workingBlocksTall
    fun solid(x: Int, y: Int) = x !in 0 until w || y !in 0 until h || (readBlockWord(x, y) shr 12) and 0xF == 0x8
    val edits = mutableListOf<TileEdit>()
    for (y in (area.y0 - 1).coerceAtLeast(0)..(area.y1 + 1).coerceAtMost(h - 1)) {
        for (x in (area.x0 - 1).coerceAtLeast(0)..(area.x1 + 1).coerceAtMost(w - 1)) {
            val old = readBlockWord(x, y)
            if ((old shr 12) and 0xF != 0x8 || (old and 0x3FF) !in table.family) continue
            val mask = MakerEdgeTable.edgeMask { dx, dy -> !solid(x + dx, y + dy) }
            val pick = if (mask == 0) table.ground else table.byMask[mask] ?: table.ground
            val word = 0x8000 or pick
            if (word != old) edits += TileEdit(x, y, old, word, readBts(x, y), readBts(x, y))
        }
    }
    return edits
}
