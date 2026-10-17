package com.supermetroid.editor.maker

import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.SessionConfig
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.maker.MakerFx
import kotlinx.serialization.json.jsonObject
import com.supermetroid.editor.ui.ProjectLifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Opt-in (V1_ROM, CATALOG_OUT, SMEDIT_LIBRETRO_CORE, VERIFY_CATALOG=1): for every swept enemy species and item
 * variant, place it in Landing Site, export, boot headless with no save, run ~10 s, and check it spawned and
 * the game kept running. Writes verified.json (the library only shows parts that pass) and verify-log.txt.
 */
class MakerCatalogVerifyTest {
    private fun log(s: String) = println("[VERIFY] $s")

    private class Booted(val b: LibretroBackend) {
        suspend fun r16(a: Int) = b.readMemory(a, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
    }

    /** Boot [rom] into Landing Site, run [frames] more frames, and return (bootedOk, stillRunning, enemyIds, plmIds). */
    private fun playCheck(rom: File, work: File, frames: Int = 600): Triple<String?, List<Int>, List<Int>> = runBlocking {
        val b = LibretroBackend(audioEnabledOverride = false, stateDirOverride = File(work, "emu").apply { deleteRecursively(); mkdirs() })
        b.connect()
        try {
            b.startSession(SessionConfig(romPath = rom.absolutePath))
            val g = Booted(b)
            val parser = RomParser.loadRom(rom.absolutePath)
            val plan = requireNotNull(MakerPlayPlan.forRoom(parser, 0x91F8))
            val launcher = MakerPlayLauncher(
                read16 = { g.r16(it) },
                write16 = { a, v -> b.writeMemory(a, byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) },
                waitFrames = { b.step(EmulatorInput(repeat = it, includeFrame = false)) },
                writeBlock = { a, bytes -> b.writeMemory(a, bytes) },
            )
            val r = launcher.launch(plan, MakerPlayLoadout(0x1105, 0x1000, 4, 30))
            if (!r.ok) return@runBlocking Triple("boot: ${r.message}", emptyList(), emptyList())
            val enemyIds = (0 until 32).map { g.r16(0x0F78 + it * 0x40) }.filter { it != 0 && it != 0xFFFF }
            val plmIds = (0 until 40).map { g.r16(0x1C37 + it * 2) }.filter { it != 0 }
            // Run ~10 s in slices; the global frame counter must keep moving and the game must stay in play.
            var lastFrame = g.r16(0x05B6)
            var problem: String? = null
            repeat(frames / 60) {
                b.step(EmulatorInput(repeat = 60, includeFrame = false))
                val f = g.r16(0x05B6)
                val state = g.r16(0x0998)
                if (f == lastFrame) problem = "game froze (frame counter stopped)"
                if (state !in setOf(0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A)) problem = "left gameplay (state 0x${state.toString(16)})"
                lastFrame = f
                if (problem != null) return@repeat
            }
            b.closeSession()
            Triple(problem, enemyIds, plmIds)
        } catch (e: Throwable) {
            Triple("emulator error: ${e.message}", emptyList(), emptyList())
        } finally {
            runCatching { b.disconnect() }
        }
    }

