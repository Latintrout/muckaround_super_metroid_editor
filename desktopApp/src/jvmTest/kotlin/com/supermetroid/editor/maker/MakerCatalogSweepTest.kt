package com.supermetroid.editor.maker

import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in (V1_ROM, CATALOG_OUT): sweeps the whole ROM and writes a raw inventory for review. */
class MakerCatalogSweepTest {
    @Test
    fun `sweep the game`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val parser = RomParser.loadRom(rom.absolutePath)
        val t0 = System.currentTimeMillis()
        val c = MakerCatalog.build(parser)
        val ms = System.currentTimeMillis() - t0
        val names = parser.roomCatalog.rooms.associate { it.getRoomIdAsInt() to it.name }
        val out = StringBuilder()
        out.appendLine("sweep ${ms}ms rooms=${parser.roomCatalog.rooms.size} enemies=${c.enemies.size} plms=${c.plms.size} tilesets=${c.tiles.keys} fx=${c.fx.size}")
        c.enemies.forEach { e ->
            out.appendLine("ENEMY 0x%04X %-28s rooms=%3d init=0x%04X props=0x%04X vram=%s e.g. %s".format(e.speciesId, e.name, e.rooms.size, e.initParam, e.properties,
                e.vramDestinations.joinToString { "0x" + it.toString(16) }, e.rooms.take(3).joinToString { names[it] ?: it.toString(16) }))
        }
        c.plms.forEach { p -> out.appendLine("PLM 0x%04X %-32s rooms=%3d count=%4d".format(p.plmId, p.name, p.rooms.size, p.count)) }
        c.fx.forEach { f -> out.appendLine("FX 0x%02X %-24s rooms=%3d e.g. %s".format(f.fxType, f.name, f.rooms.size, f.rooms.take(3).joinToString { names[it] ?: "?" })) }
        c.tiles.forEach { (ts, tiles) ->
            val byType = tiles.groupBy { it.blockType }.mapValues { (_, v) -> v.size }
            out.appendLine("TILESET $ts rooms=${c.roomsByTileset[ts]?.size} distinct (word,bts)=${tiles.size} byType=$byType")
        }
        val file = File(System.getenv("CATALOG_OUT") ?: "build/catalog-sweep.txt").apply { parentFile?.mkdirs() }
        file.writeText(out.toString())
        println("[SWEEP] " + out.lines().first())
        assertTrue(c.enemies.isNotEmpty() && c.plms.isNotEmpty() && c.tiles.isNotEmpty())
    }
}
