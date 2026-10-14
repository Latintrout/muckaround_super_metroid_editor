package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmGeneratedRoomSourceTest {
    @Test fun `save placement reports are grouped with their owning room not mistaken for a second editable bank`() {
        val save = com.supermetroid.editor.rom.SourceSaveResource("SmeditObjects_91F8_Save_0_2", 0x91F8, 0, 2,
            listOf(0x91F8, 0x8000, 0, 0, 0, 104, 0xFFE0))
        val report = assertNotNull(AsmGeneratedRoomSource.parse(AsmRoomSaveTable(save, 0x44E1).renderSource(), "test"))
        assertEquals(0x91F8, report.sections.single().roomId)
        assertEquals("Save spawn record", report.sections.single().title)
    }
    @Test fun `an entirely typed authored plan still has a report with room owned scroll and object sections`() {
        val scroll = AsmRoomScrollTable(com.supermetroid.editor.rom.SourceScrollResource("SmeditObjects_ACB3_Scroll_1",
            listOf(com.supermetroid.editor.data.ScrollCommand(0, 1))), 0x7F000)
        val objects = AsmRoomPlmTable("SmeditObjects_ACB3_State_73746174652d31_Plms", 0x7F010,
            listOf(RomParser.PlmEntry(0xB703, 1, 2, 0xF000)), listOf(0x7B014), "SmeditObjects_ACB3_State_73746174652d31")
        val report = assertNotNull(AsmGeneratedRoomSource.parse(scroll.renderSource() + objects.renderSource(), "test"))
        assertEquals(listOf("Scroll trigger commands", "Authored objects (PLMs)"), report.sections.map { it.title })
        assertTrue(report.sections.all { it.roomId == 0xACB3 })
    }
    @Test
    fun `actual emitted tables expose their room identity and symbolic references`() {
        val table = AsmRoomPlmTable(
            "SmeditRoom_91F8_State2_Plms", 0x7F000,
            listOf(RomParser.PlmEntry(0xBEEF, 4, 9, 100)), listOf(0x7B014, 0x7B034),
        )
        val document = assertNotNull(AsmGeneratedRoomSource.parse(table.renderSource(), "fixture-time"))
        val section = document.sections.single()
        assertEquals(0x91F8, section.roomId)
        assertEquals("Source state 2 · Objects (PLMs)", section.title)
        assertEquals(section.lineIndex, document.definitions[table.label])
        assertTrue(document.definitions.containsKey("${table.label}_End"))
        val pointerLine = document.lines.indexOfLast { it.contains("dw ${table.label}&") }
        assertEquals(section, document.sectionAt(pointerLine))
        assertNull(document.sectionAt(0), "header text must not imply an owner")
    }

    @Test
    fun `raw byte overlays do not invent a room owner and empty builds have no document`() {
        val source = "SmeditRoomGraph_001:\ndb ${'$'}00\nSmeditRoomGraph_002:\ndb ${'$'}01\n"
        val document = assertNotNull(AsmGeneratedRoomSource.parse(source, "fixture-time"))
        assertEquals(2, document.sections.size)
        assertTrue(document.sections.all { it.roomId == null })
        assertEquals("Unmapped build write", document.sectionAt(3)?.title)
        assertNull(AsmGeneratedRoomSource.parse("; This compilation has no generated room source.\n", "time"))
        assertNull(AsmGeneratedRoomSource.parse("", "time"))
    }

    @Test
    fun `room names group sections and raw writes retain addresses without guessing owners`() {
        val document = assertNotNull(AsmGeneratedRoomSource.parse("""
            ; @room 91F8 Landing Site
            ; @section SmeditRoom_91F8_State2_Plms Default · Objects (PLMs)
            org ${'$'}8F9000
            SmeditRoom_91F8_State2_Plms:
                dw ${'$'}0000
            org ${'$'}83AD68
            SmeditRoomGraph_018:
                db ${'$'}FF
        """.trimIndent(), "test"))
        assertEquals(listOf("Landing Site", "Other build writes"), document.groups().map { it.title })
        assertEquals("Bytes at ${'$'}83:AD68", document.sections.last().title)
        assertNull(document.sections.last().roomId)
        assertEquals(1, document.groups(query = "landing").size)
        assertEquals(1, document.groups(query = "Default").single().sections.size)
        assertTrue(document.groups(query = "missing").isEmpty())
    }
}
