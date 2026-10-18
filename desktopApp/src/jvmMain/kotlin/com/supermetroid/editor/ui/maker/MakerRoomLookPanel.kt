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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import java.awt.image.BufferedImage

/** Area effects vanilla rooms use that work in any room (room-specific scripted FX are left out). */
enum class MakerFx(val fxType: Int, val label: String, val icon: String, val color: Color) {
    NONE(0x00, "None", "M5 5l14 14M19 5L5 19", Color(0xFF9AA1A8)),
    LAVA(0x02, "Lava", "M3 15c2-2 4 2 6 0s4 2 6 0 4 2 6 0M3 19h18M8 10l1-3M14 9l1-4", Color(0xFFE77D73)),
    ACID(0x04, "Acid", "M3 15c2-2 4 2 6 0s4 2 6 0 4 2 6 0M3 19h18M8 9a1.5 1.5 0 100 3M15 6a2 2 0 100 4", Color(0xFF8FB7A0)),
    WATER(0x06, "Water", "M3 12c2-2 4 2 6 0s4 2 6 0 4 2 6 0M3 17c2-2 4 2 6 0s4 2 6 0 4 2 6 0", Color(0xFF7E9FBE)),
    SPORES(0x08, "Spores", "M6 6h.5M12 9h.5M18 5h.5M8 14h.5M16 13h.5M11 19h.5M19 18h.5M4 11h.5", Color(0xFFE6BC70)),
    RAIN(0x0A, "Rain", "M6 4l-2 6M12 4l-2 6M18 4l-2 6M9 13l-2 6M15 13l-2 6M21 13l-2 6", Color(0xFF7E9FBE)),
    FOG(0x0C, "Fog", "M3 8h14M6 12h15M3 16h12M8 20h10", Color(0xFFA9B4BF)),
    FIREFLEA(0x24, "Firefleas (dark)", "M12 4a3 3 0 100 6 3 3 0 000-6zM12 2v1M12 11v1M7 7h1M16 7h1M4 20h16", Color(0xFFE6BC70)),
    ;

    val isLiquid: Boolean get() = this == LAVA || this == ACID || this == WATER
}

