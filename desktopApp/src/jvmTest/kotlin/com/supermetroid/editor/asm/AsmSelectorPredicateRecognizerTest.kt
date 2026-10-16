package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RoomStateSimulationContext
import com.supermetroid.editor.rom.matches
import kotlin.test.*

class AsmSelectorPredicateRecognizerTest {
    private fun recognize(code: String) = AsmSelectorPredicateRecognizer.recognize(
        AsmSelectorPredicateRecognizer.MARKER + "\n" + code.trimIndent())

    @Test fun `short circuit branches produce nested AND OR NOT with unsigned thresholds`() {
        val c = recognize("""
            lda.w ${'$'}09A4
            bit.w #${'$'}0004
            beq .alternative
            lda.w ${'$'}09C8
            cmp.w #${'$'}0019
            bcs .yes
        .alternative:
            lda.w ${'$'}09A8
            bit.w #${'$'}1000
            bne .no
            lda.w ${'$'}09C6
            cmp.w #${'$'}0005
            bcc .no
        .yes: sec
            rts
        .no: clc
            rts
        """)
        for (gear in listOf(0, 4, 0xFFFF)) for (beam in listOf(0, 1, 0x1000, 0xFFFF))
            for (max in listOf(0, 24, 25, 26, 0xFFFF)) for (current in listOf(0, 4, 5, 6, 0xFFFF)) {
                val expected = (gear and 4 != 0 && max >= 25) || (beam and 0x1000 == 0 && current >= 5)
                assertEquals(expected, c.matches(RoomStateSimulationContext(0, collectedEquipment = gear,
                    collectedBeams = beam, maxMissiles = max, currentMissiles = current)))
            }
    }

    @Test fun `BIT does not modify A and multibit tests mean any bit not all bits`() {
        val c = recognize("""
            lda.w ${'$'}09A4
            bit.w #${'$'}000C
            beq .no
            and.w #${'$'}2000
            beq .no
            sec
            rts
        .no: clc
            rts
        """)
        for (gear in 0..0xFFFF) assertEquals(gear and 12 != 0 && gear and 0x2000 != 0,
            c.matches(RoomStateSimulationContext(0, collectedEquipment = gear)), "gear=$gear")
    }

    @Test fun `LDA AND and BIT preserve carry and CMP updates it independently from zero`() {
        val c = recognize("""
            lda.w ${'$'}09C8
            cmp.w #${'$'}8000
            lda.w ${'$'}09A4
            bit.w #${'$'}0004
            and.w #${'$'}0000
            rts
        """)
        for (max in listOf(0, 1, 0x7FFF, 0x8000, 0xFFFF)) for (gear in listOf(0, 4, 0xFFFF))
            assertEquals(max >= 0x8000, c.matches(RoomStateSimulationContext(0, maxMissiles = max, collectedEquipment = gear)))
    }

    @Test fun `masked equality checks both set and unset bits and branches can rejoin`() {
        val c = recognize("""
            lda.w ${'$'}09A2
            and.w #${'$'}000D
            cmp.w #${'$'}0005
            bne .no
            sec
            bra .done
        .no: clc
            jmp .done
        .done: nop
            rts
        """)
        for (gear in 0..0xFFFF) assertEquals(gear and 13 == 5,
            c.matches(RoomStateSimulationContext(0, equippedEquipment = gear)), "gear=$gear")
    }

    @Test fun `capacity equality includes zero and FFFF without overflow`() {
        for (threshold in listOf(0, 1, 25, 0x7FFF, 0x8000, 0xFFFF)) {
            val c = recognize("""
                lda.w ${'$'}09C8
                cmp.w #$threshold
                beq .yes
                clc
                rts
            .yes: sec
                rts
            """)
            for (value in listOf(0, 1, 24, 25, 26, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF))
                assertEquals(value == threshold, c.matches(RoomStateSimulationContext(0, maxMissiles = value)))
        }
    }

    @Test fun `unknown flag or accumulator dependencies cannot be guessed`() {
        for (code in listOf("rts", "bit.w #4\nsec\nrts", "bcs .yes\nclc\nrts\n.yes: sec\nrts",
            "lda.w ${'$'}09A4\nsec\ncmp.w #4\nrts", // Gear inequality has no typed equivalent in this recognizer.
            "lda.w ${'$'}09C8\nand.w #255\nbeq .no\nsec\nrts\n.no: clc\nrts")) {
            assertFailsWith<IllegalArgumentException>(code) { recognize(code) }
        }
    }

