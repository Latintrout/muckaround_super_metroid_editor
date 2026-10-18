package com.supermetroid.editor.ui.maker

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): search across every category, starred and recent parts. Screenshots go to MAKER_UI_SHOTS. */
@OptIn(ExperimentalTestApi::class)
class MakerPartsUiTest {
    @Test
    fun `search everything, star a part, quick strip`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Parts", Files.createTempDirectory("maker-parts").toFile(), rom)
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
        fun shot(name: String) { shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

        // Terrain is open; searching finds items and stations too.
        onNode(hasSetTextAction()).performTextReplacement("Start")
        waitForIdle()
        onNodeWithText("Start point").assertExists()
        onNode(hasSetTextAction()).performTextReplacement("Missile")
        waitForIdle()
        assertTrue(onAllNodesWithText("Missile", substring = true).fetchSemanticsNodes().size >= 3, "missile items from Items")
        shot("parts-search")

        // Star one; it is saved in the project and shown in the quick strip once the search is cleared.
        onNodeWithContentDescription("Star Missile").performClick(); waitForIdle()
        assertTrue(editor.project.makerFavourites.isNotEmpty())
        onNode(hasSetTextAction()).performTextReplacement("")
        waitForIdle()
        onNodeWithText("Starred and recent").assertExists()
        onNodeWithContentDescription("Quick Missile").performClick(); waitForIdle()
        assertTrue(onAllNodesWithText("Missile").fetchSemanticsNodes().isNotEmpty(), "the selected-part card")
        shot("parts-starred")
        val saved = ProjectFileService.loadProject(open.file.also { editor.saveProject(parser) })
        assertEquals(editor.project.makerFavourites, saved.makerFavourites, "favourites saved with the project")
    }
}
