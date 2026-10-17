package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.MAKER_LOADOUTS
import com.supermetroid.editor.ui.MakerLoadout
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.buildIpsPatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 0A vertical slice V1 (Classic): import Landing Site into Maker JSON, edit it,
 * export through SMEDIT, Play in the embedded libretro core, edit again, Play again,
 * and re-import (R1). Opt-in: needs V1_ROM (clean JU ROM) and V1_OUT (work folder).
 */
class V1VerticalSliceTest {
    private val landingSite = 0x91F8
    private val missile = 0xEEDB

    private fun log(msg: String) = println("[V1] $msg")
    private fun sha1(f: File) = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `v1 landing site slice`() {
        val baseRom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(baseRom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT"))).apply { mkdirs() }
        val baseSha = sha1(baseRom)
        log("base ROM ${baseRom.absolutePath} sha1=$baseSha readOnly=${!baseRom.canWrite()}")

        // ── 1. New project + import Landing Site into Maker JSON ──
        val name = "V1LandingSite-${System.currentTimeMillis()}"
        val open = ProjectLifecycle.create(name, work, baseRom)
        val projectDir = open.file.parentFile
        log("project ${open.file.absolutePath}")
        val parser0 = RomParser.loadRom(open.project.romPath)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(parser0)
        editor.loadRoom(landingSite, parser, requireNotNull(parser.readRoomHeader(landingSite)))
        val imported = MakerImporter.fromEditor(editor)
        val makerFile = File(projectDir, "landing_site.smmaker.json")
        makerFile.writeText(MakerJson.encode(MakerProject(rooms = listOf(imported))))
        log("imported ${imported.widthBlocks}x${imported.heightBlocks} blocks, items=${imported.items}, doors=${imported.doors.size} -> ${makerFile.name}")

        // ── 2. Edit 1 (on the Maker JSON): a 3-block solid platform and one Missile ──
        val solid = imported.terrain.groupingBy { it }.eachCount()
            .filterKeys { (it shr 12) and 0xF == 0x8 }.maxBy { it.value }.key
        fun isAir(room: MakerRoom, x: Int, y: Int) = (room.terrain[room.blockIndex(x, y)] shr 12) and 0xF == 0
        val platformY = 66
        val platformXs = 64..66
        val itemA = 69 to 66
        val itemB = 77 to 66
        for (x in platformXs) check(isAir(imported, x, platformY)) { "($x,$platformY) is not air" }
        check(isAir(imported, itemA.first, itemA.second) && isAir(imported, itemB.first, itemB.second))
        val edit1 = imported.copy(
            terrain = imported.terrain.toMutableList().apply { for (x in platformXs) this[imported.blockIndex(x, platformY)] = solid },
            bts = imported.bts.toMutableList().apply { for (x in platformXs) this[imported.blockIndex(x, platformY)] = 0 },
            items = imported.items + MakerItem(missile, itemA.first, itemA.second),
        )
        makerFile.writeText(MakerJson.encode(MakerProject(rooms = listOf(edit1))))
        log("edit1: platform word=0x${solid.toString(16)} at x=$platformXs y=$platformY; missile at $itemA")

        // ── 3. Classic export: Maker JSON -> SMEDIT edits -> temp ROM + IPS ──
        fun exportBuild(tag: String, target: MakerRoom): File {
            val r = ClassicMakerTranslator.apply(MakerJson.decode(makerFile.readText()).rooms.single().also { assertEquals(target, it) }, editor)
            log("$tag translate: $r")
            val out = File(requireNotNull(editor.exportToRom(parser)) { "export failed: ${editor.statusMessage}" })
            val exported = File(projectDir, "exports").apply { mkdirs() }.let { File(it, "$tag.smc") }
            out.copyTo(exported, overwrite = true)
            val ips = buildIpsPatch(baseRom.readBytes(), exported.readBytes())
            File(exported.parentFile, "$tag.ips").writeBytes(ips)
            log("$tag exported ${exported.absolutePath} sha1=${sha1(exported)} ips=${ips.size} bytes")
            logDiff(baseRom.readBytes(), exported.readBytes(), tag)
            return exported
        }
        val rom1 = exportBuild("edit1", edit1)
        assertEquals(baseSha, sha1(baseRom), "base ROM must be unchanged")

        // ── 4. Play #1 ──
        val loadout = MAKER_LOADOUTS[1]
        val play1 = play(rom1, loadout, work, "play1", edit1, solid, platformXs, platformY, itemA)

        // ── 5. Stop, reload project from disk, edit again (move the item), Play #2 ──
        val reloaded = ProjectFileService.loadProject(open.file)
        val roomEdits = reloaded.rooms[reloaded.roomKey(landingSite)]
        log("reloaded project: room edits present=${roomEdits != null} ops=${roomEdits?.operations?.size} plmChanges=${roomEdits?.plmChanges?.size}")
        assertTrue(roomEdits != null && roomEdits.hasEdits, "project state must survive")
        val fromDisk = MakerJson.decode(makerFile.readText()).rooms.single()
        assertEquals(edit1, fromDisk, "Maker JSON must survive")
        assertEquals(edit1, MakerImporter.fromEditor(editor), "editor state must still match Maker JSON after Play")
        val edit2 = fromDisk.copy(items = fromDisk.items.map { if (it == MakerItem(missile, itemA.first, itemA.second)) it.copy(x = itemB.first, y = itemB.second) else it })
        makerFile.writeText(MakerJson.encode(MakerProject(rooms = listOf(edit2))))
        log("edit2: missile moved $itemA -> $itemB")
        val rom2 = exportBuild("edit2", edit2)
        assertEquals(baseSha, sha1(baseRom), "base ROM must be unchanged")
        val play2 = play(rom2, loadout, work, "play2", edit2, solid, platformXs, platformY, itemB)

        // ── 6. R1: re-import the room from the exported ROM ──
        val reParser = RomParser.loadRom(rom2.absolutePath)
        val reEditor = EditorState().apply { testMode = true }
        reEditor.initForReadOnlyRom(rom2.absolutePath)
        reEditor.loadRoom(landingSite, reParser, requireNotNull(reParser.readRoomHeader(landingSite)))
        val reimported = MakerImporter.fromEditor(reEditor)
        val r1 = reimported.copy(items = reimported.items.sortedBy { it.x }) == edit2.copy(items = edit2.items.sortedBy { it.x })
        log("R1 round trip equal=$r1 (terrain=${reimported.terrain == edit2.terrain}, bts=${reimported.bts == edit2.bts}, items=${reimported.items}, doors=${reimported.doors == edit2.doors})")
        log("RESULT play1=$play1 play2=$play2 r1=$r1 baseUnchanged=${sha1(baseRom) == baseSha}")
        assertTrue(play1 && play2 && r1)
    }

    private fun logDiff(a: ByteArray, b: ByteArray, tag: String) {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < maxOf(a.size, b.size)) {
            if (a.getOrElse(i) { 0 } != b.getOrElse(i) { 0 }) {
                val start = i
                while (i < maxOf(a.size, b.size) && (a.getOrElse(i) { 0 } != b.getOrElse(i) { 0 } ||
                        (i + 1 until minOf(i + 16, maxOf(a.size, b.size))).any { a.getOrElse(it) { 0 } != b.getOrElse(it) { 0 } })) i++
                ranges += start until i
            } else i++
        }
        fun snes(pc: Int) = "$%02X:%04X".format(pc / 0x8000 + 0x80, pc % 0x8000 + 0x8000)
        log("$tag diff: ${ranges.size} ranges, ${ranges.sumOf { it.count() }} bytes")
        ranges.forEach { log("$tag   pc 0x%06X-0x%06X (%s) %d bytes".format(it.first, it.last, snes(it.first), it.count())) }
    }

