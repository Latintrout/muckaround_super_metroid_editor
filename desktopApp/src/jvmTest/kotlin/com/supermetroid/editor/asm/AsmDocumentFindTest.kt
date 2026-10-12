package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmDocumentFindTest {
    @Test
    fun `search is literal includes repeated matches and keeps whole bank coordinates`() {
        val text = "\n".repeat(800) + "    dw ${'$'}04E0, ${'$'}04e0 ; !SPF\n"
        val matches = findAsmDocumentText(text, "${'$'}04e0")
        assertEquals(listOf(807, 814), matches.map { it.offset })
        assertEquals(listOf(800, 800), matches.map { it.line })
        assertEquals(listOf(7, 14), matches.map { it.column })
        assertEquals(1, findAsmDocumentText(text, "${'$'}04e0", matchCase = true).size)
        assertEquals(1, findAsmDocumentText(text, "!SPF").size)
        assertTrue(findAsmDocumentText(text, "").isEmpty())
        assertTrue(findAsmDocumentText(text, "missing").isEmpty())
    }

    @Test
    fun `find sessions wrap both directions and are independent across split views`() {
        val left = AsmDocumentFindState()
        val right = AsmDocumentFindState()
        left.show()
        left.query = "LDA"
        left.updateMatches(findAsmDocumentText("LDA LDA\nLDA", left.query), restart = true)
        assertEquals(0, left.selected?.offset)
        assertEquals(8, left.step(-1)?.offset)
        assertEquals(0, left.step(1)?.offset)
        assertTrue(right.matches.isEmpty())
        assertEquals("", right.query)
        left.updateMatches(emptyList(), restart = false)
        assertNull(left.step(1))
        left.close()
        assertNull(left.selected)
    }

    @Test
    fun `multiline matches CRLF and Unicode use text field offsets`() {
        val text = "; 日本語\r\nLabel:\n    LDA"
        val match = findAsmDocumentText(text, "Label:\n    LDA").single()
        assertEquals(1, match.line)
        assertEquals(0, match.column)
        assertEquals("Label:\n    LDA", text.substring(match.offset, match.offset + match.length))
        assertEquals(listOf(0, 7, 14), asmDocumentLineOffsets(text.split('\n')).toList())
    }

    @Test
    fun `visible highlights include partial hits but exclude adjacent offscreen hits`() {
        val find = AsmDocumentFindState()
        find.updateMatches(findAsmDocumentText("LDA LDA LDA", "LDA"), restart = true)
        assertEquals(listOf(4), find.matchesInRange(3, 8).map { it.offset })
        assertEquals(listOf(0, 4), find.matchesInRange(2, 5).map { it.offset })
        assertTrue(find.matchesInRange(11, 20).isEmpty())
        assertTrue(find.matchesInRange(3, 3).isEmpty())
    }
}
