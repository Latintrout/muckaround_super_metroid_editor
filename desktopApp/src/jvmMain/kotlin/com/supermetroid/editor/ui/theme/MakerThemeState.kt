package com.supermetroid.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File

/** Loaded themes plus the user's appearance choice. Changes are saved per user immediately. */
class MakerThemeState(
    private val userThemesDir: File? = MakerThemes.userThemesDir(),
    private val appearanceFile: File? = MakerThemes.appearanceFile(),
) {
    var themes by mutableStateOf(emptyList<MakerThemeSpec>())
        private set
    var problems by mutableStateOf(emptyList<String>())
        private set
    var appearance by mutableStateOf(appearanceFile?.let { MakerThemes.loadAppearance(it) } ?: MakerAppearance())
        private set

    init { reload() }

    /** Re-read bundled and user theme files (e.g. after dropping a new file into ~/.smedit/themes). */
    fun reload() {
        val result = MakerThemes.loadAll(userThemesDir)
        themes = result.themes
        problems = result.problems
    }

    fun update(change: (MakerAppearance) -> MakerAppearance) {
        appearance = change(appearance)
        appearanceFile?.let { runCatching { MakerThemes.saveAppearance(appearance, it) } }
    }

    val currentSpec: MakerThemeSpec
        get() = themes.firstOrNull { it.id == appearance.themeId }
            ?: themes.firstOrNull { it.id == MakerThemes.DEFAULT_ID }
            ?: error("No Maker themes could be loaded: $problems")

    fun palette(systemDark: Boolean): MakerPalette = MakerThemes.resolve(currentSpec, appearance, systemDark)
}

/**
 * Applies the Maker theme to [content]: palette, matching Material colours and UI scale.
 * Only editor chrome reads these; room/sprite rendering keeps the ROM's own colours.
 */
@Composable
fun MakerThemeProvider(state: MakerThemeState, systemDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = state.palette(systemDark)
    val density = LocalDensity.current
    val scale = state.appearance.uiScalePercent.coerceIn(80, 150) / 100f
    CompositionLocalProvider(
        LocalMakerPalette provides palette,
        LocalDensity provides Density(density.density * scale, density.fontScale),
    ) {
        MaterialTheme(colorScheme = palette.toColorScheme(), typography = MaterialTheme.typography, content = content)
    }
}
