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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.MakerLoadoutDef
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerFixedColors

/** Item bits ($09A2), in the order the pause screen lists them. */
val MAKER_ITEMS = listOf(
    "Varia Suit" to 0x0001, "Gravity Suit" to 0x0020, "Morph Ball" to 0x0004, "Bombs" to 0x1000,
    "Spring Ball" to 0x0002, "Screw Attack" to 0x0008, "Hi-Jump Boots" to 0x0100, "Space Jump" to 0x0200,
    "Speed Booster" to 0x2000, "Grapple Beam" to 0x4000, "X-Ray Scope" to 0x8000,
)

/** Beam bits ($09A6). */
val MAKER_BEAMS = listOf("Charge" to 0x1000, "Ice" to 0x0002, "Wave" to 0x0001, "Spazer" to 0x0004, "Plasma" to 0x0008)

/** The counts a loadout has: label, value, range, step. */
private data class Count(val label: String, val get: (MakerLoadoutDef) -> Int, val set: (MakerLoadoutDef, Int) -> MakerLoadoutDef, val max: Int, val step: Int)

private val COUNTS = listOf(
    Count("Energy Tanks", { it.energyTanks }, { d, v -> d.copy(energyTanks = v) }, 14, 1),
    Count("Reserve Tanks", { it.reserveTanks }, { d, v -> d.copy(reserveTanks = v) }, 4, 1),
    Count("Missiles", { it.missiles }, { d, v -> d.copy(missiles = v) }, 230, 5),
    Count("Super Missiles", { it.supers }, { d, v -> d.copy(supers = v) }, 50, 5),
    Count("Power Bombs", { it.powerBombs }, { d, v -> d.copy(powerBombs = v) }, 50, 5),
)

/**
 * Loadouts: pick one to play with, or edit and save your own (kept in the project). [presets] are built in;
 * [saved] are the project's. [onUse] plays with a loadout; [onSave]/[onDelete] change the saved list.
 */
@Composable
fun MakerLoadoutPanel(
    presets: List<MakerLoadoutDef>, saved: List<MakerLoadoutDef>, current: MakerLoadoutDef,
    onUse: (MakerLoadoutDef) -> Unit, onSave: (MakerLoadoutDef) -> Unit, onDelete: (MakerLoadoutDef) -> Unit, onClose: () -> Unit,
) {
    val p = LocalMakerPalette.current
    var draft by remember { mutableStateOf(current) }
    Box(
        Modifier.fillMaxSize().background(Color(0x99101C27))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 980.dp).fillMaxWidth(0.9f).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Left: the loadouts to pick from.
            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Loadouts", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                SectionLabel("Built in")
                presets.forEach { l -> MakerButton(onClick = { draft = l }, selected = draft.name == l.name, modifier = Modifier.fillMaxWidth(), height = 36.dp) { fg -> Text(l.name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) } }
                SectionLabel("Yours")
                if (saved.isEmpty()) Text("Save one on the right.", color = p.muted, fontSize = 12.sp)
                saved.forEach { l -> MakerButton(onClick = { draft = l }, selected = draft.name == l.name, modifier = Modifier.fillMaxWidth(), height = 36.dp) { fg -> Text(l.name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) } }
            }
            // Right: what it has.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Name")
                BasicTextField(draft.name, { draft = draft.copy(name = it.replace("\n", "")) }, singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 15.sp), cursorBrush = SolidColor(p.text),
                    modifier = Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(10.dp))
                SectionLabel("Items")
                FlowChips(MAKER_ITEMS, draft.items) { bit -> draft = draft.copy(items = draft.items xor bit) }
                SectionLabel("Beams")
                FlowChips(MAKER_BEAMS, draft.beams) { bit -> draft = draft.copy(beams = draft.beams xor bit) }
                if (draft.beams and 0x0004 != 0 && draft.beams and 0x0008 != 0) Text("Spazer and Plasma can't be on together: Samus has both, with Plasma on.", color = p.muted, fontSize = 12.sp)
                SectionLabel("Tanks and ammo")
                COUNTS.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.label, color = p.text, fontSize = 13.sp, modifier = Modifier.width(140.dp))
                        MakerButton(onClick = { draft = c.set(draft, (c.get(draft) - c.step).coerceAtLeast(0)) }, height = 30.dp,
                            modifier = Modifier.semantics { contentDescription = "Fewer ${c.label}" }) { fg -> Text("−", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        Text(c.get(draft).toString(), color = p.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
                        MakerButton(onClick = { draft = c.set(draft, (c.get(draft) + c.step).coerceAtMost(c.max)) }, height = 30.dp,
                            modifier = Modifier.semantics { contentDescription = "More ${c.label}" }) { fg -> Text("+", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isPreset = presets.any { it.name == draft.name }
                    if (saved.any { it.name == draft.name }) MakerButton(onClick = { onDelete(draft) }) { fg ->
                        MakerIcon(MakerIcons.TRASH, MakerFixedColors.danger, 16.dp); Text("Delete", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    MakerButton(onClick = { onSave(if (isPreset) draft.copy(name = draft.name + " (mine)") else draft) }, enabled = draft.name.isNotBlank()) { fg ->
                        Text(if (isPreset) "Save a copy" else "Save", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    MakerButton(onClick = { onUse(draft); onClose() }, fill = MakerFixedColors.play, onFill = MakerFixedColors.onPlay) { fg ->
                        Text("Use this loadout", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Toggle chips for bit flags: on = the bit is set. */
@Composable
private fun FlowChips(options: List<Pair<String, Int>>, value: Int, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, bit) ->
                    MakerButton(onClick = { onToggle(bit) }, selected = value and bit != 0, height = 32.dp,
                        modifier = Modifier.semantics { contentDescription = "$label ${if (value and bit != 0) "on" else "off"}" }) { fg ->
                        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
