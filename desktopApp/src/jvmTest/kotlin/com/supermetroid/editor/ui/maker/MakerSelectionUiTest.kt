package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Opt-in (V1_ROM): the Select keys (Shift-click, copy/paste/duplicate, Delete, arrows), Space-pan with the hand
 * cursor, wheel zoom, and Shift-drag lines in Paint, driven offscreen. Screenshots go to MAKER_UI_SHOTS when set.
 */
class MakerSelectionUiTest {
    @Test
    fun `select keys, space pan, wheel zoom and paint lines`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("SelUi", Files.createTempDirectory("maker-selui").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls)
        }
        val d = MakerSceneDriver(scene, editor, controls, shots)
        d.ready()
        fun words(b: MakerBox) = (b.y0..b.y1).map { y -> (b.x0..b.x1).map { x -> editor.readBlockWord(x, y) } }

        // Select: drag a box, Shift-click grows it.
        d.tool(MakerTool.SELECT)
        d.drag(d.pointOf(40, 56), d.pointOf(41, 57))
        d.click(d.pointOf(42, 58), shift = true)
        assertEquals(MakerBox(40, 56, 42, 58), editor.makerSelection(), "Shift-click grows the box")
        val original = words(MakerBox(40, 56, 42, 58))

        // Arrow keys nudge the box (blocks move with it); the panel follows.
        d.key(KeyEvent.VK_UP)
        assertEquals(MakerBox(40, 55, 42, 57), editor.makerSelection())
        assertEquals(original, words(MakerBox(40, 55, 42, 57)), "nudged one block up")
        editor.undo(); d.frames(3)
        assertEquals(original, words(MakerBox(40, 56, 42, 58)))
        editor.selectBox(MakerBox(40, 56, 42, 58)); d.frames(3)

        // Ctrl+D duplicates beside it; Ctrl+C / Ctrl+V pastes at the pointer.
        d.key(KeyEvent.VK_D, ctrl = true)
        assertEquals(MakerBox(43, 56, 45, 58), editor.makerSelection(), "duplicate goes just right")
        assertEquals(original, words(MakerBox(43, 56, 45, 58)))
        d.key(KeyEvent.VK_C, ctrl = true)
        d.move(d.pointOf(20, 50))
        d.key(KeyEvent.VK_V, ctrl = true)
        assertEquals(MakerBox(20, 50, 22, 52), editor.makerSelection(), "pasted at the pointer")
        assertEquals(original, words(MakerBox(20, 50, 22, 52)))
        d.shot("selection-pasted")

        // Delete clears the box.
        d.key(KeyEvent.VK_DELETE)
        assertTrue(words(MakerBox(20, 50, 22, 52)).flatten().all { it == RomConstants.AIR_TILE_WORD }, "Delete clears to air")
        editor.undo(); d.frames(3)
        assertEquals(original, words(MakerBox(20, 50, 22, 52)), "one undo restores")

        // Space held: hand cursor; dragging pans (closed hand) with any tool.
        d.tool(MakerTool.PAINT)
        controls.zoomBy(4f); d.frames(20)
        val before = (controls.hScroll?.value ?: 0) to (controls.vScroll?.value ?: 0)
        d.key(KeyEvent.VK_SPACE, ' ', release = false)
        assertEquals(makerPanCursor(grabbing = false), controls.cursor, "open hand while Space is held")
        val changedBefore = editor.undoStack.size
        d.drag(Offset(800f, 500f), Offset(650f, 420f))
        assertNotEquals(before, (controls.hScroll?.value ?: 0) to (controls.vScroll?.value ?: 0), "Space-drag pans")
        assertEquals(changedBefore, editor.undoStack.size, "Space-drag paints nothing")
        d.keyUp(KeyEvent.VK_SPACE)
        assertEquals(makerCursor(MakerTool.PAINT, null), controls.cursor, "back to the Paint cursor")

        // Wheel zooms toward the pointer.
        controls.fit(); d.frames(10)
        val z0 = controls.zoom.value
        d.scroll(Offset(800f, 500f), -3f)
        assertTrue(controls.zoom.value > z0, "wheel up zooms in (${controls.zoom.value} vs $z0)")
        controls.fit(); d.frames(10)

        // Paint with Shift: a straight line, one undo step.
        val ground = requireNotNull(dominantGroundMetatile(editor))
        d.tool(MakerTool.PAINT)
        d.click(Offset(168f, 442f)); d.frames(5) // first Terrain card (Ground)
        val below = words(MakerBox(30, 31, 36, 32))
        val row = words(MakerBox(30, 30, 36, 30))
        d.drag(d.pointOf(30, 30), d.pointOf(36, 32), shift = true)
        assertTrue((30..36).all { x -> editor.readBlockWord(x, 30) and 0x3FF == ground }, "a horizontal line along row 30")
        assertEquals(below, words(MakerBox(30, 31, 36, 32)), "nothing painted below the line")
        d.shot("paint-line")
        editor.undo(); d.frames(3)
        assertEquals(row, words(MakerBox(30, 30, 36, 30)), "one undo removes the whole line")
    }
}
