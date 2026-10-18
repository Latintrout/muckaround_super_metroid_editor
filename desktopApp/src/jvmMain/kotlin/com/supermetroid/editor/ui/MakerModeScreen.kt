package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.maker.MakerPlayLauncher
import com.supermetroid.editor.maker.MakerPlayLoadout
import com.supermetroid.editor.maker.MakerPlayPlan
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.maker.AREA_NAMES
import com.supermetroid.editor.ui.maker.MAKER_BASE_LAYERS
import com.supermetroid.editor.ui.maker.MakerMarker
import com.supermetroid.editor.ui.maker.ReadyCheck
import com.supermetroid.editor.ui.maker.SOLID_VIEW_OVERLAYS
import com.supermetroid.editor.ui.maker.readyChecks
import com.supermetroid.editor.ui.maker.KeyCap
import com.supermetroid.editor.ui.maker.MakerButton
import com.supermetroid.editor.ui.maker.MakerCategory
import com.supermetroid.editor.ui.maker.MakerIcon
import com.supermetroid.editor.ui.maker.MakerIcons
import com.supermetroid.editor.ui.maker.MakerPart
import com.supermetroid.editor.ui.maker.MakerTool
import com.supermetroid.editor.ui.maker.SectionLabel
import com.supermetroid.editor.ui.maker.blockTypeName
import com.supermetroid.editor.ui.maker.makerHint
import com.supermetroid.editor.ui.maker.resizeMakerRoom
import com.supermetroid.editor.ui.maker.makerSelection
import com.supermetroid.editor.ui.maker.selectBox
import com.supermetroid.editor.ui.maker.copyBox
import com.supermetroid.editor.ui.maker.pasteClip
import com.supermetroid.editor.ui.maker.duplicateSpot
import com.supermetroid.editor.ui.maker.clearBox
import com.supermetroid.editor.ui.maker.moveBox
import com.supermetroid.editor.ui.maker.fillRegion
import com.supermetroid.editor.ui.maker.makerPartsFor
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerBrightness
import com.supermetroid.editor.ui.theme.MakerFixedColors
import com.supermetroid.editor.ui.theme.MakerThemeProvider
import com.supermetroid.editor.ui.theme.MakerThemeState
import com.supermetroid.editor.ui.theme.RoomBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/** Equipment written to WRAM before a test warp. Bit values from the game's item/beam masks. */
internal data class MakerLoadout(
    val name: String,
    val items: Int,
    val beams: Int,
    val energyTanks: Int,
    val missiles: Int,
)

internal val MAKER_LOADOUTS = listOf(
    MakerLoadout("Starter (nothing)", items = 0, beams = 0, energyTanks = 0, missiles = 0),
    // Iron Warden test loadout from the planning brief: Varia, Morph, Bombs, Charge,
    // Missiles, Hi-Jump, four Energy Tanks.
    MakerLoadout(
        "Iron Warden",
        items = 0x0001 or 0x0004 or 0x1000 or 0x0100,
        beams = 0x1000,
        energyTanks = 4,
        missiles = 30,
    ),
)

/**
 * Maker Mode: a Mario Maker-style front end over the same [EditorState] that powers the
 * full editor, styled with the MODRET theme system. Theme colours apply to the chrome only;
 * the room view and part previews always use the game's own pixels.
 */
@Composable
internal fun MakerModeScreen(
    editorState: EditorState,
    romParser: RomParser?,
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    onRoomSelected: (RoomInfo) -> Unit,
    onWorkspaceChanged: () -> Unit,
    onPlay: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    themeState: MakerThemeState = remember { MakerThemeState() },
    initialSolidView: Boolean = false,
    playing: Boolean = false,
    onStopPlay: () -> Unit = {},
    initialAppearanceOpen: Boolean = false,
    initialTool: MakerTool = MakerTool.PAINT,
    canvasControls: MapCanvasControls = remember { MapCanvasControls() },
    /** Creates a project room (the same action as Advanced mode's room list); returns an error, or null on success. */
    onCreateRoom: ((NewRoomCreationRequest) -> String?)? = null,
    /** Deletes a project room and selects a neighbour; returns an error, or null on success. */
    onDeleteRoom: ((Int) -> String?)? = null,
) {
    MakerThemeProvider(themeState) {
        MakerScreenContent(editorState, romParser, rooms, selectedRoom, emulatorWorkspaceState, onRoomSelected,
            onWorkspaceChanged, onPlay, onExit, themeState, modifier, initialSolidView, playing, onStopPlay, initialAppearanceOpen,
            initialTool, canvasControls, onCreateRoom, onDeleteRoom)
    }
}

