package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.ProjectLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Experiment E4: warp into a project-created room that no vanilla door leads into,
 * after booting straight into gameplay without a save. Records results; asserts nothing
 * beyond the setup succeeding.
 */
class V1E4WarpTest {
    private fun log(msg: String) = println("[E4] $msg")

    @Test
    fun `e4 warp into project room`() {
        val baseRom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(baseRom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT"))).apply { mkdirs() }
        val open = ProjectLifecycle.create("E4NewRoom-${System.currentTimeMillis()}", work, baseRom)
        val projectDir = open.file.parentFile
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        var parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        val landing = requireNotNull(parser.readRoomHeader(0x91F8))
        editor.loadRoom(0x91F8, parser, landing)

        // Blank 1x1 Crateria room in the first free map cell, Landing Site's tileset, no doors.
        var created: com.supermetroid.editor.data.ProjectNewRoom? = null
        loop@ for (y in 1 until 30) for (x in 0 until 63) {
            val req = NewRoomCreationRequest("E4 Test Room", cloneCurrentRoom = false, area = 0, mapX = x, mapY = y, width = 1, height = 1, tileset = landing.tileset)
            val r = runCatching { editor.createNewRoomWorkspace(req, parser) }.getOrNull() ?: continue
            created = r.first; parser = r.second
            log("created room previewId=0x${r.first.previewRoomId.toString(16)} at map ($x,$y) doors=${r.first.doors.size}")
            break@loop
        }
        val room = requireNotNull(created)
        val roomId = room.previewRoomId
        val incoming = parser.findDoorsLeadingTo(roomId)
        log("doors leading into new room in workspace ROM: ${incoming.size}")
        editor.loadRoom(roomId, parser, requireNotNull(parser.readRoomHeader(roomId)))

        // Floor (bottom 3 rows) and side walls via the Maker translator.
        val m = MakerImporter.fromEditor(editor)
        val solid = 0x8111
        val floored = m.copy(terrain = m.terrain.mapIndexed { i, word ->
            val x = i % m.widthBlocks; val y = i / m.widthBlocks
            if (y >= m.heightBlocks - 3 || x == 0 || x == m.widthBlocks - 1) solid else word
        })
        log("translate: ${ClassicMakerTranslator.apply(floored, editor)}")
        val exportPath = requireNotNull(editor.exportToRom(parser)) { "export failed: ${editor.statusMessage}" }
        val exported = File(exportPath).readBytes()
        val header = exported.copyOfRange(0x078000 + (roomId and 0x7FFF), 0x078000 + (roomId and 0x7FFF) + 11)
        log("exported ${exportPath}; header at \$8F:${roomId.toString(16)} = ${header.joinToString("") { "%02x".format(it) }}")
        val exParser = RomParser.loadRom(exportPath)
        log("doors leading into new room in exported ROM: ${exParser.findDoorsLeadingTo(roomId).size}")

        // Play build only: add a test DoorDef in bank $83 free space that targets the new room.
        val play = exported.copyOf()
        val bank83 = 0x018000 until 0x020000
        val freePc = (bank83.last - 31 downTo bank83.first).first { pc -> (0 until 32).all { play[pc + it] == 0xFF.toByte() } } + 8
        val doorPtr = 0x8000 + (freePc - 0x018000)
        val door = intArrayOf(roomId and 0xFF, roomId shr 8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00)
        door.forEachIndexed { i, v -> play[freePc + i] = v.toByte() }
        val playRom = File(projectDir, "exports").apply { mkdirs() }.let { File(it, "e4-play.smc") }.apply { writeBytes(play) }
        log("test DoorDef at \$83:${doorPtr.toString(16)} (pc 0x${freePc.toString(16)}) -> room 0x${roomId.toString(16)}; play ROM ${playRom.absolutePath}")

        for ((label, state) in listOf("spike warp (state 6)" to 0x06, "door-hit warp (state 9)" to 0x09)) {
            tryWarp(playRom, work, label, state, doorPtr, roomId)
        }
    }

    private fun tryWarp(rom: File, work: File, label: String, warpState: Int, doorPtr: Int, roomId: Int) = runBlocking {
        val tag = "e4-state$warpState"
        val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu-$tag").apply { deleteRecursively(); mkdirs() })
        b.connect()
        try {
            b.startSession(SessionConfig(romPath = rom.absolutePath))
            suspend fun w(a: Int, v: Int) = b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))
            suspend fun rw(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            var f = 0
            while (rw(0x0998) != 0x01 && f < 1200) { b.step(EmulatorInput(repeat = 10, includeFrame = false)); f += 10 }
            w(0x09C2, 299); w(0x09C4, 299); w(0x079F, 0); w(0x078B, 0); w(0x0998, 0x06)
            f = 0
            while (rw(0x0998) != 0x08 && f < 1200) { b.step(EmulatorInput(repeat = 10, includeFrame = false)); f += 10 }
            b.step(EmulatorInput(repeat = 300, includeFrame = false)) // let the arrival pose finish
            log("$label: before warp room=0x${rw(0x079B).toString(16)} state=0x${rw(0x0998).toString(16)}")
            w(0x078D, doorPtr); w(0x0998, warpState)
            val trace = mutableListOf<String>()
            repeat(20) {
                b.step(EmulatorInput(repeat = 15, includeFrame = it == 19))
                trace += "0x${rw(0x0998).toString(16)}/0x${rw(0x079B).toString(16)}"
            }
            val finalRoom = rw(0x079B); val finalState = rw(0x0998)
            b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "$tag.png")) }
            log("$label: state/room every 15f = $trace")
            log("$label: RESULT room=0x${finalRoom.toString(16)} state=0x${finalState.toString(16)} samus=(${rw(0x0AF6)},${rw(0x0AFA)}) inNewRoom=${finalRoom == roomId}")
            if (finalRoom == roomId && finalState == 0x08) {
                // Door geometry decides where Samus lands; place her explicitly above the painted floor.
                w(0x0AF6, 0x60); w(0x0AFA, 0xA0)
                b.step(EmulatorInput(repeat = 60, includeFrame = false))
                val right = List(12) { if (it == 7) 1 else 0 }
                val r = b.step(EmulatorInput(buttons = right, repeat = 30, includeFrame = true))
                b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "$tag-placed.png")) }
                log("$label: placed + Right 30f -> samus=(${r.snapshot.samusX},${r.snapshot.samusY}) state=0x${r.snapshot.gameState?.toString(16)} hp=${r.snapshot.health}")
            }
            b.closeSession()
        } finally {
            b.disconnect()
        }
    }
}
