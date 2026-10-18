package com.supermetroid.editor.ui.maker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.LayoutEditScope
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

/** Comfort features: backups and autosave, the room picker, the shortcut sheet, the empty-room hint, the Save chip. */
@OptIn(ExperimentalTestApi::class)
class MakerComfortUiTest {
    @Test
    fun `each save keeps a backup of the previous file, five at most, and autosave writes a separate copy`() {
        val dir = Files.createTempDirectory("maker-backups").toFile()
        val file = File(dir, "p.smedit")
        val project = SmEditProject(romPath = File(dir, "base.smc").absolutePath)
        repeat(8) { i ->
            project.buildName = "v$i"
            assertTrue(ProjectFileService.saveProject(project, file.absolutePath, null, false) {})
            Thread.sleep(5)
        }
        val backups = File(dir, "backups").listFiles().orEmpty()
        assertEquals(ProjectFileService.BACKUPS_KEPT, backups.size, "only the newest backups are kept")
        assertTrue(backups.all { it.name.startsWith("p-") && it.name.endsWith(".smedit") })
        assertEquals("v7", ProjectFileService.loadProject(file).buildName, "the project file holds the latest save")
        val auto = ProjectFileService.writeAutosave(file, ProjectFileService.serializeProject(project.apply { buildName = "unsaved" }))
        assertEquals(File(dir, "autosave/p.autosave.smedit").canonicalPath, auto.canonicalPath)
        assertEquals("v7", ProjectFileService.loadProject(file).buildName, "autosave never touches the project file")
    }

    @Test
    fun `room picker, shortcut sheet, empty-room hint and save chip`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Comfort", Files.createTempDirectory("maker-comfort").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser by mutableStateOf(editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath)))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val plan = requireNotNull(planMakerNewRoom(editor, parser, 0x91F8, 1, 1, MakerNewRoomStart.EMPTY, "My room", seed = 1))
        val (created, ws) = editor.createNewRoomWorkspace(plan.request, parser); parser = ws
        editor.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(editor, requireNotNull(plan.look))
        val rooms = parser.roomCatalog.rooms
        var room by mutableStateOf(rooms.first { it.getRoomIdAsInt() == created.previewRoomId })
        var saves = 0
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, room, EmulatorWorkspaceState(), { room = it }, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls,
                onSave = { saves++ })
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }
        fun shot(name: String) { shots?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

        // Empty-room hint in a room you made with no Start point.
        onNodeWithText("Get this room playing").assertExists()
        shot("empty-room-hint")
        onNodeWithText("Got it").performClick(); waitForIdle()
        onNodeWithText("Get this room playing").assertDoesNotExist()

        // Save chip: unsaved after edits; clicking saves (as Ctrl+S).
        onNodeWithContentDescription("Unsaved changes, save").performClick(); waitForIdle()
        assertEquals(1, saves)

        // ? (the key, or the ? in the hint bar) opens the shortcut sheet.
        onNodeWithContentDescription("Show shortcuts").performClick(); waitForIdle()
        onNodeWithText("Shortcuts").assertExists()
        shot("shortcuts")
        onNodeWithText("Got it").performClick(); waitForIdle()
        onNodeWithText("Shortcuts").assertDoesNotExist()

        // Room picker: your rooms first; search; pick one.
        onNodeWithText("My room").performClick(); waitForIdle()
        onNodeWithText("Rooms").assertExists()
        assertTrue(onAllNodesWithText("Your rooms", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        shot("room-picker")
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 1].performTextReplacement("Parlor") // the picker's search (newest field)
        waitForIdle()
        onNodeWithText("Parlor and Alcatraz").performClick(); waitForIdle()
        assertEquals("Parlor and Alcatraz", room.name)
    }
}