    /**
     * Opt-in (V1_ROM, CATALOG_OUT, SMEDIT_LIBRETRO_CORE, VERIFY_ROOMLOOK=1): every area effect and every tileset the
     * Room look panel offers, applied to Landing Site, exported and booted. Adds "fx" and "tilesets" to verified.json.
     */
    @Test
    fun `verify room looks in game`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile && System.getenv("VERIFY_ROOMLOOK") != null, "opt-in")
        val outDir = File(requireNotNull(System.getenv("CATALOG_OUT"))).apply { mkdirs() }
        val work = Files.createTempDirectory("roomlook-verify").toFile()
        val catalog = MakerCatalog.build(RomParser.loadRom(rom.absolutePath))
        val lines = mutableListOf<String>()
        fun check(label: String, theme: BiomeTheme, ramCheck: (suspend (suspend (Int) -> Int) -> String?)? = null): Pair<Boolean, String> {
            val open = ProjectLifecycle.create(label, work, rom)
            val editor = EditorState().apply { testMode = true }
            editor.initForProject(open.file, open.project)
            val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
            editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
            if (!editor.applyBiomeTheme(theme, parser)) return false to "SMEDIT refused to apply it"
            val exported = runCatching { editor.exportToRom(parser) }.getOrNull() ?: return false to "export refused: ${editor.statusMessage}"
            val (problem, _, _) = playCheck(File(exported), work)
            return if (problem != null) false to problem else true to "booted; ran 10 s"
        }
        val fx = MakerFx.entries.filter { it != MakerFx.NONE }.associate { f ->
            f.fxType to check("FX-${f.name}", BiomeTheme("v-${f.name}", f.label, "", tilesetId = null, fxType = f.fxType, liquidFraction = 0.6))
                .also { "%s fx %02X %-18s %s".format(if (it.first) "PASS" else "FAIL", f.fxType, f.label, it.second).also { s -> log(s); lines += s } }
        }
        val tilesets = catalog.roomsByTileset.keys.sorted().associateWith { ts ->
            check("TS-$ts", BiomeTheme("v-ts-$ts", "Tileset $ts", "", tilesetId = ts))
                .also { "%s tileset %2d %s".format(if (it.first) "PASS" else "FAIL", ts, it.second).also { s -> log(s); lines += s } }
        }
        val file = File(outDir, "verified.json")
        val existing = file.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject } ?: JsonObject(emptyMap())
        val json = buildJsonObject {
            existing.filterKeys { it != "fx" && it != "tilesets" }.forEach { (k, v) -> put(k, v) }
            putJsonObject("fx") { fx.forEach { (id, v) -> putJsonObject("%02X".format(id)) { put("ok", v.first); put("reason", v.second) } } }
            putJsonObject("tilesets") { tilesets.forEach { (id, v) -> putJsonObject("%02X".format(id)) { put("ok", v.first); put("reason", v.second) } } }
        }
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        File(outDir, "verify-roomlook-log.txt").writeText(lines.joinToString("\n"))
        log("fx pass=${fx.count { it.value.first }}/${fx.size}; tilesets pass=${tilesets.count { it.value.first }}/${tilesets.size}")
    }

    @Test
    fun `verify catalog parts in game`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile && System.getenv("VERIFY_CATALOG") != null, "opt-in")
        val outDir = File(requireNotNull(System.getenv("CATALOG_OUT"))).apply { mkdirs() }
        val work = Files.createTempDirectory("catalog-verify").toFile()
        val parser0 = RomParser.loadRom(rom.absolutePath)
        val catalog = MakerCatalog.build(parser0)
        val only = System.getenv("VERIFY_ONLY")?.split(",")?.map { it.trim().removePrefix("0x").toInt(16) }?.toSet()
        val logLines = mutableListOf<String>()
        val enemyResults = linkedMapOf<Int, Pair<Boolean, String>>()
        val itemResults = linkedMapOf<Int, Pair<Boolean, String>>()

        fun freshEditor(name: String): Pair<EditorState, RomParser> {
            val open = ProjectLifecycle.create(name, work, rom)
            val editor = EditorState().apply { testMode = true }
            editor.initForProject(open.file, open.project)
            val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
            editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
            return editor to parser
        }

        // ── Enemies ──
        for (e in catalog.enemies) {
            if (only != null && e.speciesId !in only) continue
            val excluded = MakerCatalogRules.excludedEnemyReason(e.speciesId, e.name)
            if (excluded != null) { enemyResults[e.speciesId] = false to excluded; continue }
            val (editor, parser) = freshEditor("E%04X".format(e.speciesId))
            editor.addEnemy(e.speciesId, 70 * 16 + 8, 60 * 16 + 8, e.initParam, e.properties)
            val exported = runCatching { editor.exportToRom(parser) }.getOrNull()
            val verdict = if (exported == null) {
                false to "export refused: ${editor.statusMessage.ifBlank { "unknown" }}"
            } else {
                val (problem, enemies, _) = playCheck(File(exported), work)
                when {
                    problem != null -> false to problem
                    e.speciesId !in enemies -> false to "did not spawn in game"
                    else -> true to "spawned; ran 10 s"
                }
            }
            enemyResults[e.speciesId] = verdict
            "%s enemy 0x%04X %-30s %s".format(if (verdict.first) "PASS" else "FAIL", e.speciesId, e.name, verdict.second).also { log(it); logLines += it }
        }

        // ── Items: every variant, one export per variant kind (visible / chozo / hidden). ──
        if (only == null) for ((kind, pick) in listOf<Pair<String, (RomParser.Companion.ItemDef) -> Int>>(
            "visible" to { it.visibleId }, "chozo" to { it.chozoId }, "hidden" to { it.hiddenId })) {
            val (editor, parser) = freshEditor("Items-$kind")
            val defs = RomParser.ITEM_DEFS
            defs.forEachIndexed { i, def -> editor.addPlm(pick(def), 40 + i * 2, 58, 0, ItemStateScope.ALL_STATES) }
            val exported = runCatching { editor.exportToRom(parser) }.getOrNull()
            if (exported == null) {
                defs.forEach { itemResults[pick(it)] = false to "export refused: ${editor.statusMessage}" }
                continue
            }
            val (problem, _, plms) = playCheck(File(exported), work)
            defs.forEach { def ->
                val id = pick(def)
                val verdict = when {
                    problem != null -> false to problem
                    id !in plms -> false to "did not appear in the PLM table"
                    else -> true to "spawned; ran 10 s"
                }
                itemResults[id] = verdict
                "%s item %-6s 0x%04X %-16s %s".format(if (verdict.first) "PASS" else "FAIL", kind, id, def.name, verdict.second).also { log(it); logLines += it }
            }
        }

        val json = buildJsonObject {
            put("rom", "Super Metroid (JU) da957f0d63d14cb441d215462904c4fa8519c613")
            put("testRoom", "Landing Site (0x91F8)")
            putJsonObject("enemies") { enemyResults.forEach { (id, v) -> putJsonObject("%04X".format(id)) { put("ok", v.first); put("reason", v.second) } } }
            putJsonObject("items") { itemResults.forEach { (id, v) -> putJsonObject("%04X".format(id)) { put("ok", v.first); put("reason", v.second) } } }
        }
        File(outDir, if (only == null) "verified.json" else "verified-partial.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        File(outDir, "verify-log.txt").writeText(logLines.joinToString("\n"))
        log("enemies pass=${enemyResults.count { it.value.first }} fail=${enemyResults.count { !it.value.first }}; items pass=${itemResults.count { it.value.first }} fail=${itemResults.count { !it.value.first }}")
    }
}
