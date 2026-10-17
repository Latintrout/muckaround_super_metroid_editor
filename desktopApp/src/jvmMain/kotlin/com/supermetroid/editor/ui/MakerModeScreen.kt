package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.maker.MakerPlayLauncher
import com.supermetroid.editor.maker.MakerPlayLoadout
import com.supermetroid.editor.maker.MakerPlayPlan
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Maker Mode: a Super Mario Maker 2-style front end over the same [EditorState]
 * that powers the full editor. Place -> Play -> Adjust.
 *
 * Painting parts (ground, hazards, doors) set the shared brush and let [MapCanvas]
 * handle strokes, undo and validation. Stamp parts (items, enemies) intercept a
 * click on the canvas and place one object at the hovered block.
 */
internal enum class MakerCategory(val label: String, val icon: String, val hint: String) {
    GROUND("Ground", "▦", "Paint solid ground, erase it, or copy a tile from the room."),
    DOORS("Doors", "▯", "Door caps and stations. Doors need a door opening to work."),
    ENEMIES("Enemies", "☠", "Enemies whose graphics are already loaded in this room."),
    ITEMS("Items", "✦", "Click to drop a pickup on a block."),
    HAZARDS("Hazards", "▲", "Spikes, crumble, bomb, shot and grapple blocks."),
    DECORATIONS("Decorations", "✿", "Pick any tile from the room to use as scenery."),
}

internal sealed class MakerPart(val label: String, val detail: String) {
    /** Paint with a single metatile; block type comes from tileset defaults. */
    class Tile(label: String, detail: String, val metatile: Int) : MakerPart(label, detail)
    class Tool(label: String, detail: String, val tool: EditorTool) : MakerPart(label, detail)
    class Pattern(label: String, detail: String, val patternId: String) : MakerPart(label, detail)
    class Item(label: String, detail: String, val plmId: Int) : MakerPart(label, detail)
    class Enemy(label: String, detail: String, val enemyId: Int) : MakerPart(label, detail)
}

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
        "Iron Warden test",
        items = 0x0001 or 0x0004 or 0x1000 or 0x0100,
        beams = 0x1000,
        energyTanks = 4,
        missiles = 30,
    ),
)

/** Hazard metatiles from [TilesetDefaults] (common tiles shared by every tileset). */
private val HAZARD_PARTS = listOf(
    MakerPart.Tile("Spikes", "Hurts Samus on contact", 86),
    MakerPart.Tile("Crumble", "Falls away after Samus stands on it", 188),
    MakerPart.Tile("Bomb block", "Breaks with bombs, re-forms", 88),
    MakerPart.Tile("Shot block", "Breaks with any shot", 82),
    MakerPart.Tile("Grapple", "Grapple Beam can latch on", 155),
)

/** Most common solid (type 0x8) metatile in the loaded room, so "Ground" matches the area's look. */
internal fun dominantGroundMetatile(editorState: EditorState): Int? {
    val w = editorState.workingBlocksWide
    val h = editorState.workingBlocksTall
    if (w <= 0 || h <= 0) return null
    val counts = HashMap<Int, Int>()
    for (by in 0 until h) for (bx in 0 until w) {
        val word = editorState.readBlockWord(bx, by)
        if ((word shr 12) and 0xF == 0x8) {
            val metatile = word and 0x3FF
            counts[metatile] = (counts[metatile] ?: 0) + 1
        }
    }
    return counts.maxByOrNull { it.value }?.key
}

