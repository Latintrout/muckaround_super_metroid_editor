package com.supermetroid.editor.maker

import com.supermetroid.editor.rom.RomParser

/** Equipment written to WRAM before Maker Play enters gameplay. Bit values from the game's item/beam masks. */
data class MakerPlayLoadout(
    val items: Int, val beams: Int, val energyTanks: Int, val missiles: Int,
    val supers: Int = 0, val powerBombs: Int = 0, val reserveTanks: Int = 0,
) {
    /** Beams Samus has on: Spazer and Plasma can't be on together (the game glitches), so Plasma wins. */
    val equippedBeams: Int get() = if (beams and BEAM_PLASMA != 0) beams and BEAM_SPAZER.inv() else beams

    companion object {
        const val BEAM_SPAZER = 0x0004
        const val BEAM_PLASMA = 0x0008
    }
}

/**
 * How Maker Play reaches a room on a freshly booted ROM with no save file:
 * load a station (in [area]), then (if the station is elsewhere) take a door into it.
 *
 * With a [startPoint] (block x, y) the plan always enters through a temporary door that
 * [MakerPlayDoor] writes into the play-test ROM copy, whose screen is the start point's,
 * so rooms with no door leading in can be played too. [entryDoor] is then filled in by
 * [withEntryDoor] once that copy is built. The door moves left when [facingLeft], so Samus
 * arrives facing left. When the room's area has no save station, the station comes from
 * another area and the door changes area ([areaChange]).
 */
