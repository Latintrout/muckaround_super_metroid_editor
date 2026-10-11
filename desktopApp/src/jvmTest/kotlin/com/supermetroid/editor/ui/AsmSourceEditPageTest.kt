package com.supermetroid.editor.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import com.supermetroid.editor.asm.AsmCompletionItem
import com.supermetroid.editor.asm.AsmCompletionKind
import com.supermetroid.editor.asm.AsmCompletionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.awt.event.MouseEvent

class AsmSourceEditPageTest {
    @Test
    fun `page isolates bounded lines and merges edits into the complete source`() {
        val source = (1..1_205).joinToString("\n") { "line $it" }

        val page = asmSourceEditPage(source, requestedStartLine = 500, maxLines = 500)

        assertEquals(500, page.startLine)
        assertEquals(500, page.lineCount)
        assertEquals(1_205, page.totalLineCount)
        assertEquals("line 501", page.text.lineSequence().first())
        assertEquals("line 1000", page.text.lineSequence().last())

        val updated = page.text.replaceFirst("line 501", "line 501 ; edited")
        val merged = page.merge(updated)
        assertEquals("line 500", merged.lineSequence().elementAt(499))
        assertEquals("line 501 ; edited", merged.lineSequence().elementAt(500))
        assertEquals("line 1001", merged.lineSequence().elementAt(1_000))
        assertEquals("line 1205", merged.lineSequence().last())
    }

    @Test
    fun `last page preserves a trailing newline and accepts inserted lines`() {
        val source = "one\ntwo\nthree\n"
        val page = asmSourceEditPage(source, requestedStartLine = 2, maxLines = 500)

        assertEquals("three\n", page.text)
        assertEquals("one\ntwo\nthree\ninserted\n", page.merge(page.text + "inserted\n"))
    }

    @Test
    fun `page size must be positive`() {
        assertFailsWith<IllegalArgumentException> { asmSourceEditPage("source", 0, 0) }
    }

    @Test
    fun `enter inherits opcode indentation`() {
        val previous = TextFieldValue("    LDA.W #\$01", TextRange(14))
        val updated = TextFieldValue("    LDA.W #\$01\n", TextRange(15))

        val result = asmApplyAutomaticIndentation(previous, updated)

        assertEquals("    LDA.W #\$01\n    ", result.text)
        assertEquals(TextRange(19), result.selection)
    }

    @Test
    fun `enter after a label starts an indented instruction`() {
        val previous = TextFieldValue("HandleJump:", TextRange(11))
        val updated = TextFieldValue("HandleJump:\n", TextRange(12))

        val result = asmApplyAutomaticIndentation(previous, updated)

        assertEquals("HandleJump:\n    ", result.text)
        assertEquals(TextRange(16), result.selection)
    }

    @Test
    fun `paste and multi-line edits are never reformatted`() {
        val previous = TextFieldValue("LDA.W #\$01", TextRange(10))
        val pasted = "LDA.W #\$01\nSTA.W \$12\n"
        val updated = TextFieldValue(pasted, TextRange(pasted.length))

        assertEquals(updated, asmApplyAutomaticIndentation(previous, updated))
    }

    @Test
    fun `editable symbols resolve their whole-file line`() {
        val text = "    dw \$0600*!SPF*\$100\n    RTS"
        val offset = text.indexOf("!SPF") + 2

        assertEquals(
            AsmEditableSymbolReference(lineIndex = 500, token = "!SPF"),
            asmEditableSymbolAt(text, offset, pageStartLine = 500),
        )
    }

    @Test
    fun `editable macro calls retain the percent prefix for definition lookup`() {
        val text = "    %DoorHeader(Left, ${'$'}01)"
        val offset = text.indexOf("DoorHeader") + 4

        assertEquals(
            AsmEditableSymbolReference(lineIndex = 42, token = "%DoorHeader"),
            asmEditableSymbolAt(text, offset, pageStartLine = 42),
        )
    }

    @Test
    fun `editable indexed struct members remain one navigation token`() {
        val text = "    LDA.W Enemy[1].properties"
        val offset = text.indexOf("properties") + 3

        assertEquals(
            AsmEditableSymbolReference(lineIndex = 90, token = "Enemy[1].properties"),
            asmEditableSymbolAt(text, offset, pageStartLine = 90),
        )
    }

    @Test
    fun `editable symbols in comments are not navigation targets`() {
        val text = "    RTS ; !SPF is only commentary"

        assertEquals(null, asmEditableSymbolAt(text, text.indexOf("!SPF") + 1, pageStartLine = 0))
    }

    @Test
    fun `caret helpers safely handle an empty first line`() {
        val text = "\nSecondLine"

        assertEquals(0, asmColumnIndexAtOffset(text, 0))
        assertEquals(null, asmEditableSymbolAt(text, 0, pageStartLine = 0))
    }

