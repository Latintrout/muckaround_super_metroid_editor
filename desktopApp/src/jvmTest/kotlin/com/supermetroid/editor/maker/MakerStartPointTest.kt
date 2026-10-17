package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.maker.readyChecks
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
        val baseRom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(baseRom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT")), "start-point").apply { deleteRecursively(); mkdirs() }
        val open = ProjectLifecycle.create("StartPoint-${System.currentTimeMillis()}", work, baseRom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        val landing = requireNotNull(parser.readRoomHeader(0x91F8))
        editor.loadRoom(0x91F8, parser, landing)

        // Blank two-screen Crateria room in the first free map cells, no doors.
        var created: com.supermetroid.editor.data.ProjectNewRoom? = null
        loop@ for (y in 1 until 30) for (x in 0 until 62) {
            val req = NewRoomCreationRequest("Start Point Room", cloneCurrentRoom = false, area = 0, mapX = x, mapY = y, width = 2, height = 1, tileset = landing.tileset)
            val r = runCatching { editor.createNewRoomWorkspace(req, parser) }.getOrNull() ?: continue
            created = r.first; parser = r.second
            break@loop
        }
        val roomId = requireNotNull(created).previewRoomId
        editor.loadRoom(roomId, parser, requireNotNull(parser.readRoomHeader(roomId)))
        assertTrue(parser.findDoorsLeadingTo(roomId).isEmpty(), "the new room should have no door leading in")
        assertNull(MakerPlayPlan.forRoom(parser, roomId), "without a start point Play cannot reach the room")
        assertTrue(!readyChecks(parser, roomId) { "?" }.first().ok)

        // Floor (bottom 3 rows) and walls, then a start point on the second screen, standing on the floor.
        val m = MakerImporter.fromEditor(editor)
        val floored = m.copy(terrain = m.terrain.mapIndexed { i, word ->
            val x = i % m.widthBlocks; val y = i / m.widthBlocks
            if (y >= m.heightBlocks - 3 || x == 0 || x == m.widthBlocks - 1) 0x8111 else word
        })
        ClassicMakerTranslator.apply(floored, editor)
        val start = 20 to m.heightBlocks - 4
        editor.setMakerStartPoint(MakerStartPoint(5, 5))
        editor.setMakerStartPoint(MakerStartPoint(start.first, start.second)) // placing again moves it
        assertEquals(MakerStartPoint(start.first, start.second), editor.makerStartPoint())
        editor.undo()
        assertEquals(MakerStartPoint(5, 5), editor.makerStartPoint())
        editor.redo()
        assertEquals(MakerStartPoint(start.first, start.second), editor.makerStartPoint())
        assertEquals(1, editor.project.makerStartPoints.size)
        assertTrue(readyChecks(parser, roomId, start) { "?" }.first().ok)

        val exportPath = requireNotNull(editor.exportToRom(parser)) { "export failed: ${editor.statusMessage}" }
        val exported = File(exportPath)
        val exportedBytes = exported.readBytes()
        val exParser = RomParser.loadRom(exportPath)
        val plan0 = requireNotNull(MakerPlayPlan.forRoom(exParser, roomId, start))
        val (playRom, door) = MakerPlayDoor.writePlayCopy(exported, roomId, start)
        assertTrue(exportedBytes.contentEquals(exported.readBytes()), "the export must not change")
        val plan = plan0.withEntryDoor(door)

        runBlocking {
            val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu").apply { mkdirs() })
            b.connect()
            try {
                b.startSession(SessionConfig(romPath = playRom.absolutePath))
                suspend fun r16(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val launcher = MakerPlayLauncher(
                    read16 = { r16(it) },
                    write16 = { a, v -> b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                    waitFrames = { b.step(EmulatorInput(repeat = it, includeFrame = false)) },
                    log = { println("[START] $it") },
                    writeBlock = { a, bytes -> b.writeMemory(a, bytes) },
                )
                val r = launcher.launch(plan, MakerPlayLoadout(items = 0x1105, beams = 0x1000, energyTanks = 4, missiles = 30))
                b.step(EmulatorInput(repeat = 90, includeFrame = true))
                b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "start-point.png")) }
                val room = r16(0x079B); val state = r16(0x0998)
                val x = r16(0x0AF6); val y = r16(0x0AFA)
                val camX = r16(0x0911); val camY = r16(0x0915)
                val (ex, ey) = MakerPlayPlan.startPixel(start.first, start.second)
                println("[START] ${r.message}; room=0x${room.toString(16)} state=0x${state.toString(16)} samus=($x,$y) expected=($ex,$ey) camera=($camX,$camY)")
                assertTrue(r.ok, r.message)
                assertEquals(roomId, room)
                assertEquals(0x08, state)
                assertTrue(abs(x - ex) <= 4 && abs(y - ey) <= 4, "Samus at ($x,$y), expected near ($ex,$ey)")
                assertTrue(x - camX in 0 until 256 && y - camY in 0 until 224, "Samus off-screen: ($x,$y) camera ($camX,$camY)")
                b.closeSession()
            } finally { b.disconnect() }
        }
    }
}
