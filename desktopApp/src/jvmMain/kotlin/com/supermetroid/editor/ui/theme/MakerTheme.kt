package com.supermetroid.editor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Maker UI themes (MODRET design language).
 *
 * A theme is design tokens for the editor chrome only: surfaces, borders, text and one accent.
 * Game art (room tiles, part previews, sprites, the emulator view) is never drawn with these
 * colours, and [MakerFixedColors] keeps category/PLAY/delete meanings identical in every theme.
 * Themes are JSON files: bundled under resources/maker-themes, plus user files in ~/.smedit/themes.
 */
@Serializable
data class MakerThemeSpec(
    val id: String,
    val name: String,
    val tag: String = "",
    val accent: AccentSpec,
    val light: SurfaceSpec,
    val dark: SurfaceSpec,
) {
    @Serializable
    data class AccentSpec(val main: String, val deep: String, val on: String)

    @Serializable
    data class SurfaceSpec(
        val desk: String,
        val background: String,
        val panel: String,
        val raised: String,
        val inset: String,
        val border: String,
        val text: String,
        val muted: String,
    )
}

/** Resolved colours for one theme in one brightness. */
@Immutable
data class MakerPalette(
    val themeId: String,
    val isDark: Boolean,
    val desk: Color,
    val background: Color,
    val panel: Color,
    val raised: Color,
    val inset: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
)

/** Colours whose meaning must never change with the theme. Paired with shapes and labels in the UI. */
object MakerFixedColors {
    val terrain = Color(0xFF8FB7A0)
    val hazards = Color(0xFFE6BC70)
    val doors = Color(0xFF7E9FBE)
    val items = Color(0xFFE77D73)
    val enemies = Color(0xFFA78BC3)
    val decor = Color(0xFFF0B79C)
    val stations = Color(0xFF5FA7A0)
    val play = Color(0xFF8FB7A0)
    val playDeep = Color(0xFF527F70)
    val onPlay = Color(0xFF18302A)
    val danger = Color(0xFFC9675D)
    val ok = Color(0xFF527F70)
    val warning = Color(0xFFA9802F)
}

enum class MakerBrightness { LIGHT, DARK, SYSTEM }

enum class RoomBackdrop(val label: String) { DARK("Dark"), CHECKER("Checkerboard"), GAME("Game background") }

/** Per-user appearance settings, stored in ~/.smedit/appearance.json (never in a project). */
@Serializable
data class MakerAppearance(
    val themeId: String = MakerThemes.DEFAULT_ID,
    val brightness: MakerBrightness = MakerBrightness.LIGHT,
    /** Optional accent override, e.g. "#5FA7A0"; null uses the theme's own accent. */
    val accentOverride: String? = null,
    val uiScalePercent: Int = 100,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val showHintBar: Boolean = true,
    val roomBackdrop: RoomBackdrop = RoomBackdrop.GAME,
)

object MakerThemes {
    const val DEFAULT_ID = "modret"
    val BUNDLED_IDS = listOf("modret", "rose", "sage", "slate", "mono")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    data class LoadResult(val themes: List<MakerThemeSpec>, val problems: List<String>)

    fun userThemesDir(home: File = File(System.getProperty("user.home"))): File = File(File(home, ".smedit"), "themes")
    fun appearanceFile(home: File = File(System.getProperty("user.home"))): File = File(File(home, ".smedit"), "appearance.json")

    fun parse(source: String): MakerThemeSpec {
        val spec = json.decodeFromString(MakerThemeSpec.serializer(), source)
        require(spec.id.matches(Regex("^[a-z0-9][a-z0-9_-]{0,39}$"))) { "Theme id '${spec.id}' must be lower-case letters, digits, - or _" }
        require(spec.name.isNotBlank()) { "Theme '${spec.id}' needs a name" }
        val colours = with(spec.accent) { listOf(main, deep, on) } +
            listOf(spec.light, spec.dark).flatMap { with(it) { listOf(desk, background, panel, raised, inset, border, text, muted) } }
        colours.firstOrNull { !it.matches(hex) }?.let { error("Theme '${spec.id}': '$it' is not a #RRGGBB colour") }
        return spec
    }

