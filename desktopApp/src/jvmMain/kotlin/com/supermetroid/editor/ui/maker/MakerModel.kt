package com.supermetroid.editor.ui.maker

import androidx.compose.ui.graphics.Color
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EditorTool
import com.supermetroid.editor.ui.theme.MakerFixedColors

/** The Maker's tools. Each has its own silhouette, label and one-key shortcut. */
enum class MakerTool(val label: String, val key: Char, val icon: String, val editorTool: EditorTool, val group: Int) {
    SELECT("Select", 'V', MakerIcons.SELECT, EditorTool.SELECT, 0),
    MOVE("Move", 'H', MakerIcons.MOVE, EditorTool.SELECT, 0),
    PAINT("Paint", 'B', MakerIcons.PAINT, EditorTool.PAINT, 1),
    BOX("Box", 'R', MakerIcons.BOX, EditorTool.SELECT, 1),
    FILL("Fill", 'G', MakerIcons.FILL, EditorTool.FILL, 1),
    ERASE("Erase", 'E', MakerIcons.ERASE, EditorTool.ERASE, 2),
    PICK("Pick", 'I', MakerIcons.PICK, EditorTool.SAMPLE, 2);

    companion object {
        fun forKey(c: Char): MakerTool? = entries.firstOrNull { it.key.equals(c, ignoreCase = true) }
    }
}

/** Where each tool's icon "points" (24-unit icon space), used as its cursor hotspot. */
private val TOOL_HOTSPOTS = mapOf(
    MakerTool.MOVE to (12 to 12),  // centre of the arrows
    MakerTool.PAINT to (3 to 21),  // brush tip
    MakerTool.BOX to (4 to 6),     // top-left corner
    MakerTool.FILL to (18 to 19),  // the drip
    MakerTool.ERASE to (5 to 18),  // eraser edge
    MakerTool.PICK to (4 to 18),   // dropper tip
)

/**
 * Mouse cursor over the room: the tool's own toolbar icon. Select keeps the system arrow;
 * Paint with "Any room tile" copies a tile, so it shows Pick.
 */
fun makerCursor(tool: MakerTool, part: MakerPart?): androidx.compose.ui.input.pointer.PointerIcon {
    val shown = if (tool == MakerTool.PAINT && part is MakerPart.AnyTile) MakerTool.PICK else tool
    val (hx, hy) = TOOL_HOTSPOTS[shown] ?: return androidx.compose.ui.input.pointer.PointerIcon.Default
    return com.supermetroid.editor.ui.PixelEditorCursors.fromLineIcon(shown.icon, java.awt.Point(hx, hy), "maker-${shown.name.lowercase()}")
}

/** Part categories: fixed colour + marker shape + label, identical in every theme. */
enum class MakerCategory(val label: String, val icon: String, val color: Color, val emptyHint: String) {
    TERRAIN("Terrain", MakerIcons.TERRAIN, MakerFixedColors.terrain, "No ground tile found in this room."),
    HAZARDS("Hazards", MakerIcons.HAZARDS, MakerFixedColors.hazards, ""),
    DOORS("Doors", MakerIcons.DOORS, MakerFixedColors.doors, "Door patterns load with the project."),
    ITEMS("Items", MakerIcons.ITEMS, MakerFixedColors.items, ""),
    ENEMIES("Enemies", MakerIcons.ENEMIES, MakerFixedColors.enemies, "No verified enemies yet."),
    DECOR("Decor", MakerIcons.DECOR, MakerFixedColors.decor, ""),
    STATIONS("Stations", MakerIcons.STATIONS, MakerFixedColors.stations, "Station patterns load with the project."),
}

