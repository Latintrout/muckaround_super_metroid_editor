package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlin.test.*

class AsmDoorArrivalsSourceTest {
    private val name = AsmDoorArrivalsSource.name(0x91F8, 0)
    private val label = "${name}_Entry"
    private val root = AsmDoorArrivalsSource.render(0x91F8, 0, null)
    private val parser = RomParser(saveSourceTestRom())
    private val room = RoomEdits(0x91F8, asmDoorSourceVersion = 1)
    private val doors = SourceRoomDoors(0x8060, listOf(SourceRoomDoor(0x8000, DoorChange(0, 0x91F8, 0, 0, 0, 0, 0x8000, 0))))
    private fun source(target: String = label) = AsmDoorSource.render(0x91F8, "Test", doors)
        .replace("dw ${'$'}0000 ; Arrival ASM", "dw $target ; Arrival ASM")
    private fun symbols(vararg labels: Pair<String, Int>) = ("[labels]\n" + labels.joinToString("\n") { (name, value) ->
        "${(value ushr 16).toString(16)}:${(value and 65535).toString(16).padStart(4, '0')} $name"
    } + "\n").toByteArray()
    private fun compiled(header: Int = 0, labels: ByteArray = symbols(label to 0x8FF000, "${label}_End" to 0x8FF003),
                         claims: List<AsmSourceOwnedRange> = listOf(AsmSourceOwnedRange(0x7F000, 3, PLM_SOURCE_FILE))): AsmCompiledBuildBase {
        val body = parser.copyRomData().also {
            it.fill(-1, 0x7F000, 0x80000)
            DOOR_ARRIVAL_DISPATCH_BYTES.copyInto(it, 0x7E8A3)
        }
        return AsmCompiledBuildBase(ByteArray(header) + body, body, "fixture", claims,
            assemblerVersion = "test", compilerOutput = emptyList(), compilerSymbols = labels)
    }

    @Test fun `symbolic arrival words preserve data editing spelling comments and unresolved authority`() {
        val text = source().replace("\n", "\r\n")
        val codec = AsmDoorSource(text, root)
        val value = codec.resource(room, parser)
        assertEquals(SourceDoorArrival(label), value.entries.single().arrival)
        assertEquals(0, value.entries.single().change.entryCode)
        assertFails { value.entries.single().bytes() }
        val changed = codec.edit(room, parser, 0, value.entries.single().entry().copy(distFromDoor = 0x1234))
        assertEquals(text.replace("dw ${'$'}8000 ; Distance", "dw ${'$'}1234 ; Distance"), changed)
        assertFails { codec.edit(room, parser, 0, value.entries.single().entry().copy(entryCode = 0x9000)) }
        assertEquals(SourceDoorArrival("DoorASM_Test"), AsmDoorSource(source("DoorASM_Test"), "", mapOf("DoorASM_Test" to 0x9000)).resource(room, parser).entries.single().arrival)
        for (target in listOf("Missing", "DoorASM_Missing", "${label}+1", "${label}&${'$'}FFFF"))
            assertFails(target) { AsmDoorSource(source(target), root).resource(room, parser) }
        assertFails { AsmDoorSource(source().replace("dw ${'$'}91F8", "dw $label"), root).resource(room, parser) }
        assertFails { AsmDoorSource(source(), "").resource(room, parser) }
        assertEquals(doors, AsmDoorSource(source("${'$'}0000")).resource(room, parser), "Literal legacy declarations remain supported")
    }

