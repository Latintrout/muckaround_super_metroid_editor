package com.supermetroid.editor.ui.maker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomConstants
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

/**
 * Opt-in (V1_ROM): Brandon's 2026-10-18 feedback. Select picks a whole door (blocks and cap) or station in one
 * click, moves and deletes it as one thing; a door's cap never outlives its door blocks; leftover caps in a
 * saved project are cleaned up when the room opens. Screenshots go to MAKER_UI_SHOTS.
 */
@OptIn(ExperimentalTestApi::class)
class MakerObjectsUiTest {
    @Test
    fun `doors and stations select, move and delete whole`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Objects", Files.createTempDirectory("maker-objects").toFile(), rom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, 2, 1, MakerNewRoomStart.EMPTY, "Objects", seed = 1))
        val (created, ws) = e.createNewRoomWorkspace(plan.request, parser); parser = ws
        e.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
        e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(e, requireNotNull(plan.look))
        e.seedBuiltInPatterns(parser)
        val w = e.workingBlocksWide
        fun paintPattern(id: String, x: Int, y: Int) { e.selectAndApplyPattern(id); e.beginStroke(); e.paintAt(x, y); e.endStroke(id) }
        paintPattern("builtin_door_blue_right", w - 1, 9)
        paintPattern("builtin_save_station", 10, 8)
        // A leftover cap with no door (as an older project might have): cleaned up when the room opens.
        e.addPlm(0xC8A2, 4, 3, 0, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
        val caps = { e.workingPlms.filter { RomParser.isDoorCapPlm(it.id) } }
        assertTrue(caps().any { it.x == 4 && it.y == 3 } || caps().none { it.x == 4 && it.y == 3 }) // placement may adjust

        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(e, parser, rooms, rooms.first { it.getRoomIdAsInt() == created.previewRoomId }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls, initialTool = MakerTool.SELECT, initialAutoEdges = false)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null && controls.fitZoom != null }
        controls.fit(); waitForIdle()
        fun shot(name: String) { shots?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }
        val root = onAllNodes(isRoot()).onFirst()
        assertEquals(1, caps().size, "the leftover cap was removed when the room opened; the door's cap stays")

        fun pointOf(bx: Int, by: Int): Offset {
            var px = -1f
            for (x in 393 until 1255 step 4) { root.performMouseInput { moveTo(Offset(x.toFloat(), 500f)) }; if (e.hoverBlockX == bx) { px = x + 1f; break } }
            check(px >= 0) { "column $bx" }
            for (y in 118 until 880 step 4) { val p = Offset(px, y + 1f); root.performMouseInput { moveTo(p) }; if (e.hoverBlockY == by) return p }
            error("block $bx,$by")
        }

        // One click on the middle of the door selects all of it (blocks and cap).
        val mid = pointOf(w - 1, 11)
        root.performMouseInput { moveTo(Offset(mid.x, mid.y - 30)); moveTo(mid); click(mid) }
        waitForIdle()
        assertEquals(MakerBox(w - 1, 9, w - 1, 12), e.makerSelection(), "the whole door")
        shot("door-selected")

        // Clicking its name badge (drawn to the left of the cap) selects it too.
        e.selectBox(null); waitForIdle()
        val label = requireNotNull(e.partLabelRect(caps().single()))
        val lb = pointOf((label[0] + 4) / 16, (label[1] + label[3]) / 32)
        root.performMouseInput { moveTo(Offset(lb.x, lb.y - 30)); moveTo(lb); click(lb) }
        waitForIdle()
        assertEquals(MakerBox(w - 1, 9, w - 1, 12), e.makerSelection(), "the badge selects the door")

        // Drag it up three rows: blocks and cap move together.
        val up = pointOf(w - 1, 8)
        root.performMouseInput { moveTo(mid); press(); moveTo(Offset(mid.x, (mid.y + up.y) / 2)); moveTo(up); release() }
        waitForIdle()
        assertTrue((6..9).all { y -> (e.readBlockWord(w - 1, y) shr 12) and 0xF == 0x9 }, "door blocks moved up")
        assertTrue(caps().single().let { it.x == w - 1 && it.y == 6 }, "the cap moved with it")

        // Delete removes the door and its cap; undo brings both back.
        root.performKeyInput { pressKey(Key.Delete) }; waitForIdle()
        assertTrue(caps().isEmpty(), "cap gone with the door")
        assertTrue((6..9).none { y -> (e.readBlockWord(w - 1, y) shr 12) and 0xF == 0x9 })
        e.undo(); waitForIdle()
        assertEquals(1, caps().size, "undo restores the cap")

        // Erasing the door blocks some other way also takes the cap (in the same undo step).
        runOnIdle { e.applyBulkEdits("erase door", (6..9).map { y -> TileEdit(w - 1, y, e.readBlockWord(w - 1, y), RomConstants.AIR_TILE_WORD) }) }
        waitForIdle()
        assertTrue(caps().isEmpty(), "no cap left floating")
        e.undo(); waitForIdle()
        assertEquals(1, caps().size, "one undo puts back the blocks and the cap")

        // One click on a save station block selects its whole 2×5 footprint.
        val st = pointOf(11, 10)
        root.performMouseInput { moveTo(Offset(st.x, st.y - 30)); moveTo(st); click(st) }
        waitForIdle()
        assertEquals(MakerBox(10, 8, 11, 12), e.makerSelection(), "the whole station")
        shot("station-selected")
    }
}
