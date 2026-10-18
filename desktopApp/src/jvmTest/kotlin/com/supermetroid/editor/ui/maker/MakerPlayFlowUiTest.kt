package com.supermetroid.editor.ui.maker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.emulator.GameSnapshot
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
 * Opt-in (V1_ROM): play-test flow around a running game (stood in for by a snapshot): the live Samus marker,
 * R restarting, Esc stopping into "Edit here" and putting the Start point where Samus stopped; and the
 * pre-Play checks. Screenshots go to MAKER_UI_SHOTS.
 */
@OptIn(ExperimentalTestApi::class)
class MakerPlayFlowUiTest {
    @Test
    fun `edit here, restart and live marker`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("PlayFlow", Files.createTempDirectory("maker-flow").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        val emu = EmulatorWorkspaceState()
        var playing by mutableStateOf(true)
        var plays = 0
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, emu, {}, {}, onPlay = { plays++ }, onExit = {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null), canvasControls = controls,
                playing = playing, onStopPlay = { playing = false })
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }
        fun shot(name: String) { shots?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

        // Samus in Landing Site at pixel (1000, 1100): the live marker shows her.
        emu.snapshot = GameSnapshot(roomId = 0x91F8, gameState = 8, samusX = 1000, samusY = 1100)
        waitForIdle()
        shot("live-marker")

        // R while playing restarts: with no saved restart state it falls back to a full Play (which then waits for
        // an emulator this test doesn't have, so Esc follows straight away and that wait ends).
        mainClock.autoAdvance = false
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.R) }
        mainClock.advanceTimeByFrame()
        assertEquals(1, plays, "R restarts the room")

        // Esc: stop and edit here.
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(false, playing)
        val bx = 1000 / 16; val by = (1100 + 0x15 - 1) / 16
        onNodeWithText("Samus stopped at block $bx, $by.").assertExists()
        shot("edit-here")
        onNodeWithText("Put the Start point here").performClick(); waitForIdle()
        assertEquals(MakerStartPoint(bx, by), editor.makerStartPoint())
    }

    @Test
    fun `pre-play checks catch a door going nowhere and items out of reach`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val open = ProjectLifecycle.create("Checks", Files.createTempDirectory("maker-checks").toFile(), rom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, 1, 1, MakerNewRoomStart.EMPTY, "Checks", seed = 1))
        val (created, ws) = e.createNewRoomWorkspace(plan.request, parser); parser = ws
        e.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
        e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(e, requireNotNull(plan.look))
        val start = 4 to 12
        assertEquals("Exits", makerRoomChecks(e, start).first().short, "no doors: says there's no way out")

        // A door with no connection, and an item sealed in a pocket of solid blocks.
        e.seedBuiltInPatterns(parser)
        e.selectAndApplyPattern("builtin_door_blue_right")
        e.beginStroke(); e.paintAt(e.workingBlocksWide - 1, 9); e.endStroke("door")
        val ground = requireNotNull(plan.look).ground
        e.applyBulkEdits("pocket", (3..7).flatMap { y -> (8..12).map { x -> x to y } }.filter { (x, y) -> x == 8 || x == 12 || y == 3 || y == 7 }
            .map { (x, y) -> com.supermetroid.editor.data.TileEdit(x, y, e.readBlockWord(x, y), ground) })
        e.addPlm(0xEED7, 10, 5, 0, com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
        val checks = makerRoomChecks(e, start)
        println("[CHECKS] ${checks.map { "${it.short}:${it.ok}:${it.title}" }}")
        assertTrue(checks.any { it.short == "Doors" && !it.ok }, "door goes nowhere")
        assertTrue(checks.any { it.short == "Items" && !it.ok }, "item out of reach")
    }
}
