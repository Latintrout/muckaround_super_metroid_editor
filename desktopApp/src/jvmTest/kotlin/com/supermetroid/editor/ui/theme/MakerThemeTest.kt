package com.supermetroid.editor.ui.theme

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MakerThemeTest {
    private val noUserDir = Files.createTempDirectory("themes-empty").toFile()

    @Test
    fun `bundled themes load with light and dark`() {
        val result = MakerThemes.loadAll(noUserDir)
        assertEquals(emptyList(), result.problems)
        assertEquals(MakerThemes.BUNDLED_IDS, result.themes.map { it.id })
    }

    @Test
    fun `every bundled theme is readable in both brightnesses`() {
        for (spec in MakerThemes.loadAll(noUserDir).themes) for (b in listOf(MakerBrightness.LIGHT, MakerBrightness.DARK)) {
            val p = MakerThemes.resolve(spec, MakerAppearance(themeId = spec.id, brightness = b), systemDark = false)
            val where = "${spec.id}/$b"
            for ((name, surface) in listOf("background" to p.background, "panel" to p.panel, "raised" to p.raised)) {
                assertTrue(MakerThemes.contrast(p.text, surface) >= 7.0, "$where text on $name")
                assertTrue(MakerThemes.contrast(p.muted, surface) >= 4.5, "$where muted on $name: ${MakerThemes.contrast(p.muted, surface)}")
            }
            assertTrue(MakerThemes.contrast(p.onAccent, p.accent) >= 4.5, "$where label on accent")
            assertEquals(b == MakerBrightness.DARK, p.isDark)
        }
    }

    @Test
    fun `accent overrides keep labels readable`() {
        for ((hex, c) in MakerThemes.ACCENT_OVERRIDES) assertTrue(MakerThemes.contrast(c.first, c.third) >= 4.5, hex)
    }

    @Test
    fun `system brightness follows the OS`() {
        val spec = MakerThemes.loadAll(noUserDir).themes.first()
        val a = MakerAppearance(brightness = MakerBrightness.SYSTEM)
        assertTrue(MakerThemes.resolve(spec, a, systemDark = true).isDark)
        assertTrue(!MakerThemes.resolve(spec, a, systemDark = false).isDark)
    }

    @Test
    fun `user themes add or replace bundled ones and bad files are reported`() {
        val dir = Files.createTempDirectory("themes-user").toFile()
        val base = MakerThemes::class.java.getResourceAsStream("/maker-themes/slate.json")!!.bufferedReader().readText()
        dir.resolve("ocean.json").writeText(base.replace("\"slate\"", "\"ocean\"").replace("\"Slate\"", "\"Ocean\""))
        dir.resolve("rose.json").writeText(base.replace("\"slate\"", "\"rose\"").replace("\"Slate\"", "\"My Rose\""))
        dir.resolve("broken.json").writeText(base.replace("#7E9FBE", "blue"))
        val result = MakerThemes.loadAll(dir)
        assertTrue(result.themes.any { it.id == "ocean" })
        assertEquals("My Rose", result.themes.first { it.id == "rose" }.name)
        assertTrue(result.problems.single().startsWith("broken.json"), result.problems.toString())
    }

    @Test
    fun `appearance is saved per user and survives a reload`() {
        val file = Files.createTempDirectory("appearance").toFile().resolve("appearance.json")
        val state = MakerThemeState(userThemesDir = noUserDir, appearanceFile = file)
        state.update { it.copy(themeId = "sage", brightness = MakerBrightness.DARK, uiScalePercent = 120, roomBackdrop = RoomBackdrop.CHECKER) }
        val again = MakerThemeState(userThemesDir = noUserDir, appearanceFile = file)
        assertEquals(state.appearance, again.appearance)
        assertEquals("sage", again.currentSpec.id)
    }

    @Test
    fun `unknown theme id falls back to MODRET`() {
        val state = MakerThemeState(userThemesDir = noUserDir, appearanceFile = null)
        state.update { it.copy(themeId = "gone") }
        assertEquals("modret", state.currentSpec.id)
    }
}
