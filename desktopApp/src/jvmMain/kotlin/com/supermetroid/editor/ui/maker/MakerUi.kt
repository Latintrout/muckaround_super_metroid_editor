package com.supermetroid.editor.ui.maker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.ui.theme.LocalMakerPalette

/** 24x24 line icons (MODRET: clear silhouettes, consistent 2px stroke). */
object MakerIcons {
    const val SELECT = "M5 3l14 8-6 2-3 6z"
    const val MOVE = "M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3"
    const val BOX = "M4 6h16v12H4zM4 6l16 12"
    const val PAINT = "M18 3l3 3-10 10-4 1 1-4zM5 18c-1.5 0-2 1.5-2 3 2 0 3.5-.5 3.5-2"
    const val FILL = "M5 11l7-7 7 7-7 7zM5 11h14M20 16c0 1.7-.7 3-1.5 3S17 17.7 17 16l1.5-2.5z"
    const val ERASE = "M3 16l9-9 6 6-6 6H6zM14 19h7"
    const val PICK = "M15 3l6 6-3 3-6-6zM12 6l-8 8v4h4l8-8"
    const val UNDO = "M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3"
    const val REDO = "M15 14l5-5-5-5M20 9H10a6 6 0 000 12h3"
    const val GRID = "M3 9h18M3 15h18M9 3v18M15 3v18"
    const val SOLID = "M4 4h16v16H4zM4 12l8-8M4 20L20 4M12 20l8-8"
    const val CHEVRON = "M6 9l6 6 6-6"
    const val SEARCH = "M11 4a7 7 0 100 14 7 7 0 000-14zM20 20l-4-4"
    const val ROOM = "M3 5h18v14H3zM3 10h6v9M15 5v6h6"
    const val PLAY_FILLED = "M7 4l13 8-13 8z"
    const val CHECK = "M5 12l5 5 9-10"
    const val WARN = "M12 4v9M12 18v.5"
    const val TRASH = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"
    const val THEME = "M12 3a9 9 0 100 18c1 0 1.5-.8 1.5-1.6 0-1.4 1-2.4 2.4-2.4H18a3 3 0 003-3A9 9 0 0012 3zM7.5 12h.5M10 7.5h.5M15 7.5h.5"
    const val ADVANCED = "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0M14 4v4M8 10v4M16 16v4"
    const val TERRAIN = "M3 20l6-9 4 5 3-3 5 7z"
    const val HAZARDS = "M12 3l10 18H2zM12 10v5M12 18v.5"
    const val DOORS = "M6 3h12v18H6zM14 12h.5"
    const val ITEMS = "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"
    const val ENEMIES = "M5 11a7 7 0 0114 0v6l-2-1-2 1-2-1-2 1-2-1-2 1zM9 10h.5M14.5 10h.5"
    const val STATIONS = "M5 20h14M7 20V9h10v11M9 9V5h6v4M10 14h4"
    /** Start point: a flag on a pole. */
    const val START = "M6 21V3M6 4h12l-3 4.5 3 4.5H6"
    const val DECOR = "M12 21V11M12 11c0-4 3-7 7-7 0 4-3 7-7 7zM12 14c0-3-2.5-5-6-5 0 3 2.5 5 6 5z"
}

/** Draws a [MakerIcons] path scaled from its 24-unit box. */
@Composable
fun MakerIcon(path: String, color: Color, size: Dp = 24.dp, filled: Boolean = false, strokeWidth: Float = 2f) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(parsed, color, style = if (filled) Fill else Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Tactile MODRET button: raised neutral, or filled with [fill] when selected/primary. */
@Composable
fun MakerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Color? = null,
    onFill: Color? = null,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val p = LocalMakerPalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        !enabled -> p.inset
        fill != null -> fill
        selected -> p.accent
        hovered -> p.inset
        else -> p.raised
    }
    val fg = when {
        !enabled -> p.muted
        fill != null -> onFill ?: p.text
        selected -> p.onAccent
        else -> p.text
    }
    Row(
        modifier
            .height(height)
            .shadow(if (enabled && !selected && fill == null) 1.dp else 0.dp, shape)
            .background(bg, shape)
            .border(1.dp, if (selected) p.accentDeep else if (fill != null) fill else p.border, shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content(fg) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalMakerPalette.current
    Text(text.uppercase(), modifier = modifier, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
}

/** Small key-cap label for shortcuts. */
@Composable
fun KeyCap(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}
