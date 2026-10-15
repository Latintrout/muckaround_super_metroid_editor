package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomSelectorsSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File) {
        val room get() = project.rooms.getValue("91F8")
        val parser get() = bridge.catalog.parser
        fun input() = AsmRoomSelectorsSource(bridge.sourceText()).resource(room)
        fun adopt() = assertTrue(editor.ensureSelectorSource("Landing Site"), editor.statusMessage)
        fun conditional() = room.states.first { it.sourceCondition?.kind != ProjectRoomStateConditionKind.DEFAULT }.id
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

    @Test fun `adoption preserves legacy condition edits and undo retires only source-owned current values`() = runBlocking {
        val f = fixture()
        val c = projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 25, negated = true)
        f.room.states.first().apply { condition = c; conditionChanged = true }
        val original = snapshotRoomForResize(f.room)
        val before = f.bridge.sourceText()
        f.adopt()
        assertEquals(c, f.input().conditions.getValue(f.conditional()))
        assertTrue(f.room.states.all { !it.conditionChanged && it.condition == AsmRoomSelectorsSource.baseline(it) })
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains("Projection"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertEquals(original, f.room)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(c, f.room.states.first().effectiveCondition)
    }

    @Test fun `raw predicates update GUI projections and GUI writes preserve comments with reciprocal undo`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        val before = f.bridge.sourceText()
        val raw = AsmRoomSelectorsSource(before).editCondition(f.room, id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7))
            .replace("; argument (", "; handwritten note / argument (")
        val version = f.editor.editVersion
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        f.editor.refreshLinkedPlms()
        assertEquals(7, f.room.states.first().effectiveCondition.argument)
        assertTrue(f.editor.editVersion > version)
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), f.parser)
        assertEquals(8, f.input().conditions.getValue(id).argument)
        assertContains(f.bridge.sourceText(), "; handwritten note")
        assertEquals(1, f.room.states.first().condition.argument)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(raw, f.bridge.sourceText())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED), f.parser)
        assertContains(f.bridge.sourceText(), "; SPEED_BOOSTER_COLLECTED: kind, NOT, child count")
        assertContains(f.bridge.sourceText(), "; handwritten note")
        val edited = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, edited, edited + "; newer raw edit\n")
        assertFalse(f.editor.undo())
    }

    @Test fun `source branch order reorders stable identities without changing resource ownership and exports in source order`() = runBlocking {
        val f = fixture()
        val first = f.conditional()
        val second = f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 2), f.parser)
        val resources = f.room.states.associate { it.id to it.resources }
        val canonicalOrder = f.room.states.map { it.id }
        f.adopt()
        assertTrue(f.editor.moveRoomState(second, -1, f.parser), f.editor.statusMessage)
        assertEquals(listOf(second, first, canonicalOrder.last()), f.room.effectiveStates.map { it.id })
        assertEquals(canonicalOrder, f.room.states.map { it.id })
        assertEquals(resources, f.room.states.associate { it.id to it.resources })
        val target = f.parser.copyRomData()
        ProjectRoomExporter(f.project, f.parser, target, sourceSelectors = mapOf(0x91F8 to f.input())).exportRooms()
        val built = RomParser(target).parseRoomStates(0x91F8)
        assertEquals(listOf(2, 1, 0), built.map { it.conditionArg })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(canonicalOrder, f.room.effectiveStates.map { it.id })
        assertFalse(f.editor.moveRoomState(canonicalOrder.last(), -1, f.parser))
        assertFails { f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 3), f.parser) }
        assertFails { f.editor.deleteRoomState(first, f.parser) }
        Unit
    }

    @Test fun `compound conditions compile and reopen from source without persisting current predicate values`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        val condition = projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 25, negated = true)))
        f.editor.setRoomStateCondition(id, condition, f.parser)
        assertEquals(condition, f.input().conditions.getValue(id))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val project = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertNull(project.rooms.getValue("91F8").states.first().asmConditionProjection)
        assertEquals(1, project.rooms.getValue("91F8").states.first().condition.argument)
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge; loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!) }
        assertEquals(condition, project.rooms.getValue("91F8").states.first().effectiveCondition)
        assertTrue(editor.roomPropertiesPreviewAvailable())
        val target = f.parser.copyRomData()
        ProjectRoomExporter(project, f.parser, target, sourceSelectors = mapOf(0x91F8 to f.input())).exportRooms()
        val built = RomParser(target).inspectRoomStates(0x91F8).states.first().condition
        assertEquals(RoomStateConditionKind.ALL_OF, built.kind)
        assertEquals(7, built.children.first().argument)
        assertEquals(25, built.children.last().argument); assertTrue(built.children.last().negated)
    }

    @Test fun `invalid drafts keep last valid previews block writes and builds but remain saveable`() = runBlocking {
        val f = fixture(); f.adopt()
        val good = f.bridge.sourceText()
        val previous = f.input()
        val broken = good.replaceFirst("db \$04, \$00, \$00", "db \$FF, \$00, \$00")
        assertNotEquals(good, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.selectorPreviewProblem)
        assertEquals(previous.conditions.getValue(f.conditional()), f.room.states.first().effectiveCondition)
        f.editor.setRoomStateCondition(f.conditional(), projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9), f.parser)
        assertEquals(broken, f.bridge.sourceText())
        assertFails { f.input() }
        assertFails { ProjectRoomExporter(f.project, f.parser, f.parser.copyRomData()).exportRooms() }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { reopened }
        val editor = EditorState().apply { testMode = true; initForProject(f.file, reopened); asmPlmSourceBridge = bridge; loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!) }
        assertNotNull(editor.selectorPreviewProblem)
        assertFalse(editor.roomPropertiesPreviewAvailable())
    }

    @Test fun `selectors coexist with layouts properties resize reset and context navigation`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.ensureLayoutSource("Landing Site"), f.editor.statusMessage)
        assertTrue(f.editor.ensureRoomPropertiesSource("Landing Site"), f.editor.statusMessage)
        f.adopt()
        val id = f.conditional()
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        val before = f.bridge.sourceText()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(7, f.input().conditions.getValue(id).argument)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText())
        val target = AsmRoomSelectorsSource.targetAt(before.lines(), f.bridge.selectors.line(0x91F8, id) + 1)
        assertEquals(AsmEditorTarget.Room(0x91F8, stateId = id), target)
        f.editor.requestLayoutNavigation(0x91F8, null, id)
        f.editor.loadRoomForCanvas(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertEquals(id, f.editor.currentPlmStateId())
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0, f.room.asmStateSelectorsSourceVersion)
        assertFalse(f.bridge.sourceText().contains("macro SmeditSelectors_"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(7, f.input().conditions.getValue(id).argument)
    }

    @Test fun `every writable vanilla selector graph round trips with stable kind IDs and literal validation`() {
        val parser = TestRomHelper.requireRomParser()
        assertEquals(ProjectRoomStateConditionKind.entries.toSet(), AsmRoomSelectorsSource.KINDS.toSet())
        for (info in parser.roomCatalog.rooms) {
            val id = info.getRoomIdAsInt()
            if (parser.readRoomHeader(id)!!.area !in 0..6) continue
            val room = RoomEdits(id).also { it.ensureStateManifest(parser); it.asmStateSelectorsSourceVersion = 1 }
            val selectors = SourceRoomSelectors(room.states.map { it.id }, room.states.associate { it.id to it.condition })
            val source = AsmRoomSelectorsSource.render(id, info.name, selectors)
            assertEquals(selectors, AsmRoomSelectorsSource(source).resource(room), info.name)
            assertFails { AsmRoomSelectorsSource(source + source).resource(room) }
        }
    }
}
