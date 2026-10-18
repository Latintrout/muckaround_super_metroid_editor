package com.supermetroid.editor.ui.maker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.MinimapEditorState
import com.supermetroid.editor.ui.MinimapTool
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerFixedColors

/** One room on the Maker map: its footprint in map cells, whether you made it, and the rooms its doors lead to. */
data class MakerMapRoom(val roomId: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val own: Boolean, val linksTo: List<Int>)

/** The rooms of [area] on the map, with where each one's doors lead. */
fun EditorState.makerMapRooms(parser: RomParser, area: Int, names: Map<Int, String>): List<MakerMapRoom> =
    parser.roomCatalog.rooms.mapNotNull { info ->
        val id = info.getRoomIdAsInt()
        val room = parser.readRoomHeader(id)?.let(::applyHeaderChanges)?.takeIf { it.area == area } ?: return@mapNotNull null
        val links = runCatching { effectiveDoorsForRoom(id, parser).map { it.destRoomPtr } }.getOrDefault(emptyList()).distinct()
        MakerMapRoom(id, names[id] ?: info.name, room.mapX, room.mapY, room.width, room.height, project.newRoomForPreviewId(id) != null, links)
    }

/**
 * The area map: every room as a block, yours in colour with names, door links as lines. Click a room to open
 * it; drag one of your rooms to move it on the map (the move is checked like SMEDIT's map editor: no overlaps
 * with rooms or map tiles). [onMoved] runs after a move so the workspace can be rebuilt.
 */
