package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import kotlin.test.*

class AsmRoomScrollSourcesTest {
    private val name = "SmeditObjects_91F8_Scroll_1"
    private val resource = SourceScrollResource(name, listOf(ScrollCommand(0, 1), ScrollCommand(3, 2)))
    private fun report(bytes: ByteArray = serializeScrollCommands(resource.commands), owner: String = ASM_ROOM_GRAPH_OWNER,
                       pc: Int = 0x7F000) = RomWritePlanReport(listOf(
        RomWriteIntent(owner, "Room 91F8 source scrolls $name", pc, bytes.map { it.toInt() and 255 }, RomWriteKind.ALLOCATION),
    ), emptyList(), emptyList(), emptyList())

    @Test fun `named command placement is byte faithful header independent and emitted from authored macro`() {
        for (header in listOf(0, 0x200)) {
            val rom = ByteArray(0x300000 + header)
            serializeScrollCommands(resource.commands).copyInto(rom, header + 0x7F000)
            val table = discoverAsmRoomScrollTables(report(), rom, mapOf(name to resource)).single()
            assertContentEquals(byteArrayOf(0, 1, 3, 2, 0x80.toByte()), table.claim().bytes)
            assertContains(table.renderSource(), "$name:\n    %$name()")
            assertContains(table.renderSource(), "assert ${name}_End-$name == 5")
            assertFalse(table.renderSource().contains("db "), "The placement report must not become a second command-value authority")
        }
    }

    @Test fun `command promotion refuses missing allocations byte drift overlapping owners and bank spill`() {
        val rom = ByteArray(0x300000)
        serializeScrollCommands(resource.commands).copyInto(rom, 0x7F000)
        assertFailsWith<IllegalStateException> { discoverAsmRoomScrollTables(report(owner = "another"), rom, mapOf(name to resource)) }
        assertFailsWith<IllegalArgumentException> { discoverAsmRoomScrollTables(report(byteArrayOf(0, 0, 0x80.toByte())), rom, mapOf(name to resource)) }
        val overlap = report().copy(writes = report().writes + RomWriteIntent("patch:test", "Conflict", 0x7F001, listOf(1)))
        assertFailsWith<IllegalArgumentException> { discoverAsmRoomScrollTables(overlap, rom, mapOf(name to resource)) }
        assertFailsWith<IllegalArgumentException> { AsmRoomScrollTable(resource, 0x7FFFF).claim() }
    }

    @Test fun `shared resource identities allocate once while independent equal streams stay independent`() {
        val original = objectSourceTestRom().also { it.fill(0xFF.toByte(), 0x7F000, 0x80000) }
        val parser = RomParser(original)
        val project = SmEditProject("test.sfc")
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(parser)
        room.asmPlmSourceVersion = 1
        val entry = RomParser.PlmEntry(0xB703, 4, 5, 0)
        val input = SourcePlmResource("SmeditObjects_91F8_List_1", listOf(entry), mapOf(0 to resource))
        val target = original.copyOf()
        val export = ProjectRoomExporter(project, parser, target, sourcePlmSets = mapOf(room.roomId to room.states.associate { it.id to input })).exportRooms()
        assertEquals(1, export.allocations.count { it.label.contains("source scrolls") })
        assertEquals(1, export.allocations.count { it.label.contains("source PLMs") })
        val built = RomParser(target)
        val states = built.parseRoomStatesWithData(room.roomId)
        assertEquals(1, states.map { it.plmSetPtr }.distinct().size)
        val pointer = built.parsePlmSet(states.first().plmSetPtr).single().param
        assertEquals(resource.commands, RomParser.decodeScrollCommands(built, pointer, 2).map { (screen, _, value) -> ScrollCommand(screen, value) })
        val alternate = input.copy(identity = "SmeditObjects_91F8_List_2", scrollReferences = mapOf(0 to resource.copy(identity = "SmeditObjects_91F8_Scroll_2")))
        val independent = original.copyOf()
        val separate = ProjectRoomExporter(project, parser, independent,
            sourcePlmSets = mapOf(room.roomId to mapOf(room.states.first().id to input, room.states.last().id to alternate))).exportRooms()
        assertEquals(2, separate.allocations.count { it.label.contains("source scrolls") })
        val separateParser = RomParser(independent)
        val pointers = separateParser.parseRoomStatesWithData(room.roomId).map { separateParser.parsePlmSet(it.plmSetPtr).single().param }
        assertEquals(2, pointers.distinct().size)
        assertContentEquals(original, parser.getRomData())
    }
}