    @Test fun `side effects width changes external calls symbols and indirect jumps are not recognized`() {
        for (code in listOf("sta.w ${'$'}09C8", "stz.w ${'$'}09C8", "inc.w ${'$'}09C8", "rep #32", "sep #32",
            "lda.b #1", "lda ${'$'}09C8", "lda.w MaxMissiles", "lda.w ${'$'}4210", "lda.w ${'$'}09C8,X",
            "jsl CheckIfEvent_inA_HasHappened", "jsr MyHelper", "jmp (${ '$'}1234)", "rtl", "db 1")) {
            assertFails(code) { recognize("$code\nsec\nrts") }
        }
    }

    @Test fun `malformed control flow and unmarked code remain drafts`() {
        for (code in listOf(".again: bra .again", "sec", "bra .missing", ".same: sec\n.same: rts",
            "sec\nrts\nclc\nrts", ".end: bra .end", "sec\n${AsmSelectorPredicateRecognizer.MARKER}\nrts"))
            assertFails(code) { recognize(code) }
        assertFails { AsmSelectorPredicateRecognizer.recognize("sec\nrts") }
        assertFails { recognize("nop\n".repeat(65) + "sec\nrts") }
        assertFails { recognize("lda.w #65536\nsec\nrts") }
    }

    @Test fun `branch distance and exponential path budgets fail closed`() {
        assertFails { recognize("lda.w ${'$'}09C8\nbeq .end\n" + "lda.w #0\n".repeat(45) + ".end: sec\nrts") }
        val diamonds = (0..9).joinToString("\n") { "cmp.w #$it\nbcs .next$it\nnop\n.next$it:" }
        assertContains(assertFails { recognize("lda.w ${'$'}09C8\n$diamonds\nsec\nrts") }.message.orEmpty(), "budget")
    }

    @Test fun `all sixteen mask bits flatten into a bounded expression and constant predicates keep typed invariants`() {
        val c = recognize("lda.w ${'$'}09A4\nbeq .no\nsec\nrts\n.no: clc\nrts")
        for (gear in listOf(0, 1, 4, 0x8000, 0xFFFF))
            assertEquals(gear != 0, c.matches(RoomStateSimulationContext(0, collectedEquipment = gear)))
        assertTrue(recognize("lda.w #0\nbit.w #4\nbeq .yes\nclc\nrts\n.yes: sec\nrts").matches(RoomStateSimulationContext(0)))
        assertFalse(recognize("lda.w #7\nand.w #0\nbit.w #1\nclc\nrts").matches(RoomStateSimulationContext(0)))
    }

    @Test fun `long short circuit OR chains do not recursively recompute composite complements`() {
        val branches = (0..15).joinToString("\n") { "bit.w #${1 shl it}\nbne .yes" }
        val c = recognize("lda.w ${'$'}09A4\n$branches\nclc\nrts\n.yes: sec\nrts")
        for (gear in listOf(0, 1, 4, 0x8000, 0xFFFF))
            assertEquals(gear != 0, c.matches(RoomStateSimulationContext(0, collectedEquipment = gear)))
    }

    @Test fun `all supported RAM bindings map to the right collected equipped current or maximum fields`() {
        val contexts = mapOf(
            0x09A2 to RoomStateSimulationContext(0, equippedEquipment = 4),
            0x09A4 to RoomStateSimulationContext(0, collectedEquipment = 4),
            0x09A6 to RoomStateSimulationContext(0, equippedBeams = 4),
            0x09A8 to RoomStateSimulationContext(0, collectedBeams = 4),
            0x09C2 to RoomStateSimulationContext(0, currentEnergy = 500),
            0x09C4 to RoomStateSimulationContext(0, maxEnergy = 500),
            0x09C6 to RoomStateSimulationContext(0, currentMissiles = 500),
            0x09C8 to RoomStateSimulationContext(0, maxMissiles = 500),
            0x09CA to RoomStateSimulationContext(0, currentSuperMissiles = 500),
            0x09CC to RoomStateSimulationContext(0, maxSuperMissiles = 500),
            0x09CE to RoomStateSimulationContext(0, currentPowerBombs = 500),
            0x09D0 to RoomStateSimulationContext(0, maxPowerBombs = 500),
            0x09D4 to RoomStateSimulationContext(0, maxReserveEnergy = 500),
            0x09D6 to RoomStateSimulationContext(0, currentReserveEnergy = 500),
        )
        contexts.forEach { (address, context) ->
            val mask = if (address < 0x09C0) "bit.w #4\nbeq .no" else "cmp.w #500\nbcc .no"
            val c = recognize("lda.w ${'$'}${address.toString(16)}\n$mask\nsec\nrts\n.no: clc\nrts")
            assertTrue(c.matches(context), address.toString(16))
            assertFalse(c.matches(RoomStateSimulationContext(0)), address.toString(16))
        }
    }
}
