package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope

/** The doorway (door blocks) that contains block ([x], [y]): its column and top row, and the door pattern colour. */
data class MakerDoorway(val x: Int, val top: Int, val height: Int)

/** Door blocks (type 0x9) in the column of ([x], [y]), stacked; null when that block isn't a door. */
fun EditorState.makerDoorwayAt(x: Int, y: Int): MakerDoorway? {
    fun door(by: Int) = by in 0 until workingBlocksTall && (readBlockWord(x, by) shr 12) and 0xF == 0x9
    if (x !in 0 until workingBlocksWide || !door(y)) return null
    var top = y; while (door(top - 1)) top--
    var bottom = y; while (door(bottom + 1)) bottom++
    return MakerDoorway(x, top, bottom - top + 1)
}

/**
 * Puts a doorway on the room's side wall the way the game's rooms have it: the door blocks (transition) in the
 * wall column and the door cap one column inside. A cap left on the transition column would replace the door
 * blocks when it loads and clear them when shot, so Samus could never go through. Returns true if it moved one.
 */
fun EditorState.seatMakerDoorCap(doorway: MakerDoorway): Boolean {
    val inward = when (doorway.x) { 0 -> 1; workingBlocksWide - 1 -> -1; else -> return false }
    val caps = workingPlms.filter { RomParser.isDoorCapPlm(it.id) && it.x == doorway.x && it.y in doorway.top until doorway.top + doorway.height }
    if (caps.isEmpty()) return false
    asOneUndoStep {
        for (cap in caps) {
            removePlm(cap.x, cap.y, cap.id, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
            addPlm(cap.id, cap.x + inward, cap.y, cap.param, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
        }
    }
    return true
}

/** The door pattern of the other side: "builtin_door_blue_left" ↔ "builtin_door_blue_right". */
fun returnDoorPattern(patternId: String): String? = when {
    patternId.endsWith("_left") -> patternId.removeSuffix("_left") + "_right"
    patternId.endsWith("_right") -> patternId.removeSuffix("_right") + "_left"
    else -> null
}

/** Result of connecting a door: the rebuilt workspace and what to tell the user. */
data class MakerDoorLink(val parser: RomParser, val message: String)

/**
 * Connects the doorway at ([x], [y]) in the loaded room (one you made) to [destRoomId].
 *
 * Doors go left or right (Samus moves through the side wall). When the destination is also a room you made,
 * a matching return door is painted on its facing wall at the same height (opening the wall behind it) and
 * linked back, so the two rooms connect both ways. A game room gets a one-way door that arrives where the
 * game's own doors from that side arrive. Loading the destination to paint the return door starts a fresh
 * Undo history. [doorPattern] is the pattern used for the doorway (for the return door's colour).
 */
fun EditorState.connectMakerDoor(parser: RomParser, x: Int, y: Int, destRoomId: Int, doorPattern: String?): Result<MakerDoorLink> = runCatching {
    val srcId = currentRoomId
    val src = requireNotNull(project.newRoomForPreviewId(srcId)) { "Doors can be connected from rooms you made. Use Advanced mode for the game's rooms." }
    val doorway = requireNotNull(makerDoorwayAt(x, y)) { "That isn't a door. Place a door from the Doors parts first." }
    require(destRoomId != srcId) { "A door can't lead back into the same room." }
    seatMakerDoorCap(doorway)
    val w = workingBlocksWide
    // A door in the left half of a screen faces left (Samus walks out to the left), otherwise right.
    val direction = if (doorway.x % 16 < 8) 1 else 0
    val destHeader = requireNotNull(parser.readRoomHeader(destRoomId)?.let(::applyHeaderChanges)) { "That room can't be read." }
    val destOwn = project.newRoomForPreviewId(destRoomId)
    val srcScreen = doorway.x / 16 to doorway.top / 16
    val relRow = doorway.top % 16

    // Where Samus arrives in the destination: its facing wall, on the same screen row (kept inside the room).
    val destScreenY = srcScreen.second.coerceIn(0, destHeader.height - 1)
    val destScreenX = if (direction == 0) 0 else destHeader.width - 1
    if (destOwn == null) {
        // Arrive the way one of the game's own doors into that room from the same side does.
        val vanilla = parser.findDoorsLeadingTo(destRoomId).firstOrNull { (it.direction and 0x03) == direction && !it.isElevator && it.doorDefPtr != 0 }
            ?: error("No door of the game enters ${destHeader.name.ifBlank { "that room" }} from that side.")
        val index = addProjectRoomDoor(srcId, destRoomId, direction, vanilla.screenX, vanilla.screenY, parser, doorCapCode = vanilla.doorCapCode)
        linkDoorwayTilesToConnection(doorway.x, doorway.top, index)
        val ws = prepareWorkspaceParser()
        return@runCatching MakerDoorLink(ws, "Connected one way to ${destHeader.name.ifBlank { "that room" }}")
    }

    val index = addProjectRoomDoor(srcId, destRoomId, direction, destScreenX, destScreenY, parser)
    linkDoorwayTilesToConnection(doorway.x, doorway.top, index)
    var ws = prepareWorkspaceParser()

    // The return door, in the destination.
    loadRoom(destRoomId, ws, requireNotNull(ws.readRoomHeader(destRoomId)))
    if (currentLayoutEditingContext()?.requiresScopeChoice == true) setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
    val dw = workingBlocksWide
    val rx = if (direction == 0) 0 else dw - 1
    val ry = (destScreenY * 16 + relRow).coerceIn(1, workingBlocksTall - 5)
    // Open the wall just inside the return door so Samus can walk in (up to 3 blocks deep).
    val inward = if (direction == 0) 1 else -1
    val open = mutableListOf<TileEdit>()
    for (row in ry until ry + doorway.height.coerceAtLeast(4)) for (d in 1..3) {
        val cx = rx + inward * d
        val word = readBlockWord(cx, row)
        if ((word shr 12) and 0xF == 0x0) break
        open += TileEdit(cx, row, word, RomConstants.AIR_TILE_WORD, readBts(cx, row), 0)
    }
    applyBulkEdits("Maker open doorway", open)
    seedBuiltInPatterns(ws) // the door patterns (the Maker seeds them when the Doors tab opens)
    val pattern = doorPattern?.let(::returnDoorPattern) ?: if (direction == 0) "builtin_door_blue_left" else "builtin_door_blue_right"
    val savedBrush = brush; val savedTool = activeTool
    selectAndApplyPattern(pattern)
    beginStroke(); paintAt(rx, ry); endStroke("Maker return door")
    brush = savedBrush; activeTool = savedTool
    seatMakerDoorCap(requireNotNull(makerDoorwayAt(rx, ry)) { "The return door could not be placed." })
    // Each door's cap code is where the cap of the door it arrives through sits (the game closes it behind Samus):
    // one column inside a doorway on the room's side wall.
    fun capColumn(x: Int, roomWidth: Int) = when (x) { 0 -> 1; roomWidth - 1 -> x - 1; else -> x }
    val back = addProjectRoomDoor(destRoomId, srcId, 1 - direction, srcScreen.first, srcScreen.second, ws,
        doorCapCode = capColumn(doorway.x, w) or (doorway.top shl 8))
    linkDoorwayTilesToConnection(rx, ry, back)
    src.doors[index].doorCapCode = capColumn(rx, dw) or (ry shl 8)
    ws = prepareWorkspaceParser()
    loadRoom(srcId, ws, requireNotNull(ws.readRoomHeader(srcId)))
    if (currentLayoutEditingContext()?.requiresScopeChoice == true) setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
    if (w != workingBlocksWide) error("The room changed while connecting")
    MakerDoorLink(ws, "Connected both ways with ${destOwn.name}")
}