@Composable
fun MakerMapPanel(
    editorState: EditorState, parser: RomParser, startArea: Int, currentRoomId: Int, names: Map<Int, String>,
    onOpen: (Int) -> Unit, onMoved: (String) -> Unit, onClose: () -> Unit,
) {
    val p = LocalMakerPalette.current
    var area by remember { mutableStateOf(startArea) }
    var version by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val mm = remember { MinimapEditorState() }
    LaunchedEffect(area, version) { mm.loadArea(parser, area, editorState); mm.activateTool(MinimapTool.SELECT, editorState) }
    val rooms = remember(area, version, editorState.editVersion) { editorState.makerMapRooms(parser, area, names) }
    val measurer = rememberTextMeasurer()
    var drag by remember { mutableStateOf<Pair<MakerMapRoom, Pair<Int, Int>>?>(null) } // room and its preview cell

    Box(
        Modifier.fillMaxSize().background(Color(0x99101C27))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth(0.94f).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Map", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Your rooms are in colour. Click a room to open it; drag one of yours to move it. Lines are doors.", color = p.muted, fontSize = 13.sp)
                }
                MakerButton(onClick = onClose) { fg -> Text("Done", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AREA_NAMES.take(6).forEachIndexed { i, name ->
                    MakerButton(onClick = { area = i; error = null }, selected = i == area, height = 32.dp,
                        modifier = Modifier.semantics { contentDescription = "Map of $name" }) { fg -> Text(name, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            val cells = MinimapData.MAP_WIDTH to MinimapData.ROOM_MAP_HEIGHT
            Canvas(
                Modifier.fillMaxWidth().height(560.dp).background(Color(0xFF0C0C18), RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Area map" }
                    .pointerInput(rooms, area) {
                        fun cellAt(o: Offset): Pair<Int, Int> {
                            val c = minOf(size.width / cells.first.toFloat(), size.height / cells.second.toFloat())
                            return (o.x / c).toInt() to (o.y / c).toInt()
                        }
                        awaitPointerEventScope {
                            var pressed: MakerMapRoom? = null
                            var start: Pair<Int, Int>? = null
                            while (true) {
                                val e = awaitPointerEvent()
                                val pos = e.changes.firstOrNull()?.position ?: continue
                                val (cx, cy) = cellAt(pos)
                                when (e.type) {
                                    PointerEventType.Press -> {
                                        pressed = rooms.firstOrNull { cx in it.x until it.x + it.w && cy in it.y until it.y + it.h }
                                        start = cx to cy
                                        val r = pressed
                                        if (r != null && r.own && mm.beginPrimaryRoomDrag(cx, cy, editorState)) drag = r to (r.x to r.y)
                                        e.changes.forEach { it.consume() }
                                    }
                                    PointerEventType.Move -> drag?.let { (r, _) ->
                                        mm.updatePointerRoomMove(cx, cy)
                                        val b = mm.moveBuffer
                                        if (b != null) drag = r to (b.currentX to b.currentY)
                                    }
                                    PointerEventType.Release -> {
                                        val r = pressed
                                        val moved = start != null && start != (cx to cy)
                                        val d = drag
                                        if (d != null && moved) {
                                            val before = editorState.statusMessage
                                            mm.endPrimaryRoomDrag(cx, cy, editorState)
                                            val now = editorState.makerMapRooms(parser, area, names).firstOrNull { it.roomId == d.first.roomId }
                                            if (now != null && (now.x != d.first.x || now.y != d.first.y)) {
                                                error = null; version++; onMoved("Moved ${d.first.name} on the map")
                                            } else error = editorState.statusMessage.takeIf { it != before }
                                                ?: "That spot is taken by another room or map tiles."
                                        } else {
                                            if (d != null) mm.cancelMove(editorState)
                                            if (r != null) onOpen(r.roomId)
                                        }
                                        drag = null; pressed = null; start = null
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
            ) {
                val c = minOf(size.width / cells.first, size.height / cells.second)
                // Grid.
                for (gx in 0..cells.first) drawLine(Color(0x22FFFFFF), Offset(gx * c, 0f), Offset(gx * c, cells.second * c), 1f)
                for (gy in 0..cells.second) drawLine(Color(0x22FFFFFF), Offset(0f, gy * c), Offset(cells.first * c, gy * c), 1f)
                fun centre(r: MakerMapRoom) = Offset((r.x + r.w / 2f) * c, (r.y + r.h / 2f) * c)
                val byId = rooms.associateBy { it.roomId }
                // Door links (yours bright, the game's faint).
                val drawn = HashSet<Pair<Int, Int>>()
                for (r in rooms) for (to in r.linksTo) {
                    val t = byId[to] ?: continue
                    if (!drawn.add(minOf(r.roomId, to) to maxOf(r.roomId, to))) continue // each link once
                    val own = r.own || t.own
                    drawLine(if (own) MakerFixedColors.doors else Color(0x40FFFFFF), centre(r), centre(t), if (own) 3f else 1.5f,
                        pathEffect = if (own) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                }
                for (r in rooms) {
                    val dragged = drag?.takeIf { it.first.roomId == r.roomId }
                    val (rx, ry) = dragged?.second ?: (r.x to r.y)
                    val tl = Offset(rx * c + 1, ry * c + 1); val sz = Size(r.w * c - 2, r.h * c - 2)
                    val fill = when { r.own -> MakerFixedColors.terrain; else -> Color(0xFF3A4652) }
                    drawRoundRect(fill.copy(alpha = if (dragged != null) 0.7f else 1f), tl, sz, CornerRadius(3f))
                    if (r.roomId == currentRoomId) drawRoundRect(MakerFixedColors.play, tl, sz, CornerRadius(3f), style = Stroke(3f))
                    if (r.own) drawLabel(measurer, r.name, tl, sz)
                }
            }
            error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(14.dp).background(MakerFixedColors.terrain, RoundedCornerShape(3.dp))); Text("Your rooms", color = p.text, fontSize = 12.sp)
                Box(Modifier.size(14.dp).background(Color(0xFF3A4652), RoundedCornerShape(3.dp))); Text("The game's rooms", color = p.text, fontSize = 12.sp)
                Box(Modifier.size(width = 18.dp, height = 3.dp).background(MakerFixedColors.doors)); Text("Door", color = p.text, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(measurer: TextMeasurer, text: String, tl: Offset, sz: Size) {
    val layout = measurer.measure(text, TextStyle(color = Color(0xFF18302A), fontSize = 10.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = sz.width.toInt().coerceAtLeast(1)))
    if (layout.size.height <= sz.height) drawText(layout, topLeft = Offset(tl.x + 3, tl.y + 2))
}