    @Test fun `symbol resolution checks the native ABI bank boundaries and full envelopes including headered ROMs`() {
        val input = mapOf(0x91F8 to AsmDoorSource(source(), root).resource(room, parser))
        for (header in listOf(0, 512)) {
            val resolved = resolveDoorArrivals(input, compiled(header))
            val door = resolved.getValue(0x91F8).entries.single()
            assertEquals(0x8FF000, door.arrival?.snesAddress)
            assertEquals(0xF000, door.change.entryCode)
            assertEquals(0xF0.toByte(), door.bytes()[11])
        }
        for (labels in listOf(symbols(), symbols(label to 0x908000, "${label}_End" to 0x908001),
            symbols(label to 0x8F7FFF, "${label}_End" to 0x8F8001), symbols(label to 0x8FF000),
            symbols(label to 0x8FF000, "${label}_End" to 0x8FF000), symbols(label to 0x8FF000, "${label}_End" to 0x8FF401)))
            assertFails { resolveDoorArrivals(input, compiled(labels = labels)) }
        assertFails { resolveDoorArrivals(input, compiled(claims = emptyList())) }
        assertFails { resolveDoorArrivals(input, compiled(claims = listOf(AsmSourceOwnedRange(0x7F000, 2)))) }
        val changedEngine = compiled().let { it.copy(romBytes = it.romBytes.copyOf().also { bytes -> bytes[0x7E8A5] = 0xEA.toByte() }) }
        assertContains(assertFails { resolveDoorArrivals(input, changedEngine) }.message.orEmpty(), "door-dispatch ABI")
        assertEquals(mapOf(0x91F8 to doors), resolveDoorArrivals(mapOf(0x91F8 to doors), changedEngine), "Literal connections do not claim a new engine contract")
        val native = mapOf(0x91F8 to AsmDoorSource(source("DoorASM_Test"), "", mapOf("DoorASM_Test" to 0x9000)).resource(room, parser))
        assertEquals(0x9010, resolveDoorArrivals(native, compiled(labels = symbols("DoorASM_Test" to 0x8F9010))).getValue(0x91F8).entries.single().change.entryCode)
    }

    @Test fun `arrivals and predicates share deterministic packed claims and cannot escape their placement gateway`() {
        val arrival = AsmDoorArrivalsSource(root).routines().single()
        arrival.validateBody("${AsmDoorArrivalsSource.MARKER}\nlda.w #${'$'}0001\nsta.l ${'$'}7ECD20\nrts\n")
        for (body in listOf("org ${'$'}8FF000", "incsrc file.asm", "%Escape()", "GlobalLabel:\nrts", "fill 1025", "rts\n${AsmDoorArrivalsSource.MARKER}"))
            assertFails(body) { arrival.validateBody("${AsmDoorArrivalsSource.MARKER}\n$body\n") }
        assertFails { AsmDoorArrivalsSource(root + root).routines() }
        val predicate = AsmManagedRoomPredicate("SmeditSelectors_91F8_Condition_616263")
        val routines = listOf<AsmManagedBank8FRoutine>(arrival, predicate)
        val rendered = AsmManagedBank8FCode.render(routines, 0x8FF000)
        assertTrue(rendered.indexOf("$label:") < rendered.indexOf("${predicate.label}:"))
        assertContains(rendered, "%$name()")
        val reference = compiled().referenceRomBody
        val claims = AsmManagedBank8FCode.claims(routines, 0x8FF000, reference, reference,
            symbols(label to 0x8FF000, "${label}_End" to 0x8FF040, predicate.label to 0x8FF040, "${predicate.label}_End" to 0x8FF080))
        assertEquals(listOf(AsmSourceOwnedRange(0x7F000, 64, PLM_SOURCE_FILE), AsmSourceOwnedRange(0x7F040, 64, PLM_SOURCE_FILE)), claims)
        assertEquals(0x8FF001, AsmManagedBank8FCode.chooseStart(reference, reference, symbols(), routines))
    }

    @Test fun `native symbolic relinking hands off only validated DoorDef bytes and preserves neighboring raw source owners`() {
        val before = compiled().referenceRomBody.copyOf().also { it[0x1800B] = 0x90.toByte() }
        val nativeParser = RomParser(before)
        val moved = before.copyOf().also { it[0x1800A] = 0x10 }
        val base = compiled(labels = symbols("DoorASM_Test" to 0x8F9010), claims = listOf(AsmSourceOwnedRange(0x17FFF, 14, "bank_83.asm")))
            .copy(romBytes = moved, referenceRomBody = before)
        val raw = AsmDoorSource(source("DoorASM_Test"), "", mapOf("DoorASM_Test" to 0x9000)).resource(room, nativeParser)
        val input = resolveDoorArrivals(mapOf(0x91F8 to raw), base)
        val protected = protectDoorSourceBaseline(input, nativeParser, base, mapOf("DoorASM_Test" to 0x9000))
        assertEquals(listOf(AsmSourceOwnedRange(0x17FFF, 1, "bank_83.asm"), AsmSourceOwnedRange(0x1800C, 1, "bank_83.asm")), protected.sourceOwnedRanges)
        assertContentEquals(moved, protected.romBytes)
        val changedFlag = base.copy(romBytes = moved.copyOf().also { it[0x18002] = 0x40 })
        assertFails { protectDoorSourceBaseline(input, nativeParser, changedFlag, mapOf("DoorASM_Test" to 0x9000)) }
        val literal = input.mapValues { (_, value) -> value.copy(entries = value.entries.map { it.copy(arrival = null) }) }
        assertFails { protectDoorSourceBaseline(literal, nativeParser, base, mapOf("DoorASM_Test" to 0x9000)) }
        assertContentEquals(before, nativeParser.copyRomData(), "Validation and ownership handoff must not mutate the base ROM")
    }

