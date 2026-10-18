package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.parseLibraryBackground
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.effectiveMapStationData
import com.supermetroid.editor.ui.effectiveMinimapData
import com.supermetroid.editor.ui.findAvailableRoomMapPosition

/** Room sizes the Maker offers for a new room (screens wide × tall). */
val MAKER_ROOM_SIZES = listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 1 to 2, 1 to 3, 2 to 2, 3 to 2)

/**
 * How a new room starts: an empty room or a layout template (all with walls, a ceiling and a floor
 * [MAKER_NEW_ROOM_FLOOR_ROWS] thick, in the area's ground), or a [COPY] of the room you're in.
 * [size] is the suggested size in screens.
 */
enum class MakerNewRoomStart(val label: String, val hint: String, val size: Pair<Int, Int>) {
    EMPTY("Empty", "walls and a floor", 1 to 1),
    CORRIDOR("Corridor", "long, low, with ledges", 3 to 1),
    SHAFT("Shaft", "tall, with ledges to climb", 1 to 3),
    CAVERN("Cavern", "rough natural cave", 2 to 2),
    PIPE_MAZE("Pipe maze", "tunnels 4 blocks wide", 2 to 2),
    COPY("Copy", "of the room you're in", 1 to 1);

    val isTemplate get() = this != COPY
}

/** The look an empty new room is given once it loads: solid border, air inside, plain background. */
const val MAKER_NEW_ROOM_FLOOR_ROWS = 3
data class MakerNewRoomLook(val ground: Int, val air: Int, val background: Int, val template: MakerNewRoomStart = MakerNewRoomStart.EMPTY, val seed: Int = 1)

/**
 * Which blocks of a [w] × [h] block room are solid in [template]. Deterministic for a [seed]; every
 * template keeps the outer walls, the ceiling and a floor [MAKER_NEW_ROOM_FLOOR_ROWS] thick (the
 * game's normal scrolling hides the bottom 2 rows).
 */
fun makerTemplateSolid(template: MakerNewRoomStart, w: Int, h: Int, seed: Int = 1): BooleanArray {
    val solid = BooleanArray(w * h)
    fun set(x: Int, y: Int, v: Boolean = true) { if (x in 0 until w && y in 0 until h) solid[y * w + x] = v }
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Boolean = true) { for (y in y0..y1) for (x in x0..x1) set(x, y, v) }
    val floor = h - MAKER_NEW_ROOM_FLOOR_ROWS
    when (template) {
        MakerNewRoomStart.CORRIDOR -> {
            rect(0, 0, w - 1, 3)                              // thick ceiling
            for (x in 10 until w - 6 step 14) rect(x, floor - 4, x + 3, floor - 4) // ledges to hop between
            for (x in 17 until w - 6 step 28) rect(x, floor - 1, x + 1, floor - 1) // low bumps
        }
        MakerNewRoomStart.SHAFT -> {
            rect(0, 0, 1, h - 1); rect(w - 2, 0, w - 1, h - 1) // thick walls
            var left = true
            for (y in floor - 5 downTo 4 step 5) { if (left) rect(2, y, 6, y) else rect(w - 7, y, w - 3, y); left = !left }
        }
        MakerNewRoomStart.CAVERN -> {
            val rnd = java.util.Random(seed.toLong())
            for (i in solid.indices) solid[i] = rnd.nextInt(100) < 44
            repeat(5) {
                val next = solid.copyOf()
                for (y in 0 until h) for (x in 0 until w) {
                    var n = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h || solid[ny * w + nx]) n++
                    }
                    next[y * w + x] = n >= 5
                }
                next.copyInto(solid)
            }
            // A clear band to stand and walk in above the floor.
            rect(2, floor - 4, w - 3, floor - 1, false)
        }
        MakerNewRoomStart.PIPE_MAZE -> {
            // Cells of 6 blocks: 4 open (Samus is 3 tall) + a 2-block wall; carved as a depth-first maze.
            for (i in solid.indices) solid[i] = true
            val cw = (w - 2) / 6; val ch = (floor - 1) / 6
            if (cw > 0 && ch > 0) {
                val rnd = java.util.Random(seed.toLong())
                val seen = Array(ch) { BooleanArray(cw) }
                fun carve(cx: Int, cy: Int) { rect(2 + cx * 6, 1 + cy * 6 + 1, 2 + cx * 6 + 3, 1 + cy * 6 + 4, false) }
                val stack = ArrayDeque(listOf(0 to ch - 1)); seen[ch - 1][0] = true; carve(0, ch - 1)
                while (stack.isNotEmpty()) {
                    val (cx, cy) = stack.last()
                    val next = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).shuffled(rnd)
                        .map { (dx, dy) -> cx + dx to cy + dy }.firstOrNull { (nx, ny) -> nx in 0 until cw && ny in 0 until ch && !seen[ny][nx] }
                    if (next == null) { stack.removeLast(); continue }
                    val (nx, ny) = next
                    seen[ny][nx] = true; carve(nx, ny)
                    // Open the wall between the two cells.
                    rect(2 + minOf(cx, nx) * 6, 1 + minOf(cy, ny) * 6 + 1, 2 + maxOf(cx, nx) * 6 + 3, 1 + maxOf(cy, ny) * 6 + 4, false)
                    stack.addLast(next)
                }
            } else rect(1, 1, w - 2, floor - 1, false)
        }
        else -> Unit
    }
    // Every template: walls, ceiling, floor.
    rect(0, 0, w - 1, 0); rect(0, 0, 0, h - 1); rect(w - 1, 0, w - 1, h - 1); rect(0, floor, w - 1, h - 1)
    return solid
}

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
    seed: Int = (System.nanoTime() and 0x7FFFFFFF).toInt(),
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
    return MakerNewRoomPlan(request, MakerNewRoomLook(ground, air, background, start, seed))
}

