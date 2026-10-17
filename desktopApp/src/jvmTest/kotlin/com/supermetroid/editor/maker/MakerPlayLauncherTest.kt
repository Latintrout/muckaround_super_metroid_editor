package com.supermetroid.editor.maker

import androidx.compose.ui.graphics.toAwtImage
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in (V1_ROM / V1_OUT): Maker Play reaches several vanilla rooms from a no-save boot. */
class MakerPlayLauncherTest {
    @Test
    fun `maker play reaches rooms without a save`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val out = File(requireNotNull(System.getenv("V1_OUT")), "play-launcher").apply { mkdirs() }
        val parser = RomParser.loadRom(rom.absolutePath)
        val loadout = MakerPlayLoadout(items = 0x1105, beams = 0x1000, energyTanks = 4, missiles = 30)
        val targets = (System.getenv("PLAY_ROOMS") ?: "91F8,92FD,9994,9804,92B3,95FF").split(",").map { it.trim().toInt(16) }
        val results = mutableListOf<Boolean>()
        for (roomId in targets) for (place in listOf(false, true)) runBlocking {
            val plan = requireNotNull(MakerPlayPlan.forRoom(parser, roomId)) { "no plan for ${roomId.toString(16)}" }
            val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(out, "emu").apply { deleteRecursively(); mkdirs() })
            b.connect()
            try {
                b.startSession(SessionConfig(romPath = rom.absolutePath))
                suspend fun r16(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val launcher = MakerPlayLauncher(
                    read16 = { r16(it) },
                    write16 = { a, v -> b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                    waitFrames = { b.step(EmulatorInput(repeat = it, includeFrame = false)) },
                    log = { println("[PLAY] ${roomId.toString(16)} $it") },
                    writeBlock = { a, bytes -> b.writeMemory(a, bytes) },
                )
                val r = launcher.launch(plan, loadout, placeSamus = place)
                // Regression (GUI escape-sequence bug): power-on RAM must not leak into events/bosses/items.
                val flags = b.readMemory(0xD820, 0x10) + b.readMemory(0xD870, 0x40) + b.readMemory(0xD8B0, 0x40)
                val flagsClear = flags.all { it == 0.toByte() }
                val ammo = listOf(0x09CA, 0x09CC, 0x09CE, 0x09D0, 0x09D4, 0x09D6).map { r16(it) }
                val x0 = r16(0x0AF6); val y0 = r16(0x0AFA); val hp0 = r16(0x09C2)
                b.step(EmulatorInput(repeat = 120, includeFrame = false))
                val right = List(12) { if (it == 7) 1 else 0 }; val left = List(12) { if (it == 6) 1 else 0 }
                b.step(EmulatorInput(buttons = if (plan.entryDoor?.direction?.and(3) == 1) left else right, repeat = 30, includeFrame = false))
                b.step(EmulatorInput(repeat = 10, includeFrame = true))
                val x1 = r16(0x0AF6); val y1 = r16(0x0AFA)
                b.step(EmulatorInput(repeat = 600, includeFrame = true)) // escape states explode/kill within seconds
                val hp = r16(0x09C2); val state = r16(0x0998); val room = r16(0x079B)
                b.frameHolder.latestFrame?.toAwtImage()?.let { ImageIO.write(it, "png", File(out, "${roomId.toString(16)}-place$place.png")) }
                val ok = r.ok && state == 8 && room == roomId && hp0 == 499 && hp > 0 && x1 != x0 && flagsClear && ammo.all { it == 0 }
                println("[PLAY] ${roomId.toString(16)} place=$place door=${plan.entryDoor?.let { "0x%04X dir=%d cap=0x%04X".format(it.doorDefPtr, it.direction and 3, it.doorCapCode) }} " +
                    "station=${plan.area}/${plan.stationIndex} -> ${r.message}; arrive=($x0,$y0) after=($x1,$y1) state=0x${state.toString(16)} hp=$hp0->$hp flagsClear=$flagsClear ammo=$ammo OK=$ok")
                if (place) results += ok else results += (flagsClear && state == 8 && hp0 == 499 && hp > 0)
                b.closeSession()
            } finally { b.disconnect() }
        }
        assertTrue(results.all { it }, "with placement: $results")
    }
}
