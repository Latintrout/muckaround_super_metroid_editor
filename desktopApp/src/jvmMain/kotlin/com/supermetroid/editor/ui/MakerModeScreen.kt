package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    MakerThemeProvider(themeState) {
        MakerScreenContent(editorState, romParser, rooms, selectedRoom, emulatorWorkspaceState, onRoomSelected,
            onWorkspaceChanged, onPlay, onExit, themeState, modifier, initialSolidView, playing, onStopPlay, initialAppearanceOpen)
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
) {
    val p = LocalMakerPalette.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(MakerTool.PAINT) }
    var category by remember { mutableStateOf(MakerCategory.TERRAIN) }
    var activePart by remember { mutableStateOf<MakerPart?>(null) }
    var search by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var loadout by remember { mutableStateOf(MAKER_LOADOUTS[1]) }
    var message by remember { mutableStateOf<String?>(null) }
    val controls = remember { MapCanvasControls() }
    var solidView by remember { mutableStateOf(initialSolidView) }
    var appearanceOpen by remember { mutableStateOf(initialAppearanceOpen) }
    var markers by remember { mutableStateOf(setOf<MakerMarker>()) }
    androidx.compose.runtime.SideEffect {
        controls.overlays = MAKER_BASE_LAYERS + (if (solidView) SOLID_VIEW_OVERLAYS else emptySet()) + markers.map { it.overlay }
    }
    val backdropChoice = themeState.appearance.roomBackdrop
    val backdrop = remember(backdropChoice, editorState.tileGraphics) { roomBackdropBrush(backdropChoice, editorState) }
    androidx.compose.runtime.SideEffect { controls.backdrop = backdrop }
    val areaOf = remember(rooms, romParser) {
        val areas = rooms.associate { it.id to (romParser?.readRoomHeader(it.getRoomIdAsInt())?.area ?: -1) }
        ({ room: RoomInfo? -> areas[room?.id] ?: -1 })
    }

    val parts = remember(category, editorState.editVersion, editorState.patternVersion, selectedRoom) {
        if (category == MakerCategory.DOORS) editorState.seedBuiltInPatterns(romParser)
        makerPartsFor(category, editorState)
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

    fun choose(part: MakerPart) {
        activePart = part
        when (part) {
            is MakerPart.Tile -> editorState.selectMetatile(part.metatile)
            is MakerPart.Pattern -> editorState.selectAndApplyPattern(part.patternId)
            else -> Unit
        }
        applyTool(if (tool == MakerTool.FILL && part is MakerPart.Tile) MakerTool.FILL else MakerTool.PAINT, part)
    }

    fun stampAt(bx: Int, by: Int) {
        when (val part = activePart) {
            is MakerPart.Item -> editorState.addPlm(part.plmId, bx, by, 0)
            is MakerPart.Enemy -> editorState.addEnemy(part.enemyId, bx * 16 + 8, by * 16 + 8)
            else -> return
        }
        message = "Placed ${activePart?.name} at block $bx, $by"
    }

    /** Right-click in Paint: remove the item/enemy under the cursor, else erase the block. */
    fun removeAt(bx: Int, by: Int) {
        editorState.workingPlms.firstOrNull { it.x == bx && it.y == by && editorState.isEditorItemPlm(it.id) }?.let {
            editorState.removePlm(it.x, it.y, it.id); message = "Removed ${RomParser.plmDisplayName(it.id)}"; return
        }
        editorState.workingEnemies.firstOrNull { it.x / 16 == bx && it.y / 16 == by }?.let {
            editorState.removeEnemy(it); message = "Removed ${RomParser.enemyName(it.id)}"; return
        }
        editorState.eraseAt(bx, by)
    }

    fun playHere() {
        val parser = romParser ?: return
        val roomId = selectedRoom?.getRoomIdAsInt() ?: return
        val plan = MakerPlayPlan.forRoom(parser, roomId)
        if (plan == null) {
            message = "No save station or door leads into this room yet, so Play can't reach it."
            return
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
                launcher.launch(plan, MakerPlayLoadout(l.items, l.beams, l.energyTanks, l.missiles))
            }.getOrElse { MakerPlayLauncher.Result(false, it.message ?: "Play failed") }
            message = if (result.ok) "Playing ${selectedRoom.name} with ${l.name}" else result.message
        }
    }

    // Start ready to paint with this area's ground.
    androidx.compose.runtime.LaunchedEffect(selectedRoom?.id, parts.isNotEmpty()) {
        if (activePart == null && category == MakerCategory.TERRAIN) parts.firstOrNull()?.let { choose(it) }
    }

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || searchFocused) return@onPreviewKeyEvent false
                val mod = e.isCtrlPressed || e.isMetaPressed
                when {
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
            onRestart = ::playHere,
        ) else CommandBar(
            rooms = rooms, selectedRoom = selectedRoom, onRoomSelected = onRoomSelected, areaOf = areaOf,
            onUndo = { editorState.undo() }, onRedo = { editorState.redo() },
            controls = controls, themeState = themeState, solidView = solidView, onSolidView = { solidView = it },
            loadout = loadout, onLoadout = { loadout = it },
            onPlay = ::playHere, onAdvanced = onExit, onAppearance = { appearanceOpen = true },
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolRail(tool) { applyTool(it) }
            PartsPanel(
                category = category, onCategory = { category = it; search = "" },
                parts = shownParts, active = activePart, onChoose = ::choose,
                search = search, onSearch = { search = it }, onSearchFocus = { searchFocused = it },
                editorState = editorState,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0C0C18))) {
                    val stamping = tool == MakerTool.PAINT && activePart?.stamps == true
                    val paintTiles = tool == MakerTool.PAINT && activePart is MakerPart.Tile
                    Box(
                        Modifier.fillMaxSize().pointerInput(stamping, paintTiles, playing) {
                            awaitPointerEventScope {
                                var erasing = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (playing) {
                                        if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
                                        continue
                                    }
                                    val bx = editorState.hoverBlockX
                                    val by = editorState.hoverBlockY
                                    val inRoom = bx >= 0 && by >= 0
                                    when {
                                        stamping && event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && inRoom -> {
                                            stampAt(bx, by); event.changes.forEach { it.consume() }
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
                    ZoomControls(controls, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                    if (solidView) SolidLegend(Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                if (playing) PlayingHint() else if (themeState.appearance.showHintBar) HintBar(tool, activePart)
            }
            Inspector(editorState, romParser, selectedRoom, areaOf(selectedRoom), activePart, rooms, markers, { markers = it }, onAdvanced = onExit)
        }
        StatusBar(editorState, message)
    }
    if (appearanceOpen) com.supermetroid.editor.ui.maker.MakerAppearancePanel(themeState) { appearanceOpen = false }
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
    controls: MapCanvasControls,
    themeState: MakerThemeState,
    solidView: Boolean,
    onSolidView: (Boolean) -> Unit,
    loadout: MakerLoadout,
    onLoadout: (MakerLoadout) -> Unit,
    onPlay: () -> Unit,
    onAdvanced: () -> Unit,
    onAppearance: () -> Unit,
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
                rooms.forEach { room ->
                    DropdownMenuItem(
                        text = { Text("${AREA_NAMES.getOrElse(areaOf(room)) { "?" }} · ${room.name}", fontSize = 13.sp) },
                        onClick = { roomMenu = false; onRoomSelected(room) },
                    )
                }
            }
        }
        Divider()
        MakerButton(onClick = onUndo) { fg -> MakerIcon(MakerIcons.UNDO, fg, 20.dp); Text("Undo", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        MakerButton(onClick = onRedo) { fg -> MakerIcon(MakerIcons.REDO, fg, 20.dp); Text("Redo", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        Divider()
        MakerButton(onClick = { controls.showGrid = !controls.showGrid }, selected = controls.showGrid) { fg ->
            MakerIcon(MakerIcons.GRID, fg, 18.dp); Text("Grid", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        MakerButton(onClick = { onSolidView(!solidView) }, selected = solidView) { fg ->
            MakerIcon(MakerIcons.SOLID, fg, 18.dp); Text("What's solid?", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
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
                Column(
                    Modifier.height(108.dp)
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

/** Game art for a part: real metatile pixels or item sprite, nearest-neighbour, never themed. */
@Composable
private fun PartPreview(part: MakerPart, editorState: EditorState, modifier: Modifier) {
    val bitmap: ImageBitmap? = remember(part.id, editorState.tileGraphics) {
        when (part) {
            is MakerPart.Tile -> editorState.tileGraphics?.renderMetatile(part.metatile)?.let { px ->
                BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 16, 16, px, 0, 16) }.toComposeImageBitmap()
            }
            is MakerPart.Item -> ROOM_ITEM_SPRITE_COORDS[part.name]?.let { c ->
                ItemSpriteSheetCache.get()?.getSubimage(c.x, c.y, 16, 16)?.toComposeImageBitmap()
            }
            else -> null
        }
    }
    Box(modifier.background(Color(0xFF0C0C18), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = part.name, filterQuality = FilterQuality.None, modifier = Modifier.size(48.dp))
        } else {
            MakerIcon(part.category.icon, part.category.color, 30.dp)
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
        Text(makerHint(tool, part), color = p.text, fontSize = 14.sp)
    }
}

@Composable
private fun Inspector(
    editorState: EditorState, romParser: RomParser?, room: RoomInfo?, area: Int, part: MakerPart?, rooms: List<RoomInfo>,
    markers: Set<MakerMarker>, onMarkers: (Set<MakerMarker>) -> Unit, onAdvanced: () -> Unit,
) {
    val p = LocalMakerPalette.current
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(p.panel).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Selected part")
        Row(
            Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (part != null) {
                PartPreview(part, editorState, Modifier.size(52.dp))
                Column {
                    Text(part.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${part.category.label} · ${part.hint}", color = p.muted, fontSize = 12.sp)
                }
            } else {
                Text("Choose a part from the library to start building.", color = p.muted, fontSize = 13.sp)
            }
        }
        SectionLabel("Under the cursor")
        val bx = editorState.hoverBlockX
        val by = editorState.hoverBlockY
        if (bx >= 0 && by >= 0) {
            val word = editorState.readBlockWord(bx, by)
            InfoGrid(listOf("Block" to "x $bx  y $by", "Type" to blockTypeName(word), "Tile" to "0x%03X".format(word and 0x3FF)), mono = setOf("Block", "Tile"))
        } else {
            Text("Point at the room to see a block's position and type.", color = p.muted, fontSize = 13.sp)
        }
        SectionLabel("Room")
        if (room != null) {
            val roomId = room.getRoomIdAsInt()
            val doorsIn = remember(roomId, romParser) { romParser?.findDoorsLeadingTo(roomId)?.size ?: 0 }
            InfoGrid(listOf(
                "Size" to "${editorState.workingBlocksWide / 16} × ${editorState.workingBlocksTall / 16} screens",
                "Area" to AREA_NAMES.getOrElse(area) { "?" },
                "Doors" to "${editorState.doorEntries.size} out · $doorsIn lead in",
            ), mono = setOf("Size"))
        }
        if (room != null && romParser != null) {
            val roomId = room.getRoomIdAsInt()
            val checks = remember(roomId, romParser) {
                val names = rooms.associate { it.getRoomIdAsInt() to it.name }
                readyChecks(romParser, roomId) { names[it] ?: "another room" }
            }
            val todo = checks.count { !it.ok }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Ready to play?", Modifier.weight(1f))
                Text(if (todo == 0) "All good" else "$todo to fix", color = if (todo == 0) MakerFixedColors.ok else MakerFixedColors.warning,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            checks.forEach { c -> ReadyRow(c) }
        }
        SectionLabel("Show on canvas")
        MakerMarker.entries.forEach { m ->
            val on = m in markers
            Row(
                Modifier.fillMaxWidth().height(38.dp).background(p.raised, RoundedCornerShape(6.dp)).border(1.dp, p.border, RoundedCornerShape(6.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { onMarkers(if (on) markers - m else markers + m) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(12.dp).background(Color(m.overlay.color), RoundedCornerShape(3.dp)))
                Text(m.label, color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(20.dp).background(if (on) MakerFixedColors.ok else p.inset, RoundedCornerShape(4.dp)).border(1.dp, if (on) MakerFixedColors.ok else p.border, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (on) MakerIcon(MakerIcons.CHECK, Color.White, 14.dp, strokeWidth = 2.8f) }
            }
        }
        Text("Advanced room settings ›", color = p.accentDeep.takeIf { !p.isDark } ?: p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onAdvanced).padding(vertical = 4.dp))
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
private fun ReadyRow(c: ReadyCheck) {
    val p = LocalMakerPalette.current
    val warnBg = if (p.isDark) Color(0xFF3A3220) else Color(0xFFF6E6C4)
    Row(
        Modifier.fillMaxWidth()
            .background(if (c.ok) p.raised else warnBg, RoundedCornerShape(6.dp))
            .border(1.dp, if (c.ok) p.border else MakerFixedColors.hazards, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MakerIcon(if (c.ok) MakerIcons.CHECK else MakerIcons.WARN, if (c.ok) MakerFixedColors.ok else MakerFixedColors.warning, 18.dp, strokeWidth = 2.6f)
        Column {
            Text(c.title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(c.detail, color = p.muted, fontSize = 12.sp)
        }
    }
}

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