/**
 * Gives the loaded (new, blank) room [look] as one undo step: solid walls and ceiling, a floor
 * [MAKER_NEW_ROOM_FLOOR_ROWS] thick, air inside, and a plain
 * background (a blank room's embedded layer 2 is metatile 0, which most tilesets draw as a pattern).
 */
fun applyMakerNewRoomLook(editorState: EditorState, look: MakerNewRoomLook) {
    val w = editorState.workingBlocksWide; val h = editorState.workingBlocksTall
    val cells = (0 until h).flatMap { y -> (0 until w).map { x -> x to y } }
    val solid = makerTemplateSolid(look.template, w, h, look.seed)
    val layer1 = cells.mapNotNull { (x, y) ->
        val word = if (solid[y * w + x]) look.ground else look.air
        val old = editorState.readBlockWord(x, y); val oldBts = editorState.readBts(x, y)
        if (old == word && oldBts == 0) null else TileEdit(x, y, old, word, oldBts, 0)
    }
    val layer2 = if (!editorState.canEditEmbeddedLayer2()) emptyList() else cells.mapNotNull { (x, y) ->
        val old = editorState.readLayer2BlockWord(x, y)
        if (old == look.background) null else TileEdit(x, y, old, look.background, layer = TILE_EDIT_LAYER_2)
    }
    editorState.applyBulkEdits("Maker new room", layer1 + layer2)
}

/** A side of the room, for adding or removing a screen. */
enum class MakerSide(val label: String) { LEFT("Left"), RIGHT("Right"), TOP("Top"), BOTTOM("Bottom") }

/**
 * Adds ([grow]) or removes one screen on [side] of the loaded room. A new screen continues the room: it
 * copies the row or column just inside the old edge (so floor and ceiling carry on) and the outer wall
 * moves to the new edge. Removing a screen first clears it (its items, enemies and Start point go too).
 * Left/top changes shift the room on the map. Returns an error to show, or null when done.
 * Like SMEDIT's own resize this starts a fresh undo history for the room.
 */
