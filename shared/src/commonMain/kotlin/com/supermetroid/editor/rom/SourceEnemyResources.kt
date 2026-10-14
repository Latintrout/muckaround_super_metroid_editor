package com.supermetroid.editor.rom

import com.supermetroid.editor.data.EnemyChange

/** Disposable decoded source values. Project JSON stores ownership, never another actor list. */
data class SourceEnemyPopulation(val identity: String, val entries: List<RomParser.EnemyEntry>, val killCount: Int) {
    init {
        require(entries.size <= 32 && killCount in 0..255) { "$identity: the native engine has 32 enemy slots; kill quota must fit one byte." }
        entries.forEach { require(it.fields().all { value -> value in 0..0xFFFF } && it.id in 0x8000..0xFFC0) {
            "$identity: enemy records require eight words and a bank-\$A0 header, not a terminator."
        } }
    }
    fun bytes(): ByteArray = (entries.flatMap { it.fields().flatMap(::enemyWordBytes) } + listOf((-1).toByte(), (-1).toByte(), killCount.toByte())).toByteArray()
}

data class SourceEnemyGraphics(val identity: String, val entries: List<RomParser.EnemyGfxEntry>) {
    init {
        require(entries.size <= 4) { "$identity: the engine supports only four enemy GFX entries." }
        entries.forEach { require(it.speciesId in 0x8000..0xFFC0 && it.paletteIndex in 0..0xFFFF && (it.paletteIndex and 255) <= 7) {
            "$identity: GFX requires a species word and a VRAM destination with palette offset 0..7."
        } }
    }
    fun bytes(): ByteArray = (entries.flatMap { enemyWordBytes(it.speciesId) + enemyWordBytes(it.paletteIndex) } + listOf((-1).toByte(), (-1).toByte())).toByteArray()
}

data class SourceEnemyResources(val population: SourceEnemyPopulation, val graphics: SourceEnemyGraphics)

