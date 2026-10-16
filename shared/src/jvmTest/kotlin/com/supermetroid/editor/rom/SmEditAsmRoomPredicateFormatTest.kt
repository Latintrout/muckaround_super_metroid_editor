package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomEdits
import kotlin.test.*

class SmEditAsmRoomPredicateFormatTest {
    @Test fun `native dispatch adapter restores registers widths and stack for both outcomes`() {
        for (matches in listOf(false, true)) {
            val memory = ByteArray(0x10000)
            fun byte(at: Int) = memory[at].toInt() and 255
            fun word(at: Int) = byte(at) or (byte(at + 1) shl 8)
            fun write(at: Int, value: Int) { memory[at] = value.toByte() }
            fun writeWord(at: Int, value: Int) { write(at, value); write(at + 1, value ushr 8) }
            SmEditAsmRoomPredicateFormat.engineBytes.copyInto(memory, 0xE5D2)
            SmEditAsmRoomPredicateFormat.adapterBytes(0x8FD000).copyInto(memory, 0xF000)
            // Deliberately clobber X/Y/D/DB and leave both width flags set. The
            // routine balances its stack and returns only carry, as contracted.
            val predicate = listOf(0xA2, 0xCD, 0xAB, 0xA0, 0x34, 0x12, 0xA9, 0x78, 0x56, 0x5B,
                0xE2, 0x30, 0xA9, 0x7E, 0x48, 0xAB, if (matches) 0x38 else 0x18, 0x60)
            predicate.forEachIndexed { i, value -> write(0xD000 + i, value) }
            writeWord(0x900B, 0xF000); writeWord(0x900D, 0xA000); writeWord(0x900F, 0xE5E6)
            var pc = 0xE5D2; var a = 0; var x = 0x9000; var y = 0x4567
            var db = 0x8F; var d = 0x1234; var p = 0x04; var sp = 0x1FF
            fun push(v: Int) { write(sp--, v) }
            fun pop() = byte(++sp)
            fun pushWord(v: Int) { push(v ushr 8); push(v) }
            fun popWord() = pop() or (pop() shl 8)
            fun next() = byte(pc++)
            fun nextWord() = next() or (next() shl 8)
            fun flag(mask: Int, set: Boolean) { p = if (set) p or mask else p and mask.inv() }
            fun nz(v: Int, small: Boolean = false) { flag(2, v == 0); flag(128, v and (if (small) 128 else 32768) != 0) }
            push(0x82); pushWord(0x1233) // External JSL return.
            var savedP: Int? = null
            var checkedRestore = false
            var returned = false
            repeat(200) {
                if (returned) return@repeat
                if (pc == 0xF000) savedP = p
                if (pc == 0xF011 || pc == 0xF01D) {
                    assertEquals(0x900D, x); assertEquals(0x4567, y)
                    assertEquals(0x1234, d); assertEquals(0x8F, db); assertEquals(savedP, p)
                    checkedRestore = true
                }
                when (val op = next()) {
                    0x8A -> { a = x; nz(a) }
                    0x18 -> flag(1, false)
                    0x38 -> flag(1, true)
                    0x69 -> { val sum = a + nextWord() + (p and 1); a = sum and 65535; flag(1, sum > 65535); nz(a) }
                    0xAA -> { x = a; nz(x) }
                    0xBD -> { a = word((nextWord() + x) and 65535); nz(a) }
                    0x8D -> writeWord(nextWord(), a)
                    0x8E -> writeWord(nextWord(), x)
                    0xE8 -> { x = (x + 1) and 65535; nz(x) }
                    0xF4 -> pushWord(nextWord())
                    0x6C -> pc = word(nextWord())
                    0x08 -> push(p)
                    0x8B -> push(db)
                    0x0B -> pushWord(d)
                    0xDA -> pushWord(x)
                    0x5A -> pushWord(y)
                    0x20 -> { val target = nextWord(); pushWord(pc - 1); pc = target }
                    0xC2 -> p = p and next().inv()
                    0xE2 -> { p = p or next(); if (p and 16 != 0) { x = x and 255; y = y and 255 } }
                    0x90 -> { val delta = next().toByte().toInt(); if (p and 1 == 0) pc += delta }
                    0x7A -> { assertEquals(0, p and 16); y = popWord(); nz(y) }
                    0xFA -> { assertEquals(0, p and 16); x = popWord(); nz(x) }
                    0x2B -> { d = popWord(); nz(d) }
                    0xAB -> { db = pop(); nz(db, true) }
                    0x28 -> p = pop()
                    0x4C -> pc = nextWord()
                    0xA9 -> { a = if (p and 32 == 0) nextWord() else (a and 0xFF00) or next(); nz(a and if (p and 32 == 0) 65535 else 255, p and 32 != 0) }
                    0xA2 -> { x = nextWord(); nz(x) }
                    0xA0 -> { y = nextWord(); nz(y) }
                    0x5B -> { d = a; nz(d) }
                    0x48 -> if (p and 32 == 0) pushWord(a) else push(a)
                    0x68 -> { assertEquals(0, p and 32); a = popWord(); nz(a) }
                    0x60 -> pc = popWord() + 1
                    0x6B -> { assertEquals(0x1234, popWord() + 1); assertEquals(0x82, pop()); returned = true }
                    else -> error("Unexpected opcode ${op.toString(16)} at ${(pc - 1).toString(16)}")
                }
            }
            assertTrue(returned); assertTrue(checkedRestore); assertEquals(0x1FF, sp)
            assertEquals(if (matches) 0xA000 else 0x9011, word(0x07BB))
        }
    }

