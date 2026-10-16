package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.rom.*
import kotlin.test.*

class AsmExecutableRoomSelectorsTest {
    private fun input() = mapOf(0x9000 to SourceRoomSelectors(listOf("custom", "default"),
        mapOf("default" to projectRoomStateCondition(ProjectRoomStateConditionKind.DEFAULT)),
        mapOf("custom" to SourceRoomSelectorRoutine("MyPredicate"))))
    private fun compiled(start: Int = 0x8FF000, end: Int = 0x8FF040): AsmCompiledBuildBase {
        val rom = ByteArray(0x300000) { -1 }
        SmEditAsmRoomPredicateFormat.engineBytes.copyInto(rom, 0x7E5D2)
        rom[0x7F000] = 0x60
        fun symbol(value: Int, name: String) = "${(value ushr 16).toString(16)}:${(value and 65535).toString(16).padStart(4, '0')} $name\n"
        return AsmCompiledBuildBase(rom, rom.copyOf().also { it[0x7F000] = -1 }, "test", emptyList(), assemblerVersion = "test", compilerOutput = emptyList(),
            compilerSymbols = ("[labels]\n" + symbol(start, "MyPredicate") + symbol(end, "MyPredicate_End")).toByteArray())
    }
    @Test fun `resolved routines have assembler addresses and complete ownership including unchanged erased bytes`() {
        val base = compiled().copy(sourceOwnedRanges = listOf(AsmSourceOwnedRange(0x7F000, 1, "project/custom.asm")))
        val resolved = resolveExecutableRoomSelectors(input(), base)
        val value = resolved.selectors.getValue(0x9000)
        assertEquals(0x8FF000, value.routines.getValue("custom").snesAddress)
        assertEquals(64, value.routines.getValue("custom").sizeBytes)
        assertFalse("custom" in value.conditions, "Opaque conditions must not get a guessed GUI predicate")
        val claims = resolved.compiled.sourceOwnedRanges
        for (pc in 0x7F000 until 0x7F040) assertEquals(1, claims.count { pc in it.pcOffset until it.pcOffset + it.length })
        assertEquals("asm-module:custom", claims.first().owner)
        assertContentEquals(base.romBytes, resolved.compiled.romBytes)
        assertTrue(base.sourceOwnedRanges.size == 1, "Resolution cannot mutate the compiler cache entry")
    }
    @Test fun `missing labels invalid envelopes and modified selector engine fail before graph planning`() {
        val source = input()
        for (base in listOf(compiled().copy(compilerSymbols = "[labels]\n8F:F000 MyPredicate\n".toByteArray()),
            compiled(0x908000, 0x908040), compiled(0x8FF000, 0x8FF000), compiled(0x8FF010, 0x8FF000),
            compiled(0x8FF000, 0x8FF401), compiled(0x8FFFF0, 0x900010))) {
            assertFails { resolveExecutableRoomSelectors(source, base) }
        }
        val changed = compiled().also { it.romBytes[0x7E5D2] = 0 }
        assertContains(assertFails { resolveExecutableRoomSelectors(source, changed) }.message.orEmpty(), "dispatcher/exit ABI")
        val occupied = compiled().also { it.referenceRomBody[0x7F03F] = 0 }
        assertContains(assertFails { resolveExecutableRoomSelectors(source, occupied) }.message.orEmpty(), "verified free space")
    }
    @Test fun `typed and executable authorities are disjoint and default remains typed last`() {
        val c = projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER)
        val d = projectRoomStateCondition(ProjectRoomStateConditionKind.DEFAULT)
        val routine = SourceRoomSelectorRoutine("MyPredicate")
        assertFails { SourceRoomSelectors(listOf("custom", "default"), mapOf("custom" to c, "default" to d), mapOf("custom" to routine)) }
        assertFails { SourceRoomSelectors(listOf("default", "custom"), mapOf("default" to d), mapOf("custom" to routine)) }
        assertFails { SourceRoomSelectors(listOf("custom"), emptyMap(), mapOf("custom" to routine)) }
        assertFails { SourceRoomSelectorRoutine("MyPredicate+1") }
        val typed = mapOf(0x9000 to SourceRoomSelectors(listOf("default"), mapOf("default" to d)))
        val base = compiled().copy(compilerSymbols = byteArrayOf())
        assertSame(base, resolveExecutableRoomSelectors(typed, base).compiled)
    }
}
