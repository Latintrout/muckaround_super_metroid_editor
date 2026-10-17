package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.maker.applyMakerNewRoomLook
import com.supermetroid.editor.ui.maker.planMakerNewRoom
import com.supermetroid.editor.ui.maker.readyChecks
import com.supermetroid.editor.ui.maker.startPointCheck
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Maker Start point: saved with the project, and Play enters door-less rooms through it. */
class MakerStartPointTest {
    @Test
    fun `start point survives project save and load`() {
        val dir = Files.createTempDirectory("sp").toFile()
        val file = File(dir, "sp.smedit")
        val project = SmEditProject(romPath = File(dir, "base.smc").absolutePath)
        project.makerStartPoints["project:room-a"] = MakerStartPoint(20, 12)
        project.makerStartPoints[project.romRoomDestination(0x91F8)] = MakerStartPoint(3, 4)
        assertTrue(ProjectFileService.saveProject(project, file.absolutePath, null, false) {})
        val loaded = ProjectFileService.loadProject(file)
        assertEquals(project.makerStartPoints, loaded.makerStartPoints)

        // The V1 Maker room format carries it too.
        val room = MakerRoom(0x91F8, 1, 1, listOf(0), listOf(0), emptyList(), emptyList(), MakerStartPoint(0, 0))
        assertEquals(room, MakerJson.decode(MakerJson.encode(MakerProject(rooms = listOf(room)))).rooms.single())
    }

    @Test
    fun `temporary play door goes only into the play copy`() {
        val exported = ByteArray(0x300000) { 0xFF.toByte() }
        val (play, door) = MakerPlayDoor.install(exported, 0xA5C0, 20 to 12)
        assertTrue(exported.all { it == 0xFF.toByte() }, "the exported ROM must not change")
        val pc = 0x018000 + (door.doorDefPtr - 0x8000)
        assertTrue(door.doorDefPtr in 0x8000..0xFFF4)
        val bytes = (0 until 12).map { play[pc + it].toInt() and 0xFF }
        assertEquals(listOf(0xC0, 0xA5, 0, 0, 0, 0, 1, 0, 0x00, 0x80, 0, 0), bytes)
        assertEquals(12, play.indices.count { play[it] != exported[it] })
    }


    /** Opt-in (V1_ROM / V1_OUT): a project-created room with no door in is played from its Start point. */
    @Test
    fun `play enters a door-less project room at its start point`() {
        val r = playMakerRoom("start-point", sourceRoomId = 0x91F8) { editor, parser, roomId ->
            assertTrue(parser.findDoorsLeadingTo(roomId).isEmpty(), "the new room should have no door leading in")
            assertNull(MakerPlayPlan.forRoom(parser, roomId), "without a start point Play cannot reach the room")
            assertTrue(!readyChecks(parser, roomId) { "?" }.first().ok)
            // A start point on the second screen, standing on the floor (its top row is the last one on screen).
            val start = MakerStartPoint(20, editor.workingBlocksTall - 4)
            editor.setMakerStartPoint(MakerStartPoint(5, 5))
            editor.setMakerStartPoint(start) // placing again moves it
            assertEquals(start, editor.makerStartPoint())
            editor.undo()
            assertEquals(MakerStartPoint(5, 5), editor.makerStartPoint())
            editor.redo()
            assertEquals(start, editor.makerStartPoint())
            assertEquals(1, editor.project.makerStartPoints.size)
            assertTrue(readyChecks(parser, roomId, start.x to start.y) { "?" }.first().ok)
            start
        } ?: return
        assertEquals(0x04, r.facing, "facing right")
        assertEquals(0, r.area)
    }

    /**
     * Opt-in: a Maker room in Brinstar with the Start point facing left, booting from a Crateria station
     * (as Play does for an area with no save station): the door changes area and Samus faces left.
     */
    @Test
    fun `play crosses areas and faces left`() {
        val r = playMakerRoom("cross-area", sourceRoomId = 0x9AD9, bootArea = 0) { editor, _, _ ->
            MakerStartPoint(8, editor.workingBlocksTall - 4, facingLeft = true)
        } ?: return
        assertEquals(1, r.area, "the game is in Brinstar")
        assertEquals(0x08, r.facing, "Samus faces left")
    }

    @Test
    fun `start point placement checks`() {
        // A 10x10 room: walls at x=0, floor at y=9, a pillar at x=5 for y in 3..8.
        val type = { x: Int, y: Int ->
            if (x !in 0 until 10 || y !in 0 until 10) null
            else if (x == 0 || y == 9 || (x == 5 && y >= 3)) 0x8 else 0x0
        }
        assertNull(startPointCheck(2 to 8, type), "standing on the floor: no warning")
        assertEquals(false, startPointCheck(5 to 4, type)?.ok, "inside the pillar")
        assertNull(startPointCheck(5 to 2, type), "standing on top of the pillar")
        assertEquals(false, startPointCheck(4 to 9, type)?.ok, "feet in the floor")
        assertEquals("She drops 3 blocks to the floor first", startPointCheck(2 to 5, type)?.detail)
        val open = { x: Int, y: Int -> if (x in 0 until 10 && y in 0 until 10) 0x0 else null }
        assertEquals(false, startPointCheck(2 to 5, open)?.ok, "nothing below")
    }

