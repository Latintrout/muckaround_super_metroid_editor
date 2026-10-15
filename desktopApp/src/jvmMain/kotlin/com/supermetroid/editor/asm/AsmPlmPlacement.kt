package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser

/** Navigation evidence, not another authoring model or a generated source file. */
internal sealed interface AsmPlmPlacementTarget {
    data class Source(val lineIndex: Int, val columnIndex: Int = 0) : AsmPlmPlacementTarget
    data class Unavailable(val reason: String) : AsmPlmPlacementTarget
}

/** Captured only on navigation; history must not silently replace it with another room/state. */
internal data class AsmPlmPlacementSnapshot(
    val roomId: Int,
    val roomName: String,
    val stateName: String,
    val entry: RomParser.PlmEntry,
    val reason: String,
)

/** Used only immediately after an explicit migration, never as a stale-selection fallback.
 * Custom scroll IDs become named references (with an unallocated preview parameter). */
internal fun placementAfterObjectMigration(entry: RomParser.PlmEntry, current: List<RomParser.PlmEntry>): RomParser.PlmEntry? {
    current.singleOrNull { it == entry }?.let { return it }
    if (entry.id != 0xB703 || (entry.param and 0xFF00) != 0xCC00) return null
    return current.singleOrNull { it.id == entry.id && it.x == entry.x && it.y == entry.y && it.param == 0 }
}

internal fun AsmPlmDocument.placementTarget(entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
    // Identical types can occur many times in one list. Never navigate by ID alone or guess
    // between identical records: source order can change independently of the room preview.
    val matches = records.filter { it.entry == entry }
    if (matches.size != 1) return AsmPlmPlacementTarget.Unavailable(
        if (matches.isEmpty()) "This placement no longer matches an entry in the current ASM source."
        else "Multiple identical entries match this placement; an exact source line cannot be identified safely.",
    )
    val offset = matches.single().fields.first().first
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    return AsmPlmPlacementTarget.Source(
        lineIndex = text.take(offset).count { it == '\n' },
        columnIndex = offset - lineStart,
    )
}
