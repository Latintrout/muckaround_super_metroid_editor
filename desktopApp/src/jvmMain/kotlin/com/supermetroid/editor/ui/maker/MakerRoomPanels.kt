package com.supermetroid.editor.ui.maker

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerFixedColors

/** A small card over the Maker with a scrim; Esc or a click outside closes it. */
@Composable
private fun MakerCard(title: String, subtitle: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalMakerPalette.current
    Box(
        Modifier.fillMaxSize().background(Color(0x99101C27))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = p.muted, fontSize = 13.sp)
            }
            content()
        }
    }
}

@Composable
private fun NameField(value: String, onValue: (String) -> Unit, onDone: () -> Unit) {
    val p = LocalMakerPalette.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value, onValueChange = { onValue(it.replace("\n", "")) }, singleLine = true,
        textStyle = TextStyle(color = p.text, fontSize = 15.sp), cursorBrush = SolidColor(p.text),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { onDone(); true } else false }
            .background(p.raised, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(12.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, preview: (@Composable () -> Unit)? = null) {
    val p = LocalMakerPalette.current
    Column(
        modifier.background(if (selected) MakerFixedColors.terrain.copy(alpha = 0.35f) else p.raised, RoundedCornerShape(8.dp))
            .border(2.dp, if (selected) p.accentDeep else p.border, RoundedCornerShape(8.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        preview?.invoke()
        Text(text, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A tiny drawing of [template] at [size] screens: solid blocks in the area-neutral terrain colour. */
@Composable
private fun TemplatePreview(template: MakerNewRoomStart, screens: Pair<Int, Int>) {
    val w = screens.first * 16; val h = screens.second * 16
    val solid = remember(template, screens) { makerTemplateSolid(template, w, h, seed = 7) }
    val scale = minOf(56f / w, 40f / h)
    androidx.compose.foundation.Canvas(Modifier.size(56.dp, 40.dp)) {
        val px = scale * density
        val ox = (size.width - w * px) / 2; val oy = (size.height - h * px) / 2
        drawRect(Color(0xFF0C0C18), androidx.compose.ui.geometry.Offset(ox, oy), androidx.compose.ui.geometry.Size(w * px, h * px))
        for (y in 0 until h) for (x in 0 until w) if (solid[y * w + x]) {
            drawRect(MakerFixedColors.terrain, androidx.compose.ui.geometry.Offset(ox + x * px, oy + y * px), androidx.compose.ui.geometry.Size(px + 0.5f, px + 0.5f))
        }
    }
}

/** A room-shaped preview: [w] × [h] screens drawn as cells. */
@Composable
private fun SizeShape(w: Int, h: Int) {
    val p = LocalMakerPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(h) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(w) { Box(Modifier.size(12.dp).background(p.muted.copy(alpha = 0.55f), RoundedCornerShape(2.dp))) }
            }
        }
    }
}

/**
 * New room: name, size in screens, and whether to start empty (solid border, air inside) or as a copy of
 * the room you're in. [onCreate] returns an error to show, or null when the room was made.
 */
@Composable
fun MakerNewRoomPanel(
    defaultName: String, currentRoomName: String?,
    onCreate: (name: String, width: Int, height: Int, start: MakerNewRoomStart) -> String?, onClose: () -> Unit,
) {
    val p = LocalMakerPalette.current
    var name by remember { mutableStateOf(defaultName) }
    var size by remember { mutableStateOf(MAKER_ROOM_SIZES.first()) }
    var start by remember { mutableStateOf(MakerNewRoomStart.EMPTY) }
    var error by remember { mutableStateOf<String?>(null) }
    fun create() { error = onCreate(name, size.first, size.second, start); if (error == null) onClose() }
    MakerCard("New room", "It goes in this area, next to the room you're in, and opens straight away.", onClose) {
        SectionLabel("Name")
        NameField(name, { name = it; error = null }, ::create)
        SectionLabel("Start from")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MakerNewRoomStart.entries.filter { it.isTemplate }.forEach { t ->
                Chip(t.label, start == t, { start = t; size = t.size }, Modifier.weight(1f), preview = { TemplatePreview(t, t.size) })
            }
        }
        Chip(if (currentRoomName != null) "Copy of $currentRoomName" else "Copy of this room", start == MakerNewRoomStart.COPY,
            { if (currentRoomName != null) start = MakerNewRoomStart.COPY }, Modifier.fillMaxWidth())
        if (start.isTemplate) {
            Text("${start.label}: ${start.hint}.", color = p.muted, fontSize = 13.sp)
            SectionLabel("Size (screens)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                MAKER_ROOM_SIZES.forEach { s ->
                    Chip("${s.first}×${s.second}", s == size, { size = s }, preview = { SizeShape(s.first, s.second) })
                }
            }
        } else Text("Same size, tiles, items and enemies as the room you're in. Doors aren't copied.", color = p.muted, fontSize = 13.sp)
        error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            MakerButton(onClick = onClose) { fg -> Text("Cancel", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
            MakerButton(onClick = ::create, fill = MakerFixedColors.play, onFill = MakerFixedColors.onPlay) { fg ->
                MakerIcon(MakerIcons.NEW_ROOM, fg, 18.dp); Text("Make room", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Rename one of your rooms. [onRename] returns an error to show, or null when renamed. */
@Composable
fun MakerRenameRoomPanel(current: String, onRename: (String) -> String?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    fun rename() { error = onRename(name); if (error == null) onClose() }
    MakerCard("Rename room", "The name shows in the room picker and in Advanced mode.", onClose) {
        NameField(name, { name = it; error = null }, ::rename)
        error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
        Row {
            Spacer(Modifier.weight(1f))
            MakerButton(onClick = onClose) { fg -> Text("Cancel", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
            MakerButton(onClick = ::rename, fill = MakerFixedColors.play, onFill = MakerFixedColors.onPlay) { fg ->
                Text("Rename", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Confirm deleting one of your rooms. [onDelete] returns an error to show, or null when deleted. */
@Composable
fun MakerDeleteRoomPanel(roomName: String, onDelete: () -> String?, onClose: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    MakerCard("Delete $roomName?", "The room, its tiles, items and Start point are removed from the project. This can't be undone.", onClose) {
        error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
        Row {
            Spacer(Modifier.weight(1f))
            MakerButton(onClick = onClose) { fg -> Text("Keep it", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
            MakerButton(onClick = { error = onDelete(); if (error == null) onClose() }, fill = MakerFixedColors.danger, onFill = Color.White) { fg ->
                MakerIcon(MakerIcons.TRASH, fg, 18.dp); Text("Delete room", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Resize: add or remove a screen on any side. Each press applies straight away; [onResize] returns an
 * error to show, or null. [size] is the room's current size in screens.
 */
@Composable
fun MakerResizeRoomPanel(size: Pair<Int, Int>, onResize: (MakerSide, Boolean) -> String?, onClose: () -> Unit) {
    val p = LocalMakerPalette.current
    var error by remember { mutableStateOf<String?>(null) }
    @Composable
    fun SideButtons(side: MakerSide) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            MakerButton(onClick = { error = onResize(side, false) }, height = 34.dp) { fg -> Text("−", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Text(side.label, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            MakerButton(onClick = { error = onResize(side, true) }, height = 34.dp) { fg -> Text("+", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
    MakerCard("Resize room", "Add or remove a screen on any side. New screens carry on the floor and ceiling. This starts a fresh Undo history.", onClose) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SideButtons(MakerSide.TOP)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SideButtons(MakerSide.LEFT)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SizeShape(size.first.coerceAtMost(8), size.second.coerceAtMost(8))
                    Text("${size.first} × ${size.second} screens", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                SideButtons(MakerSide.RIGHT)
            }
            SideButtons(MakerSide.BOTTOM)
        }
        error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
        Row {
            Spacer(Modifier.weight(1f))
            MakerButton(onClick = onClose, fill = MakerFixedColors.play, onFill = MakerFixedColors.onPlay) { fg ->
                Text("Done", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Where does this door go? Your rooms connect both ways (a return door is added there); the game's rooms
 * get a one-way door. [onPick] returns an error to show, or null when connected.
 */
@Composable
fun MakerConnectDoorPanel(
    ownRooms: List<Pair<Int, String>>, gameRooms: List<Pair<Int, String>>,
    onPick: (Int) -> String?, onClose: () -> Unit,
) {
    val p = LocalMakerPalette.current
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    MakerCard("Where does this door go?", "Your rooms connect both ways: a matching door is added on their facing wall. The game's rooms get a one-way door.", onClose) {
        NameField(query, { query = it }, {})
        val q = query.trim()
        fun List<Pair<Int, String>>.matching() = filter { q.isEmpty() || it.second.contains(q, ignoreCase = true) }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val own = ownRooms.matching(); val game = gameRooms.matching()
            if (own.isNotEmpty()) item { SectionLabel("Your rooms · both ways") }
            items(own.size) { i ->
                val (id, name) = own[i]
                MakerButton(onClick = { if (!busy) { busy = true; error = onPick(id); busy = false; if (error == null) onClose() } },
                    modifier = Modifier.fillMaxWidth(), height = 38.dp) { fg ->
                    MakerIcon(MakerIcons.DOORS, MakerFixedColors.doors, 16.dp); Text(name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (game.isNotEmpty()) item { SectionLabel("Game rooms · one way") }
            items(game.size) { i ->
                val (id, name) = game[i]
                MakerButton(onClick = { if (!busy) { busy = true; error = onPick(id); busy = false; if (error == null) onClose() } },
                    modifier = Modifier.fillMaxWidth(), height = 34.dp) { fg ->
                    Text(name, color = fg, fontSize = 12.sp)
                }
            }
        }
        error?.let { Text(it, color = MakerFixedColors.danger, fontSize = 13.sp) }
        Row {
            Spacer(Modifier.weight(1f))
            MakerButton(onClick = onClose) { fg -> Text("Not now", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
