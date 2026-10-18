package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.maker.MakerPlayPlan
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.TileOverlay

/** One line of the "Ready to play?" checklist. [ok] false shows a warning with how to fix it. */
data class ReadyCheck(val ok: Boolean, val title: String, val detail: String, val short: String = title)

/**
 * The same facts Maker PLAY relies on (see [MakerPlayPlan]): a save station in the area to boot from,
 * and either that station in this room or a door leading in.
 */
/** Block types Samus can't stand inside (solid, doors, spikes, breakables, grapple, and their extensions). */
private val SOLID_TYPES = setOf(0x1, 0x5, 0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF)

/**
 * Where the Start point is: inside a wall (Samus is about three blocks tall), or how far she falls.
 * [blockType] gives a block's collision type in the room (null outside it).
 */
fun startPointCheck(start: Pair<Int, Int>, blockType: (Int, Int) -> Int?): ReadyCheck? {
    val (x, y) = start
    fun solid(bx: Int, by: Int) = blockType(bx, by)?.let { it in SOLID_TYPES } ?: false
    if ((0..2).any { solid(x, y - it) }) return ReadyCheck(false, "Start point is inside a wall",
        "Samus is 3 blocks tall: move it to open space", "Start")
    val fall = (y + 1..y + 64).firstOrNull { blockType(x, it) == null || solid(x, it) }
    if (fall == null || blockType(x, fall) == null) return ReadyCheck(false, "Nothing under the Start point",
        "Samus would fall out of the room", "Start")
    val drop = fall - y - 1
    return if (drop == 0) null else ReadyCheck(true, "Samus can start here", "She drops $drop block${if (drop == 1) "" else "s"} to the floor first", "Start")
}

