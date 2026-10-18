package com.supermetroid.editor.ui.maker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in (V1_ROM): Select's right-click menu and turning the Start point around. Screenshots go to MAKER_UI_SHOTS. */
@OptIn(ExperimentalTestApi::class)
class MakerBlockMenuUiTest {
    @Test
    fun `right-click menu places the start point and clicking it again turns samus`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Menu", Files.createTempDirectory("maker-menu").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, initialTool = MakerTool.SELECT)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null && controls.fitZoom != null }
        controls.fit(); waitForIdle()
        fun shot(name: String) { shots?.let { ImageIO.write(onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }
        // Column first, then row: each mouse move waits for the UI, so don't scan every pixel.
        fun pointOf(bx: Int, by: Int): Offset {
            var px = -1f
            for (x in 395 until 1255 step 3) {
                onRoot().performMouseInput { moveTo(Offset(x.toFloat(), 400f)) }
                if (editor.hoverBlockX == bx) { px = x + 1f; break }
            }
            check(px >= 0) { "column $bx not on screen" }
            for (y in 120 until 880 step 3) {
                val p = Offset(px, y + 1f)
                onRoot().performMouseInput { moveTo(p) }
                if (editor.hoverBlockY == by && editor.hoverBlockX == bx) return p
            }
            error("block $bx,$by not on screen")
        }

        // Select: right-click a block, "Put the Start point here".
        val p = pointOf(60, 60)
        onRoot().performMouseInput { press(androidx.compose.ui.test.MouseButton.Secondary); release(androidx.compose.ui.test.MouseButton.Secondary) }
        waitForIdle()
        onNodeWithText("Play from here").assertExists()
        shot("block-menu")
        onNodeWithText("Put the Start point here").performClick(); waitForIdle()
        assertEquals(MakerStartPoint(60, 60), editor.makerStartPoint())

        // Paint with the Start point part: clicking its block again turns Samus around (one undo step each).
        onNodeWithText("Stations").performClick(); waitForIdle()
        onNodeWithText("Start point").performClick(); waitForIdle()
        onRoot().performMouseInput { moveTo(p + Offset(40f, 40f)); moveTo(p) }; waitForIdle() // the injector still thinks the pointer is at p
        println("[MENU] hover=${editor.hoverBlockX},${editor.hoverBlockY} tool=${editor.activeTool} sp=${editor.makerStartPoint()}")
        onRoot().performMouseInput { click(p) }
        waitForIdle()
        assertEquals(MakerStartPoint(60, 60, facingLeft = true), editor.makerStartPoint())
        shot("start-point-facing-left")
        editor.undo(); waitForIdle()
        assertEquals(MakerStartPoint(60, 60), editor.makerStartPoint())
    }

    @Test
    fun `clicking an item shows it in the inspector with its variants and remove`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Pick", Files.createTempDirectory("maker-pick").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        editor.addPlm(0xEED7, 60, 60, 0, com.supermetroid.editor.data.ItemStateScope.ALL_STATES) // Energy Tank
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, initialTool = MakerTool.SELECT)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null && controls.fitZoom != null }
        controls.fit(); waitForIdle()
        var px = -1f
        for (x in 395 until 1255 step 3) { onRoot().performMouseInput { moveTo(Offset(x.toFloat(), 400f)) }; if (editor.hoverBlockX == 60) { px = x + 1f; break } }
        var p = Offset.Zero
        for (y in 120 until 880 step 3) { p = Offset(px, y + 1f); onRoot().performMouseInput { moveTo(p) }; if (editor.hoverBlockY == 60) break }
        onRoot().performMouseInput { click(p) }; waitForIdle()
        onNodeWithText("Selected in the room", ignoreCase = true).assertExists()
        shots?.let { ImageIO.write(onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "picked-item.png")) }
        onNodeWithText("Chozo ball").performClick(); waitForIdle()
        assertEquals(true, editor.workingPlms.any { it.id == 0xEF2B && it.x == 60 && it.y == 60 }, "now in a Chozo ball")
        editor.undo(); waitForIdle()
        assertEquals(true, editor.workingPlms.any { it.id == 0xEED7 && it.x == 60 && it.y == 60 }, "one undo")
        editor.redo(); waitForIdle()
        onNodeWithText("Remove").performClick(); waitForIdle()
        assertEquals(true, editor.workingPlms.none { it.x == 60 && it.y == 60 && editor.isEditorItemPlm(it.id) }, "removed")
        onNodeWithText("Selected in the room", ignoreCase = true).assertDoesNotExist()
    }
}
