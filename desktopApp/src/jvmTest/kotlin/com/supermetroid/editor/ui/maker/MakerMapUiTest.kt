package com.supermetroid.editor.ui.maker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.LayoutEditScope
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

/** Opt-in (V1_ROM): the Maker map: your rooms and door links, drag a room, click to open. */
@OptIn(ExperimentalTestApi::class)
class MakerMapUiTest {
    @Test
    fun `map shows rooms, moves yours and opens a room`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("MapUi", Files.createTempDirectory("maker-map").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser by mutableStateOf(editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath)))
        fun make(name: String): Int {
            editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
            val plan = requireNotNull(planMakerNewRoom(editor, parser, 0x91F8, 1, 1, MakerNewRoomStart.EMPTY, name, seed = 1))
            val (created, ws) = editor.createNewRoomWorkspace(plan.request, parser); parser = ws
            editor.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
            editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
            applyMakerNewRoomLook(editor, requireNotNull(plan.look))
            return created.previewRoomId
        }
        val far = make("Far room"); val hall = make("Hall")
        editor.seedBuiltInPatterns(parser)
        editor.selectAndApplyPattern("builtin_door_blue_right")
        editor.beginStroke(); editor.paintAt(editor.workingBlocksWide - 1, 9); editor.endStroke("door")
        parser = editor.connectMakerDoor(parser, editor.workingBlocksWide - 1, 9, far, "builtin_door_blue_right").getOrThrow().parser
        var rooms by mutableStateOf(parser.roomCatalog.rooms)
        var room by mutableStateOf(rooms.first { it.getRoomIdAsInt() == hall })
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, room, EmulatorWorkspaceState(), { room = it },
                onWorkspaceChanged = { parser = editor.prepareWorkspaceParser(); rooms = parser.roomCatalog.rooms; room = rooms.first { it.handle == room.handle } },
                onPlay = {}, onExit = {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }
        fun shot(name: String) { shots?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

        onNodeWithText("Map").performClick(); waitForIdle()
        val mapNode = onNodeWithContentDescription("Area map", useUnmergedTree = true) // the panel's clickable card merges it otherwise
        val bounds = mapNode.fetchSemanticsNode().boundsInRoot
        val c = minOf(bounds.width / MinimapData.MAP_WIDTH, bounds.height / MinimapData.ROOM_MAP_HEIGHT)
        fun cellCentre(x: Int, y: Int) = Offset(bounds.left + (x + 0.5f) * c, bounds.top + (y + 0.5f) * c) // in the window
        shot("map")
        val farMap = editor.makerMapRooms(parser, 0, emptyMap()).first { it.roomId == far }
        assertTrue(editor.makerMapRooms(parser, 0, emptyMap()).first { it.roomId == hall }.linksTo.contains(far), "the door is a link on the map")

        // Drag "Far room" to an empty spot (searching for one a few cells away), then check it moved.
        val all = editor.makerMapRooms(parser, 0, emptyMap())
        val target = (2..20).asSequence().flatMap { d -> listOf(farMap.x + d to farMap.y, farMap.x to farMap.y + d, farMap.x - d to farMap.y) }
            .firstOrNull { (x, y) -> x in 0..62 && y in 0..29 && all.none { o -> x in o.x - 1..o.x + o.w && y in o.y - 1..o.y + o.h } }
            ?: error("no free spot")
        onAllNodes(isRoot()).onFirst().performMouseInput {
            moveTo(cellCentre(farMap.x, farMap.y)); press()
            moveTo(cellCentre((farMap.x + target.first) / 2, (farMap.y + target.second) / 2)); moveTo(cellCentre(target.first, target.second)); release()
        }
        waitForIdle()
        shot("map-moved")
        val moved = editor.makerMapRooms(parser, 0, emptyMap()).first { it.roomId == far }
        // A spot with no map tiles may still hold map data; either it moved, or it says why not.
        if (moved.x to moved.y != target) onNodeWithText("in the way", substring = true).assertExists()
        else assertEquals(target, moved.x to moved.y)

        // Click Hall on the map: it opens (it's the current room; click Far room instead).
        val farNow = editor.makerMapRooms(parser, 0, emptyMap()).first { it.roomId == far }
        onAllNodes(isRoot()).onFirst().performMouseInput { click(cellCentre(farNow.x, farNow.y)) }
        waitForIdle()
        assertEquals(far, room.getRoomIdAsInt(), "clicking a room opens it")
    }
}