    private fun play(
        rom: File, loadout: MakerLoadout, work: File, tag: String,
        expected: MakerRoom, solid: Int, platformXs: IntRange, platformY: Int, item: Pair<Int, Int>,
    ): Boolean = runBlocking {
        val stateDir = File(work, "emu-$tag").apply { deleteRecursively(); mkdirs() } // empty: no battery save
        val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = stateDir)
        b.connect()
        try {
            var r = b.startSession(SessionConfig(romPath = rom.absolutePath))
            log("$tag session: ${r.message}")
            suspend fun w(a: Int, v: Int) = b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))
            suspend fun rw(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            suspend fun shot(name: String) = b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(work, "$tag-$name.png")) }
            // Boot to the title screen (no save file), then load straight into gameplay at
            // Crateria load station 0 (the ship in Landing Site) with the test loadout.
            var frames = 0
            while (rw(0x0998) != 0x01 && frames < 1200) { b.step(EmulatorInput(repeat = 10, includeFrame = false)); frames += 10 }
            log("$tag title reached after $frames frames (state=0x${rw(0x0998).toString(16)})")
            val maxHealth = 99 + 100 * loadout.energyTanks
            w(0x09A2, loadout.items); w(0x09A4, loadout.items); w(0x09A6, loadout.beams); w(0x09A8, loadout.beams)
            w(0x09C2, maxHealth); w(0x09C4, maxHealth); w(0x09C6, loadout.missiles); w(0x09C8, loadout.missiles)
            w(0x079F, 0); w(0x078B, 0); w(0x0998, 0x06)
            frames = 0
            while (rw(0x0998) != 0x08 && frames < 1200) { b.step(EmulatorInput(repeat = 10, includeFrame = false)); frames += 10 }
            r = b.step(EmulatorInput(repeat = 60, includeFrame = true))
            val s = r.snapshot
            log("$tag gameplay after ${frames + 60} frames: state=0x${s.gameState?.toString(16)} room=0x${s.roomId?.toString(16)} hp=${s.health}/${s.maxHealth} missiles=${s.missiles} items=0x${s.collectedItems.toString(16)} beams=0x${s.collectedBeams.toString(16)} samus=(${s.samusX},${s.samusY})")
            shot("arrive")

            // Live level data ($7F:0002) and PLM table ($7E:1C37 ids, $7E:1C87 block offsets).
            val w = expected.widthBlocks
            val platformOk = platformXs.all { x -> rw(0x10002 + 2 * (platformY * w + x)) == solid }
            val ids = (0 until 40).map { rw(0x1C37 + 2 * it) }
            val pos = (0 until 40).map { rw(0x1C87 + 2 * it) }
            val plms = ids.indices.filter { ids[it] != 0 }.map { "0x%04X@(%d,%d)".format(ids[it], (pos[it] / 2) % w, (pos[it] / 2) / w) }
            val itemOk = ids.indices.any { ids[it] == missile && pos[it] == 2 * (item.second * w + item.first) }
            log("$tag live platform=$platformOk missileAt$item=$itemOk plms=$plms")

            // Hold Right briefly so the camera/Samus move (shows the game is playable), then capture.
            val right = List(12) { if (it == 7) 1 else 0 }
            val startX = s.samusX
            var moved = false
            for (attempt in 1..6) {
                b.step(EmulatorInput(repeat = 30, includeFrame = false)) // released
                r = b.step(EmulatorInput(buttons = right, repeat = 30, includeFrame = true))
                log("$tag input attempt $attempt (+60f): samus=(${r.snapshot.samusX},${r.snapshot.samusY}) pose=0x${rw(0x0A1C).toString(16)} state=0x${r.snapshot.gameState?.toString(16)}")
                if (r.snapshot.samusX != startX) { moved = true; break }
            }
            b.step(EmulatorInput(buttons = right, repeat = 40, includeFrame = true))
            r = b.step(EmulatorInput(repeat = 5, includeFrame = true))
            log("$tag playable(moved with Right)=$moved samus=(${r.snapshot.samusX},${r.snapshot.samusY})")
            shot("moved")
            b.closeSession()
            s.gameState == 0x08 && s.roomId == 0x91F8 && platformOk && itemOk && s.health == maxHealth
        } finally {
            b.disconnect()
        }
    }
}
