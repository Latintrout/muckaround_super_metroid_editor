package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** No live projects: all source, ROM bytes, project metadata and caches are disposable fixtures. */
class AsmRoomGenerationSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge, val project: SmEditProject,
                               val editor: EditorState, val file: File)
    private suspend fun fixture(adopt: Boolean = false, invalidPreset: Boolean = false, withSave: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("; Reference bank remains intact\n")
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = (if (withSave) saveSourceTestRom() else objectSourceTestRom()).also {
            it.fill(0xFF.toByte(), 0x7F000, 0x80000)
            it.fill(0xFF.toByte(), 0x207000, 0x208000)
        }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        if (invalidPreset) rom[0x79300 + 14] = 2
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc")
        project.asmWorkspace.enabled = true
        project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(parser)
        room.plmChanges += PlmChange("add", 0xB703, 4, 4, 0xCC00)
        room.plmChanges += PlmChange("add", 0xB647, 4, 5, 0x8000)
        room.customScrollCommands["cmd_0"] = mutableListOf(ScrollCommand(0, 0), ScrollCommand(1, 2))
        room.scrollChanges += ScrollChange(0, 0, 2, 0)
        if (withSave) {
            room.plmChanges += PlmChange("add", 0xB76F, 9, 9, 0x8002)
            room.saveStationSpawns += SaveStationSpawnChange(0, 2, room.roomId, 0x8000, 0, 0, 128, 128)
        }
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap())
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        if (adopt) { bridge.migrateRoom(room.roomId, "Landing Site"); bridge.initialScrolls.migrate(room.roomId, "Landing Site") }
        val editor = EditorState().apply {
            testMode = true
            initForProject(file, project)
            asmPlmSourceBridge = bridge
            loadRoom(room.roomId, parser, parser.readRoomHeader(room.roomId)!!)
            setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
        return Fixture(workspace, bridge, project, editor, file)
    }
    private fun Fixture.generate(tile: Int = 0x8123): Boolean {
        val width = editor.workingBlocksWide / 16
        val height = editor.workingBlocksTall / 16
        val scrolls = (0 until width * height).mapNotNull { index ->
            val target = if (index / width == height - 1) 2 else 1
            val old = editor.workingScrolls[index]
            if (old == target) null else ScrollChange(index % width, index / width, old, target)
        }
        val removals = editor.workingPlms.filter { RomParser.isScrollPlm(it.id) }.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        val old = editor.readBlockWord(8, 8)
        val edits = if (old == tile) emptyList() else listOf(TileEdit(8, 8, old, tile, editor.readBts(8, 8), 0))
        return editor.applyGeneratedRoomOperation("Generate biome (fixture)", edits, scrolls, removals)
    }

    @Test fun `first generation adopts both resources and retires GUI inputs in one reciprocal undo step`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.sourceText()
        val metadata = AsmRoomBank8FMetadata.capture(room)
        val pixels = f.editor.workingLevelData!!.copyOf()
        val objects = f.editor.workingPlms.toList()
        val grid = f.editor.workingScrolls.copyOf()
        assertTrue(f.generate(), f.editor.statusMessage)
        val after = f.bridge.sourceText()
        assertEquals(1, room.asmPlmSourceVersion)
        assertEquals(1, room.asmInitialScrollSourceVersion)
        assertTrue(room.plmChanges.isEmpty() && room.scrollChanges.isEmpty() && room.customScrollCommands.isEmpty())
        assertEquals(1, f.editor.undoStack.size)
        assertEquals(1, room.levelResourceOperations.values.flatten().size)
        room.states.forEach { state ->
            assertTrue(f.bridge.managedView(room.roomId, state.id).document!!.entries.none { RomParser.isScrollPlm(it.id) })
            assertEquals(listOf(1, 1, 2, 2), f.bridge.initialScrolls.view(room.roomId, state.id).resource!!.values)
        }
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText())
        assertEquals(metadata, AsmRoomBank8FMetadata.capture(room))
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertContentEquals(grid, f.editor.workingScrolls)
        assertEquals(objects, f.editor.workingPlms)
        assertTrue(room.levelResourceOperations.isEmpty())
        assertFalse(f.workspace.canUndoSource(PLM_SOURCE_FILE), "Adoption did not leave extra partial undo steps")
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(after, f.bridge.sourceText())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(objects, f.editor.workingPlms)
        assertEquals(metadata, AsmRoomBank8FMetadata.capture(room))
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(0x8123, f.editor.readBlockWord(8, 8))
        assertEquals(listOf(1, 1, 2, 2), f.editor.workingScrolls.toList())
    }

    @Test fun `a shared target subset forks object and grid groups once and preserves an independent state`() = runBlocking {
        val f = fixture(adopt = true)
        val room = f.project.rooms.getValue("91F8")
        val original = f.editor.currentPlmStateId()!!
        val separate = f.editor.addRoomState(original, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 12), f.bridge.catalog.parser)
        f.editor.switchRoomState(separate, f.bridge.catalog.parser)
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val objectsBefore = f.bridge.managedView(room.roomId, separate).document!!.entries
        val gridBefore = f.bridge.initialScrolls.view(room.roomId, separate).resource!!
        f.editor.switchRoomState(original, f.bridge.catalog.parser)
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        assertTrue(f.generate(), f.editor.statusMessage)
        val targets = room.states.filter { it.id != separate }
        val source = AsmRoomObjectSource(f.bridge.sourceText())
        assertEquals(1, targets.map { source.table(room.roomId, it.id).resource.name }.distinct().size)
        assertEquals(1, targets.map { f.bridge.initialScrolls.view(room.roomId, it.id).resource!!.identity }.distinct().size)
        assertNotEquals(source.table(room.roomId, separate).resource.name, source.table(room.roomId, targets.first().id).resource.name)
        assertEquals(objectsBefore, f.bridge.managedView(room.roomId, separate).document!!.entries)
        assertEquals(gridBefore, f.bridge.initialScrolls.view(room.roomId, separate).resource)
        f.editor.switchRoomState(separate, f.bridge.catalog.parser)
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(objectsBefore, f.editor.workingPlms)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(objectsBefore, f.editor.workingPlms)
        assertEquals(gridBefore.values, f.editor.workingScrolls.toList())
    }

    @Test fun `invalid existing grids cannot partially adopt objects or change tiles`() = runBlocking {
        val f = fixture()
        f.bridge.initialScrolls.migrate(0x91F8, "Landing Site")
        val original = f.bridge.sourceText()
        val broken = original.replace("db \$00 ; Screen", "db \$80 ; Screen")
        assertNotEquals(original, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original, broken)
        val room = f.project.rooms.getValue("91F8")
        val metadata = AsmRoomBank8FMetadata.capture(room)
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.generate())
        assertEquals(broken, f.bridge.sourceText())
        assertEquals(metadata, AsmRoomBank8FMetadata.capture(room))
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(0, room.asmPlmSourceVersion)
        assertTrue(f.editor.undoStack.isEmpty())
    }

    @Test fun `custom initial presets reject the entire adoption before any values are published`() = runBlocking {
        val f = fixture(invalidPreset = true)
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.sourceText()
        val metadata = AsmRoomBank8FMetadata.capture(room)
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.generate())
        assertContains(f.editor.statusMessage, "Custom initial scroll presets")
        assertEquals(before, f.bridge.sourceText())
        assertEquals(metadata, AsmRoomBank8FMetadata.capture(room))
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertFalse(f.workspace.canUndoSource(PLM_SOURCE_FILE))
    }

    @Test fun `different protected objects in sharing states require an independent layout`() = runBlocking {
        val f = fixture(adopt = true)
        val room = f.project.rooms.getValue("91F8")
        val other = room.states.first { it.id != f.editor.currentPlmStateId() }.id
        f.bridge.editManaged(room.roomId, other, false, { false }, RomParser.PlmEntry(0xEEE7, 12, 12, 0x51))
        val before = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.generate())
        assertContains(f.editor.statusMessage, "Choose This state")
        assertEquals(before, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val otherGrid = f.bridge.initialScrolls.view(room.roomId, other).resource
        assertTrue(f.generate(), f.editor.statusMessage)
        assertEquals(otherGrid, f.bridge.initialScrolls.view(room.roomId, other).resource)
        assertTrue(f.bridge.managedView(room.roomId, other).document!!.entries.any { it.id == 0xEEE7 })
    }

    @Test fun `stale source and metadata plans fail before callbacks or source writes`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val plan = f.bridge.planGeneration(room.roomId, "Landing Site", room.states.map { it.id }.toSet(), RomParser::isItemPlm)
        val before = f.bridge.sourceText()
        room.scrollChanges += ScrollChange(1, 0, 2, 0)
        var callback = false
        assertFails { f.bridge.applyGeneration(plan, { callback = true }, { callback = true }) }
        assertFalse(callback)
        assertEquals(before, f.bridge.sourceText())
        room.scrollChanges.removeAt(room.scrollChanges.lastIndex)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before + "; newer source\n")
        assertFails { f.bridge.applyGeneration(plan, { callback = true }, { callback = true }) }
        assertFalse(callback)
        assertEquals(before + "; newer source\n", f.bridge.sourceText())
        assertEquals(0, room.asmPlmSourceVersion)
        assertEquals(0, room.asmInitialScrollSourceVersion)
    }

    @Test fun `newer layout or metadata changes cannot be overwritten by source undo`() = runBlocking {
        val f = fixture()
        assertTrue(f.generate(), f.editor.statusMessage)
        val after = f.bridge.sourceText()
        val room = f.project.rooms.getValue("91F8")
        f.editor.setTileProperties(8, 8, 0, 0)
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(after, f.bridge.sourceText())
        assertEquals(0x0123, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.undo())
        room.scrollChanges += ScrollChange(0, 0, 1, 0) // Simulate an incompatible newer metadata edit.
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(after, f.bridge.sourceText())
        assertEquals(0x8123, f.editor.readBlockWord(8, 8))
        room.scrollChanges.clear()
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertEquals(0, room.asmPlmSourceVersion)
    }

    @Test fun `saved generation reopens two way and compiled data matches all state projections`() = runBlocking {
        val f = fixture()
        assertTrue(f.generate(), f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val json = Json { encodeDefaults = true }
        val reloaded = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), f.project))
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { reloaded }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, reloaded); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        }
        assertEquals(f.editor.workingPlms, editor.workingPlms)
        assertContentEquals(f.editor.workingLevelData, editor.workingLevelData)
        assertEquals(listOf(1, 1, 2, 2), editor.workingScrolls.toList())
        val before = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db \$01 ; Screen (1,1)", "db \$00 ; Screen (1,1)"))
        editor.refreshLinkedPlms()
        assertEquals(0, editor.workingScrolls[0])
        editor.setScroll(0, 0, 1, 2)
        assertContains(bridge.sourceText(), "db \$01 ; Screen (1,1)")
        val room = reloaded.rooms.getValue("91F8")
        val text = bridge.sourceText()
        val objects = mapOf(room.roomId to AsmRoomObjectSource(text).resources(room, 4))
        val grids = mapOf(room.roomId to AsmInitialScrollSource(text).resources(room, 2, 2))
        val target = bridge.catalog.parser.copyRomData()
        ProjectRoomExporter(reloaded, bridge.catalog.parser, target, sourcePlmSets = objects, sourceInitialScrolls = grids).exportRooms()
        val built = RomParser(target)
        val states = built.parseRoomStatesWithData(room.roomId)
        room.states.forEachIndexed { index, state ->
            assertEquals(objects.getValue(room.roomId).getValue(state.id).entries, built.parsePlmSet(states[index].plmSetPtr))
            assertEquals(grids.getValue(room.roomId).getValue(state.id).values, built.parseScrollData(states[index].scrollPtr, 2, 2).toList())
            assertContentEquals(editor.workingLevelData, built.decompressLZ2(states[index].levelDataPtr))
        }
        assertContentEquals(bridge.catalog.parser.copyRomData(), f.bridge.catalog.parser.copyRomData(), "The input ROM remains untouched")
    }

    @Test fun `repeated generation with unchanged source retains a normal tile-only undo boundary`() = runBlocking {
        val f = fixture()
        assertTrue(f.generate(), f.editor.statusMessage)
        val after = f.bridge.sourceText()
        val head = f.workspace.sourceUndoHead(PLM_SOURCE_FILE)
        assertTrue(f.generate(0x8124), f.editor.statusMessage)
        assertSame(head, f.workspace.sourceUndoHead(PLM_SOURCE_FILE))
        assertEquals(after, f.bridge.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0x8123, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.editor.readBlockWord(8, 8))
    }

    @Test fun `ROM-only generation keeps delta inputs and reciprocal undo without adopting ASM`() = runBlocking {
        val f = fixture()
        f.project.asmWorkspace.enabled = false
        f.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        f.editor.asmPlmSourceBridge = null
        val before = f.bridge.sourceText()
        assertTrue(f.generate(), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        assertEquals(0, room.asmPlmSourceVersion)
        assertEquals(0, room.asmInitialScrollSourceVersion)
        assertEquals(before, f.bridge.sourceText())
        assertTrue(f.editor.workingPlms.none { RomParser.isScrollPlm(it.id) })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertTrue(f.editor.workingPlms.any { it.id == 0xB703 })
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(0x8123, f.editor.readBlockWord(8, 8))
    }

    @Test fun `generation adopts save overrides without losing their spawn or collection metadata`() = runBlocking {
        val f = fixture(withSave = true)
        val room = f.project.rooms.getValue("91F8")
        val original = room.saveStationSpawns.toList()
        assertTrue(f.generate(), f.editor.statusMessage)
        assertTrue(room.saveStationSpawns.isEmpty())
        assertEquals(listOf(room.roomId, 0x8000, 7, 0, 0, 128, 128), f.bridge.saveSpawn(room.roomId, 0, 2)!!.words)
        assertTrue(f.editor.workingPlms.any { it.id == 0xB76F && it.param == 0x8002 })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(original, room.saveStationSpawns)
        assertTrue(f.editor.workingPlms.any { it.id == 0xB76F && it.param == 0x8002 })
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(7, f.bridge.saveSpawn(room.roomId, 0, 2)!!.words[2])
    }

    @Test fun `the ordinary procedural generator reaches the source transaction without extra UI actions`() = runBlocking {
        val f = fixture()
        val changed = f.editor.generateBiome(
            com.supermetroid.editor.procgen.BiomeRules.roll(com.supermetroid.editor.procgen.BiomeStyle.PIPE_MAZE, 1234L),
            com.supermetroid.editor.procgen.TilesetProfile.synthetic(), 1234L, romParser = f.bridge.catalog.parser)
        assertTrue(changed > 0, f.editor.statusMessage)
        assertEquals(1, f.project.rooms.getValue("91F8").asmInitialScrollSourceVersion)
        assertTrue(f.editor.workingPlms.none { RomParser.isScrollPlm(it.id) })
        assertEquals(1, f.editor.undoStack.size)
        val reset = f.editor.resetGeneratedBiomeRooms(f.bridge.catalog.parser)
        assertEquals(1, reset.skippedRooms, "Bulk reset must not separate generated geometry from source-owned camera data")
        assertEquals(0, reset.generatedRooms)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
    }

    @Test fun `unchanged selected tiles still reconcile other sharing states camera data`() = runBlocking {
        val f = fixture(adopt = true)
        assertTrue(f.generate(), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        val other = room.states.first { it.id != f.editor.currentPlmStateId() }.id
        f.bridge.editScroll(room.roomId, other, 4, 4, listOf(ScrollCommand(0, 0)))
        f.bridge.initialScrolls.edit(room.roomId, other, 0, 0)
        f.editor.refreshLinkedPlms()
        assertTrue(f.editor.workingPlms.none { RomParser.isScrollPlm(it.id) })
        assertEquals(listOf(1, 1, 2, 2), f.editor.workingScrolls.toList())
        assertTrue(f.generate(), f.editor.statusMessage)
        room.states.forEach { state ->
            assertTrue(f.bridge.managedView(room.roomId, state.id).document!!.entries.none { RomParser.isScrollPlm(it.id) })
            assertEquals(listOf(1, 1, 2, 2), f.bridge.initialScrolls.view(room.roomId, state.id).resource!!.values)
        }
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertTrue(f.bridge.managedView(room.roomId, other).document!!.entries.any { it.id == 0xB703 })
        assertEquals(0, f.bridge.initialScrolls.view(room.roomId, other).resource!!.values[0])
    }

    @Test fun `new custom references outside the room bank invalidate an older generation plan`() = runBlocking {
        val f = fixture(adopt = true)
        val room = f.project.rooms.getValue("91F8")
        val plan = f.bridge.planGeneration(room.roomId, "Landing Site", room.states.map { it.id }.toSet(), RomParser::isItemPlm)
        val main = f.workspace.projectSourceText("main.asm")
        f.workspace.replaceProjectSource("main.asm", main, main + "; new custom reference\n")
        var callback = false
        assertFails { f.bridge.applyGeneration(plan, { callback = true }, { callback = true }) }
        assertFalse(callback)
        assertEquals(plan.before, f.bridge.sourceText())
        assertEquals(plan.metadataBefore, AsmRoomBank8FMetadata.capture(room))
    }
}
