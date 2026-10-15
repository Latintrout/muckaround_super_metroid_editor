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
import com.supermetroid.editor.asm.FX_SOURCE_FILE
import com.supermetroid.editor.asm.authoredMacroBody
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
import com.supermetroid.editor.rom.words
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
import kotlin.test.assertFalse
import kotlin.test.assertContains

/** Explicit clean ROM/source fixtures; every project and compiler artifact lives in @TempDir. */
class AsmBuildPipelineIntegrationTest {
    @TempDir lateinit var tempDirectory: File

    @Test fun `authored enemy populations and GFX compose with states PLMs and ROM patches with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(fixture.projectFile, project); asmPlmSourceBridge = bridge }
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.ensureEnemySource("Landing Site"), editor.statusMessage)
        val room = project.rooms.getValue("91F8")
        val stateId = editor.currentPlmStateId()!!
        val original = editor.workingEnemies.first()
        editor.updateEnemy(original, original.copy(x = original.x + 16, extra3 = 0x0123))
        assertNull(editor.enemyPreviewProblem)
        val newId = editor.addRoomState(stateId, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        editor.switchRoomState(newId, parser)
        editor.addEnemy(0xD17F, 128, 160)
        assertNull(editor.enemyPreviewProblem, editor.statusMessage)
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        editor.setScroll(0, 0, 2, editor.workingBlocksWide / 16)
        editor.setRoomStateFxChange(newId, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x100), parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = TileBrush(listOf(listOf(42, 43)), plmOverrides = mapOf(0L to (0xEEDF to 0)))
        val patternBefore = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.paintAt(12, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        val patternAfter = editor.workingLevelData!!.copyOf()
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(patternBefore, editor.workingLevelData)
        assertTrue(editor.redo(), editor.statusMessage)
        assertContentEquals(patternAfter, editor.workingLevelData)
        project.patches += SmPatch("enemy-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x43))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error)
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            val states = built.parseRoomStatesWithData(room.roomId)
            room.states.forEachIndexed { index, state ->
                val expected = assertNotNull(bridge.enemies.view(room.roomId, state.id).resource)
                assertContentEquals(expected.population.bytes(), com.supermetroid.editor.rom.readSourceEnemyPopulation(built, states[index].enemySetPtr, expected.population.identity).bytes())
                assertContentEquals(expected.graphics.bytes(), com.supermetroid.editor.rom.readSourceEnemyGraphics(built, states[index].enemyGfxPtr, expected.graphics.identity).bytes())
                assertEquals(bridge.managedView(room.roomId, state.id).document!!.entries, built.parsePlmSet(states[index].plmSetPtr))
                if (state.resources.level == room.states.single { it.id == newId }.resources.level) {
                    assertContentEquals(patternAfter, built.decompressLZ2(states[index].levelDataPtr), "Compound pattern geometry and PLMs must reach the same built ROM")
                }
            }
            val populations = room.states.mapIndexed { index, state -> bridge.enemies.view(room.roomId, state.id).resource!!.population.identity to states[index].enemySetPtr }.groupBy({ it.first }, { it.second })
            assertTrue(populations.values.all { it.distinct().size == 1 })
            assertEquals(populations.size, populations.values.map { it.first() }.distinct().size)
            assertEquals(0x43, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:enemy-test-patch" })
            val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n")
            assertContains(generated, "; Enemy placement: authored paired values live in bank_A1.asm.")
            assertContains(generated, "assert (")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val before = bridge.enemies.sourceText()
        val selected = bridge.enemies.view(room.roomId, newId).resource!!.population
        val body = authoredMacroBody(before, selected.identity)
        workspace.replaceProjectSource(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE, before, before.replace(body,
            body.replace("\$0080, \$00A0", "\$0090, \$00A0")))
        editor.refreshLinkedPlms()
        assertEquals(144, editor.workingEnemies.last().x)
        buildAndVerify()
        val current = bridge.enemies.sourceText()
        val invalid = current.replace("db \$00 ; Required kills", "db \$100 ; Required kills")
        assertNotEquals(current, invalid)
        workspace.replaceProjectSource(com.supermetroid.editor.asm.ENEMY_SOURCE_FILE, current, invalid)
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val failure = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!
        assertFalse(failure.succeeded)
        assertTrue(failure.diagnostics.any { it.fileId == com.supermetroid.editor.asm.ENEMY_SOURCE_FILE && it.lineIndex != null })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `authored FX compose with PLMs scrolls door variants new states and ROM patches with real Asar parity`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.ensureCurrentPlmSource("Landing Site"))
        editor.setScroll(0, 0, if (editor.workingScrolls[0] == 0) 1 else 0, editor.workingBlocksWide / 16)
        val landing = project.rooms.getValue("91F8")
        val landingState = editor.currentPlmStateId()!!
        editor.setRoomStateFxChange(landingState, com.supermetroid.editor.data.FxChange(fxType = 6,
            liquidSurfaceStart = 0x0100, liquidSurfaceNew = 0x0080, liquidSpeed = 0xFFF0, liquidDelay = 30), parser)
        assertEquals(1, landing.asmFxSourceVersion, editor.statusMessage)
        assertEquals(0x0100, editor.effectiveFxEntries(landing.roomId, parser).last().liquidSurfaceStart)
        val routed = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.first { room ->
            room.roomId != 0xDD58 && parser.parseRoomStatesWithData(room.roomId).any { state -> parser.parseFxEntries(state.fxPtr).any { it.doorSelect != 0 } }
        }
        bridge.fx.migrate(routed.roomId, routed.name)
        val routedRoom = project.rooms.getValue(project.roomKey(routed.roomId))
        val routedState = routedRoom.states.first { bridge.fx.view(routed.roomId, it.id).resource!!.entries.any { fx -> fx.doorSelect != 0 } }.id
        val doorEntry = bridge.fx.view(routed.roomId, routedState).resource!!.entries.first { it.doorSelect != 0 }
        val originalDefault = bridge.fx.view(routed.roomId, routedState).resource!!.entries.lastOrNull { it.doorSelect == 0 }
        editor.loadRoom(routed.roomId, parser, routed)
        editor.switchRoomState(routedState, parser)
        editor.setRoomStateDoorFxChange(routedState, doorEntry.doorSelect, com.supermetroid.editor.data.FxChange(liquidDelay = 60, liquidSpeed = 0xFFE0), parser)
        assertEquals(originalDefault, bridge.fx.view(routed.roomId, routedState).resource!!.entries.lastOrNull { it.doorSelect == 0 })
        val empty = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }.first { room ->
            room.roomId != 0xDD58 && parser.parseRoomStatesWithData(room.roomId).all { parser.parseFxEntries(it.fxPtr).isEmpty() }
        }
        bridge.fx.migrate(empty.roomId, empty.name)
        val emptyRoom = project.rooms.getValue(project.roomKey(empty.roomId))
        bridge.fx.edit(empty.roomId, emptyRoom.states.last().id, 0, com.supermetroid.editor.data.FxChange(fxType = 2, liquidSurfaceStart = 0x0040))
        project.patches += SmPatch("fx-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources(), workspace.error ?: "Source save failed: busy=${workspace.busy}, progress=${workspace.progress}")
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            for (room in listOf(landing, routedRoom, emptyRoom)) {
                val states = built.parseRoomStatesWithData(room.roomId)
                val inputs = room.states.map { bridge.fx.view(room.roomId, it.id).resource!! }
                inputs.forEachIndexed { index, expected ->
                    assertEquals(expected.entries, built.parseFxEntries(states[index].fxPtr))
                    assertContentEquals(expected.bytes(), com.supermetroid.editor.rom.readSourceFxResource(built, states[index].fxPtr, expected.identity).bytes())
                }
                val identities = inputs.mapIndexed { index, input -> input.identity to states[index].fxPtr }.groupBy({ it.first }, { it.second })
                assertTrue(identities.values.all { it.distinct().size == 1 })
                assertEquals(identities.size, identities.values.map { it.first() }.distinct().size)
            }
            val builtLanding = built.parseRoomStatesWithData(landing.roomId)
            landing.states.forEachIndexed { index, state ->
                assertEquals(bridge.managedView(landing.roomId, state.id).document!!.entries, built.parsePlmSet(builtLanding[index].plmSetPtr))
                assertEquals(bridge.initialScrolls.view(landing.roomId, state.id).resource!!.values,
                    built.parseScrollData(builtLanding[index].scrollPtr, built.readRoomHeader(landing.roomId)!!.width, built.readRoomHeader(landing.roomId)!!.height).toList())
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:fx-test-patch" })
            assertContains(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n"), "; FX placement; values live in bank_83.asm.")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val selected = bridge.fx.view(landing.roomId, landingState).resource!!
        val before = bridge.fx.sourceText()
        val body = authoredMacroBody(before, selected.identity)
        workspace.replaceProjectSource(FX_SOURCE_FILE, before, before.replace(body, body.replace("dw \$0100 ; Liquid Start", "dw \$0080 ; Liquid Start")))
        editor.loadRoom(landing.roomId, parser, parser.readRoomHeader(landing.roomId)!!)
        editor.switchRoomState(landingState, parser)
        editor.refreshLinkedPlms()
        assertEquals(0x0080, editor.effectiveFxEntries(landing.roomId, parser).last().liquidSurfaceStart)
        val added = editor.addRoomState(landingState, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        bridge.fx.edit(landing.roomId, added, 0, com.supermetroid.editor.data.FxChange(paletteBlend = 0x40))
        buildAndVerify()
        val sourceBeforeGuard = bridge.fx.sourceText()
        assertTrue(runCatching { bridge.fx.migrate(0xDD58, "Mother Brain") }.isFailure)
        assertEquals(sourceBeforeGuard, bridge.fx.sourceText(), "Never drop Mother Brain's indexed battle FX tail")
        val currentFx = bridge.fx.view(landing.roomId, landingState).resource!!
        val currentBody = authoredMacroBody(sourceBeforeGuard, currentFx.identity)
        val invalid = sourceBeforeGuard.replace(currentBody, currentBody.replace("db \$06 ; FX Type", "db \$03 ; FX Type"))
        assertNotEquals(sourceBeforeGuard, invalid)
        workspace.replaceProjectSource(FX_SOURCE_FILE, sourceBeforeGuard, invalid)
        assertTrue(workspace.saveAllSources(), workspace.error)
        val errors = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(project, parser, errors::add, errors::add, fixture.projectFile.path), errors.joinToString("\n"))
        val failure = AsmBuildArtifactRepository().load(fixture.projectFile.path)!!
        assertFalse(failure.succeeded)
        val diagnostic = failure.diagnostics.first { it.fileId == FX_SOURCE_FILE }
        assertContains(invalid.lines()[assertNotNull(diagnostic.lineIndex)], "db \$03 ; FX Type")
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `initial screen scrolls assemble with state isolation sentinels new states and ordinary patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site") // Existing object integration must compose with initial scrolls.
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, assertNotNull(parser.readRoomHeader(0x91F8)))
        val original = editor.workingScrolls.toList()
        val width = editor.workingBlocksWide / 16
        editor.setScroll(0, 0, if (original[0] == 0) 1 else 0, width)
        val landing = project.rooms.getValue("91F8")
        assertEquals(1, landing.asmInitialScrollSourceVersion)
        val current = editor.currentPlmStateId()!!
        val sentinel = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }
            .first { it.roomScrollsPtr == 0 && it.height > 1 && it.width * it.height <= 50 }
        bridge.initialScrolls.migrate(sentinel.roomId, sentinel.name)
        val sentinelRoom = project.rooms.getValue(project.roomKey(sentinel.roomId))
        assertEquals(0, sentinelRoom.asmPlmSourceVersion)
        sentinelRoom.states.forEach { state ->
            val source = assertNotNull(bridge.initialScrolls.view(sentinel.roomId, state.id).resource)
            assertEquals(parser.parseScrollData(parser.parseRoomStatesWithData(sentinel.roomId)[state.sourceStateIndex!!].scrollPtr,
                sentinel.width, sentinel.height).toList(), source.values)
        }
        project.patches += SmPatch("initial-scroll-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        suspend fun buildAndVerify() {
            assertTrue(workspace.saveAllSources())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
            val built = RomParser(result.resultRom)
            for (room in listOf(landing, sentinelRoom)) {
                val header = assertNotNull(built.readRoomHeader(room.roomId))
                val states = built.parseRoomStatesWithData(room.roomId)
                room.states.forEachIndexed { index, state ->
                    val expected = assertNotNull(bridge.initialScrolls.view(room.roomId, state.id).resource)
                    assertTrue(states[index].scrollPtr >= 0x8000, "Presets become concrete private source allocations")
                    assertEquals(expected.values, built.parseScrollData(states[index].scrollPtr, header.width, header.height).toList())
                }
                val pointersByIdentity = room.states.mapIndexed { index, state ->
                    bridge.initialScrolls.view(room.roomId, state.id).resource!!.identity to states[index].scrollPtr
                }.groupBy({ it.first }, { it.second })
                assertTrue(pointersByIdentity.values.all { it.distinct().size == 1 })
                assertEquals(pointersByIdentity.size, pointersByIdentity.values.map { it.first() }.distinct().size)
            }
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.owner == "patch:initial-scroll-test-patch" })
            assertContains(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!.lines.joinToString("\n"),
                "; Initial screen scroll placement; values live in bank_8F.asm.")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        buildAndVerify()
        val selected = assertNotNull(bridge.initialScrolls.view(landing.roomId, current).resource)
        val before = bridge.sourceText()
        val prefix = "macro ${selected.identity}()\n    db \$${selected.values[0].toString(16).uppercase().padStart(2, '0')}"
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace(prefix, "macro ${selected.identity}()\n    db \$02"))
        editor.refreshLinkedPlms()
        assertEquals(2, editor.workingScrolls[0])
        val added = editor.addRoomState(current, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B), parser)
        assertEquals(selected.identity, bridge.initialScrolls.view(landing.roomId, added).resource!!.identity)
        bridge.initialScrolls.edit(landing.roomId, added, 0, 1)
        buildAndVerify()
    }

    @Test fun `paired save PLM and spawn assemble atomically preserve patches and release only the final state`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(fixture.projectFile, project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val station = assertNotNull(editor.workingPlms.singleOrNull { it.id == 0xB76F }, editor.statusMessage)
        val slot = station.param and 255
        assertEquals(2, slot, "Crateria's gunship and original save retain their slots")
        val stateId = editor.currentPlmStateId()!!
        editor.updateSaveStationSpawnFields(0, slot, -32, 104, 0, 0, parser)
        val authored = bridge.saveSpawn(0x91F8, 0, slot)!!
        val legacy = SmEditProject("test.sfc")
        val legacyRoom = legacy.getOrCreateRoom(0x91F8)
        legacyRoom.ensureStateManifest(parser)
        legacyRoom.plmChanges += PlmChange("add", station.id, station.x, station.y, station.param)
        val w = authored.words
        legacyRoom.saveStationSpawns += com.supermetroid.editor.data.SaveStationSpawnChange(0, slot, w[0], w[1], w[3], w[4], w[5], w[6])
        val oracle = assertNotNull(RomExporter(legacy, parser).build())
        project.patches += SmPatch("save-test-patch", "Independent ROM patch", enabled = true,
            writes = mutableListOf(PatchWrite(0x2FFFF0, listOf(0x42))))
        val logs = mutableListOf<String>()
        suspend fun build(): RomBuildResult {
            assertTrue(workspace.saveAllSources())
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult, expected: List<Int>) {
            val built = RomParser(result.resultRom)
            assertEquals(expected, built.readSaveEntry(0, slot)!!.words())
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM && it.label.contains("Save_0_$slot") && it.bytes.size == 14 })
            assertEquals(parser.readSaveEntry(0, 0)!!.words(), built.readSaveEntry(0, 0)!!.words(), "Gunship record untouched")
            assertEquals(parser.readSaveEntry(0, 1)!!.words(), built.readSaveEntry(0, 1)!!.words(), "Other room's save untouched")
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        val first = build()
        verify(first, authored.words)
        assertEquals(RomParser(oracle.resultRom).readSaveEntry(0, slot)!!.words(), RomParser(first.resultRom).readSaveEntry(0, slot)!!.words())
        assertEquals(RomParser(oracle.resultRom).parseRoomStatesWithData(0x91F8).map { RomParser(oracle.resultRom).parsePlmSet(it.plmSetPtr) },
            RomParser(first.resultRom).parseRoomStatesWithData(0x91F8).map { RomParser(first.resultRom).parsePlmSet(it.plmSetPtr) })
        val otherState = project.rooms.getValue("91F8").states.first { it.id != stateId }.id
        assertTrue(project.rooms.getValue("91F8").states.all { branch ->
            bridge.managedView(0x91F8, branch.id).document!!.entries.count { it == station } == 1
        }, "A default GUI addition includes every state, without duplicates")
        editor.removePlm(station.x, station.y, station.id, ItemStateScope.THIS_STATE)
        assertFalse(bridge.saveSpawn(0x91F8, 0, slot)!!.cleared)
        verify(build(), authored.words)
        bridge.editSaveStation(0x91F8, otherState, { it == station }, null, authored, allStates = true)
        assertTrue(bridge.saveSpawn(0x91F8, 0, slot)!!.cleared)
        verify(build(), List(7) { 0 })
        assertTrue(project.rooms.getValue("91F8").saveStationSpawns.isEmpty())
    }

    @Test fun `verified original scroll source edits agree with real Asar and resizing is blocked before assembly`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val catalog = AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser)
        assertEquals(0x92B0, catalog.symbols["RoomPLM_LandingSite_0"])
        val checkedStreams = mutableSetOf<Int>()
        for ((pointer, consumers) in catalog.consumers) {
            for (consumer in consumers.distinctBy { it.roomId }) {
                val header = assertNotNull(parser.readRoomHeader(consumer.roomId))
                for (trigger in parser.parsePlmSet(pointer).filter { it.id == 0xB703 }) {
                    val decoded = com.supermetroid.editor.asm.AsmScrollCommandSource.original(catalog.originalText,
                        catalog.symbols, trigger.param, parser, header.width * header.height, catalog.originalText)
                    assertEquals(RomParser.decodeScrollCommands(parser, trigger.param, header.width).map { (screen, _, value) ->
                        com.supermetroid.editor.data.ScrollCommand(screen, value)
                    }, decoded, "${consumer.roomName}: ${trigger.param.toString(16)}")
                    checkedStreams += trigger.param
                }
            }
        }
        assertTrue(checkedStreams.size > 100, "Cover the vanilla command graph, not only Landing Site")
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        bridge.migrateRoom(0x91F8, "Landing Site")
        val stateId = project.rooms.getValue("91F8").states.first { state ->
            bridge.managedView(0x91F8, state.id).document!!.entries.any { it.id == 0xB703 }
        }.id
        val trigger = bridge.managedView(0x91F8, stateId).document!!.entries.single { it.id == 0xB703 }
        val text = bridge.sourceText()
        val declaration = Regex("(?m)^RoomPLM_LandingSite_0:\\r?\\n[^\\r\\n]+").find(text)!!
        val changed = declaration.value.replace("${'$'}13,${'$'}01", "${'$'}13,${'$'}00")
        assertNotEquals(declaration.value, changed)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, text.replaceRange(declaration.range, changed))
        assertEquals(listOf(com.supermetroid.editor.data.ScrollCommand(0x13, 0)), bridge.scrollCommands(0x91F8, stateId, trigger))
        assertTrue(workspace.saveAllSources())
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        val built = RomParser(result.resultRom)
        assertEquals(0, RomParser.decodeScrollCommands(built, trigger.param, 9).single().third)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, bridge.sourceText().replace(changed, changed.replace("${'$'}80", "${'$'}01,${'$'}01,${'$'}80")))
        assertTrue(workspace.saveAllSources())
        logs.clear()
        assertNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path))
        assertTrue(logs.any { it.contains("original stream resizing") })
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test fun `authored scroll commands migrate grow and assemble with real pointers without losing ROM patches`() = runBlocking {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        val room = project.getOrCreateRoom(0xACB3)
        val initialCommands = listOf(com.supermetroid.editor.data.ScrollCommand(0, 1), com.supermetroid.editor.data.ScrollCommand(3, 2))
        room.customScrollCommands["cmd_0"] = initialCommands.toMutableList()
        room.plmChanges += PlmChange("add", 0xB703, 4, 5, 0xCC00)
        project.patches += SmPatch(id = "scroll-test-patch", name = "Independent test byte", enabled = true,
            writes = mutableListOf(PatchWrite(offset = 0x2FFFF0, bytes = listOf(0x42))))
        val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
        val workspace = AsmWorkspaceState()
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(room.roomId, "Bubble Mountain")
        val stateId = room.states.single().id
        val logs = mutableListOf<String>()
        fun build(): RomBuildResult {
            logs.clear()
            return assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path), logs.joinToString("\n"))
        }
        fun verify(result: RomBuildResult, commands: List<com.supermetroid.editor.data.ScrollCommand>) {
            val built = RomParser(result.resultRom)
            val entries = built.parsePlmSet(built.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
            val trigger = entries.single { it.id == 0xB703 && it.x == 4 && it.y == 5 }
            assertTrue(trigger.param in 0x8000..0xFFFF)
            assertEquals(commands, RomParser.decodeScrollCommands(built, trigger.param, 2).map { (screen, _, value) ->
                com.supermetroid.editor.data.ScrollCommand(screen, value)
            })
            assertEquals(0x42, result.resultRom[0x2FFFF0].toInt() and 255)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM })
            val generated = AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)!!
            assertTrue(generated.lines.any { it.contains("%SmeditObjects_ACB3_Scroll_") })
            assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        }
        assertTrue(workspace.saveAllSources())
        val first = build()
        verify(first, initialCommands)
        val built = RomParser(first.resultRom)
        val oracleParser = RomParser(oracle.resultRom)
        val firstEntries = built.parsePlmSet(built.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
        val oracleEntries = oracleParser.parsePlmSet(oracleParser.parseRoomStatesWithData(room.roomId).single().plmSetPtr)
        assertEquals(oracleEntries.map { if (it.id == 0xB703) it.copy(param = 0) else it }, firstEntries.map { if (it.id == 0xB703) it.copy(param = 0) else it })
        // Raw ASM updates the preview; then a GUI edit grows the same semantic resource.
        workspace.updateSourceEditText(PLM_SOURCE_FILE, bridge.sourceText().replace("db ${'$'}00, ${'$'}01", "db ${'$'}00, ${'$'}00"))
        val trigger = bridge.managedView(room.roomId, stateId).document!!.entries.single { it.id == 0xB703 }
        assertEquals(0, bridge.scrollCommands(room.roomId, stateId, trigger).first().scrollValue)
        val grown = listOf(com.supermetroid.editor.data.ScrollCommand(0, 0), com.supermetroid.editor.data.ScrollCommand(1, 1),
            com.supermetroid.editor.data.ScrollCommand(2, 2), com.supermetroid.editor.data.ScrollCommand(3, 1))
        bridge.editScroll(room.roomId, stateId, 4, 5, grown)
        assertTrue(workspace.saveAllSources())
        verify(build(), grown)
        // Empty streams are legitimate no-ops, not truncated data; preserve the terminator.
        bridge.editScroll(room.roomId, stateId, 4, 5, emptyList())
        assertTrue(workspace.saveAllSources())
        verify(build(), emptyList())
    }

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
        workspace.bindProject(fixture.projectFile.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser), workspace) { project }
        bridge.migrateRoom(room.roomId, "Bubble Mountain")
        bridge.preloadProjections()
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
        assertNull(authored.table(room.roomId, state).objects.last().commonMacro)
        assertTrue(!bridge.sourceText().contains("SmeditObjects_ACB3_Common_"))
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
        // A real legacy multi-state helper becomes direct rows before the same checked Asar build.
        val commonEntry = RomParser.PlmEntry(0xEEDF, 9, 9, 0x101)
        val legacyHelper = "SmeditObjects_91F8_Common_7"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(commonEntry), "%$legacyHelper()") +
            "\nmacro $legacyHelper()\n    ${AsmRoomObjectSource.renderEntry(commonEntry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        assertTrue(workspace.saveAllSources())
        assertTrue(!bridge.sourceText().contains("SmeditObjects_91F8_Common_"))
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
