package com.supermetroid.editor.maker

import com.supermetroid.editor.rom.RomParser

/**
 * A sweep of everything the vanilla game places, built from the ROM (every room and every state):
 * enemy species, item and object PLMs, collision tiles per tileset, and FX. The Maker library is
 * filled from this, so parts come from what the game really uses rather than a hand-written list.
 */
class MakerCatalog(
    val enemies: List<Enemy>,
    val plms: List<Plm>,
    val tiles: Map<Int, List<Tile>>,
    val fx: List<Fx>,
    val roomsByTileset: Map<Int, List<Int>>,
) {
    data class Enemy(
        val speciesId: Int,
        val name: String,
        val rooms: List<Int>,
        /** Most common (initParam, properties) pair used in vanilla placements. */
        val initParam: Int,
        val properties: Int,
        /** Enemy-GFX VRAM destinations this species uses in vanilla rooms. */
        val vramDestinations: List<Int>,
    )

    data class Plm(val plmId: Int, val name: String, val rooms: List<Int>, val count: Int)

    /** A block as the game stores it: [word] (type, flips, metatile) plus [bts]. */
    data class Tile(val word: Int, val bts: Int, val count: Int, val rooms: Int) {
        val blockType: Int get() = (word shr 12) and 0xF
        val metatile: Int get() = word and 0x3FF
        val hFlip: Boolean get() = word and 0x400 != 0
        val vFlip: Boolean get() = word and 0x800 != 0
    }

    data class Fx(val fxType: Int, val name: String, val sample: RomParser.FxEntry, val rooms: List<Int>)

    companion object {
        fun build(parser: RomParser): MakerCatalog {
            data class EnemyAcc(val rooms: MutableSet<Int> = linkedSetOf(), val placements: MutableMap<Pair<Int, Int>, Int> = mutableMapOf(), val vram: MutableSet<Int> = linkedSetOf())
            val enemies = linkedMapOf<Int, EnemyAcc>()
            val plmRooms = linkedMapOf<Int, MutableSet<Int>>()
            val plmCounts = mutableMapOf<Int, Int>()
            val tileCounts = mutableMapOf<Int, MutableMap<Pair<Int, Int>, Int>>()
            val tileRooms = mutableMapOf<Int, MutableMap<Pair<Int, Int>, MutableSet<Int>>>()
            val fxRooms = linkedMapOf<Int, Pair<RomParser.FxEntry, MutableSet<Int>>>()
            val roomsByTileset = sortedMapOf<Int, MutableSet<Int>>()
            val seenLevels = mutableSetOf<Pair<Int, Int>>()

            for (info in parser.roomCatalog.rooms) {
                val roomId = info.getRoomIdAsInt()
                val states = runCatching { parser.parseRoomStatesWithData(roomId) }.getOrNull() ?: continue
                for (state in states) {
                    roomsByTileset.getOrPut(state.tileset) { linkedSetOf() }.add(roomId)
                    runCatching { parser.parseEnemyPopulation(state.enemySetPtr) }.getOrDefault(emptyList()).forEach { e ->
                        val acc = enemies.getOrPut(e.id) { EnemyAcc() }
                        acc.rooms += roomId
                        acc.placements.merge(e.initParam to e.properties, 1, Int::plus)
                    }
                    runCatching { parser.parseEnemyGfxSet(state.enemyGfxPtr) }.getOrDefault(emptyList()).forEach { g ->
                        enemies.getOrPut(g.speciesId) { EnemyAcc() }.vram += g.paletteIndex
                    }
                    runCatching { parser.parsePlmSet(state.plmSetPtr) }.getOrDefault(emptyList()).forEach { p ->
                        plmRooms.getOrPut(p.id) { linkedSetOf() } += roomId
                        plmCounts.merge(p.id, 1, Int::plus)
                    }
                    if (state.fxPtr != 0 && state.fxPtr != 0xFFFF) {
                        runCatching { parser.parseFxEntries(state.fxPtr) }.getOrDefault(emptyList()).forEach { fx ->
                            if (fx.fxType != 0) fxRooms.getOrPut(fx.fxType) { fx to linkedSetOf() }.second += roomId
                        }
                    }
                    // Collision tiles per tileset, counted once per distinct level data.
                    if (!seenLevels.add(state.tileset to state.levelDataPtr)) continue
                    val level = runCatching { parser.decompressLZ2(state.levelDataPtr) }.getOrNull() ?: continue
                    if (level.size < 2) continue
                    val layer1 = (level[0].toInt() and 0xFF) or ((level[1].toInt() and 0xFF) shl 8)
                    val blocks = layer1 / 2
                    if (2 + layer1 + blocks > level.size) continue
                    val counts = tileCounts.getOrPut(state.tileset) { mutableMapOf() }
                    val rooms = tileRooms.getOrPut(state.tileset) { mutableMapOf() }
                    for (i in 0 until blocks) {
                        val word = (level[2 + 2 * i].toInt() and 0xFF) or ((level[3 + 2 * i].toInt() and 0xFF) shl 8)
                        val bts = level[2 + layer1 + i].toInt() and 0xFF
                        val key = word to bts
                        counts.merge(key, 1, Int::plus)
                        rooms.getOrPut(key) { mutableSetOf() } += roomId
                    }
                }
            }

            val enemyList = enemies.map { (id, acc) ->
                val common = acc.placements.maxByOrNull { it.value }?.key ?: (0 to 0x2800)
                Enemy(id, RomParser.enemyName(id), acc.rooms.toList(), common.first, common.second, acc.vram.toList())
            }.filter { it.rooms.isNotEmpty() }.sortedBy { it.name }
            val plmList = plmRooms.map { (id, rooms) -> Plm(id, RomParser.plmDisplayName(id), rooms.toList(), plmCounts[id] ?: 0) }
                .sortedBy { it.name }
            val tiles = tileCounts.mapValues { (tileset, counts) ->
                counts.map { (key, n) -> Tile(key.first, key.second, n, tileRooms[tileset]?.get(key)?.size ?: 0) }.sortedByDescending { it.count }
            }
            val fx = fxRooms.map { (type, v) -> Fx(type, v.first.fxTypeName, v.first, v.second.toList()) }.sortedBy { it.fxType }
            return MakerCatalog(enemyList, plmList, tiles, fx, roomsByTileset.mapValues { it.value.toList() })
        }
    }
}
