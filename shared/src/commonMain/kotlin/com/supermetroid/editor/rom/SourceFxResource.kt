package com.supermetroid.editor.rom

import com.supermetroid.editor.data.FxChange

/** Ordered 16-byte headers. A final default header stops lookup; otherwise a word $FFFF stops it. */
data class SourceFxResource(val identity: String, val entries: List<RomParser.FxEntry>) {
    init {
        require(entries.size <= 16) { "$identity: more than 16 FX headers are not supported." }
        require(entries.map { it.doorSelect }.distinct().size == entries.size) { "$identity: duplicate FX door selectors hide later records." }
        require(entries.dropLast(1).none { it.doorSelect == 0 }) { "$identity: Default FX must be last." }
        entries.forEach { fxEntryFromFields(it.fields()) }
    }
    fun bytes(): ByteArray = entries.flatMap { entry -> entry.fields().flatMapIndexed { index, value ->
        if (index < 4) listOf(value.toByte(), (value ushr 8).toByte()) else listOf(value.toByte())
    } }.toByteArray() + if (entries.lastOrNull()?.doorSelect == 0) ByteArray(0) else byteArrayOf(-1, -1)
}

fun RomParser.FxEntry.fields(): List<Int> = listOf(doorSelect, liquidSurfaceStart, liquidSurfaceNew, liquidSpeed,
    liquidDelay, fxType, fxBitA, fxBitB, fxBitC, paletteFxBitflags, tileAnimBitflags, paletteBlend)

fun fxEntryFromFields(values: List<Int>): RomParser.FxEntry {
    require(values.size == 12 && values.take(4).all { it in 0..0xFFFF } && values.drop(4).all { it in 0..255 }) { "FX requires four words and eight bytes." }
    require(values[0] == 0 || values[0] in 0x8000..0xFFFE) { "FX door selector must be Default or a bank-\$83 door pointer (not \$FFFF)." }
    // $89:AC48 indexes the 23 word function pointers at $83:AC18 with this BYTE OFFSET.
    require(values[5] in 0..0x2C && values[5] % 2 == 0) { "FX type must be an even native dispatch offset from \$00 to \$2C." }
    return RomParser.FxEntry(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], values[11])
}

fun RomParser.FxEntry.withFxChange(change: FxChange?): RomParser.FxEntry = if (change == null) this else copy(
    liquidSurfaceStart = change.liquidSurfaceStart ?: liquidSurfaceStart, liquidSurfaceNew = change.liquidSurfaceNew ?: liquidSurfaceNew,
    liquidSpeed = change.liquidSpeed ?: liquidSpeed, liquidDelay = change.liquidDelay ?: liquidDelay, fxType = change.fxType ?: fxType,
    fxBitA = change.fxBitA ?: fxBitA, fxBitB = change.fxBitB ?: fxBitB, fxBitC = change.fxBitC ?: fxBitC,
    paletteFxBitflags = change.paletteFxBitflags ?: paletteFxBitflags, tileAnimBitflags = change.tileAnimBitflags ?: tileAnimBitflags,
    paletteBlend = change.paletteBlend ?: paletteBlend,
)

fun emptyDefaultFxEntry() = RomParser.FxEntry(0, 0xFFFF, 0xFFFF, 0, 0, 0, 2, 2, 0, 0, 0, 0)

/** Strict adoption/build reader, unlike the tolerant ROM preview parser. Never adopt a clipped table. */
fun readSourceFxResource(parser: RomParser, pointer: Int, identity: String): SourceFxResource {
    if (pointer == 0 || pointer == 0xFFFF) return SourceFxResource(identity, emptyList())
    require(pointer in 0x8000..0xFFFE) { "FX pointer is outside bank \$83." }
    val rom = parser.getRomData()
    var pc = parser.snesToPc(0x830000 or pointer)
    val end = minOf(rom.size, parser.snesToPc(0x83FFFF) + 1)
    val entries = mutableListOf<RomParser.FxEntry>()
    while (pc + 2 <= end) {
        val door = parser.readUInt16At(pc)
        if (door == 0xFFFF) return SourceFxResource(identity, entries.toList())
        require(entries.size < 16 && pc + 16 <= end) { "$identity: truncated or unterminated FX table." }
        val words = (0 until 4).map { parser.readUInt16At(pc + it * 2) }
        val bytes = (8 until 16).map { rom[pc + it].toInt() and 255 }
        entries += fxEntryFromFields(words + bytes)
        if (door == 0) return SourceFxResource(identity, entries.toList())
        pc += 16
    }
    error("$identity: FX table crosses the bank boundary without a stop record.")
}

fun validateSourceFxDoors(roomId: Int, resource: SourceFxResource, parser: RomParser) {
    for (entry in resource.entries.filter { it.doorSelect != 0 }) {
        require(entry.doorSelect <= 0xFFF4) { "FX door record crosses bank \$83." }
        val pc = parser.snesToPc(0x830000 or entry.doorSelect)
        require(pc + 12 <= parser.getRomData().size && parser.readUInt16At(pc) == roomId) {
            "${resource.identity}: door \$${entry.doorSelect.toString(16).uppercase()} does not enter this room."
        }
    }
}

/** $A9:86EE/$888F read slots 1/2 after the Default at $83:A0A4. Door lookup alone is not its owner. */
fun requireDoorRoutedFxOwnership(room: com.supermetroid.editor.data.RoomEdits, parser: RomParser) {
    val states = parser.inspectRoomStates(room.roomId).states
    require(room.states.mapNotNull { it.baseSourceStateIndex() }.distinct().none { index ->
        states.getOrNull(index)?.stateDataPcOffset?.let { parser.readStateData(it)["fxPtr"] == 0xA0A4 } == true
    }) { "Mother Brain FX uses runtime-indexed records after Default. Keep its original FX table until indexed FX authoring is supported; no source was replaced." }
}