fun RomParser.EnemyEntry.fields() = listOf(id, x, y, initParam, properties, extra1, extra2, extra3)
fun enemyEntryFromFields(values: List<Int>): RomParser.EnemyEntry {
    require(values.size == 8 && values.all { it in 0..0xFFFF }) { "Enemy population records require eight words." }
    return RomParser.EnemyEntry(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
}
private fun enemyWordBytes(value: Int) = listOf(value.toByte(), (value ushr 8).toByte())

/** Strict reads stay inside the pointer's bank; tolerant preview parsing cannot authorize ownership. */
fun readSourceEnemyPopulation(parser: RomParser, pointer: Int, identity: String): SourceEnemyPopulation {
    if (pointer == 0 || pointer == 0xFFFF) return SourceEnemyPopulation(identity, emptyList(), 0)
    require(pointer in 0x8000..0xFFFE) { "$identity: population pointer is outside bank \$A1." }
    var pc = parser.snesToPc(0xA10000 or pointer)
    val end = minOf(parser.getRomData().size, parser.snesToPc(0xA1FFFF) + 1)
    val entries = mutableListOf<RomParser.EnemyEntry>()
    while (pc + 2 <= end) {
        if (parser.readUInt16At(pc) == 0xFFFF) {
            // Native empty lists can intentionally omit the quota byte; the loader returns before reading it.
            val quota = if (entries.isEmpty()) 0 else {
                require(pc + 3 <= end) { "$identity: missing required kill count." }; parser.readByteAt(pc + 2)
            }
            return SourceEnemyPopulation(identity, entries.toList(), quota)
        }
        require(entries.size < 32 && pc + 16 <= end) { "$identity: truncated, unterminated or over-32-slot population." }
        entries += enemyEntryFromFields((0..7).map { parser.readUInt16At(pc + it * 2) })
        pc += 16
    }
    error("$identity: population crosses bank \$A1 without a terminator.")
}

fun readSourceEnemyGraphics(parser: RomParser, pointer: Int, identity: String): SourceEnemyGraphics {
    if (pointer == 0 || pointer == 0xFFFF) return SourceEnemyGraphics(identity, emptyList())
    require(pointer in 0x8000..0xFFFE) { "$identity: graphics pointer is outside bank \$B4." }
    var pc = parser.snesToPc(0xB40000 or pointer)
    val end = minOf(parser.getRomData().size, parser.snesToPc(0xB4FFFF) + 1)
    val entries = mutableListOf<RomParser.EnemyGfxEntry>()
    while (pc + 2 <= end) {
        if (parser.readUInt16At(pc) == 0xFFFF) return SourceEnemyGraphics(identity, entries.toList())
        require(entries.size < 4 && pc + 4 <= end) { "$identity: truncated GFX or more than four entries." }
        entries += RomParser.EnemyGfxEntry(parser.readUInt16At(pc), parser.readUInt16At(pc + 2))
        pc += 4
    }
    error("$identity: GFX crosses bank \$B4 without a terminator.")
}

fun applyEnemyChanges(entries: List<RomParser.EnemyEntry>, changes: List<EnemyChange>): List<RomParser.EnemyEntry> {
    val result = entries.toMutableList()
    for (change in changes) {
        val entry = RomParser.EnemyEntry(change.enemyId, change.x, change.y, change.initParam, change.properties, change.extra1, change.extra2, change.extra3)
        when (change.action) {
            "add" -> result.add(entry)
            "remove" -> result.removeAll { it.id == change.enemyId && it.x == change.origX && it.y == change.origY }
            "update" -> {
                val index = result.indexOfFirst { it.id == change.enemyId && it.x == change.origX && it.y == change.origY }
                require(index >= 0) { "An enemy update has no matching original placement." }
                result[index] = entry
            }
            else -> error("Unsupported enemy change ${change.action}.")
        }
    }
    return result.toList()
}

/** Preserve native transfer-only entries and intentional shared/no-GFX actors. Never prune by population membership. */
fun completeEnemyGraphics(parser: RomParser, population: List<RomParser.EnemyEntry>, originalPopulation: List<RomParser.EnemyEntry>,
                          graphics: SourceEnemyGraphics, originalGraphics: SourceEnemyGraphics): SourceEnemyGraphics {
    val entries = graphics.entries.toMutableList()
    val originalSpecies = originalPopulation.map { it.id }.toSet()
    for (species in population.map { it.id }.distinct()) {
        if (entries.any { it.speciesId == species }) continue
        if (species in originalSpecies && originalGraphics.entries.none { it.speciesId == species }) continue
        val pc = parser.snesToPc(0xA00000 or species)
        require(pc >= 0 && pc + 64 <= parser.getRomData().size && parser.readByteAt(pc + 12) in 0xA0..0xB3) { "Invalid enemy species header." }
        if ((parser.readUInt16At(pc) and 0x7FFF) == 0) continue
        // The engine matches species IDs, not GRAPHADR aliases. Equal pixels alone cannot authorize sharing.
        require(entries.size < 4) { "Adding this actor requires a fifth GFX entry; the engine only supports four." }
        val destination = requireNotNull(selectEnemyGfxVramDestination(entries, emptyList())) { "No safe enemy palette slot." }
        require(entries.none { (it.paletteIndex and 255) == (destination and 255) }) { "No unused enemy palette slot." }
        entries += RomParser.EnemyGfxEntry(species, destination)
    }
    return graphics.copy(entries = entries.toList()).also { validateEnemyGraphicsTransfers(parser, it) }
}

/** $A0:8D64 stages raw transfers into the $7000..97FF buffer. Size bit 15 selects an explicit quadrant. */
fun validateEnemyGraphicsTransfers(parser: RomParser, graphics: SourceEnemyGraphics) {
    var cursor = 0x0800
    for (entry in graphics.entries) {
        val pc = parser.snesToPc(0xA00000 or entry.speciesId)
        require(pc >= 0 && pc + 64 <= parser.getRomData().size) { "GFX species header is truncated." }
        val word = parser.readUInt16At(pc)
        val size = word and 0x7FFF
        val offset = if (word and 0x8000 != 0) (entry.paletteIndex and 0x3000) ushr 3 else cursor
        require(size > 0 && size % 8 == 0 && offset + size <= 0x2800) {
            "${graphics.identity}: GFX transfer size/offset is unsafe for the native staging buffer; zero-sized entries are not valid transfers."
        }
        val pointer = parser.readUInt16At(pc + 54)
        val bank = parser.readByteAt(pc + 56)
        require(bank in 0x80..0xBF && pointer >= 0x8000 && pointer + size <= 0x10000 &&
            parser.snesToPc((bank shl 16) or pointer) + size <= parser.getRomData().size) {
            "${graphics.identity}: GFX source crosses its bank or cannot use the native LoROM tile loader."
        }
        cursor = (cursor + word) and 0xFFFF
    }
}
