package com.supermetroid.editor.asm

import kotlin.test.*

class AsmManagedRoomPredicatesTest {
    private val predicate = AsmManagedRoomPredicate("SmeditSelectors_9879_Condition_616263")
    private val marker = AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER
    private fun symbols(vararg pairs: Pair<String, Int>) = ("[labels]\n" + pairs.joinToString("\n") { (name, value) ->
        "${(value ushr 16).toString(16)}:${(value and 65535).toString(16).padStart(4, '0')} $name"
    } + "\n").toByteArray()
    private fun rom() = ByteArray(0x300000).also { it.fill(-1, 0x7F000, 0x80000) }

    @Test fun `managed bodies support real instructions helpers and data but cannot escape placement through directives or macros`() {
        predicate.validateBody("$marker\njsr.w .test\nrts\n.test:\nlda.w ${'$'}09C8\ncmp.w #${'$'}0001\nrts\nfillbyte ${'$'}FF\nfill 32\n")
        predicate.validateBody("$marker\nlda.w ${'$'}09C8\nbeq .no\nsec\nrts\n.no: clc\nrts\n")
        for (body in listOf("org ${'$'}8FF000", "pushpc", "pullpc", "pad ${'$'}900000", "incsrc helper.asm", "%Escape()",
            "db 1 : org ${'$'}8FF000", "GlobalLabel:", "!address = 1", "fill 1025", "fillbyte 256", "", ".label:\n; only a label",
            "rts\n$marker", "rts\n${AsmRoomSelectorsSource.ASM_PREDICATE_MARKER}", "rts\n${AsmSelectorPredicateRecognizer.MARKER}"))
            assertFails(body) { predicate.validateBody("$marker\n$body\n") }
        assertFails { AsmManagedRoomPredicate("SmeditSelectors_9879_Order") }
    }

    @Test fun `placement respects reference bytes authored bytes and unchanged manually owned padding`() {
        val reference = rom()
        val authored = rom().also { it[0x7F010] = 0x60 }
        val labels = symbols("Manual" to 0x8FF010, "Manual_End" to 0x8FF080,
            predicate.label to 0x8F8000, predicate.label + "_End" to 0x8F8001)
        val beforeReference = reference.copyOf()
        val beforeAuthored = authored.copyOf()
        assertEquals(0x8FF081, AsmManagedRoomPredicates.chooseStart(reference, authored, labels, listOf(predicate)))
        assertContentEquals(beforeReference, reference)
        assertContentEquals(beforeAuthored, authored)
        reference[0x7F100] = 0 // A raw source edit cannot erase existing reference data and offer it as free space.
        assertEquals(0x8FF102, AsmManagedRoomPredicates.chooseStart(reference, authored, labels, listOf(predicate)))
        authored[0x7FFFF] = 0
        assertContains(assertFails { AsmManagedRoomPredicates.chooseStart(reference, authored, labels, listOf(predicate)) }.message.orEmpty(), "No verified")
        assertEquals(0x60, authored[0x7F010].toInt())
        assertEquals(0, reference[0x7F100].toInt())
    }

    @Test fun `managed claims cover FF tails require contiguous resolved symbols and enforce bank and size bounds`() {
        val reference = rom()
        val compiled = rom().also { it[0x7F001] = 0x60 }
        fun claims(start: Int = 0x8FF001, end: Int = 0x8FF041) = AsmManagedRoomPredicates.claims(listOf(predicate), 0x8FF001,
            reference, compiled, symbols(predicate.label to start, predicate.label + "_End" to end))
        assertEquals(listOf(AsmSourceOwnedRange(0x7F001, 64, PLM_SOURCE_FILE)), claims())
        for ((start, end) in listOf(0x8FF002 to 0x8FF041, 0x8FF001 to 0x8FF001, 0x8FF001 to 0x8FF402, 0x8FF001 to 0x900001))
            assertFails { claims(start, end) }
        reference[0x7F040] = 0
        assertContains(assertFails { claims() }.message.orEmpty(), "reference-ROM")
    }

    @Test fun `discovery produces stable identities and build only envelopes without modifying authored text`() {
        val text = "macro ${predicate.macroName}()\r\n    $marker\r\n    clc\r\n    rts\r\nendmacro\r\n"
        assertEquals(listOf(predicate), AsmRoomSelectorsSource(text).managedPredicates())
        val probe = AsmManagedRoomPredicates.render(listOf(predicate), null)
        assertContains(probe, "${predicate.label} = ${'$'}8F8000")
        assertFalse(probe.contains("%${predicate.macroName}()"))
        val source = AsmManagedRoomPredicates.render(listOf(predicate), 0x8FF001)
        assertContains(source, "%${predicate.macroName}()")
        assertContains(source, "${predicate.label}_End:")
        assertContains(source, "warnpc ${'$'}900000")
        assertEquals("", AsmManagedRoomPredicates.render(emptyList(), null))
        assertFails { AsmManagedRoomPredicates.render(listOf(predicate), 0x908000) }
        assertEquals(text, AsmRoomSelectorsSource(text).text)
    }
}