    private data class PlayResult(val area: Int, val facing: Int)

    /** Makes an empty 2x1 room next to [sourceRoomId] as New room does, sets its Start point via [setup], exports and plays it. */
    private fun playMakerRoom(
        tag: String, sourceRoomId: Int, bootArea: Int? = null,
        setup: (EditorState, RomParser, Int) -> MakerStartPoint,
    ): PlayResult? {
        val baseRom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(baseRom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT")), tag).apply { deleteRecursively(); mkdirs() }
        val open = ProjectLifecycle.create("StartPoint-${System.currentTimeMillis()}", work, baseRom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(sourceRoomId, parser, requireNotNull(parser.readRoomHeader(sourceRoomId)))

        // A 2x1 empty room made exactly as the Maker's New room does: walls, 3-row floor, air, plain background; no doors.
        val newPlan = requireNotNull(planMakerNewRoom(editor, parser, sourceRoomId, width = 2, height = 1, name = "Start Point Room"))
        val (created, workspace) = editor.createNewRoomWorkspace(newPlan.request, parser)
        parser = workspace
        val roomId = created.previewRoomId
        editor.loadRoom(roomId, parser, requireNotNull(parser.readRoomHeader(roomId)))
        applyMakerNewRoomLook(editor, requireNotNull(newPlan.look))
        val start = setup(editor, parser, roomId)
        editor.setMakerStartPoint(start)

        val exportPath = requireNotNull(editor.exportToRom(parser)) { "export failed: ${editor.statusMessage}" }
        val exported = File(exportPath)
        val exportedBytes = exported.readBytes()
        val exParser = RomParser.loadRom(exportPath)
        val plan0 = requireNotNull(MakerPlayPlan.forRoom(exParser, roomId, start.x to start.y, start.facingLeft, bootArea))
        val (playRom, door) = MakerPlayDoor.writePlayCopy(exported, plan0)
        assertTrue(exportedBytes.contentEquals(exported.readBytes()), "the export must not change")
        val plan = plan0.withEntryDoor(door)

        return runBlocking {
            val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu").apply { mkdirs() })
            b.connect()
            try {
                b.startSession(SessionConfig(romPath = playRom.absolutePath))
                suspend fun r16(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val launcher = MakerPlayLauncher(
                    read16 = { r16(it) },
                    write16 = { a, v -> b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                    waitFrames = { b.step(EmulatorInput(repeat = it, includeFrame = false)) },
                    log = { println("[START] $tag $it") },
                    writeBlock = { a, bytes -> b.writeMemory(a, bytes) },
                )
                val r = launcher.launch(plan, MakerPlayLoadout(items = 0x1105, beams = 0x1000, energyTanks = 4, missiles = 30))
                b.step(EmulatorInput(repeat = 90, includeFrame = true))
                b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "$tag.png")) }
                val room = r16(0x079B); val state = r16(0x0998)
                val x = r16(0x0AF6); val y = r16(0x0AFA)
                val camX = r16(0x0911); val camY = r16(0x0915)
                val area = r16(0x079F); val facing = r16(0x0A1E) and 0xFF
                println("[START] $tag pose=0x${r16(0x0A1C).toString(16)} 0A1E=0x${r16(0x0A1E).toString(16)} 0A20=0x${r16(0x0A20).toString(16)}")
                val (ex, ey) = MakerPlayPlan.startPixel(start.x, start.y)
                println("[START] $tag ${r.message}; room=0x${room.toString(16)} area=$area facing=0x${facing.toString(16)} state=0x${state.toString(16)} " +
                    "samus=($x,$y) expected=($ex,$ey) camera=($camX,$camY)")
                assertTrue(r.ok, r.message)
                assertEquals(roomId, room)
                assertEquals(0x08, state)
                assertTrue(abs(x - ex) <= 4 && abs(y - ey) <= 4, "Samus at ($x,$y), expected near ($ex,$ey)")
                assertTrue(x - camX in 0 until 256 && y - camY in 0 until 224, "Samus off-screen: ($x,$y) camera ($camX,$camY)")
                // The room's background is plain in game (a blank room's layer 2 would draw a tile pattern).
                val frame = requireNotNull(b.frameHolder.latestFrame).toAwtImage()
                val sky = (48 until 120 step 2).flatMap { py -> (40 until 216 step 2).map { px -> frame.getRGB(px, py) } }
                val share = sky.groupingBy { it }.eachCount().maxOf { it.value } / sky.size.toDouble()
                println("[START] $tag plain-sky share ${"%.2f".format(share)}")
                assertTrue(share > 0.9, "the room's inside should be plain, not patterned (top colour share $share)")
                // Samus answers the controls: hold Right (or Left) and she moves.
                val dirButton = List(12) { if (it == (if (start.facingLeft) 6 else 7)) 1 else 0 }
                b.step(EmulatorInput(buttons = dirButton, repeat = 30, includeFrame = false))
                val moved = r16(0x0AF6) - x
                println("[START] $tag moved=$moved pose=0x${r16(0x0A1C).toString(16)}")
                assertTrue(if (start.facingLeft) moved < -8 else moved > 8, "Samus should walk when the d-pad is held (moved $moved)")
                b.closeSession()
                PlayResult(area, facing)
            } finally { b.disconnect() }
        }
    }
}
