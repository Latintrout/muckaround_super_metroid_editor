package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.MapCanvasControls
import org.jetbrains.skia.EncodedImageFormat
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File

/**
 * Drives a Maker [ImageComposeScene] offscreen the way the real window does: pointer events carry AWT events
 * (the canvas reads them) and keyboard modifiers. Nothing reaches the desktop.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MakerSceneDriver(val scene: ImageComposeScene, val editor: EditorState, val controls: MapCanvasControls, private val shots: File?) {
    private val awtSource = javax.swing.JPanel()

    fun frames(n: Int = 10) { repeat(n) { scene.render(System.nanoTime()); Thread.sleep(50) } }

    fun shot(name: String) {
        shots?.let { File(it, "$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
    }

    private fun mods(shift: Boolean) = PointerKeyboardModifiers(isShiftPressed = shift)
    private fun awt(id: Int, p: Offset, mods: Int, button: Int) =
        MouseEvent(awtSource, id, System.currentTimeMillis(), mods, p.x.toInt(), p.y.toInt(), 1, false, button)

    fun move(p: Offset) { scene.sendPointerEvent(PointerEventType.Move, p, nativeEvent = awt(MouseEvent.MOUSE_MOVED, p, 0, MouseEvent.NOBUTTON)); frames(1) }

    fun click(p: Offset, secondary: Boolean = false, shift: Boolean = false) {
        val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
        val awtButton = if (secondary) MouseEvent.BUTTON3 else MouseEvent.BUTTON1
        val mask = (if (secondary) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK) or (if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
        move(p)
        scene.sendPointerEvent(PointerEventType.Press, p, buttons = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true),
            keyboardModifiers = mods(shift), button = button, nativeEvent = awt(MouseEvent.MOUSE_PRESSED, p, mask, awtButton)); frames(1)
        scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), keyboardModifiers = mods(shift), button = button,
            nativeEvent = awt(MouseEvent.MOUSE_RELEASED, p, if (shift) InputEvent.SHIFT_DOWN_MASK else 0, awtButton)); frames(3)
    }

    fun drag(from: Offset, to: Offset, steps: Int = 8, shift: Boolean = false, middle: Boolean = false) {
        val button = if (middle) PointerButton.Tertiary else PointerButton.Primary
        val awtButton = if (middle) MouseEvent.BUTTON2 else MouseEvent.BUTTON1
        val down = (if (middle) InputEvent.BUTTON2_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK) or (if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
        val buttons = if (middle) PointerButtons(isTertiaryPressed = true) else PointerButtons(isPrimaryPressed = true)
        move(from)
        scene.sendPointerEvent(PointerEventType.Press, from, buttons = buttons, keyboardModifiers = mods(shift), button = button,
            nativeEvent = awt(MouseEvent.MOUSE_PRESSED, from, down, awtButton)); frames(1)
        for (i in 1..steps) {
            val p = from + (to - from) * (i / steps.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, p, buttons = buttons, keyboardModifiers = mods(shift),
                nativeEvent = awt(MouseEvent.MOUSE_DRAGGED, p, down, awtButton)); frames(1)
        }
        scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), keyboardModifiers = mods(shift), button = button,
            nativeEvent = awt(MouseEvent.MOUSE_RELEASED, to, if (shift) InputEvent.SHIFT_DOWN_MASK else 0, awtButton)); frames(4)
    }

    fun scroll(p: Offset, dy: Float, shift: Boolean = false) {
        move(p)
        val wheel = java.awt.event.MouseWheelEvent(awtSource, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), if (shift) InputEvent.SHIFT_DOWN_MASK else 0,
            p.x.toInt(), p.y.toInt(), 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, dy.toInt())
        scene.sendPointerEvent(PointerEventType.Scroll, p, scrollDelta = Offset(0f, dy), keyboardModifiers = mods(shift), nativeEvent = wheel); frames(4)
    }

    /** A key press (and release); [char] for typed keys, [ctrl] for shortcuts. */
    fun key(code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, ctrl: Boolean = false, release: Boolean = true) {
        val m = if (ctrl) InputEvent.CTRL_DOWN_MASK else 0
        scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(KeyEvent(awtSource, KeyEvent.KEY_PRESSED, 0, m, code, char))); frames(2)
        if (release) { scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(KeyEvent(awtSource, KeyEvent.KEY_RELEASED, 0, m, code, char))); frames(2) }
    }

    fun keyUp(code: Int) { scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(KeyEvent(awtSource, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED))); frames(2) }

    fun tool(t: MakerTool) = key(KeyEvent.getExtendedKeyCodeForChar(t.key.lowercaseChar().code), t.key.lowercaseChar())

    /** Waits for the canvas and fits the room, so block lookups don't depend on when the first fit happens. */
    fun ready() {
        frames(40)
        repeat(300) { if (controls.openTilePropertiesAt == null || controls.fitZoom == null) frames(2) }
        controls.fit(); frames(10)
    }

    fun pointOf(bx: Int, by: Int): Offset {
        repeat(5) {
            for (y in 120 until 880 step 3) for (x in 395 until 1255 step 3) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), y.toFloat()))
                if (editor.hoverBlockX == bx && editor.hoverBlockY == by) return Offset(x.toFloat(), y.toFloat())
            }
            frames(20)
        }
        error("block $bx,$by not on screen (zoom ${controls.zoom.value}, fit ${controls.fitZoom})")
    }
}
