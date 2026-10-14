package com.supermetroid.editor.ui

import androidx.compose.runtime.mutableStateMapOf
import com.supermetroid.editor.data.AppConfig

/** View preferences, never room/project edits. Persist each click, not each composition. */
internal class TileOverlayPreferences(
    initial: List<String>? = AppConfig.load().roomEditorEnabledTileOverlays,
    private val persist: (List<String>) -> Unit = { enabled ->
        AppConfig.update { copy(roomEditorEnabledTileOverlays = enabled) }
    },
) {
    private val selected = initial?.toSet() ?: setOf(
        TileOverlay.ITEMS.name, TileOverlay.ENEMIES.name, TileOverlay.LAYER2.name, TileOverlay.LIQUID.name,
    )
    private val state = mutableStateMapOf(*TileOverlay.values().map { it to (it.name in selected) }.toTypedArray())
    val toggles: Map<TileOverlay, Boolean> get() = state

    fun toggle(overlay: TileOverlay) {
        state[overlay] = state[overlay] != true
        // Names, not labels/ordinals: UI wording and enum order may change independently.
        persist(TileOverlay.values().filter { state[it] == true }.map { it.name })
    }
}