@Composable
private fun MakerScreenContent(
    editorState: EditorState,
    romParser: RomParser?,
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    onRoomSelected: (RoomInfo) -> Unit,
    onWorkspaceChanged: () -> Unit,
    onPlay: () -> Unit,
    onExit: () -> Unit,
    themeState: MakerThemeState,
    modifier: Modifier,
    initialSolidView: Boolean,
    playing: Boolean,
    onStopPlay: () -> Unit,
    initialAppearanceOpen: Boolean,
    initialTool: MakerTool,
    controls: MapCanvasControls,
    onCreateRoom: ((NewRoomCreationRequest) -> String?)?,
    onDeleteRoom: ((Int) -> String?)?,
) {
    val p = LocalMakerPalette.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(initialTool) }
    var category by remember { mutableStateOf(MakerCategory.TERRAIN) }
    var activePart by remember { mutableStateOf<MakerPart?>(null) }
    var search by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var loadout by remember { mutableStateOf(MAKER_LOADOUTS[1]) }
    var message by remember { mutableStateOf<String?>(null) }
    var solidView by remember { mutableStateOf(initialSolidView) }
    var appearanceOpen by remember { mutableStateOf(initialAppearanceOpen) }
    var roomLookOpen by remember { mutableStateOf(false) }
    // Item and enemy markers start on: at fit-to-room zoom a 16-pixel item is only a few pixels wide.
    var markers by remember { mutableStateOf(setOf(MakerMarker.ITEMS, MakerMarker.ENEMIES)) }
    // Library drag-and-drop: the part being dragged and the pointer's window position.
    var dragPart by remember { mutableStateOf<MakerPart?>(null) }
    var dragPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val rootCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val canvasBoxCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // Block properties live in the side panel, never floating over the room.
    androidx.compose.runtime.SideEffect { controls.dockTileProperties = true; controls.skipConsumedPresses = true }
    androidx.compose.runtime.SideEffect {
        controls.overlays = MAKER_BASE_LAYERS + (if (solidView) SOLID_VIEW_OVERLAYS else emptySet()) + markers.map { it.overlay }
    }
    val backdropChoice = themeState.appearance.roomBackdrop
    val backdrop = remember(backdropChoice, editorState.tileGraphics) { roomBackdropBrush(backdropChoice, editorState) }
    androidx.compose.runtime.SideEffect { controls.backdrop = backdrop }
    // Space held: drag with any tool to pan. panningNow: a pan drag is under way (closed-hand cursor).
    var spaceHeld by remember { mutableStateOf(false) }
    var panningNow by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<com.supermetroid.editor.ui.maker.MakerClip?>(null) }
    androidx.compose.runtime.SideEffect { controls.wheelZooms = true }
    // Each tool shows its own icon as the mouse cursor over the room; a hand while panning.
    // Read in composition (not inside SideEffect) so a change recomposes and updates the cursor.
    val cursor = when {
        panningNow -> com.supermetroid.editor.ui.maker.makerPanCursor(grabbing = true)
        spaceHeld -> com.supermetroid.editor.ui.maker.makerPanCursor(grabbing = false)
        else -> com.supermetroid.editor.ui.maker.makerCursor(tool, activePart)
    }
    androidx.compose.runtime.SideEffect { controls.cursor = cursor }
    // The room's Start point, drawn as a flag badge on its block.
    val startPoint = editorState.editVersion.let { editorState.makerStartPoint() }
    val startFlag = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString(MakerIcons.START).toPath() }
    // Ghosts at the pointer: where a stamped part lands, and the area a Fill click would flood.
    val ghostPart = activePart?.takeIf { tool == MakerTool.PAINT && it.stamps }
    val fillGhost = tool == MakerTool.FILL && activePart is MakerPart.Tile
    val fillCache = remember { arrayOfNulls<Pair<Triple<Int, Int, Int>, Set<Pair<Int, Int>>>>(1) }
    androidx.compose.runtime.SideEffect {
        controls.drawOverRoom = { scale ->
            startPoint?.let { drawStartPoint(it, scale, startFlag) }
            val hx = editorState.hoverBlockX; val hy = editorState.hoverBlockY
            val block = 16f * scale
            if (hx >= 0 && hy >= 0 && ghostPart != null) {
                val color = if (ghostPart is MakerPart.StartPoint) MakerFixedColors.play else ghostPart.category.color
                val tl = androidx.compose.ui.geometry.Offset(hx * block, hy * block)
                val size = androidx.compose.ui.geometry.Size(block, block)
                drawRect(color.copy(alpha = 0.28f), tl, size)
                drawRect(color, tl, size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1.5f, block * 0.1f)))
            }
            if (hx >= 0 && hy >= 0 && fillGhost) {
                val key = Triple(hx, hy, editorState.editVersion)
                val cells = fillCache[0]?.takeIf { it.first == key }?.second
                    ?: editorState.fillRegion(hx, hy).also { fillCache[0] = key to it }
                val c = MakerFixedColors.terrain.copy(alpha = 0.35f)
                for ((x, y) in cells) drawRect(c, androidx.compose.ui.geometry.Offset(x * block, y * block), androidx.compose.ui.geometry.Size(block, block))
            }
        }
    }
    val areaOf = remember(rooms, romParser) {
        val areas = rooms.associate { it.id to (romParser?.readRoomHeader(it.getRoomIdAsInt())?.area ?: -1) }
        ({ room: RoomInfo? -> areas[room?.id] ?: -1 })
    }

    // The library: everything the game uses (ROM sweep) that passed the in-game check.
    val library = remember(romParser) {
        romParser?.let { EnemySpriteCache.setRomParser(it); runCatching { com.supermetroid.editor.ui.maker.MakerLibrary.build(it) }.getOrNull() }
    }
    val parts = remember(category, editorState.editVersion, editorState.patternVersion, selectedRoom, library, editorState.currentTilesetId) {
        if (category == MakerCategory.DOORS || category == MakerCategory.STATIONS) editorState.seedBuiltInPatterns(romParser)
        makerPartsFor(category, editorState, library, romParser)
    }
    val shownParts = if (search.isBlank()) parts else parts.filter { it.name.contains(search.trim(), ignoreCase = true) }

    // Keep the shared editor tool in step with the Maker tool + part.
    fun applyTool(t: MakerTool, part: MakerPart? = activePart) {
        tool = t
        editorState.activeTool = when {
            t == MakerTool.PAINT && part is MakerPart.AnyTile -> EditorTool.SAMPLE
            t == MakerTool.PAINT && part?.stamps == true -> EditorTool.SELECT // stamped by the click interceptor
            else -> t.editorTool
        }
    }

    /** Picking a part switches to Paint unless the current tool already uses it; [switchTool] false keeps the tool. */
    fun choose(part: MakerPart, switchTool: Boolean = true) {
        if (part is MakerPart.Enemy && part.blocked != null) {
            message = "${part.name} can't go in this room: ${part.blocked}."
            return
        }
        activePart = part
        when (part) {
            is MakerPart.Tile -> if (part.blockType != null) {
                // Paint the block exactly as the game uses it: type, BTS and flips.
                editorState.brush = TileBrush(
                    tiles = listOf(listOf(part.metatile)),
                    blockType = part.blockType,
                    btsOverrides = if (part.bts != 0) mapOf(0L to part.bts) else emptyMap(),
                    flipOverrides = ((if (part.hFlip) 1 else 0) or (if (part.vFlip) 2 else 0)).takeIf { it != 0 }?.let { mapOf(0L to it) } ?: emptyMap(),
                )
            } else editorState.selectMetatile(part.metatile)
            is MakerPart.Pattern -> editorState.selectAndApplyPattern(part.patternId)
            else -> Unit
        }
        val keep = !switchTool || ((tool == MakerTool.FILL || tool == MakerTool.BOX) && part is MakerPart.Tile)
        applyTool(if (keep) tool else MakerTool.PAINT, part)
    }

    /** [dragging]: the pointer moved on with the button held (items and enemies keep stamping; the Start point does not). */
    fun stampAt(bx: Int, by: Int, dragging: Boolean = false) {
        when (val part = activePart) {
            MakerPart.StartPoint -> {
                if (dragging) return
                val current = editorState.makerStartPoint()
                if (current != null && current.x == bx && current.y == by) {
                    editorState.setMakerStartPoint(current.copy(facingLeft = !current.facingLeft), "Maker turn start point")
                    message = "Samus starts facing ${if (current.facingLeft) "right" else "left"}"
                } else {
                    editorState.setMakerStartPoint(MakerStartPoint(bx, by, current?.facingLeft ?: false), "Maker place start point")
                    message = "Samus starts at block $bx, $by. Click it again to turn her around."
                }
                return
            }
            is MakerPart.Item -> editorState.addPlm(part.plmId, bx, by, 0, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
            is MakerPart.Enemy -> {
                if (part.blocked != null) { message = "${part.name} can't go in this room: ${part.blocked}."; return }
                editorState.addEnemy(part.enemyId, bx * 16 + 8, by * 16 + 8, part.initParam, part.properties)
            }
            else -> return
        }
        message = "Placed ${activePart?.name} at block $bx, $by"
    }

    /** Place [part] at one block: used by library drag-and-drop. Selects the part and switches to Paint. */
    fun placePartAt(part: MakerPart, bx: Int, by: Int) {
        choose(part)
        when (part) {
            is MakerPart.Item, is MakerPart.Enemy, MakerPart.StartPoint -> stampAt(bx, by)
            is MakerPart.Tile, is MakerPart.Pattern -> {
                editorState.beginStroke(); editorState.paintAt(bx, by); editorState.endStroke("Maker place ${part.name}")
                message = "Placed ${part.name} at block $bx, $by"
            }
            MakerPart.AnyTile -> Unit
        }
    }

    /** Select tool: move a placed item or enemy to another block (keeps its identity and parameters). */
    fun movePlaced(obj: Any, to: Pair<Int, Int>) {
        when (obj) {
            is MakerStartPoint -> {
                editorState.setMakerStartPoint(obj.copy(x = to.first, y = to.second), "Maker move start point")
                message = "Moved the Start point to block ${to.first}, ${to.second}"
            }
            is RomParser.PlmEntry -> {
                val before = editorState.undoStack.size
                editorState.removePlm(obj.x, obj.y, obj.id, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
                editorState.addPlm(obj.id, to.first, to.second, obj.param, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
                // One Undo reverses the whole move.
                if (editorState.undoStack.size == before + 2) editorState.joinLastUndoStepWithPrevious()
                message = "Moved ${RomParser.plmDisplayName(obj.id)} to block ${to.first}, ${to.second}"
            }
            is RomParser.EnemyEntry -> {
                editorState.updateEnemy(obj, obj.copy(x = to.first * 16 + obj.x % 16, y = to.second * 16 + obj.y % 16))
                message = "Moved ${RomParser.enemyName(obj.id)} to block ${to.first}, ${to.second}"
            }
        }
    }

    /** Select tool: move the selected blocks (and the items, enemies and Start point on them) as one undo step. */
    fun moveBlocks(from: IntArray, dx: Int, dy: Int): IntArray {
        val moved = editorState.moveBox(com.supermetroid.editor.ui.maker.MakerBox(from[0], from[1], from[2], from[3]), dx, dy)
        message = "Moved ${moved.width} × ${moved.height} blocks"
        return intArrayOf(moved.x0, moved.y0, moved.x1, moved.y1)
    }

    /** Box tool: fill (or clear) every block between two corners as one undo step. */
    fun applyBox(a: Pair<Int, Int>, b: Pair<Int, Int>, clear: Boolean) {
        val xs = minOf(a.first, b.first)..maxOf(a.first, b.first)
        val ys = minOf(a.second, b.second)..maxOf(a.second, b.second)
        editorState.beginStroke()
        for (y in ys) for (x in xs) if (clear) editorState.eraseAt(x, y) else editorState.paintAt(x, y)
        editorState.endStroke(if (clear) "Maker box clear" else "Maker box")
        message = "${if (clear) "Cleared" else "Filled"} ${xs.count()} × ${ys.count()} blocks"
    }

    /** Right-click in Paint: remove the item/enemy under the cursor, else erase the block. */
    fun removeAt(bx: Int, by: Int) {
        if (editorState.makerStartPoint() == MakerStartPoint(bx, by)) {
            editorState.setMakerStartPoint(null, "Maker remove start point"); message = "Removed the Start point"; return
        }
        editorState.workingPlms.firstOrNull { it.x == bx && it.y == by && editorState.isEditorItemPlm(it.id) }?.let {
            editorState.removePlm(it.x, it.y, it.id); message = "Removed ${RomParser.plmDisplayName(it.id)}"; return
        }
        editorState.workingEnemies.firstOrNull { it.x / 16 == bx && it.y / 16 == by }?.let {
            editorState.removeEnemy(it); message = "Removed ${RomParser.enemyName(it.id)}"; return
        }
        editorState.eraseAt(bx, by)
    }

    // An empty new room gets its look (solid border, air, plain background) once it has loaded.
    var lookPending by remember { mutableStateOf<Pair<Int, com.supermetroid.editor.ui.maker.MakerNewRoomLook>?>(null) }
    androidx.compose.runtime.LaunchedEffect(lookPending, selectedRoom?.id, editorState.editVersion) {
        val (roomId, look) = lookPending ?: return@LaunchedEffect
        if (editorState.currentRoomId != roomId || editorState.workingLevelData == null) return@LaunchedEffect
        lookPending = null
        com.supermetroid.editor.ui.maker.applyMakerNewRoomLook(editorState, look)
    }
    var newRoomOpen by remember { mutableStateOf(false) }
    var resizeOpen by remember { mutableStateOf(false) }
    // Select's right-click menu: the block and where the pointer was (canvas pixels).
    var blockMenu by remember { mutableStateOf<Pair<Pair<Int, Int>, androidx.compose.ui.geometry.Offset>?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    /** New room next to this one (see [com.supermetroid.editor.ui.maker.planMakerNewRoom]); returns an error, or null. */
    fun newRoom(name: String, width: Int, height: Int, start: com.supermetroid.editor.ui.maker.MakerNewRoomStart): String? {
        val create = onCreateRoom ?: return "Rooms can't be made here."
        val parser = romParser ?: return "Open a project first."
        val plan = com.supermetroid.editor.ui.maker.planMakerNewRoom(editorState, parser, selectedRoom?.getRoomIdAsInt(), width, height, start, name)
            ?: return "No free map space left in ${AREA_NAMES.getOrElse(areaOf(selectedRoom)) { "this area" }} for a ${width}×$height room."
        create(plan.request)?.let { return it }
        val made = editorState.project.newRooms.lastOrNull()
        if (made != null && plan.look != null) lookPending = made.previewRoomId to plan.look
        message = "Made ${plan.request.name}. Paint it, place a Start point (Stations), then Play."
        return null
    }

    /** PLAY. [from] plays once from that block instead of the saved Start point ("Play from here"). */
    fun playHere(from: MakerStartPoint? = null) {
        val parser = romParser ?: return
        val roomId = selectedRoom?.getRoomIdAsInt() ?: return
        val saved = from ?: editorState.makerStartPoint(roomId)
        val start = saved?.let { it.x to it.y }
        var plan = MakerPlayPlan.forRoom(parser, roomId, start, facingLeft = saved?.facingLeft ?: false)
        if (plan == null) {
            message = if (start == null) "No save station or door leads into this room yet. Place a Start point (Stations) so Play can reach it."
            else "There is no save station in this area for Play to boot from."
            return
        }
        // A start point enters through a temporary door added to a play-test copy of the build only.
        if (plan!!.needsTemporaryDoor) emulatorWorkspaceState.playRomTransform = { built ->
            val (copy, door) = com.supermetroid.editor.maker.MakerPlayDoor.writePlayCopy(java.io.File(built), plan!!)
            plan = plan!!.withEntryDoor(door)
            copy.absolutePath
        }
        val l = loadout
        val wasActive = emulatorWorkspaceState.session.active
        val previousFrame = emulatorWorkspaceState.session.frameCounter
        // Build the project and boot a fresh session (no save file), then drive the game into the room.
        onPlay()
        message = "Building and booting…"
        scope.launch {
            var waited = 0
            while (waited < 120_000) {
                val s = emulatorWorkspaceState.session
                if (s.active && emulatorWorkspaceState.isMemoryAccessible &&
                    (!wasActive || s.frameCounter < previousFrame)) break
                delay(100); waited += 100
            }
            if (!emulatorWorkspaceState.isMemoryAccessible) {
                message = "The emulator did not start."
                return@launch
            }
            message = "Entering ${selectedRoom.name} with ${l.name}…"
            val launcher = MakerPlayLauncher(
                read16 = { a -> emulatorWorkspaceState.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) } },
                write16 = { a, v -> emulatorWorkspaceState.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) },
                waitFrames = { frames -> delay(frames * 17L) },
                writeBlock = { a, bytes -> emulatorWorkspaceState.writeMemory(a, bytes) },
            )
            val result = runCatching {
                launcher.launch(plan!!, MakerPlayLoadout(l.items, l.beams, l.energyTanks, l.missiles))
            }.getOrElse { MakerPlayLauncher.Result(false, it.message ?: "Play failed") }
            message = if (result.ok) "Playing ${selectedRoom.name} with ${l.name}" else result.message
        }
    }

    // A Maker room is one layout: edits apply to every state that shares it (no per-state prompt).
    androidx.compose.runtime.LaunchedEffect(selectedRoom?.id, editorState.editVersion) {
        if (editorState.currentLayoutEditingContext()?.requiresScopeChoice == true) {
            editorState.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
    }

    // Start ready to paint with this area's ground.
    androidx.compose.runtime.LaunchedEffect(selectedRoom?.id, parts.isNotEmpty()) {
        if (activePart == null && category == MakerCategory.TERRAIN) parts.firstOrNull()?.let { choose(it, switchTool = false) }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { rootCoords[0] = it }) {
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .onPreviewKeyEvent { e ->
                if (searchFocused) return@onPreviewKeyEvent false
                if (e.key == Key.Spacebar && !playing) { spaceHeld = e.type == KeyEventType.KeyDown; return@onPreviewKeyEvent true }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = e.isCtrlPressed || e.isMetaPressed
                val box = editorState.makerSelection()
                fun showBox(b: com.supermetroid.editor.ui.maker.MakerBox?) {
                    editorState.selectBox(b)
                    if (b != null) controls.openSelectionProperties?.invoke()
                }
                val nudge = when (e.key) { Key.DirectionLeft -> -1 to 0; Key.DirectionRight -> 1 to 0; Key.DirectionUp -> 0 to -1; Key.DirectionDown -> 0 to 1; else -> null }
                when {
                    !playing && mod && e.key == Key.C && box != null -> {
                        clip = editorState.copyBox(box); message = "Copied ${box.width} × ${box.height} blocks"; true
                    }
                    !playing && mod && e.key == Key.V && clip != null -> {
                        val c = clip!!
                        val at = if (editorState.hoverBlockX >= 0) editorState.hoverBlockX to editorState.hoverBlockY
                            else box?.let { it.x0 + 1 to it.y0 + 1 } ?: (0 to 0)
                        editorState.pasteClip(c, at.first, at.second)?.let { showBox(it); message = "Pasted ${c.width} × ${c.height} blocks" }
                            ?: run { message = "That copy is bigger than this room." }
                        true
                    }
                    !playing && mod && e.key == Key.D && box != null -> {
                        val (x, y) = editorState.duplicateSpot(box)
                        editorState.pasteClip(editorState.copyBox(box), x, y, "Maker duplicate")?.let { showBox(it); message = "Duplicated ${box.width} × ${box.height} blocks" }
                        true
                    }
                    !playing && !mod && (e.key == Key.Delete || e.key == Key.Backspace) && box != null -> {
                        editorState.clearBox(box); message = "Cleared ${box.width} × ${box.height} blocks"; true
                    }
                    !playing && !mod && nudge != null && box != null -> {
                        showBox(editorState.moveBox(box, nudge.first, nudge.second)); true
                    }
                    e.key == Key.Escape && playing -> { onStopPlay(); message = "Stopped. Back to editing."; true }
                    e.key == Key.F5 -> { playHere(); true }
                    playing -> false
                    mod && e.key == Key.Z -> { editorState.undo(); true }
                    mod && e.key == Key.Y -> { editorState.redo(); true }
                    !mod -> MakerTool.forKey(e.utf16CodePoint.toChar())?.let { applyTool(it); true } ?: false
                    else -> false
                }
            },
    ) {
        if (playing) PlayBar(
            roomName = { id -> rooms.firstOrNull { it.getRoomIdAsInt() == id }?.name },
            emulatorWorkspaceState = emulatorWorkspaceState,
            onStop = { onStopPlay(); message = "Stopped. Back to editing." },
            onRestart = { playHere() },
        ) else CommandBar(
            rooms = rooms, selectedRoom = selectedRoom, onRoomSelected = onRoomSelected, areaOf = areaOf,
            onUndo = { editorState.undo() }, onRedo = { editorState.redo() },
            themeState = themeState,
            loadout = loadout, onLoadout = { loadout = it },
            onPlay = { playHere() }, onAdvanced = onExit, onAppearance = { appearanceOpen = true },
            onNewRoom = if (onCreateRoom != null && romParser != null) ({ newRoomOpen = true }) else null,
            onRename = if (selectedRoom != null && editorState.project.newRoomForPreviewId(selectedRoom.getRoomIdAsInt()) != null) ({ renameOpen = true }) else null,
            onDelete = if (onDeleteRoom != null && selectedRoom != null && editorState.project.newRoomForPreviewId(selectedRoom.getRoomIdAsInt()) != null) ({ deleteOpen = true }) else null,
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolRail(tool) { applyTool(it) }
            PartsPanel(
                category = category, onCategory = { category = it; search = "" },
                parts = shownParts, active = activePart, onChoose = ::choose,
                onDragStart = { part, windowPos -> dragPart = part; dragPos = windowPos },
                onDragTo = { windowPos ->
                    dragPos = windowPos
                    val target = controls.blockAtWindow?.invoke(dragPos)
                    editorState.mapSelStart = target; editorState.mapSelEnd = target
                },
                onDragEnd = {
                    val part = dragPart
                    val target = controls.blockAtWindow?.invoke(dragPos)
                    editorState.mapSelStart = null; editorState.mapSelEnd = null
                    dragPart = null
                    if (part != null && target != null) placePartAt(part, target.first, target.second)
                },
                search = search, onSearch = { search = it }, onSearchFocus = { searchFocused = it },
                editorState = editorState,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val checks = remember(selectedRoom?.id, romParser, startPoint, editorState.editVersion) {
                    val parser = romParser ?: return@remember emptyList()
                    val room = selectedRoom ?: return@remember emptyList()
                    val names = rooms.associate { it.getRoomIdAsInt() to it.name }
                    val loaded = editorState.currentRoomId == room.getRoomIdAsInt()
                    val blockType = { x: Int, y: Int ->
                        if (x in 0 until editorState.workingBlocksWide && y in 0 until editorState.workingBlocksTall) (editorState.readBlockWord(x, y) shr 12) and 0xF else null
                    }
                    readyChecks(parser, room.getRoomIdAsInt(), startPoint?.let { it.x to it.y }, if (loaded) blockType else null) { names[it] ?: "another room" }
                }
                ViewBar(controls, solidView, { solidView = it }, markers, { markers = it }, checks)
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0C0C18))) {
                    val stamping = tool == MakerTool.PAINT && activePart?.stamps == true
                    val paintTiles = tool == MakerTool.PAINT && activePart is MakerPart.Tile
                    val currentTool = tool
                    val boxTile = activePart is MakerPart.Tile
                    Box(
                        Modifier.fillMaxSize().onGloballyPositioned { canvasBoxCoords[0] = it }.pointerInput(stamping, paintTiles, playing, currentTool, boxTile) {
                            // The block under this event's own position (the editor's hover block lags one event behind).
                            fun blockAt(p: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                                val coords = canvasBoxCoords[0]?.takeIf { it.isAttached }
                                val lookup = controls.blockAtWindow
                                if (coords == null || lookup == null) return editorState.hoverBlockX to editorState.hoverBlockY
                                return lookup(coords.localToWindow(p)) ?: (-1 to -1)
                            }
                            awaitPointerEventScope {
                                var erasing = false
                                var panning = false
                                var boxStart: Pair<Int, Int>? = null
                                var boxClear = false
                                var last = androidx.compose.ui.geometry.Offset.Zero
                                var lastStamp: Pair<Int, Int>? = null
                                var moving: Any? = null
                                var moveFrom: Pair<Int, Int>? = null
                                var selectPress: Pair<Int, Int>? = null
                                var blockMove: IntArray? = null // selected box being dragged (minX, minY, maxX, maxY)
                                var menuPress: Pair<Int, Int>? = null
                                var lineStart: Pair<Int, Int>? = null
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (playing) {
                                        if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
                                        continue
                                    }
                                    val pos = event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero
                                    // Move tool drag, or middle-drag with any tool: pan the room.
                                    if (event.type == PointerEventType.Press && (event.buttons.isTertiaryPressed ||
                                            ((currentTool == MakerTool.MOVE || spaceHeld) && event.buttons.isPrimaryPressed))) {
                                        panning = true; panningNow = true; last = pos; event.changes.forEach { it.consume() }; continue
                                    }
                                    if (panning) {
                                        if (event.type == PointerEventType.Move) { controls.panBy(last.x - pos.x, last.y - pos.y); last = pos }
                                        if (event.type == PointerEventType.Release) { panning = false; panningNow = false }
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }
                                    if (currentTool == MakerTool.BOX && boxTile) {
                                        val (bx, by) = blockAt(pos)
                                        when {
                                            event.type == PointerEventType.Press && bx >= 0 && by >= 0 -> {
                                                boxClear = event.buttons.isSecondaryPressed
                                                boxStart = bx to by
                                                editorState.mapSelStart = bx to by; editorState.mapSelEnd = bx to by
                                                event.changes.forEach { it.consume() }
                                            }
                                            event.type == PointerEventType.Move && boxStart != null && bx >= 0 && by >= 0 -> {
                                                editorState.mapSelEnd = bx to by // live preview; hover keeps tracking
                                            }
                                            event.type == PointerEventType.Release && boxStart != null -> {
                                                val start = boxStart!!
                                                val end = if (bx >= 0 && by >= 0) bx to by else editorState.mapSelEnd ?: start
                                                editorState.mapSelStart = null; editorState.mapSelEnd = null
                                                boxStart = null
                                                applyBox(start, end, boxClear)
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                        continue
                                    }
                                    val (bx, by) = blockAt(pos)
                                    val inRoom = bx >= 0 && by >= 0
                                    if (currentTool == MakerTool.SELECT) {
                                        when {
                                            // Right-click: a small menu (Play from here, Start point here, properties).
                                            event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && inRoom -> {
                                                menuPress = bx to by; event.changes.forEach { it.consume() }
                                            }
                                            menuPress != null && event.type == PointerEventType.Release -> {
                                                blockMenu = menuPress!! to pos; menuPress = null
                                                event.changes.forEach { it.consume() }
                                            }
                                            event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && inRoom &&
                                                event.keyboardModifiers.isShiftPressed && editorState.mapSelStart != null -> {
                                                // Shift-click: grow the box to this block.
                                                editorState.mapSelEnd = bx to by
                                                controls.openSelectionProperties?.invoke()
                                                event.changes.forEach { it.consume() }
                                            }
                                            event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && inRoom -> {
                                                val obj: Any? = editorState.makerStartPoint()?.takeIf { it.x == bx && it.y == by }
                                                    ?: editorState.workingPlms.firstOrNull { it.x == bx && it.y == by && editorState.isEditorItemPlm(it.id) }
                                                    ?: editorState.workingEnemies.firstOrNull { it.x / 16 == bx && it.y / 16 == by }
                                                val ss = editorState.mapSelStart; val se = editorState.mapSelEnd
                                                val sel = if (ss != null && se != null) intArrayOf(minOf(ss.first, se.first), minOf(ss.second, se.second),
                                                    maxOf(ss.first, se.first), maxOf(ss.second, se.second)) else null
                                                if (obj != null) {
                                                    moving = obj; moveFrom = bx to by
                                                    editorState.mapSelStart = bx to by; editorState.mapSelEnd = bx to by
                                                    event.changes.forEach { it.consume() }
                                                } else if (sel != null && bx in sel[0]..sel[2] && by in sel[1]..sel[3]) {
                                                    // Press inside the selected box: drag moves its blocks in one gesture.
                                                    blockMove = sel; moveFrom = bx to by
                                                    event.changes.forEach { it.consume() }
                                                } else selectPress = bx to by // the canvas draws the selection box; properties open on release
                                            }
                                            blockMove != null && event.type == PointerEventType.Move && inRoom -> {
                                                val b = blockMove!!; val from = moveFrom!!
                                                val dx = (bx - from.first).coerceIn(-b[0], editorState.workingBlocksWide - 1 - b[2])
                                                val dy = (by - from.second).coerceIn(-b[1], editorState.workingBlocksTall - 1 - b[3])
                                                editorState.mapSelStart = b[0] + dx to b[1] + dy; editorState.mapSelEnd = b[2] + dx to b[3] + dy
                                                event.changes.forEach { it.consume() }
                                            }
                                            blockMove != null && event.type == PointerEventType.Release -> {
                                                val b = blockMove!!; val from = moveFrom!!
                                                val to = if (inRoom) bx to by else from
                                                if (to == from) {
                                                    editorState.mapSelStart = b[0] to b[1]; editorState.mapSelEnd = b[2] to b[3]
                                                    controls.openTilePropertiesAt?.invoke(from.first, from.second)
                                                } else {
                                                    val moved = moveBlocks(b, to.first - from.first, to.second - from.second)
                                                    editorState.mapSelStart = moved[0] to moved[1]; editorState.mapSelEnd = moved[2] to moved[3]
                                                    controls.openSelectionProperties?.invoke()
                                                }
                                                blockMove = null; moveFrom = null
                                                event.changes.forEach { it.consume() }
                                            }
                                            moving != null && event.type == PointerEventType.Move && inRoom -> {
                                                editorState.mapSelStart = bx to by; editorState.mapSelEnd = bx to by
                                            }
                                            moving != null && event.type == PointerEventType.Release -> {
                                                val from = moveFrom!!
                                                val to = if (inRoom) bx to by else from
                                                editorState.mapSelStart = null; editorState.mapSelEnd = null
                                                if (to != from) movePlaced(moving!!, to) else controls.openTilePropertiesAt?.invoke(from.first, from.second)
                                                moving = null; moveFrom = null
                                                event.changes.forEach { it.consume() }
                                            }
                                            selectPress != null && event.type == PointerEventType.Release -> {
                                                if (inRoom && (bx to by) == selectPress) controls.openTilePropertiesAt?.invoke(bx, by)
                                                selectPress = null
                                            }
                                        }
                                        continue
                                    }
                                    // Paint with Shift held: a straight line, previewed while dragging and painted on release.
                                    if (paintTiles && event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && inRoom &&
                                        event.keyboardModifiers.isShiftPressed) {
                                        lineStart = bx to by
                                        editorState.mapSelStart = bx to by; editorState.mapSelEnd = bx to by
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    if (lineStart != null) {
                                        val line = com.supermetroid.editor.ui.maker.straightLine(lineStart!!, if (inRoom) bx to by else lineStart!!)
                                        if (event.type == PointerEventType.Move && inRoom) { editorState.mapSelStart = line.x0 to line.y0; editorState.mapSelEnd = line.x1 to line.y1 }
                                        if (event.type == PointerEventType.Release) {
                                            val l = com.supermetroid.editor.ui.maker.MakerBox.of(editorState.mapSelStart ?: lineStart!!, editorState.mapSelEnd ?: lineStart!!)
                                            editorState.mapSelStart = null; editorState.mapSelEnd = null; lineStart = null
                                            editorState.beginStroke()
                                            for (y in l.y0..l.y1) for (x in l.x0..l.x1) editorState.paintAt(x, y)
                                            editorState.endStroke("Maker line")
                                            message = "Painted a line of ${l.width * l.height} blocks"
                                        }
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    when {
                                        stamping && event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && inRoom -> {
                                            stampAt(bx, by); lastStamp = bx to by; event.changes.forEach { it.consume() }
                                        }
                                        // Drag-painting items/enemies: place one on each new block the pointer reaches.
                                        stamping && lastStamp != null && event.type == PointerEventType.Move && inRoom -> {
                                            if ((bx to by) != lastStamp) { stampAt(bx, by, dragging = true); lastStamp = bx to by }
                                            event.changes.forEach { it.consume() }
                                        }
                                        stamping && lastStamp != null && event.type == PointerEventType.Release -> {
                                            if (inRoom && (bx to by) != lastStamp) stampAt(bx, by, dragging = true)
                                            lastStamp = null; event.changes.forEach { it.consume() }
                                        }
                                        (stamping || paintTiles) && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && inRoom -> {
                                            erasing = !stamping
                                            if (erasing) editorState.beginStroke()
                                            removeAt(bx, by); event.changes.forEach { it.consume() }
                                        }
                                        erasing && event.type == PointerEventType.Move && inRoom -> {
                                            editorState.eraseAt(bx, by); event.changes.forEach { it.consume() }
                                        }
                                        erasing && event.type == PointerEventType.Release -> {
                                            erasing = false; editorState.endStroke("Maker erase"); event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        MapCanvas(
                            room = selectedRoom,
                            romParser = romParser,
                            editorState = editorState,
                            rooms = rooms,
                            onRoomSelected = onRoomSelected,
                            onWorkspaceChanged = onWorkspaceChanged,
                            controls = controls,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    blockMenu?.let { (block, at) ->
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.toInt(), at.y.toInt()) }) {
                            DropdownMenu(expanded = true, onDismissRequest = { blockMenu = null }) {
                                DropdownMenuItem(text = { Text("Play from here", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }, onClick = {
                                    blockMenu = null
                                    playHere(MakerStartPoint(block.first, block.second, editorState.makerStartPoint()?.facingLeft ?: false))
                                })
                                DropdownMenuItem(text = { Text("Put the Start point here", fontSize = 13.sp) }, onClick = {
                                    blockMenu = null
                                    editorState.setMakerStartPoint(MakerStartPoint(block.first, block.second, editorState.makerStartPoint()?.facingLeft ?: false), "Maker place start point")
                                    message = "Samus starts at block ${block.first}, ${block.second}"
                                })
                                DropdownMenuItem(text = { Text("Block properties", fontSize = 13.sp) }, onClick = {
                                    blockMenu = null; controls.openTilePropertiesAt?.invoke(block.first, block.second)
                                })
                            }
                        }
                        if (density.density < 0f) Unit
                    }
                    ZoomControls(controls, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                    if (solidView) SolidLegend(Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                if (playing) PlayingHint() else if (themeState.appearance.showHintBar) HintBar(tool, activePart)
            }
            Inspector(editorState, romParser, selectedRoom, areaOf(selectedRoom), activePart, controls, onAdvanced = onExit,
                onRoomLook = if (library != null && romParser != null) ({ roomLookOpen = true }) else null,
                onResize = if (romParser != null) ({ resizeOpen = true }) else null)
        }
        StatusBar(editorState, message)
    }
    if (appearanceOpen) com.supermetroid.editor.ui.maker.MakerAppearancePanel(themeState) { appearanceOpen = false }
    if (resizeOpen && romParser != null) com.supermetroid.editor.ui.maker.MakerResizeRoomPanel(
        size = (editorState.workingBlocksWide / 16).coerceAtLeast(1) to (editorState.workingBlocksTall / 16).coerceAtLeast(1),
        onResize = { side, grow ->
            editorState.resizeMakerRoom(romParser, side, grow)
                .also { if (it == null) { onWorkspaceChanged(); message = "${if (grow) "Added" else "Removed"} a screen on the ${side.label.lowercase()}" } }
        },
        onClose = { resizeOpen = false },
    )
    if (newRoomOpen) com.supermetroid.editor.ui.maker.MakerNewRoomPanel(
        defaultName = "New room ${editorState.project.newRooms.size + 1}", currentRoomName = selectedRoom?.name,
        onCreate = ::newRoom, onClose = { newRoomOpen = false },
    )
    if (renameOpen && selectedRoom != null) com.supermetroid.editor.ui.maker.MakerRenameRoomPanel(selectedRoom.name, onRename = { name ->
        runCatching { editorState.renameProjectRoom(selectedRoom.getRoomIdAsInt(), name); onWorkspaceChanged() }.exceptionOrNull()?.message
            .also { if (it == null) message = "Renamed to ${name.trim()}" }
    }, onClose = { renameOpen = false })
    if (deleteOpen && selectedRoom != null && onDeleteRoom != null) com.supermetroid.editor.ui.maker.MakerDeleteRoomPanel(selectedRoom.name, onDelete = {
        val name = selectedRoom.name
        val roomId = selectedRoom.getRoomIdAsInt()
        val key = editorState.project.roomDestination(roomId)
        onDeleteRoom(roomId).also { if (it == null) { editorState.project.makerStartPoints.remove(key); message = "Deleted $name" } }
    }, onClose = { deleteOpen = false })
    if (roomLookOpen && library != null && romParser != null) {
        com.supermetroid.editor.ui.maker.MakerRoomLookPanel(editorState, romParser, library, rooms.associate { it.getRoomIdAsInt() to it.name },
            onApplied = { message = it }, onClose = { roomLookOpen = false })
    }
    dragPart?.let { part ->
        val local = rootCoords[0]?.windowToLocal(dragPos) ?: dragPos
        val density = androidx.compose.ui.platform.LocalDensity.current
        Row(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(local.x.toInt() + 12, local.y.toInt() + 12) }
                .background(p.raised.copy(alpha = 0.95f), RoundedCornerShape(8.dp)).border(2.dp, part.category.color, RoundedCornerShape(8.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PartPreview(part, editorState, Modifier.size(40.dp))
            Text(if (controls.blockAtWindow?.invoke(dragPos) != null) "Drop to place ${part.name}" else part.name,
                color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (density.density < 0f) Unit
    }
    }
}

@Composable
private fun PlayBar(
    roomName: (Int) -> String?,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val p = LocalMakerPalette.current
    val snap = emulatorWorkspaceState.snapshot
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(p.panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.background(MakerFixedColors.play, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(MakerFixedColors.onPlay, RoundedCornerShape(4.dp)))
            Text("PLAYING", color = MakerFixedColors.onPlay, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        MakerButton(onClick = onStop, selected = true, height = 50.dp) { fg ->
            MakerIcon("M5 5h14v14H5z", fg, 20.dp, filled = true)
            Text("STOP & EDIT", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            KeyCap("Esc", fg)
        }
        MakerButton(onClick = onRestart) { fg ->
            MakerIcon("M20 12a8 8 0 11-2.3-5.6M20 4v4h-4", fg, 20.dp)
            Text("Restart room", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            KeyCap("F5", fg)
        }
        val running = emulatorWorkspaceState.isRunning
        MakerButton(onClick = { emulatorWorkspaceState.setLoopRunning(!running) }) { fg ->
            if (running) MakerIcon("M6 5h4v14H6zM14 5h4v14h-4z", fg, 20.dp, filled = true) else MakerIcon(MakerIcons.PLAY_FILLED, fg, 20.dp, filled = true)
            Text(if (running) "Pause" else "Resume", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        LiveChip("Room", snap?.roomId?.let { roomName(it) } ?: "—")
        LiveChip("Energy", snap?.health?.let { h -> "$h/${snap.maxHealth ?: h}" } ?: "—", mono = true)
        LiveChip("Missiles", snap?.missiles?.toString() ?: "—", mono = true)
    }
}

@Composable
private fun LiveChip(label: String, value: String, mono: Boolean = false) {
    val p = LocalMakerPalette.current
    Column(
        Modifier.background(p.raised, RoundedCornerShape(7.dp)).border(1.dp, p.border, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label.uppercase(), color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun PlayingHint() {
    val p = LocalMakerPalette.current
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(MakerFixedColors.play.copy(alpha = 0.25f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MakerIcon(MakerIcons.PLAY_FILLED, p.text, 18.dp, filled = true)
        Text("Playing. Editing is paused. Press Esc or STOP & EDIT to change the room; playing never changes it.", color = p.text, fontSize = 14.sp)
    }
}

@Composable
private fun CommandBar(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    onRoomSelected: (RoomInfo) -> Unit,
    areaOf: (RoomInfo?) -> Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    themeState: MakerThemeState,
    loadout: MakerLoadout,
    onLoadout: (MakerLoadout) -> Unit,
    onPlay: () -> Unit,
    onAdvanced: () -> Unit,
    onAppearance: () -> Unit,
    onNewRoom: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val p = LocalMakerPalette.current
    var roomMenu by remember { mutableStateOf(false) }
    var loadoutMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(p.panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            MakerButton(onClick = { roomMenu = true }, modifier = Modifier.width(260.dp), height = 52.dp) { fg ->
                Box(Modifier.size(28.dp).background(MakerFixedColors.terrain.copy(alpha = 0.35f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    MakerIcon(MakerIcons.ROOM, fg, 18.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("ROOM · ${AREA_NAMES.getOrElse(areaOf(selectedRoom)) { "" }.uppercase()}", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(selectedRoom?.name ?: "Pick a room", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MakerIcon(MakerIcons.CHEVRON, fg, 16.dp)
            }
            DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }, modifier = Modifier.heightIn(max = 520.dp)) {
                if (onNewRoom != null) DropdownMenuItem(
                    text = { Text("+ New room", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = { roomMenu = false; onNewRoom() },
                )
                if (onRename != null) DropdownMenuItem(
                    text = { Text("Rename this room…", fontSize = 13.sp) },
                    onClick = { roomMenu = false; onRename() },
                )
                if (onDelete != null) DropdownMenuItem(
                    text = { Text("Delete this room…", fontSize = 13.sp, color = MakerFixedColors.danger) },
                    onClick = { roomMenu = false; onDelete() },
                )
                rooms.forEach { room ->
                    DropdownMenuItem(
                        text = { Text("${AREA_NAMES.getOrElse(areaOf(room)) { "?" }} · ${room.name}", fontSize = 13.sp) },
                        onClick = { roomMenu = false; onRoomSelected(room) },
                    )
                }
            }
        }
        if (onNewRoom != null) MakerButton(onClick = onNewRoom) { fg ->
            MakerIcon(MakerIcons.NEW_ROOM, fg, 20.dp); Text("New room", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Divider()
        MakerButton(onClick = onUndo) { fg -> MakerIcon(MakerIcons.UNDO, fg, 20.dp); Text("Undo", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        MakerButton(onClick = onRedo) { fg -> MakerIcon(MakerIcons.REDO, fg, 20.dp); Text("Redo", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        Divider()
        Spacer(Modifier.weight(1f))
        Box {
            MakerButton(onClick = { themeMenu = true }) { fg ->
                Box(Modifier.size(16.dp).background(p.accent, RoundedCornerShape(8.dp)).border(1.dp, p.accentDeep, RoundedCornerShape(8.dp)))
                Text("${themeState.currentSpec.name} · ${themeState.appearance.brightness.name.lowercase().replaceFirstChar { it.uppercase() }}", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                themeState.themes.forEach { spec ->
                    DropdownMenuItem(
                        text = { Text((if (spec.id == themeState.currentSpec.id) "● " else "   ") + spec.name, fontSize = 13.sp) },
                        onClick = { themeState.update { it.copy(themeId = spec.id) } },
                    )
                }
                MakerBrightness.entries.forEach { b ->
                    DropdownMenuItem(
                        text = { Text((if (b == themeState.appearance.brightness) "● " else "   ") + when (b) { MakerBrightness.LIGHT -> "Light"; MakerBrightness.DARK -> "Dark"; MakerBrightness.SYSTEM -> "Follow system" }, fontSize = 13.sp) },
                        onClick = { themeState.update { it.copy(brightness = b) } },
                    )
                }
                DropdownMenuItem(text = { Text("   Appearance settings…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }, onClick = { themeMenu = false; onAppearance() })
                RoomBackdrop.entries.forEach { b ->
                    DropdownMenuItem(
                        text = { Text((if (b == themeState.appearance.roomBackdrop) "● " else "   ") + "Room backdrop: " + b.label, fontSize = 13.sp) },
                        onClick = { themeState.update { it.copy(roomBackdrop = b) } },
                    )
                }
            }
        }
        Box {
            MakerButton(onClick = { loadoutMenu = true }) { fg ->
                Text("LOADOUT", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(loadout.name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                KeyCap("E×${loadout.energyTanks}", fg)
                KeyCap("M×${loadout.missiles}", fg)
            }
            DropdownMenu(expanded = loadoutMenu, onDismissRequest = { loadoutMenu = false }) {
                MAKER_LOADOUTS.forEach { option ->
                    DropdownMenuItem(text = { Text(option.name, fontSize = 13.sp) }, onClick = { loadoutMenu = false; onLoadout(option) })
                }
            }
        }
        MakerButton(onClick = onPlay, fill = MakerFixedColors.play, onFill = MakerFixedColors.onPlay, height = 50.dp) { fg ->
            MakerIcon(MakerIcons.PLAY_FILLED, fg, 22.dp, filled = true)
            Text("PLAY", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            KeyCap("F5", fg)
        }
        MakerButton(onClick = onAdvanced) { fg -> MakerIcon(MakerIcons.ADVANCED, fg, 18.dp); Text("Advanced", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Divider() {
    val p = LocalMakerPalette.current
    Box(Modifier.width(1.dp).height(32.dp).background(p.border))
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ToolRail(current: MakerTool, onPick: (MakerTool) -> Unit) {
    val p = LocalMakerPalette.current
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(p.panel).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var lastGroup = 0
        MakerTool.entries.forEach { t ->
            if (t.group != lastGroup) { Box(Modifier.width(48.dp).height(1.dp).background(p.border)); lastGroup = t.group }
            val on = t == current
            androidx.compose.foundation.TooltipArea(
                tooltip = {
                    Row(Modifier.background(p.raised, RoundedCornerShape(6.dp)).border(1.dp, p.border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.label, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        KeyCap(t.key.toString(), p.text)
                        Text(makerHint(t, null).substringBefore(". ").removeSuffix("."), color = p.muted, fontSize = 12.sp)
                    }
                },
                delayMillis = 400,
                tooltipPlacement = androidx.compose.foundation.TooltipPlacement.ComponentRect(anchor = Alignment.CenterEnd, alignment = Alignment.CenterEnd,
                    offset = androidx.compose.ui.unit.DpOffset(8.dp, 0.dp)),
            ) {
            Box(
                Modifier.size(width = 70.dp, height = 64.dp)
                    .background(if (on) p.accent else p.raised, RoundedCornerShape(8.dp))
                    .border(1.dp, if (on) p.accentDeep else p.border, RoundedCornerShape(8.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(t) },
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (on) p.onAccent else p.text
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MakerIcon(t.icon, fg, 26.dp)
                    Text(t.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(t.key.toString(), color = fg.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 6.dp))
            }
            }
        }
    }
}

@Composable
private fun PartsPanel(
    category: MakerCategory,
    onCategory: (MakerCategory) -> Unit,
    parts: List<MakerPart>,
    active: MakerPart?,
    onChoose: (MakerPart) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    editorState: EditorState,
    onDragStart: (MakerPart, androidx.compose.ui.geometry.Offset) -> Unit = { _, _ -> },
    onDragTo: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val p = LocalMakerPalette.current
    Column(Modifier.width(304.dp).fillMaxHeight().background(p.background).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Parts", Modifier.weight(1f))
            Text("Classic-ready only", color = p.muted, fontSize = 12.sp)
        }
        // Category tabs: colour + icon + label (meaning fixed across themes).
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MakerCategory.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        val on = c == category
                        Column(
                            Modifier.weight(1f).height(56.dp)
                                .background(if (on) c.color else p.raised, RoundedCornerShape(7.dp))
                                .border(2.dp, if (on) c.color.copy(alpha = 1f).darker() else p.border, RoundedCornerShape(7.dp))
                                .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onCategory(c) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val fg = if (on) Color(0xFF252B30) else p.text
                            MakerIcon(c.icon, fg, 20.dp)
                            Text(c.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(p.inset, RoundedCornerShape(7.dp)).border(1.dp, p.border, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MakerIcon(MakerIcons.SEARCH, p.muted, 16.dp)
            Box(Modifier.weight(1f)) {
                if (search.isEmpty()) Text("Search ${category.label.lowercase()}…", color = p.muted, fontSize = 14.sp)
                BasicTextField(search, onSearch, singleLine = true, textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.text),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onSearchFocus(it.isFocused) })
            }
        }
        Text("Previews use the game's real tiles and colours", color = p.muted, fontSize = 11.sp)
        if (parts.isEmpty()) Text(category.emptyHint.ifBlank { "Nothing matches." }, color = p.muted, fontSize = 13.sp)
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(parts, key = { it.id }) { part ->
                val on = active?.id == part.id
                val cardCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                val blocked = (part as? MakerPart.Enemy)?.blocked != null
                Column(
                    Modifier.height(108.dp)
                        .alpha(if (blocked) 0.45f else 1f)
                        .onGloballyPositioned { cardCoords[0] = it }
                        // Drag a card onto the room to place it there; a plain click selects it.
                        .pointerInput(part.id) {
                            detectDragGestures(
                                onDragStart = { o -> cardCoords[0]?.let { onDragStart(part, it.localToWindow(o)) } },
                                onDrag = { change, _ -> change.consume(); cardCoords[0]?.let { onDragTo(it.localToWindow(change.position)) } },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                            )
                        }
                        .background(p.raised, RoundedCornerShape(8.dp))
                        .border(2.dp, if (on) part.category.color.darker() else p.border, RoundedCornerShape(8.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onChoose(part) }
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PartPreview(part, editorState, Modifier.fillMaxWidth().height(58.dp))
                    Text(part.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(part.hint, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Game art for a part, from the ROM, nearest-neighbour, never themed. Abstract parts get a MODRET icon. */
@Composable
private fun PartPreview(part: MakerPart, editorState: EditorState, modifier: Modifier) {
    val bitmap: ImageBitmap? = remember(part.id, editorState.tileGraphics) {
        when (part) {
            is MakerPart.Tile -> editorState.tileGraphics?.renderMetatile(part.metatile)?.let { px ->
                val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 16) for (x in 0 until 16) {
                    val sx = if (part.hFlip) 15 - x else x
                    val sy = if (part.vFlip) 15 - y else y
                    img.setRGB(x, y, px[sy * 16 + sx])
                }
                img.toComposeImageBitmap()
            }
            is MakerPart.Item -> RomParser.ITEM_DEFS.firstOrNull { part.plmId in setOf(it.visibleId, it.chozoId, it.hiddenId) }
                ?.let { ROOM_ITEM_SPRITE_COORDS[it.name] }
                ?.let { c -> ItemSpriteSheetCache.get()?.getSubimage(c.x, c.y, 16, 16)?.toComposeImageBitmap() }
            is MakerPart.Enemy -> EnemySpriteCache.get("%04X".format(part.enemyId), part.initParam)?.toComposeImageBitmap()
            // Doors and stations: the pattern's real metatiles from this room's tileset.
            is MakerPart.Pattern -> editorState.project.patterns.firstOrNull { it.id == part.patternId }
                ?.let { renderPatternThumbnail(it, editorState.tileGraphics) }?.toComposeImageBitmap()
            else -> null
        }
    }
    Box(modifier.background(Color(0xFF0C0C18), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = part.name, filterQuality = FilterQuality.None, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(5.dp))
        } else {
            when (part) {
                MakerPart.AnyTile -> MakerIcon(MakerIcons.PICK, part.category.color, 30.dp)
                MakerPart.StartPoint -> MakerIcon(MakerIcons.START, MakerFixedColors.play, 30.dp, strokeWidth = 2.4f)
                else -> MakerIcon(part.category.icon, part.category.color, 30.dp)
            }
        }
        // Item variants: a small shape badge (orb = Chozo ball, dashed square = hidden in a block).
        val variant = (part as? MakerPart.Item)?.let { item ->
            RomParser.ITEM_DEFS.firstOrNull { it.chozoId == item.plmId }?.let { "orb" } ?: RomParser.ITEM_DEFS.firstOrNull { it.hiddenId == item.plmId }?.let { "hidden" }
        }
        if (variant != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(16.dp).background(Color(0xE6252B30), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                MakerIcon(if (variant == "orb") "M12 4a8 8 0 100 16 8 8 0 000-16z" else "M5 5h4M15 5h4M5 19h4M15 19h4M5 5v4M5 15v4M19 5v4M19 15v4",
                    Color(0xFFF2EBDD), 12.dp, strokeWidth = 2.6f)
            }
        }
    }
}

/** Start point badge: a dark rounded square over the block with the flag in Play green. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStartPoint(
    sp: MakerStartPoint, px: Float, flag: androidx.compose.ui.graphics.Path,
) {
    // Never smaller than 22 canvas pixels, so it stays findable at fit-to-room zoom; centred on its block.
    val block = maxOf(16f * px, 22f)
    val mirror = sp.facingLeft
    val centre = androidx.compose.ui.geometry.Offset((sp.x + 0.5f) * 16f * px, (sp.y + 0.5f) * 16f * px)
    val topLeft = centre - androidx.compose.ui.geometry.Offset(block / 2, block / 2)
    val size = androidx.compose.ui.geometry.Size(block, block)
    val radius = androidx.compose.ui.geometry.CornerRadius(block * 0.2f)
    drawRoundRect(Color(0xE6182226), topLeft, size, radius)
    drawRoundRect(MakerFixedColors.play, topLeft, size, radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1.5f, block * 0.08f)))
    val s = block / 24f
    translate(topLeft.x + (if (mirror) block else 0f), topLeft.y) {
        scale(if (mirror) -s else s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(flag, MakerFixedColors.play, style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun ZoomControls(controls: MapCanvasControls, modifier: Modifier) {
    val p = LocalMakerPalette.current
    Row(
        modifier.background(p.panel, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MakerButton(onClick = { controls.zoomBy(1f / 1.25f) }, height = 36.dp) { fg -> Text("−", color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        Text("${(controls.zoom.value * 100).toInt()}%", color = p.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
        MakerButton(onClick = { controls.zoomBy(1.25f) }, height = 36.dp) { fg -> Text("+", color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        MakerButton(onClick = { controls.fit() }, height = 36.dp, enabled = controls.fitZoom != null) { fg -> Text("Fit room", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun HintBar(tool: MakerTool, part: MakerPart?) {
    val p = LocalMakerPalette.current
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(p.raised).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(26.dp).background(p.accent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { MakerIcon(tool.icon, p.onAccent, 16.dp) }
        Text(tool.label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(makerHint(tool, part), color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        com.supermetroid.editor.ui.maker.makerMouseLegend(tool, part).forEach { (button, action) ->
            Text(button, color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(action, color = p.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Inspector(
    editorState: EditorState, romParser: RomParser?, room: RoomInfo?, area: Int, part: MakerPart?,
    controls: MapCanvasControls, onAdvanced: () -> Unit, onRoomLook: (() -> Unit)? = null,
    onResize: (() -> Unit)? = null,
) {
    val p = LocalMakerPalette.current
    val docked = controls.dockedPanel
    Column(
        Modifier.width(340.dp).fillMaxHeight().background(p.panel).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("Selected part", Modifier.padding(horizontal = 6.dp))
        Row(
            Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (part != null) {
                PartPreview(part, editorState, Modifier.size(48.dp))
                Column {
                    Text(part.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${part.category.label} · ${part.hint}", color = p.muted, fontSize = 12.sp)
                }
            } else {
                Text("Choose a part from the library to start building.", color = p.muted, fontSize = 13.sp)
            }
        }
        SectionLabel(if (docked != null) "Block properties" else "Under the cursor", Modifier.padding(horizontal = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (docked != null) {
                // SMEDIT's block-properties panel, docked here (its close button returns to this summary).
                docked(Modifier.fillMaxWidth())
            } else Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val bx = editorState.hoverBlockX
                val by = editorState.hoverBlockY
                if (bx >= 0 && by >= 0) {
                    val word = editorState.readBlockWord(bx, by)
                    InfoGrid(listOf("Block" to "x $bx  y $by", "Type" to blockTypeName(word), "Tile" to "0x%03X".format(word and 0x3FF)), mono = setOf("Block", "Tile"))
                } else {
                    Text("Point at the room to see a block's position and type.", color = p.muted, fontSize = 13.sp)
                }
                Text("With Select (V), click a block to see and edit its properties here, or drag an item or enemy to move it.",
                    color = p.muted, fontSize = 12.sp)
            }
        }
        SectionLabel("Room", Modifier.padding(horizontal = 6.dp))
        if (room != null) {
            val roomId = room.getRoomIdAsInt()
            val doorsIn = remember(roomId, romParser) { romParser?.findDoorsLeadingTo(roomId)?.size ?: 0 }
            Box(Modifier.padding(horizontal = 6.dp)) {
                InfoGrid(listOf(
                    "Size" to "${editorState.workingBlocksWide / 16} × ${editorState.workingBlocksTall / 16} screens",
                    "Area" to AREA_NAMES.getOrElse(area) { "?" },
                    "Doors" to "${editorState.doorEntries.size} out · $doorsIn lead in",
                ), mono = setOf("Size"))
            }
        }
        if (onResize != null && room != null) MakerButton(onClick = onResize, modifier = Modifier.fillMaxWidth(), height = 40.dp) { fg ->
            MakerIcon(MakerIcons.ROOM, fg, 18.dp); Text("Resize room", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (onRoomLook != null) MakerButton(onClick = onRoomLook, modifier = Modifier.fillMaxWidth(), height = 40.dp) { fg ->
            MakerIcon(MakerIcons.THEME, fg, 18.dp); Text("Room look: tileset & effects", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Advanced room settings ›", color = p.accentDeep.takeIf { !p.isDark } ?: p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onAdvanced).padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

/** One fixed bar above the room: view toggles on the left, "Ready to play?" checks on the right. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ViewBar(
    controls: MapCanvasControls, solidView: Boolean, onSolidView: (Boolean) -> Unit,
    markers: Set<MakerMarker>, onMarkers: (Set<MakerMarker>) -> Unit, checks: List<ReadyCheck>,
) {
    val p = LocalMakerPalette.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(p.panel).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MakerButton(onClick = { controls.showGrid = !controls.showGrid }, selected = controls.showGrid, height = 36.dp) { fg ->
            MakerIcon(MakerIcons.GRID, fg, 16.dp); Text("Grid", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        MakerButton(onClick = { onSolidView(!solidView) }, selected = solidView, height = 36.dp) { fg ->
            MakerIcon(MakerIcons.SOLID, fg, 16.dp); Text("What's solid?", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        MakerMarker.entries.forEach { m ->
            val on = m in markers
            androidx.compose.foundation.TooltipArea(tooltip = { TooltipText("Show ${m.label.lowercase()}") }) {
                MakerButton(onClick = { onMarkers(if (on) markers - m else markers + m) }, selected = on, height = 36.dp,
                    modifier = Modifier.semantics { contentDescription = "Show ${m.label.lowercase()}" }) { fg ->
                    MakerIcon(m.icon, fg, 16.dp)
                    Box(Modifier.size(8.dp).background(Color(m.overlay.color), RoundedCornerShape(2.dp)))
                }
            }
        }
        // Ready checks sit at the right and scroll sideways on narrow windows instead of being clipped.
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            if (checks.isNotEmpty()) {
                val todo = checks.count { !it.ok }
                Text(if (todo == 0) "READY" else "$todo TO FIX", color = if (todo == 0) MakerFixedColors.ok else MakerFixedColors.warning,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 6.dp))
                checks.forEach { ReadyChip(it) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReadyChip(c: ReadyCheck) {
    val p = LocalMakerPalette.current
    var open by remember { mutableStateOf(false) }
    val warnBg = if (p.isDark) Color(0xFF3A3220) else Color(0xFFF6E6C4)
    Box {
        androidx.compose.foundation.TooltipArea(tooltip = { TooltipText(c.title + ": " + c.detail) }) {
            Row(
                Modifier.height(30.dp)
                    .background(if (c.ok) p.raised else warnBg, RoundedCornerShape(15.dp))
                    .border(1.dp, if (c.ok) p.border else MakerFixedColors.hazards, RoundedCornerShape(15.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
                    .semantics { contentDescription = "${c.title}: ${c.detail}" }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MakerIcon(if (c.ok) MakerIcons.CHECK else MakerIcons.WARN, if (c.ok) MakerFixedColors.ok else MakerFixedColors.warning, 14.dp, strokeWidth = 2.8f)
                Text(c.short, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).width(260.dp)) {
                Text(c.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(c.detail, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TooltipText(text: String) {
    Box(Modifier.background(Color(0xF0252B30), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(text, color = Color(0xFFF2EBDD), fontSize = 12.sp)
    }
}

@Composable
private fun InfoGrid(rows: List<Pair<String, String>>, mono: Set<String> = emptySet()) {
    val p = LocalMakerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, color = p.muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                Text(v, color = p.text, fontSize = 13.sp, fontFamily = if (k in mono) FontFamily.Monospace else null)
            }
        }
    }
}

@Composable
private fun StatusBar(editorState: EditorState, message: String?) {
    val p = LocalMakerPalette.current
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(p.panel).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val bx = editorState.hoverBlockX
        val by = editorState.hoverBlockY
        Text(if (bx >= 0 && by >= 0) "x $bx  y $by · ${blockTypeName(editorState.readBlockWord(bx, by))}" else "—",
            color = p.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MakerIcon(MakerIcons.CHECK, MakerFixedColors.ok, 14.dp, strokeWidth = 2.6f)
            Text("Every Maker part works in Classic", color = p.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        message?.let { Text(it, color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun Color.darker(f: Float = 0.72f) = Color(red * f, green * f, blue * f, alpha)


@Composable
private fun SolidLegend(modifier: Modifier) {
    Row(
        modifier.background(Color(0xE6252B30), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(TileOverlay.SOLID to "Solid", TileOverlay.SLOPE to "Slope", TileOverlay.SPIKE to "Hurts", TileOverlay.DOOR to "Door",
            TileOverlay.CRUMBLE to "Breakable").forEach { (o, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).background(Color(o.color), RoundedCornerShape(3.dp)))
                Text(label, color = Color(0xFFF2EBDD), fontSize = 12.sp)
            }
        }
        Text("Everything else: Samus passes through", color = Color(0xFFA9B4BF), fontSize = 12.sp)
    }
}

/** Fill behind the room image. Never applied to the game pixels themselves. */
private fun roomBackdropBrush(choice: RoomBackdrop, editorState: EditorState): androidx.compose.ui.graphics.Brush = when (choice) {
    RoomBackdrop.DARK -> SolidColor(EditorColors.romBackground)
    RoomBackdrop.GAME -> SolidColor(editorState.tileGraphics?.getPalettes()?.getOrNull(0)?.getOrNull(0)?.let { Color(it or (0xFF shl 24)) } ?: Color.Black)
    RoomBackdrop.CHECKER -> {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) img.setRGB(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xFF3A3F45.toInt() else 0xFF2A2E32.toInt())
        androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(img.toComposeImageBitmap(), androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated),
        )
    }
}
