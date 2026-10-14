package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmInitialScrollSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge, val project: SmEditProject)
    private suspend fun fixture(): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("; Original source remains untouched\n")
        File(src, "macros.asm").writeText("macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = objectSourceTestRom().also { it.fill(0xFF.toByte(), 0x7F000, 0x80000) }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc")
        project.asmWorkspace.enabled = true
        project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), File(src, "macros.asm").readText(), emptyMap(), parser, emptyMap())
        return Fixture(workspace, AsmPlmSourceBridge(catalog, workspace) { project }, project)
    }
    private fun Fixture.editor(): EditorState {
        val fixture = this
        return EditorState().apply {
            testMode = true
            initForProject(File(directory, "Test.smedit"), fixture.project)
            asmPlmSourceBridge = fixture.bridge
            loadRoom(0x91F8, fixture.bridge.catalog.parser, fixture.bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        }
    }

    @Test fun `migration retires legacy changes atomically and saves ownership not a second grid`() = runBlocking {
        val f = fixture()
        val room = f.project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(f.bridge.catalog.parser)
        room.scrollChanges += ScrollChange(0, 0, 2, 0)
        room.states.last().scrollChanges += ScrollChange(0, 0, 2, 1)
        val before = f.bridge.sourceText()
        f.bridge.initialScrolls.migrate(room.roomId, "Landing Site")
        assertEquals(0, room.asmPlmSourceVersion, "Initial grids do not require object adoption")
        assertEquals(listOf(0, 2, 1, 1), f.bridge.initialScrolls.view(room.roomId, room.states.first().id).resource!!.values)
        assertEquals(listOf(1, 2, 1, 1), f.bridge.initialScrolls.view(room.roomId, room.states.last().id).resource!!.values)
        assertTrue(room.scrollChanges.isEmpty() && room.states.all { it.scrollChanges.isEmpty() })
        assertTrue(room.hasEdits)
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains("SmeditScrolls_"))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(before, f.bridge.sourceText())
        assertEquals(0, room.asmInitialScrollSourceVersion)
        assertEquals(0, room.scrollChanges.single().newValue)
        assertEquals(1, room.states.last().scrollChanges.single().newValue)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(1, room.asmInitialScrollSourceVersion)
        assertTrue(room.scrollChanges.isEmpty())
    }

    @Test fun `GUI edits detach shared state tables and ASM edits update live grid with shared undo`() = runBlocking {
        val f = fixture()
        val editor = f.editor()
        editor.setScroll(0, 0, 0, 2)
        val room = f.project.rooms.getValue("91F8")
        val current = editor.currentPlmStateId()!!
        val other = room.states.first { it.id != current }.id
        assertEquals(listOf(0, 2, 1, 1), editor.workingScrolls.toList())
        assertEquals(listOf(2, 2, 1, 1), f.bridge.initialScrolls.view(room.roomId, other).resource!!.values)
        assertNotEquals(f.bridge.initialScrolls.view(room.roomId, current).resource!!.identity, f.bridge.initialScrolls.view(room.roomId, other).resource!!.identity)
        assertTrue(editor.undo())
        assertEquals(listOf(2, 2, 1, 1), editor.workingScrolls.toList())
        assertTrue(editor.redo())
        assertEquals(0, editor.workingScrolls[0])
        val before = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db \$00 ; Screen (1,1) · Red", "db \$01 ; Screen (1,1) · Red"))
        editor.refreshLinkedPlms()
        assertEquals(1, editor.workingScrolls[0])
        assertTrue(f.workspace.saveAllSources())
        assertContains(f.bridge.sourceText(), "db \$01 ; Screen (1,1) · Blue")
        val restarted = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { f.project }
        assertEquals(listOf(1, 2, 1, 1), restarted.initialScrolls.view(room.roomId, current).resource!!.values)
        assertTrue(room.scrollChanges.isEmpty() && room.states.all { it.scrollChanges.isEmpty() })
    }

    @Test fun `raw shared edits affect aliases but invalid drafts retain last good preview and block visual writes`() = runBlocking {
        val f = fixture()
        f.bridge.initialScrolls.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db \$02 ; Screen (1,1)", "db \$00 ; Screen (1,1)"))
        room.states.forEach { assertEquals(0, f.bridge.initialScrolls.view(room.roomId, it.id).resource!!.values[0]) }
        val editor = f.editor()
        val good = f.bridge.sourceText()
        val invalid = good.replace("db \$00 ; Screen (1,1)", "db \$80 ; Screen (1,1)")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, invalid)
        editor.refreshLinkedPlms()
        assertNotNull(editor.initialScrollPreviewProblem)
        assertTrue(f.bridge.initialScrolls.line(room.roomId, editor.currentPlmStateId()!!) >= 0, "Broken bytes must remain reachable for repair")
        assertEquals(0, editor.workingScrolls[0])
        editor.setScroll(0, 0, 1, 2)
        assertEquals(invalid, f.bridge.sourceText(), "Never overwrite an invalid draft with a stale GUI preview")
        assertTrue(f.workspace.saveAllSources(), "Invalid text is still saveable")
        assertContains(f.bridge.sourceText(), "db \$80")
    }

    @Test fun `initial source survives state cloning reordering and removes only unreachable owned tables`() = runBlocking {
        val f = fixture()
        f.bridge.initialScrolls.migrate(0x91F8, "Test")
        val editor = f.editor()
        val room = f.project.rooms.getValue("91F8")
        val template = editor.currentPlmStateId()!!
        val newId = editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 12), f.bridge.catalog.parser)
        assertEquals(f.bridge.initialScrolls.view(room.roomId, template).resource, f.bridge.initialScrolls.view(room.roomId, newId).resource)
        f.bridge.initialScrolls.edit(room.roomId, newId, 1, 0)
        assertEquals(2, f.bridge.initialScrolls.view(room.roomId, template).resource!!.values[1])
        room.states.add(0, room.states.removeAt(1))
        assertEquals(0, f.bridge.initialScrolls.view(room.roomId, newId).resource!!.values[1])
        val source = AsmInitialScrollSource(f.bridge.sourceText())
        val orphan = SourceInitialScrollResource("SmeditScrolls_91F8_Table_99", 2, 2, listOf(0, 0, 0, 0))
        val withOrphan = source.text + "\n" + AsmInitialScrollSource.renderTable(orphan)
        assertFalse(AsmInitialScrollSource(withOrphan).tidy(room).contains(orphan.identity))
        assertContains(AsmInitialScrollSource(withOrphan).tidy(room, setOf(orphan.identity)), orphan.identity)
        editor.deleteRoomState(newId, f.bridge.catalog.parser)
        val replacementId = editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 13), f.bridge.catalog.parser)
        assertNotEquals(newId, replacementId, "A deleted state's declaration may remain until Save; do not reuse its source identity")
        assertNotNull(f.bridge.initialScrolls.view(room.roomId, replacementId).resource)
    }

    @Test fun `codec is bounded preserves CRLF notes and rejects malformed or ambiguous tables`() {
        val room = RoomEdits(0x91F8, asmInitialScrollSourceVersion = 1)
        room.ensureStateManifest(RomParser(objectSourceTestRom()))
        val name = "SmeditScrolls_91F8_Table_1"
        val resource = SourceInitialScrollResource(name, 2, 2, listOf(2, 2, 1, 1))
        val text = (AsmInitialScrollSource.renderTable(resource) + room.states.joinToString("") { AsmInitialScrollSource.renderState(room.roomId, it.id, name) }).replace("\n", "\r\n")
        assertEquals(resource, AsmInitialScrollSource(text).resources(room, 2, 2).values.first())
        val privateRoom = room.copy(states = mutableListOf(room.states.first()))
        val annotated = text.replace("· Green", "· Green ; handwritten note")
        val changed = AsmInitialScrollSource(annotated).edit(privateRoom, room.states.first().id, 2, 2, 0, 0)
        assertContains(changed, "\r\n")
        assertContains(changed, "; handwritten note")
        assertContains(AsmInitialScrollSource(changed).withComments(privateRoom, 2, 2), "· Red ; handwritten note")
        listOf(text.replace("db \$02", "dw \$02"), text.replaceFirst("db \$02", "db \$03"),
            text.replaceFirst("db \$02", "db \$02+0"), text.replaceFirst("db \$02", "db \$02,\$02"),
            text + AsmInitialScrollSource.renderTable(resource)).forEach { invalid ->
            assertFails { AsmInitialScrollSource(invalid).resources(room, 2, 2) }
        }
        assertFails { AsmInitialScrollSource(text).resources(room, 1, 3) }
        assertFails { SourceInitialScrollResource(name, 9, 6, List(54) { 1 }) }
        assertFails { AsmInitialScrollSource(text).resources(room.copy(scrollChanges = mutableListOf(ScrollChange(0, 0, 2, 1))), 2, 2) }
    }

    @Test fun `source ownership persists and unsupported compound operations cannot partially change a room`() = runBlocking {
        val f = fixture()
        val editor = f.editor()
        editor.setScroll(0, 0, 0, 2)
        val before = f.bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        editor.resizeRoom(2, 2, 3, 2)
        assertEquals(32, editor.workingBlocksWide)
        assertContentEquals(pixels, editor.workingLevelData)
        assertFalse(editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser))
        editor.setProjectRomBuildMode(ProjectRomBuildMode.PATCHED_ROM)
        assertEquals(ProjectRomBuildMode.ASM_SOURCE, f.project.asmWorkspace.buildMode)
        editor.setScroll(-1, 0, 1, 2)
        editor.setScroll(0, 2, 1, 2)
        editor.setScroll(0, 0, 3, 2)
        assertEquals(before, f.bridge.sourceText())
        val json = Json { encodeDefaults = true }
        val reopened = json.decodeFromString(SmEditProject.serializer(), json.encodeToString(SmEditProject.serializer(), f.project))
        val fresh = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, registerSaveParticipant = false) { reopened }
        assertTrue(reopened.rooms.getValue("91F8").hasEdits)
        assertTrue(fresh.initialScrolls.isManaged(0x91F8))
        assertEquals(editor.workingScrolls.toList(), fresh.initialScrolls.view(0x91F8, editor.currentPlmStateId()!!).resource!!.values)
        editor.asmPlmSourceBridge = null
        editor.loadRoom(0x91F8, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.workingScrolls.isEmpty(), "Startup must not display vanilla as the authored grid while the lens is pending")
        assertNotNull(editor.initialScrollPreviewProblem)
        editor.asmPlmSourceBridge = f.bridge
        editor.refreshLinkedPlms()
        assertEquals(0, editor.workingScrolls[0])
        assertNull(editor.initialScrollPreviewProblem)
    }

    @Test fun `checked exporter allocates identities once preserves baseline and handles headered graphs`() {
        for (header in listOf(0, 0x200)) {
            val original = ByteArray(header) + objectSourceTestRom().also { it.fill(0xFF.toByte(), 0x7F000, 0x80000) }
            val parser = RomParser(original)
            val project = SmEditProject("test.sfc")
            val room = project.getOrCreateRoom(0x91F8)
            room.ensureStateManifest(parser)
            room.asmInitialScrollSourceVersion = 1
            val table = SourceInitialScrollResource("SmeditScrolls_91F8_Table_1", 2, 2, listOf(0, 2, 1, 1))
            val inputs = mapOf(room.roomId to room.states.associate { it.id to table })
            val target = original.copyOf()
            val result = ProjectRoomExporter(project, parser, target, sourceInitialScrolls = inputs).exportRooms()
            assertEquals(1, result.allocations.size)
            val built = RomParser(target)
            val states = built.parseRoomStatesWithData(room.roomId)
            assertEquals(1, states.map { it.scrollPtr }.distinct().size)
            states.forEach { assertEquals(table.values, built.parseScrollData(it.scrollPtr, 2, 2).toList()) }
            assertContentEquals(listOf(2, 2, 1, 1), parser.parseScrollData(0, 2, 2).toList())
            val separate = table.copy(identity = "SmeditScrolls_91F8_Table_2")
            val isolated = ProjectRoomExporter(project, parser, original.copyOf(), sourceInitialScrolls = mapOf(room.roomId to
                mapOf(room.states.first().id to table, room.states.last().id to separate))).exportRooms()
            assertEquals(2, isolated.allocations.size, "Content equality must not create new aliases")
            assertFails { ProjectRoomExporter(project, parser, original.copyOf()).exportRooms() }
            assertFails { ProjectRoomExporter(project, parser, original.copyOf(), sourceInitialScrolls = mapOf(room.roomId to mapOf(room.states.first().id to table))).exportRooms() }
        }
    }

    @Test fun `baseline protection refuses original pointer byte and room dimension changes`() {
        val original = objectSourceTestRom()
        original[0x79300 + 14] = 0x00; original[0x79300 + 15] = 0x94.toByte()
        byteArrayOf(1, 2, 1, 2).copyInto(original, 0x79400)
        val baseline = RomParser(original)
        val project = SmEditProject("test.sfc")
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(baseline)
        room.asmInitialScrollSourceVersion = 1
        protectInitialScrollBaseline(project, baseline, RomParser(original.copyOf()))
        listOf(0x79300 + 14, 0x79400, 0x791F8 + 4, 0x16B23).forEach { offset ->
            val changed = original.copyOf().also { it[offset] = (it[offset] + 1).toByte() }
            assertFails { protectInitialScrollBaseline(project, baseline, RomParser(changed)) }
        }
        assertFails { AsmRoomInitialScrollTable(SourceInitialScrollResource("SmeditScrolls_91F8_Table_1", 2, 2, listOf(0, 1, 2, 1)), 0x7FFFF, listOf(0x7930E)).claims() }
    }

    @Test fun `typed placement owns complete pointers and rejects missing allocations byte drift and overlapping owners`() {
        for (header in listOf(0, 0x200)) {
            val original = ByteArray(header) + objectSourceTestRom().also { it.fill(0xFF.toByte(), 0x7F000, 0x80000) }
            val parser = RomParser(original)
            val project = SmEditProject("test.sfc")
            val room = project.getOrCreateRoom(0x91F8)
            room.ensureStateManifest(parser)
            room.asmInitialScrollSourceVersion = 1
            val resource = SourceInitialScrollResource("SmeditScrolls_91F8_Table_1", 2, 2, listOf(0, 1, 2, 1))
            val inputs = mapOf(room.roomId to room.states.associate { it.id to resource })
            val target = original.copyOf()
            val allocation = ProjectRoomExporter(project, parser, target, sourceInitialScrolls = inputs).exportRooms().allocations.single()
            val intent = RomWriteIntent(ASM_ROOM_GRAPH_OWNER, allocation.label, allocation.pcOffset - header,
                resource.values, RomWriteKind.ALLOCATION)
            val report = RomWritePlanReport(listOf(intent), emptyList(), emptyList(), emptyList())
            val table = discoverAsmInitialScrollTables(project, report, target, inputs).single()
            assertEquals(3, table.claims().size, "Allocation plus two complete state-pointer words")
            assertEquals(2, table.pointerOffsets.size)
            assertContains(table.renderSource(), "%${resource.identity}()")
            assertContains(table.renderSource(), "dw ${resource.identity}&\$FFFF")
            assertFalse(table.renderSource().contains("db "), "Placement must invoke the authored table, not duplicate its values")
            assertFails { discoverAsmInitialScrollTables(project, report.copy(writes = emptyList()), target, inputs) }
            assertFails { discoverAsmInitialScrollTables(project, report.copy(writes = listOf(intent.copy(bytes = listOf(2, 1, 2, 1)))), target, inputs) }
            val patch = RomWriteIntent("patch:test", "Pointer conflict", table.pointerOffsets.first(), listOf(0))
            assertFails { discoverAsmInitialScrollTables(project, report.copy(writes = report.writes + patch), target, inputs) }
            val changed = target.copyOf().also { it[allocation.pcOffset] = 1 }
            assertFails { discoverAsmInitialScrollTables(project, report, changed, inputs) }
        }
    }
}
