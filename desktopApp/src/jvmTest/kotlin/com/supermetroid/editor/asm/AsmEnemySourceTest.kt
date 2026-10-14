package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.RomExporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmEnemySourceTest {
    @TempDir lateinit var directory: File
    private val actor = RomParser.EnemyEntry(0xD13F, 40, 56, 0x1234, 0x2800, 0x5678, 0x9ABC, 0xDEF0)
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge, val project: SmEditProject, val path: File)
    private suspend fun fixture(): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_A1.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("; objects\n")
        File(src, FX_SOURCE_FILE).writeText("; effects\n")
        File(src, ENEMY_SOURCE_FILE).writeText("; immutable original population context\r\n")
        File(src, "macros.asm").writeText("macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val path = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(path.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(path.path, true, progressive = true)
        val rom = objectSourceTestRom()
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        rom.fill(-1, 0x10F000, 0x110000); rom.fill(-1, 0x1A7000, 0x1A8000); rom.fill(-1, 0x7F000, 0x80000)
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            word(pc + 8, 0x8800); word(pc + 10, 0x8800)
        }
        SourceEnemyPopulation("original", listOf(actor, actor.copy(x = 80)), 7).bytes().copyInto(rom, 0x108800)
        SourceEnemyGraphics("original", listOf(RomParser.EnemyGfxEntry(actor.id, 1))).bytes().copyInto(rom, 0x1A0800)
        for (id in listOf(actor.id, 0xD17F, 0xD1BF, 0xD1FF, 0xD23F, 0xD73F)) {
            val pc = 0x100000 + (id and 0x7FFF)
            word(pc, if (id == 0xD73F) 0 else 32); word(pc + 4, 100)
            rom[pc + 12] = 0xA2.toByte(); word(pc + 54, id); rom[pc + 56] = 0xAD.toByte()
        }
        val parser = RomParser(rom)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), File(src, "macros.asm").readText(), emptyMap(), parser, emptyMap())
        return Fixture(workspace, AsmPlmSourceBridge(catalog, workspace) { project }, project, path)
    }
    private fun Fixture.editor() = EditorState().apply {
        testMode = true; initForProject(path, this@editor.project)
        asmPlmSourceBridge = bridge
        loadRoom(0x91F8, bridge.catalog.parser, requireNotNull(bridge.catalog.parser.readRoomHeader(0x91F8)))
    }

    @Test fun `adoption preserves eight words order quota and independent shared resources with metadata undo`() = runBlocking {
        val f = fixture()
        val room = f.project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(f.bridge.catalog.parser)
        room.enemyChanges += EnemyChange("add", actor.id, 12, 16)
        room.states.last().enemyChanges += EnemyChange("remove", actor.id, 12, 16, origX = 12, origY = 16)
        val before = f.bridge.enemies.sourceText()
        f.bridge.enemies.migrate(room.roomId, "Test")
        val sources = AsmEnemySource(f.bridge.enemies.sourceText()).resources(room)
        assertEquals(3, sources.getValue(room.states.first().id).population.entries.size)
        assertEquals(2, sources.getValue(room.states.last().id).population.entries.size)
        sources.values.forEach { assertEquals(actor, it.population.entries.first()); assertEquals(7, it.population.killCount) }
        assertEquals(1, sources.values.map { it.graphics.identity }.distinct().size)
        assertTrue(room.enemyChanges.isEmpty() && room.states.all { it.enemyChanges.isEmpty() })
        assertTrue(f.workspace.undoSourceEdit(ENEMY_SOURCE_FILE))
        assertEquals(0, room.asmEnemySourceVersion); assertEquals(before, f.bridge.enemies.sourceText())
        assertEquals(1, room.enemyChanges.size); assertEquals(1, room.states.last().enemyChanges.size)
        assertTrue(f.workspace.redoSourceEdit(ENEMY_SOURCE_FILE)); assertEquals(1, room.asmEnemySourceVersion)
    }

    @Test fun `GUI update and raw source edits are reciprocal state local and undoable`() = runBlocking {
        val f = fixture(); val editor = f.editor()
        val changed = actor.copy(x = 64, extra2 = 0x1111)
        editor.updateEnemy(actor, changed)
        assertEquals(changed, editor.workingEnemies.first())
        val room = f.project.rooms.getValue("91F8")
        val state = editor.currentPlmStateId()!!
        val other = room.states.single { it.id != state }.id
        assertEquals(actor, f.bridge.enemies.view(room.roomId, other).resource!!.population.entries.first())
        assertTrue(editor.undo()); assertEquals(actor, editor.workingEnemies.first())
        assertTrue(editor.redo()); assertEquals(changed, editor.workingEnemies.first())
        val before = f.bridge.enemies.sourceText()
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, before, before.replace("\$0040, \$0038", "\$0044, \$0038"))
        editor.refreshLinkedPlms(); assertEquals(68, editor.workingEnemies.first().x)
        assertEquals(actor, f.bridge.enemies.view(room.roomId, other).resource!!.population.entries.first())
        assertTrue(f.workspace.saveAllSources())
        val json = Json { encodeDefaults = true }
        val saved = json.encodeToString(SmEditProject.serializer(), f.project)
        assertFalse(saved.contains("SmeditEnemies_"), "JSON stores ownership, not duplicate population values")
        val reopened = json.decodeFromString(SmEditProject.serializer(), saved)
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.path.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { reopened }
        assertEquals(68, bridge.enemies.view(room.roomId, state).resource!!.population.entries.first().x)
    }

    @Test fun `add pairs GFX atomically remove preserves transfer owners and fifth GFX fails without source mutation`() = runBlocking {
        val f = fixture(); val editor = f.editor()
        for (id in listOf(0xD17F, 0xD1BF, 0xD1FF)) editor.addEnemy(id, 120, 140)
        assertNull(editor.enemyPreviewProblem)
        assertEquals(4, editor.workingEnemyGraphics.size)
        val before = f.bridge.enemies.sourceText()
        editor.addEnemy(0xD23F, 150, 140)
        assertEquals(before, f.bridge.enemies.sourceText())
        assertContains(editor.statusMessage, "fifth GFX")
        val count = editor.workingEnemies.size
        editor.addEnemy(0xD73F, 160, 140)
        assertEquals(count + 1, editor.workingEnemies.size, "Zero-transfer shared actors need no fifth GFX entry")
        editor.removeEnemy(editor.workingEnemies.last())
        assertEquals(count, editor.workingEnemies.size)
        assertEquals(4, editor.workingEnemyGraphics.size, "Do not prune transfer-only or shared owners")
    }

    @Test fun `invalid draft retains last valid preview stays saveable and blocks stale GUI overwrite`() = runBlocking {
        val f = fixture(); val editor = f.editor()
        assertTrue(editor.ensureEnemySource("Test"))
        val before = f.bridge.enemies.sourceText()
        val invalid = before.replace("db \$07 ; Required kills", "db \$100 ; Required kills")
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, before, invalid)
        editor.refreshLinkedPlms()
        assertNotNull(editor.enemyPreviewProblem); assertEquals(actor, editor.workingEnemies.first())
        editor.updateEnemy(actor, actor.copy(x = 60))
        assertEquals(invalid, f.bridge.enemies.sourceText())
        assertTrue(f.workspace.saveAllSources()); assertEquals(invalid, f.bridge.enemies.sourceText())
    }

    @Test fun `typed placement emits both banks complete pointers and protects native data edits`() = runBlocking {
        val f = fixture(); val editor = f.editor(); editor.addEnemy(0xD17F, 64, 72)
        val room = f.project.rooms.getValue("91F8")
        val inputs = mapOf(room.roomId to AsmEnemySource(f.bridge.enemies.sourceText()).resources(room))
        val parser = f.bridge.catalog.parser
        val planned = requireNotNull(RomExporter(f.project, parser, {}, {}, sourceEnemies = inputs).build())
        val materialized = AsmSourceRoomMaterializer().materialize(f.project, planned.writeReport, planned.resultRom, sourceEnemies = inputs)
        assertEquals(4, materialized.enemyTables.size)
        assertEquals(4, materialized.enemyTables.sumOf { it.pointerOffsets.size })
        assertContains(materialized.source, "%${inputs.getValue(room.roomId).getValue(editor.currentPlmStateId()!!).population.identity}()")
        for (table in materialized.enemyTables) {
            assertEquals(table.bank, pcToSnesLoRom(table.pcOffset)!! ushr 16)
            table.claims().forEach { assertContentEquals(it.bytes, planned.resultRom.copyOfRange(it.pcOffset, it.endExclusive)) }
        }
        protectEnemyBaseline(f.project, parser, parser)
        val corrupt = parser.copyRomData().also { it[0x108800 + 2]++ }
        assertFailsWith<IllegalArgumentException> { protectEnemyBaseline(f.project, parser, RomParser(corrupt)) }
        assertNull(RomExporter(f.project, parser, {}, {}).build(), "ROM-only export cannot ignore source ownership")
    }

    @Test fun `strict readers reject missing stops and malformed records before adoption`() {
        val rom = ByteArray(0x300000)
        assertFails { readSourceEnemyPopulation(RomParser(rom), 0xFFF8, "truncated") }
        assertFails { readSourceEnemyGraphics(RomParser(rom), 0xFFFE, "truncated") }
        assertFails { SourceEnemyPopulation("bad", listOf(actor.copy(extra3 = 0x10000)), 0) }
        assertFails { SourceEnemyPopulation("too many", List(33) { actor }, 0) }
        assertEquals(32 * 16 + 3, SourceEnemyPopulation("full", List(32) { actor }, 0).bytes().size)
        assertFails { SourceEnemyGraphics("bad", List(5) { RomParser.EnemyGfxEntry(actor.id, 1) }) }
        assertFails { SourceEnemyGraphics("bad", listOf(RomParser.EnemyGfxEntry(actor.id, 8))) }
    }

    @Test fun `native GFX tables satisfy the checked engine transfer contract`() {
        val parser = TestRomHelper.requireRomParser()
        val pointers = parser.roomCatalog.rooms.flatMap { info -> parser.parseRoomStatesWithData(info.getRoomIdAsInt()).map { it.enemyGfxPtr } }.distinct()
        for (pointer in pointers) {
            val graphics = readSourceEnemyGraphics(parser, pointer, "GFX ${pointer.toString(16)}")
            validateEnemyGraphicsTransfers(parser, graphics)
        }
        val populations = parser.roomCatalog.rooms.flatMap { info -> parser.parseRoomStatesWithData(info.getRoomIdAsInt()).map { it.enemySetPtr } }.distinct()
        populations.forEach { readSourceEnemyPopulation(parser, it, "Population ${it.toString(16)}") }
    }

    @Test fun `shared raw edits affect every alias while GUI detach retains notes literals and original text`() = runBlocking {
        val f = fixture(); f.bridge.enemies.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.enemies.sourceText()
        val styled = before.replace("\$0028, \$0038", "40, \$0038")
            .replace(Regex("(; Actor 0:[^\\r\\n]*)"), "${'$'}1 | my first actor")
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, before, styled)
        val edit = styled.replace("40, \$0038", "44, \$0038")
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, styled, edit)
        room.states.forEach { assertEquals(44, f.bridge.enemies.view(room.roomId, it.id).resource!!.population.entries.first().x) }
        val state = room.states.last().id
        f.bridge.enemies.edit(room.roomId, state) { entries -> entries.toMutableList().also { it[0] = it[0].copy(extra2 = 3) } }
        val result = f.bridge.enemies.sourceText()
        assertContains(result, "dw \$D13F, 44, \$0038", message = "Unchanged authored literal spelling must survive GUI edits")
        assertTrue(result.startsWith("; immutable original population context\r\n"))
        assertContains(result, "my first actor")
        assertEquals(0x9ABC, f.bridge.enemies.view(room.roomId, room.states.first().id).resource!!.population.entries.first().extra2)
        assertEquals(3, f.bridge.enemies.view(room.roomId, state).resource!!.population.entries.first().extra2)
    }

    @Test fun `raw missing GFX and zero or overflowing transfers fail instead of inferring pixel aliases`() = runBlocking {
        val f = fixture(); f.bridge.enemies.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val state = room.states.last().id
        assertNull(f.bridge.enemies.view(room.roomId, state).problem)
        val before = f.bridge.enemies.sourceText()
        val missing = before.lineSequence().filterNot { it.startsWith("    dw \$D13F, \$0001 ; GFX:") }.joinToString("\n")
        assertNotEquals(before, missing)
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, before, missing)
        assertNotNull(f.bridge.enemies.view(room.roomId, state).problem)
        val parser = f.bridge.catalog.parser
        assertFails { validateEnemyGraphicsTransfers(parser, SourceEnemyGraphics("zero", listOf(RomParser.EnemyGfxEntry(0xD73F, 1)))) }
        val rom = parser.copyRomData()
        val oldPc = parser.snesToPc(0xA0D13F); val newPc = parser.snesToPc(0xA0D17F)
        rom.copyInto(rom, newPc + 54, oldPc + 54, oldPc + 57)
        val baseline = SourceEnemyGraphics("gfx", listOf(RomParser.EnemyGfxEntry(actor.id, 1)))
        val aliased = completeEnemyGraphics(RomParser(rom), listOf(actor, actor.copy(id = 0xD17F)), listOf(actor), baseline, baseline)
        assertEquals(listOf(actor.id, 0xD17F), aliased.entries.map { it.speciesId }, "Runtime species lookup does not resolve GRAPHADR aliases")
        rom[oldPc] = 8; rom[oldPc + 1] = 0x20
        assertFails { validateEnemyGraphicsTransfers(RomParser(rom), baseline) }
    }

    @Test fun `new states inherit paired tables and source cleanup removes unreferenced wrappers`() = runBlocking {
        val f = fixture(); val editor = f.editor(); assertTrue(editor.ensureEnemySource("Test"))
        val room = f.project.rooms.getValue("91F8")
        val template = editor.currentPlmStateId()!!
        val added = editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), f.bridge.catalog.parser)
        assertEquals(f.bridge.enemies.view(room.roomId, template).resource, f.bridge.enemies.view(room.roomId, added).resource)
        editor.switchRoomState(added, f.bridge.catalog.parser)
        editor.updateEnemy(actor, actor.copy(x = 70))
        assertEquals(actor, f.bridge.enemies.view(room.roomId, template).resource!!.population.entries.first())
        editor.deleteRoomState(added, f.bridge.catalog.parser)
        assertTrue(f.workspace.saveAllSources())
        assertFalse(AsmEnemySource(f.bridge.enemies.sourceText()).hasState(room.roomId, added))
        assertEquals(1, Regex("(?m)^macro SmeditEnemies_91F8_Population_").findAll(f.bridge.enemies.sourceText()).count())
    }

    @Test fun `headered allocation claims use body offsets and missing or competing inputs fail closed`() = runBlocking {
        val f = fixture(); f.bridge.enemies.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val inputs = mapOf(room.roomId to AsmEnemySource(f.bridge.enemies.sourceText()).resources(room))
        val headered = ByteArray(512) + f.bridge.catalog.parser.copyRomData()
        val file = File(directory, "headered.smc").apply { writeBytes(headered) }
        val project = f.project.copy(romPath = file.path)
        val result = requireNotNull(RomExporter(project, RomParser(headered), {}, {}, sourceEnemies = inputs).build())
        val materialization = AsmSourceRoomMaterializer().materialize(project, result.writeReport, result.resultRom, sourceEnemies = inputs)
        materialization.enemyTables.forEach { table -> table.claims().forEach { claim ->
            assertContentEquals(claim.bytes, result.resultRom.copyOfRange(512 + claim.pcOffset, 512 + claim.endExclusive))
        } }
        val data = headered.copyOf()
        assertFails { ProjectRoomExporter(project, RomParser(headered), data).exportRooms() }
        assertContentEquals(headered, data)
        room.enemyChanges += EnemyChange("add", actor.id, 100, 120)
        assertFails { AsmEnemySource(f.bridge.enemies.sourceText()).resources(room) }
    }

    @Test fun `custom record macros block ownership without discarding a draft or changing GUI values`() = runBlocking {
        val f = fixture(); val editor = f.editor(); assertTrue(editor.ensureEnemySource("Test"))
        val original = f.workspace.projectSourceText("macros.asm")
        val changed = original + "\nmacro EnemyHeader(Value)\n    db <Value>\nendmacro\n"
        f.workspace.replaceProjectSource("macros.asm", original, changed)
        editor.refreshLinkedPlms()
        assertContains(editor.enemyPreviewProblem.orEmpty(), "custom record layout")
        val before = f.bridge.enemies.sourceText()
        editor.updateEnemy(actor, actor.copy(x = 90))
        assertEquals(before, f.bridge.enemies.sourceText())
        assertEquals(actor, editor.workingEnemies.first())
        assertTrue(f.workspace.saveAllSources())
        assertEquals(changed, f.workspace.projectSourceText("macros.asm"))
    }

    @Test fun `ROM only editing retains its delta exporter and cannot overwrite a malformed source owned project`() = runBlocking {
        val f = fixture()
        f.project.asmWorkspace.enabled = false; f.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        val editor = f.editor()
        val before = f.bridge.enemies.sourceText()
        val changed = actor.copy(x = 72, extra1 = 0x0101)
        editor.updateEnemy(actor, changed)
        assertEquals(before, f.bridge.enemies.sourceText())
        assertFalse(editor.hasManagedEnemies())
        val result = requireNotNull(RomExporter(f.project, f.bridge.catalog.parser, {}, {}).build())
        val built = RomParser(result.resultRom)
        val room = built.readRoomHeader(0x91F8)!!
        assertEquals(changed, built.parseEnemyPopulation(room.enemySetPtr).first())
        f.project.asmWorkspace.enabled = true; f.project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        assertTrue(editor.ensureEnemySource("Test"))
        val source = f.bridge.enemies.sourceText()
        f.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM // Malformed external metadata, not a permitted mode switch.
        editor.addEnemy(actor.id, 10, 10)
        assertEquals(source, f.bridge.enemies.sourceText())
        assertTrue(f.project.rooms.getValue("91F8").enemyChanges.isEmpty())
        assertContains(editor.statusMessage, "requires ASM mode")
    }

    @Test fun `identical overlapping actors retain slot identity and revision projections are reused`() = runBlocking {
        val f = fixture(); val editor = f.editor(); assertTrue(editor.ensureEnemySource("Test"))
        editor.addEnemy(actor.id, actor.x, actor.y, actor.initParam, actor.properties)
        editor.updateEnemy(editor.workingEnemies.last(), actor)
        assertEquals(actor, editor.workingEnemies.first())
        assertEquals(actor, editor.workingEnemies.last())
        editor.updateEnemy(editor.workingEnemies.last(), actor.copy(extra3 = 3))
        assertEquals(actor, editor.workingEnemies.first())
        assertEquals(3, editor.workingEnemies.last().extra3)
        editor.updateEnemy(editor.workingEnemies.last(), actor)
        editor.removeEnemy(editor.workingEnemies.last())
        assertEquals(listOf(actor, actor.copy(x = 80)), editor.workingEnemies)
        val room = f.project.rooms.getValue("91F8")
        val source = AsmEnemySource(f.bridge.enemies.sourceText())
        val state = editor.currentPlmStateId()!!
        assertSame(source.resources(room).getValue(state), source.resources(room).getValue(state))
    }
}
