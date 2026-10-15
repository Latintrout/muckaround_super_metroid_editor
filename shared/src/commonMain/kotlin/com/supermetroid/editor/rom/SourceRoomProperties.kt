package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.StateDataChange

/** Validated, ephemeral source values. Dimensions and door pointers remain structural resources. */
data class SourceRoomProperties(val header: RoomHeaderChange, val states: Map<String, StateDataChange>) {
    init {
        require(header.index in 0..255 && header.area in 0..6 && header.mapX in 0..63 && header.mapY in 0..30 &&
            header.upScroller in 0..255 && header.downScroller in 0..255 && header.creBitflag in 0..255 &&
            header.width == null && header.height == null && header.doorOut == null) { "Invalid authored room header scalars: $header" }
        require(states.isNotEmpty()) { "Room properties need complete state coverage." }
        states.forEach { (id, fields) -> require(fields.tileset in 0..28 && fields.musicData in 0..255 && fields.musicTrack in 0..255 && fields.bgScrolling in 0..65535) {
            "$id: invalid tileset, music byte or background-scroll word."
        } }
    }
}
