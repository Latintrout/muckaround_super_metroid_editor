package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerIcon
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.PixelEditorCursors
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
 * Opt-in (V1_ROM): Brandon's 2026-10-17 round-2 feedback (New room, Move vs Select, tool cursors),
 * driven offscreen with synthetic mouse events. Nothing reaches the desktop. Screenshots go to MAKER_UI_SHOTS when set.
 */
class MakerToolsUiTest {
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
        // With AWT events attached, as the real window delivers (the canvas's own selection box reads them).
        fun awt(id: Int, p: Offset, mods: Int) = java.awt.event.MouseEvent(awtSource, id, System.currentTimeMillis(), mods, p.x.toInt(), p.y.toInt(), 1, false,
            java.awt.event.MouseEvent.BUTTON1)
        val down = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
        sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_PRESSED, from, down)); frames(1)
        for (i in 1..steps) {
            val p = from + (to - from) * (i / steps.toFloat())
            sendPointerEvent(PointerEventType.Move, p, buttons = PointerButtons(isPrimaryPressed = true), nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_DRAGGED, p, down)); frames(1)
        }
        sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary,
            nativeEvent = awt(java.awt.event.MouseEvent.MOUSE_RELEASED, to, 0)); frames(4)
    }

    @Test
    fun `move pans, select boxes and drags, tools show their own cursors`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Tools", Files.createTempDirectory("maker-tools").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser by mutableStateOf(editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath)))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        var rooms by mutableStateOf(parser.roomCatalog.rooms)
        var room by mutableStateOf(rooms.first { it.getRoomIdAsInt() == 0x91F8 })
        // The same create action Main passes to the Maker.
        val createRoom = { request: NewRoomCreationRequest ->
            runCatching {
                val (created, workspace) = editor.createNewRoomWorkspace(request, parser)
                parser = workspace
                rooms = workspace.roomCatalog.rooms
                room = editor.projectRoomInfos().first { it.handle == created.handle }
            }.exceptionOrNull()?.message
        }
        val controls = MapCanvasControls()
        val themes = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null)
        val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
            MakerModeScreen(editor, parser, rooms, room, EmulatorWorkspaceState(), { room = it }, {}, {}, {},
                themeState = themes, canvasControls = controls, onCreateRoom = createRoom)
        }
        scene.frames(40)
        repeat(300) { if (controls.openTilePropertiesAt == null || controls.fitZoom == null) scene.frames(2) }
        controls.fit() // don't depend on when the canvas applies its own first fit under a busy test run
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
            error("block $bx,$by not on screen (zoom ${controls.zoom.value}, fit ${controls.fitZoom}, scroll ${controls.hScroll?.value},${controls.vScroll?.value})")
        }
        fun key(c: Char) {
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(java.awt.event.KeyEvent(awtSource, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
                java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code), c)))
            scene.frames(3)
        }

        // Cursors: every tool but Select (system arrow) shows its own toolbar icon.
        for (t in MakerTool.entries) {
            key(t.key.lowercaseChar())
            val expected = makerCursor(t, null)
            assertEquals(expected, controls.cursor, "${t.label} cursor")
            if (t == MakerTool.SELECT) assertEquals(PointerIcon.Default, expected)
            else {
                val img = PixelEditorCursors.renderLineIcon(t.icon)
                val drawn = (0 until img.width * img.height).count { (img.getRGB(it % img.width, it / img.width) ushr 24) > 0 }
                assertTrue(drawn > 20, "${t.label} cursor image is empty")
            }
        }

        // Move: dragging pans the view and never draws a selection box.
        controls.zoomBy(4f); scene.frames(20)
        key('h')
        val h0 = controls.hScroll?.value ?: 0; val v0 = controls.vScroll?.value ?: 0
        scene.drag(Offset(800f, 500f), Offset(600f, 400f))
        assertNull(editor.mapSelStart, "Move must not start a selection box")
        assertTrue((controls.hScroll?.value ?: 0) != h0 || (controls.vScroll?.value ?: 0) != v0, "Move should pan the view")
        controls.fit(); scene.frames(20)

        // Select: drag on empty space draws a box; dragging inside the box moves those blocks in one gesture.
        key('v')
        val before = (40..42).map { x -> (58..59).map { y -> editor.readBlockWord(x, y) } }
        scene.drag(pointOf(40, 58), pointOf(42, 59))
        assertEquals((40 to 58) to (42 to 59), editor.mapSelStart to editor.mapSelEnd, "Select drag draws the box")
        scene.drag(pointOf(41, 58), pointOf(41, 52))
        val after = (40..42).map { x -> (52..53).map { y -> editor.readBlockWord(x, y) } }
        assertEquals(before, after, "the boxed blocks moved up 6 rows")
        assertEquals((40 to 52) to (42 to 53), editor.mapSelStart to editor.mapSelEnd, "the box follows the blocks")
        shot("select-moved-blocks")
        editor.undo(); scene.frames(3)
        assertEquals(before, (40..42).map { x -> (58..59).map { y -> editor.readBlockWord(x, y) } }, "one undo puts the blocks back")

    }
}