fun EditorState.resizeMakerRoom(parser: RomParser, side: MakerSide, grow: Boolean): String? {
    val roomId = currentRoomId
    // A Maker room is one layout: edits apply to every state that shares it (as the Maker screen sets).
    fun oneLayout() {
        if (currentLayoutEditingContext()?.requiresScopeChoice == true) setCurrentLayoutEditScope(com.supermetroid.editor.ui.LayoutEditScope.ALL_SHARING_STATES)
    }
    oneLayout()
    val header = parser.readRoomHeader(roomId)?.let(::applyHeaderChanges) ?: return "Room not found."
    val w = workingBlocksWide / 16; val h = workingBlocksTall / 16
    val horizontal = side == MakerSide.LEFT || side == MakerSide.RIGHT
    val nw = if (horizontal) w + (if (grow) 1 else -1) else w
    val nh = if (!horizontal) h + (if (grow) 1 else -1) else h
    if (nw !in 1..15 || nh !in 1..15) return if (grow) "Rooms can be at most 15 screens across or down." else "A room needs at least one screen."
    val leading = side == MakerSide.LEFT || side == MakerSide.TOP
    if (leading && parser.findDoorsLeadingTo(roomId).any { it.doorDefPtr != 0 }) {
        return "Doors lead into this room, so it can only change on the right or bottom for now."
    }
    val mapX = header.mapX - if (grow && side == MakerSide.LEFT) 1 else if (!grow && side == MakerSide.LEFT) -1 else 0
    val mapY = header.mapY - if (grow && side == MakerSide.TOP) 1 else if (!grow && side == MakerSide.TOP) -1 else 0
    if (grow) {
        if (mapX < 0 || mapY < 0 || mapX + nw > 64 || mapY + nh > 31) return "There's no map space on that side."
        val others = parser.roomCatalog.rooms.mapNotNull { info ->
            info.getRoomIdAsInt().takeIf { it != roomId }?.let { parser.readRoomHeader(it)?.let(::applyHeaderChanges) }?.let { info.name to it }
        }.filter { it.second.area == header.area }
        val clash = others.firstOrNull { (_, o) -> mapX < o.mapX + o.width && o.mapX < mapX + nw && mapY < o.mapY + o.height && o.mapY < mapY + nh }
        if (clash != null) return "${clash.first.ifBlank { "Another room" }} is in the way on the map."
    }
    val bw = w * 16; val bh = h * 16
    if (!grow) {
        // Clear the screen being removed, then (left/top) slide the rest over it.
        val strip = when (side) {
            MakerSide.LEFT -> MakerBox(0, 0, 15, bh - 1)
            MakerSide.RIGHT -> MakerBox(bw - 16, 0, bw - 1, bh - 1)
            MakerSide.TOP -> MakerBox(0, 0, bw - 1, 15)
            MakerSide.BOTTOM -> MakerBox(0, bh - 16, bw - 1, bh - 1)
        }
        clearBox(strip)
        when (side) {
            MakerSide.LEFT -> moveBox(MakerBox(16, 0, bw - 1, bh - 1), -16, 0)
            MakerSide.TOP -> moveBox(MakerBox(0, 16, bw - 1, bh - 1), 0, -16)
            else -> Unit
        }
        if (!resizeRoom(w, h, nw, nh)) return statusMessage.ifBlank { "The room could not be resized." }
    } else {
        if (!resizeRoom(w, h, nw, nh)) return statusMessage.ifBlank { "The room could not be resized." }
        oneLayout()
        when (side) {
            MakerSide.LEFT -> moveBox(MakerBox(0, 0, bw - 1, bh - 1), 16, 0)
            MakerSide.TOP -> moveBox(MakerBox(0, 0, bw - 1, bh - 1), 0, 16)
            else -> Unit
        }
        // Continue the room into the new screen.
        val nbw = nw * 16; val nbh = nh * 16
        val edits = mutableListOf<TileEdit>()
        fun put(x: Int, y: Int, fromX: Int, fromY: Int) {
            val word = readBlockWord(fromX, fromY); val bts = readBts(fromX, fromY)
            val oldWord = readBlockWord(x, y); val oldBts = readBts(x, y)
            if (oldWord != word || oldBts != bts) edits += TileEdit(x, y, oldWord, word, oldBts, bts)
        }
        // How thick the wall is at that edge (columns/rows that are solid all along), so the new edge keeps it.
        fun isSolid(x: Int, y: Int) = (readBlockWord(x, y) shr 12) and 0xF == 0x8
        fun wallCols(from: Int, step: Int) = (0 until 6).takeWhile { i -> (0 until nbh).all { y -> isSolid(from + i * step, y) } }.size.coerceIn(1, 6)
        fun wallRows(from: Int, step: Int) = (0 until 6).takeWhile { i -> (0 until nbw).all { x -> isSolid(x, from + i * step) } }.size.coerceIn(1, 6)
        when (side) {
            MakerSide.LEFT -> { // old content now starts at x = 16
                val t = wallCols(16, 1)
                for (y in 0 until nbh) { for (x in t until 16 + t) put(x, y, 16 + t, y); for (i in 0 until t) put(i, y, 16 + i, y) }
            }
            MakerSide.RIGHT -> {
                val t = wallCols(bw - 1, -1)
                for (y in 0 until nbh) { for (x in bw - t until nbw - t) put(x, y, bw - 1 - t, y); for (i in 0 until t) put(nbw - 1 - i, y, bw - 1 - i, y) }
            }
            MakerSide.TOP -> { // old content now starts at y = 16
                val t = wallRows(16, 1)
                for (x in 0 until nbw) { for (y in t until 16 + t) put(x, y, x, 16 + t); for (i in 0 until t) put(x, i, x, 16 + i) }
            }
            MakerSide.BOTTOM -> {
                // Keep the floor's thickness at the new bottom and continue the row above it.
                val t = wallRows(bh - 1, -1).coerceAtLeast(MAKER_NEW_ROOM_FLOOR_ROWS)
                for (x in 0 until nbw) { for (y in bh - t until nbh - t) put(x, y, x, bh - 1 - t); for (i in 0 until t) put(x, nbh - 1 - i, x, bh - 1 - i) }
            }
        }
        // Every copy above reads the room as it was before this edit, so one bulk edit applies them all.
        applyBulkEdits("Maker extend room", edits.distinctBy { it.blockX to it.blockY })
    }
    if (mapX != header.mapX || mapY != header.mapY) {
        val change = (project.getOrCreateRoom(roomId).effectiveHeaderChange ?: com.supermetroid.editor.data.RoomHeaderChange())
        setRoomHeaderChange(change.copy(mapX = mapX, mapY = mapY))
    }
    return null
}

