package com.supermetroid.editor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import com.supermetroid.editor.asm.AsmDocumentFindState
import com.supermetroid.editor.asm.findAsmDocumentText
import com.supermetroid.editor.asm.AsmGeneratedRoomSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsmRoomDataTextTest {
    @Test
    fun `find highlighting preserves links and clips matches at page boundaries`() {
        val find = AsmDocumentFindState().also { it.show(); it.query = "Landing Site" }
        val fullText = "room: Landing Site\n"
        find.updateMatches(findAsmDocumentText(fullText, find.query), restart = true)
        val block = buildAnnotatedString {
            append("Site\n")
            addStringAnnotation("generated", "room", 0, 4)
        }
        val highlighted = asmHighlightDocumentMatches(block, 14, find, Color.Yellow)
        assertEquals(block.text, highlighted.text)
        assertEquals(block.getStringAnnotations(0, block.length), highlighted.getStringAnnotations(0, highlighted.length))
        assertEquals(0, highlighted.spanStyles.single().start)
        assertEquals(4, highlighted.spanStyles.single().end)
        find.close()
        assertEquals(block, asmHighlightDocumentMatches(block, 14, find, Color.Yellow))
    }

    @Test
    fun `selection preserves real newlines and symbolic links across displayed blocks`() {
        val text = "org ${'$'}8F9000\nSmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n" +
            "org ${'$'}8F9234\n    dw SmeditRoom_91F8_State0_Plms&${'$'}FFFF\n"
        val source = assertNotNull(AsmGeneratedRoomSource.parse(text, "test"))
        val first = asmRoomDataTextBlock(source, 0, source.lines.take(3).map(::AnnotatedString))
        val second = asmRoomDataTextBlock(source, 3, source.lines.drop(3).map(::AnnotatedString))
        assertEquals(text, first.text + second.text)
        val link = second.getStringAnnotations("generated", 0, second.length).single()
        assertEquals("SmeditRoom_91F8_State0_Plms", second.text.substring(link.start, link.end))
        assertEquals(source.definitions.keys.single(), link.item)
    }
}
