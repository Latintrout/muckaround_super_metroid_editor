package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.*

/** The native dispatcher establishes 16-bit A/X/Y and DB=$8F, and supplies an RTS return. */
internal val DOOR_ARRIVAL_DISPATCH_BYTES = listOf(0x08, 0x8B, 0xC2, 0x30, 0xAE, 0x8D, 0x07,
    0xBF, 0x0A, 0x00, 0x83, 0xF0, 0x0A, 0x85, 0x12, 0x4B, 0xAB, 0xF4, 0xB9, 0xE8, 0x6C, 0x12, 0x00, 0xAB, 0x28, 0x6B)
    .map { it.toByte() }.toByteArray()

/** No guessed live-preview address may reach the ROM writer. Resolve against this build only. */
internal fun resolveDoorArrivals(inputs: Map<Int, SourceRoomDoors>, compiled: AsmCompiledBuildBase): Map<Int, SourceRoomDoors> {
    if (inputs.values.all { doors -> doors.entries.all { it.arrival == null } }) return inputs
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val engine = parser.snesToPc(0x8FE8A3)
    require(compiled.romBytes.copyOfRange(engine, engine + DOOR_ARRIVAL_DISPATCH_BYTES.size).contentEquals(DOOR_ARRIVAL_DISPATCH_BYTES)) {
        "Symbolic arrival ASM requires the verified native bank-8F/16-bit/RTS door-dispatch ABI."
    }
    return inputs.mapValues { (_, doors) -> doors.copy(entries = doors.entries.map { door ->
        val binding = door.arrival ?: return@map door
        val address = requireNotNull(labels[binding.label]) { "Missing arrival ASM symbol ${binding.label}." }
        val resolved = binding.copy(snesAddress = address) // Enforces bank and pointer bounds, without truncation.
        if (binding.label.startsWith("SmeditArrival_")) {
            val end = requireNotNull(labels[binding.label + "_End"]) { "Missing managed arrival boundary." }
            require(end > address && end - address <= 1024 && end <= 0x900000) { "Managed arrival escaped its bank/size bounds." }
            val pc = requireNotNull(snesLoRomToPc(address))
            require((pc until pc + end - address).all { offset -> compiled.sourceOwnedRanges.any { offset >= it.pcOffset && offset < it.pcOffset + it.length } }) {
                "${binding.label}: the complete managed arrival envelope must have source ownership."
            }
        }
        door.copy(change = door.change.copy(entryCode = address and 65535), arrival = resolved)
    }) }
}