data class MakerPlayPlan(
    val roomId: Int,
    /** Area of the station Play boots from (the room's own area unless [areaChange]). */
    val area: Int,
    val stationIndex: Int,
    val stationRoomId: Int,
    val entryDoor: RomParser.DoorEntry?,
    val startPoint: Pair<Int, Int>? = null,
    val facingLeft: Boolean = false,
    val areaChange: Boolean = false,
) {
    /** True when the play-test ROM needs a temporary entry door (see [MakerPlayDoor]). */
    val needsTemporaryDoor: Boolean get() = startPoint != null

    fun withEntryDoor(door: RomParser.DoorEntry) = copy(entryDoor = door)

    companion object {
        /** Save stations Play can boot from in [area]: (slot index, entry). */
        fun stationsIn(parser: RomParser, area: Int) =
            (0 until parser.saveEntryCount(area).coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
                .mapNotNull { i -> parser.readSaveEntry(area, i)?.let { i to it } }
                .filter { it.second.roomId >= 0x8000 }

        /**
         * [bootArea] forces the station's area (a start point can then cross areas through its door);
         * by default it is the room's own area, falling back to Crateria's when that area has none.
         */
        fun forRoom(
            parser: RomParser, roomId: Int, startPoint: Pair<Int, Int>? = null,
            facingLeft: Boolean = false, bootArea: Int? = null,
        ): MakerPlayPlan? {
            val area = parser.readRoomHeader(roomId)?.area ?: return null
            val stations = stationsIn(parser, area)
            if (startPoint != null) {
                val stationArea = bootArea ?: area.takeIf { stations.isNotEmpty() }
                    ?: (0..5).firstOrNull { stationsIn(parser, it).isNotEmpty() } ?: return null
                val (index, station) = stationsIn(parser, stationArea).firstOrNull() ?: return null
                return MakerPlayPlan(roomId, stationArea, index, station.roomId, null, startPoint, facingLeft, stationArea != area)
            }
            stations.firstOrNull { it.second.roomId == roomId }?.let { (i, s) ->
                return MakerPlayPlan(roomId, area, i, s.roomId, null)
            }
            val (index, station) = stations.firstOrNull() ?: return null
            // Prefer a door from a room in the same area, so the area graphics are already loaded.
            val doors = parser.findDoorsLeadingTo(roomId).filter { it.doorDefPtr != 0 && !it.isElevator }
            val door = doors.firstOrNull { (it.bitflag and 0x40) == 0 } ?: doors.firstOrNull() ?: return null
            return MakerPlayPlan(roomId, area, index, station.roomId, door)
        }

        /** Samus centre (pixels) standing in block [x], [y]: horizontally centred, feet on the block below. */
        fun startPixel(x: Int, y: Int): Pair<Int, Int> = x * 16 + 8 to (y + 1) * 16 - SAMUS_STAND_Y_RADIUS

        /** Samus's standing hitbox half-height in pixels. */
        const val SAMUS_STAND_Y_RADIUS = 0x15

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
 * Temporary entry door for Maker Play: a door definition in bank $83 free space of the
 * play-test ROM copy only (never the project's export or the source ROM) that leads into
 * a room on the screen of its start point.
 */
object MakerPlayDoor {
    private const val BANK83_PC = 0x018000
    private const val DOOR_SIZE = 12

    /** Copy of [exportedRom] with the door added, and the door (its [RomParser.DoorEntry.doorDefPtr] set). */
    fun install(
        exportedRom: ByteArray, roomId: Int, startPoint: Pair<Int, Int>,
        facingLeft: Boolean = false, areaChange: Boolean = false,
    ): Pair<ByteArray, RomParser.DoorEntry> {
        val header = if (exportedRom.size % 0x8000 == 0x200) 0x200 else 0
        val rom = exportedRom.copyOf()
        val bank = (header + BANK83_PC) until (header + BANK83_PC + 0x8000)
        // A run of unused (0xFF) bytes near the end of the bank, kept clear of its neighbours.
        val run = DOOR_SIZE + 16
        val start = (bank.last - run + 1 downTo bank.first).firstOrNull { pc -> (0 until run).all { rom[pc + it] == 0xFF.toByte() } }
            ?: error("No free space in bank \$83 for the Maker Play entry door")
        val pc = start + 8
        val screenX = startPoint.first / 16
        val screenY = startPoint.second / 16
        // Destination room, bitflag (0x40 = area change), direction (0 right, 1 left: Samus faces the way she
        // moves), no door cap, start screen, default spawn distance (0x8000), no door ASM.
        val flags = if (areaChange) 0x40 else 0x00
        val direction = if (facingLeft) 0x01 else 0x00
        val bytes = intArrayOf(roomId and 0xFF, (roomId shr 8) and 0xFF, flags, direction, 0x00, 0x00, screenX, screenY, 0x00, 0x80, 0x00, 0x00)
        bytes.forEachIndexed { i, v -> rom[pc + i] = v.toByte() }
        val ptr = 0x8000 + (pc - header - BANK83_PC)
        return rom to RomParser.DoorEntry(roomId, flags or (direction shl 8), 0, screenX, screenY, 0x8000, 0, ptr)
    }

    /** Writes the play-test copy of [exported] next to it and returns its path and entry door. */
    fun writePlayCopy(exported: java.io.File, roomId: Int, startPoint: Pair<Int, Int>): Pair<java.io.File, RomParser.DoorEntry> {
        val (rom, door) = install(exported.readBytes(), roomId, startPoint)
        val copy = java.io.File(exported.parentFile, exported.nameWithoutExtension + "-maker-play." + exported.extension)
        copy.writeBytes(rom)
        return copy to door
    }

    /** [writePlayCopy] for [plan]'s start point, facing and area change. */
    fun writePlayCopy(exported: java.io.File, plan: MakerPlayPlan): Pair<java.io.File, RomParser.DoorEntry> {
        val start = requireNotNull(plan.startPoint) { "The plan has no start point" }
        val (rom, door) = install(exported.readBytes(), plan.roomId, start, plan.facingLeft, plan.areaChange)
        val copy = java.io.File(exported.parentFile, exported.nameWithoutExtension + "-maker-play." + exported.extension)
        copy.writeBytes(rom)
        return copy to door
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
    private val writeBlock: suspend (Int, ByteArray) -> Unit = { a, bytes ->
        for (i in bytes.indices step 2) write16(a + i, (bytes[i].toInt() and 0xFF) or ((bytes.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8))
    },
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
        // Skipping file select skips the game's new-file setup, so the save-data mirror still holds
        // power-on RAM (e.g. 0x60 bytes: the "Zebes timebomb" event bit selects the escape states).
        // Clear it as a fresh file would: equipment block, then events/bosses/items/doors/stations.
        writeBlock(SAMUS_DATA.first, ByteArray(SAMUS_DATA.count()))
        writeBlock(SAVE_MIRROR.first, ByteArray(SAVE_MIRROR.count()))
        DEFAULT_CONTROLS.forEach { (a, v) -> write16(a, v) }
        val maxHealth = 99 + 100 * loadout.energyTanks
        for (a in listOf(0x09A2, 0x09A4)) write16(a, loadout.items)
        write16(0x09A6, loadout.equippedBeams); write16(0x09A8, loadout.beams)
        for (a in listOf(0x09C2, 0x09C4)) write16(a, maxHealth)
        for (a in listOf(0x09C6, 0x09C8)) write16(a, loadout.missiles)
        for (a in listOf(0x09CA, 0x09CC)) write16(a, loadout.supers)
        for (a in listOf(0x09CE, 0x09D0)) write16(a, loadout.powerBombs)
        // Reserve tanks: max and current reserve energy, auto mode (1) when there are any.
        for (a in listOf(0x09D4, 0x09D6)) write16(a, 100 * loadout.reserveTanks)
        write16(0x09C0, if (loadout.reserveTanks > 0) 1 else 0)
        write16(0x079F, plan.area)
        write16(0x078B, plan.stationIndex)
        write16(GAME_STATE, STATE_LOAD_GAME)
        if (!waitForState(STATE_GAMEPLAY, 1800)) return Result(false, "Loading the station did not reach gameplay")
        log("station ${plan.area}/${plan.stationIndex} -> room 0x${read16(ROOM).toString(16)}")
        // Loading at the ship plays the landing pose; Samus ignores input (and door warps keep her
        // frozen) until it ends.
        var waited = 0
        while (read16(SAMUS_POSE) == POSE_SHIP_LANDING && waited < 900) { waitFrames(5); waited += 5 }
        if (plan.needsTemporaryDoor && plan.entryDoor == null) return Result(false, "The play-test ROM has no entry door for this room")
        val door = plan.entryDoor
        if (door != null) {
            write16(0x078D, door.doorDefPtr)
            write16(GAME_STATE, STATE_HIT_DOOR) // the game's own door transition
            waitFrames(10)
            if (!waitForState(STATE_GAMEPLAY, 1800)) return Result(false, "The door transition did not finish")
            log("door 0x${door.doorDefPtr.toString(16)} -> room 0x${read16(ROOM).toString(16)} samus=(${read16(SAMUS_X)},${read16(SAMUS_Y)})")
            val start = plan.startPoint?.let { (x, y) -> MakerPlayPlan.startPixel(x, y) }
            if (placeSamus) (start ?: MakerPlayPlan.arrivalPoint(door))?.let { (x, y) -> write16(SAMUS_X, x); write16(SAMUS_Y, y) }
            // At a Start point Samus stands facing its way (she'd otherwise keep the station's arrival pose).
            if (placeSamus && start != null) {
                val pose = if (plan.facingLeft) POSE_STAND_LEFT else POSE_STAND_RIGHT
                val direction = if (plan.facingLeft) DIRECTION_LEFT else DIRECTION_RIGHT
                for (a in listOf(SAMUS_POSE, SAMUS_PREVIOUS_POSE)) write16(a, pose)
                for (a in listOf(SAMUS_POSE_DIRECTION, SAMUS_PREVIOUS_POSE_DIRECTION)) write16(a, direction)
            }
        }
        log("room state header \$8F:${read16(ROOM_STATE).toString(16)} events=${read16(0xD820).toString(16)}/${read16(0xD822).toString(16)}")
        val room = read16(ROOM)
        return if (room == plan.roomId) Result(true, "Playing room 0x${room.toString(16).uppercase()}")
        else Result(false, "Landed in room 0x${room.toString(16).uppercase()} instead of 0x${plan.roomId.toString(16).uppercase()}")
    }

    companion object {
        const val GAME_STATE = 0x0998
        const val ROOM = 0x079B
        const val ROOM_STATE = 0x07BB
        const val SAMUS_X = 0x0AF6
        const val SAMUS_Y = 0x0AFA
        const val SAMUS_POSE = 0x0A1C
        /** $7E:09A2-09FF: Samus equipment/ammo/controls, the block a save file stores. */
        val SAMUS_DATA = 0x09A2..0x09FF
        /** $7E:D7C0-DE1B: save-file mirror (events $D820, bosses $D828, items $D870, doors $D8B0, stations). */
        val SAVE_MIRROR = 0xD7C0..0xDE1B
        /** New-file button bindings: shot X, jump A, dash B, item select Select, cancel Y, angle R / L. */
        val DEFAULT_CONTROLS = listOf(
            0x09B2 to 0x0040, 0x09B4 to 0x0080, 0x09B6 to 0x8000, 0x09B8 to 0x2000,
            0x09BA to 0x4000, 0x09BC to 0x0010, 0x09BE to 0x0020,
        )
        const val POSE_SHIP_LANDING = 0x9B
        /** $0A1E/$0A22: low byte = facing (4 right, 8 left), high byte = movement type (0 standing). */
        const val SAMUS_POSE_DIRECTION = 0x0A1E
        const val SAMUS_PREVIOUS_POSE = 0x0A20
        const val SAMUS_PREVIOUS_POSE_DIRECTION = 0x0A22
        const val POSE_STAND_RIGHT = 0x01
        const val POSE_STAND_LEFT = 0x02
        const val DIRECTION_RIGHT = 0x0004
        const val DIRECTION_LEFT = 0x0008
        const val STATE_TITLE = 0x01
        const val STATE_LOAD_GAME = 0x06
        const val STATE_GAMEPLAY = 0x08
        const val STATE_HIT_DOOR = 0x09
    }
}
