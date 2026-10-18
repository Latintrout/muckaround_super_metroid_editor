package com.supermetroid.editor.maker

import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loadouts: Spazer and Plasma never both on; opt-in (V1_ROM): a full loadout lands in the game's memory. */
class MakerLoadoutTest {
    @Test
    fun `plasma wins over spazer when both are chosen`() {
        assertEquals(0x1008, MakerPlayLoadout(0, 0x100C, 0, 0).equippedBeams)
        assertEquals(0x1004, MakerPlayLoadout(0, 0x1004, 0, 0).equippedBeams)
    }

    @Test
    fun `everything loadout is in memory after launch`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val work = File(requireNotNull(System.getenv("V1_OUT")), "loadout").apply { deleteRecursively(); mkdirs() }
        val parser = RomParser.loadRom(rom.absolutePath)
        val plan = requireNotNull(MakerPlayPlan.forRoom(parser, 0x91F8))
        val everything = MakerPlayLoadout(items = 0xF32F, beams = 0x100F, energyTanks = 14, missiles = 230, supers = 50, powerBombs = 50, reserveTanks = 4)
        runBlocking {
            val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu").apply { mkdirs() })
            b.connect()
            try {
                b.startSession(SessionConfig(romPath = rom.absolutePath))
                suspend fun r16(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val launcher = MakerPlayLauncher(
                    read16 = { r16(it) },
                    write16 = { a, v -> b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                    waitFrames = { b.step(EmulatorInput(repeat = it, includeFrame = false)) },
                    writeBlock = { a, bytes -> b.writeMemory(a, bytes) },
                )
                assertTrue(launcher.launch(plan, everything).ok)
                b.step(EmulatorInput(repeat = 60, includeFrame = false))
                assertEquals(0xF32F, r16(0x09A2), "items on"); assertEquals(0xF32F, r16(0x09A4), "items collected")
                assertEquals(0x100B, r16(0x09A6), "beams on: Plasma, not Spazer"); assertEquals(0x100F, r16(0x09A8), "beams collected")
                assertEquals(99 + 1400, r16(0x09C4), "max energy")
                assertEquals(230, r16(0x09C8)); assertEquals(50, r16(0x09CC)); assertEquals(50, r16(0x09D0))
                assertEquals(400, r16(0x09D4), "reserve tanks"); assertEquals(1, r16(0x09C0), "reserve on auto")
                assertEquals(0x08, r16(0x0998), "still playing")
                b.closeSession()
            } finally { b.disconnect() }
        }
    }
}
