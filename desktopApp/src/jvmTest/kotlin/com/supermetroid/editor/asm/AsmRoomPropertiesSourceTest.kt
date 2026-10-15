package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomPropertiesSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File) {
        val room get() = project.rooms.getValue("91F8")
        val parser get() = bridge.catalog.parser
        fun input() = AsmRoomPropertiesSource(bridge.sourceText()).resource(room)
        fun adopt() = assertTrue(editor.ensureRoomPropertiesSource("Landing Site"), editor.statusMessage)
    }
    private suspend fun fixture(): Fixture {
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
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        val parser = RomParser(rom)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        }
        project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        return Fixture(workspace, bridge, project, editor, file)
    }

    @Test fun `adoption flattens legacy header and state overrides without serializing duplicate values`() = runBlocking {
        val f = fixture()
        f.room.roomHeaderChange = RoomHeaderChange(upScroller = 0x72, creBitflag = 3, doorOut = 0x9400)
        f.room.stateDataChange = StateDataChange(musicData = 0x18)
        f.room.states.first().stateDataChange = StateDataChange(musicTrack = 7)
        val old = snapshotRoomForResize(f.room)
        val before = f.bridge.sourceText()
        f.adopt()
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(RoomHeaderChange(doorOut = 0x9400), f.room.roomHeaderChange)
        assertNull(f.room.stateDataChange)
        assertTrue(f.room.states.all { it.stateDataChange == null })
        assertEquals(0x72, f.input().header.upScroller)
        assertEquals(3, f.input().header.creBitflag)
        assertEquals(7, f.input().states.getValue(f.room.states.first().id).musicTrack)
        assertTrue(f.input().states.values.all { it.musicData == 0x18 })
        val json = Json.encodeToString(SmEditProject.serializer(), f.project)
        assertFalse(json.contains("PropertiesProjection"))
        val decoded = Json.decodeFromString<SmEditProject>(json).rooms.getValue("91F8")
        assertNull(decoded.asmHeaderPropertiesProjection)
        assertTrue(decoded.states.all { it.effectiveStateDataChange == null })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertEquals(old, f.room)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(0x72, f.room.effectiveHeaderChange?.upScroller)
    }

    @Test fun `raw ASM refreshes header and selected state and GUI writes preserve other literals and comments`() = runBlocking {
        val f = fixture(); f.adopt()
        val state = f.editor.currentPlmStateId()!!
        val other = f.room.states.first { it.id != state }.id
        val oldOther = f.input().states.getValue(other)
        val before = f.bridge.sourceText()
        val source = AsmRoomPropertiesSource(before)
        val header = source.editHeader(f.room, f.input().header.copy(upScroller = 0x71, creBitflag = 2))
        val raw = AsmRoomPropertiesSource(header).editStates(f.room,
            mapOf(state to f.input().states.getValue(state).copy(musicData = 0x18, musicTrack = 5, bgScrolling = 0x0201)))
            .replace("; Music data", "; Music data / handwritten annotation")
        val previewVersion = f.editor.editVersion
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        f.editor.refreshLinkedPlms()
        assertTrue(f.editor.editVersion > previewVersion, "Raw property changes must invalidate GUI consumers")
        val base = f.parser.readRoomHeader(0x91F8)!!
        assertEquals(0x71, f.editor.applyHeaderChanges(base).upScroller)
        assertEquals(2, f.editor.applyHeaderChanges(base).creBitflag)
        assertEquals(0x0201, f.editor.applyCurrentStateData(base, f.parser).bgScrolling)
        assertEquals(0x18, f.editor.applyCurrentStateData(base, f.parser).musicData)
        f.editor.setRoomStateDataChange(state, f.input().states.getValue(state).copy(musicTrack = 7), f.parser)
        assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertEquals(0x18, f.input().states.getValue(state).musicData)
        assertEquals(oldOther, f.input().states.getValue(other))
        assertContains(f.bridge.sourceText(), "; Music data / handwritten annotation")
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(raw, f.bridge.sourceText())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        val edited = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, edited, edited + "; newer hand edit\n")
        assertFalse(f.editor.undo(), "A newer source edit must not be overwritten")
    }

    @Test fun `GUI header edits and reverting state fields to native update source without creating overrides`() = runBlocking {
        val f = fixture()
        f.editor.setRoomHeaderChange(RoomHeaderChange(upScroller = 0x70))
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(0x70, f.input().header.upScroller)
        assertNull(f.room.roomHeaderChange)
        val id = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(id, StateDataChange(musicData = 0x18, musicTrack = 7), f.parser)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
        f.editor.setRoomStateDataChange(id, null, f.parser)
        assertEquals(f.bridge.properties.nativeState(f.room, id), f.input().states.getValue(id))
        assertTrue(f.room.states.all { it.stateDataChange == null })
    }

    @Test fun `invalid saveable drafts retain last good values and block GUI writes cloning and builds`() = runBlocking {
        val f = fixture(); f.adopt()
        val good = f.bridge.sourceText()
        val resource = f.input()
        val broken = good.replaceFirst("db \$00 ; Area", "db \$FF ; Area")
        assertNotEquals(good, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.roomPropertiesPreviewProblem)
        assertEquals(resource.header, f.room.effectiveHeaderChange)
        f.editor.setRoomHeaderChange(RoomHeaderChange(upScroller = 4))
        assertEquals(broken, f.bridge.sourceText())
        assertFails { f.editor.addRoomState(f.room.states.last().id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 1), f.parser) }
        assertEquals(broken, f.bridge.sourceText())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertFails { f.input() }
        assertFails { ProjectRoomExporter(f.project, f.parser, f.parser.copyRomData()).exportRooms() }
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { reopened }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, reopened); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        }
        assertNotNull(editor.roomPropertiesPreviewProblem)
        assertFalse(editor.roomPropertiesPreviewAvailable())
    }

    @Test fun `saved source reloads and typed export writes every field without modifying the project model`() = runBlocking {
        val f = fixture(); f.adopt()
        val state = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(state, StateDataChange(tileset = 1, musicData = 0x18, musicTrack = 7, bgScrolling = 0x0201), f.parser)
        f.editor.setRoomHeaderChange(RoomHeaderChange(index = 0x12, mapX = 8, mapY = 9, upScroller = 0x71, downScroller = 0x72, creBitflag = 3))
        val inputs = f.input()
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val encoded = Json.encodeToString(SmEditProject.serializer(), f.project)
        val project = Json.decodeFromString<SmEditProject>(encoded)
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        }
        assertEquals(inputs.header, project.rooms.getValue("91F8").effectiveHeaderChange)
        editor.switchRoomState(state, f.parser)
        assertEquals(0x0201, editor.applyCurrentStateData(f.parser.readRoomHeader(0x91F8)!!, f.parser).bgScrolling)
        val beforeExport = Json.encodeToString(SmEditProject.serializer(), project)
        val target = f.parser.copyRomData()
        ProjectRoomExporter(project, f.parser, target, sourceRoomProperties = mapOf(0x91F8 to inputs)).exportRooms()
        val built = RomParser(target)
        val h = built.readRoomHeader(0x91F8)!!
        assertEquals(0x12, h.index); assertEquals(8, h.mapX); assertEquals(9, h.mapY)
        assertEquals(0x71, h.upScroller); assertEquals(0x72, h.downScroller); assertEquals(3, h.creBitflag)
        built.parseRoomStatesWithData(0x91F8).forEachIndexed { index, data ->
            val expected = inputs.states.getValue(f.room.states[index].id)
            assertEquals(expected, StateDataChange(data.tileset, data.musicData, data.musicTrack, data.bgScrolling))
        }
        assertEquals(beforeExport, Json.encodeToString(SmEditProject.serializer(), project))
    }

    @Test fun `properties coexist with layout resize theme reset and state cloning without dual ownership`() = runBlocking {
        val f = fixture(); f.adopt()
        assertTrue(f.editor.ensureLayoutSource("Landing Site"), f.editor.statusMessage)
        val id = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(id, StateDataChange(musicTrack = 7), f.parser)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(3, f.room.roomHeaderChange?.width)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertNull(f.room.roomHeaderChange)
        val source = f.bridge.sourceText()
        assertTrue(f.editor.applyBiomeTheme(BiomeTheme.KEEP.copy(tilesetId = 1), f.parser), f.editor.statusMessage)
        assertEquals(1, f.input().states.getValue(id).tileset)
        assertTrue(f.room.states.all { it.stateDataChange == null })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(source, f.bridge.sourceText())
        val newId = f.editor.addRoomState(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 2), f.parser)
        assertEquals(f.input().states.getValue(id), f.input().states.getValue(newId))
        f.editor.deleteRoomState(newId, f.parser)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0, f.room.asmRoomPropertiesSourceVersion)
        assertFalse(f.bridge.sourceText().contains("macro SmeditRoomInfo_"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
    }

    @Test fun `unsupported structural header edits and area reassignment stop before model or source mutations`() = runBlocking {
        val f = fixture(); f.adopt()
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText()
        f.editor.setRoomHeaderChange(RoomHeaderChange(width = 3))
        assertFalse(f.editor.reassignRoomArea(0x91F8, 1, f.parser).success)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(source, f.bridge.sourceText())
    }

    @Test fun `literal lens rejects duplicate expression reordered widths and invalid byte and word ranges`() = runBlocking {
        val f = fixture(); f.adopt()
        val text = f.bridge.sourceText()
        val macro = text.substring(text.indexOf("macro SmeditRoomInfo_"), text.indexOf("endmacro") + "endmacro".length) + "\n"
        assertFails { AsmRoomPropertiesSource(text + macro).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("; Area", "+1 ; Area")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("db ", "dw ")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("; Area", "000 ; Area")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("dw ", "dw \$10000,")).resource(f.room) }
        val lines = text.lines()
        val id = f.room.states.first().id
        assertEquals(AsmEditorTarget.Room(0x91F8, stateId = id), AsmRoomPropertiesSource.targetAt(lines, f.bridge.properties.line(0x91F8, id) + 2))
        assertEquals(AsmEditorTarget.Room(0x91F8), AsmRoomPropertiesSource.targetAt(lines, f.bridge.properties.line(0x91F8) + 1))
        assertNull(AsmRoomPropertiesSource.targetAt(lines + listOf("macro Other()", " db 0"), lines.size + 1))
    }

    @Test fun `every writable vanilla catalog room retains all native scalar fields through typed source declarations`() {
        val parser = TestRomHelper.requireRomParser()
        for (info in parser.roomCatalog.rooms) {
            val id = info.getRoomIdAsInt()
            val header = assertNotNull(parser.readRoomHeader(id), info.name)
            // The existing exporter excludes the engine's debug/unused area 7.
            if (header.area !in 0..6) continue
            val room = RoomEdits(id).also { it.ensureStateManifest(parser); it.asmRoomPropertiesSourceVersion = 1 }
            val data = parser.parseRoomStatesWithData(id)
            val properties = SourceRoomProperties(RoomHeaderChange(index = header.index, area = header.area, mapX = header.mapX,
                mapY = header.mapY, upScroller = header.upScroller, downScroller = header.downScroller, creBitflag = header.creBitflag),
                room.states.associate { state ->
                    val native = data[state.baseSourceStateIndex()!!]
                    state.id to StateDataChange(native.tileset, native.musicData, native.musicTrack, native.bgScrolling)
                })
            assertEquals(properties, AsmRoomPropertiesSource(AsmRoomPropertiesSource.render(room, info.name, properties)).resource(room), info.name)
        }
    }
}
