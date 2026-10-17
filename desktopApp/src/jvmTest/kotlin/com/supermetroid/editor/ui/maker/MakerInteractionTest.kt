package com.supermetroid.editor.ui.maker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Opt-in (V1_ROM): drives the real Maker screen in an offscreen Compose scene with synthetic
 * mouse events (nothing reaches the desktop) and checks Box fill/clear, one-step undo and Move panning.
 */
class MakerInteractionTest {
    private fun ImageComposeScene.frames(n: Int = 10) { repeat(n) { render(System.nanoTime()); Thread.sleep(60) } }

    private fun ImageComposeScene.drag(from: Offset, to: Offset, button: PointerButton, buttons: PointerButtons) {
        sendPointerEvent(PointerEventType.Move, from); frames(2)
        sendPointerEvent(PointerEventType.Press, from, buttons = buttons, button = button); frames(2)
        for (i in 1..6) { val t = i / 6f; sendPointerEvent(PointerEventType.Move, from + (to - from) * t, buttons = buttons); frames(1) }
        sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = button); frames(3)
    }

    @Test
    fun `box fill, box clear, undo and move pan`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val work = Files.createTempDirectory("maker-ui-it").toFile()
        val open = ProjectLifecycle.create("UiIt", work, rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val room = rooms.first { it.getRoomIdAsInt() == 0x91F8 }
        val themes = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null)
        var tool by mutableStateOf(MakerTool.BOX)
        val controls = MapCanvasControls()
        val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
            key(tool) {
                MakerModeScreen(
                    editorState = editor, romParser = parser, rooms = rooms, selectedRoom = room,
                    emulatorWorkspaceState = EmulatorWorkspaceState(), onRoomSelected = {}, onWorkspaceChanged = {},
                    onPlay = {}, onExit = {}, themeState = themes, initialTool = tool, canvasControls = controls,
                )
            }
        }
        scene.frames(40)
        val ground = requireNotNull(dominantGroundMetatile(editor))

        // Find the screen point of a block by hovering until the canvas reports it.
        fun pointOf(bx: Int, by: Int): Offset {
            for (y in 70 until 880 step 4) for (x in 395 until 1295 step 4) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), y.toFloat()))
                if (editor.hoverBlockX == bx && editor.hoverBlockY == by) return Offset(x.toFloat(), y.toFloat())
            }
            error("block $bx,$by not on screen")
        }

        val a = pointOf(60, 60)
        val b = pointOf(63, 61)
        val before = (60..63).flatMap { x -> (60..61).map { y -> editor.readBlockWord(x, y) } }
        scene.drag(a, b, PointerButton.Primary, PointerButtons(isPrimaryPressed = true))
        val filled = (60..63).all { x -> (60..61).all { y -> editor.readBlockWord(x, y) and 0x3FF == ground } }
        println("[UI] box fill 4x2 with ground 0x${ground.toString(16)}: $filled; selection cleared=${editor.mapSelStart == null}")
        assertTrue(filled)
        assertEquals(null, editor.mapSelStart)

        editor.undo(); scene.frames(3)
        val undone = (60..63).flatMap { x -> (60..61).map { y -> editor.readBlockWord(x, y) } } == before
        println("[UI] one undo restores all 8 blocks: $undone")
        assertTrue(undone)

        editor.redo(); scene.frames(3)
        scene.drag(pointOf(61, 60), pointOf(62, 61), PointerButton.Secondary, PointerButtons(isSecondaryPressed = true))
        val cleared = (61..62).all { x -> (60..61).all { y -> (editor.readBlockWord(x, y) shr 12) and 0xF == 0 } }
        val kept = editor.readBlockWord(60, 60) and 0x3FF == ground && editor.readBlockWord(63, 61) and 0x3FF == ground
        println("[UI] right-drag box clears the inner 2x2 to air: $cleared, outer blocks kept: $kept")
        assertTrue(cleared && kept)

        // Paint: a click places the part, a right-click removes it again.
        tool = MakerTool.PAINT; scene.frames(20)
        val p = pointOf(50, 60)
        // The canvas paints from the AWT event (as in the real window), so attach one.
        val src = javax.swing.JPanel()
        fun awt(id: Int, mods: Int) = java.awt.event.MouseEvent(src, id, System.currentTimeMillis(), mods, p.x.toInt(), p.y.toInt(), 1, false, java.awt.event.MouseEvent.BUTTON1)
        scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.InputEvent.BUTTON1_DOWN_MASK)); scene.frames(2)
        scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = PointerButton.Primary,
            nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_RELEASED, 0)); scene.frames(3)
        val painted = editor.readBlockWord(50, 60) and 0x3FF == ground
        scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary); scene.frames(2)
        scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = PointerButton.Secondary); scene.frames(3)
        val removed = (editor.readBlockWord(50, 60) shr 12) and 0xF == 0
        println("[UI] paint click places ground: $painted; right-click removes it: $removed")
        assertTrue(painted && removed)

        // Move: zoom in so the room scrolls, then drag with the Move tool and check the view moved.
        tool = MakerTool.MOVE; scene.frames(20)
        controls.zoom.value = 1.5f; scene.frames(10)
        val h0 = controls.hScroll?.value ?: -1
        val v0 = controls.vScroll?.value ?: -1
        scene.drag(Offset(900f, 500f), Offset(700f, 400f), PointerButton.Primary, PointerButtons(isPrimaryPressed = true))
        val h1 = controls.hScroll?.value ?: -1
        val v1 = controls.vScroll?.value ?: -1
        println("[UI] move drag pans the view: h $h0 -> $h1, v $v0 -> $v1")
        assertNotEquals(h0, h1)
        assertNotEquals(v0, v1)
        scene.close()
    }
}