/** Something that can be placed. [hint] says what it does in-game, in a few words. */
sealed class MakerPart(val id: String, val name: String, val hint: String, val category: MakerCategory) {
    /**
     * Paint one block. With [blockType] set it is painted exactly as the game uses it (type, BTS, flips);
     * without, collision comes from the tileset defaults.
     */
    class Tile(
        id: String, name: String, hint: String, category: MakerCategory, val metatile: Int,
        val blockType: Int? = null, val bts: Int = 0, val hFlip: Boolean = false, val vFlip: Boolean = false,
    ) : MakerPart(id, name, hint, category)
    class Pattern(id: String, name: String, hint: String, category: MakerCategory, val patternId: String) : MakerPart(id, name, hint, category)
    class Item(id: String, name: String, hint: String, val plmId: Int) : MakerPart(id, name, hint, MakerCategory.ITEMS)
    class Enemy(
        id: String, name: String, hint: String, val enemyId: Int,
        val initParam: Int = 0, val properties: Int = 0x2800,
        /** Why it can't be placed in this room (graphics limits), or null when it can. */
        val blocked: String? = null,
    ) : MakerPart(id, name, hint, MakerCategory.ENEMIES)
    /** Where Samus starts in Play. One per room: placing it again moves it. */
    object StartPoint : MakerPart("start-point", "Start point", "Samus starts here in Play", MakerCategory.STATIONS)
    /** Copy any tile from the room (switches to Pick). */
    object AnyTile : MakerPart("decor-any", "Any room tile", "Pick it from the room", MakerCategory.DECOR)

    val stamps: Boolean get() = this is Item || this is Enemy || this is StartPoint
}

/** Common breakable/special metatiles shared by every tileset (TilesetDefaults). */
private val SHARED_TERRAIN = listOf(
    Triple("crumble", "Crumble", 188) to "falls away",
    Triple("bomb", "Bomb block", 88) to "Bombs break it",
    Triple("shot", "Shot block", 82) to "any shot breaks it",
    Triple("grapple", "Grapple block", 155) to "Grapple latches",
)

/**
 * Parts for [category]. With a [library] (the ROM sweep + in-game verification) terrain, hazards, decor,
 * items and enemies come from what the game really uses; without one, a small built-in set is used.
 */
fun makerPartsFor(category: MakerCategory, editorState: EditorState, library: MakerLibrary? = null, parser: RomParser? = null): List<MakerPart> {
    if (library != null) when (category) {
        // The room's own ground first, then what the game uses with this tileset, then the shared (CRE) blocks for
        // kinds this tileset never uses, so every kind is always available.
        MakerCategory.TERRAIN, MakerCategory.HAZARDS -> {
            val fromGame = library.tileParts(editorState.currentTilesetId, category, editorState)
            val basics = basicPartsFor(category, editorState).filterIsInstance<MakerPart.Tile>()
            val ground = basics.filter { it.id == "ground" }
            val shared = basics.filter { it.id != "ground" && fromGame.none { g -> g.name.substringBefore(" ").equals(it.name.substringBefore(" "), true) } }
            return ground + fromGame.filter { g -> ground.none { it.metatile == g.metatile } } + shared
        }
        MakerCategory.DECOR -> return listOf(MakerPart.AnyTile) + library.tileParts(editorState.currentTilesetId, category, editorState)
        MakerCategory.ITEMS -> return library.itemParts()
        MakerCategory.ENEMIES -> if (parser != null) return library.enemyParts(editorState, parser)
        else -> Unit
    }
    return basicPartsFor(category, editorState)
}

private fun basicPartsFor(category: MakerCategory, editorState: EditorState): List<MakerPart> = when (category) {
    MakerCategory.TERRAIN -> buildList {
        dominantGroundMetatile(editorState)?.let { add(MakerPart.Tile("ground", "Ground", "solid, this area's look", category, it)) }
        SHARED_TERRAIN.forEach { (t, hint) -> add(MakerPart.Tile(t.first, t.second, hint, category, t.third)) }
    }
    MakerCategory.HAZARDS -> listOf(MakerPart.Tile("spikes", "Spikes", "hurts on touch", category, 86))
    MakerCategory.DOORS, MakerCategory.STATIONS -> (if (category == MakerCategory.STATIONS) listOf(MakerPart.StartPoint) else emptyList()) +
        editorState.project.patterns
        .filter { it.builtIn && it.id.startsWith("builtin_") && isDoorPattern(it.id) == (category == MakerCategory.DOORS) }
        .sortedBy { it.name }
        .map { MakerPart.Pattern(it.id, it.name, if (category == MakerCategory.DOORS) doorHint(it.name) else stationHint(it.id), category, it.id) }
    MakerCategory.ITEMS -> RomParser.ITEM_DEFS.map { MakerPart.Item("item-${it.visibleId}", it.name, ITEM_HINTS[it.name] ?: "upgrade", it.visibleId) }
    MakerCategory.ENEMIES -> editorState.workingEnemies.map { it.id }.distinct()
        .map { MakerPart.Enemy("enemy-$it", RomParser.enemyName(it), "loaded in this room", it) }
    MakerCategory.DECOR -> listOf(MakerPart.AnyTile)
}

