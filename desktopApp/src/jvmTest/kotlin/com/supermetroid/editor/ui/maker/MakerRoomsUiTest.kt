package com.supermetroid.editor.ui.maker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): New room (sizes, copy), Rename and Delete from the Maker. Screenshots go to MAKER_UI_SHOTS when set. */
@OptIn(ExperimentalTestApi::class)
class MakerRoomsUiTest {
    @Test
    fun `new room sizes and copy, rename and delete`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Rooms", Files.createTempDirectory("maker-rooms").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser by mutableStateOf(editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath)))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        var rooms by mutableStateOf(parser.roomCatalog.rooms)
        var room by mutableStateOf(rooms.first { it.getRoomIdAsInt() == 0x91F8 })
        // The same create/delete actions Main passes to the Maker.
        val createRoom = { request: NewRoomCreationRequest ->
            runCatching {
                val (created, workspace) = editor.createNewRoomWorkspace(request, parser)
                parser = workspace; rooms = workspace.roomCatalog.rooms
                room = editor.projectRoomInfos().first { it.handle == created.handle }
            }.exceptionOrNull()?.message
        }
        val deleteRoom = { roomId: Int ->
            runCatching {
                editor.deleteProjectRoom(roomId, parser)
                parser = editor.prepareWorkspaceParser(); rooms = parser.roomCatalog.rooms
                room = rooms.first { it.getRoomIdAsInt() == 0x91F8 }
            }.exceptionOrNull()?.message
        }
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, room, EmulatorWorkspaceState(), { room = it },
                onWorkspaceChanged = {
                    parser = editor.prepareWorkspaceParser(); rooms = parser.roomCatalog.rooms
                    room = rooms.firstOrNull { it.handle == room.handle } ?: room
                },
                onPlay = {}, onExit = {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, onCreateRoom = createRoom, onDeleteRoom = deleteRoom)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }
        fun shot(name: String) { shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }
        fun waitLoaded(roomId: Int) = waitUntil(20_000) { editor.currentRoomId == roomId && editor.workingLevelData != null }

        // New room, 2×1, empty: walls, a 3-row floor, air inside, the given name.
        onNodeWithText("New room").performClick(); waitForIdle()
        shot("new-room-panel")
        onNodeWithText("New room 1").performTextReplacement("Wide room")
        onNodeWithText("2×1").performClick()
        onNodeWithText("Make room").performClick(); waitForIdle()
        val wide = editor.project.newRooms.single()
        assertEquals("Wide room", wide.name)
        assertEquals(2 to 1, wide.header.width to wide.header.height)
        waitLoaded(wide.previewRoomId)
        waitUntil(10_000) { ((editor.readBlockWord(5, editor.workingBlocksTall - 3) shr 12) and 0xF) == 0x8 }
        assertEquals(32, editor.workingBlocksWide)
        assertTrue((0 until 32).all { x -> ((editor.readBlockWord(x, editor.workingBlocksTall - 3) shr 12) and 0xF) == 0x8 }, "3-row floor")
        assertEquals(0, (editor.readBlockWord(16, 8) shr 12) and 0xF, "air inside")
        shot("new-room-wide")

        // Rename it from the room menu.
        onNodeWithText("Wide room").performClick(); waitForIdle()
        onNodeWithText("Rename this room…").performClick(); waitForIdle()
        onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Wide room")).performTextReplacement("Long hall")
        onNodeWithText("Rename").performClick(); waitForIdle()
        assertEquals("Long hall", editor.project.newRooms.single().name)
        onNodeWithText("Long hall").assertExists()

        // A Start point, then Delete: the room and its Start point go, Landing Site is selected.
        editor.setMakerStartPoint(MakerStartPoint(5, 5))
        onNodeWithText("Long hall").performClick(); waitForIdle()
        onNodeWithText("Delete this room…").performClick(); waitForIdle()
        shot("delete-room-confirm")
        onNodeWithText("Delete room").performClick(); waitForIdle()
        assertTrue(editor.project.newRooms.isEmpty(), "room deleted")
        assertTrue(editor.project.makerStartPoints.isEmpty(), "its Start point is gone too")
        assertEquals(0x91F8, room.getRoomIdAsInt())

        // Copy of Landing Site: same size as the source.
        waitLoaded(0x91F8)
        onNodeWithText("New room").performClick(); waitForIdle()
        onNodeWithText("Copy of Landing Site").performClick()
        onNodeWithText("Make room").performClick(); waitForIdle()
        val copy = editor.project.newRooms.single()
        val landing = parser.readRoomHeader(0x91F8)!!
        assertEquals(landing.width to landing.height, copy.header.width to copy.header.height)
    }
}
