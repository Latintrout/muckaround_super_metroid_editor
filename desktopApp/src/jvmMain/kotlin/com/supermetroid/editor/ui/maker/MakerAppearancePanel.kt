package com.supermetroid.editor.ui.maker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.ui.theme.LocalMakerPalette
import com.supermetroid.editor.ui.theme.MakerBrightness
import com.supermetroid.editor.ui.theme.MakerThemeState
import com.supermetroid.editor.ui.theme.MakerThemes
import com.supermetroid.editor.ui.theme.RoomBackdrop
import java.awt.Desktop

/** Settings › Appearance, drawn over the Maker (no extra OS window). Changes apply and save immediately. */
@Composable
fun MakerAppearancePanel(state: MakerThemeState, onClose: () -> Unit) {
    val p = LocalMakerPalette.current
    val a = state.appearance
    Box(Modifier.fillMaxSize().background(Color(0x99101C27)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 980.dp).fillMaxWidth(0.9f).background(p.background, RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Appearance", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Themes restyle the editor. Room tiles, sprites and item art always use the game's own pixels and colours.", color = p.muted, fontSize = 13.sp)
                }
                MakerButton(onClick = onClose) { fg -> Text("Done", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }

            SectionLabel("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.themes.forEach { spec ->
                    val on = spec.id == state.currentSpec.id
                    Column(
                        Modifier.weight(1f).background(p.raised, RoundedCornerShape(10.dp)).border(2.dp, if (on) p.accentDeep else p.border, RoundedCornerShape(10.dp))
                            .clickable(role = Role.RadioButton) { state.update { it.copy(themeId = spec.id) } }.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (s in listOf(spec.light, spec.dark)) {
                                Column(
                                    Modifier.weight(1f).height(84.dp).background(MakerThemes.color(s.background), RoundedCornerShape(6.dp))
                                        .border(1.dp, MakerThemes.color(s.border), RoundedCornerShape(6.dp)).padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(Modifier.fillMaxWidth().height(8.dp).background(MakerThemes.color(spec.accent.main), RoundedCornerShape(3.dp)))
                                    Box(Modifier.fillMaxWidth(0.7f).height(6.dp).background(MakerThemes.color(s.muted), RoundedCornerShape(3.dp)))
                                    // The room view: never changes with the theme.
                                    Box(Modifier.fillMaxSize().background(Color(0xFF1B2A36), RoundedCornerShape(3.dp)))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(spec.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(spec.tag, color = p.muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("Brightness")
                    Segmented(
                        listOf(MakerBrightness.LIGHT to "Light", MakerBrightness.DARK to "Dark", MakerBrightness.SYSTEM to "Follow system"),
                        a.brightness,
                    ) { b -> state.update { it.copy(brightness = b) } }

                    SectionLabel("Accent colour")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Swatch(state.currentSpec.accent.main.let(MakerThemes::color), a.accentOverride == null, "Theme") { state.update { it.copy(accentOverride = null) } }
                        MakerThemes.ACCENT_OVERRIDES.forEach { (hex, c) ->
                            Swatch(c.first, a.accentOverride.equals(hex, ignoreCase = true), hex) { state.update { it.copy(accentOverride = hex) } }
                        }
                    }
                    Text("Accent only colours the active tool, selection and main buttons.", color = p.muted, fontSize = 12.sp)

                    SectionLabel("Room backdrop")
                    Segmented(RoomBackdrop.entries.map { it to it.label }, a.roomBackdrop) { b -> state.update { it.copy(roomBackdrop = b) } }
                    Text("Shown behind the room image, never over the game's pixels.", color = p.muted, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("Comfort")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("UI scale", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                        Slider(
                            value = a.uiScalePercent.toFloat(), valueRange = 80f..150f, steps = 6,
                            onValueChange = { v -> state.update { it.copy(uiScalePercent = (Math.round(v / 10f) * 10)) } },
                            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.inset),
                            modifier = Modifier.weight(1f),
                        )
                        Text("${a.uiScalePercent}%", color = p.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                    }
                    Toggle("High contrast", "Stronger borders and text for the editor", a.highContrast) { v -> state.update { it.copy(highContrast = v) } }
                    Toggle("Reduce motion", "No sliding panels or animated highlights", a.reduceMotion) { v -> state.update { it.copy(reduceMotion = v) } }
                    Toggle("Hint bar", "Show what a click will do under the room", a.showHintBar) { v -> state.update { it.copy(showHintBar = v) } }
                }
            }

            SectionLabel("Your themes")
            Text("Add or share a theme by dropping its .json file into this folder, then press Reload.", color = p.muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(MakerThemes.userThemesDir().path, color = p.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).background(p.inset, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp))
                MakerButton(onClick = {
                    val dir = MakerThemes.userThemesDir().apply { mkdirs() }
                    runCatching { Desktop.getDesktop().open(dir) }
                }, height = 38.dp) { fg -> Text("Open folder", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                MakerButton(onClick = { state.reload() }, height = 38.dp) { fg -> Text("Reload", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (state.problems.isNotEmpty()) {
                state.problems.forEach { Text("Skipped: $it", color = MakerFixedColorsWarning, fontSize = 12.sp) }
            }
        }
    }
}

private val MakerFixedColorsWarning = com.supermetroid.editor.ui.theme.MakerFixedColors.warning

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val p = LocalMakerPalette.current
    Row(Modifier.fillMaxWidth().background(p.inset, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp)).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(42.dp).background(if (on) p.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(role = Role.RadioButton) { onPick(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) p.onAccent else p.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val p = LocalMakerPalette.current
    Box(
        Modifier.size(44.dp).border(3.dp, if (selected) p.text else Color.Transparent, RoundedCornerShape(22.dp)).padding(5.dp)
            .background(color, RoundedCornerShape(17.dp)).clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
    )
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalMakerPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
            .clickable(role = Role.Switch) { onChange(!on) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = p.muted, fontSize = 12.sp)
        }
        Box(Modifier.size(width = 52.dp, height = 30.dp).background(if (on) com.supermetroid.editor.ui.theme.MakerFixedColors.ok else p.inset, RoundedCornerShape(15.dp)).padding(4.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(22.dp).background(Color(0xFFF7F1E6), RoundedCornerShape(11.dp)))
        }
    }
}
