package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser

/** Navigation evidence, not another authoring model or a generated source file. */
internal sealed interface AsmPlmPlacementTarget {
    data class Source(val lineIndex: Int, val columnIndex: Int) : AsmPlmPlacementTarget
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
