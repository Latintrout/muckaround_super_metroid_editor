package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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
import kotlin.test.Test
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): auto edges learned from the game's rooms, applied to ground painted in a Maker room. */
class MakerAutoEdgesTest {
    @Test
    fun `painted ground gets the game's edge tiles`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Edges", Files.createTempDirectory("maker-edges").toFile(), rom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val ground = requireNotNull(dominantGroundMetatile(e))
        val table = assertNotNull(MakerEdgeTable.forGround(parser, e.currentTilesetId, ground), "Landing Site's ground has an edge table")
        println("[EDGES] tileset ${e.currentTilesetId} ground %03X: ".format(ground) + table.byMask.toSortedMap().map { (m, w) -> "%X->%03X".format(m, w) })
        assertTrue(table.byMask.containsKey(1), "a top-edge tile")

        // A 2x1 empty Maker room; paint a blob of the interior tile, then auto-edge it.
        val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, 2, 1, MakerNewRoomStart.EMPTY, "Edges", seed = 1))
        val (created, ws) = e.createNewRoomWorkspace(plan.request, parser); parser = ws
        e.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
        e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(e, requireNotNull(plan.look))
        val interior = 0x8000 or ground
        val blob = MakerBox(8, 5, 15, 9)
        e.applyBulkEdits("blob", (blob.y0..blob.y1).flatMap { y -> (blob.x0..blob.x1).map { x -> com.supermetroid.editor.data.TileEdit(x, y, e.readBlockWord(x, y), interior) } })
        e.applyBulkEdits("edges", e.autoEdgeEdits(blob, table))
        assertEquals(0x8000 or table.byMask.getValue(1), e.readBlockWord(11, 5), "the blob's top row uses the top-edge tile")
        assertEquals(interior, e.readBlockWord(11, 7), "its middle stays the ground")
        table.byMask[1 or 8]?.let { assertEquals(0x8000 or it, e.readBlockWord(8, 5), "top-left corner") }

        // Look at it.
        val controls = MapCanvasControls()
        val rooms = parser.roomCatalog.rooms
        val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
            MakerModeScreen(e, parser, rooms, rooms.first { it.getRoomIdAsInt() == created.previewRoomId }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls)
        }
        val d = MakerSceneDriver(scene, e, controls, shots)
        d.ready()
        d.shot("auto-edges")
    }

    /** Opt-in: with Auto edges on, a Box of Ground gets its edges in the same undo step. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `box of ground tidies its edges in one undo step`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val open = ProjectLifecycle.create("Edges2", Files.createTempDirectory("maker-edges2").toFile(), rom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        // An empty Maker room next to Landing Site (same tileset and ground).
        val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, 1, 1, MakerNewRoomStart.EMPTY, "Edges", seed = 1))
        val (created, ws) = e.createNewRoomWorkspace(plan.request, parser); parser = ws
        e.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
        e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(e, requireNotNull(plan.look))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(e, parser, rooms, rooms.first { it.getRoomIdAsInt() == created.previewRoomId }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, initialTool = MakerTool.BOX)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null && controls.fitZoom != null }
        controls.fit(); waitForIdle()
        onNodeWithContentDescription("Auto edges on").assertExists()
        onAllNodesWithText("Ground").onFirst().performClick(); waitForIdle()
        val ground = requireNotNull(dominantGroundMetatile(e))
        val table = requireNotNull(MakerEdgeTable.forGround(parser, e.currentTilesetId, ground))
        fun pointOf(bx: Int, by: Int): androidx.compose.ui.geometry.Offset {
            var px = -1f
            for (x in 393 until 1255 step 2) { onRoot().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(x.toFloat(), 300f)) }; if (e.hoverBlockX == bx) { px = x + 1f; break } }
            check(px >= 0) { "column $bx" }
            for (y in 118 until 880 step 2) { val p = androidx.compose.ui.geometry.Offset(px, y + 1f); onRoot().performMouseInput { moveTo(p) }; if (e.hoverBlockY == by) return p }
            error("block $bx,$by")
        }
        // Open air in the empty room.
        val a = pointOf(4, 4); val b = pointOf(10, 8)
        val before = (3..9).map { y -> (3..11).map { x -> e.readBlockWord(x, y) } }
        onRoot().performMouseInput { moveTo(a); press(); moveTo(androidx.compose.ui.geometry.Offset((a.x + b.x) / 2, (a.y + b.y) / 2)); moveTo(b); release() }
        waitForIdle(); waitUntil(5_000) { e.readBlockWord(7, 6) == (0x8000 or ground) }
        waitForIdle()
        table.byMask[8]?.let { assertEquals(0x8000 or it, e.readBlockWord(4, 6), "left side edge") }
        table.byMask[2]?.let { assertEquals(0x8000 or it, e.readBlockWord(10, 6), "right side edge") }
        assertEquals(0x8000 or ground, e.readBlockWord(7, 6), "inside is ground")
        e.undo(); waitForIdle()
        assertEquals(before, (3..9).map { y -> (3..11).map { x -> e.readBlockWord(x, y) } }, "one undo removes the box and its edges")
    }
}