fun readyChecks(
    parser: RomParser, roomId: Int, startPoint: Pair<Int, Int>? = null,
    blockType: ((Int, Int) -> Int?)? = null, roomName: (Int) -> String,
): List<ReadyCheck> {
    val area = parser.readRoomHeader(roomId)?.area ?: return listOf(ReadyCheck(false, "Room not found", "Pick another room", "Room"))
    val areaName = AREA_NAMES.getOrElse(area) { "this area" }
    val stations = (0 until parser.saveEntryCount(area).coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
        .mapNotNull { parser.readSaveEntry(area, it) }.filter { it.roomId >= 0x8000 }
    val stationHere = stations.any { it.roomId == roomId }
    val doorsIn = parser.findDoorsLeadingTo(roomId).filter { it.doorDefPtr != 0 && !it.isElevator }
    val plan = MakerPlayPlan.forRoom(parser, roomId, startPoint)
    val placement = if (startPoint != null && blockType != null) startPointCheck(startPoint, blockType) else null
    return listOf(
        when {
            placement != null && plan != null -> placement
            plan == null && stations.isNotEmpty() -> ReadyCheck(false, "Play can't reach this room yet", "Place a Start point (Stations) or connect a door", "Start")
            plan == null -> ReadyCheck(false, "Play can't reach this room yet", "Connect a door from another room", "Start")
            plan.startPoint != null && plan.areaChange -> ReadyCheck(true, "Samus can start here",
                "At the Start point, coming in from ${AREA_NAMES.getOrElse(plan.area) { "another area" }}", "Start")
            plan.startPoint != null -> ReadyCheck(true, "Samus can start here", "At the Start point, block ${plan.startPoint.first}, ${plan.startPoint.second}", "Start")
            plan.entryDoor == null -> ReadyCheck(true, "Samus can start here", "From the save station in this room", "Start")
            else -> ReadyCheck(true, "Samus can start here", "Enters through the door from ${doorSource(parser, plan.entryDoor.doorDefPtr, roomName)}", "Start")
        },
        when {
            doorsIn.isNotEmpty() -> ReadyCheck(true, "Doors lead in", "${doorsIn.size} door${if (doorsIn.size == 1) "" else "s"} enter this room", "Doors in ${doorsIn.size}")
            stationHere -> ReadyCheck(true, "No door needed", "The save station here is the way in", "Doors in")
            startPoint != null -> ReadyCheck(true, "No door needed", "Play starts at the Start point", "Doors in")
            else -> ReadyCheck(false, "No door leads in yet", "Connect a door from another room", "Doors in")
        },
        when {
            stations.isNotEmpty() -> ReadyCheck(true, "Save station in $areaName", "Play boots from station ${plan?.stationIndex ?: 0}", "Save station")
            plan?.areaChange == true -> ReadyCheck(true, "No save station needed", "Play boots in ${AREA_NAMES.getOrElse(plan.area) { "another area" }} and walks in", "Save station")
            else -> ReadyCheck(false, "No save station in $areaName", "Place a Start point and Play boots from another area", "Save station")
        },
        ReadyCheck(true, "Every part works in Classic", "The Maker only offers Classic-ready parts", "Classic"),
    )
}

private fun doorSource(parser: RomParser, doorDefPtr: Int, roomName: (Int) -> String): String {
    for (info in parser.roomCatalog.rooms) {
        val header = parser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
        if (parser.parseDoorList(header.doorOut).any { it.doorDefPtr == doorDefPtr }) return roomName(info.getRoomIdAsInt())
    }
    return "another room"
}

/** Overlays for "What's solid?": everything Samus collides with or that hurts, drawn over the game art. */
val SOLID_VIEW_OVERLAYS: Set<TileOverlay> = setOf(
    TileOverlay.SOLID, TileOverlay.SLOPE, TileOverlay.DOOR, TileOverlay.SPIKE, TileOverlay.BOMB, TileOverlay.CRUMBLE,
    TileOverlay.GRAPPLE, TileOverlay.SPEED, TileOverlay.SHOT_BEAM, TileOverlay.SHOT_SUPER, TileOverlay.SHOT_PB,
)

/** Visual layers the Maker always shows so the room looks as it does in game (background, liquid). */
val MAKER_BASE_LAYERS: Set<TileOverlay> = setOf(TileOverlay.LAYER2, TileOverlay.LIQUID)

/** Optional markers the inspector can show on the canvas. */
enum class MakerMarker(val label: String, val short: String, val icon: String, val overlay: TileOverlay) {
    ITEMS("Item markers", "Items", MakerIcons.ITEMS, TileOverlay.ITEMS),
    ENEMIES("Enemy markers", "Enemies", MakerIcons.ENEMIES, TileOverlay.ENEMIES),
    SCROLL("Camera scroll triggers", "Camera", "M3 7h12v10H3zM15 10l6-3v10l-6-3", TileOverlay.SCROLL_PLMS),
}

/** The game keeps at most this many enemies in a room. */
const val MAKER_ENEMY_LIMIT = 32

/**
 * Pre-Play checks on the loaded room's own content: doorways that go nowhere, whether there's a way out,
 * items Samus may not reach from the Start point (walking through open blocks), and the enemy limit.
 */
fun makerRoomChecks(editorState: com.supermetroid.editor.ui.EditorState, startPoint: Pair<Int, Int>?): List<ReadyCheck> {
    val w = editorState.workingBlocksWide; val h = editorState.workingBlocksTall
    if (w <= 0 || h <= 0) return emptyList()
    fun type(x: Int, y: Int) = (editorState.readBlockWord(x, y) shr 12) and 0xF
    val checks = mutableListOf<ReadyCheck>()

    // Doors: every doorway's connection exists.
    val doorBts = (0 until h).flatMap { y -> (0 until w).filter { x -> type(x, y) == 0x9 }.map { x -> editorState.readBts(x, y) } }.toSet()
    val connections = editorState.doorEntries.size
    val dangling = doorBts.count { it >= connections }
    checks += when {
        doorBts.isEmpty() -> ReadyCheck(true, "No doors", "Samus can't leave this room (fine for a test room)", "Exits")
        dangling > 0 -> ReadyCheck(false, "A door goes nowhere", "Right-click it with Select › Connect this door", "Doors")
        else -> ReadyCheck(true, "Doors connect", "${doorBts.size} doorway${if (doorBts.size == 1) "" else "s"} lead somewhere", "Doors")
    }

    // Items: reachable from the Start point through blocks that aren't solid.
    if (startPoint != null) {
        val solidTypes = setOf(0x1, 0x8, 0x9, 0xA, 0xB, 0xC, 0xE, 0xF)
        val seen = BooleanArray(w * h)
        val queue = ArrayDeque(listOf(startPoint))
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            if (x !in 0 until w || y !in 0 until h || seen[y * w + x] || type(x, y) in solidTypes) continue
            seen[y * w + x] = true
            queue += listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)
        }
        val items = editorState.workingPlms.filter { editorState.isEditorItemPlm(it.id) && it.x in 0 until w && it.y in 0 until h }
        // Items hidden in a block count as reachable when a neighbouring open block is.
        fun reach(x: Int, y: Int) = listOf(x to y, x + 1 to y, x - 1 to y, x to y + 1, x to y - 1).any { (ax, ay) -> ax in 0 until w && ay in 0 until h && seen[ay * w + ax] }
        val out = items.count { !reach(it.x, it.y) }
        if (items.isNotEmpty()) checks += if (out == 0) ReadyCheck(true, "Items in reach", "All ${items.size} can be reached from the Start point", "Items")
            else ReadyCheck(false, "$out item${if (out == 1) "" else "s"} may be out of reach", "Walled off from the Start point (bombs and doors aside)", "Items")
    }

    // Enemies: within the game's limit.
    val enemies = editorState.workingEnemies.size
    if (enemies > MAKER_ENEMY_LIMIT) checks += ReadyCheck(false, "Too many enemies", "$enemies in this room; the game keeps at most $MAKER_ENEMY_LIMIT", "Enemies")
    return checks
}
