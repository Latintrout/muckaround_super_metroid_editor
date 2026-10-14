package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.saveSourceTestRom
import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** ROM-only authoring must obey the same scope policy as authored ASM, without external fixtures. */
class PlmStateScopeTest {
    @TempDir lateinit var directory: File

    @Test fun `ROM object fields keep all state scope and form one reversible exportable operation`() {
        val (editor, parser) = fixture()
        editor.addPlm(0xEEDB, 12, 9, 0x60)
        val before = editor.workingPlms.toList()
        val old = before.single { it.x == 12 && it.y == 9 && it.id == 0xEEDB }
        val replacement = old.copy(id = 0xEEDF, x = 13)
        editor.updateRoomObjectFields(editor.currentRoomId, editor.currentPlmStateId(), before, before.indexOf(old), replacement)
        assertContains(editor.workingPlms, replacement)
        assertTrue(editor.plmStateMembership(replacement).allStates)
        assertTrue(editor.undo())
        assertEquals(before, editor.workingPlms)
        assertTrue(editor.redo())
        assertContains(editor.workingPlms, replacement)
        val target = parser.getRomData().copyOf()
        ProjectRoomExporter(editor.project, parser, target).exportRooms()
        val built = RomParser(target)
        built.parseRoomStatesWithData(editor.currentRoomId).forEach { state ->
            assertContains(built.parsePlmSet(state.plmSetPtr), replacement)
            assertFalse(old in built.parsePlmSet(state.plmSetPtr))
        }
        assertFalse(editor.project.asmWorkspace.enabled)
    }

    @Test fun `ROM object fields preserve private placement and stale or compound edits fail before mutation`() {
        val (editor, parser) = fixture()
        editor.addPlm(0xEEDB, 12, 9, 0x60, ItemStateScope.THIS_STATE)
        val before = editor.workingPlms.toList()
        val old = before.single { it.x == 12 && it.y == 9 && it.id == 0xEEDB }
        val state = editor.currentPlmStateId()
        val replacement = old.copy(id = 0xEEDF)
        editor.updateRoomObjectFields(editor.currentRoomId, state, before, before.indexOf(old), replacement)
        assertFalse(editor.plmStateMembership(replacement).allStates)
        assertFails { editor.updateRoomObjectFields(editor.currentRoomId, state, before, before.indexOf(old), old) }
        val current = editor.workingPlms.toList()
        assertFails { editor.updateRoomObjectFields(editor.currentRoomId, state, current, current.indexOf(replacement), replacement.copy(id = 0xB76F)) }
        assertFails { editor.updateRoomObjectFields(editor.currentRoomId, state, current, current.indexOf(replacement), replacement.copy(x = 255)) }
        assertEquals(current, editor.workingPlms)
        val sibling = editor.project.rooms.getValue("91F8").states.first { it.id != state }.id
        editor.switchRoomState(sibling, parser)
        assertFalse(replacement in editor.workingPlms)
    }
    private fun fixture(project: SmEditProject = SmEditProject("test.sfc")): Pair<EditorState, RomParser> {
        val bytes = saveSourceTestRom()
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(bytes, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            bytes[pc] = 0; bytes[pc + 1] = 0x80.toByte(); bytes[pc + 2] = 0xC0.toByte()
        }
        val parser = RomParser(bytes)
        project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        val editor = EditorState().apply {
            testMode = true
            initForProject(File(directory, "Test.smedit"), project)
            loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        }
        return editor to parser
    }