    @Test fun `inspection recognizes only the exact tagged adapter and keeps opaque conditions explicit`() {
        for (header in listOf(0, 512)) {
            val rom = ByteArray(0x300000 + header)
            val pc = 0x79000 + header
            fun word(offset: Int, value: Int) { rom[offset] = value.toByte(); rom[offset + 1] = (value ushr 8).toByte() }
            rom[pc + 4] = 1; rom[pc + 5] = 1
            word(pc + 11, 0xF000); word(pc + 13, 0xA000); word(pc + 15, 0xE5E6)
            val adapterPc = 0x7F000 + header
            val bytes = SmEditAsmRoomPredicateFormat.adapterBytes(0x8FD000)
            assertEquals(SmEditAsmRoomPredicateFormat.ADAPTER_SIZE_BYTES, bytes.size)
            bytes.copyInto(rom, adapterPc)
            val parser = RomParser(rom)
            val inspection = parser.inspectRoomStates(0x9000)
            assertTrue(inspection.isComplete, inspection.issues.toString())
            val first = inspection.states.first()
            assertEquals(RoomStateConditionKind.ASM_ONLY, first.condition.kind)
            assertEquals(0x8FD000, first.condition.asmPredicateAddress)
            assertEquals(0x7A000 + header, first.stateDataPcOffset)
            assertEquals(pc + 17, inspection.states.last().stateDataPcOffset)
            assertContains(first.condition.summary(0), "ASM-only")
            val room = RoomEdits(0x9000)
            assertContains(assertFails { room.ensureStateManifest(parser) }.message.orEmpty(), "source project")
            assertTrue(room.states.isEmpty())
            for (index in bytes.indices.filter { it !in 6..7 }) {
                rom[adapterPc + index] = (bytes[index].toInt() xor 1).toByte()
                assertFalse(RomParser(rom).inspectRoomStates(0x9000).isComplete, "corrupt adapter byte $index")
                rom[adapterPc + index] = bytes[index]
            }
            assertNull(SmEditAsmRoomPredicateFormat.targetAt(bytes.copyOf(bytes.size - 1), 0))
            assertFails { SmEditAsmRoomPredicateFormat.adapterBytes(0x908000) }
        }
    }
}
