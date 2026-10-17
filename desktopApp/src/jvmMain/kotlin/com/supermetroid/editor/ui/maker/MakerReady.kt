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
fun readyChecks(parser: RomParser, roomId: Int, roomName: (Int) -> String): List<ReadyCheck> {
    val area = parser.readRoomHeader(roomId)?.area ?: return listOf(ReadyCheck(false, "Room not found", "Pick another room", "Room"))
    val areaName = AREA_NAMES.getOrElse(area) { "this area" }
    val stations = (0 until parser.saveEntryCount(area).coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
        .mapNotNull { parser.readSaveEntry(area, it) }.filter { it.roomId >= 0x8000 }
    val stationHere = stations.any { it.roomId == roomId }
    val doorsIn = parser.findDoorsLeadingTo(roomId).filter { it.doorDefPtr != 0 && !it.isElevator }
    val plan = MakerPlayPlan.forRoom(parser, roomId)
    return listOf(
        when {
            plan == null -> ReadyCheck(false, "Play can't reach this room yet", "Connect a door from another room", "Start")
            plan.entryDoor == null -> ReadyCheck(true, "Samus can start here", "From the save station in this room", "Start")
            else -> ReadyCheck(true, "Samus can start here", "Enters through the door from ${doorSource(parser, plan.entryDoor.doorDefPtr, roomName)}", "Start")
        },
        when {
            doorsIn.isNotEmpty() -> ReadyCheck(true, "Doors lead in", "${doorsIn.size} door${if (doorsIn.size == 1) "" else "s"} enter this room", "Doors in ${doorsIn.size}")
            stationHere -> ReadyCheck(true, "No door needed", "The save station here is the way in", "Doors in")
            else -> ReadyCheck(false, "No door leads in yet", "Connect a door from another room", "Doors in")
        },
        if (stations.isNotEmpty()) ReadyCheck(true, "Save station in $areaName", "Play boots from station ${plan?.stationIndex ?: 0}", "Save station")
        else ReadyCheck(false, "No save station in $areaName", "Play needs one to start the game", "Save station"),
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
