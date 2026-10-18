package com.supermetroid.editor.ui.maker

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): edit a loadout, save it with the project, use it. Screenshots go to MAKER_UI_SHOTS. */
@OptIn(ExperimentalTestApi::class)
class MakerLoadoutUiTest {
    @Test
    fun `edit save and use a loadout`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Loadouts", Files.createTempDirectory("maker-loadouts").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }

        onAllNodesWithText("Iron Warden").onFirst().performClick(); waitForIdle()
        onNodeWithText("Edit loadouts…").performClick(); waitForIdle()
        onNodeWithContentDescription("Screw Attack off").performClick(); waitForIdle()
        onNodeWithContentDescription("More Super Missiles").performClick(); waitForIdle()
        onNode(hasSetTextAction() and hasText("Iron Warden")).performTextReplacement("Screw run")
        shots?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "loadouts.png")) }
        onNodeWithText("Save").performClick(); waitForIdle()
        val saved = editor.project.makerLoadouts.single()
        assertEquals("Screw run", saved.name)
        assertTrue(saved.items and 0x0008 != 0, "Screw Attack on")
        assertEquals(5, saved.supers)
        onNodeWithText("Use this loadout").performClick(); waitForIdle()
        assertTrue(onAllNodesWithText("Screw run").fetchSemanticsNodes().isNotEmpty(), "the command bar shows it")
    }
}
