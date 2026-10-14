package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ensureStateManifest
import kotlin.test.*

class AsmScrollCommandSourceTest {
    private val parser get() = RomParser(objectSourceTestRom())
    private val commands = listOf(ScrollCommand(0, 1), ScrollCommand(3, 2))
    private val name = "SmeditObjects_91F8_Scroll_1"

    private fun source(): Pair<RoomEdits, AsmRoomObjectSource> {
        val room = RoomEdits(0x91F8)
        val migration = AsmRoomObjectSource.migrate("; untouched bank\r\n", room, parser, "Test", emptyMap())
        room.states.addAll(migration.states)
        room.asmPlmSourceVersion = 1
        return room to AsmRoomObjectSource(migration.text)
    }

    @Test fun `command macro round trips numeric bases CRLF empty streams and one based comments`() {
        val text = AsmScrollCommandSource.render(name, commands, 2, 2)
        assertContains(text, "Screen (2, 2)")
        assertEquals(commands, AsmScrollCommandSource.fromText(text.replace("\n", "\r\n"), name, 4).commands)
        assertEquals(name, AsmScrollCommandSource.reference("$name&${'$'}FFFF"))
        assertEquals(commands, AsmScrollCommandSource.parse(name, "db %0, 1\ndb ${'$'}03, %10\ndb ${'$'}80", 4).commands)
        assertTrue(AsmScrollCommandSource.parse(name, "db ${'$'}80", 4).commands.isEmpty())
    }

    @Test fun `invalid expressions duplicates bounds values and terminators fail closed`() {
        for (body in listOf("db 0,3\ndb ${'$'}80", "db 4,1\ndb ${'$'}80", "db 0,1,0,2,${'$'}80",
            "db 0,1", "db ${'$'}80,1,${'$'}80", "db ${'$'}900000,1,${'$'}80", "dw 0,1,${'$'}80",
            "db ScreenSymbol,1,${'$'}80", "db 0,1,${'$'}80,0", "db 0,1,${'$'}80,0,${'$'}80")) {
            assertFailsWith<IllegalArgumentException>(body) { AsmScrollCommandSource.parse(name, body, 4) }
        }
    }

    @Test fun `GUI command edits author named references fork one state and clean unused resources`() {
        val (room, original) = source()
        var current = AsmRoomObjectSource(original.editScroll(room, "state-1", 4, 5, commands, 2, 2))
        val first = current.resources(room, 4)
        assertEquals(commands, first.getValue("state-1").scrollReferences.values.single().commands)
        assertTrue(first.getValue("state-2").scrollReferences.isEmpty())
        assertContains(current.text, "$name&${'$'}FFFF")
        assertTrue(current.text.startsWith("; untouched bank\r\n"))
        repeat(6) { n ->
            current = AsmRoomObjectSource(current.editScroll(room, "state-1", 4, 5, listOf(ScrollCommand(0, n % 3)), 2, 2))
            assertEquals(1, Regex("(?m)^macro SmeditObjects_91F8_Scroll_").findAll(current.text).count())
        }
        current = AsmRoomObjectSource(current.edit(room, "state-1", false, { it.id == 0xB703 }, null))
        assertFalse(current.text.contains("_Scroll_"))
        assertEquals(original.resources(room).mapValues { it.value.entries }, current.resources(room).mapValues { it.value.entries })
    }

    @Test fun `ASM shared command edits affect every consumer GUI detaches only selected state trigger`() {
        val (room, original) = source()
        var current = AsmRoomObjectSource(original.editScroll(room, "state-1", 4, 5, commands, 2, 2))
        val list = current.table(room.roomId, "state-1").resource.name
        val other = current.table(room.roomId, "state-2").root
        current = AsmRoomObjectSource(current.text.replaceRange(other.bodyStart, other.bodyEnd, "    %$list()\n"))
        current = AsmRoomObjectSource(current.text.replace("db ${'$'}00, ${'$'}01", "db ${'$'}00, ${'$'}00"))
        assertTrue(current.resources(room, 4).values.all { it.scrollReferences.values.single().commands.first().scrollValue == 0 })
        val beforeSecond = current.resources(room, 4).getValue("state-2")
        current = AsmRoomObjectSource(current.editScroll(room, "state-1", 4, 5, listOf(ScrollCommand(1, 2)), 2, 2))
        assertEquals(beforeSecond, current.resources(room, 4).getValue("state-2"))
        assertEquals(listOf(ScrollCommand(1, 2)), current.resources(room, 4).getValue("state-1").scrollReferences.values.single().commands)
    }

