package com.supermetroid.editor.cli

import com.supermetroid.editor.data.ProjectNewRoom
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.procgen.BiomeGenerationOptions
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.ProjectNewRoomMaterializer
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * World generator support (blueprint phase 1): brand-new rooms.
 *
 * gen-room --base FILE --styles CAVERN,RUINS --from 0 --count 20 -o out.json
 *   Like gen-insides, but the starting layout comes from a JSON file instead
 *   of a ROM room ({tileset, width, height, words, bts}; width/height in
 *   blocks), so the room can have a shape vanilla doesn't have.
 *
 * add-room --spec ROOM.json -o OUT.sfc [--result FILE]
 *   Writes one project room (a serialized ProjectNewRoom: header, initial
 *   state, doors with "rom:XXXX" destinations) into a copy of the ROM with
 *   ProjectNewRoomMaterializer, the same writer the Maker's "New room" uses.
 *   Prints {roomId, doorDefPtrs, allocations}. Vanilla doors are not
 *   re-pointed here; the caller does that with the returned pointers.
 */
@Serializable
data class RoomBase(val tileset: Int, val width: Int, val height: Int, val words: List<Int>, val bts: List<Int>)

@Serializable
data class AddedRoomAllocation(val snesAddress: Int, val size: Int, val label: String)

@Serializable
data class AddedRoom(val roomId: Int, val doorDefPtrs: List<Int>, val allocations: List<AddedRoomAllocation>)

private val newRoomJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun cmdGenRoom(parser: RomParser, args: List<String>) {
    var basePath: String? = null
    var styles = listOf(BiomeStyle.CAVERN)
    var from = 0L
    var count = 1
    var outPath: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--base" && iter.hasNext() -> basePath = iter.next()
            arg == "--styles" && iter.hasNext() -> styles = iter.next().split(",").map { BiomeStyle.valueOf(it.trim().uppercase()) }
            arg == "--from" && iter.hasNext() -> from = iter.next().toLong()
            arg == "--count" && iter.hasNext() -> count = iter.next().toInt()
            (arg == "-o" || arg == "--output") && iter.hasNext() -> outPath = iter.next()
            else -> throw IllegalArgumentException("Unknown gen-room option: $arg")
        }
    }
    val base = newRoomJson.decodeFromString<RoomBase>(
        File(basePath ?: throw IllegalArgumentException("Usage: gen-room --base FILE [--styles A,B] [--from S] [--count N] -o FILE")).readText()
    )
    val w = base.width
    val h = base.height
    require(base.words.size == w * h && base.bts.size == w * h) { "base layout is not ${w}x$h blocks" }
    val headers = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }
        .filter { it.levelDataPtr != 0 && it.width > 0 && it.height > 0 }
    val profile = TilesetProfileCache.getOrLearn(parser, headers, base.tileset)
    val words = base.words.toIntArray()
    val bts = base.bts.toIntArray()
    // Same door-shell protection as gen-insides.
    val keep = (0 until w * h).filter { (words[it] shr 12) and 0xF == 0x9 }
        .map { BiomeGenerationRect(it % w - 1, it / w, it % w + 1, it / w) }
    val options = BiomeGenerationOptions(preserveRects = keep)
    val tries = ArrayList<InsideTry>()
    for (seed in from until from + count) {
        for (style in styles) {
            val rules = BiomeRules.roll(style, seed).copy(mutators = emptySet(), destructibleDensity = 0.0)
            val level = BiomeGenerator(rules, profile, seed, options).generate(w, h, words, bts)
            tries.add(InsideTry(0, style.name, seed, w, h, level.words.toList(), level.bts.toList(), level.preserved.toList()))
        }
    }
    val text = newRoomJson.encodeToString(InsideTries(0, base.tileset, w, h, tries))
    if (outPath != null) File(outPath).writeText(text) else println(text)
    System.err.println("gen-room ${w}x$h tileset ${base.tileset}: ${tries.size} tries")
}

internal fun cmdAddRoom(romPath: String, args: List<String>) {
    var specPath: String? = null
    var outPath: String? = null
    var resultPath: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--spec" && iter.hasNext() -> specPath = iter.next()
            (arg == "-o" || arg == "--output") && iter.hasNext() -> outPath = iter.next()
            arg == "--result" && iter.hasNext() -> resultPath = iter.next()
            else -> throw IllegalArgumentException("Unknown add-room option: $arg")
        }
    }
    if (specPath == null || outPath == null) throw IllegalArgumentException("Usage: add-room --spec ROOM.json -o OUT.sfc [--result FILE]")
    val room = newRoomJson.decodeFromString<ProjectNewRoom>(File(specPath).readText())
    val rom = File(romPath).readBytes()
    val project = SmEditProject(romPath, newRooms = mutableListOf(room))
    val result = ProjectNewRoomMaterializer(project, RomParser(rom), rom, onLog = { System.err.println(it) }).materialize()
    File(outPath).writeBytes(rom)
    val added = AddedRoom(
        roomId = result.roomIdsByProjectId.getValue(room.id),
        doorDefPtrs = result.doorDefPtrsByProjectId[room.id].orEmpty(),
        allocations = result.allocations.map { AddedRoomAllocation(it.snesAddress, it.size, it.label) },
    )
    val text = newRoomJson.encodeToString(added)
    if (resultPath != null) File(resultPath).writeText(text) else println(text)
}
