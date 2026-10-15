package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import kotlin.test.*

class AsmPlmDocumentTest {
    private val source = """
        ; untouched prefix
        PLMPopulation_Test: ;8F8000;
            ; objects
            %PLMPopEntry(Item, ${'$'}02, 3, %00000101) ; keep this comment
            dw ${'$'}0000
        NextLabel:
            db ${'$'}99
    """.trimIndent()

    @Test fun `lens updates only a coordinate token preserving comments literals and CRLF`() {
        val original = source.replace("\n", "\r\n")
        val document = AsmPlmDocument.parse(original, "PLMPopulation_Test", mapOf("Item" to 0xEEDB))
        assertEquals(listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5)), document.entries)
        val changed = document.replace(0, document.entries[0].copy(x = 4))
        assertEquals(original.replace("${'$'}02", "${'$'}04"), changed)
        assertEquals(4, AsmPlmDocument.parse(changed, document.label, mapOf("Item" to 0xEEDB)).entries[0].x)
        assertEquals(original, document.replace(0, document.entries[0]))
    }

    @Test fun `changing a symbolic field requires an explicit source edit`() {
        val document = AsmPlmDocument.parse(source, "PLMPopulation_Test", mapOf("Item" to 0xEEDB))
        assertFailsWith<IllegalArgumentException> { document.replace(0, document.entries[0].copy(id = 0xEEDF)) }
    }

    @Test fun `incomplete expressions unknown directives duplicates and missing terminators fail closed`() {
        val variants = listOf(
            source.replace("${'$'}02", "${'$'}02+"),
            source.replace("${'$'}02", "${'$'}100"),
            source.replace("${'$'}0000", "${'$'}1234"),
            source.replace("; objects", "db 1"),
            source.replace("; objects", "if !condition"),
            source + "\nPLMPopulation_Test:\n dw ${'$'}0000",
        )
        for (invalid in variants) assertFails { AsmPlmDocument.parse(invalid, "PLMPopulation_Test", mapOf("Item" to 0xEEDB)) }
    }

    @Test fun `empty list and last line without newline are accepted`() {
        assertTrue(AsmPlmDocument.parse("PLMPopulation_Empty:\n dw 0", "PLMPopulation_Empty").entries.isEmpty())
    }

    @Test fun `address lookup requires the declaration or its first emitted line`() {
        assertEquals("PLMPopulation_Test", AsmPlmDocument.labelAtPointer(source, 0x8000))
        assertNull(AsmPlmDocument.labelAtPointer(source, 0x8006))
        assertEquals("PLMPopulation_Empty", AsmPlmDocument.labelAtPointer("PLMPopulation_Empty:\n ; note\n dw 0 ;8F8040;", 0x8040))
    }

    @Test fun `duplicate address comments retain every candidate without silently choosing one`() {
        val text = source + "\nPLMPopulation_Other: ;8F8000;\n dw 0\n"
        val index = AsmPlmDocument.Companion.Source(text)
        assertEquals(listOf("PLMPopulation_Test", "PLMPopulation_Other"), index.labelsAtPointer(0x8000))
        assertNull(index.labelAtPointer(0x8000))
    }

    @Test fun `placement lookup selects the full record and its exact line with CRLF and comments`() {
        val text = source.replace("    dw", "    %PLMPopEntry(Item, 8, 9, 12) ; second missile\n    dw")
            .replace("\n", "\r\n")
        val document = AsmPlmDocument.parse(text, "PLMPopulation_Test", mapOf("Item" to 0xEEDB))
        for ((index, entry) in document.entries.withIndex()) {
            val target = assertIs<AsmPlmPlacementTarget.Source>(document.placementTarget(entry))
            assertEquals(3 + index, target.lineIndex)
            assertEquals("Item", text.lines()[target.lineIndex].substring(target.columnIndex).take(4))
        }
        assertIs<AsmPlmPlacementTarget.Unavailable>(document.placementTarget(document.entries[0].copy(param = 99)))
        assertIs<AsmPlmPlacementTarget.Unavailable>(document.placementTarget(document.entries[0].copy(x = 99)))
    }

    @Test fun `identical duplicate placements never silently choose the first`() {
        val text = source.replace("    dw", "    %PLMPopEntry(Item, 2, 3, 5)\n    dw")
        val document = AsmPlmDocument.parse(text, "PLMPopulation_Test", mapOf("Item" to 0xEEDB))
        assertIs<AsmPlmPlacementTarget.Unavailable>(document.placementTarget(document.entries.first()))
    }
}