    /** Bundled themes first, then user themes; a user theme with a bundled id replaces it. */
    fun loadAll(userDir: File? = userThemesDir()): LoadResult {
        val problems = mutableListOf<String>()
        val byId = linkedMapOf<String, MakerThemeSpec>()
        for (id in BUNDLED_IDS) {
            val text = MakerThemes::class.java.getResourceAsStream("/maker-themes/$id.json")?.bufferedReader()?.use { it.readText() }
            if (text == null) { problems += "Bundled theme '$id' is missing"; continue }
            runCatching { parse(text) }.onSuccess { byId[it.id] = it }.onFailure { problems += "Bundled theme '$id': ${it.message}" }
        }
        userDir?.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }?.sortedBy { it.name }?.forEach { file ->
            runCatching { parse(file.readText()) }
                .onSuccess { byId[it.id] = it }
                .onFailure { problems += "${file.name}: ${it.message}" }
        }
        return LoadResult(byId.values.toList(), problems)
    }

    fun loadAppearance(file: File = appearanceFile()): MakerAppearance =
        runCatching { json.decodeFromString(MakerAppearance.serializer(), file.readText()) }.getOrDefault(MakerAppearance())

    fun saveAppearance(appearance: MakerAppearance, file: File = appearanceFile()) {
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(MakerAppearance.serializer(), appearance))
    }

    fun color(hexColour: String): Color = Color(0xFF000000 or hexColour.removePrefix("#").toLong(16))

    fun resolve(spec: MakerThemeSpec, appearance: MakerAppearance, systemDark: Boolean): MakerPalette {
        val dark = when (appearance.brightness) {
            MakerBrightness.LIGHT -> false
            MakerBrightness.DARK -> true
            MakerBrightness.SYSTEM -> systemDark
        }
        val s = if (dark) spec.dark else spec.light
        val override = appearance.accentOverride?.takeIf { it.matches(hex) }?.let { ACCENT_OVERRIDES[it.uppercase()] }
        val accent = override ?: Triple(color(spec.accent.main), color(spec.accent.deep), color(spec.accent.on))
        var text = color(s.text)
        var muted = color(s.muted)
        var border = color(s.border)
        if (appearance.highContrast) {
            muted = lerp(muted, text, 0.55f)
            border = lerp(border, text, 0.45f)
        }
        return MakerPalette(
            themeId = spec.id, isDark = dark,
            desk = color(s.desk), background = color(s.background), panel = color(s.panel), raised = color(s.raised),
            inset = color(s.inset), border = border, text = text, muted = muted,
            accent = accent.first, accentDeep = accent.second, onAccent = accent.third,
        )
    }

    /** Accent choices offered in Settings: main to (deep, readable text colour). */
    val ACCENT_OVERRIDES: Map<String, Triple<Color, Color, Color>> = mapOf(
        "#E77D73" to Triple(Color(0xFFE77D73), Color(0xFFC9675D), Color(0xFF252B30)),
        "#5FA7A0" to Triple(Color(0xFF5FA7A0), Color(0xFF3F7F79), Color(0xFF10221F)),
        "#7E9FBE" to Triple(Color(0xFF7E9FBE), Color(0xFF4F6F8E), Color(0xFF16222E)),
        "#A78BC3" to Triple(Color(0xFFA78BC3), Color(0xFF73589A), Color(0xFF1F1828)),
        "#E6BC70" to Triple(Color(0xFFE6BC70), Color(0xFFA9802F), Color(0xFF2A2210)),
        "#F0B79C" to Triple(Color(0xFFF0B79C), Color(0xFFB97D60), Color(0xFF2A1C14)),
    )

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
    )

    /** WCAG relative-luminance contrast ratio. */
    fun contrast(a: Color, b: Color): Double {
        fun lum(c: Color): Double {
            fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}

/** Material colours derived from the palette, so stock Material widgets inside the Maker match it. */
fun MakerPalette.toColorScheme(): ColorScheme = if (isDark) darkColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentDeep, onPrimaryContainer = text,
    secondary = MakerFixedColors.play, onSecondary = MakerFixedColors.onPlay,
    background = background, onBackground = text, surface = panel, onSurface = text,
    surfaceVariant = raised, onSurfaceVariant = muted, outline = border, outlineVariant = border,
    error = MakerFixedColors.danger,
) else lightColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentDeep, onPrimaryContainer = text,
    secondary = MakerFixedColors.play, onSecondary = MakerFixedColors.onPlay,
    background = background, onBackground = text, surface = panel, onSurface = text,
    surfaceVariant = raised, onSurfaceVariant = muted, outline = border, outlineVariant = border,
    error = MakerFixedColors.danger,
)

val LocalMakerPalette = staticCompositionLocalOf<MakerPalette> { error("No MakerPalette provided") }
