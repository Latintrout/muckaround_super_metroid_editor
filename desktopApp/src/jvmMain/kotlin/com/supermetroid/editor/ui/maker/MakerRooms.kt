package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.effectiveMapStationData
import com.supermetroid.editor.ui.effectiveMinimapData
import com.supermetroid.editor.ui.findAvailableRoomMapPosition

/** Room sizes the Maker offers for a new room (screens wide × tall). */
val MAKER_ROOM_SIZES = listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 1 to 2, 1 to 3, 2 to 2, 3 to 2)

/** How a new room starts: [EMPTY] (border, air, plain background) or a [COPY] of the room you're in. */
enum class MakerNewRoomStart(val label: String) { EMPTY("Empty"), COPY("Copy of this room") }

/** The look an empty new room is given once it loads: solid border, air inside, plain background. */
const val MAKER_NEW_ROOM_FLOOR_ROWS = 3
data class MakerNewRoomLook(val ground: Int, val air: Int, val background: Int)

/** Everything needed to make a new room next to the loaded one. */
data class MakerNewRoomPlan(val request: NewRoomCreationRequest, val look: MakerNewRoomLook?)

/**
 * Plans a new room in the loaded room's area: a free map spot near it, its tileset, and (for an empty
 * room) the ground, air and background words taken from it. Null when the area's map has no space.
 * The loaded room must be [sourceRoomId].
 */
fun planMakerNewRoom(
    editorState: EditorState, parser: RomParser, sourceRoomId: Int?,
    width: Int = 1, height: Int = 1, start: MakerNewRoomStart = MakerNewRoomStart.EMPTY, name: String? = null,
): MakerNewRoomPlan? {
    val header = sourceRoomId?.let { parser.readRoomHeader(it)?.let(editorState::applyHeaderChanges) }
    val copy = start == MakerNewRoomStart.COPY && header != null
    val w = if (copy) header!!.width else width
    val h = if (copy) header!!.height else height
    val area = header?.area ?: 0
    val areaRooms = parser.roomCatalog.rooms.mapNotNull { info ->
        parser.readRoomHeader(info.getRoomIdAsInt())?.let(editorState::applyHeaderChanges)
    }.filter { it.area == area }
    val spot = findAvailableRoomMapPosition(
        effectiveMinimapData(parser, editorState.project, area), effectiveMapStationData(parser, editorState.project, area),
        areaRooms, w, h, header?.mapX ?: 0, header?.mapY ?: 0,
    ) ?: return null
    val roomName = name?.trim()?.takeIf { it.isNotEmpty() } ?: "New room ${editorState.project.newRooms.size + 1}"
    val request = NewRoomCreationRequest(roomName, cloneCurrentRoom = copy, area = area, mapX = spot.first, mapY = spot.second,
        width = w, height = h, tileset = header?.tileset ?: editorState.currentTilesetId)
    if (copy) return MakerNewRoomPlan(request, null)
    val ground = 0x8000 or (dominantGroundMetatile(editorState) ?: 0x111)
    val air = dominantAirWord(editorState) ?: RomConstants.AIR_TILE_WORD
    val background = if (editorState.canEditEmbeddedLayer2()) {
        (0 until editorState.workingBlocksTall).flatMap { y -> (0 until editorState.workingBlocksWide).map { x -> editorState.readLayer2BlockWord(x, y) } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: RomConstants.AIR_TILE_WORD
    } else RomConstants.AIR_TILE_WORD
    return MakerNewRoomPlan(request, MakerNewRoomLook(ground, air, background))
}

/**
 * Gives the loaded (new, blank) room [look] as one undo step: solid walls and ceiling, a floor
 * [MAKER_NEW_ROOM_FLOOR_ROWS] thick, air inside, and a plain
 * background (a blank room's embedded layer 2 is metatile 0, which most tilesets draw as a pattern).
 */
fun applyMakerNewRoomLook(editorState: EditorState, look: MakerNewRoomLook) {
    val w = editorState.workingBlocksWide; val h = editorState.workingBlocksTall
    val cells = (0 until h).flatMap { y -> (0 until w).map { x -> x to y } }
    val layer1 = cells.mapNotNull { (x, y) ->
        // The floor is 3 blocks thick: with the game's normal scrolling the bottom 2 rows are off screen.
        val word = if (x == 0 || y == 0 || x == w - 1 || y >= h - MAKER_NEW_ROOM_FLOOR_ROWS) look.ground else look.air
        val old = editorState.readBlockWord(x, y); val oldBts = editorState.readBts(x, y)
        if (old == word && oldBts == 0) null else TileEdit(x, y, old, word, oldBts, 0)
    }
    val layer2 = if (!editorState.canEditEmbeddedLayer2()) emptyList() else cells.mapNotNull { (x, y) ->
        val old = editorState.readLayer2BlockWord(x, y)
        if (old == look.background) null else TileEdit(x, y, old, look.background, layer = TILE_EDIT_LAYER_2)
    }
    editorState.applyBulkEdits("Maker new room", layer1 + layer2)
}
