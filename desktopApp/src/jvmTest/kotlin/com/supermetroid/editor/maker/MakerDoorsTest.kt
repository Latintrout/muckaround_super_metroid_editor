package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.maker.MakerNewRoomStart
import com.supermetroid.editor.ui.maker.applyMakerNewRoomLook
import com.supermetroid.editor.ui.maker.connectMakerDoor
import com.supermetroid.editor.ui.maker.makerDoorwayAt
import com.supermetroid.editor.ui.maker.planMakerNewRoom
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in (V1_ROM / V1_OUT): a Maker door between two rooms you made, with its return door, walked through in game. */
class MakerDoorsTest {
    @Test
    fun `connect two maker rooms and walk through both ways`() {
        val baseRom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(baseRom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT")), "doors").apply { deleteRecursively(); mkdirs() }
        val open = ProjectLifecycle.create("Doors-${System.currentTimeMillis()}", work, baseRom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))

        fun makeRoom(name: String, w: Int): Int {
            e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
            val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, w, 1, MakerNewRoomStart.EMPTY, name, seed = 1))
            val (created, ws) = e.createNewRoomWorkspace(plan.request, parser); parser = ws
            e.loadRoom(created.previewRoomId, parser, requireNotNull(parser.readRoomHeader(created.previewRoomId)))
            e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
            applyMakerNewRoomLook(e, requireNotNull(plan.look))
            return created.previewRoomId
        }
        val b = makeRoom("Room B", 1)
        val a = makeRoom("Room A", 2)

        // A blue door on A's right wall, standing on the floor (rows 9-12; the floor starts at row 13).
        val w = e.workingBlocksWide
        e.seedBuiltInPatterns(parser)
        e.selectAndApplyPattern("builtin_door_blue_right")
        e.beginStroke(); e.paintAt(w - 1, 9); e.endStroke("door")
        assertNotNull(e.makerDoorwayAt(w - 1, 10))
        val link = e.connectMakerDoor(parser, w - 1, 10, b, "builtin_door_blue_right").getOrThrow()
        parser = link.parser
        println("[DOORS] ${link.message}")
        val roomA = e.project.newRoomForPreviewId(a)!!; val roomB = e.project.newRoomForPreviewId(b)!!
        assertEquals(e.project.newRoomDestination(roomB.id), roomA.doors.single().destination)
        assertEquals(e.project.newRoomDestination(roomA.id), roomB.doors.single().destination)
        assertEquals(a, e.currentRoomId, "back in the room the door was placed in")
        assertTrue((9..12).all { y -> (e.readBlockWord(w - 1, y) shr 12) and 0xF == 0x9 && e.readBts(w - 1, y) == 0 }, "A's doorway links connection 0")

        // Start in A facing the door; export and play.
        e.setMakerStartPoint(MakerStartPoint(w - 6, 12))
        val exported = File(requireNotNull(e.exportToRom(parser)) { "export failed: ${e.statusMessage}" })
        val exParser = RomParser.loadRom(exported.absolutePath)
        // The cap sits one column inside the doorway (as in the game's rooms), so the door blocks survive it.
        val ha = requireNotNull(exParser.readRoomHeader(a))
        assertTrue(exParser.parsePlmSet(ha.plmSetPtr).any { RomParser.isDoorCapPlm(it.id) && it.x == w - 2 && it.y == 9 }, "cap one column inside")
        println("[DOORS] exported A doors=${exParser.parseDoorList(ha.doorOut).map { "dest=%04X dir=%d cap=%04X scr=%d,%d".format(it.destRoomPtr, it.direction and 3, it.doorCapCode, it.screenX, it.screenY) }}")
        val plan0 = requireNotNull(MakerPlayPlan.forRoom(exParser, a, (w - 6) to 12))
        val (playRom, door) = MakerPlayDoor.writePlayCopy(exported, plan0)
        val plan = plan0.withEntryDoor(door)
        runBlocking {
            val be = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu").apply { mkdirs() })
            be.connect()
            try {
                be.startSession(SessionConfig(romPath = playRom.absolutePath))
                suspend fun r16(addr: Int) = be.readMemory(addr, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val launcher = MakerPlayLauncher(
                    read16 = { r16(it) },
                    write16 = { ad, v -> be.writeMemory(ad, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                    waitFrames = { be.step(EmulatorInput(repeat = it, includeFrame = false)) },
                    log = { println("[DOORS] $it") },
                    writeBlock = { ad, bytes -> be.writeMemory(ad, bytes) },
                )
                val r = launcher.launch(plan, MakerPlayLoadout(items = 0x1105, beams = 0x1000, energyTanks = 4, missiles = 30))
                assertTrue(r.ok, r.message)
                val right = List(12) { if (it == 7) 1 else 0 }; val left = List(12) { if (it == 6) 1 else 0 }
                suspend fun walk(buttons: List<Int>, target: Int, label: String) {
                    // Face the door and shoot it open (blue doors open to any shot; X shoots), then walk through.
                    be.step(EmulatorInput(buttons = buttons, repeat = 4, includeFrame = false))
                    val shoot = buttons.mapIndexed { i, v -> if (i == 9) 1 else v }
                    be.step(EmulatorInput(buttons = shoot, repeat = 4, includeFrame = false))
                    be.step(EmulatorInput(repeat = 40, includeFrame = false))
                    var f = 0
                    while (f < 600 && !(r16(0x079B) == target && r16(0x0998) == 0x08)) { be.step(EmulatorInput(buttons = buttons, repeat = 10, includeFrame = false)); f += 10 }
                    be.step(EmulatorInput(repeat = 60, includeFrame = true))
                    be.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "$label.png")) }
                    println("[DOORS] $label: room=0x${r16(0x079B).toString(16)} state=0x${r16(0x0998).toString(16)} samus=(${r16(0x0AF6)},${r16(0x0AFA)}) after $f frames")
                    assertEquals(target, r16(0x079B), "$label: reached the other room")
                    assertEquals(0x08, r16(0x0998), "$label: playing")
                }
                walk(right, b, "a-to-b")
                walk(left, a, "b-to-a")
                be.closeSession()
            } finally { be.disconnect() }
        }
    }
}
