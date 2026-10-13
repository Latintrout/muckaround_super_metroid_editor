package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmPlmSourceBridgeTest {
    @TempDir lateinit var directory: File
    private val source = "PLMPopulation_Test: ;8F8000;\n    %PLMPopEntry(${ '$' }EEDB, ${ '$' }02, ${ '$' }03, ${ '$' }0005) ; pickup\n    dw ${ '$' }0000\n"
    private val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"

    private suspend fun fixture(withLevelData: Boolean = false): Triple<AsmWorkspaceState, AsmPlmSourceBridge, SmEditProject> {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, PLM_SOURCE_FILE).writeText(source)
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val projectFile = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(projectFile.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(projectFile.path, true)
        val rom = objectSourceTestRom()
        if (withLevelData) {
            val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 } // 2048 layer-1 bytes + BTS
            LZ5Compressor.compress(level).copyInto(rom, 0x200000)
            for (pc in listOf(0x791F8 + 18, 0x79300)) {
                rom[pc] = 0; rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            }
        }
        val bytes = RomParser.serializePlmSet(listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5)))
        bytes.forEachIndexed { i, value -> rom[0x78000 + i] = value.toByte() }
        val consumers = mapOf(0x8000 to RomParser(rom).findAllStateDataOffsets(0x91F8).mapIndexed { index, pc ->
            AsmPlmConsumer(0x91F8, "Landing Site", index, pc)
        })
        val catalog = AsmPlmSourceCatalog(source, macros, emptyMap(), RomParser(rom), consumers)
        val project = SmEditProject("test.sfc")
        project.asmWorkspace.enabled = true
        project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        return Triple(workspace, AsmPlmSourceBridge(catalog, workspace) { project }, project)
    }

    @Test fun `migration has atomic source undo redo and persists values only in source`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val edits = project.getOrCreateRoom(0x91F8)
        val addition = PlmChange("add", 0xEEDF, 8, 9, 12)
        edits.plmChanges += addition
        bridge.migrateRoom(0x91F8, "Landing Site")
        assertTrue(bridge.isManaged(0x91F8))
        assertTrue(edits.plmChanges.isEmpty())
        val entries = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries
        assertEquals(2, entries.size)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertFalse(bridge.isManaged(0x91F8))
        assertEquals(listOf(addition), edits.plmChanges)
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(bridge.isManaged(0x91F8))
        assertTrue(edits.plmChanges.isEmpty())
        assertEquals(entries, bridge.managedView(0x91F8, "state-1").document?.entries)
        assertTrue(workspace.saveAllSources())
        val json = Json { encodeDefaults = true }
        val restoredProject = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { restoredProject }
        assertEquals(entries, restored.managedView(0x91F8, "state-1").document?.entries)
        assertTrue(restoredProject.rooms.getValue("91F8").plmChanges.isEmpty())
        assertTrue(restored.catalog.sourceBindings(restoredProject, reopened.projectSourceText(PLM_SOURCE_FILE), macros).isEmpty())
    }

    @Test fun `cached projections stay live in both directions and unrelated source edits reuse the room model`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        bridge.preloadProjections()
        val initial = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        workspace.updateSourceEditText("main.asm", workspace.projectSourceText("main.asm") + "; unrelated edit\n")
        assertSame(initial, bridge.managedView(0x91F8, "state-1").document)
        bridge.editManaged(0x91F8, "state-1", true, { false }, RomParser.PlmEntry(0xEF0B, 8, 9, 12))
        val gui = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        assertEquals(2, gui.entries.size)
        val changed = gui.entries.last().copy(id = 0xEEE7)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, gui.replace(1, changed))
        assertEquals(changed, bridge.managedView(0x91F8, "state-1").document!!.entries.last())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        restored.preloadProjections()
        val saved = assertNotNull(restored.managedView(0x91F8, "state-1").document)
        assertEquals(changed, saved.entries.last())
        reopened.updateSourceEditText(PLM_SOURCE_FILE, saved.text.replace("    dw", "    invalid draft\n    dw"))
        val invalid = restored.managedView(0x91F8, "state-1")
        assertNotNull(invalid.problem)
        assertSame(saved, invalid.document)
        assertIs<AsmPlmPlacementTarget.Unavailable>(restored.managedPlacement(0x91F8, "state-1", changed))
        reopened.awaitSourceIndexing()
    }

    @Test fun `partial indexes and stale clean buffers do not hide external helper consumers`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val entry = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries.single()
        val helper = "SmeditObjects_91F8_Common_9"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(entry), "%$helper()") +
            "\nmacro $helper()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        // Seed a clean buffer, then modify its authored file externally without updating the mirror.
        workspace.projectSourceText("main.asm")
        val root = assertNotNull(workspace.projectWorkspace).root
        File(root, "overrides/src/main.asm").apply { parentFile.mkdirs(); writeText("; external caller mentions $helper\n") }
        bridge.editManaged(0x91F8, "state-1", true, { it == entry }, null)
        assertTrue(AsmRoomObjectSource(bridge.sourceText()).contains(helper))
        assertTrue(workspace.saveAllSources())
        val partial = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        partial.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val next = AsmPlmSourceBridge(bridge.catalog, partial) { project }
        next.editManaged(0x91F8, "state-1", true, { false }, null)
        assertTrue(AsmRoomObjectSource(next.sourceText()).contains(helper))
        partial.awaitSourceIndexing()
    }

    @Test fun `source-only room edits keep invalid draft visible and never resume independent GUI deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val before = bridge.sourceText()
        val extra = RomParser.PlmEntry(0xEEDF, 8, 9, 12)
        bridge.editManaged(0x91F8, "state-1", false, { false }, extra)
        val after = bridge.sourceText()
        assertEquals(2, bridge.managedView(0x91F8, "state-1").document?.entries?.size)
        assertEquals(1, bridge.managedView(0x91F8, "state-2").document?.entries?.size)
        bridge.undoSource(after)
        assertEquals(before, bridge.sourceText())
        bridge.redoSource(before)
        assertEquals(after, bridge.sourceText())
        assertTrue(project.rooms.getValue("91F8").states.all { it.plmChanges.isEmpty() })
        workspace.updateSourceEditText(PLM_SOURCE_FILE, after.replace("${'$'}EEDF", "unknown"))
        assertNotNull(bridge.managedView(0x91F8, "state-1").problem)
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.managedPlacement(0x91F8, "state-1", extra))
        assertFails { bridge.editManaged(0x91F8, "state-1", false, { false }, extra) }
    }

    @Test fun `legacy single-state cleanup is one undo step and survives save reopen`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        val state = room.states.single().id
        bridge.migrateRoom(room.roomId, "Test room")
        val entry = assertNotNull(bridge.managedView(room.roomId, state).document).entries.single()
        val simple = bridge.sourceText()
        val name = "SmeditObjects_91F8_Common_5"
        val legacy = simple.replace("    ${AsmRoomObjectSource.renderEntry(entry)} ; PLM:", "    %$name() ; PLM:") +
            "\nmacro $name()\n    ${AsmRoomObjectSource.renderEntry(entry)} ; authored note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_2()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        val document = assertNotNull(bridge.managedView(room.roomId, state).document)
        assertEquals(legacy, bridge.sourceText(), "Reading legacy source must not rewrite it")
        bridge.updateManaged(room.roomId, state, document, 0, entry.copy(x = 7))
        val cleaned = bridge.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "authored note")
        assertTrue(bridge.managedIsInAllStates(room.roomId, state, entry.copy(x = 7)), "A sole state needs no common helper to show All states")
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(room.roomId, state, entry.copy(x = 7)))
        assertContains(cleaned.lines()[target.lineIndex], AsmRoomObjectSource.renderEntry(entry.copy(x = 7)))
        bridge.undoSource(cleaned)
        assertEquals(legacy, bridge.sourceText())
        bridge.redoSource(legacy)
        assertEquals(cleaned, bridge.sourceText())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertEquals(cleaned, restored.sourceText())
        assertEquals(listOf(entry.copy(x = 7)), restored.managedView(room.roomId, state).document?.entries)
    }

    @Test fun `project save tidies already saved legacy helpers without requiring a GUI placement edit`() = runBlocking {
        val (_, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val entry = assertNotNull(bridge.managedView(room.roomId, room.states.single().id).document).entries.single()
        val common = "SmeditObjects_91F8_Common_5"
        val legacy = bridge.sourceText().replace("    ${AsmRoomObjectSource.renderEntry(entry)} ; PLM:", "    %$common() ; PLM:") +
            "\nmacro $common()\n    ${AsmRoomObjectSource.renderEntry(entry)} ; my note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_2()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        // Reproduce an older project whose canonical source already contains the leftovers.
        AsmProjectWorkspaceRepository(emptyList(), emptyList()).saveSource(File(directory, "Test.smedit").path, PLM_SOURCE_FILE, legacy)
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertFalse(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(reopened.saveAllSources())
        val cleaned = restored.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "my note")
        assertEquals(listOf(entry), restored.managedView(room.roomId, room.states.single().id).document?.entries)
        restored.undoSource(cleaned)
        assertEquals(legacy, restored.sourceText())
        restored.redoSource(legacy)
        assertEquals(cleaned, restored.sourceText())
        // Saving an invalid draft remains possible: cleanup cannot manufacture a replacement.
        val invalid = cleaned.replace("    dw", "    v\n    dw")
        reopened.updateSourceEditText(PLM_SOURCE_FILE, invalid)
        assertTrue(reopened.saveSource(PLM_SOURCE_FILE))
        assertEquals(invalid, restored.sourceText())
    }

    @Test fun `stray text in a managed room blocks GUI placement without losing source then recovers`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val editor = EditorState().apply {
            testMode = true
            initForProject(File(directory, "Test.smedit"), project)
            asmPlmSourceBridge = bridge
            loadRoom(room.roomId, bridge.catalog.parser, assertNotNull(bridge.catalog.parser.readRoomHeader(room.roomId)))
            initTestLevel(32, 32)
        }
        editor.refreshLinkedPlms()
        val before = editor.workingPlms.toList()
        val valid = bridge.sourceText()
        val list = AsmRoomObjectSource(valid).table(room.roomId, room.states.single().id).resource
        val broken = valid.replaceRange(list.bodyStart, list.bodyStart, "    v\n")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, broken)
        editor.addPlm(0xEF23, 8, 9, 0)
        assertEquals(broken, bridge.sourceText())
        assertEquals(before, editor.workingPlms)
        assertContains(editor.statusMessage, "unsupported object source: v")
        assertContains(editor.statusMessage, "Object edit blocked: bank_8F.asm:${valid.take(list.bodyStart).count { it == '\n' } + 1}:")
        assertTrue(editor.statusIsError)
        assertTrue(editor.undoStack.isEmpty(), "Rejected placements must not enter undo history")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, valid)
        editor.addPlm(0xEF23, 8, 9, 0)
        assertTrue(editor.workingPlms.any { it.id == 0xEF23 && it.x == 8 && it.y == 9 })
        assertFalse(editor.statusIsError, "A successful placement clears the old blocker")
    }

    @Test fun `saving legacy multi-state objects produces direct rows with local ASM and explicit all-state GUI edits`() = runBlocking {
        val (_, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val room = project.rooms.getValue("91F8")
        val first = room.states.first().id
        val second = room.states.last().id
        val local = RomParser.PlmEntry(0xEEE7, 8, 9, 12)
        bridge.editManaged(room.roomId, first, false, { false }, local)
        val gravity = RomParser.PlmEntry(0xEF0B, 0x38, 0x49, 0x57)
        bridge.editManaged(room.roomId, first, true, { false }, gravity)
        val expected = room.states.associate { it.id to bridge.managedView(room.roomId, it.id).document!!.entries }
        val helper = "SmeditObjects_91F8_Common_2"
        val legacy = bridge.sourceText()
            .replace("; Values live here. Each list contains its PLMPopEntry rows; state wrappers select lists.",
                "; Values live here. State wrappers share lists; Common macros are All states items.")
            .replace(AsmRoomObjectSource.renderEntry(gravity), "%$helper()") +
            "\nmacro $helper()\n    ${AsmRoomObjectSource.renderEntry(gravity)} ; my shared pickup note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_99()\n    ${AsmRoomObjectSource.renderEntry(local)}\nendmacro\n"
        val path = File(directory, "Test.smedit").path
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.saveSource(path, PLM_SOURCE_FILE, legacy)
        val reopened = AsmWorkspaceState(projectRepository = repository)
        reopened.bindProject(path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertFalse(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(reopened.saveAllSources())
        val cleaned = restored.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "; Values live here. Each list contains its PLMPopEntry rows; state wrappers select lists.")
        assertEquals(2, cleaned.lineSequence().count {
            it.contains("%PLMPopEntry(${'$'}EF0B, ${'$'}38, ${'$'}49, ${'$'}0057) ; PLM: Gravity Suit (Visible)")
        })
        for (state in room.states) {
            assertEquals(expected[state.id], restored.managedView(room.roomId, state.id).document?.entries)
            assertTrue(restored.managedIsInAllStates(room.roomId, state.id, gravity))
            val target = assertIs<AsmPlmPlacementTarget.Source>(restored.managedPlacement(room.roomId, state.id, gravity))
            assertContains(cleaned.lines()[target.lineIndex], AsmRoomObjectSource.renderEntry(gravity))
        }
        restored.undoSource(cleaned)
        assertEquals(legacy, restored.sourceText())
        restored.redoSource(legacy)
        assertEquals(cleaned, restored.sourceText())

        // Equal direct rows are not secretly coupled: editing one list leaves the other untouched.
        val doc = assertNotNull(restored.managedView(room.roomId, first).document)
        val changed = gravity.copy(id = 0xEEF7)
        reopened.updateSourceEditText(PLM_SOURCE_FILE, doc.replace(doc.entries.indexOf(gravity), changed))
        assertTrue(restored.managedView(room.roomId, first).document!!.entries.contains(changed))
        assertTrue(restored.managedView(room.roomId, second).document!!.entries.contains(gravity))
        assertFalse(restored.managedIsInAllStates(room.roomId, first, changed))
        assertFalse(restored.managedIsInAllStates(room.roomId, second, gravity))

        val common = gravity.copy(param = 0x58)
        restored.editManaged(room.roomId, first, true, { it == gravity || it == changed }, common)
        for (state in room.states) {
            assertTrue(restored.managedIsInAllStates(room.roomId, state.id, common))
            assertEquals(1, restored.managedView(room.roomId, state.id).document!!.entries.count { it == common })
        }
        assertTrue(restored.managedView(room.roomId, first).document!!.entries.contains(local))
        assertFalse(restored.managedView(room.roomId, second).document!!.entries.contains(local))
        restored.cloneManagedState(room.roomId, second, "new-state")
        room.states += room.states.last().copy(id = "new-state")
        assertEquals(restored.managedView(room.roomId, second).document?.entries,
            restored.managedView(room.roomId, "new-state").document?.entries)
        assertFalse(restored.sourceText().contains("Common_"))
        assertTrue(reopened.saveAllSources())
    }

    @Test fun `disabled custom module unsaved references protect helpers until removed`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val entry = RomParser.PlmEntry(0xEEDF, 8, 9, 12)
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", entry.id, entry.x, entry.y, entry.param)
        bridge.migrateRoom(0x91F8, "Test room")
        val common = "SmeditObjects_91F8_Common_1"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(entry), "%$common()") +
            "\nmacro $common()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        assertTrue(workspace.createProjectModule("Test consumer"))
        val module = workspace.projectModuleFileIds.single()
        assertTrue(workspace.setProjectModuleEnabled(module, false))
        workspace.updateSourceEditText(module, "macro MyConsumer()\n    %$common()\nendmacro\n")
        bridge.editManaged(0x91F8, "state-1", true, { it == entry }, null)
        assertTrue(workspace.saveAllSources())
        assertTrue(AsmRoomObjectSource(bridge.sourceText()).contains(common))
        assertTrue(project.rooms.getValue("91F8").states.all { state ->
            bridge.managedView(0x91F8, state.id).document?.entries?.none { it == entry } == true
        })
        workspace.updateSourceEditText(module, "; custom reference removed\n")
        bridge.editManaged(0x91F8, "state-1", true, { false }, null)
        assertFalse(AsmRoomObjectSource(bridge.sourceText()).contains(common))
    }

    @Test fun `direct shared list scope survives a new state and becomes local after forking`() = runBlocking {
        val (_, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val original = room.states.single()
        val entry = assertNotNull(bridge.managedView(room.roomId, original.id).document).entries.single()
        assertTrue(bridge.managedIsInAllStates(room.roomId, original.id, entry))
        bridge.cloneManagedState(room.roomId, original.id, "new-state")
        room.states.add(original.copy(id = "new-state"))
        assertTrue(bridge.managedIsInAllStates(room.roomId, "new-state", entry))
        bridge.editManaged(room.roomId, "new-state", false, { it == entry }, entry.copy(id = 0xEEDF))
        assertFalse(bridge.managedIsInAllStates(room.roomId, "new-state", entry.copy(id = 0xEEDF)))
        assertEquals(listOf(entry), bridge.managedView(room.roomId, original.id).document?.entries)
    }

    @Test fun `managed comments use custom names and refresh together with GUI scalar edits and undo`() = runBlocking {
        val (_, bridge, project) = fixture()
        bridge.describePlm = { if (it.id == 0xF300) "Test pickup (Visible)" else RomParser.plmDisplayName(it.id, it.param) }
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xF300, 8, 9, 12)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val before = bridge.sourceText()
        assertTrue(before.startsWith(source)) // Original bank's handwritten comment is untouched.
        assertContains(before, "%PLMPopEntry(${'$'}F300, ${'$'}08, ${'$'}09, ${'$'}000C) ; PLM: Test pickup (Visible)")
        assertFalse(before.contains("Common_"))
        val document = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        val replacement = document.entries.last().copy(id = 0xEEF7)
        bridge.updateManaged(0x91F8, "state-1", document, document.entries.lastIndex, replacement)
        val after = bridge.sourceText()
        assertFalse(after.contains("Test pickup"))
        assertContains(after, "%PLMPopEntry(${'$'}EEF7, ${'$'}08, ${'$'}09, ${'$'}000C) ; PLM: Speed Booster (Visible)")
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(0x91F8, "state-2", replacement))
        assertContains(after.lines()[target.lineIndex], "%PLMPopEntry(${'$'}EEF7")
        assertEquals(after, bridge.sourceText()) // Reading/navigating never edits a bank.
        bridge.undoSource(after)
        assertEquals(before, bridge.sourceText())
        bridge.redoSource(before)
        assertEquals(after, bridge.sourceText())
    }

    @Test fun `existing uncommented authored rooms gain comments on a GUI edit not on navigation`() = runBlocking {
        val (workspace, bridge, _) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val legacy = bridge.sourceText().replace(Regex(" ; PLM: [^\\r\\n]*"), "")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        val entry = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries.single()
        assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(0x91F8, "state-1", entry))
        assertEquals(legacy, bridge.sourceText())
        bridge.editManaged(0x91F8, "state-1", false, { false }, entry.copy(id = 0xEEDF, x = 8))
        val after = bridge.sourceText()
        assertContains(after, "; PLM: Missile (Visible)")
        assertContains(after, "; PLM: Super Missile (Visible)")
        assertTrue(after.startsWith(source))
        bridge.undoSource(after)
        assertEquals(legacy, bridge.sourceText())
    }

    @Test fun `migration cannot overwrite original source edits or undo across changed state membership`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}04"))
        assertFails { bridge.migrateRoom(0x91F8, "Landing Site") }
        assertTrue(project.rooms.isEmpty())
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        bridge.migrateRoom(0x91F8, "Landing Site")
        val migrated = bridge.sourceText()
        val room = project.rooms.getValue("91F8")
        room.states.add(0, room.states.first().copy(id = "unexpected"))
        assertFalse(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(migrated, bridge.sourceText())
        assertTrue(bridge.isManaged(0x91F8))
    }

    @Test fun `GUI and source edit one document with shared undo and no GUI deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.link(0x8000)
        val first = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, first, 0, first.entries[0].copy(x = 4))
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("${'$'}04"))
        assertTrue(project.rooms.isEmpty(), "No second object model/delta stream may be persisted")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(2, bridge.view(0x8000).document?.entries?.first()?.x)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(4, bridge.view(0x8000).document?.entries?.first()?.x)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, workspace.projectSourceText(PLM_SOURCE_FILE).replace("${'$'}04", "${'$'}07"))
        assertEquals(7, bridge.view(0x8000).document?.entries?.first()?.x)
        assertFailsWith<IllegalArgumentException> { bridge.update(0x8000, first, 0, first.entries[0].copy(x = 9)) }
        assertEquals(7, bridge.view(0x8000).document?.entries?.first()?.x)
    }

    @Test fun `ASM edits acquire authority automatically and undo releases it without linking`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        assertEquals(AsmPlmAuthority.UNCHANGED, bridge.authority(0x8000).authority)
        assertFalse(bridge.isLinked(0x8000))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}06"))
        assertEquals(AsmPlmAuthority.SOURCE, bridge.authority(0x8000).authority)
        assertEquals(6, bridge.view(0x8000).document?.entries?.first()?.x)
        assertTrue(bridge.isLinked(0x8000))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty(), "Automatic ownership is derived, not another saved toggle")
        assertEquals(listOf(bridge.catalog.binding(0x8000)), bridge.catalog.sourceBindings(project,
            workspace.projectSourceText(PLM_SOURCE_FILE), macros))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        assertFalse(bridge.isLinked(0x8000))
        assertEquals(2, bridge.view(0x8000).document?.entries?.first()?.x)
    }

    @Test fun `opening fields does not take ownership and applying an edit needs no Link step`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val first = assertNotNull(bridge.view(0x8000).document)
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        bridge.update(0x8000, first, 0, first.entries[0].copy(y = 12))
        assertEquals(AsmPlmAuthority.SOURCE, bridge.authority(0x8000).authority)
        assertTrue(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertTrue(restored.isLinked(0x8000))
        assertEquals(12, restored.view(0x8000).document?.entries?.first()?.y)
    }

    @Test fun `GUI additions and rewritten states own unchanged source without consuming their deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val edits = project.getOrCreateRoom(0x91F8)
        val item = PlmChange("add", 0xEEDF, 8, 9, 12)
        edits.plmChanges += item
        assertEquals(AsmPlmAuthority.ROOM_EDITOR, bridge.authority(0x8000).authority)
        assertFalse(bridge.isLinked(0x8000))
        assertNull(bridge.view(0x8000).document, "Original ASM must never overwrite the effective GUI preview")
        assertTrue(bridge.catalog.sourceBindings(project, source, macros).isEmpty())
        edits.stateGraphChanged = true
        assertTrue(bridge.authority(0x8000).explanation.orEmpty().contains("state graph"))
        assertTrue(bridge.catalog.sourceBindings(project, source, macros).isEmpty())
        assertEquals(listOf(item), edits.plmChanges)
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
    }

    @Test fun `both changed is an explicit conflict even before a room was opened or linked`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDF, 8, 9, 12)
        val changed = source.replace("${'$'}02", "${'$'}09")
        val failure = assertFailsWith<IllegalArgumentException> { bridge.catalog.sourceBindings(project, changed, macros) }
        assertTrue(failure.message.orEmpty().contains("Both versions are retained"))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertEquals(1, project.getOrCreateRoom(0x91F8).plmChanges.size)
    }

    @Test fun `comment and literal spelling changes do not take objects away from room editing`() = runBlocking {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDF, 8, 9, 12)
        val reformatted = source.replace("${'$'}02", "%00000010").replace("pickup", "different comment")
        assertTrue(bridge.catalog.sourceBindings(project, reformatted, macros).isEmpty())
    }

    @Test fun `invalid and growing unlinked ASM lists block automatic builds rather than hide behind GUI mode`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        assertFails { bridge.catalog.sourceBindings(project, source.replace("${'$'}02", "${'$'}02+"), macros) }
        val growing = source.replace("    dw", "    %PLMPopEntry(1, 2, 3, 4)\n    dw")
        val problem = assertFailsWith<IllegalArgumentException> { bridge.catalog.sourceBindings(project, growing, macros) }
        assertTrue(problem.message.orEmpty().contains("relocation"))
    }

    @Test fun `source owned collection bits and invalid declaration navigation do not require opening the room`() = runBlocking {
        val (workspace, bridge) = fixture()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}0005", "${'$'}0100"))
        assertEquals(listOf(0x100), bridge.sourceOwnedEntries().map { it.param }.toList())
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertEquals(0, bridge.sourceLine(0x8000))
        assertNull(bridge.sourceLine(0x8006))
    }

    @Test fun `placement navigation uses live source and selected state without acquiring ownership`() = runBlocking<Unit> {
        val (workspace, bridge, project) = fixture()
        val entry = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
        for (stateIndex in 0..1) {
            val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x91F8, stateIndex, 0x8000, entry))
            assertEquals(1, target.lineIndex)
        }
        assertTrue(project.rooms.isEmpty())
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 2, 0x8000, entry))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0xB5D5, 0, 0x8000, entry))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, "; inserted line\n" + source.replace("${'$'}02", "${'$'}09"))
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x91F8, 0, 0x8000, entry.copy(x = 9)))
        assertEquals(2, target.lineIndex)
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, entry))
    }

    @Test fun `GUI owned and conflicting placements cannot navigate to stale original source`() = runBlocking<Unit> {
        val (workspace, bridge, project) = fixture()
        val original = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
        bridge.view(0x8000) // Populate last-valid preview, which navigation must never use.
        val edits = project.getOrCreateRoom(0x91F8)
        edits.plmChanges += PlmChange("add", 0xEEDB, 8, 9, 12)
        for (entry in listOf(original, original.copy(x = 8, y = 9, param = 12))) {
            val target = assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, entry))
            assertContains(target.reason, "room editor")
        }
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}04"))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, original.copy(x = 4)))
        edits.plmChanges.clear()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertNotNull(bridge.view(0x8000).document, "A last-valid preview exists, but is not current source")
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, original))
    }

    @Test fun `placement inspection survives back forward with its original room state and no file writes`() = runBlocking {
        val (workspace, _, _) = fixture()
        workspace.openSource(PLM_SOURCE_FILE, 1)
        val sourceLocation = workspace.locationSnapshot()
        val snapshot = AsmPlmPlacementSnapshot(0x91F8, "Landing Site", "Else / default",
            RomParser.PlmEntry(0xEEDB, 8, 9, 12), "Room editor data")
        workspace.openPlmPlacement(snapshot)
        val placementLocation = AsmWorkspaceLocation.PlmPlacement(snapshot)
        assertEquals(placementLocation, workspace.locationSnapshot())
        workspace.openSource(PLM_SOURCE_FILE, 0)
        workspace.goBack()
        assertEquals(placementLocation, workspace.locationSnapshot())
        assertEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode)
        workspace.goBack()
        assertEquals(sourceLocation, workspace.locationSnapshot())
        workspace.goForward()
        assertEquals(placementLocation, workspace.locationSnapshot())
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        workspace.bindProject(File(directory, "Different.smedit").path, false)
        assertNull(workspace.plmPlacementSnapshot)
        assertEquals(AsmBrowserMode.SOURCE, workspace.browserMode)
        repeat(4) { workspace.goBack(); assertNotEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode) }
        repeat(4) { workspace.goForward(); assertNotEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode) }
    }

    @Test fun `invalid draft keeps last valid preview and blocks GUI writes without discarding source`() = runBlocking {
        val (workspace, bridge) = fixture()
        bridge.link(0x8000)
        val first = assertNotNull(bridge.view(0x8000).document)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertNotNull(bridge.view(0x8000).problem)
        assertEquals(first.entries, bridge.view(0x8000).document?.entries)
        assertFails { bridge.update(0x8000, first, 0, first.entries[0].copy(x = 6)) }
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("${'$'}02+"))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        assertNull(bridge.view(0x8000).problem)
    }

    @Test fun `ownership refuses existing GUI overrides and later override conflicts`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDB, 2, 2, 1)
        assertFails { bridge.link(0x8000) }
        project.rooms.clear()
        bridge.link(0x8000)
        project.getOrCreateRoom(0x91F8).stateGraphChanged = true
        assertNotNull(bridge.view(0x8000).problem)
    }

    @Test fun `list growth and macro layout changes cannot masquerade as supported GUI data`() = runBlocking {
        val (workspace, bridge) = fixture()
        bridge.link(0x8000)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("    dw", "    %PLMPopEntry(1, 2, 3, 4)\n    dw"))
        assertTrue(bridge.view(0x8000).problem.orEmpty().contains("relocation"))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        workspace.updateSourceEditText("macros.asm", macros.replace("dw <Param>", "dl <Param>"))
        assertTrue(bridge.view(0x8000).problem.orEmpty().contains("custom layout"))
    }

    @Test fun `save and reopen retain source authority and reject stale disk writes`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.link(0x8000)
        val before = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, before, 0, before.entries[0].copy(x = 8))
        assertTrue(workspace.saveAllSources())
        val encoded = Json.encodeToString(SmEditProject.serializer(), project)
        val restored = Json.decodeFromString(SmEditProject.serializer(), encoded)
        assertEquals(project.asmWorkspace.plmSourceBindings, restored.asmWorkspace.plmSourceBindings)
        assertTrue(restored.rooms.isEmpty())
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val reopened = AsmWorkspaceState(projectRepository = repository)
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val next = AsmPlmSourceBridge(bridge.catalog, reopened) { restored }
        assertEquals(8, next.view(0x8000).document?.entries?.first()?.x)
        assertFails { next.unlink(0x8000) }
        val disk = File(reopened.projectWorkspace!!.workingDirectory, "src/$PLM_SOURCE_FILE")
        disk.appendText("; external edit\n")
        reopened.updateSourceEditText(PLM_SOURCE_FILE, reopened.projectSourceText(PLM_SOURCE_FILE).replace("${'$'}08", "${'$'}09"))
        assertFalse(reopened.saveSource(PLM_SOURCE_FILE))
        assertTrue(disk.readText().endsWith("; external edit\n"))
        assertTrue(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
    }
}
