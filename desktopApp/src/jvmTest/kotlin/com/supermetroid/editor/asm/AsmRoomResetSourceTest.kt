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

class AsmRoomResetSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState)
    private suspend fun fixture(adopt: Boolean = true, incoming: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_A1.asm\nincsrc bank_80.asm\n")
        listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, ENEMY_SOURCE_FILE, "bank_80.asm").forEach {
            File(src, it).writeText("; Immutable original $it\r\n")
        }
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Reset.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            word(pc + 6, 0x8800); word(pc + 8, 0x8800); word(pc + 10, 0x8800)
        }
        SourceFxResource("native", listOf(emptyDefaultFxEntry().copy(fxType = 6))).bytes().copyInto(rom, 0x18800)
        val actor = RomParser.EnemyEntry(0xD13F, 40, 56, 1, 2, 3, 4, 5)
        SourceEnemyPopulation("native", listOf(actor), 1).bytes().copyInto(rom, 0x108800)
        SourceEnemyGraphics("native", listOf(RomParser.EnemyGfxEntry(actor.id, 1))).bytes().copyInto(rom, 0x1A0800)
        val speciesPc = 0x100000 + (actor.id and 0x7FFF)
        word(speciesPc, 32); rom[speciesPc + 12] = 0xA2.toByte()
        word(speciesPc + 54, actor.id); rom[speciesPc + 56] = 0xAD.toByte()
        if (incoming) {
            rom[0x79C89 + 4] = 2; rom[0x79C89 + 5] = 2
            word(0x79C89 + 11, 0xE5E6); word(0x79C89 + 9, 0x9800)
            rom[0x79C96 + 1] = 0x80.toByte(); rom[0x79C96 + 2] = 0xC0.toByte()
            word(0x79800, 0x8020); word(0x18020, 0x91F8)
        }
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val room = project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        room.plmChanges += PlmChange("add", 0xB76F, 8, 8, 0x8002)
        room.saveStationSpawns += SaveStationSpawnChange(0, 2, room.roomId, 0x8000, 256, 0, 128, 128)
        room.scrollChanges += ScrollChange(0, 0, 2, 0)
        room.fxChange = FxChange(fxType = 2)
        room.enemyChanges += EnemyChange("update", actor.id, 64, 56, 1, 2, 3, 4, 5, origX = 40, origY = 56)
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap())
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        if (adopt) {
            bridge.migrateRoom(room.roomId, "Test"); bridge.initialScrolls.migrate(room.roomId, "Test")
            bridge.fx.migrate(room.roomId, "Test"); bridge.enemies.migrate(room.roomId, "Test")
        }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(room.roomId, parser, parser.readRoomHeader(room.roomId)!!)
            setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
        return Fixture(workspace, bridge, project, editor)
    }
    private val banks = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, ENEMY_SOURCE_FILE)
    private fun Fixture.sources() = banks.associateWith { workspace.projectSourceText(it) }

    @Test fun `reset restores all native resources and reciprocal cross bank undo restores the original room identity`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val texts = f.sources()
        val level = f.editor.workingLevelData!!.copyOf()
        val objects = f.editor.workingPlms.toList()
        val actors = f.editor.workingEnemies.toList()
        val grid = f.editor.workingScrolls.copyOf()
        val other = f.project.getOrCreateRoom(0x9C89).also { it.fxChange = FxChange(fxType = 4) }
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        val nativeRoom = f.project.rooms.getValue("91F8")
        assertFalse(nativeRoom.hasEdits)
        val resetHead = f.workspace.sourceUndoHead(PLM_SOURCE_FILE)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        assertSame(resetHead, f.workspace.sourceUndoHead(PLM_SOURCE_FILE), "Resetting an already native room must not add history")
        assertEquals(32, f.editor.workingBlocksWide)
        assertEquals(listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5)), f.editor.workingPlms)
        assertEquals(40, f.editor.workingEnemies.single().x)
        assertEquals(6, f.editor.effectiveFxEntries(room.roomId, f.bridge.catalog.parser).single().fxType)
        assertSame(other, f.project.rooms["9C89"])
        f.sources().values.forEach { assertFalse(Regex("(?m)^macro Smedit(?:Objects|Scrolls|Fx|Enemies)_91F8_").containsMatchIn(it)) }
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertSame(room, f.project.rooms["91F8"])
        assertEquals(before, room)
        assertEquals(texts, f.sources())
        assertContentEquals(level, f.editor.workingLevelData)
        assertContentEquals(grid, f.editor.workingScrolls)
        assertEquals(objects, f.editor.workingPlms); assertEquals(actors, f.editor.workingEnemies)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertSame(nativeRoom, f.project.rooms["91F8"])
        assertTrue(f.workspace.undoSourceEdit(ENEMY_SOURCE_FILE), f.workspace.error)
        assertTrue(f.editor.undo(), "The preceding resize must remain undoable after undoing reset: ${f.editor.statusMessage}")
        assertEquals(32, f.editor.workingBlocksWide)
        assertSame(room, f.project.rooms["91F8"])
    }

    @Test fun `reset removes added states and restores their selected preview and sharing on undo`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        val room = f.project.rooms.getValue("91F8")
        val template = f.editor.currentPlmStateId()!!
        val added = f.editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 12), parser)
        f.editor.switchRoomState(added, parser)
        f.bridge.fx.edit(room.roomId, added, 0, FxChange(fxType = 4))
        val before = snapshotRoomForResize(room)
        val sources = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(parser), f.editor.statusMessage)
        assertEquals(2, f.project.rooms.getValue("91F8").states.size)
        assertFalse(f.project.rooms.getValue("91F8").stateGraphChanged)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertSame(room, f.project.rooms["91F8"]); assertEquals(before, room)
        assertEquals(added, f.editor.currentPlmStateId())
        assertEquals(sources, f.sources())
        assertEquals(4, f.editor.effectiveFxEntries(room.roomId, parser).single().fxType)
        assertTrue(f.workspace.redoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertEquals(2, f.project.rooms.getValue("91F8").states.size)
    }

    @Test fun `newer source metadata or external reference changes block the entire reset undo`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        val reset = f.sources()
        val clean = f.project.rooms.getValue("91F8")
        clean.roomHeaderChange = RoomHeaderChange(mapX = 7)
        assertFalse(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertEquals(reset, f.sources()); assertSame(clean, f.project.rooms["91F8"])
        clean.roomHeaderChange = null
        val external = f.workspace.projectSourceText("bank_80.asm")
        f.workspace.replaceProjectSource("bank_80.asm", external, "$external%SmeditObjects_91F8_List_1()\n")
        assertFalse(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertEquals(reset, f.sources())
        assertTrue(f.workspace.undoSourceEdit("bank_80.asm"))
        val fx = reset.getValue(FX_SOURCE_FILE)
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, fx, "$fx; newer raw source\n")
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertSame(clean, f.project.rooms["91F8"])
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertTrue(f.workspace.undoSourceEdit(ENEMY_SOURCE_FILE), f.workspace.error)
        assertTrue(f.project.rooms.getValue("91F8").hasEdits)
    }

    @Test fun `legacy room reset has a metadata only source undo anchor and preserves unrelated banks`() = runBlocking {
        val f = fixture(adopt = false)
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val sources = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        assertEquals(sources, f.sources())
        assertFalse(f.project.rooms.getValue("91F8").hasEdits)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertSame(room, f.project.rooms["91F8"]); assertEquals(before, room)
        assertEquals(sources, f.sources())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
    }

    @Test fun `incoming destination on an expanded screen blocks reset before any source or model change`() = runBlocking {
        val f = fixture(incoming = true)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        f.project.getOrCreateRoom(0x9C89).doorChanges += DoorChange(0, 0x91F8, 0, 0, 2, 0, 0, 0)
        val before = snapshotRoomForResize(f.project.rooms.getValue("91F8"))
        val sources = f.sources()
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser))
        assertContains(f.editor.statusMessage, "incoming door")
        assertEquals(before, f.project.rooms["91F8"]); assertEquals(sources, f.sources())
    }

    @Test fun `saved reset reopens as native data and can adopt all four resource families again`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        assertTrue(f.editor.resetCurrentRoomToOriginal(parser), f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString(SmEditProject.serializer(), Json.encodeToString(SmEditProject.serializer(), f.project))
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        val file = File(directory, "Reset.smedit")
        workspace.bindProject(file.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { reopened }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, reopened); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        }
        assertFalse(reopened.rooms.getValue("91F8").hasEdits)
        assertEquals(40, editor.workingEnemies.single().x)
        assertTrue(editor.ensureCurrentPlmSource("Test"), editor.statusMessage)
        assertTrue(editor.ensureEnemySource("Test"), editor.statusMessage)
        assertTrue(editor.ensureFxSource("Test"), editor.statusMessage)
        editor.setScroll(0, 0, 0, 2)
        val room = reopened.rooms.getValue("91F8")
        assertEquals(1, room.asmInitialScrollSourceVersion)
        assertEquals(1, room.asmPlmSourceVersion); assertEquals(1, room.asmFxSourceVersion); assertEquals(1, room.asmEnemySourceVersion)
        assertNull(editor.initialScrollPreviewProblem); assertNull(editor.fxPreviewProblem); assertNull(editor.enemyPreviewProblem)
    }

    @Test fun `source undo from another room does not replace its preview or editor history`() = runBlocking {
        val f = fixture(incoming = true)
        val parser = f.bridge.catalog.parser
        val original = f.project.rooms.getValue("91F8")
        assertTrue(f.editor.resetCurrentRoomToOriginal(parser), f.editor.statusMessage)
        f.editor.loadRoom(0x9C89, parser, parser.readRoomHeader(0x9C89)!!)
        val pixels = f.editor.workingLevelData!!.copyOf()
        val objects = f.editor.workingPlms.toList()
        val history = f.editor.undoStack.toList()
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertSame(original, f.project.rooms["91F8"])
        assertEquals(0x9C89, f.editor.currentRoomId)
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(objects, f.editor.workingPlms); assertEquals(history, f.editor.undoStack)
        assertTrue(f.workspace.redoSourceEdit(ENEMY_SOURCE_FILE), f.workspace.error)
        assertEquals(0x9C89, f.editor.currentRoomId)
        assertContentEquals(pixels, f.editor.workingLevelData)
    }

    @Test fun `cleanup preserves external and transitive cross bank helpers notes and unknown custom declarations`() {
        val room = RoomEdits(0x91F8, asmPlmSourceVersion = 1, asmFxSourceVersion = 1)
        val objects = """
            ; Original bank
            macro SmeditObjects_91F8_List_1()
                %SmeditFx_91F8_Table_1()
            endmacro
            macro SmeditObjects_91F8_List_2()
                dw ${'$'}0000 ; my retired note
            endmacro
            macro SmeditObjects_91F8_MyRoutine()
                nop
            endmacro
            macro SmeditObjects_9C89_List_1()
                dw ${'$'}0000
            endmacro
        """.trimIndent() + "\n"
        val fx = "macro SmeditFx_91F8_Table_1()\n dw ${'$'}FFFF\nendmacro\n"
        val result = resetRoomSource(room, mapOf(PLM_SOURCE_FILE to objects, FX_SOURCE_FILE to fx),
            sequenceOf("; %SmeditObjects_91F8_List_2()\n%SmeditObjects_91F8_List_1()"))
        assertContains(result.getValue(PLM_SOURCE_FILE), "macro SmeditObjects_91F8_List_1()")
        assertFalse(result.getValue(PLM_SOURCE_FILE).contains("macro SmeditObjects_91F8_List_2()"))
        assertContains(result.getValue(PLM_SOURCE_FILE), "; my retired note")
        assertContains(result.getValue(PLM_SOURCE_FILE), "macro SmeditObjects_91F8_MyRoutine()")
        assertContains(result.getValue(PLM_SOURCE_FILE), "macro SmeditObjects_9C89_List_1()")
        assertEquals(fx, result.getValue(FX_SOURCE_FILE))
    }

    @Test fun `cleanup refuses duplicate missing and nested authored macro envelopes`() {
        val room = RoomEdits(0x91F8, asmFxSourceVersion = 1)
        val valid = "macro SmeditFx_91F8_Table_1()\n dw ${'$'}FFFF\nendmacro\n"
        for (invalid in listOf(valid + valid, valid.substringBefore("endmacro"), "macro Custom()\n$valid" + "endmacro\n",
            valid.replace("dw ${'$'}FFFF", "macro Custom()\nendmacro"))) {
            assertFails { resetRoomSource(room, mapOf(FX_SOURCE_FILE to invalid), emptySequence()) }
        }
    }
}
