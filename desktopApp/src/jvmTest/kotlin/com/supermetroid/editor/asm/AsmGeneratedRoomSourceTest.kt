package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmGeneratedRoomSourceTest {
    @Test
    fun `actual emitted tables expose their room identity and symbolic references`() {
        val table = AsmRoomPlmTable(
            "SmeditRoom_91F8_State2_Plms", 0x7F000,
            listOf(RomParser.PlmEntry(0xBEEF, 4, 9, 100)), listOf(0x7B014, 0x7B034),
        )
        val document = assertNotNull(AsmGeneratedRoomSource.parse(table.renderSource(), "fixture-time"))
        val section = document.sections.single()
        assertEquals(0x91F8, section.roomId)
        assertEquals("Room $91F8 · source state 2 · PLMs", section.title)
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
        assertEquals("Room bytes 002", document.sectionAt(3)?.title)
        assertNull(AsmGeneratedRoomSource.parse("; This compilation has no generated room source.\n", "time"))
        assertNull(AsmGeneratedRoomSource.parse("", "time"))
    }
}
