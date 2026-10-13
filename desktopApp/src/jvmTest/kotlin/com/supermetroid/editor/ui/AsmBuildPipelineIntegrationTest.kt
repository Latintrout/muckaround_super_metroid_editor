package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmBuildArtifactRepository

import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.asm.AsmAssetManifest
import com.supermetroid.editor.asm.AsmWlaSymbolParser
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.AsmRoomObjectSource
import com.supermetroid.editor.asm.PLM_SOURCE_FILE
import kotlinx.coroutines.runBlocking
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.projectRoomStateCondition
import java.io.File
import java.util.Base64
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Explicit clean ROM/source fixtures; every project and compiler artifact lives in @TempDir. */
class AsmBuildPipelineIntegrationTest {
    @TempDir lateinit var tempDirectory: File

    @Test
    fun `single-state Bubble Mountain stays simple then grows shared state source with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val room = project.getOrCreateRoom(0xACB3)
        room.plmChanges += PlmChange("add", 0xEF23, 0x1A, 0x0A, 0x100)
        room.plmChanges += PlmChange("add", 0xEEE7, 0x1C, 0x09, 0x101)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(room.roomId, "Bubble Mountain")
        val state = room.states.single().id
        val bomb = RomParser.PlmEntry(0xEEE7, 0x1C, 0x09, 0x101)
        repeat(3) {
            bridge.editManaged(room.roomId, state, true, { it == bomb }, null)
            bridge.editManaged(room.roomId, state, true, { false }, bomb)
        }
        assertTrue(!bridge.sourceText().contains("SmeditObjects_ACB3_Common_"))
        val logs = mutableListOf<String>()
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources())
            logs.clear()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path),
                logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val expected = room.states.map { assertNotNull(bridge.managedView(room.roomId, it.id).document).entries }
            assertEquals(expected, built.parseRoomStatesWithData(room.roomId).map { built.parsePlmSet(it.plmSetPtr) })
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()

        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(room.roomId, parser, assertNotNull(parser.readRoomHeader(room.roomId)))
        val newState = editor.addRoomState(state,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        assertEquals(bridge.managedView(room.roomId, state).document?.entries, bridge.managedView(room.roomId, newState).document?.entries)
        bridge.editManaged(room.roomId, newState, false, { it == bomb }, null)
        bridge.editManaged(room.roomId, state, true, { false }, bomb.copy(x = 20, param = 0x102))
        val authored = AsmRoomObjectSource(bridge.sourceText(), bridge.catalog.symbols)
        assertNotNull(authored.table(room.roomId, state).objects.last().commonMacro)
        assertTrue(authored.table(room.roomId, state).document.entries.contains(bomb))
        assertTrue(!authored.table(room.roomId, newState).document.entries.contains(bomb))
        buildAndVerify()
    }

    @Test
    fun `GUI edited room migrates to authored growing lists with state exceptions and real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val landing = project.getOrCreateRoom(0x91F8)
        landing.ensureStateManifest(parser)
        landing.plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        landing.states.last().plmChanges += PlmChange("remove", 0xEEDB, 8, 8, 0x100)
        landing.states.add(1, landing.states.removeAt(0))
        landing.states.removeAll { it.id == "state-3" }
        landing.stateGraphChanged = true
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
        val expected = RomParser(oracle.resultRom).let { rom -> rom.parseRoomStatesWithData(0x91F8).map { rom.parsePlmSet(it.plmSetPtr) } }
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        assertEquals(1, landing.asmPlmSourceVersion)
        assertTrue(landing.plmChanges.isEmpty() && landing.states.all { it.plmChanges.isEmpty() })
        assertEquals(expected, landing.states.map { bridge.managedView(0x91F8, it.id).document?.entries })
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        fun build(): RomBuildResult {
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        val first = RomParser(build().resultRom)
        assertEquals(expected, first.parseRoomStatesWithData(0x91F8).map { first.parsePlmSet(it.plmSetPtr) })
        val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)?.lines.orEmpty().joinToString("\n")
        assertTrue(generated.contains("%SmeditObjects_91F8_State_"), generated.take(500))

