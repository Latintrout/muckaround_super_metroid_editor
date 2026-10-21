package com.supermetroid.editor.cli

import com.supermetroid.editor.procgen.BiomeGenerationOptions
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * World generator support (rung 2b): new layer-1 insides for one room.
 *
 * gen-insides ROOM_ID --styles CAVERN,RUINS --from 0 --count 200 -o out.json
 *   For each sub-seed and style, run the biome generator with the room's own
 *   tileset, door frames kept, no mutators and no destructibles. Output: the
 *   room's block words + BTS per try (row-major, width*height blocks).
 *
 * render-insides --in tries.json -o DIR
 *   Render the original room and the chosen tries (PNG, no enemies on the
 *   new insides).
 */
@Serializable
data class InsideTry(
    val roomId: Int,
    val style: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val words: List<Int>,
    val bts: List<Int>,
)

@Serializable
data class InsideTries(val roomId: Int, val tileset: Int, val width: Int, val height: Int, val tries: List<InsideTry>)

private val insidesJson = Json { ignoreUnknownKeys = true }

private fun parseRoomId(text: String): Int =
    text.removePrefix("0x").removePrefix("0X").toInt(16)

private fun originalLayer1(parser: RomParser, roomId: Int): Triple<ByteArray, IntArray, IntArray> {
    val room = parser.readRoomHeader(roomId) ?: throw IllegalArgumentException("Room not found: $roomId")
    val data = parser.decompressLZ2(room.levelDataPtr)
    val n = room.width * 16 * room.height * 16
    val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    val words = IntArray(n) { (data[2 + 2 * it].toInt() and 0xFF) or ((data[3 + 2 * it].toInt() and 0xFF) shl 8) }
    val bts = IntArray(n) { data[2 + layer1Size + it].toInt() and 0xFF }
    return Triple(data, words, bts)
}

internal fun cmdGenInsides(parser: RomParser, args: List<String>) {
    var roomText: String? = null
    var styles = listOf(BiomeStyle.CAVERN)
    var from = 0L
    var count = 1
    var outPath: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--styles" && iter.hasNext() -> styles = iter.next().split(",").map { BiomeStyle.valueOf(it.trim().uppercase()) }
            arg == "--from" && iter.hasNext() -> from = iter.next().toLong()
            arg == "--count" && iter.hasNext() -> count = iter.next().toInt()
            (arg == "-o" || arg == "--output") && iter.hasNext() -> outPath = iter.next()
            roomText == null -> roomText = arg
            else -> throw IllegalArgumentException("Unknown gen-insides option: $arg")
        }
    }
    val roomId = parseRoomId(roomText ?: throw IllegalArgumentException("Usage: gen-insides ROOM_ID [--styles A,B] [--from S] [--count N] -o FILE"))
    val room = parser.readRoomHeader(roomId) ?: throw IllegalArgumentException("Room not found: $roomText")
    val headers = parser.roomCatalog.rooms.mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }
        .filter { it.levelDataPtr != 0 && it.width > 0 && it.height > 0 }
    val profile = TilesetProfileCache.getOrLearn(parser, headers, room.tileset)
    val (_, words, bts) = originalLayer1(parser, roomId)
    val w = room.width * 16
    val h = room.height * 16
    // Keep the blocks on both sides of every door block (blue door shells):
    // DoorPreservation treats a left/right door column as a "vertical" group
    // and skips the cells beside it in the door's own rows.
    val keep = (0 until w * h).filter { (words[it] shr 12) and 0xF == 0x9 }
        .map { BiomeGenerationRect(it % w - 1, it / w, it % w + 1, it / w) }
    val options = BiomeGenerationOptions(preserveRects = keep)
    val tries = ArrayList<InsideTry>()
    for (seed in from until from + count) {
        for (style in styles) {
            val rules = BiomeRules.roll(style, seed).copy(
                mutators = emptySet(),
                destructibleDensity = 0.0,
            )
            val level = BiomeGenerator(rules, profile, seed, options).generate(w, h, words, bts)
            tries.add(InsideTry(roomId, style.name, seed, w, h, level.words.toList(), level.bts.toList()))
        }
    }
    val out = InsideTries(roomId, room.tileset, w, h, tries)
    val text = insidesJson.encodeToString(out)
    if (outPath != null) File(outPath).writeText(text) else println(text)
    System.err.println("gen-insides 0x${roomId.toString(16)}: ${tries.size} tries")
}

internal fun cmdRenderInsides(parser: RomParser, args: List<String>) {
    var inPath: String? = null
    var outDir: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--in" && iter.hasNext() -> inPath = iter.next()
            (arg == "-o" || arg == "--output") && iter.hasNext() -> outDir = iter.next()
            else -> throw IllegalArgumentException("Unknown render-insides option: $arg")
        }
    }
    if (inPath == null || outDir == null) throw IllegalArgumentException("Usage: render-insides --in FILE -o DIR")
    val dir = File(outDir)
    dir.mkdirs()
    val tries = insidesJson.decodeFromString<List<InsideTry>>(File(inPath).readText())
    val renderer = MapRenderer(parser)
    fun save(pixels: com.supermetroid.editor.rom.RoomRenderData?, name: String) {
        if (pixels == null) return
        val img = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, pixels.width, pixels.height, pixels.pixels, 0, pixels.width)
        ImageIO.write(img, "PNG", File(dir, name))
    }
    for (t in tries) {
        val room = parser.readRoomHeader(t.roomId) ?: continue
        val (data, _, _) = originalLayer1(parser, t.roomId)
        val id = t.roomId.toString(16)
        if (!File(dir, "$id-original.png").exists()) save(renderer.renderRoom(room), "$id-original.png")
        val copy = data.copyOf()
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        for (i in t.words.indices) {
            copy[2 + 2 * i] = (t.words[i] and 0xFF).toByte()
            copy[3 + 2 * i] = ((t.words[i] shr 8) and 0xFF).toByte()
            copy[2 + layer1Size + i] = (t.bts[i] and 0xFF).toByte()
        }
        save(renderer.renderRoomFromLevelData(room, copy, enemyOverrides = emptyList()), "$id-${t.style.lowercase()}-${t.seed}.png")
    }
    System.err.println("render-insides: ${tries.size} rooms -> ${dir.absolutePath}")
}
