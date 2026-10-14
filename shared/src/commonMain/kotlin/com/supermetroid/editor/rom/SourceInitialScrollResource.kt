package com.supermetroid.editor.rom

/** Initial grid, not a B703 trigger stream: one byte per screen, no terminator. */
data class SourceInitialScrollResource(val identity: String, val width: Int, val height: Int, val values: List<Int>) {
    init {
        require(width in 1..15 && height in 1..15 && width * height <= 50) { "Initial scrolls require supported 1–15 dimensions and at most 50 screens." }
        require(values.size == width * height && values.all { it in 0..2 }) { "Initial scrolls need exactly one Red/Blue/Green byte per screen." }
    }
    fun bytes(): ByteArray = values.map(Int::toByte).toByteArray()
}
