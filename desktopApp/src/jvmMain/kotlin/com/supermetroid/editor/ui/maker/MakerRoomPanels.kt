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
            Chip("Empty room", start == MakerNewRoomStart.EMPTY, { start = MakerNewRoomStart.EMPTY }, Modifier.weight(1f))
            Chip(if (currentRoomName != null) "Copy of $currentRoomName" else "Copy of this room", start == MakerNewRoomStart.COPY,
                { if (currentRoomName != null) start = MakerNewRoomStart.COPY }, Modifier.weight(1f))
        }
        if (start == MakerNewRoomStart.EMPTY) {
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
