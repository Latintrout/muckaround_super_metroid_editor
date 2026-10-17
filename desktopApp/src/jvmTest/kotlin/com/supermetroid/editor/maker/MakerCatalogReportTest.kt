package com.supermetroid.editor.maker

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.maker.MakerCategory
import com.supermetroid.editor.ui.maker.MakerFx
import com.supermetroid.editor.ui.maker.MakerLibrary
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/** Opt-in (V1_ROM, CATALOG_REPORT=<md file>): writes the Maker catalogue report from the sweep + verified.json. */
class MakerCatalogReportTest {
    @Test
    fun `write catalogue report`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        val outPath = System.getenv("CATALOG_REPORT")
        assumeTrue(rom.isFile && outPath != null, "opt-in")
        val parser = RomParser.loadRom(rom.absolutePath)
        val catalog = MakerCatalog.build(parser)
        val verified = MakerLibrary.loadVerified()
        val names = parser.roomCatalog.rooms.associate { it.getRoomIdAsInt() to it.name }
        val lib = MakerLibrary(catalog, verified)
        val md = StringBuilder()
        fun rooms(ids: List<Int>) = ids.take(3).joinToString { names[it] ?: "0x%04X".format(it) } + if (ids.size > 3) " +${ids.size - 3} more" else ""

        md.appendLine("# Maker parts catalogue (full-game sweep)")
        md.appendLine()
        md.appendLine("Built from Super Metroid (JU) `da957f0d…`: every room (${parser.roomCatalog.rooms.size}) and every room state.")
        md.appendLine("A part joins the Classic library only after its in-game check passes. The check: place it in Landing Site, export, boot")
        md.appendLine("the real game headlessly with no save, run 10 seconds, and confirm it spawned and the game kept running.")
        md.appendLine()

        val enemyOk = catalog.enemies.filter { verified.enemies[it.speciesId]?.ok == true }
        val enemyNo = catalog.enemies.filter { verified.enemies[it.speciesId]?.ok != true }
        val itemIds = RomParser.ITEM_DEFS.flatMap { listOf(it.visibleId to "${it.name}", it.chozoId to "${it.name} (Chozo orb)", it.hiddenId to "${it.name} (hidden)") }
        val itemOk = itemIds.filter { verified.items[it.first]?.ok == true }
        val itemNo = itemIds.filter { verified.items[it.first]?.ok != true }

        md.appendLine("## Summary")
        md.appendLine()
        md.appendLine("| Category | In the library | Needs work |")
        md.appendLine("|---|---|---|")
        md.appendLine("| Enemies | ${enemyOk.size} | ${enemyNo.size} |")
        md.appendLine("| Items (all variants) | ${itemOk.size} | ${itemNo.size} |")
        var terrain = 0; var hazards = 0; var decor = 0
        for (ts in catalog.tiles.keys) {
            val tiles = catalog.tiles[ts].orEmpty()
            terrain += tiles.count { lib.kindOf(it)?.category == MakerCategory.TERRAIN }
            hazards += tiles.count { lib.kindOf(it)?.category == MakerCategory.HAZARDS }
            decor += tiles.count { lib.kindOf(it)?.category == MakerCategory.DECOR }
        }
        md.appendLine("| Terrain blocks (distinct, all tilesets) | $terrain (top 16 per kind shown for the room's tileset) | doors, extension and special-air blocks are structural |")
        md.appendLine("| Hazard blocks | $hazards | |")
        md.appendLine("| Decor tiles | $decor (top 32 drawn tiles shown per tileset) | |")
        val fxOk = MakerFx.entries.count { it != MakerFx.NONE && lib.fxVerdict(it.fxType)?.ok == true }
        val tsOk = catalog.roomsByTileset.keys.count { lib.tilesetVerdict(it)?.ok == true }
        md.appendLine("| Area effects (Room look) | $fxOk + None | Tourian statues, Ceres Ridley, Ceres elevator (scripted to their rooms) |")
        md.appendLine("| Tilesets (Room look) | $tsOk of ${catalog.roomsByTileset.size} | room background layer (BG data) is not editable yet |")
        md.appendLine()

        md.appendLine("## Enemies in the library (${enemyOk.size})")
        md.appendLine()
        md.appendLine("Each is placed with its most common vanilla settings. In a room that doesn't load its graphics yet, the Maker adds them")
        md.appendLine("(4 enemy types per room at most). When a room has no room left, the card is greyed with the reason.")
        md.appendLine()
        md.appendLine("| Enemy | ID | Vanilla rooms |")
        md.appendLine("|---|---|---|")
        enemyOk.forEach { md.appendLine("| ${it.name} | `%04X` | ${rooms(it.rooms)} |".format(it.speciesId)) }
        md.appendLine()
        md.appendLine("## Enemies that need work (${enemyNo.size})")
        md.appendLine()
        md.appendLine("| Enemy | ID | Why it is not in the library |")
        md.appendLine("|---|---|---|")
        enemyNo.forEach { e ->
            val why = verified.enemies[e.speciesId]?.reason ?: MakerCatalogRules.excludedEnemyReason(e.speciesId, e.name) ?: "not verified yet"
            md.appendLine("| ${e.name} | `%04X` | $why |".format(e.speciesId))
        }
        md.appendLine()
        md.appendLine("## Items")
        md.appendLine()
        md.appendLine("| Item | PLM | Status |")
        md.appendLine("|---|---|---|")
        itemIds.forEach { (id, name) -> md.appendLine("| $name | `%04X` | ${verified.items[id]?.let { if (it.ok) "in library" else "needs work: ${it.reason}" } ?: "not verified"} |".format(id)) }
        md.appendLine()
        md.appendLine("## Other objects found in the game")
        md.appendLine()
        md.appendLine("Doors (all colours and directions) and gates are in **Doors**. Save, energy and missile stations and the Chozo statue are in **Stations**.")
        md.appendLine("Not yet parts: map stations, elevator bases, scroll triggers (camera control), and unnamed room-specific objects:")
        md.appendLine()
        catalog.plms.filter { p -> !RomParser.isItemPlm(p.plmId) && !RomParser.isDoorCapPlm(p.plmId) && p.plmId !in setOf(0xB76F, 0xB6DF, 0xB6EB, 0xC836, 0xC82A) }
            .forEach { md.appendLine("- ${it.name} (`%04X`), ${it.count}× in ${rooms(it.rooms)}".format(it.plmId)) }
        md.appendLine()
        md.appendLine("## Area effects found")
        md.appendLine()
        catalog.fx.forEach { md.appendLine("- ${it.name} (`%02X`): ${rooms(it.rooms)}".format(it.fxType)) }
        md.appendLine()
        md.appendLine("## Tilesets")
        md.appendLine()
        md.appendLine("| Tileset | Rooms | Distinct blocks used |")
        md.appendLine("|---|---|---|")
        catalog.roomsByTileset.toSortedMap().forEach { (ts, ids) -> md.appendLine("| $ts | ${ids.size} (${rooms(ids)}) | ${catalog.tiles[ts]?.size ?: 0} |") }
        File(outPath!!).apply { parentFile?.mkdirs() }.writeText(md.toString())
        println("[REPORT] enemies ${enemyOk.size}/${catalog.enemies.size}, items ${itemOk.size}/${itemIds.size} -> $outPath")
    }
}