    @Test fun `custom command migration preserves room and state ownership but retires duplicate JSON values`() {
        val room = RoomEdits(0x91F8)
        room.ensureStateManifest(parser)
        room.customScrollCommands["cmd_0"] = commands.toMutableList()
        room.plmChanges += PlmChange("add", 0xB703, 4, 5, 0xCC00)
        room.states.last().customScrollCommands["cmd_0"] = mutableListOf(ScrollCommand(1, 0))
        val migration = AsmRoomObjectSource.migrate("", room, parser, "Test", emptyMap())
        val authored = room.copy(asmPlmSourceVersion = 1, plmChanges = mutableListOf(), customScrollCommands = linkedMapOf(),
            states = migration.states.map { it.copy(plmChanges = mutableListOf(), customScrollCommands = linkedMapOf()) }.toMutableList())
        val resources = AsmRoomObjectSource(migration.text).resources(authored, 4)
        assertEquals(commands, resources.getValue("state-1").scrollReferences.values.single().commands)
        assertEquals(listOf(ScrollCommand(1, 0)), resources.getValue("state-2").scrollReferences.values.single().commands)
        assertNotEquals(resources.getValue("state-1").identity, resources.getValue("state-2").identity)
        assertTrue(room.customScrollCommands.isNotEmpty(), "Staging migration must not change project data")
    }

    @Test fun `known original command source updates live and unsupported changed source never falls back to ROM`() {
        val text = "RoomPLM_Test:\n    db ${'$'}00, ${'$'}01, ${'$'}80\nOtherLabel:\n    db 5\n"
        val symbols = mapOf("RoomPLM_Test" to 0x8100)
        assertEquals(listOf(ScrollCommand(0, 1)), AsmScrollCommandSource.original(text, symbols, 0x8100, parser, 4))
        assertEquals(listOf(ScrollCommand(0, 2)), AsmScrollCommandSource.original(text.replace("${'$'}01", "${'$'}02"), symbols, 0x8100, parser, 4))
        assertFailsWith<IllegalArgumentException> { AsmScrollCommandSource.original(text.replace("${'$'}01", "Expression"), symbols, 0x8100, parser, 4) }
        assertFailsWith<IllegalArgumentException> { AsmScrollCommandSource.original("", symbols, 0x8100, parser, 4) }
    }

    @Test fun `matching pointer addresses never replace a hacks commands or silently accept two conflicting authors`() {
        val text = "RoomPLM_Test:\n    db ${'$'}00, ${'$'}01, ${'$'}80\n"
        val symbols = mapOf("RoomPLM_Test" to 0x8100)
        val rom = objectSourceTestRom()
        byteArrayOf(0, 2, 0x80.toByte()).copyInto(rom, 0x78100)
        val hack = RomParser(rom)
        assertEquals(listOf(ScrollCommand(0, 2)), AsmScrollCommandSource.original(text, symbols, 0x8100, hack, 4, text))
        assertNull(AsmScrollCommandSource.verifiedOriginalName(text, symbols, 0x8100, hack, 4))
        assertFailsWith<IllegalArgumentException> {
            AsmScrollCommandSource.original(text.replace("${'$'}01", "${'$'}00"), symbols, 0x8100, hack, 4, text)
        }
        assertFailsWith<IllegalArgumentException> {
            AsmScrollCommandSource.original(text.replace("${'$'}80", "${'$'}01, ${'$'}02, ${'$'}80"), symbols, 0x8100, hack, 4, text)
        }
    }
}
