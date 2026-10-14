package com.supermetroid.editor.rom

/**
 * Locate embedded Layer 2 using the level stream's allocation, not room dimensions.
 * The layout is [L1 byte count][L1 words][one BTS byte per allocated block][L2 words].
 * Vanilla Double Chamber allocates more blocks than its room header displays.
 */
fun embeddedLayer2StartOffset(levelData: ByteArray, visibleBlockCount: Int): Int? {
    if (levelData.size < 2 || visibleBlockCount <= 0) return null
    val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
    val allocatedBlockCount = layer1Size / 2
    if (layer1Size % 2 != 0 || visibleBlockCount > allocatedBlockCount) return null

    val start = 2 + layer1Size + allocatedBlockCount
    return start.takeIf { it + visibleBlockCount * 2 <= levelData.size }
}
