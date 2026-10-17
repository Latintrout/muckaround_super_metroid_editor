package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.maker.MakerCatalog
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.selectEnemyGfxVramDestination
import com.supermetroid.editor.ui.EditorState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Maker library built from the ROM sweep ([MakerCatalog]) plus the in-game verification results
 * bundled at /maker-catalog/verified.json. A part only appears when its in-game check passed.
 */
class MakerLibrary(val catalog: MakerCatalog, private val verified: Verified) {
    data class Verdict(val ok: Boolean, val reason: String)
    data class Verified(
        val enemies: Map<Int, Verdict>,
        val items: Map<Int, Verdict>,
        val fx: Map<Int, Verdict> = emptyMap(),
        val tilesets: Map<Int, Verdict> = emptyMap(),
    )

    fun enemyVerdict(id: Int) = verified.enemies[id]
    fun itemVerdict(id: Int) = verified.items[id]
    fun fxVerdict(fxType: Int) = verified.fx[fxType]
    fun tilesetVerdict(tileset: Int) = verified.tilesets[tileset]

    companion object {
        fun loadVerified(text: String? = MakerLibrary::class.java.getResourceAsStream("/maker-catalog/verified.json")?.bufferedReader()?.use { it.readText() }): Verified {
            if (text == null) return Verified(emptyMap(), emptyMap())
            val root = Json.parseToJsonElement(text).jsonObject
            fun section(name: String) = (root[name] as? JsonObject).orEmpty().mapKeys { it.key.toInt(16) }.mapValues { (_, v) ->
                val o = v.jsonObject
                Verdict(o["ok"]?.jsonPrimitive?.boolean == true, o["reason"]?.jsonPrimitive?.content.orEmpty())
            }
            return Verified(section("enemies"), section("items"), section("fx"), section("tilesets"))
        }

        fun build(parser: RomParser) = MakerLibrary(MakerCatalog.build(parser), loadVerified())
    }

    // ── Tiles ──

    enum class TileKind(val label: String, val category: MakerCategory, val hint: String) {
        SOLID("Solid", MakerCategory.TERRAIN, "solid"),
        SLOPE("Slope", MakerCategory.TERRAIN, "slope"),
        CRUMBLE("Crumble", MakerCategory.TERRAIN, "falls away"),
        SPEED("Speed block", MakerCategory.TERRAIN, "Speed Booster breaks it"),
        SHOT("Shot block", MakerCategory.TERRAIN, "any shot breaks it"),
        SUPER("Super block", MakerCategory.TERRAIN, "Super Missile breaks it"),
        PB("Power Bomb block", MakerCategory.TERRAIN, "Power Bomb breaks it"),
        BOMB("Bomb block", MakerCategory.TERRAIN, "Bombs break it"),
        GRAPPLE("Grapple block", MakerCategory.TERRAIN, "Grapple latches"),
        SPIKES("Spikes", MakerCategory.HAZARDS, "hurts on touch"),
        HIDDEN_SPIKES("Spike air", MakerCategory.HAZARDS, "invisible, hurts"),
        DECOR("Scenery", MakerCategory.DECOR, "no collision"),
    }

    /** What a (word, bts) does in game, from the block type and BTS conventions the engine uses. */
    fun kindOf(tile: MakerCatalog.Tile): TileKind? = when (tile.blockType) {
        0x8 -> TileKind.SOLID
        0x1 -> TileKind.SLOPE
        0xA -> TileKind.SPIKES
        0x2 -> TileKind.HIDDEN_SPIKES
        0xB -> when (tile.bts) { 0x0E, 0x0F -> TileKind.SPEED; in 0x00..0x07 -> TileKind.CRUMBLE; else -> null }
        0xC -> when (tile.bts) { 0x08, 0x09 -> TileKind.PB; 0x0A, 0x0B -> TileKind.SUPER; in 0x00..0x07 -> TileKind.SHOT; else -> null }
        0xF -> TileKind.BOMB
        0xE -> TileKind.GRAPPLE
        0x0 -> TileKind.DECOR
        else -> null // doors, extension blocks and special air are structural, not paintable parts
    }

    private val perKindLimit = mapOf(
        TileKind.SOLID to 16, TileKind.SLOPE to 16, TileKind.DECOR to 32, TileKind.SPIKES to 6, TileKind.HIDDEN_SPIKES to 2,
    )

