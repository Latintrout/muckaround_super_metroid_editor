package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import kotlin.test.*

class AsmRoomSaveSourcesTest {
    private val save = SourceSaveResource("SmeditObjects_91F8_Save_0_2", 0x91F8, 0, 2, listOf(0x91F8, 0x8000, 7, 0, 0, 104, 0xFFE0))

    @Test fun `save source has one complete fourteen byte claim independent of copier header and state count`() {
        for (header in listOf(0, 0x200)) {
            val base = ByteArray(header) + saveSourceTestRom()
            val parser = RomParser(base)
            val project = SmEditProject("test.sfc")
            val room = project.getOrCreateRoom(0x91F8)
            room.ensureStateManifest(parser); room.asmPlmSourceVersion = 1
            val input = SourcePlmResource("SmeditObjects_91F8_List_1", listOf(RomParser.PlmEntry(0xB76F, 8, 8, 0x8002)), saves = listOf(save))
            val target = base.copyOf()
            val result = ProjectRoomExporter(project, parser, target, sourcePlmSets = mapOf(room.roomId to room.states.associate { it.id to input })).exportRooms()
            val claimed = result.sourceSaveClaims.single()
            assertEquals(header + 0x44E1, claimed.first)
            assertEquals(save.words, RomParser(target).readSaveEntry(0, 2)!!.words())
            val report = RomWritePlanReport(listOf(RomWriteIntent(ASM_ROOM_GRAPH_OWNER, "Source save ${save.identity}",
                claimed.first - header, save.bytes().map { it.toInt() and 255 }, RomWriteKind.ROOM)), emptyList(), emptyList(), emptyList())
            val table = discoverAsmRoomSaveTables(report, target, mapOf(save.identity to save)).single()
            assertContentEquals(save.bytes(), table.claim().bytes)
            assertContains(table.renderSource(), "org ${'$'}80C4E1")
            assertContains(table.renderSource(), "%${save.identity}()")
            assertFalse(table.renderSource().contains("dw "), "The generated placement is not a second value authority")
            assertContentEquals(base, parser.getRomData())
        }
    }

    @Test fun `save promotion rejects byte drift missing claims other owners and bank crossing`() {
        val rom = saveSourceTestRom()
        save.bytes().copyInto(rom, 0x44E1)
        val write = RomWriteIntent(ASM_ROOM_GRAPH_OWNER, "Source save ${save.identity}", 0x44E1, save.bytes().map { it.toInt() and 255 }, RomWriteKind.ROOM)
        val report = RomWritePlanReport(listOf(write), emptyList(), emptyList(), emptyList())
        assertFails { discoverAsmRoomSaveTables(report.copy(writes = emptyList()), rom, mapOf(save.identity to save)) }
        assertFails { discoverAsmRoomSaveTables(report.copy(writes = listOf(write.copy(bytes = List(14) { 0 }))), rom, mapOf(save.identity to save)) }
        assertFails { discoverAsmRoomSaveTables(report.copy(writes = listOf(write, RomWriteIntent("patch:other", "conflict", 0x44E1, listOf(0xF8)))), rom, mapOf(save.identity to save)) }
        assertFails { AsmRoomSaveTable(save, 0x7FFF).claim() }
    }
}
