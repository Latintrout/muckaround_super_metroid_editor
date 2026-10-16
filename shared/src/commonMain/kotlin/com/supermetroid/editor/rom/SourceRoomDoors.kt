package com.supermetroid.editor.rom

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits

/** Symbolic executable authority; a live preview has no guessed allocation address. */
data class SourceDoorArrival(val label: String, val snesAddress: Int? = null) {
    init {
        require(label.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid arrival ASM label." }
        require(snesAddress == null || snesAddress in 0x8F8000..0x8FFFFE) { "Arrival ASM must resolve inside bank \$8F." }
    }
}

/** Fixed native connection identities; values are ephemeral decoded ASM, never duplicate JSON deltas. */
data class SourceRoomDoor(val doorDefPtr: Int, val change: DoorChange, val arrival: SourceDoorArrival? = null) {
    init {
        require(doorDefPtr in 0x8000..0xFFF4 && change.doorIndex in 0..15) { "Invalid DoorDef identity." }
        // Direction 0..3: no closure, 4..7: blue door, 8..11: escape gate (bank $82:E8EB).
        require(change.destRoomPtr in 0x8000..0xFFFE && change.bitflag in 0..65535 && change.bitflag ushr 8 in 0..11 &&
            change.doorCapCode in 0..65535 && change.screenX in 0..255 && change.screenY in 0..255 &&
            change.distFromDoor in 0..65535 && (change.entryCode == 0 || change.entryCode in 0x8000..0xFFFE)) {
            "Door ${change.doorIndex + 1}: invalid destination, direction, byte/word field or arrival ASM pointer."
        }
        require(arrival == null || change.entryCode == (arrival.snesAddress?.and(65535) ?: 0)) { "Arrival ASM address does not match its resolved symbol." }
    }
    fun entry() = RomParser.DoorEntry(change.destRoomPtr, change.bitflag, change.doorCapCode, change.screenX,
        change.screenY, change.distFromDoor, change.entryCode, doorDefPtr)
    fun bytes(): ByteArray = buildList {
        require(arrival == null || arrival.snesAddress != null) { "Resolve arrival ASM ${arrival?.label} through assembler symbols before exporting." }
        fun word(value: Int) { add(value.toByte()); add((value ushr 8).toByte()) }
        word(change.destRoomPtr); word(change.bitflag); word(change.doorCapCode)
        add(change.screenX.toByte()); add(change.screenY.toByte()); word(change.distFromDoor); word(change.entryCode)
    }.toByteArray()
}

data class SourceRoomDoors(val doorOutPtr: Int, val entries: List<SourceRoomDoor>) {
    init {
        require(doorOutPtr in 0x8000..0xFFFE && entries.size in 1..16 && doorOutPtr + entries.size * 2 <= 0x10000 &&
            entries.map { it.change.doorIndex } == entries.indices.toList() && entries.map { it.doorDefPtr }.distinct().size == entries.size) {
            "Door source must cover each existing connection exactly once; list growth/reordering needs a structural transaction."
        }
    }
}

fun validateSourceRoomDoors(room: RoomEdits, source: SourceRoomDoors, parser: RomParser,
                            dimensions: (Int) -> Pair<Int, Int>? = { id -> parser.readRoomHeader(id)?.let { it.width to it.height } }) {
    require(room.asmDoorSourceVersion == 1 && room.doorChanges.isEmpty()) { "Door fields have unsupported or competing ASM/GUI ownership." }
    val header = requireNotNull(parser.readRoomHeader(room.roomId)) { "Missing door source room." }
    require(header.area in 0..6) { "The debug/unused area's door data is not writable." }
    require(source.doorOutPtr == header.doorOut && (room.roomHeaderChange?.doorOut ?: header.doorOut) == header.doorOut) {
        "Door-list relocation is not supported by the fixed DoorDef source format."
    }
    val native = parser.parseDoorList(header.doorOut)
    require(native.map { it.doorDefPtr } == source.entries.map { it.doorDefPtr }) { "Door count/order/identities changed; repair the native list before building." }
    for (door in source.entries) {
        val target = requireNotNull(dimensions(door.change.destRoomPtr)) { "Door ${door.change.doorIndex + 1}: destination has no readable room header." }
        // Vanilla includes unused/special entrances outside their target dimensions (e.g. Waterway
        // E-Tank -> Kraid Refill). Adoption and distance edits must retain them byte-for-byte, not
        // normalize them. Only the original geometry in the original-sized destination is exempt.
        val baseline = native[door.change.doorIndex]
        val nativeTarget = parser.readRoomHeader(door.change.destRoomPtr)?.let { it.width to it.height }
        if (target == nativeTarget && door.entry() == baseline.copy(
                bitflag = (baseline.bitflag and 0x40.inv()) or (door.change.bitflag and 0x40),
                distFromDoor = door.change.distFromDoor, entryCode = door.change.entryCode)) continue
        require(door.change.screenX < target.first && door.change.screenY < target.second) { "Door ${door.change.doorIndex + 1}: arrival screen is outside the destination room." }
        // Blue-door and escape-gate closures both use the cap coordinates.
        if (door.change.bitflag ushr 8 >= 4) require((door.change.doorCapCode and 255) < target.first * 16 &&
            (door.change.doorCapCode ushr 8) < target.second * 16) { "Door ${door.change.doorIndex + 1}: closing-door cap is outside the destination room." }
    }
}

fun validateSourceDoorOwnership(roomId: Int, source: SourceRoomDoors, parser: RomParser) {
    val pointers = source.entries.map { it.doorDefPtr }.toSet()
    require(parser.roomCatalog.rooms.none { info -> info.getRoomIdAsInt() != roomId &&
        parser.readRoomHeader(info.getRoomIdAsInt())?.let { h -> parser.parseDoorList(h.doorOut).any { it.doorDefPtr in pointers } } == true }) {
        "DoorDefs shared by multiple rooms need an explicit joint-owner workflow; no room was changed."
    }
}
