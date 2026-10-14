package com.supermetroid.editor.rom

import com.supermetroid.editor.data.TilesetGfxData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Whitelisted native graphics owners. Compressed owners never grow into the next ROM asset. */
@OptIn(ExperimentalEncodingApi::class)
object NativeSpriteAssets {
    data class Source(val name: String, val address: Int, val bytes: Int, val compressed: Boolean, val mode7: Boolean) {
        val key: String get() = address.toString(16).uppercase().padStart(6, '0')
        fun read(parser: RomParser): ByteArray = if (compressed) parser.decompressLZ2(address) else RomVisualAssets.raw(parser, address, bytes)
    }

    val SOURCES = RomVisualAssets.ASSETS.distinctBy { it.graphics }.map { asset ->
        Source(asset.name, asset.graphics, asset.bytes, asset.compressed,
            asset.format in listOf(RomVisualAssets.Format.MODE7_TILES, RomVisualAssets.Format.MODE7_MAP, RomVisualAssets.Format.TITLE_SCENE))
    } + listOf(Source("Samus' Ship shared OBJ tiles", 0xADB600, 0x1000, false, false),
        Source("Golden Four statue OBJ tiles", TourianStatueSprites.TILES, TourianStatueSprites.TILE_BYTES, false, false))

    fun shipPalette(parser: RomParser): Int {
        val header = parser.snesToPc(0xA0D07F)
        return (parser.readByteAt(header + 0x0C) shl 16) or parser.readUInt16At(header + 2)
    }

    fun source(key: String): Source = requireNotNull(SOURCES.firstOrNull { it.key == key }) { "Unknown native sprite source '$key'" }
    fun key(address: Int): String = address.toString(16).uppercase().padStart(6, '0')

    fun paletteRows(parser: RomParser): Set<Int> = buildSet {
        for (asset in RomVisualAssets.ASSETS) for (row in 0 until asset.paletteColors / 16) add(asset.palette + row * 32)
        add(shipPalette(parser))
        TourianStatueSprites.PARTS.forEach { add(it.palette) }
        for (mask in 0 until 12) add(0x900000 or parser.readUInt16At(parser.snesToPc(0x90C3C9 + mask * 2)))
        for (i in 0 until 40) {
            val pointer = parser.readUInt16At(parser.snesToPc(0x9BB7D3 + i * 2))
            if (pointer >= 0x8000) add(0x9B0000 or pointer)
        }
    }

    fun encodedSource(parser: RomParser, source: Source, raw: ByteArray): ByteArray {
        val original = source.read(parser)
        require(raw.size == original.size) { "${source.name}: expected ${original.size} bytes, got ${raw.size}" }
        if (!source.compressed) return raw
        val (_, budget) = parser.decompressLZ2WithSize(source.address)
        if (raw.contentEquals(original)) return RomVisualAssets.raw(parser, source.address, budget)
        val encoded = LZ5Compressor.compress(raw)
        require(LZ5Compressor.decompress(encoded).contentEquals(raw)) { "${source.name}: compression round trip failed" }
        require(encoded.size <= budget) {
            "${source.name}: compressed edit needs ${encoded.size} bytes; only $budget are available. The edit was not applied; asset relocation is required."
        }
        return encoded
    }

    /** Validate everything before producing any writes; normal write-plan overlap checks still apply. */
    fun planEdits(parser: RomParser, gfx: TilesetGfxData): List<RomWriteIntent> = buildList {
        for ((key, encoded) in gfx.visualTileBlocks) {
            val source = source(key)
            val bytes = encodedSource(parser, source, Base64.decode(encoded))
            val pc = parser.snesToPc(source.address)
            add(RomWriteIntent("graphics:native-$key", source.name, pc, bytes.map { it.toInt() and 255 }, kind = RomWriteKind.GRAPHICS))
        }
        val rows = paletteRows(parser)
        for ((key, encoded) in gfx.visualPalettes) {
            val address = key.toIntOrNull(16)
            require(address != null && NativeSpriteAssets.key(address) == key && address in rows) { "Unknown native palette row '$key'" }
            val bytes = Base64.decode(encoded)
            require(bytes.size == 32) { "Native palette $key requires exactly 32 bytes" }
            RomVisualAssets.raw(parser, address, 32) // Bounds validation before staging.
            add(RomWriteIntent("graphics:native-palette-$key", "Native palette $key", parser.snesToPc(address), bytes.map { it.toInt() and 255 }, kind = RomWriteKind.GRAPHICS))
        }
    }
}
