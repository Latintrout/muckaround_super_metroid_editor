package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.*

internal data class AsmResolvedRoomSelectors(val selectors: Map<Int, SourceRoomSelectors>, val compiled: AsmCompiledBuildBase)

/** Resolve only real assembler symbols, and claim entire code envelopes (not just changed bytes). */
internal fun resolveExecutableRoomSelectors(inputs: Map<Int, SourceRoomSelectors>, compiled: AsmCompiledBuildBase): AsmResolvedRoomSelectors {
    if (inputs.values.all { it.routines.isEmpty() }) return AsmResolvedRoomSelectors(inputs, compiled)
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val enginePc = parser.snesToPc(0x8FE5D2)
    require(compiled.romBytes.copyOfRange(enginePc, enginePc + SmEditAsmRoomPredicateFormat.engineBytes.size)
        .contentEquals(SmEditAsmRoomPredicateFormat.engineBytes)) { "ASM-only predicates require the verified native room-selector dispatcher/exit ABI." }
    val spans = linkedSetOf<IntRange>()
    val header = if (compiled.romBytes.size % 0x8000 == 0x200) 0x200 else 0
    val resolved = inputs.mapValues { (_, selectors) -> selectors.copy(routines = selectors.routines.mapValues { (_, routine) ->
        val start = requireNotNull(labels[routine.label]) { "Missing executable predicate label ${routine.label}." }
        val end = requireNotNull(labels[routine.label + "_End"]) { "Missing executable predicate end label ${routine.label}_End." }
        val value = routine.copy(snesAddress = start, sizeBytes = end - start)
        val pc = parser.snesToPc(start) - header
        val size = requireNotNull(value.sizeBytes)
        require(pc >= 0 && pc + size <= compiled.referenceRomBody.size) { "Executable predicate is outside the compiled ROM." }
        require((pc until pc + size).all { compiled.referenceRomBody[it].toInt() and 255 == 255 }) {
            "${routine.label}: executable predicates must occupy verified free space in the reference ROM, not replace existing game data/code."
        }
        spans += pc until pc + size
        value
    }) }
    val additions = mutableListOf<AsmSourceOwnedRange>()
    spans.forEach { span ->
        var start: Int? = null
        for (pc in span.first..span.last + 1) {
            val uncovered = pc <= span.last && (compiled.sourceOwnedRanges + additions).none { pc >= it.pcOffset && pc < it.endExclusive() }
            if (uncovered && start == null) start = pc
            if (!uncovered && start != null) { additions += AsmSourceOwnedRange(start, pc - start); start = null }
        }
    }
    return AsmResolvedRoomSelectors(resolved, compiled.copy(sourceOwnedRanges = compiled.sourceOwnedRanges + additions))
}

private fun AsmSourceOwnedRange.endExclusive() = pcOffset + length