    /** Enough opaque, not-near-black pixels that the tile reads as something on a card (not a blank or shadow tile). */
    private fun isVisibleArt(px: IntArray): Boolean = px.count { c ->
        c ushr 24 != 0 && ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 > 48
    } >= 20

    fun tileParts(tileset: Int, category: MakerCategory, editorState: EditorState): List<MakerPart.Tile> {
        val tiles = catalog.tiles[tileset].orEmpty()
        val tg = editorState.tileGraphics
        val out = mutableListOf<MakerPart.Tile>()
        for (kind in TileKind.entries.filter { it.category == category }) {
            val picks = tiles.asSequence()
                .filter { kindOf(it) == kind }
                // Scenery must actually draw something; blank air is not a part.
                .filter { kind != TileKind.DECOR || tg?.renderMetatile(it.metatile)?.let(::isVisibleArt) == true }
                .distinctBy { it.word to it.bts }
                .take(perKindLimit[kind] ?: 4)
                .toList()
            picks.forEachIndexed { i, t ->
                val name = if (picks.size == 1) kind.label else "${kind.label} ${i + 1}"
                out += MakerPart.Tile(
                    id = "tile-%X-%04X-%02X".format(tileset, t.word, t.bts), name = name,
                    hint = "${kind.hint} · used ×${t.count}", category = category,
                    metatile = t.metatile, blockType = t.blockType, bts = t.bts, hFlip = t.hFlip, vFlip = t.vFlip,
                )
            }
        }
        return out
    }

    // ── Items ──

    fun itemParts(): List<MakerPart.Item> = RomParser.ITEM_DEFS.flatMap { def ->
        listOf(Triple(def.visibleId, "", "pickup"), Triple(def.chozoId, " (Chozo orb)", "in a Chozo orb"), Triple(def.hiddenId, " (hidden)", "hidden in a block"))
            .filter { (id, _, _) -> itemVerdict(id)?.ok == true }
            .map { (id, suffix, how) ->
                MakerPart.Item("item-%04X".format(id), def.name + suffix, "${ITEM_HINTS[def.name] ?: "upgrade"} · $how", id)
            }
    }

    // ── Enemies ──

    /** Whether [speciesId] can be placed in the loaded room, given its enemy graphics set (4 species max). */
    fun enemyAvailability(speciesId: Int, editorState: EditorState, parser: RomParser): String? {
        val header = parser.readRoomHeader(editorState.currentRoomId) ?: return "room not loaded"
        val set = runCatching { parser.parseEnemyGfxSet(header.enemyGfxPtr) }.getOrDefault(emptyList())
        if (set.any { it.speciesId == speciesId }) return null
        val vanilla = runCatching { parser.parseEnemyPopulation(header.enemySetPtr) }.getOrDefault(emptyList()).map { it.id }.toSet()
        // Species already added in this project also take a graphics slot.
        val added = editorState.workingEnemies.map { it.id }.filter { id -> id !in vanilla && set.none { it.speciesId == id } }.distinct()
        if (speciesId in added) return null
        if (set.size + added.size >= 4) return "this room already loads 4 enemy types"
        val dest = selectEnemyGfxVramDestination(set, catalog.enemies.firstOrNull { it.speciesId == speciesId }?.vramDestinations.orEmpty())
            ?: return "no free enemy graphics slot in this room"
        return if (dest < 0) "no free enemy graphics slot in this room" else null
    }

    fun enemyParts(editorState: EditorState, parser: RomParser): List<MakerPart.Enemy> = catalog.enemies
        .filter { enemyVerdict(it.speciesId)?.ok == true }
        .map { e ->
            val header = parser.readRoomHeader(editorState.currentRoomId)
            val loaded = header != null && runCatching { parser.parseEnemyGfxSet(header.enemyGfxPtr) }.getOrDefault(emptyList()).any { it.speciesId == e.speciesId }
            val blocked = enemyAvailability(e.speciesId, editorState, parser)
            val hint = when {
                blocked != null -> blocked
                loaded -> "loaded in this room"
                else -> "adds its graphics to this room"
            }
            MakerPart.Enemy("enemy-%04X".format(e.speciesId), e.name, hint, e.speciesId, e.initParam, e.properties, blocked)
        }
        .sortedWith(compareBy({ it.blocked != null }, { it.name }))
}

internal val ITEM_HINTS = mapOf(
    "Energy Tank" to "+100 energy", "Missile" to "+5 Missiles", "Super Missile" to "+5 Supers", "Power Bomb" to "+5 Power Bombs",
    "Reserve Tank" to "+100 reserve",
)
