package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in (V1_ROM): the Start point part, driven offscreen with synthetic mouse events.
 * Nothing reaches the desktop. Screenshots go to MAKER_UI_SHOTS when set.
 */
class MakerStartPointUiTest {
    private fun ImageComposeScene.frames(n: Int = 10) { repeat(n) { render(System.nanoTime()); Thread.sleep(50) } }
    private val awtSource = javax.swing.JPanel()

    /** Mouse press/release with an AWT event attached, as the real window delivers (the canvas paints from it). */
    private fun ImageComposeScene.click(p: Offset, secondary: Boolean = false) {
        val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
        val awtButton = if (secondary) java.awt.event.MouseEvent.BUTTON3 else java.awt.event.MouseEvent.BUTTON1
        val mask = if (secondary) java.awt.event.InputEvent.BUTTON3_DOWN_MASK else java.awt.event.InputEvent.BUTTON1_DOWN_MASK
        fun awt(id: Int, mods: Int) = java.awt.event.MouseEvent(awtSource, id, System.currentTimeMillis(), mods, p.x.toInt(), p.y.toInt(), 1, false, awtButton)
        sendPointerEvent(PointerEventType.Move, p); frames(1)
        sendPointerEvent(PointerEventType.Press, p, buttons = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true),
            button = button, nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_PRESSED, mask)); frames(1)
        sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = button,
            nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_RELEASED, 0)); frames(3)
    }

    private fun ImageComposeScene.drag(from: Offset, to: Offset, steps: Int = 8) {
        sendPointerEvent(PointerEventType.Move, from); frames(1)
        sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary); frames(1)
        for (i in 1..steps) { sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / steps.toFloat()), buttons = PointerButtons(isPrimaryPressed = true)); frames(1) }
        sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary); frames(4)
    }

    @Test
    fun `start point is placed moved and removed on the canvas`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("StartUi", Files.createTempDirectory("maker-sp").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val room = rooms.first { it.getRoomIdAsInt() == 0x91F8 }
        val controls = MapCanvasControls()
        val themes = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null)
        val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
            MakerModeScreen(editor, parser, rooms, room, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = themes, canvasControls = controls)
        }
        scene.frames(40)
        // Under a busy test run the canvas can take a while to lay out and fit the room.
        repeat(300) { if (controls.openTilePropertiesAt == null || controls.fitZoom == null) scene.frames(2) }
        scene.frames(10)
        fun shot(name: String) { shots?.let { File(it, "$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
        fun pointOf(bx: Int, by: Int): Offset {
            repeat(5) {
                for (y in 120 until 880 step 3) for (x in 395 until 1255 step 3) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), y.toFloat()))
                    if (editor.hoverBlockX == bx && editor.hoverBlockY == by) return Offset(x.toFloat(), y.toFloat())
                }
                scene.frames(20)
            }
            error("block $bx,$by not on screen")
        }
        fun key(c: Char) {
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(java.awt.event.KeyEvent(awtSource, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
                java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code), c)))
            scene.frames(3)
        }

        editor.seedBuiltInPatterns(parser)
        assertEquals(MakerPart.StartPoint, makerPartsFor(MakerCategory.STATIONS, editor).first(), "Start point leads the Stations parts")
        scene.click(Offset(146f, 266f)); scene.frames(5) // Stations tab
        scene.click(Offset(168f, 442f)); scene.frames(5) // first card: Start point
        scene.click(pointOf(60, 60))
        assertEquals(MakerStartPoint(60, 60), editor.makerStartPoint())
        scene.click(pointOf(62, 61)) // placing again moves it: still one per room
        assertEquals(MakerStartPoint(62, 61), editor.makerStartPoint())
        assertEquals(1, editor.project.makerStartPoints.size)
        shot("start-point-placed")

        key('v')
        scene.drag(pointOf(62, 61), pointOf(64, 62))
        assertEquals(MakerStartPoint(64, 62), editor.makerStartPoint(), "Select drag moves the start point")
        editor.undo(); scene.frames(3)
        assertEquals(MakerStartPoint(62, 61), editor.makerStartPoint(), "one undo puts it back")
        editor.redo(); scene.frames(3)
        assertEquals(MakerStartPoint(64, 62), editor.makerStartPoint())
        shot("start-point-moved")

        key('b')
        scene.click(pointOf(64, 62), secondary = true)
        assertNull(editor.makerStartPoint(), "right-click in Paint removes it")
        editor.undo(); scene.frames(3)
        assertNotNull(editor.makerStartPoint())
    }
}
