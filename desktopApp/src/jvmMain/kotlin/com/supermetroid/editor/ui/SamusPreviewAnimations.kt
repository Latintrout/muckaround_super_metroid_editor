package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SamusDeathSprites
import com.supermetroid.editor.rom.SamusSpriteDecoder
import com.supermetroid.editor.rom.SpriteAnimation

// UI-only selections: death uses separate graphics, not the 253 gameplay pose IDs.
internal const val SAMUS_DEATH_RIGHT = -1
internal const val SAMUS_DEATH_LEFT = -2
internal val SAMUS_PREVIEW_GROUPS = SamusSpriteDecoder.ANIMATION_GROUPS +
    SamusSpriteDecoder.AnimGroup("Death", listOf(SAMUS_DEATH_RIGHT, SAMUS_DEATH_LEFT), "Suit explosion")

internal fun isSamusDeathSelection(animationId: Int): Boolean =
    animationId == SAMUS_DEATH_RIGHT || animationId == SAMUS_DEATH_LEFT

internal fun samusPreviewAnimation(
    parser: RomParser,
    decoder: SamusSpriteDecoder,
    animationId: Int,
    suit: SamusSpriteDecoder.SuitType,
): SpriteAnimation? = if (isSamusDeathSelection(animationId)) {
    SamusDeathSprites(parser).render(suit.ordinal, animationId == SAMUS_DEATH_LEFT)
} else {
    decoder.buildAnimation(animationId, suit, renderSize = 96)
}

internal fun samusDeathTarget(parser: RomParser, suit: SamusSpriteDecoder.SuitType): NativeSpriteTarget {
    val stage = parser.readByteAt(parser.snesToPc(0x9BB824))
    val palette = 0x9B0000 or parser.readUInt16At(parser.snesToPc(0x9BB7D3 + suit.ordinal * 20 + stage * 2))
    return NativeSpriteTarget("9B8000", palette, 1)
}