    @Test
    fun `caret offset resolves the active source row`() {
        val text = "first\nsecond\nthird"

        assertEquals(0, asmLineIndexAtOffset(text, 0))
        assertEquals(1, asmLineIndexAtOffset(text, text.indexOf("second") + 2))
        assertEquals(2, asmLineIndexAtOffset(text, text.length))
        assertEquals(2, asmColumnIndexAtOffset(text, text.indexOf("second") + 2))
        assertEquals(5, asmColumnIndexAtOffset(text, text.length))
    }

    @Test
    fun `tab advances a caret to the next four column stop`() {
        val value = TextFieldValue("  LDA.W #\$01", TextRange(2))

        val result = asmApplyTabIndentation(value, unindent = false)

        assertEquals("    LDA.W #\$01", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun `tab and shift tab format only selected rows`() {
        val original = "Label:\nLDA.W #\$01\nSTA.W \$12\nRTS"
        val selectionStart = original.indexOf("LDA")
        val selectionEnd = original.indexOf("RTS")
        val selected = TextFieldValue(original, TextRange(selectionStart, selectionEnd))

        val indented = asmApplyTabIndentation(selected, unindent = false)
        assertEquals("Label:\n    LDA.W #\$01\n    STA.W \$12\nRTS", indented.text)

        val restored = asmApplyTabIndentation(indented, unindent = true)
        assertEquals(original, restored.text)
        assertEquals(selected.selection, restored.selection)
    }

    @Test
    fun `completion replaces only the active token and places the caret after it`() {
        val value = TextFieldValue("    JSR Gl,X", TextRange(10))
        val completion = AsmCompletionResult(
            prefix = "Gl",
            replaceStart = 8,
            replaceEnd = 10,
            items = listOf(AsmCompletionItem("GlobalRoutine", AsmCompletionKind.LABEL, "bank_80.asm:2", 0)),
        )

        val result = asmApplyCompletion(value, completion, "GlobalRoutine")

        assertEquals("    JSR GlobalRoutine,X", result.text)
        assertEquals(TextRange(21), result.selection)
    }

    @Test
    fun `macro completion inserts parentheses and leaves the caret inside`() {
        val value = TextFieldValue("    %spr", TextRange(8))
        val completion = AsmCompletionResult(
            prefix = "%spr",
            replaceStart = 4,
            replaceEnd = 8,
            items = emptyList(),
        )

        val result = asmApplyCompletion(
            value,
            completion,
            replacement = "%spritemapEntry()",
            caretBacktrack = 1,
        )

        assertEquals("    %spritemapEntry()", result.text)
        assertEquals(TextRange(result.text.length - 1), result.selection)
    }

    @Test
    fun `moving the caret does not summon completion over an existing symbol`() {
        val source = "    LDA.B DP_Temp03"
        val previous = TextFieldValue(source, TextRange(source.length))
        val clicked = previous.copy(selection = TextRange(source.indexOf("Temp03") + 4))

        assertEquals(false, asmAutomaticCompletionRequested(previous, clicked))

        val typed = clicked.copy(
            text = clicked.text.replaceRange(clicked.selection.start, clicked.selection.end, "0"),
            selection = TextRange(clicked.selection.start + 1),
        )
        assertEquals(true, asmAutomaticCompletionRequested(clicked, typed))
    }

    @Test
    fun `middle click and platform definition modifiers are recognized on press`() {
        assertTrue(asmIsDefinitionPointerGesture(MouseEvent.BUTTON2, false, false, isMac = true))
        assertTrue(asmIsDefinitionPointerGesture(MouseEvent.BUTTON2, false, false, isMac = false))
        assertTrue(asmIsDefinitionPointerGesture(MouseEvent.BUTTON1, true, false, isMac = true))
        assertTrue(asmIsDefinitionPointerGesture(MouseEvent.BUTTON1, false, true, isMac = false))
        assertTrue(
            asmIsDefinitionPointerGesture(
                MouseEvent.NOBUTTON,
                metaDown = false,
                controlDown = false,
                isMac = true,
                tertiaryPressed = true,
            ),
        )
        assertFalse(asmIsDefinitionPointerGesture(MouseEvent.BUTTON1, false, false, isMac = true))
        assertFalse(asmIsDefinitionPointerGesture(MouseEvent.BUTTON1, true, false, isMac = false))
    }

    @Test
    fun `linked text tolerates a layout offset immediately after a symbol`() {
        val text = buildAnnotatedString {
            append("JSR ")
            pushStringAnnotation("label", "Target")
            append("Target")
            pop()
            append("  ; call")
        }
        val symbolEnd = "JSR Target".length

        assertEquals("Target", assertNotNull(asmStringAnnotationAt(text, symbolEnd - 2)).item)
        assertEquals("Target", assertNotNull(asmStringAnnotationAt(text, symbolEnd)).item)
        assertEquals(null, asmStringAnnotationAt(text, text.length))
    }
}
