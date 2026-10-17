package com.supermetroid.editor.ui.maker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Opt-in (V1_ROM): every close/dismiss control the Maker shows actually closes its panel. */
@OptIn(ExperimentalTestApi::class)
class MakerDismissTest {
    @Test
    fun `close buttons close their panels`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val open = ProjectLifecycle.create("Dismiss", Files.createTempDirectory("maker-dismiss").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }

        // Block properties (docked): its X closes it.
        runOnIdle { controls.openTilePropertiesAt!!.invoke(60, 60) }
        waitUntil(5_000) { controls.dockedPanel != null }
        onNodeWithContentDescription("Close tile properties").performClick()
        waitForIdle()
        assertNull(controls.dockedPanel, "block properties X")
        println("[DISMISS] PASS block properties X closes the docked panel")

        // Appearance panel: Done closes it.
        onNodeWithText("MODRET · Light", substring = true).performClick()
        onNodeWithText("Appearance settings…", substring = true).performClick()
        onNodeWithText("Appearance").assertExists()
        onNodeWithText("Done").performClick()
        waitForIdle()
        onNodeWithText("Appearance").assertDoesNotExist()
        println("[DISMISS] PASS appearance Done closes the panel")

        // Ready chip: opening shows details, choosing again closes.
        val detail = "From the save station in this room"
        fun detailShown() = onAllNodes(androidx.compose.ui.test.hasText(detail) and !androidx.compose.ui.test.hasContentDescription(detail, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
        onNodeWithContentDescription("Samus can start here", substring = true).performClick()
        waitUntil(5_000) { detailShown() }
        println("[DISMISS] PASS ready chip opens its details")
        // Clicking outside dismisses the details popup.
        onNodeWithText("Undo").performClick()
        waitUntil(5_000) { !detailShown() }
        println("[DISMISS] PASS ready chip details close when clicking elsewhere")
        assertNotNull(controls.openTilePropertiesAt)
    }
}
