package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.supermetroid.editor.data.ItemStateScope
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
 * Opt-in (V1_ROM): Brandon's 2026-10-17 Maker feedback, driven offscreen with synthetic mouse events.
 * Nothing reaches the desktop. Screenshots go to MAKER_UI_SHOTS when set.
 */
class MakerFeedbackTest {
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
    fun `maker feedback round 1`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Feedback", Files.createTempDirectory("maker-fb").toFile(), rom)
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
        fun shot(name: String) { shots?.let { File(it, "$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
        fun pointOf(bx: Int, by: Int): Offset {
            for (y in 120 until 880 step 3) for (x in 395 until 1255 step 3) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x.toFloat(), y.toFloat()))
                if (editor.hoverBlockX == bx && editor.hoverBlockY == by) return Offset(x.toFloat(), y.toFloat())
            }
            error("block $bx,$by not on screen")
        }
        fun key(c: Char) {
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(java.awt.event.KeyEvent(awtSource, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
                java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code), c)))
            scene.frames(3)
        }
        val results = linkedMapOf<String, Boolean>()

        // 1. Select click on a block docks its properties in the side panel (not over the room).
        key('v')
        scene.click(pointOf(60, 60))
        scene.frames(5)
        results["select click docks block properties in the side panel"] = controls.dockedPanel != null
        shot("feedback-docked-panel")

        // 2. Items: pick the Items tab and the first card, then click and drag-paint on the room.
        scene.click(Offset(146f, 204f)); scene.frames(5) // Items tab
        scene.click(Offset(168f, 442f)); scene.frames(5) // first item card (Energy Tank)
        val before = editor.workingPlms.size
        scene.click(pointOf(62, 60))
        results["paint click places an item"] = editor.workingPlms.any { it.x == 62 && it.y == 60 && editor.isEditorItemPlm(it.id) }
        scene.drag(pointOf(64, 60), pointOf(67, 60))
        val dragged = (64..67).count { x -> editor.workingPlms.any { it.x == x && it.y == 60 && editor.isEditorItemPlm(it.id) } }
        results["paint drag places an item on each block (got $dragged/4)"] = dragged >= 3
        println("[FB] items placed: ${editor.workingPlms.size - before}")

        // 3. Library drag-and-drop: drag the second item card onto block (70, 62).
        val dropAt = pointOf(70, 62)
        scene.drag(Offset(310f, 442f), dropAt, steps = 12)
        println("[FB] after card drop: near (70,62) = ${editor.workingPlms.filter { kotlin.math.abs(it.x - 70) <= 4 && kotlin.math.abs(it.y - 62) <= 4 }}")
        results["drag a card onto the room places it there"] = editor.workingPlms.any { it.x == 70 && it.y == 62 && editor.isEditorItemPlm(it.id) }
        shot("feedback-items-placed")

        // 4. Select drag moves a placed item.
        key('v')
        val moved = editor.workingPlms.first { it.x == 62 && it.y == 60 }
        scene.drag(pointOf(62, 60), pointOf(62, 63))
        results["select drag moves an item"] = editor.workingPlms.any { it.id == moved.id && it.x == 62 && it.y == 63 } &&
            editor.workingPlms.none { it.x == 62 && it.y == 60 && it.id == moved.id }
        editor.undo(); scene.frames(3)
        results["one undo puts a moved item back"] = editor.workingPlms.any { it.id == moved.id && it.x == 62 && it.y == 60 } &&
            editor.workingPlms.none { it.id == moved.id && it.x == 62 && it.y == 63 }
        editor.redo(); scene.frames(3)
        results["one redo moves it again"] = editor.workingPlms.any { it.id == moved.id && it.x == 62 && it.y == 63 } &&
            editor.workingPlms.none { it.id == moved.id && it.x == 62 && it.y == 60 }

        // Doors holds only doors/gates; stations, refills and statues have their own category; all show real tiles.
        editor.seedBuiltInPatterns(parser)
        val doors = makerPartsFor(MakerCategory.DOORS, editor)
        val stations = makerPartsFor(MakerCategory.STATIONS, editor)
        println("[FB] doors=${doors.map { it.name }} stations=${stations.map { it.name }}")
        results["doors category holds only doors and gates"] = doors.isNotEmpty() && doors.all { isDoorPattern(it.id) }
        results["stations category holds the save station, refills and statue"] =
            setOf("builtin_save_station", "builtin_energy_refill", "builtin_missile_refill", "builtin_chozo_statue").all { id -> stations.any { it.id == id } }
        val noPreview = (doors + stations).filterIsInstance<MakerPart.Pattern>().filter { part ->
            editor.project.patterns.first { it.id == part.patternId }.let { com.supermetroid.editor.ui.renderPatternThumbnail(it, editor.tileGraphics) } == null
        }.map { it.name }
        results["every door/station card has a real tile preview (missing: $noPreview)"] = noPreview.isEmpty()

        // 5. Every item type places (the same call the Maker makes).
        val failed = RomParser.ITEM_DEFS.withIndex().filterNot { (i, def) ->
            editor.addPlm(def.visibleId, 40 + i, 64, 0, ItemStateScope.ALL_STATES)
            editor.workingPlms.any { it.x == 40 + i && it.y == 64 && it.id == def.visibleId }
        }.map { it.value.name }
        results["every item type places (failed: $failed)"] = failed.isEmpty()

        // 6. Terrain/hazard/door cards paint with a click (canvas paint path).
        for ((tab, tabName) in listOf(Offset(146f, 142f) to "Terrain", Offset(240f, 142f) to "Hazards", Offset(334f, 142f) to "Doors")) {
            scene.click(tab); scene.frames(5)
            scene.click(Offset(168f, 442f)); scene.frames(5)
            val bx = 50; val by = if (tabName == "Doors") 48 else 58
            val beforeWord = editor.readBlockWord(bx, by); val beforePlms = editor.workingPlms.size
            scene.click(pointOf(bx, by))
            val changed = editor.readBlockWord(bx, by) != beforeWord || editor.workingPlms.size != beforePlms
            results["$tabName first card paints with a click"] = changed
            println("[FB] $tabName: word ${"%04x".format(beforeWord)} -> ${"%04x".format(editor.readBlockWord(bx, by))}, plms $beforePlms -> ${editor.workingPlms.size} status='${editor.statusMessage}'")
        }
        scene.click(Offset(146f, 266f)); scene.frames(8) // Stations tab
        shot("feedback-stations")
        shot("feedback-final")
        scene.close()
        results.forEach { (k, v) -> println("[FB] ${if (v) "PASS" else "FAIL"} $k") }
        assertTrue(results.values.all { it }, results.filterValues { !it }.keys.toString())
    }
}
