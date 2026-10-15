package com.supermetroid.editor.rom

import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TILE_EDIT_LAYER_1
import com.supermetroid.editor.data.TileEdit

/** Decoded authored level stream. This projection is never another persisted copy in project JSON. */
data class SourceRoomLayoutResource(val identity: String, val width: Int, val height: Int, val values: List<Int>) {
    init {
        require(width in 16..240 && height in 16..240 && width % 16 == 0 && height % 16 == 0 && width * height <= 12800) { "Unsupported layout dimensions." }
        require(values.size in 2..65536 && values.all { it in 0..255 }) { "$identity: invalid level bytes." }
        val layerBytes = values[0] or (values[1] shl 8)
        require(layerBytes % 2 == 0 && layerBytes / 2 >= width * height && values.size >= 2 + layerBytes + layerBytes / 2) {
            "$identity: truncated layout or layer-1 byte count does not cover the room."
        }
    }
    fun bytes(): ByteArray = values.map(Int::toByte).toByteArray()

    fun edited(edits: List<TileEdit>): SourceRoomLayoutResource {
        val data = bytes()
        val layerBytes = values[0] or (values[1] shl 8)
        val layer2 = embeddedLayer2StartOffset(data, width * height)
        fun word(offset: Int) = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
        for (edit in edits) {
            require(edit.layer == TILE_EDIT_LAYER_1 || edit.layer == TILE_EDIT_LAYER_2) { "Unsupported layout layer." }
            require(edit.blockX in 0 until width && edit.blockY in 0 until height && edit.newBlockWord in 0..65535 && edit.newBts in 0..255) { "Layout edit is outside the room or field width." }
            val index = edit.blockY * width + edit.blockX
            val offset = if (edit.layer == TILE_EDIT_LAYER_2) requireNotNull(layer2) { "This layout has no embedded layer 2." } + index * 2 else 2 + index * 2
            require(word(offset) == edit.oldBlockWord && (edit.layer == TILE_EDIT_LAYER_2 || (data[2 + layerBytes + index].toInt() and 255) == edit.oldBts)) {
                "Layout source changed since this tile edit was prepared."
            }
            data[offset] = edit.newBlockWord.toByte(); data[offset + 1] = (edit.newBlockWord ushr 8).toByte()
            if (edit.layer != TILE_EDIT_LAYER_2) data[2 + layerBytes + index] = edit.newBts.toByte()
        }
        return copy(values = data.map { it.toInt() and 255 })
    }
}