/** A background the game uses: a library background program and how layer 2 scrolls with it. */
data class MakerBackground(val bgDataPtr: Int, val bgScrolling: Int, val exampleRoomId: Int)

/** Backgrounds vanilla rooms with [tileset] use (one per background program), with a room that shows each. */
fun makerBackgroundChoices(parser: RomParser, tileset: Int, projectRoomIds: Set<Int> = emptySet()): List<MakerBackground> =
    parser.roomCatalog.rooms.asSequence()
        .map { it.getRoomIdAsInt() }.filter { it !in projectRoomIds }
        .mapNotNull { id -> parser.readRoomHeader(id)?.takeIf { it.tileset == tileset && it.bgDataPtr in 0x8000..0xFFFE }?.let { MakerBackground(it.bgDataPtr, it.bgScrolling, id) } }
        // Whole backgrounds only: programs that pick their tilemap by the incoming door (our play-test door
        // isn't one of the game's) are left out.
        .filter { bg ->
            runCatching {
                val program = parser.parseLibraryBackground(bg.bgDataPtr) ?: return@runCatching false
                program.commands.none { it.type == com.supermetroid.editor.rom.LibraryBackgroundCommandType.DOOR_DEPENDENT_TRANSFER } &&
                    parser.readBgTilemapLayout(bg.bgDataPtr) != null
            }.getOrDefault(false)
        }
        .distinctBy { it.bgDataPtr }
        .toList()

/** The background of a room you made, or null for a plain one (layer 2 in its own layout). */
fun EditorState.makerBackground(roomId: Int): MakerBackground? =
    project.newRoomForPreviewId(roomId)?.initialState?.takeIf { it.bgDataPtr != 0 }?.let { MakerBackground(it.bgDataPtr, it.bgScrolling, 0) }

/**
 * Gives a room you made [background] (null: plain). Only project rooms can change background here;
 * the caller rebuilds the workspace afterwards. Returns an error to show, or null.
 */
fun EditorState.setMakerBackground(roomId: Int, background: MakerBackground?): String? {
    val room = project.newRoomForPreviewId(roomId) ?: return "Backgrounds can be changed for rooms you made. Use Advanced mode for the game's rooms."
    val state = room.initialState
    val ptr = background?.bgDataPtr ?: 0
    val scroll = background?.bgScrolling ?: 0
    if (state.bgDataPtr == ptr && state.bgScrolling == scroll) return null
    state.bgDataPtr = ptr
    state.bgScrolling = scroll
    markProjectChanged()
    return null
}

/** The loaded room's liquid: its fx type (lava, acid, water) and surface height in pixels, or null when it has none. */
fun EditorState.makerLiquid(parser: RomParser): Pair<MakerFx, Int>? {
    val fx = runCatching { effectiveFxEntries(currentRoomId, parser).firstOrNull { it.doorSelect == 0 } }.getOrNull() ?: return null
    val kind = MakerFx.entries.firstOrNull { it.fxType == fx.fxType && it.isLiquid } ?: return null
    val y = fx.liquidSurfaceStart.takeIf { it in 0 until workingBlocksTall * 16 } ?: return null
    return kind to y
}

/** Sets the loaded room's liquid surface to [y] pixels (start and target the same), as one undo step. */
fun EditorState.setMakerLiquidLevel(y: Int) {
    val before = currentFxChange()
    val after = (before ?: com.supermetroid.editor.data.FxChange()).copy(liquidSurfaceStart = y, liquidSurfaceNew = y)
    if (before == after) return
    putCurrentFxChange(after)
    recordProjectChange("Maker liquid level", undo = { putCurrentFxChange(before) }, redo = { putCurrentFxChange(after) })
}