    @Test fun `native dispatcher and behavior preserving scaffold deliver identical registers scratch flags and return stack`() {
        fun execute(managed: Boolean): List<Int> {
            val memory = ByteArray(65536)
            fun byte(at: Int) = memory[at].toInt() and 255
            fun word(at: Int) = byte(at) or (byte(at + 1) shl 8)
            fun putWord(at: Int, value: Int) { memory[at] = value.toByte(); memory[at + 1] = (value ushr 8).toByte() }
            DOOR_ARRIVAL_DISPATCH_BYTES.copyInto(memory, 0xE8A3)
            val target = if (managed) 0xF000 else 0x9000
            val door = ByteArray(12).also { it[10] = target.toByte(); it[11] = (target ushr 8).toByte() }
            byteArrayOf(0xA9.toByte(), 0x00, 0x90.toByte(), 0x85.toByte(), 0x12, 0x4C, 0x00, 0x90.toByte()).copyInto(memory, 0xF000)
            memory[0x9000] = 0x60; putWord(0x078D, 0x8000)
            var pc = 0xE8A3; var sp = 0x1FF; var a = 0x1234; var x = 0x56; val y = 0x78
            var p = 0x75; var db = 0x7E; var returned = false
            var context: List<Int>? = null
            fun push(value: Int) { memory[sp--] = value.toByte() }
            fun pop() = byte(++sp)
            fun pushWord(value: Int) { push(value ushr 8); push(value) }
            fun popWord() = pop() or (pop() shl 8)
            fun next() = byte(pc++)
            fun nextWord() = next() or (next() shl 8)
            fun nz(value: Int, small: Boolean = false) {
                p = (p and 0x82.inv()) or (if (value == 0) 2 else 0) or (if (value and (if (small) 128 else 32768) != 0) 128 else 0)
            }
            push(0x82); pushWord(0x1233) // External JSL to Execute_Door_ASM.
            repeat(100) {
                if (returned) return@repeat
                if (pc == 0x9000) context = listOf(a, x, y, p, db, sp, word(0x12), word(sp + 1))
                when (val opcode = next()) {
                    0x08 -> push(p)
                    0x8B -> push(db)
                    0xC2 -> p = p and next().inv()
                    0xAE -> { assertEquals(0, p and 0x10); x = word(nextWord()); nz(x) }
                    0xBF -> {
                        assertEquals(0, p and 0x20)
                        val address = nextWord() or (next() shl 16)
                        val offset = address + x - 0x838000
                        a = (door[offset].toInt() and 255) or ((door[offset + 1].toInt() and 255) shl 8); nz(a)
                    }
                    0xF0 -> { val delta = next().toByte().toInt(); if (p and 2 != 0) pc += delta }
                    0x85 -> { assertEquals(0, p and 0x20); putWord(next(), a) }
                    0x4B -> push(0x8F)
                    0xAB -> { db = pop(); nz(db, small = true) }
                    0xF4 -> pushWord(nextWord())
                    0x6C -> pc = word(nextWord())
                    0xA9 -> { assertEquals(0, p and 0x20); a = nextWord(); nz(a) }
                    0x4C -> pc = nextWord()
                    0x60 -> pc = popWord() + 1
                    0x28 -> p = pop()
                    0x6B -> { assertEquals(0x1234, popWord() + 1); assertEquals(0x82, pop()); returned = true }
                    else -> error("Unexpected opcode ${opcode.toString(16)}")
                }
            }
            assertTrue(returned); assertEquals(0x1FF, sp); assertEquals(0x7E, db); assertEquals(0x75, p)
            return assertNotNull(context)
        }
        assertEquals(execute(managed = false), execute(managed = true))
        val scaffold = AsmDoorArrivalsSource.render(0x91F8, 0, "DoorASM_Test")
        assertContains(scaffold, "lda.w #(DoorASM_Test&${'$'}FFFF)")
        assertContains(scaffold, "sta.b ${'$'}12")
        assertContains(scaffold, "jmp.w DoorASM_Test")
    }
}
