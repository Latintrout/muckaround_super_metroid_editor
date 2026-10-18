package com.supermetroid.editor.ui.maker

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerFixedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

/** A room's silhouette, one pixel per block: solid blocks light, open blocks dark. Cached per ROM build. */
object MakerRoomThumbnails {
    private val cache = java.util.Collections.synchronizedMap(HashMap<Pair<Int, Int>, ImageBitmap?>())

    fun get(parser: RomParser, roomId: Int): ImageBitmap? = cache.getOrPut(System.identityHashCode(parser) to roomId) {
        runCatching {
            val room = parser.readRoomHeader(roomId) ?: return@runCatching null
            val data = parser.decompressLZ2(room.levelDataPtr)
            val w = room.width * 16; val h = room.height * 16
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until h) for (x in 0 until w) {
                val o = 2 + (y * w + x) * 2
                val type = if (o + 1 < data.size) ((data[o + 1].toInt() and 0xFF) shr 4) else 0
                img.setRGB(x, y, when (type) { 0x8, 0xB, 0xC, 0xE, 0xF -> 0xFF8FB7A0.toInt(); 0x1 -> 0xFF5F8A75.toInt(); 0x9 -> 0xFF7E9FBE.toInt(); 0xA -> 0xFFE6BC70.toInt(); else -> 0xFF1B2A36.toInt() })
            }
            img.toComposeImageBitmap()
        }.getOrNull()
    }
}

/**
 * Room picker: search, your rooms first, then the game's rooms by area, each with its silhouette. Also New
 * room, and Rename / Delete for the room you're in when it's one of yours.
 */
@Composable
fun MakerRoomPickerPanel(
    rooms: List<RoomInfo>, parser: RomParser, areaOf: (RoomInfo) -> Int, ownRoomIds: Set<Int>, currentRoomId: Int?,
    onPick: (RoomInfo) -> Unit, onNewRoom: (() -> Unit)?, onRename: (() -> Unit)?, onDelete: (() -> Unit)?, onClose: () -> Unit,
) {
    val p = LocalMakerPalette.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim()
    val matching = rooms.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || AREA_NAMES.getOrElse(areaOf(it)) { "" }.contains(q, ignoreCase = true) }
    val groups = buildList {
        val own = matching.filter { it.getRoomIdAsInt() in ownRoomIds }
        if (own.isNotEmpty()) add("Your rooms" to own)
        matching.filter { it.getRoomIdAsInt() !in ownRoomIds }.groupBy { areaOf(it) }.toSortedMap().forEach { (area, list) ->
            add(AREA_NAMES.getOrElse(area) { "Other" } to list)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x99101C27))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.7f).height(720.dp).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rooms", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onNewRoom != null) MakerButton(onClick = { onClose(); onNewRoom() }) { fg -> MakerIcon(MakerIcons.NEW_ROOM, fg, 18.dp); Text("+ New room", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                if (onRename != null) MakerButton(onClick = { onClose(); onRename() }) { fg -> Text("Rename this room…", color = fg, fontSize = 13.sp) }
                if (onDelete != null) MakerButton(onClick = { onClose(); onDelete() }) { fg -> Text("Delete this room…", color = MakerFixedColors.danger, fontSize = 13.sp) }
            }
            Row(
                Modifier.fillMaxWidth().background(p.inset, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MakerIcon(MakerIcons.SEARCH, p.muted, 16.dp)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search rooms or areas…", color = p.muted, fontSize = 14.sp)
                    BasicTextField(query, { query = it.replace("\n", "") }, singleLine = true, textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { (title, list) ->
                    item(key = "h-$title") { SectionLabel("$title · ${list.size}", Modifier.padding(top = 6.dp)) }
                    items(list, key = { it.id }) { room ->
                        val id = room.getRoomIdAsInt()
                        val thumb by produceState<ImageBitmap?>(null, parser, id) { value = withContext(Dispatchers.Default) { MakerRoomThumbnails.get(parser, id) } }
                        Row(
                            Modifier.fillMaxWidth().background(if (id == currentRoomId) MakerFixedColors.play.copy(alpha = 0.25f) else p.raised, RoundedCornerShape(8.dp))
                                .border(1.dp, if (id == currentRoomId) MakerFixedColors.playDeep else p.border, RoundedCornerShape(8.dp))
                                .clickable { onPick(room); onClose() }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(width = 72.dp, height = 40.dp).background(Color(0xFF0C0C18), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                thumb?.let { Image(it, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(2.dp)) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(room.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(AREA_NAMES.getOrElse(areaOf(room)) { "" }, color = p.muted, fontSize = 11.sp)
                            }
                            if (id in ownRoomIds) Box(Modifier.size(width = 8.dp, height = 28.dp).background(MakerFixedColors.terrain, RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
            Row { Spacer(Modifier.weight(1f)); MakerButton(onClick = onClose) { fg -> Text("Close", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
        }
    }
}
