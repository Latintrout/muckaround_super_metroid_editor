package com.supermetroid.editor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in (MAKER_UI_SHOTS=<dir>): renders every theme in Light and Dark offscreen to a PNG. */
class MakerThemeRenderTest {
    @Test
    fun `render theme gallery offscreen`() {
        val outDir = System.getenv("MAKER_UI_SHOTS")
        assumeTrue(outDir != null)
        val empty = Files.createTempDirectory("themes-none").toFile()
        val state = MakerThemeState(userThemesDir = empty, appearanceFile = null)
        val scene = ImageComposeScene(width = 1300, height = 560, density = Density(1f)) {
            Column(Modifier.fillMaxSize().background(Color(0xFF6B6B6B)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (brightness in listOf(MakerBrightness.LIGHT, MakerBrightness.DARK)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (spec in state.themes) {
                            state.update { it.copy(themeId = spec.id, brightness = brightness) }
                            MakerThemeProvider(state, systemDark = false) { Swatch() }
                        }
                    }
                }
            }
        }
        val image = scene.render()
        val file = File(outDir, "theme-gallery.png").apply { parentFile.mkdirs() }
        file.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        scene.close()
        assertTrue(file.length() > 0)
    }

    @Composable
    private fun Swatch() {
        val p = LocalMakerPalette.current
        Column(
            Modifier.width(240.dp).background(p.background, RoundedCornerShape(10.dp)).border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(p.themeId.uppercase() + if (p.isDark) " · DARK" else " · LIGHT", color = p.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(width = 96.dp, height = 40.dp).background(p.accent, RoundedCornerShape(7.dp)).padding(10.dp)) {
                    Text("Paint", color = p.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Box(Modifier.size(width = 96.dp, height = 40.dp).background(p.raised, RoundedCornerShape(7.dp)).border(1.dp, p.border, RoundedCornerShape(7.dp)).padding(10.dp)) {
                    Text("Erase", color = p.text, fontSize = 14.sp)
                }
            }
            Box(Modifier.size(width = 216.dp, height = 70.dp).background(Color(0xFF1B2A36), RoundedCornerShape(6.dp)).padding(8.dp)) {
                Text("room view (game art, never themed)", color = Color(0xFFB9C4CC), fontSize = 11.sp)
            }
            Text("Secondary text on panel", color = p.muted, fontSize = 12.sp, modifier = Modifier.background(p.panel).padding(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in listOf(MakerFixedColors.terrain, MakerFixedColors.hazards, MakerFixedColors.doors, MakerFixedColors.items, MakerFixedColors.enemies, MakerFixedColors.play)) {
                    Box(Modifier.size(20.dp).background(c, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
