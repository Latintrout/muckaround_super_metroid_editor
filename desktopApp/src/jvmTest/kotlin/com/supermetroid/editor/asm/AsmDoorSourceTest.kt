package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.MinimapEditorState
import com.supermetroid.editor.ui.RoomAsmNavigation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmDoorSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val project: SmEditProject, val editor: EditorState,
                               val bridge: AsmPlmSourceBridge, val file: File) {
        val parser get() = bridge.catalog.parser
        val room get() = project.rooms.getValue("91F8")
        fun input() = AsmDoorSource(bridge.doors.sourceText(), workspace.projectSourceText(PLM_SOURCE_FILE), bridge.catalog.symbols).resource(room, parser)
        fun adopt() = assertTrue(editor.ensureDoorSource("Landing Site"), editor.statusMessage)
    }
    private suspend fun fixture(shared: Boolean = false, nativeOutside: Boolean = false, nativeArrival: Int = 0, knownArrival: Boolean = true,
                                built: Boolean = false): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native room source\n" + if (nativeArrival != 0 && knownArrival) "DoorASM_Test: ;8F9000;\n rts\n" else "")
        File(src, DOOR_SOURCE_FILE).writeText("; Native DoorDefs and FX stay intact\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        if (built) AsmBuildArtifactRepository(repository).publishSuccess(file.path, ByteArray(0), listOf("Fixture build"), "fixture")
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        rom[0x1800A] = nativeArrival.toByte(); rom[0x1800B] = (nativeArrival ushr 8).toByte()
        if (nativeOutside) rom[0x18006] = 2 // A retained native/special entrance outside this 2x2 room.
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        rom.copyOfRange(0x791F8, 0x791F8 + 44).copyInto(rom, 0x7A000)
        rom[0x7A001] = 1 // Readable destination in another area, with no outgoing connections.
        rom[0x7A009] = 0; rom[0x7A00A] = 0
        if (shared) {
            // Discovery validates state data too; a header-only clone is deliberately not a room consumer.
            rom.copyOfRange(0x791F8, 0x791F8 + 44).copyInto(rom, 0x79879)
        }
        val parser = RomParser(rom)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path).apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        val symbols = if (nativeArrival != 0 && knownArrival) mapOf("DoorASM_Test" to nativeArrival) else emptyMap()
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog("", macros, symbols, parser, emptyMap()), workspace)
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        return Fixture(workspace, project, editor, bridge, file)
    }

    @Test fun `automatic door editor is a pure navigable overlay never included in saves searches or build input`() = runBlocking {
        val f = fixture(built = true)
        assertTrue(f.editor.saveProject())
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.doors.sourceText()
        val report = f.workspace.buildReport
        assertNotNull(report); assertFalse(f.workspace.sourceBuildRequired)
        val revision = f.workspace.sourceBufferRevision
        val head = f.workspace.sourceUndoHead(DOOR_SOURCE_FILE)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)), f.editor.statusMessage)
        val snapshot = assertNotNull(f.workspace.roomSourceInspection)
        val planned = assertNotNull(snapshot.automaticEdit)
        assertTrue(f.workspace.isProjectSourceEditable)
        assertEquals(planned.lineIndex, f.workspace.selectedLineIndex)
        assertEquals(planned.text, f.workspace.sourceEditorText(DOOR_SOURCE_FILE))
        assertEquals(source, f.workspace.sourceTexts().single { it.fileId == DOOR_SOURCE_FILE }.text)
        assertTrue(f.workspace.previewProjectSourceReplacement("SmeditDoors_", "oops", true).matches.isEmpty())
        f.workspace.selectSourceEditorMatch(DOOR_SOURCE_FILE, planned.lineIndex + 1, 7)
        assertSame(snapshot, f.workspace.roomSourceInspection, "Find must not discard the staged document")
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, planned.text), "Selection/no-op events must not adopt")
        assertTrue(f.workspace.saveAllSources()); assertTrue(f.workspace.saveSource(DOOR_SOURCE_FILE))
        assertEquals(source, f.bridge.doors.sourceText())
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertSame(head, f.workspace.sourceUndoHead(DOOR_SOURCE_FILE)); assertSame(report, f.workspace.buildReport)
        assertFalse(f.editor.dirty); assertFalse(f.workspace.sourceBuildRequired)
        assertTrue(f.editor.undoStack.isEmpty()); assertFalse(f.workspace.hasProjectSourceChanges)
        f.workspace.openSource(PLM_SOURCE_FILE)
        f.workspace.goBack()
        assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `first door keystroke adopts existing GUI values atomically with reciprocal undo redo and no caret relocation`() = runBlocking {
        val f = fixture(built = true)
        val native = f.editor.doorEntries.single()
        f.project.getOrCreateRoom(0x91F8).doorChanges += DoorChange(0, native.destRoomPtr, native.bitflag, native.doorCapCode, 1, 1, 0x8000, 0)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertTrue(f.editor.saveProject())
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.doors.sourceText()
        val selectedState = f.editor.currentStateIndex
        val request = RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(request), f.editor.statusMessage)
        val planned = f.workspace.sourceEditorText(DOOR_SOURCE_FILE)
        val edited = planned.replace("dw ${'$'}8000 ; Distance", "dw ${'$'}1234 ; Distance")
        assertNotEquals(planned, edited)
        val line = f.workspace.selectedLineIndex + 5
        f.workspace.selectSourcePosition(line, 10)
        val serial = f.workspace.navigationSerial
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, edited), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection)
        assertEquals(serial, f.workspace.navigationSerial); assertEquals(line, f.workspace.selectedLineIndex)
        assertEquals(1, f.room.asmDoorSourceVersion); assertTrue(f.room.doorChanges.isEmpty())
        assertEquals(1, f.editor.doorEntries.single().screenY)
        assertEquals(0x1234, f.editor.doorEntries.single().distFromDoor)
        assertEquals(selectedState, f.editor.currentStateIndex)
        assertTrue(f.workspace.sourceBuildRequired); assertTrue(f.editor.dirty)
        assertEquals(1, f.editor.undoStack.size)
        assertEquals(edited, f.bridge.doors.sourceText())
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(source, f.bridge.doors.sourceText())
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(0x8000, f.editor.doorEntries.single().distFromDoor)
        assertTrue(f.editor.undoStack.isEmpty()); assertEquals(1, f.editor.redoStack.size)
        assertEquals(planned, f.workspace.sourceEditorText(DOOR_SOURCE_FILE))
        assertEquals(line, f.workspace.selectedLineIndex); assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertFalse(f.workspace.sourceBuildRequired, "Undo restores the saved build, not an adopted no-op bank")
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertNull(f.workspace.roomSourceInspection)
        assertEquals(edited, f.bridge.doors.sourceText()); assertEquals(1, f.editor.undoStack.size)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x2345))
        assertContains(f.bridge.doors.sourceText(), "dw ${'$'}2345 ; Distance")
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0x1234, f.editor.doorEntries.single().distFromDoor)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(edited, f.bridge.doors.sourceText())
        val revision = f.workspace.sourceBufferRevision
        assertTrue(navigation.open(request), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `an incomplete first edit remains saveable and repairable without losing native door preview`() = runBlocking {
        val f = fixture()
        val original = f.editor.doorEntries.single()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)))
        val planned = f.workspace.sourceEditorText(DOOR_SOURCE_FILE)
        val broken = planned.replace("dw ${'$'}91F8", "dw ${'$'}")
        assertNotEquals(planned, broken)
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, broken), f.editor.statusMessage)
        assertEquals(1, f.room.asmDoorSourceVersion)
        assertNotNull(f.editor.doorPreviewProblem)
        assertEquals(original, f.editor.doorEntries.single())
        f.editor.updateDoor(0, original.copy(distFromDoor = 0x1234))
        assertEquals(broken, f.bridge.doors.sourceText())
        assertFails { f.input() }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertTrue(f.editor.saveProject())
        val cold = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { f.project }
        assertNull(cold.doors.view(0x91F8).resource); assertNotNull(cold.doors.view(0x91F8).problem)
        val fixed = planned.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}1234 ; Distance")
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, fixed))
        f.editor.refreshLinkedPlms()
        assertNull(f.editor.doorPreviewProblem); assertEquals(0x1234, f.editor.doorEntries.single().distFromDoor)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertFalse(f.editor.hasManagedDoors()); assertEquals(original, f.editor.doorEntries.single())
    }

    @Test fun `stale automatic door editors cannot publish any part of a first edit`() = runBlocking {
        val changes: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "room" to { it.project.getOrCreateRoom(0x91F8).fxChange = FxChange(fxType = 2) },
            "source" to { it.workspace.replaceProjectSource(PLM_SOURCE_FILE, it.bridge.sourceText(), "; Newer code\n") },
            "mode" to { it.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM },
            "parser" to { it.editor.loadRoom(0x91F8, RomParser(it.parser.copyRomData()), it.parser.readRoomHeader(0x91F8)!!) },
            "view" to { it.workspace.openSource(PLM_SOURCE_FILE) },
            "project" to { it.editor.initForProject(File(directory, "Other.smedit"), SmEditProject("other.sfc")) })
        for ((name, change) in changes) {
            val f = fixture()
            val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
            assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)), name)
            val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
            change(f)
            val model = Json.encodeToString(SmEditProject.serializer(), f.editor.project)
            val source = f.bridge.doors.sourceText(); val revision = f.workspace.sourceBufferRevision
            val dirty = f.editor.dirty; val history = f.editor.undoStack.toList()
            assertFalse(draft.apply(draft.text + "; Draft edit\n"), name)
            assertContains(f.editor.statusMessage, "reopen", message = name)
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.editor.project), name)
            assertEquals(source, f.bridge.doors.sourceText(), name); assertEquals(revision, f.workspace.sourceBufferRevision, name)
            assertEquals(history, f.editor.undoStack, name); assertEquals(dirty, f.editor.dirty, name)
        }
    }

    @Test fun `automatic door editing preserves the existing bank exactly and never steals another split pane's edits`() = runBlocking {
        val f = fixture()
        val original = f.bridge.doors.sourceText()
        val before = original.replace("\n", "\r\n") + "; My trailing note  \r\n  "
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, original, before)
        val earlierHead = f.workspace.sourceUndoHead(DOOR_SOURCE_FILE)
        val native8F = f.bridge.sourceText()
        f.workspace.openSource(PLM_SOURCE_FILE)
        val otherPane = f.workspace.activeSourcePaneId
        f.workspace.openSourceInSplit(DOOR_SOURCE_FILE)
        val doorPane = f.workspace.activeSourcePaneId
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)))
        val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertTrue(draft.text.startsWith(before), "Adoption must not trim or normalize the existing bank")
        assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(draft.text))
        f.workspace.activateSourcePane(otherPane)
        val otherEdit = native8F + "; independent pane edit\n"
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, otherEdit))
        assertEquals(otherEdit, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertFalse(f.editor.hasManagedDoors())
        f.workspace.activateSourcePane(doorPane)
        assertFalse(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, draft.text + "; stale edit\r\n"))
        assertEquals(before, f.bridge.doors.sourceText())
        assertSame(earlierHead, f.workspace.sourceUndoHead(DOOR_SOURCE_FILE))
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)))
        val current = f.workspace.sourceEditorText(DOOR_SOURCE_FILE)
        val edited = current.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}1234 ; Distance")
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, edited), f.editor.statusMessage)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.bridge.doors.sourceText()); assertSame(earlierHead, f.workspace.sourceUndoHead(DOOR_SOURCE_FILE))
        assertEquals(otherEdit, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertTrue(f.bridge.doors.sourceText().startsWith(before))
        assertEquals(otherEdit, f.workspace.projectSourceText(PLM_SOURCE_FILE))
    }

    @Test fun `door previews are pure adoption retires GUI values and reciprocal history retains identity`() = runBlocking {
        val f = fixture()
        val native = f.editor.doorEntries.single()
        f.project.getOrCreateRoom(0x91F8).doorChanges += DoorChange(0, native.destRoomPtr, native.bitflag, native.doorCapCode, 1, 1, 0x8000, 0)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.doors.sourceText()
        val revision = f.workspace.sourceBufferRevision
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS, door = 0)), f.editor.statusMessage)
        val preview = assertNotNull(f.workspace.roomSourceInspection)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(source, f.bridge.doors.sourceText())
        assertContains(preview.text, "Door cap tile X, Y (not an ASM pointer)")
        preview.startEditing()
        assertEquals(1, f.room.asmDoorSourceVersion)
        assertTrue(f.room.doorChanges.isEmpty())
        assertEquals(0x8000, f.editor.doorEntries.single().doorDefPtr)
        assertEquals(1, f.input().entries.single().change.screenY)
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains("asmDoorProjection"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.room.asmDoorSourceVersion)
        assertEquals(1, f.room.doorChanges.single().screenY)
        assertEquals(source, f.bridge.doors.sourceText())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(1, f.room.asmDoorSourceVersion)
        assertTrue(f.room.doorChanges.isEmpty())
    }

    @Test fun `raw fields and GUI fields share one source with comments CRLF isolation and undo`() = runBlocking {
        val f = fixture(); f.adopt()
        val original = f.bridge.doors.sourceText()
        val noted = original.replace("; Distance (", "; personal note · Distance (").replace("\n", "\r\n")
        val raw = noted.replace("dw ${'$'}0000 ; personal note", "dw ${'$'}1234 ; personal note")
        assertNotEquals(noted, raw)
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, original, raw)
        f.editor.refreshLinkedPlms()
        assertEquals(0x1234, f.editor.doorEntries.single().distFromDoor)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x2345))
        val changed = f.bridge.doors.sourceText()
        assertContains(changed, "dw ${'$'}2345 ; personal note")
        assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(changed))
        assertEquals(0x8000, f.editor.doorEntries.single().doorDefPtr)
        assertEquals(0x2345, f.input().entries.single().change.distFromDoor)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(raw, f.bridge.doors.sourceText())
        assertEquals(0x1234, f.editor.doorEntries.single().distFromDoor)
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(0x2345, f.editor.doorEntries.single().distFromDoor)
        assertTrue(f.room.doorChanges.isEmpty())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val cold = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { reopened }
        assertEquals(f.input(), cold.doors.view(0x91F8).resource)
    }

    @Test fun `invalid door drafts keep a last valid preview block writes and survive saving while unrelated data stays editable`() = runBlocking {
        val f = fixture(); f.adopt()
        assertTrue(f.editor.ensureRoomPropertiesSource("Landing Site"))
        val valid = f.bridge.doors.sourceText()
        val broken = valid.replace("dw ${'$'}0000 ; Arrival ASM", "dw ${'$'}0042 ; Arrival ASM")
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, valid, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.doorPreviewProblem)
        assertEquals(0, f.editor.doorEntries.single().entryCode)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(broken, f.bridge.doors.sourceText())
        assertFails { f.input() }
        assertTrue(f.editor.roomPropertiesPreviewAvailable())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { f.project }
        assertNull(cold.doors.view(0x91F8).resource)
        assertNotNull(cold.doors.view(0x91F8).problem)
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, broken, valid)
        f.editor.refreshLinkedPlms()
        assertNull(f.editor.doorPreviewProblem)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(0x1234, f.input().entries.single().change.distFromDoor)
    }

    @Test fun `retargeting all connection fields is reciprocal and exports exact data without forcing a reverse link`() = runBlocking {
        val f = fixture(); f.adopt()
        val changed = f.editor.doorEntries.single().copy(destRoomPtr = 0xA000, bitflag = 0x0500, doorCapCode = 0x1A15,
            screenX = 1, screenY = 1, distFromDoor = 0xF234, entryCode = 0x9000)
        f.editor.updateDoor(0, changed)
        assertEquals(changed, f.input().entries.single().entry())
        val target = f.parser.copyRomData()
        ProjectRoomExporter(f.project, f.parser, target, sourceDoors = mapOf(0x91F8 to f.input())).exportRooms()
        assertEquals(changed, RomParser(target).parseDoorEntry(f.input().doorOutPtr, 0), "No implicit cap, flags or arrival-code normalization")
        assertTrue(RomParser(target).parseDoorList(f.parser.readRoomHeader(0xA000)!!.doorOut).isEmpty(), "One-way links are valid")
        val text = f.bridge.doors.sourceText()
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, text, text.replace("dw ${'$'}A000", "dw ${'$'}91F8"))
        f.editor.refreshLinkedPlms()
        assertEquals(changed.copy(destRoomPtr = 0x91F8), f.editor.doorEntries.single())
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(changed, f.editor.doorEntries.single())
    }

    @Test fun `malformed widths duplicates expressions invalid targets and competing ownership reject without rewriting source`() = runBlocking {
        val f = fixture(); f.adopt()
        val text = f.bridge.doors.sourceText()
        for (draft in listOf(text + authoredMacro(text), text + authoredMacro(text).replace("Door_0", "Door_1"), text.replace("dw ${'$'}91F8", "db ${'$'}91F8"),
            text.replace("dw ${'$'}91F8", "dw ${'$'}FFFF"), text.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}10000 ; Distance"),
            text.replace("dw ${'$'}0000 ; Distance", "dw 1+2 ; Distance"), text.replace("db ${'$'}00, ${'$'}00 ; Arrival screen", "db ${'$'}02, ${'$'}00 ; Arrival screen"))) {
            assertFails(draft.takeLast(100)) { AsmDoorSource(draft).resource(f.room, f.parser) }
        }
        f.room.doorChanges += DoorChange(0, 0x91F8, 0, 0, 0, 0, 0, 0)
        assertContains(assertFails { f.input() }.message.orEmpty(), "competing")
        assertEquals(text, f.bridge.doors.sourceText())
    }
    private fun authoredMacro(text: String) = "macro ${AsmDoorSource.name(0x91F8, 0)}()\n" + authoredMacroBody(text, AsmDoorSource.name(0x91F8, 0)) + "endmacro\n"

    @Test fun `a stale visual field edit cannot overwrite a newer ASM row`() = runBlocking {
        val f = fixture(); f.adopt()
        val old = f.editor.doorEntries.single()
        val before = f.bridge.doors.sourceText()
        val raw = before.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}1111 ; Distance")
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, before, raw)
        // Deliberately do not refresh the canvas first: this is the asynchronous UI edge.
        f.editor.updateDoor(0, old.copy(distFromDoor = 0x2222))
        assertEquals(raw, f.bridge.doors.sourceText())
        assertEquals(0x1111, f.editor.doorEntries.single().distFromDoor)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x2222))
        assertEquals(0x2222, f.input().entries.single().change.distFromDoor)
    }

    @Test fun `native exceptional entrances survive adoption but new geometry and dimension changes are validated`() = runBlocking {
        val f = fixture(nativeOutside = true); f.adopt()
        assertEquals(2, f.input().entries.single().change.screenX)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(0x1234, f.input().entries.single().change.distFromDoor)
        val before = f.bridge.doors.sourceText()
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(screenX = 3))
        assertEquals(before, f.bridge.doors.sourceText(), "New out-of-bounds geometry must not inherit a native exception")
        assertFails { AsmDoorSource(before).resource(f.room, f.parser) { 1 to 2 } }
        f.room.roomHeaderChange = RoomHeaderChange(width = 1)
        assertTrue(f.bridge.doors.view(0x91F8).problem != null, "Target dimension changes must invalidate the cached view too")
    }

    @Test fun `shared native definitions and invalid first edits fail before source or metadata adoption`() = runBlocking {
        val f = fixture(shared = true)
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val text = f.bridge.doors.sourceText()
        assertFalse(f.editor.ensureDoorSource("Landing Site"))
        assertContains(f.editor.statusMessage, "shared")
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(text, f.bridge.doors.sourceText())
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x10000))
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(text, f.bridge.doors.sourceText())
    }

    @Test fun `source export owns complete definitions and connection pointers and retains literal fields without normalization`() = runBlocking {
        val f = fixture(); f.adopt()
        val entry = f.editor.doorEntries.single().copy(bitflag = 0x0080, distFromDoor = 0x1234)
        f.editor.updateDoor(0, entry)
        val target = f.parser.copyRomData()
        val result = ProjectRoomExporter(f.project, f.parser, target, sourceDoors = mapOf(0x91F8 to f.input())).exportRooms()
        assertEquals(entry, RomParser(target).parseDoorEntry(f.input().doorOutPtr, 0))
        assertTrue(result.sourceDoorClaims.contains(0x18000 to 12))
        assertTrue(result.sourceDoorClaims.contains(0x78060 to 2))
        assertTrue(result.sourceDoorClaims.contains(0x79201 to 2))
        val table = AsmRoomDoorTable(AsmDoorSource.name(0x91F8, 0), f.input().entries.single())
        assertContentEquals(target.copyOfRange(0x18000, 0x1800C), table.claim().bytes)
        assertContains(table.renderSource(), "%SmeditDoors_91F8_Door_0()")
        assertEquals(AsmEditorTarget.Room(0x91F8), AsmDoorSource.targetAt(f.bridge.doors.sourceText().lines(), f.bridge.doors.line(0x91F8) + 3))
        assertFalse(f.editor.resizeRoom(2, 2, 3, 2), "An owned self/incoming door needs a joint resize transaction")
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0, f.project.rooms.getValue("91F8").asmDoorSourceVersion)
        assertFalse(f.bridge.doors.sourceText().contains("macro SmeditDoors_91F8_"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(1, f.project.rooms.getValue("91F8").asmDoorSourceVersion)
        assertEquals(entry, f.editor.doorEntries.single())
    }

    @Test fun `native connection identities and known arrival anchors must survive the initial assembly`() = runBlocking {
        val f = fixture(); f.adopt()
        val original = f.parser.copyRomData()
        val native = f.input()
        val source = native.copy(entries = native.entries.map { it.copy(change = it.change.copy(entryCode = 0x9000)) })
        val sources = mapOf(0x91F8 to source)
        val anchors = mapOf("DoorASM_Test" to 0x9000)
        fun compiled(bytes: ByteArray = original, symbols: String = "[labels]\n8F:9000 DoorASM_Test\n") =
            AsmCompiledBuildBase(bytes, original, "fixture", emptyList(), assemblerVersion = "test", compilerOutput = emptyList(),
                compilerSymbols = symbols.toByteArray())
        protectDoorSourceBaseline(sources, f.parser, compiled(), anchors)
        for (symbols in listOf("[labels]\n8F:9001 DoorASM_Test\n", "[labels]\n")) {
            assertContains(assertFails { protectDoorSourceBaseline(sources, f.parser, compiled(symbols = symbols), anchors) }.message.orEmpty(), "moved or was removed")
        }
        val movedList = original.copyOf().also { it[0x78060] = 2 }
        assertContains(assertFails { protectDoorSourceBaseline(sources, f.parser, compiled(movedList), anchors) }.message.orEmpty(), "identity")
        val competingFields = original.copyOf().also { it[0x18008] = 1 }
        assertContains(assertFails { protectDoorSourceBaseline(sources, f.parser, compiled(competingFields), anchors) }.message.orEmpty(), "original bank_83")
        assertContentEquals(original, f.parser.copyRomData(), "Baseline validation is read-only")
    }

    @Test fun `older map history cannot restore retired GUI door overrides over authored connections`() = runBlocking {
        val f = fixture()
        val d = f.editor.doorEntries.single()
        f.project.getOrCreateRoom(0x91F8).doorChanges += DoorChange(0, d.destRoomPtr, d.bitflag, d.doorCapCode, d.screenX, d.screenY, 0x1234, d.entryCode)
        val map = MinimapEditorState()
        map.initIfNeeded(f.parser, f.editor)
        map.selectedTile = if (map.mapData.getTile(0, 0) == 1) 2 else 1
        map.paintTile(0, 0, f.editor)
        assertTrue(map.undoStack.isNotEmpty())
        f.adopt()
        val before = Json.encodeToString(RoomEdits.serializer(), f.room)
        val mapBefore = map.mapData.tiles.copyOf()
        val source = f.bridge.doors.sourceText()
        val history = map.undoStack.toList()
        map.undo(f.editor) // Direct shortcut before the map has remounted/refreshed its external-edit version.
        assertEquals(before, Json.encodeToString(RoomEdits.serializer(), f.room))
        assertContentEquals(mapBefore, map.mapData.tiles, "A blocked restore must not undo only the map half")
        assertEquals(source, f.bridge.doors.sourceText())
        assertEquals(history, map.undoStack)
        assertTrue(f.room.doorChanges.isEmpty())
    }

    @Test fun `native writable room door tables fit the literal codec without imposing reciprocal topology`() {
        val parser = RomParser(TestRomHelper.requireRomBytes())
        var covered = 0
        for (info in parser.roomCatalog.rooms) {
            val header = parser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
            if (header.area !in 0..6) continue
            val native = parser.parseDoorList(header.doorOut)
            if (native.isEmpty()) continue
            val resource = runCatching { SourceRoomDoors(header.doorOut, native.mapIndexed { index, door -> SourceRoomDoor(door.doorDefPtr,
                DoorChange(index, door.destRoomPtr, door.bitflag, door.doorCapCode, door.screenX, door.screenY, door.distFromDoor, door.entryCode)) }) }
                .getOrElse { throw AssertionError("Native room ${info.id}: $native", it) }
            val room = RoomEdits(header.roomId, asmDoorSourceVersion = 1)
            val text = AsmDoorSource.render(header.roomId, header.name, resource)
            val decoded = runCatching { AsmDoorSource(text).resource(room, parser) }.getOrElse { problem ->
                throw AssertionError("Native room ${info.id}: $resource", problem)
            }
            assertEquals(resource, decoded, "Native room ${info.id}")
            covered++
        }
        assertTrue(covered > 200, "The fixture must cover the native room population, not an empty catalog")
    }

    @Test fun `managed arrivals create one cross bank source transaction without guessed addresses or duplicate model values`() = runBlocking {
        val f = fixture(); f.adopt()
        val old83 = f.bridge.doors.sourceText()
        val old8F = f.workspace.projectSourceText(PLM_SOURCE_FILE)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.openArrival(0, create = true), f.editor.statusMessage)
        val arrival = assertNotNull(f.input().entries.single().arrival)
        assertEquals("SmeditArrival_91F8_Door_0_Entry", arrival.label)
        assertNull(arrival.snesAddress)
        assertEquals(0, f.editor.doorEntries.single().entryCode, "Zero is only a preview placeholder, never an exportable address")
        assertEquals(arrival, f.editor.doorArrivalBindings[0])
        assertFails { f.input().entries.single().bytes() }
        assertContains(assertFails { f.editor.exportRoomToJson(0x91F8, f.parser) }.message.orEmpty(), "cannot carry symbolic arrival code")
        assertFails { ProjectRoomExporter(f.project, f.parser, f.parser.copyRomData(), sourceDoors = mapOf(0x91F8 to f.input())).exportRooms() }
        val new83 = f.bridge.doors.sourceText(); val new8F = f.workspace.projectSourceText(PLM_SOURCE_FILE)
        assertFalse(f.editor.createManagedDoorArrival(0), "Repeated actions must not recreate or duplicate a managed body")
        assertEquals(new83, f.bridge.doors.sourceText()); assertEquals(new8F, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertContains(new83, "dw ${arrival.label}")
        assertContains(new8F, "rts ; No previous arrival behavior")
        assertEquals(PLM_SOURCE_FILE, f.workspace.selectedFileId)
        assertEquals(AsmDoorArrivalsSource(new8F).line(arrival.label), f.workspace.selectedLineIndex)
        val revision = f.workspace.sourceBufferRevision
        assertTrue(navigation.openArrival(0), f.editor.statusMessage)
        assertEquals(revision, f.workspace.sourceBufferRevision, "Navigation must not rebuild or recreate a routine")
        val word = "dw ${arrival.label}"
        assertTrue(com.supermetroid.editor.ui.asmEditableReferenceIsNavigable(f.workspace.workspace?.index, DOOR_SOURCE_FILE,
            word, word.indexOf(arrival.label), 0, state = f.workspace))
        assertTrue(com.supermetroid.editor.ui.asmOpenEditableReference(f.workspace, f.workspace.workspace?.index, DOOR_SOURCE_FILE,
            word, word.indexOf(arrival.label), 0), "Go to definition must work from unsaved symbolic data before a build")
        assertEquals(PLM_SOURCE_FILE, f.workspace.selectedFileId)
        assertEquals(AsmDoorArrivalsSource(new8F).line(arrival.label), f.workspace.selectedLineIndex)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains(arrival.label))
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(arrival, f.input().entries.single().arrival)
        assertEquals(0x1234, f.input().entries.single().change.distFromDoor)
        assertTrue(f.editor.undo()); assertEquals(new83, f.bridge.doors.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(old83, f.bridge.doors.sourceText()); assertEquals(old8F, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(new83, f.bridge.doors.sourceText()); assertEquals(new8F, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(old83, f.bridge.doors.sourceText()); assertTrue(f.editor.doorArrivalBindings.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(new8F, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(f.input(), AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { reopened }.doors.view(0x91F8).resource)
    }

    @Test fun `explicit arrival creation automatically adopts door data in the same reversible cross bank action`() = runBlocking {
        val f = fixture(nativeArrival = 0x9000)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val old83 = f.bridge.doors.sourceText(); val old8F = f.bridge.sourceText()
        assertFalse(f.editor.hasManagedDoors())
        assertTrue(navigation.openArrival(0, create = true), f.editor.statusMessage)
        assertTrue(f.editor.hasManagedDoors()); assertEquals(1, f.editor.undoStack.size)
        val new83 = f.bridge.doors.sourceText(); val new8F = f.bridge.sourceText()
        assertContains(new8F, "jmp.w DoorASM_Test")
        assertContains(new83, "dw SmeditArrival_91F8_Door_0_Entry")
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(old83, f.bridge.doors.sourceText()); assertEquals(old8F, f.bridge.sourceText())
        assertEquals(0x9000, f.editor.doorEntries.single().entryCode)
        assertTrue(f.editor.undoStack.isEmpty()); assertEquals(1, f.editor.redoStack.size)
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(new83, f.bridge.doors.sourceText()); assertEquals(new8F, f.bridge.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(new83, f.bridge.doors.sourceText()); assertEquals(new8F, f.bridge.sourceText())
    }

    @Test fun `unsupported automatic door and arrival actions leave source metadata and history untouched`() = runBlocking {
        val f = fixture(nativeArrival = 0x9000, knownArrival = false)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val old83 = f.bridge.doors.sourceText(); val old8F = f.bridge.sourceText()
        val revision = f.workspace.sourceBufferRevision
        assertFalse(f.editor.createManagedDoorArrival(0))
        assertContains(f.editor.statusMessage, "no verified native label")
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(old83, f.bridge.doors.sourceText()); assertEquals(old8F, f.bridge.sourceText())
        assertEquals(revision, f.workspace.sourceBufferRevision); assertTrue(f.editor.undoStack.isEmpty())
        assertFalse(f.workspace.canUndoSource(DOOR_SOURCE_FILE)); assertFalse(f.workspace.canUndoSource(PLM_SOURCE_FILE))
        val collision = old83 + "  MACRO\tSmeditDoors_91F8_Door_0()\nendmacro\n"
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, old83, collision)
        val collisionRevision = f.workspace.sourceBufferRevision
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertFalse(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS)))
        assertContains(f.editor.statusMessage, "Retained door declarations")
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(collision, f.bridge.doors.sourceText()); assertEquals(collisionRevision, f.workspace.sourceBufferRevision)
        assertTrue(f.editor.undoStack.isEmpty())
    }

    @Test fun `opaque arrival code does not lock reciprocal connection fields and returning to a literal unlocks the address control`() = runBlocking {
        val f = fixture(); f.adopt(); assertTrue(f.editor.createManagedDoorArrival(0), f.editor.statusMessage)
        val before = f.workspace.projectSourceText(PLM_SOURCE_FILE)
        val draft = before.replace("rts ; No previous arrival behavior.", "not_an_instruction ; unfinished custom code")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, draft)
        f.editor.refreshLinkedPlms()
        assertNull(f.editor.doorPreviewProblem)
        assertFails { AsmDoorArrivalsSource(draft).routines() }
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x1234))
        assertEquals(0x1234, f.input().entries.single().change.distFromDoor)
        val data = f.bridge.doors.sourceText()
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(entryCode = 0x9000))
        assertEquals(data, f.bridge.doors.sourceText(), "A numeric GUI field must not silently replace a symbolic target")
        assertEquals(draft, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val missing = data.replace("dw SmeditArrival_91F8_Door_0_Entry", "dw SmeditArrival_91F8_Door_99_Entry")
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, data, missing)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.doorPreviewProblem)
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(distFromDoor = 0x2222))
        assertEquals(missing, f.bridge.doors.sourceText(), "A missing binding blocks stale GUI writes, unlike an unfinished executable body")
        assertNull(AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { f.project }.doors.view(0x91F8).resource)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms(); assertNull(f.editor.doorPreviewProblem)
        val literal = data.replace("dw SmeditArrival_91F8_Door_0_Entry", "dw ${'$'}0000")
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, data, literal)
        f.editor.refreshLinkedPlms()
        assertTrue(f.editor.doorArrivalBindings.isEmpty())
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(entryCode = 0x9000))
        assertEquals(0x9000, f.input().entries.single().change.entryCode)
        assertFails { AsmDoorArrivalsSource(f.workspace.projectSourceText(PLM_SOURCE_FILE)).routines() } // Unbinding does not silently delete authored code.
    }

    @Test fun `native arrival scaffolding preserves behavior with a symbolic tail jump and reset has reciprocal history`() = runBlocking {
        val f = fixture(nativeArrival = 0x9000); f.adopt()
        assertTrue(f.editor.createManagedDoorArrival(0), f.editor.statusMessage)
        val root = f.workspace.projectSourceText(PLM_SOURCE_FILE)
        assertContains(root, "jmp.w DoorASM_Test")
        assertContains(root, "assert (DoorASM_Test>>16) == ${'$'}8F")
        assertFalse(root.contains("No previous arrival behavior"))
        val data = f.bridge.doors.sourceText()
        assertEquals(AsmEditorTarget.Room(0x91F8), AsmDoorArrivalsSource.targetAt(root.lines(), AsmDoorArrivalsSource(root).line(f.input().entries.single().arrival!!.label) + 2))
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertFalse(f.workspace.projectSourceText(PLM_SOURCE_FILE).contains("macro SmeditArrival_"))
        assertContains(f.workspace.projectSourceText(PLM_SOURCE_FILE), "DoorASM_Test:")
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(root, f.workspace.projectSourceText(PLM_SOURCE_FILE)); assertEquals(data, f.bridge.doors.sourceText())
        val retained = resetRoomSource(f.room, mapOf(PLM_SOURCE_FILE to root, DOOR_SOURCE_FILE to data),
            sequenceOf("jsr.w SmeditArrival_91F8_Door_0_Entry"))
        assertContains(retained.getValue(PLM_SOURCE_FILE), "macro SmeditArrival_91F8_Door_0()")
    }

    @Test fun `unknown original arrival code is never replaced by an automatic empty scaffold`() = runBlocking {
        val f = fixture(nativeArrival = 0x9000, knownArrival = false); f.adopt()
        val before = f.bridge.doors.sourceText(); val root = f.workspace.projectSourceText(PLM_SOURCE_FILE)
        val history = f.editor.undoStack.toList()
        assertFalse(f.editor.createManagedDoorArrival(0))
        assertContains(f.editor.statusMessage, "no verified native label")
        assertEquals(before, f.bridge.doors.sourceText()); assertEquals(root, f.workspace.projectSourceText(PLM_SOURCE_FILE))
        assertEquals(history, f.editor.undoStack)
    }

    @Test fun `scroll diagnostics follow authored targets without presenting native effects as custom code simulation`() = runBlocking {
        val f = fixture(); f.adopt()
        assertFalse(com.supermetroid.editor.ui.doorArrivalScrollPreview(0x91F8, f.parser, f.editor).hasSourceManagedEffects)
        assertTrue(f.editor.createManagedDoorArrival(0), f.editor.statusMessage)
        val preview = com.supermetroid.editor.ui.doorArrivalScrollPreview(0x91F8, f.parser, f.editor)
        assertTrue(preview.hasSourceManagedEffects); assertTrue(preview.analyses.isEmpty())
        f.editor.updateDoor(0, f.editor.doorEntries.single().copy(destRoomPtr = 0xA000))
        assertFalse(com.supermetroid.editor.ui.doorArrivalScrollPreview(0x91F8, f.parser, f.editor).hasSourceManagedEffects)
        assertTrue(com.supermetroid.editor.ui.doorArrivalScrollPreview(0xA000, f.parser, f.editor).hasSourceManagedEffects)
    }
}