        editor.switchRoomState(landing.states.first().id, parser)
        editor.addPlm(0xEEDF, 9, 9, 0x101, itemScopeOverride = ItemStateScope.ALL_STATES)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF && it.x == 9 })
        assertTrue(editor.undo())
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF && it.x == 9 })
        assertTrue(editor.redo())
        val newId = editor.addRoomState(landing.states.first().id,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        editor.switchRoomState(newId, parser)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF && it.x == 9 })
        editor.addPlm(0xEEE3, 10, 9, 0x102, itemScopeOverride = ItemStateScope.THIS_STATE)
        val objectView = assertNotNull(bridge.managedView(0x91F8, newId).document)
        val changedIndex = objectView.entries.indexOfFirst { it.id == 0xEEE3 && it.x == 10 }
        workspace.updateSourceEditText(PLM_SOURCE_FILE, objectView.replace(changedIndex,
            objectView.entries[changedIndex].copy(param = 0x103)))
        editor.refreshLinkedPlms()
        assertEquals(0x103, editor.workingPlms.single { it.id == 0xEEE3 && it.x == 10 }.param)
        assertTrue(landing.plmChanges.isEmpty() && landing.states.all { it.plmChanges.isEmpty() })
        assertTrue(workspace.saveAllSources())
        val authored = AsmRoomObjectSource(workspace.projectSourceText(PLM_SOURCE_FILE), bridge.catalog.symbols)
        val expectedAfter = landing.states.map { authored.table(0x91F8, it.id).document.entries }
        val second = build()
        val built = RomParser(second.resultRom)
        assertEquals(expectedAfter, built.parseRoomStatesWithData(0x91F8).map { built.parsePlmSet(it.plmSetPtr) })
        val pointers = built.parseRoomStatesWithData(0x91F8).map { it.plmSetPtr }
        val identities = landing.states.map { authored.table(0x91F8, it.id).resource.name }
        for (a in pointers.indices) for (b in pointers.indices) {
            assertEquals(identities[a] == identities[b], pointers[a] == pointers[b], "Source resource sharing must survive allocation")
        }
        assertEquals(1, expectedAfter.count { entries -> entries.any { it.id == 0xEEE3 && it.x == 10 } })
        assertTrue(expectedAfter.all { entries -> entries.any { it.id == 0xEEDF && it.x == 9 } })
        assertContentEquals(second.resultRom, build().resultRom)
        assertTrue(logs.any { it.contains("reusing prepared source base") }, logs.joinToString("\n"))
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        // Standard format is a contract, not a guess about arbitrary user macros.
        workspace.updateSourceEditText("macros.asm", workspace.projectSourceText("macros.asm")
            .replace("macro PLMPopEntry(ID, X, Y, Param)", "macro PLMPopEntry(ID, X, Y, Param)\n    db 0"))
        assertTrue(workspace.saveAllSources())
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("custom layout") }, logs.joinToString("\n"))
    }

    @Test
    fun `automatic PLM authority round trips GUI ASM and build without linking or a second room writer`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        // Landing Site: one real source list shared by three states. No Link step or saved binding.
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        val states = project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        val sharedStateIndex = catalog.consumers.getValue(0x8000).first().stateIndex
        editor.switchRoomState(states[sharedStateIndex].id, parser)
        assertEquals(0x8000, editor.currentPlmSourcePointer())
        val original = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, original, 0, original.entries[0].copy(x = 0x1D))
        editor.refreshLinkedPlms()
        assertEquals(0x1D, editor.workingPlms[0].x)
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,"))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, workspace.projectSourceText(PLM_SOURCE_FILE)
            .replaceFirst("%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,", "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1C,"))
        editor.refreshLinkedPlms()
        assertEquals(0x1C, editor.workingPlms[0].x)
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        editor.refreshLinkedPlms()
        assertEquals(original.entries, editor.workingPlms, "Undo to baseline must restore preview and release automatic ownership")
        assertTrue(!bridge.isLinked(0x8000))
        workspace.redoSourceEdit(PLM_SOURCE_FILE)
        workspace.redoSourceEdit(PLM_SOURCE_FILE)
        val anotherSharedState = catalog.consumers.getValue(0x8000).last().stateIndex
        editor.switchRoomState(states[anotherSharedState].id, parser)
        assertEquals(0x1C, editor.workingPlms[0].x, "Switching state must use the source projection immediately")
        assertTrue(project.rooms.values.all { it.plmChanges.isEmpty() && it.states.all { state -> state.plmChanges.isEmpty() } })
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        assertEquals(original.entries[0].copy(x = 0x1C), built.parsePlmSet(0x8000)[0])
        assertEquals(3, built.parseRoomStatesWithData(0x91F8).count { it.plmSetPtr == 0x8000 })
        assertTrue(result.writeReport.writes.none { it.owner == "room-graph:project" }, "Source-linked records must not be replayed as GUI edits")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // An unchanged byte still belongs to the source resource: patch overlap must fail, even with identical data.
        project.patches += SmPatch("linked_overlap", "Conflicting object patch", enabled = true,
            writes = mutableListOf(PatchWrite(offset = 0x78000, bytes = listOf(fixture.rom[0x78000].toInt() and 0xFF))))
        val conflictLogs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, conflictLogs::add, conflictLogs::add, fixture.projectFile.path))
        assertTrue(conflictLogs.any { it.contains("conflict", ignoreCase = true) }, conflictLogs.joinToString("\n"))
    }

    @Test
    fun `edited Landing Site keeps GUI authority while Wasteland source is adopted automatically`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val landing = project.getOrCreateRoom(0x91F8)
        val states = landing.ensureStateManifest(parser)
        // Model the user's important combination: a changed graph, common additions, and a state exception.
        val moved = landing.states.removeAt(0)
        landing.states.add(1, moved)
        landing.stateGraphChanged = true
        landing.plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        states.last().plmChanges += PlmChange("remove", 0xEEDB, 8, 8, 0x100)
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())

        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true)
        val source = workspace.projectSourceText(PLM_SOURCE_FILE)
        val changed = source.replace("%PLMPopEntry(PLMEntries_PowerBombTank, ${'$'}07, ${'$'}08, ${'$'}004C)",
            "%PLMPopEntry(PLMEntries_SuperMissileTank, ${'$'}07, ${'$'}08, ${'$'}004C)")
        assertNotEquals(source, changed)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, changed)
        assertTrue(workspace.saveAllSources())
        val metadataBefore = project.rooms.mapValues { (_, value) -> value.plmChanges.toList() }
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        val expected = RomParser(oracle.resultRom)
        assertEquals(expected.parseRoomStatesWithData(0x91F8).map { expected.parsePlmSet(it.plmSetPtr) },
            built.parseRoomStatesWithData(0x91F8).map { built.parsePlmSet(it.plmSetPtr) })
        assertEquals(0xEEDF, built.parsePlmSet(0x9036).last().id)
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertEquals(metadataBefore, project.rooms.mapValues { (_, value) -> value.plmChanges.toList() })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // Editing that same GUI-owned Landing Site list in source is NOT another automatic takeover.
        workspace.updateSourceEditText(PLM_SOURCE_FILE, changed.replaceFirst(
            "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1E,", "%PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}1D,"))
        assertTrue(workspace.saveAllSources())
        logs.clear()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("Both versions are retained") }, logs.joinToString("\n"))
        assertEquals(metadataBefore, project.rooms.mapValues { (_, value) -> value.plmChanges.toList() })
    }

    @Test
    fun `changed selectors and reordered states keep source validation and final room data consistent`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        for (reorder in listOf(false, true)) {
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            val states = edits.ensureStateManifest(parser)
            if (reorder) {
                val first = edits.states.removeAt(0)
                edits.states.add(1, first)
                edits.stateGraphChanged = true
            } else {
                states[2].condition = states[2].condition.copy(argument = 2)
                states[2].conditionChanged = true
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path),
                logs.joinToString("\n"))
            assertTrue(result.sourceRoomPatched > 0)
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom))
            val data = assertNotNull(AsmBuildArtifactRepository().loadRoomData(fixture.projectFile.path))
            assertTrue(data.isRoomDataSnapshot)
            assertEquals(parser.roomCatalog.rooms.size, data.groups().size)
            assertEquals("Landing Site", data.groups().single { it.roomId == 0x91F8 }.title)
        }
    }

    @Test
    fun `PLM growth sharing selected-state shrink and empty lists compile with symbolic pointers`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val before = parser.parseRoomStatesWithData(0x91F8)
        val originals = before.map { parser.parsePlmSet(it.plmSetPtr) }
        val sharedStateIndex = before.indices.first { i -> before.count { it.plmSetPtr == before[i].plmSetPtr } > 1 }
        val item = PlmChange("add", 0xEEDB, 8, 8, 0x100)
        val cases = listOf("all-state growth", "selected-state growth", "selected-state shrink", "empty lists")
        cases.forEach { scenario ->
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            when (scenario) {
                "all-state growth" -> edits.plmChanges += item
                "selected-state growth" -> edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges += item
                "selected-state shrink" -> {
                    val removed = originals[sharedStateIndex].last()
                    edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges +=
                        PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
                else -> originals.flatten().distinct().forEach { removed ->
                    edits.plmChanges += PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(
                project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
            ), "$scenario\n${logs.joinToString("\n")}")
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom), scenario)
            assertTrue(result.sourceRoomPatched > 0)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM })
            assertTrue(result.writeReport.writes.none {
                it.owner == "room-graph:project" && it.kind in setOf(RomWriteKind.ROOM, RomWriteKind.ALLOCATION)
            })
            val buildDirectory = ProjectPaths(fixture.projectFile).asmBuild
            val generated = File(buildDirectory, "generated-rooms.asm").readText()
            assertTrue(generated.contains("dw SmeditRoom_91F8_State"), generated)
            val snapshot = assertNotNull(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path))
            assertEquals(generated.lines(), snapshot.lines)
            assertTrue(snapshot.sections.any { it.roomId == 0x91F8 }, "actual emitted tables must link back to Landing Site")
            val symbols = AsmWlaSymbolParser.parse(File(buildDirectory, "symbols.sym").readText())
            val tableAddresses = symbols.labels.filterKeys { it.startsWith("SmeditRoom_") && it.endsWith("_Plms") }
                .values.map { it and 0xFFFF }.toSet()
            assertTrue(tableAddresses.isNotEmpty())
            val built = RomParser(result.resultRom)
            val after = built.parseRoomStatesWithData(0x91F8)
            val lists = after.map { built.parsePlmSet(it.plmSetPtr) }
            when (scenario) {
                "all-state growth" -> {
                    assertEquals(before.size, after.size)
                    before.indices.forEach { i ->
                        assertEquals(originals[i] + RomParser.PlmEntry(item.plmId, item.x, item.y, item.param), lists[i])
                        assertNotEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                        assertTrue(after[i].plmSetPtr in tableAddresses)
                        before.indices.forEach { j ->
                            assertEquals(before[i].plmSetPtr == before[j].plmSetPtr, after[i].plmSetPtr == after[j].plmSetPtr)
                        }
                    }
                }
                "selected-state growth", "selected-state shrink" -> {
                    assertNotEquals(before[sharedStateIndex].plmSetPtr, after[sharedStateIndex].plmSetPtr)
                    assertTrue(after[sharedStateIndex].plmSetPtr in tableAddresses)
                    before.indices.filter { it != sharedStateIndex }.forEach { i ->
                        assertEquals(originals[i], lists[i])
                        assertEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                    }
                    val expectedSize = originals[sharedStateIndex].size + if (scenario.endsWith("growth")) 1 else -1
                    assertEquals(expectedSize, lists[sharedStateIndex].size)
                }
                else -> assertTrue(lists.all { it.isEmpty() })
            }
        }
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test
    fun `room source asset and patch backends compose and reuse an unchanged build`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        project.patches += SmPatch(
            "config_samus_physics", "Samus Physics", enabled = true,
            configType = "samus_physics", configData = mutableMapOf("gravity" to 0x2C),
        )
        val legacyOffset = 0x1000
        val legacyValue = (fixture.rom[legacyOffset].toInt() and 0xFF) xor 0x5A
        val legacyPatch = SmPatch("fixture-legacy", "Legacy byte patch", enabled = true,
            writes = mutableListOf(PatchWrite(legacyOffset.toLong(), listOf(legacyValue))))
        project.patches += legacyPatch
        project.patches += SmPatch("fixture-disabled", "Disabled byte patch", enabled = false,
            writes = mutableListOf(PatchWrite(legacyOffset.toLong(), listOf(legacyValue xor 0xFF))))
        val asset = AsmAssetManifest.loadBundled().single { it.path == "Tiles_Zoomer.bin" }
        val replacement = fixture.rom.copyOfRange(asset.pcOffset, asset.endExclusive)
        replacement[0] = (replacement[0].toInt() xor 1).toByte()
        project.customGfx.spriteTileBlocks["enemy:DCFF"] = Base64.getEncoder().encodeToString(replacement)
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(
            project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
        ), logs.joinToString("\n"))
        assertTrue(result.sourceAssetPatched > 0 && result.sourceRoomPatched > 0 && result.sourcePatchPatched > 0,
            "assets=${result.sourceAssetPatched}, rooms=${result.sourceRoomPatched}, patches=${result.sourcePatchPatched}\n${logs.joinToString("\n")}")
        assertEquals(0x2C, result.resultRom[0x081EA2].toInt() and 0xFF)
        assertEquals(legacyValue, result.resultRom[legacyOffset].toInt() and 0xFF,
            "Legacy writes must apply after ASM alongside source-supported patches")
        val generatedFile = File(ProjectPaths(fixture.projectFile).asmBuild, "generated-rooms.asm")
        val generated = generatedFile.readText()
        generatedFile.writeText("; report from a different build\n")
        val cachedLogs = mutableListOf<String>()
        val cached = assertNotNull(ProjectFileService.buildRom(
            project, parser, cachedLogs::add, cachedLogs::add, fixture.projectFile.absolutePath,
        ), cachedLogs.joinToString("\n"))
        assertContentEquals(result.resultRom, cached.resultRom)
        assertEquals(generated, generatedFile.readText())
        assertEquals(generated.lines(), AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)?.lines)
        assertTrue(cachedLogs.any { it.contains("unchanged — reusing prepared source base") }, cachedLogs.joinToString("\n"))
        assertTrue(cachedLogs.none { it.contains("Compiling project ASM source") })

        // The ROM backend applies the same enabled patch values from the same GUI settings.
        val romProject = ProjectFileService.snapshotProject(project).apply {
            asmWorkspace.enabled = false
            asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        }
        val romLogs = mutableListOf<String>()
        val romResult = assertNotNull(ProjectFileService.buildRom(romProject, parser, romLogs::add, romLogs::add), romLogs.joinToString("\n"))
        assertEquals(result.resultRom[legacyOffset], romResult.resultRom[legacyOffset])
        assertEquals(result.resultRom[0x081EA2], romResult.resultRom[0x081EA2])
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())

        // A successful Asar pass must not hide a later planner failure in the IDE.
        project.patches += SmPatch(
            "fixture-invalid-range", "Invalid range fixture", enabled = true,
            writes = mutableListOf(PatchWrite(Int.MAX_VALUE.toLong(), listOf(0xFF))),
        )
        val failureLogs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(
            project, parser, failureLogs::add, failureLogs::add, fixture.projectFile.path,
        ))
        val failedReport = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path))
        assertEquals(false, failedReport.succeeded)
        assertTrue(failedReport.diagnostics.any { it.message.contains("range", ignoreCase = true) }, failureLogs.joinToString("\n"))
    }

    @Test
    fun `renamed project exports from either backend without changing base or authored paths`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val renamedProject = File(tempDirectory, "My New Hack.smedit")
        assertTrue(fixture.projectFile.renameTo(renamedProject))
        val project = sourceProject(fixture)
        assertTrue(ProjectFileService.saveProject(project, renamedProject.path, null, false) {})
        val paths = ProjectPaths(renamedProject)
        val parser = RomParser(fixture.rom)
        val logs = mutableListOf<String>()
        val output = assertNotNull(ProjectFileService.exportToRom(project, parser, logs::add, logs::add, renamedProject.path), logs.joinToString("\n"))
        assertEquals(File(tempDirectory, "My New Hack-v1.0.smc").absolutePath, output)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        assertContentEquals(fixture.rom, File(output).readBytes())
        assertTrue(File(paths.asmBuild, "symbols.sym").isFile)
        val roomData = assertNotNull(AsmBuildArtifactRepository().loadRoomData(renamedProject.path))
        assertEquals(parser.roomCatalog.rooms.size, roomData.groups().size, "unchanged builds must also publish all room data")
        val reopened = ProjectFileService.loadProject(renamedProject)
        assertEquals(fixture.romFile.absolutePath, reopened.romPath)
        ProjectLifecycle.verify(reopened, fixture.romFile)
        reopened.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        reopened.buildName = "rom"
        val patchedOutput = assertNotNull(ProjectFileService.exportToRom(reopened, parser, logs::add, logs::add, renamedProject.path))
        assertEquals("My New Hack-rom-v1.0.smc", File(patchedOutput).name)
        assertContentEquals(File(output).readBytes(), File(patchedOutput).readBytes())
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    private fun sourceProject(fixture: AsmProjectTestFixture) = SmEditProject(fixture.romFile.absolutePath).also {
        it.asmWorkspace.enabled = true
        it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
    }
}