    @Test fun `all ordinary additions are room wide and survive reopen future conditions and export`() {
        val (editor, parser) = fixture()
        assertEquals(ItemStateScope.ALL_STATES, editor.itemEditScope)
        listOf(0xEEDF, 0xB6DF, 0xB6EB, 0xB6D3, 0xC836, 0xC8A2, 0xB647).forEachIndexed { index, id ->
            editor.addPlm(id, 5 + index, 8, if (id == 0xC836) 8 else 0)
        }
        val additions = editor.workingPlms.filter { it.x in 5..11 }
        assertEquals(8, additions.size)
        val room = editor.project.rooms.getValue("91F8")
        assertTrue(room.states.all { it.plmChanges.isEmpty() })
        assertTrue(additions.all { editor.plmStateMembership(it).allStates })
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), editor.project))
        val (reopened, reparser) = fixture(restored)
        assertTrue(reopened.workingPlms.containsAll(additions))
        val added = reopened.addRoomState(restored.rooms.getValue("91F8").states.last().id,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 63), reparser)
        reopened.switchRoomState(added, reparser)
        assertTrue(reopened.workingPlms.containsAll(additions))
        val target = parser.getRomData().copyOf()
        ProjectRoomExporter(editor.project, parser, target).exportRooms()
        val built = RomParser(target)
        for (state in built.parseRoomStatesWithData(0x91F8)) assertTrue(built.parsePlmSet(state.plmSetPtr).containsAll(additions))
        assertFalse(editor.project.asmWorkspace.enabled, "ROM authoring must not silently enable ASM")
    }

    @Test fun `private placement stays private on recoloring and default deletion`() {
        val (editor, parser) = fixture()
        editor.selectItemEditScope(ItemStateScope.THIS_STATE)
        editor.addPlm(0xC836, 8, 8, 8)
        editor.selectItemEditScope(ItemStateScope.ALL_STATES)
        editor.addPlm(0xC836, 8, 8, 10)
        val gate = editor.workingPlms.single { it.id == 0xC836 }
        assertEquals("This state", editor.plmStateMembership(gate).label)
        val room = editor.project.rooms.getValue("91F8")
        assertTrue(room.plmChanges.isEmpty())
        val current = editor.currentStateIndex
        val other = room.states.first { it.sourceStateIndex != current }.id
        editor.removePlm(8, 8, gate.id)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        assertTrue(editor.undo())
        assertEquals(10, editor.workingPlms.single { it.id == gate.id }.param)
        editor.switchRoomState(other, parser)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
    }

    @Test fun `save last reference release and undo retain manual spawn values`() {
        val (editor, parser) = fixture()
        val helperState = editor.project.rooms.getValue("91F8").states.first { it.sourceStateIndex != editor.currentStateIndex }
        helperState.plmChanges += PlmChange("add", 0xDB44, 8, 8, 8)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val station = editor.workingPlms.single { it.id == 0xB76F }
        assertEquals(2, station.param and 255)
        assertTrue(helperState.plmChanges.any { it.plmId == 0xDB44 }, "Keep the script-only setup PLM")
        editor.updateSaveStationSpawnFields(0, 2, -32, 144, 256, 0, parser)
        val spawn = editor.project.rooms.getValue("91F8").saveStationSpawns.single()
        editor.removePlm(8, 8, station.id, ItemStateScope.THIS_STATE)
        assertEquals(spawn, editor.project.rooms.getValue("91F8").saveStationSpawns.single())
        assertTrue(editor.undo())
        editor.removePlm(8, 8, station.id)
        assertTrue(editor.project.rooms.getValue("91F8").saveStationSpawns.isEmpty())
        assertTrue(editor.undo())
        assertEquals(spawn, editor.project.rooms.getValue("91F8").saveStationSpawns.single())
        assertTrue(editor.workingPlms.contains(station))
        assertTrue(editor.redo())
        assertTrue(editor.workingPlms.none { it.id == station.id })
    }

    @Test fun `all state commands and their PLM undo atomically without masking local resources`() {
        val (editor, parser) = fixture()
        val original = listOf(ScrollCommand(0, 1))
        editor.addScrollTriggerWithCommands(8, 8, original)
        val trigger = editor.workingPlms.single { it.id == 0xB703 }
        assertTrue(editor.plmStateMembership(trigger).allStates)
        assertTrue(editor.undo())
        assertTrue(editor.project.rooms.getValue("91F8").customScrollCommands.isEmpty())
        assertTrue(editor.workingPlms.none { it.id == trigger.id })
        assertTrue(editor.redo())
        assertEquals(original, editor.scrollCommandsFor(trigger))
        val edited = listOf(ScrollCommand(1, 2))
        assertTrue(editor.updateScrollTriggerCommands(trigger, edited))
        val current = editor.workingPlms.single { it.id == trigger.id }
        assertTrue(editor.plmStateMembership(current).allStates)
        for (state in editor.project.rooms.getValue("91F8").states.toList()) {
            editor.switchRoomState(state.id, parser)
            assertEquals(edited, editor.scrollCommandsFor(editor.workingPlms.single { it.id == trigger.id }))
        }
    }

    @Test fun `conflicts and different collision geometry fail before any object or metadata mutation`() {
        val (editor, _) = fixture()
        val room = editor.project.rooms.getValue("91F8")
        val other = room.states.first { it.sourceStateIndex != editor.currentStateIndex }
        other.plmChanges += PlmChange("add", 0xB6DF, 8, 8, 0)
        val before = Json.encodeToString(SmEditProject.serializer(), editor.project)
        editor.addPlm(0xB6EB, 8, 8, 0)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), editor.project))
        assertContains(editor.statusMessage, "occupies this position")
        other.operations += EditOperation("Different wall", listOf(TileEdit(12, 8, 0, 0x8000)))
        val geometry = Json.encodeToString(SmEditProject.serializer(), editor.project)
        editor.addPlm(0xC836, 12, 8, 8)
        assertEquals(geometry, Json.encodeToString(SmEditProject.serializer(), editor.project))
        assertContains(editor.statusMessage, "geometry differs")
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
    }

    @Test fun `explicit local edit overrides inferred shared membership not the default for new additions`() {
        val (editor, parser) = fixture()
        editor.addPlm(0xC836, 8, 8, 8)
        val gate = editor.workingPlms.single { it.id == 0xC836 }
        editor.selectPlmEditScope(gate, ItemStateScope.THIS_STATE)
        editor.addPlm(0xC836, 8, 8, 10)
        assertEquals(ItemStateScope.ALL_STATES, editor.itemEditScope)
        assertEquals("This state", editor.plmStateMembership(editor.workingPlms.single { it.id == 0xC836 }).label)
        assertTrue(editor.undo())
        assertTrue(editor.plmStateMembership(gate).allStates)
        assertTrue(editor.redo())
        val other = editor.project.rooms.getValue("91F8").states.first { it.sourceStateIndex != editor.currentStateIndex }.id
        editor.switchRoomState(other, parser)
        assertEquals(8, editor.workingPlms.single { it.id == 0xC836 }.param)
    }

    @Test fun `gate body membership includes its shot block and removal never leaves a half gate`() {
        val (editor, parser) = fixture()
        editor.addPlm(0xC836, 8, 8, 8)
        val shot = editor.workingPlms.single { it.id == 0xC836 }
        editor.selectPlmEditScope(shot, ItemStateScope.THIS_STATE)
        editor.addPlm(shot.id, 8, 8, 10)
        val body = editor.workingPlms.single { it.id == 0xC82A }
        assertFalse(editor.plmStateMembership(body).allStates, "Equal body rows can belong to different shot gates")
        editor.removePlm(body.x, body.y, body.id)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        assertTrue(editor.undo())
        assertEquals(10, editor.workingPlms.single { it.id == 0xC836 }.param)
        val other = editor.project.rooms.getValue("91F8").states.first { it.sourceStateIndex != editor.currentStateIndex }.id
        editor.switchRoomState(other, parser)
        assertEquals(8, editor.workingPlms.single { it.id == 0xC836 }.param)
        assertEquals(1, editor.workingPlms.count { it.id == 0xC82A })
    }

    @Test fun `all state gate pattern validates geometry before painting and undoes tiles with both PLMs`() {
        val (editor, parser) = fixture()
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.setBrushForTest(TileBrush(tiles = listOf(listOf(1)), blockType = 8,
            plmOverrides = mapOf(0L to (0xC836 to 8))))
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        editor.endStroke()
        assertEquals(0x8001, editor.readBlockWord(8, 8))
        assertEquals(2, editor.workingPlms.count { RomParser.isGatePlm(it.id) })
        assertTrue(editor.plmStateMembership(editor.workingPlms.single { it.id == 0xC836 }).allStates)
        assertTrue(editor.undo())
        assertEquals(0, editor.readBlockWord(8, 8))
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        assertTrue(editor.redo())
        val other = editor.project.rooms.getValue("91F8").states.first { it.sourceStateIndex != editor.currentStateIndex }.id
        editor.switchRoomState(other, parser)
        assertEquals(0x8001, editor.readBlockWord(8, 8))
        assertEquals(2, editor.workingPlms.count { RomParser.isGatePlm(it.id) })
    }
}
