package com.supermetroid.editor.ui

import com.supermetroid.editor.data.AppSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class TileOverlayPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun enabled(preferences: TileOverlayPreferences) = preferences.toggles.filterValues { it }.keys

    @Test fun `legacy config preserves the existing default layers without writing on open`() {
        val settings = json.decodeFromString<AppSettings>("{}")
        assertNull(settings.roomEditorEnabledTileOverlays)
        val preferences = TileOverlayPreferences(settings.roomEditorEnabledTileOverlays) { fail("Opening a view must not save config") }
        assertEquals(setOf(TileOverlay.ITEMS, TileOverlay.ENEMIES, TileOverlay.LAYER2, TileOverlay.LIQUID), enabled(preferences))
    }

    @Test fun `each toggle persists immediately and recreated views restore the selection`() {
        var settings = AppSettings(theme = "LIGHT", fontSize = "LARGER", patchFavoriteIds = listOf("favorite"))
        var writes = 0
        val preferences = TileOverlayPreferences(settings.roomEditorEnabledTileOverlays) {
            writes++
            settings = settings.copy(roomEditorEnabledTileOverlays = it)
        }
        preferences.toggle(TileOverlay.SCROLLS)
        assertEquals(1, writes)
        assertContains(settings.roomEditorEnabledTileOverlays!!, "SCROLLS")
        preferences.toggle(TileOverlay.SCREEN_COORDINATES)
        preferences.toggle(TileOverlay.ENEMIES)
        assertEquals(3, writes)
        val restored = TileOverlayPreferences(settings.roomEditorEnabledTileOverlays) { fail("Restoring must not write") }
        assertEquals(enabled(preferences), enabled(restored))
        assertEquals("LIGHT", settings.theme)
        assertEquals("LARGER", settings.fontSize)
        assertEquals(listOf("favorite"), settings.patchFavoriteIds)
    }

    @Test fun `configuration round trip restores custom layers after restart`() {
        val settings = AppSettings(roomEditorEnabledTileOverlays = listOf("SCROLL_PLMS", "SCREEN_COORDINATES", "SOLID"))
        val decoded = json.decodeFromString<AppSettings>(json.encodeToString(settings))
        val restored = TileOverlayPreferences(decoded.roomEditorEnabledTileOverlays) { fail("Restoring must not write") }
        assertEquals(setOf(TileOverlay.SCROLL_PLMS, TileOverlay.SCREEN_COORDINATES, TileOverlay.SOLID), enabled(restored))
    }

    @Test fun `all off remains empty instead of resurrecting defaults`() {
        var saved: List<String>? = null
        val preferences = TileOverlayPreferences(null) { saved = it }
        enabled(preferences).toList().forEach(preferences::toggle)
        assertEquals(emptyList(), saved)
        val settings = json.decodeFromString<AppSettings>(json.encodeToString(AppSettings(roomEditorEnabledTileOverlays = saved)))
        val restored = TileOverlayPreferences(settings.roomEditorEnabledTileOverlays) { fail("Restoring must not write") }
        assertTrue(enabled(restored).isEmpty())
    }

    @Test fun `unknown names and duplicates are harmless and never force default layers on`() {
        val preferences = TileOverlayPreferences(listOf("FUTURE_LAYER", "SCROLLS", "SCROLLS")) { }
        assertEquals(setOf(TileOverlay.SCROLLS), enabled(preferences))
        assertTrue(enabled(TileOverlayPreferences(listOf("REMOVED_LAYER")) { }).isEmpty())
    }
}