/** Room look: the room's tileset and area effect, chosen from what vanilla rooms use. Applied with SMEDIT's theme path. */
@Composable
fun MakerRoomLookPanel(
    editorState: EditorState,
    parser: RomParser,
    library: MakerLibrary,
    roomNames: Map<Int, String>,
    onApplied: (String) -> Unit,
    onClose: () -> Unit,
    /** After a background change: rebuild the workspace so the room redraws. */
    onBackgroundChanged: () -> Unit = {},
) {
    val p = LocalMakerPalette.current
    // Only looks that passed the in-game check (boot, run 10 s) are offered.
    val tilesets = remember(library) { library.catalog.roomsByTileset.keys.sorted().filter { library.tilesetVerdict(it)?.ok == true } }
    val effects = remember(library) { MakerFx.entries.filter { it == MakerFx.NONE || library.fxVerdict(it.fxType)?.ok == true } }
    val thumbs = remember(library, parser) { tilesets.associateWith { tilesetThumbnail(parser, library, it) } }
    val currentFx = editorState.editVersion.let {
        runCatching { editorState.effectiveFxEntries(editorState.currentRoomId, parser).firstOrNull { e -> e.doorSelect == 0 }?.fxType }.getOrNull() ?: 0
    }
    Box(Modifier.fillMaxSize().background(Color(0x99101C27)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.92f).height(720.dp).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Room look", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Tilesets and effects the game uses in its own rooms. Changing the tileset redraws every block with that tileset's graphics.",
                        color = p.muted, fontSize = 13.sp)
                }
                MakerButton(onClick = onClose) { fg -> Text("Done", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            SectionLabel("Area effect")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                effects.forEach { fx ->
                    val on = (currentFx ?: -1) == fx.fxType
                    Column(
                        Modifier.weight(1f).height(84.dp).background(p.raised, RoundedCornerShape(8.dp))
                            .border(2.dp, if (on) p.accentDeep else p.border, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) {
                                val ok = editorState.applyBiomeTheme(
                                    BiomeTheme("maker-fx-${fx.name}", fx.label, "", tilesetId = null, fxType = fx.fxType, liquidFraction = 0.6), parser)
                                onApplied(if (ok) "Area effect: ${fx.label}" else "That effect can't be applied here")
                            }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(40.dp).background(Color(0xFF1B2A36), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            MakerIcon(fx.icon, fx.color, 26.dp)
                        }
                        Text(fx.label, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Backgrounds: rooms you made can use any background the game's rooms with this tileset use.
            val roomId = editorState.currentRoomId
            if (editorState.project.newRoomForPreviewId(roomId) != null) {
                val projectIds = remember(editorState.project.newRooms.size) { editorState.project.newRooms.map { it.previewRoomId }.toSet() }
                val choices = remember(parser, editorState.currentTilesetId) { makerBackgroundChoices(parser, editorState.currentTilesetId, projectIds).take(7) }
                val bgThumbs = remember(choices) { choices.associateWith { backgroundThumbnail(parser, it) } }
                val current = editorState.editVersion.let { editorState.makerBackground(roomId)?.bgDataPtr ?: 0 }
                SectionLabel("Background")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable
                    fun BgCard(label: String, on: Boolean, thumb: ImageBitmap?, pick: () -> Unit) {
                        Column(
                            Modifier.width(118.dp).background(p.raised, RoundedCornerShape(8.dp)).border(2.dp, if (on) p.accentDeep else p.border, RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = pick).padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.fillMaxWidth().height(62.dp).background(Color(0xFF0C0C18), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                thumb?.let { Image(it, contentDescription = label, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
                            }
                            Text(label, color = p.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    BgCard("Plain", current == 0, null) {
                        onApplied(editorState.setMakerBackground(roomId, null) ?: "Background: plain".also { onBackgroundChanged() })
                    }
                    choices.forEach { bg ->
                        BgCard(roomNames[bg.exampleRoomId] ?: "Background", current == bg.bgDataPtr, bgThumbs[bg]) {
                            onApplied(editorState.setMakerBackground(roomId, bg) ?: "Background from ${roomNames[bg.exampleRoomId] ?: "the game"}".also { onBackgroundChanged() })
                        }
                    }
                }
            }
            SectionLabel("Tileset")
            LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tilesets) { ts ->
                    val on = ts == editorState.currentTilesetId
                    val example = library.catalog.roomsByTileset[ts]?.firstOrNull()?.let { roomNames[it] } ?: ""
                    Column(
                        Modifier.background(p.raised, RoundedCornerShape(8.dp)).border(2.dp, if (on) p.accentDeep else p.border, RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) {
                                val ok = editorState.applyBiomeTheme(BiomeTheme("maker-ts-$ts", "Tileset $ts", "", tilesetId = ts), parser)
                                onApplied(if (ok) "Tileset $ts applied" else "That tileset can't be applied here")
                            }.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF0C0C18), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                            thumbs[ts]?.let { Image(it, contentDescription = "Tileset $ts", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(4.dp)) }
                        }
                        Text("Tileset $ts", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("e.g. $example", color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The first screen of [bg], as its example room draws it. */
private fun backgroundThumbnail(parser: RomParser, bg: MakerBackground): ImageBitmap? = runCatching {
    val room = parser.readRoomHeader(bg.exampleRoomId) ?: return null
    val tg = TileGraphics(parser)
    if (!tg.loadTileset(room.tileset)) return null
    val pixels = com.supermetroid.editor.rom.MapRenderer(parser, tg).renderLayer2(room) ?: return null
    val w = room.width * 256
    val img = BufferedImage(256, 224, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 224) for (x in 0 until 256) img.setRGB(x, y, pixels[y * w + x] or (0xFF shl 24))
    img.toComposeImageBitmap()
}.getOrNull()

/** A 6×4 sample of the tileset's most-used tiles, drawn from the ROM. */
private fun tilesetThumbnail(parser: RomParser, library: MakerLibrary, tileset: Int): ImageBitmap? {
    val tg = TileGraphics(parser)
    if (!tg.loadTileset(tileset)) return null
    val tiles = library.catalog.tiles[tileset].orEmpty()
        .filter { it.blockType == 0x8 || it.blockType == 0x1 || it.blockType == 0x0 }
        .map { it.metatile }.distinct()
        .filter { m -> tg.renderMetatile(m)?.any { it ushr 24 != 0 } == true }
        .take(24)
    if (tiles.isEmpty()) return null
    val cols = 6; val rows = (tiles.size + cols - 1) / cols
    val img = BufferedImage(cols * 16, rows * 16, BufferedImage.TYPE_INT_ARGB)
    tiles.forEachIndexed { i, m ->
        val px = tg.renderMetatile(m) ?: return@forEachIndexed
        val ox = (i % cols) * 16; val oy = (i / cols) * 16
        for (y in 0 until 16) for (x in 0 until 16) img.setRGB(ox + x, oy + y, px[y * 16 + x])
    }
    return img.toComposeImageBitmap()
}