internal fun makerPartsFor(category: MakerCategory, editorState: EditorState): List<MakerPart> = when (category) {
    MakerCategory.GROUND -> buildList {
        dominantGroundMetatile(editorState)?.let { add(MakerPart.Tile("Ground", "This area's ground tile", it)) }
        add(MakerPart.Tool("Eraser", "Clear blocks to air", EditorTool.ERASE))
        add(MakerPart.Tool("Fill", "Flood-fill with the last part", EditorTool.FILL))
    }
    MakerCategory.DOORS -> editorState.project.patterns
        .filter { it.builtIn && it.id.startsWith("builtin_") }
        .sortedBy { it.name }
        .map { MakerPart.Pattern(it.name, "Built-in pattern", it.id) }
    MakerCategory.ENEMIES -> editorState.workingEnemies
        .map { it.id }
        .distinct()
        .map { MakerPart.Enemy(RomParser.enemyName(it), "Already loaded in this room", it) }
    MakerCategory.ITEMS -> RomParser.ITEM_DEFS.map { MakerPart.Item(it.name, it.shortLabel, it.visibleId) }
    MakerCategory.HAZARDS -> HAZARD_PARTS
    MakerCategory.DECORATIONS -> listOf(
        MakerPart.Tool("Copy tile", "Click any tile in the room to paint with it", EditorTool.SAMPLE),
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
) {
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(MakerCategory.GROUND) }
    var activePart by remember { mutableStateOf<MakerPart?>(null) }
    var loadout by remember { mutableStateOf(MAKER_LOADOUTS[1]) }
    var roomMenuOpen by remember { mutableStateOf(false) }
    var loadoutMenuOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val parts = remember(category, editorState.editVersion, editorState.patternVersion, selectedRoom) {
        if (category == MakerCategory.DOORS) editorState.seedBuiltInPatterns(romParser)
        makerPartsFor(category, editorState)
    }

    fun choose(part: MakerPart) {
        activePart = part
        when (part) {
            is MakerPart.Tile -> {
                editorState.selectMetatile(part.metatile)
                editorState.activeTool = EditorTool.PAINT
            }
            is MakerPart.Tool -> editorState.activeTool = part.tool
            is MakerPart.Pattern -> editorState.selectAndApplyPattern(part.patternId)
            // Stamps are handled by the click interceptor; keep MapCanvas from painting.
            is MakerPart.Item, is MakerPart.Enemy -> editorState.activeTool = EditorTool.SELECT
        }
        message = part.detail
    }

    fun stampAt(bx: Int, by: Int) {
        when (val part = activePart) {
            is MakerPart.Item -> editorState.addPlm(part.plmId, bx, by, 0)
            is MakerPart.Enemy -> editorState.addEnemy(part.enemyId, bx * 16 + 8, by * 16 + 8)
            else -> return
        }
        message = "Placed ${activePart?.label} at block $bx, $by"
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
            message = "Entering ${selectedRoom?.name} with ${l.name}…"
            val launcher = MakerPlayLauncher(
                read16 = { a -> emulatorWorkspaceState.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) } },
                write16 = { a, v -> emulatorWorkspaceState.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) },
                waitFrames = { frames -> delay(frames * 17L) },
                writeBlock = { a, bytes -> emulatorWorkspaceState.writeMemory(a, bytes) },
            )
            val result = runCatching {
                launcher.launch(plan, MakerPlayLoadout(l.items, l.beams, l.energyTanks, l.missiles))
            }.getOrElse { MakerPlayLauncher.Result(false, it.message ?: "Play failed") }
            message = if (result.ok) "Playing ${selectedRoom?.name} with ${l.name}" else result.message
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Top bar ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("MAKER", fontWeight = FontWeight.Black, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            Box {
                OutlinedButton(onClick = { roomMenuOpen = true }) {
                    Text(selectedRoom?.name ?: "Pick a room", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(220.dp))
                }
                DropdownMenu(expanded = roomMenuOpen, onDismissRequest = { roomMenuOpen = false }) {
                    rooms.forEach { room ->
                        DropdownMenuItem(
                            text = { Text(room.name, fontSize = 12.sp) },
                            onClick = { roomMenuOpen = false; onRoomSelected(room) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { editorState.undo() }) { Text("↶ Undo") }
            OutlinedButton(onClick = { editorState.redo() }) { Text("↷ Redo") }
            Spacer(Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { loadoutMenuOpen = true }) { Text("Loadout: ${loadout.name}") }
                DropdownMenu(expanded = loadoutMenuOpen, onDismissRequest = { loadoutMenuOpen = false }) {
                    MAKER_LOADOUTS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = { loadoutMenuOpen = false; loadout = option },
                        )
                    }
                }
            }
            Button(
                onClick = ::playHere,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E44)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(44.dp),
            ) { Text("▶  PLAY", fontWeight = FontWeight.Black, fontSize = 18.sp) }
            OutlinedButton(onClick = onExit) { Text("Advanced editor") }
        }

        Row(Modifier.fillMaxSize()) {
            // ── Category rail ──
            Column(
                modifier = Modifier.width(96.dp).fillMaxHeight().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MakerCategory.entries.forEach { c ->
                    val selected = c == category
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { category = c; activePart = null }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(c.icon, fontSize = 26.sp)
                        Text(c.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else null)
                    }
                }
            }

            // ── Parts palette ──
            Column(
                modifier = Modifier.width(220.dp).fillMaxHeight().padding(6.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(category.hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (parts.isEmpty()) {
                    Text("Nothing available here for this room yet.", fontSize = 12.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEach { part ->
                        val selected = activePart?.label == part.label && activePart?.javaClass == part.javaClass
                        Box(
                            modifier = Modifier
                                .size(98.dp, 64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { choose(part) }
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(part.label, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3)
                        }
                    }
                }
            }

            // ── Canvas ──
            Column(Modifier.weight(1f).fillMaxHeight()) {
                message?.let {
                    Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                val stamping = activePart is MakerPart.Item || activePart is MakerPart.Enemy
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(stamping) {
                            if (!stamping) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                                        val bx = editorState.hoverBlockX
                                        val by = editorState.hoverBlockY
                                        if (bx >= 0 && by >= 0) {
                                            stampAt(bx, by)
                                            event.changes.forEach { it.consume() }
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
