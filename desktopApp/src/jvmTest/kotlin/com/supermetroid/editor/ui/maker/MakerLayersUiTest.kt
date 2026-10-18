package com.supermetroid.editor.ui.maker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.TileOverlay
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): layer eyes, and dragging the liquid surface with Select. Screenshots go to MAKER_UI_SHOTS. */
@OptIn(ExperimentalTestApi::class)
class MakerLayersUiTest {
    @Test
    fun `layer eyes and liquid level handle`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Layers", Files.createTempDirectory("maker-layers").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        // Water, as Room look applies it.
        assertTrue(editor.applyBiomeTheme(BiomeTheme("t-water", "Water", "", tilesetId = null, fxType = 0x06, liquidFraction = 0.6), parser))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, initialTool = MakerTool.SELECT)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null && controls.fitZoom != null }
        controls.fit(); waitForIdle()
        fun shot(name: String) { shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

        // Eyes: hide and show the background and the liquid.
        assertTrue(TileOverlay.LAYER2 in controls.overlays.orEmpty() && TileOverlay.LIQUID in controls.overlays.orEmpty())
        onNodeWithContentDescription("Hide background").performClick(); waitForIdle()
        onNodeWithContentDescription("Hide liquid").performClick(); waitForIdle()
        assertTrue(TileOverlay.LAYER2 !in controls.overlays.orEmpty() && TileOverlay.LIQUID !in controls.overlays.orEmpty(), "both hidden")
        shot("layers-hidden")
        onNodeWithContentDescription("Show background").performClick(); waitForIdle()
        onNodeWithContentDescription("Show liquid").performClick(); waitForIdle()
        assertTrue(TileOverlay.LAYER2 in controls.overlays.orEmpty() && TileOverlay.LIQUID in controls.overlays.orEmpty(), "both shown again")

        // Liquid: drag the grip (left edge, surface row) up five rows.
        val (fx, y0) = requireNotNull(editor.makerLiquid(parser))
        assertEquals(MakerFx.WATER, fx)
        val row = y0 / 16
        fun pointOf(bx: Int, by: Int): Offset {
            var px = -1f
            for (x in 393 until 1255 step 2) { onRoot().performMouseInput { moveTo(Offset(x.toFloat(), 500f)) }; if (editor.hoverBlockX == bx) { px = x + 1f; break } }
            check(px >= 0) { "column $bx" }
            for (y in 118 until 880 step 2) { val p = Offset(px, y + 1f); onRoot().performMouseInput { moveTo(p) }; if (editor.hoverBlockY == by) return p }
            error("block $bx,$by not on screen")
        }
        val from = pointOf(0, row); val to = pointOf(0, row - 5)
        onRoot().performMouseInput { moveTo(from); press() }; waitForIdle()
        onRoot().performMouseInput { moveTo(Offset(from.x, (from.y + to.y) / 2)) }; waitForIdle()
        onRoot().performMouseInput { moveTo(to) }; waitForIdle()
        onRoot().performMouseInput { release() }
        waitForIdle()
        assertEquals((row - 5) * 16, editor.makerLiquid(parser)?.second, "surface moved up five rows")
        shot("liquid-raised")
        editor.undo(); waitForIdle()
        assertEquals(y0, editor.makerLiquid(parser)?.second, "one undo puts it back")
    }
}