/** Built-in patterns that are doors or gates; the rest (stations, refills, statues) go under Stations. */
fun isDoorPattern(patternId: String): Boolean = "door" in patternId || "gate" in patternId

private fun stationHint(id: String): String = when {
    "save" in id -> "saves the game"
    "energy_refill" in id -> "refills energy"
    "missile_refill" in id -> "refills ammo"
    "chozo" in id -> "statue"
    else -> "station"
}

private fun doorHint(name: String): String {
    val n = name.lowercase()
    return when {
        "pink" in n || "red" in n -> "5 Missiles open it"
        "green" in n -> "1 Super opens it"
        "yellow" in n || "orange" in n -> "Power Bomb opens it"
        "grey" in n || "gray" in n -> "boss / event lock"
        "blue" in n -> "any shot opens it"
        else -> "door"
    }
}

/** Most common solid (type 0x8) metatile in the loaded room, so "Ground" matches the area's look. */
fun dominantGroundMetatile(editorState: EditorState): Int? {
    val w = editorState.workingBlocksWide
    val h = editorState.workingBlocksTall
    if (w <= 0 || h <= 0) return null
    val counts = HashMap<Int, Int>()
    for (by in 0 until h) for (bx in 0 until w) {
        val word = editorState.readBlockWord(bx, by)
        if ((word shr 12) and 0xF == 0x8) counts.merge(word and 0x3FF, 1, Int::plus)
    }
    return counts.maxByOrNull { it.value }?.key
}

/** Most common air (type 0x0) block word in the loaded room: the area's own empty sky or wall backing. */
fun dominantAirWord(editorState: EditorState): Int? {
    val counts = HashMap<Int, Int>()
    for (by in 0 until editorState.workingBlocksTall) for (bx in 0 until editorState.workingBlocksWide) {
        val word = editorState.readBlockWord(bx, by)
        if ((word shr 12) and 0xF == 0x0) counts.merge(word, 1, Int::plus)
    }
    return counts.maxByOrNull { it.value }?.key
}

/** What a click will do, for the hint bar under the room. */
fun makerHint(tool: MakerTool, part: MakerPart?): String {
    val name = part?.name ?: "the chosen part"
    return when (tool) {
        MakerTool.SELECT -> "Click to inspect. Drag a part or a selected box to move it; drag empty space to select."
        MakerTool.MOVE -> "Drag to move around the room. Middle-drag moves the view with any tool."
        MakerTool.BOX -> when (part) {
            is MakerPart.Tile -> "Drag a box to fill it with $name. Right-drag a box to clear it."
            else -> "Box fills with terrain or hazard tiles. Choose one on the left."
        }
        MakerTool.PAINT -> when {
            part == null -> "Choose a part on the left, then click or drag to place it."
            part is MakerPart.AnyTile -> "Click any block in the room to copy it, then paint with it."
            part is MakerPart.StartPoint -> "Click the block Samus should stand in when Play starts. Placing it again moves it; right-click removes it."
            part.stamps -> "Click or drag to place $name. Right-click removes the item or enemy under the cursor."
            else -> "Click or drag to place $name. Right-click or right-drag removes blocks."
        }
        MakerTool.FILL -> "Click inside an enclosed area to flood it with $name."
        MakerTool.ERASE -> "Click or drag to clear blocks back to empty air."
        MakerTool.PICK -> "Click any block to copy it, then keep painting."
    }
}

/** Collision type of a block word (high nibble), in player terms. */
fun blockTypeName(word: Int): String = when ((word shr 12) and 0xF) {
    0x0 -> "Air"
    0x1 -> "Slope"
    0x2 -> "Spike air"
    0x3 -> "Special air"
    0x4 -> "Shootable air"
    0x5 -> "Extends left"
    0x8 -> "Solid"
    0x9 -> "Door"
    0xA -> "Spikes"
    0xB -> "Crumble / special"
    0xC -> "Shot block"
    0xD -> "Extends up"
    0xE -> "Grapple"
    0xF -> "Bomb block"
    else -> "Block"
}

val AREA_NAMES = listOf("Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres")
