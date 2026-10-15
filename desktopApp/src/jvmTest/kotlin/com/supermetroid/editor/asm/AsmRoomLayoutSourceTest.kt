package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.ui.TileBrush
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomLayoutSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File)
    private suspend fun fixture(layer2: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Original bank stays intact\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = objectSourceTestRom()
        rom.fill(-1, 0x7F000, 0x80000); rom.fill(-1, 0x207000, 0x208000)
        val level = ByteArray(2 + 32 * 32 * if (layer2) 5 else 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        val parser = RomParser(rom)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
            setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
        return Fixture(workspace, bridge, project, editor, file)
    }
    private fun Fixture.adopt() { assertTrue(editor.ensureLayoutSource("Landing Site"), editor.statusMessage) }
    private fun Fixture.inputs() = AsmRoomLayoutSource(bridge.sourceText()).resources(project.rooms.getValue("91F8"), editor.workingBlocksWide, editor.workingBlocksTall)

    @Test fun `adoption retains edited geometry retires JSON tile deltas and undoes as one source boundary`() = runBlocking {
        val f = fixture(layer2 = true)
        f.editor.applyBulkEdits("Legacy geometry", listOf(TileEdit(8, 8, 0, 0x8123, 0, 7), TileEdit(9, 9, 0, 0x123, layer = TILE_EDIT_LAYER_2)))
        val before = f.bridge.sourceText()
        val room = f.project.rooms.getValue("91F8")
        val oldModel = snapshotRoomForResize(room)
        val pixels = f.editor.workingLevelData!!.copyOf()
        f.adopt()
        assertEquals(1, room.asmLayoutSourceVersion)
        assertTrue(room.levelResourceOperations.isEmpty())
        assertTrue(room.operations.all { it.edits.isEmpty() } && room.states.all { state -> state.operations.all { it.edits.isEmpty() } })
        assertEquals(1, f.inputs().values.map { it.identity }.distinct().size)
        f.inputs().values.forEach { assertContentEquals(pixels, it.bytes()) }
        assertContains(f.bridge.sourceText(), "\$8123")
        assertContains(f.bridge.sourceText(), "BTS y=8")
        assertContains(f.bridge.sourceText(), "L2 / retained tail")
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(oldModel, room)
        assertEquals(before, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(1, room.asmLayoutSourceVersion)
    }

    @Test fun `raw code edits refresh canvas and GUI properties write the same literals with reciprocal undo`() = runBlocking {
        val f = fixture()
        f.adopt()
        val original = f.bridge.sourceText()
        val draft = original.replaceFirst("dw \$0000, \$0000", "dw \$8123, \$0000")
            .replaceFirst("db \$00, \$00", "db \$05, \$00")
        assertNotEquals(original, draft)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original, draft)
        f.editor.refreshLinkedPlms()
        assertEquals(0x8123, f.editor.readBlockWord(0, 0)); assertEquals(5, f.editor.readBts(0, 0))
        f.editor.setTileProperties(0, 0, 1, 0x12)
        assertEquals(0x1123, f.editor.readBlockWord(0, 0)); assertEquals(0x12, f.editor.readBts(0, 0))
        assertContains(f.bridge.sourceText(), "dw \$1123, \$0000")
        assertContains(f.bridge.sourceText(), "db \$12, \$00")
        assertTrue(f.project.rooms.getValue("91F8").levelResourceOperations.isEmpty())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(draft, f.bridge.sourceText())
        assertEquals(0x8123, f.editor.readBlockWord(0, 0))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(0x1123, f.editor.readBlockWord(0, 0))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(0x8123, f.editor.readBlockWord(0, 0))
    }

    @Test fun `private layouts fork source once and can be linked back without merging independent resources`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        f.adopt()
        val room = f.project.rooms.getValue("91F8")
        val selected = f.editor.currentPlmStateId()!!
        val other = room.states.first { it.id != selected }.id
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        assertEquals(2, f.inputs().values.map { it.identity }.distinct().size)
        f.editor.setTileProperties(8, 8, 8, 3)
        assertEquals(0x8000, f.editor.readBlockWord(8, 8))
        f.editor.switchRoomState(other, parser)
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.shareCurrentLayoutWithState(selected, parser), f.editor.statusMessage)
        assertEquals(0x8000, f.editor.readBlockWord(8, 8))
        assertEquals(1, f.inputs().values.map { it.identity }.distinct().size)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertEquals(2, f.inputs().values.map { it.identity }.distinct().size)
    }

    @Test fun `source navigation restores the actual private layout state when the room canvas reloads`() = runBlocking {
        val f = fixture()
        f.adopt()
        val room = f.project.rooms.getValue("91F8")
        val state = f.editor.currentPlmStateId()!!
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        f.editor.setTileProperties(8, 8, 8, 7)
        val target = AsmRoomLayoutSource.targetAt(f.bridge.sourceText().lines(), f.bridge.layouts.line(room.roomId, state))!!
        assertEquals(room.states.single { it.id == state }.resources.level, target.layoutId)
        f.editor.switchRoomState(room.states.first { it.id != state }.id, f.bridge.catalog.parser)
        f.editor.requestLayoutNavigation(target.roomId, target.layoutId, target.stateId)
        f.editor.loadRoom(room.roomId, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(room.roomId)!!)
        assertEquals(state, f.editor.currentPlmStateId())
        assertEquals(0x8000, f.editor.readBlockWord(8, 8))
    }

    @Test fun `a conflicting raw tile edit during a canvas stroke survives rejected commit and refresh`() = runBlocking {
        val f = fixture()
        f.adopt()
        val room = f.project.rooms.getValue("91F8")
        f.editor.brush = TileBrush.single(42, blockType = 8)
        f.editor.beginStroke()
        assertTrue(f.editor.paintAt(8, 8))
        val before = f.bridge.sourceText()
        val raw = AsmRoomLayoutSource(before).edit(room, f.editor.currentPlmStateId()!!, 32, 32,
            listOf(TileEdit(8, 8, 0, 0x8123, 0, 5)))
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        f.editor.refreshLinkedPlms()
        assertFalse(f.editor.endStroke())
        assertEquals(raw, f.bridge.sourceText())
        assertEquals(0x8123, f.editor.readBlockWord(8, 8)); assertEquals(5, f.editor.readBts(8, 8))
        assertTrue(room.levelResourceOperations.isEmpty())
    }

    @Test fun `returning from layout source retains the active state and GUI source undo identity`() = runBlocking {
        val f = fixture()
        f.adopt()
        f.editor.setTileProperties(8, 8, 8, 7)
        val state = f.editor.currentPlmStateId()!!
        val room = f.project.rooms.getValue("91F8")
        val level = room.states.single { it.id == state }.resources.level
        val operation = f.editor.undoStack.last()
        f.editor.requestLayoutNavigation(room.roomId, level, null)
        f.editor.loadRoomForCanvas(room.roomId, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(room.roomId)!!)
        assertEquals(state, f.editor.currentPlmStateId())
        assertSame(operation, f.editor.undoStack.last())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.editor.readBlockWord(8, 8))
    }

    @Test fun `invalid drafts retain last valid preview and block GUI writes and builds`() = runBlocking {
        val f = fixture()
        f.adopt()
        val good = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        val broken = good.replaceFirst("dw \$0800 ; Layer 1", "dw \$FFFF ; Layer 1")
        assertNotEquals(good, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.layoutPreviewProblem)
        assertContentEquals(pixels, f.editor.workingLevelData)
        f.editor.setTileProperties(8, 8, 8, 5)
        assertFalse(f.editor.makeCurrentLayoutUnique(f.bridge.catalog.parser))
        assertEquals(broken, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertFails { f.inputs() }
        assertTrue(f.workspace.saveAllSources(), "Invalid drafts remain saveable")
        assertFails { ProjectRoomExporter(f.project, f.bridge.catalog.parser, f.bridge.catalog.parser.copyRomData()).exportRooms() }
    }

    @Test fun `saved source is authoritative after reopen and typed export preserves sharing tiles BTS and layer two`() = runBlocking {
        val f = fixture(layer2 = true)
        f.adopt()
        f.editor.applyBulkEdits("Geometry", listOf(TileEdit(8, 8, 0, 0x8123, 0, 7), TileEdit(9, 9, 0, 0x123, layer = TILE_EDIT_LAYER_2)))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val project = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val parser = f.bridge.catalog.parser
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge; loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!) }
        assertContentEquals(f.editor.workingLevelData, editor.workingLevelData)
        val target = parser.copyRomData()
        ProjectRoomExporter(project, parser, target, sourceLayouts = mapOf(0x91F8 to AsmRoomLayoutSource(bridge.sourceText()).resources(project.rooms.getValue("91F8"), 32, 32))).exportRooms()
        val built = RomParser(target)
        val states = built.parseRoomStatesWithData(0x91F8)
        assertEquals(1, states.map { it.levelDataPtr }.distinct().size)
        states.forEach { assertContentEquals(editor.workingLevelData, built.decompressLZ2(it.levelDataPtr)) }
    }

    @Test fun `generation with authored layout commits geometry and camera in one source undo`() = runBlocking {
        val f = fixture()
        f.adopt()
        val source = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.editor.generateBiome(BiomeRules.roll(BiomeStyle.PIPE_MAZE, 1234L), TilesetProfile.synthetic(), 1234L,
            romParser = f.bridge.catalog.parser) > 0, f.editor.statusMessage)
        val generated = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.project.rooms.getValue("91F8").levelResourceOperations.isEmpty())
        f.inputs().values.forEach { assertContentEquals(generated, it.bytes()) }
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(source, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertContentEquals(generated, f.editor.workingLevelData)
    }

    @Test fun `resize and reset retain reciprocal layout ownership and source history`() = runBlocking {
        val f = fixture(layer2 = true)
        f.adopt()
        f.editor.setTileProperties(8, 8, 8, 7)
        val source = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(48, f.editor.workingBlocksWide)
        f.inputs().values.forEach { assertContentEquals(f.editor.workingLevelData, it.bytes()) }
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(source, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        assertEquals(0, f.project.rooms.getValue("91F8").asmLayoutSourceVersion)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(source, f.bridge.sourceText())
    }

    @Test fun `functional pattern publishes layout literals and object rows in one source step`() = runBlocking {
        val f = fixture()
        f.bridge.migrateRoom(0x91F8, "Landing Site")
        f.editor.refreshLinkedPlms()
        f.adopt()
        val before = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        f.editor.brush = TileBrush(listOf(listOf(42, 43)), plmOverrides = mapOf(0L to (0xEEDF to 0)))
        f.editor.beginStroke()
        assertTrue(f.editor.paintAt(8, 8), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText())
        assertTrue(f.editor.endStroke(), f.editor.statusMessage)
        val after = f.bridge.sourceText()
        assertNotEquals(before, after)
        assertTrue(f.editor.workingPlms.any { it.id == 0xEEDF && it.x == 8 && it.y == 8 })
        assertTrue(f.project.rooms.getValue("91F8").levelResourceOperations.isEmpty())
        assertContentEquals(f.editor.workingLevelData, f.inputs().getValue(f.editor.currentPlmStateId()!!).bytes())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(after, f.bridge.sourceText())
    }

    @Test fun `themed generation retains source only geometry through the whole room model transaction`() = runBlocking {
        val f = fixture()
        f.adopt()
        val before = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        val theme = BiomeTheme.KEEP.copy(tilesetId = 1)
        assertTrue(f.editor.generateBiome(BiomeRules.roll(BiomeStyle.PIPE_MAZE, 1234L), TilesetProfile.synthetic(), 1234L,
            romParser = f.bridge.catalog.parser, theme = theme) > 0, f.editor.statusMessage)
        assertEquals(1, f.editor.currentTilesetId)
        assertTrue(f.project.rooms.getValue("91F8").levelResourceOperations.isEmpty())
        assertContentEquals(f.editor.workingLevelData, f.inputs().getValue(f.editor.currentPlmStateId()!!).bytes())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(1, f.editor.currentTilesetId)
    }

    @Test fun `retained allocation padding layer two and odd trailing bytes round trip without normalization`() {
        val allocated = 512
        val bytes = ByteArray(2 + allocated * 5 + 1) { (it * 37).toByte() }
        bytes[0] = 0; bytes[1] = 4
        val resource = SourceRoomLayoutResource(AsmRoomLayoutSource.dataName(0x9879, "test"), 16, 16, bytes.map { it.toInt() and 255 })
        val text = AsmRoomLayoutSource.render(resource)
        assertContentEquals(bytes, AsmRoomLayoutSource(text).resource(0x9879, "test", 16, 16).bytes())
        assertFails { AsmRoomLayoutSource(text + text).resource(0x9879, "test", 16, 16) }
        assertFails { AsmRoomLayoutSource(text.replaceFirst("dw \$0400", "dw \$0400+2")).resource(0x9879, "test", 16, 16) }
        assertFails { resource.copy(values = bytes.take(10).map { it.toInt() and 255 }) }
        assertFails { resource.edited(listOf(TileEdit(16, 0, 0, 0x8000))) }
    }

    @Test fun `layout source context links navigate without compiled addresses and stop at unrelated macros`() {
        val lines = "macro SmeditLayout_9879_Data_1234()\n dw \$0200\n dw \$8123\nendmacro\n\nmacro Other()\n db 0\nendmacro".lines()
        assertEquals(0x9879, AsmRoomLayoutSource.roomAt(lines, 0))
        assertEquals(0x9879, AsmRoomLayoutSource.roomAt(lines, 2))
        assertNull(AsmRoomLayoutSource.roomAt(lines, 4))
        assertNull(AsmRoomLayoutSource.roomAt(lines, 6))
    }
}
