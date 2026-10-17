package com.supermetroid.editor.maker

import com.supermetroid.editor.rom.RomParser

/** Equipment written to WRAM before Maker Play enters gameplay. Bit values from the game's item/beam masks. */
data class MakerPlayLoadout(val items: Int, val beams: Int, val energyTanks: Int, val missiles: Int)

/**
 * How Maker Play reaches a room on a freshly booted ROM with no save file:
 * load a station in the room's area, then (if the station is elsewhere) take a door into it.
 */
data class MakerPlayPlan(
    val roomId: Int,
    val area: Int,
    val stationIndex: Int,
    val stationRoomId: Int,
    val entryDoor: RomParser.DoorEntry?,
) {
    companion object {
        fun forRoom(parser: RomParser, roomId: Int): MakerPlayPlan? {
            val area = parser.readRoomHeader(roomId)?.area ?: return null
            val stations = (0 until parser.saveEntryCount(area).coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
                .mapNotNull { i -> parser.readSaveEntry(area, i)?.let { i to it } }
                .filter { it.second.roomId >= 0x8000 }
            stations.firstOrNull { it.second.roomId == roomId }?.let { (i, s) ->
                return MakerPlayPlan(roomId, area, i, s.roomId, null)
            }
            val (index, station) = stations.firstOrNull() ?: return null
            // Prefer a door from a room in the same area, so the area graphics are already loaded.
            val doors = parser.findDoorsLeadingTo(roomId).filter { it.doorDefPtr != 0 && !it.isElevator }
            val door = doors.firstOrNull { (it.bitflag and 0x40) == 0 } ?: doors.firstOrNull() ?: return null
            return MakerPlayPlan(roomId, area, index, station.roomId, door)
        }

        /** Samus centre (pixels) just inside [door] in its destination room, or null when the door has no cap. */
        fun arrivalPoint(door: RomParser.DoorEntry): Pair<Int, Int>? {
            if (door.doorCapCode == 0) return null
            val capX = door.doorCapCode and 0xFF
            val capY = (door.doorCapCode shr 8) and 0xFF
            return when (door.direction and 0x03) {
                0 -> capX * 16 + 40 to (capY + 4) * 16 - 24 // moving right: cap on the left wall
                1 -> capX * 16 - 24 to (capY + 4) * 16 - 24 // moving left: cap on the right wall
                2 -> capX * 16 + 32 to capY * 16 + 56       // moving down: cap in the ceiling
                else -> capX * 16 + 32 to capY * 16 - 40    // moving up: cap in the floor
            }
        }
    }
}

/**
 * Drives the game from boot to gameplay in [MakerPlayPlan.roomId]. Memory access and frame
 * waiting are supplied by the caller (GUI emulator workspace or a headless backend).
 */
class MakerPlayLauncher(
    private val read16: suspend (Int) -> Int,
    private val write16: suspend (Int, Int) -> Unit,
    private val waitFrames: suspend (Int) -> Unit,
    private val log: (String) -> Unit = {},
) {
    data class Result(val ok: Boolean, val message: String)

    private suspend fun waitForState(state: Int, maxFrames: Int): Boolean {
        var waited = 0
        while (read16(GAME_STATE) != state) {
            if (waited >= maxFrames) return false
            waitFrames(5); waited += 5
        }
        return true
    }

    suspend fun launch(plan: MakerPlayPlan, loadout: MakerPlayLoadout, placeSamus: Boolean = true): Result {
        if (!waitForState(STATE_TITLE, 1800)) return Result(false, "The game did not reach the title screen")
        val maxHealth = 99 + 100 * loadout.energyTanks
        for (a in listOf(0x09A2, 0x09A4)) write16(a, loadout.items)
        for (a in listOf(0x09A6, 0x09A8)) write16(a, loadout.beams)
        for (a in listOf(0x09C2, 0x09C4)) write16(a, maxHealth)
        for (a in listOf(0x09C6, 0x09C8)) write16(a, loadout.missiles)
        write16(0x079F, plan.area)
        write16(0x078B, plan.stationIndex)
        write16(GAME_STATE, STATE_LOAD_GAME)
        if (!waitForState(STATE_GAMEPLAY, 1800)) return Result(false, "Loading the station did not reach gameplay")
        log("station ${plan.area}/${plan.stationIndex} -> room 0x${read16(ROOM).toString(16)}")
        // Loading at the ship plays the landing pose; Samus ignores input (and door warps keep her
        // frozen) until it ends.
        var waited = 0
        while (read16(SAMUS_POSE) == POSE_SHIP_LANDING && waited < 900) { waitFrames(5); waited += 5 }
        val door = plan.entryDoor
        if (door != null) {
            write16(0x078D, door.doorDefPtr)
            write16(GAME_STATE, STATE_HIT_DOOR) // the game's own door transition
            waitFrames(10)
            if (!waitForState(STATE_GAMEPLAY, 1800)) return Result(false, "The door transition did not finish")
            log("door 0x${door.doorDefPtr.toString(16)} -> room 0x${read16(ROOM).toString(16)} samus=(${read16(SAMUS_X)},${read16(SAMUS_Y)})")
            if (placeSamus) MakerPlayPlan.arrivalPoint(door)?.let { (x, y) -> write16(SAMUS_X, x); write16(SAMUS_Y, y) }
        }
        val room = read16(ROOM)
        return if (room == plan.roomId) Result(true, "Playing room 0x${room.toString(16).uppercase()}")
        else Result(false, "Landed in room 0x${room.toString(16).uppercase()} instead of 0x${plan.roomId.toString(16).uppercase()}")
    }

    companion object {
        const val GAME_STATE = 0x0998
        const val ROOM = 0x079B
        const val SAMUS_X = 0x0AF6
        const val SAMUS_Y = 0x0AFA
        const val SAMUS_POSE = 0x0A1C
        const val POSE_SHIP_LANDING = 0x9B
        const val STATE_TITLE = 0x01
        const val STATE_LOAD_GAME = 0x06
        const val STATE_GAMEPLAY = 0x08
        const val STATE_HIT_DOOR = 0x09
    }
}
