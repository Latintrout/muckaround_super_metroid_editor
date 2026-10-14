package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.*
import java.util.Base64

/** Source edits are portable project data; a preview parser never mutates the loaded ROM. */
class NativeSpriteEditorState(private val customGfx: () -> TilesetGfxData, private val onDirty: () -> Unit) {
    data class Sheet(
        val source: NativeSpriteAssets.Source, val raw: ByteArray, val palette: IntArray,
        val indices: IntArray, val pixels: IntArray, val width: Int, val height: Int,
    )

    fun previewParser(parser: RomParser): RomParser {
        val gfx = customGfx()
        val plan = RomWritePlan(parser.getRomData())
        NativeSpriteAssets.planEdits(parser, gfx).forEach(plan::add)
        // Reuse existing palette owners, including shared Samus and boss palette ranges.
        for ((id, b64) in gfx.spritePalettes) {
            if (id.startsWith("enemy_pal:")) {
                val speciesId = requireNotNull(id.removePrefix("enemy_pal:").toIntOrNull(16))
                val bytes = Base64.getDecoder().decode(b64)
                require(bytes.size == 32)
                val header = parser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
                require(header >= 0 && header + 0x0D <= parser.getRomData().size)
                val address = (parser.readByteAt(header + 0x0C) shl 16) or parser.readUInt16At(header + 2)
                plan.add(RomWriteIntent("graphics:$id", "Enemy palette $id", parser.snesToPc(address), bytes.map { it.toInt() and 255 }))
                continue
            }
            val region = SpritePalettes.findRegion(id) ?: continue
            val bytes = Base64.getDecoder().decode(b64)
            require(bytes.size == region.byteSize)
            plan.add(RomWriteIntent("graphics:sprite-palette-$id", region.name, region.offset, bytes.map { it.toInt() and 255 }))
        }
        for ((key, encoded) in gfx.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesId = requireNotNull(key.removePrefix("enemy:").toIntOrNull(16))
            val raw = Base64.getDecoder().decode(encoded)
            val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, raw)
            require(validation.isExportable) { validation.errors.joinToString() }
            plan.add(RomWriteIntent("graphics:$key", key, requireNotNull(validation.pcAddress), raw.map { it.toInt() and 255 }))
        }
        return RomParser(plan.romData)
    }

    fun loadSheet(parser: RomParser, sourceKey: String, paletteAddress: Int): Sheet {
        val source = NativeSpriteAssets.source(sourceKey)
        require(paletteAddress in NativeSpriteAssets.paletteRows(parser))
        val effective = previewParser(parser)
        val raw = source.read(effective)
        val colors = RomVisualAssets.palette(effective, paletteAddress, if (source.mode7) 256 else 16, transparentRows = !source.mode7)
        colors[0] = 0
        val tileBytes = if (source.mode7) 64 else 32
        require(raw.size % tileBytes == 0)
        val width = 128
        val height = ((raw.size / tileBytes + 15) / 16) * 8
        val indices = IntArray(width * height) { -1 }
        val pixels = IntArray(indices.size)
        val decoder = TileDecoder()
        for (tile in 0 until raw.size / tileBytes) {
            val decoded = if (source.mode7) IntArray(64) { raw[tile * 64 + it].toInt() and 255 }
                else decoder.decode4bppTileIndices(raw, tile * 32)
            for (y in 0..7) for (x in 0..7) {
                val i = (tile / 16 * 8 + y) * width + tile % 16 * 8 + x
                indices[i] = decoded[y * 8 + x]
                pixels[i] = colors[indices[i]]
            }
        }
        return Sheet(source, raw, colors, indices, pixels, width, height)
    }

    /** Preserve original indexes when equal palette colors would make an ARGB round trip ambiguous. */
    fun encodePixels(sheet: Sheet, pixels: IntArray): ByteArray {
        require(pixels.size == sheet.pixels.size) { "Source sheet dimensions cannot change" }
        val raw = sheet.raw.copyOf()
        for (i in pixels.indices) {
            if (pixels[i] == sheet.pixels[i]) continue
            require(sheet.indices[i] >= 0) { "Pixels outside the source tile data cannot be painted" }
            val color = if (pixels[i] ushr 24 == 0) 0 else pixels[i]
            val index = sheet.palette.indexOf(color)
            require(index >= 0) { "Source pixels must use the selected palette" }
            val x = i % sheet.width
            val y = i / sheet.width
            val tile = (y / 8) * 16 + x / 8
            if (sheet.source.mode7) raw[tile * 64 + (y % 8) * 8 + x % 8] = index.toByte()
            else for (plane in 0..3) {
                val offset = tile * 32 + (plane / 2) * 16 + (y % 8) * 2 + plane % 2
                val bit = 1 shl (7 - x % 8)
                val old = raw[offset].toInt() and 255
                raw[offset] = (if (index and (1 shl plane) != 0) old or bit else old and bit.inv()).toByte()
            }
        }
        return raw
    }

    fun applyPixels(parser: RomParser, sheet: Sheet, pixels: IntArray) {
        val raw = encodePixels(sheet, pixels)
        NativeSpriteAssets.encodedSource(parser, sheet.source, raw) // Reject overflow before changing project state.
        val ship = sheet.source.key == "ADB600"
        if (ship) require(EnemySpriteGraphics.readGraphicsBlock(parser, 0xD07F)?.snesAddress == sheet.source.address) { "Ship graphics owner was relocated" }
        val edits = if (ship) customGfx().spriteTileBlocks else customGfx().visualTileBlocks
        val key = if (ship) "enemy:D07F" else sheet.source.key
        val old = edits[key]
        if (raw.contentEquals(sheet.source.read(parser))) edits.remove(key)
        else edits[key] = Base64.getEncoder().encodeToString(raw)
        if (old != edits[key]) onDirty()
    }

    fun hasTiles(sourceKey: String): Boolean = if (sourceKey == "ADB600") customGfx().spriteTileBlocks.containsKey("enemy:D07F") else customGfx().visualTileBlocks.containsKey(sourceKey)
    fun resetTiles(sourceKey: String) {
        val removed = if (sourceKey == "ADB600") customGfx().spriteTileBlocks.remove("enemy:D07F") else customGfx().visualTileBlocks.remove(sourceKey)
        if (removed != null) onDirty()
    }

    fun previewWithPixels(parser: RomParser, sheet: Sheet, pixels: IntArray): RomParser {
        val effective = previewParser(parser)
        val bytes = NativeSpriteAssets.encodedSource(parser, sheet.source, encodePixels(sheet, pixels))
        bytes.copyInto(effective.getRomData(), parser.snesToPc(sheet.source.address))
        return RomParser(effective.getRomData())
    }

    fun palette(parser: RomParser, address: Int): IntArray = RomVisualAssets.palette(previewParser(parser), address, 16)

    fun savePalette(parser: RomParser, address: Int, colors: IntArray) {
        require(address in NativeSpriteAssets.paletteRows(parser) && colors.size == 16)
        val original = RomVisualAssets.raw(parser, address, 32)
        val bytes = original.copyOf()
        for (i in 1..15) {
            val bgr = EnemySpriteGraphics.argbToSnesColor(colors[i])
            bytes[i * 2] = bgr.toByte()
            bytes[i * 2 + 1] = (bgr shr 8).toByte()
        }
        writePaletteRow(parser, address, bytes)
    }

    private fun paletteRegion(parser: RomParser, address: Int) = SpritePalettes.REGIONS.firstOrNull {
        val pc = parser.snesToPc(address)
        pc >= it.offset && pc + 32 <= it.offset + it.byteSize
    }

    private fun writePaletteRow(parser: RomParser, address: Int, bytes: ByteArray) {
        val gfx = customGfx()
        if (address == NativeSpriteAssets.shipPalette(parser)) {
            val key = "enemy_pal:D07F"
            val old = gfx.spritePalettes[key]
            if (bytes.contentEquals(RomVisualAssets.raw(parser, address, 32))) gfx.spritePalettes.remove(key)
            else gfx.spritePalettes[key] = Base64.getEncoder().encodeToString(bytes)
            if (old != gfx.spritePalettes[key]) onDirty()
            return
        }
        val region = paletteRegion(parser, address)
        if (region != null) {
            val original = parser.getRomData().copyOfRange(region.offset, region.offset + region.byteSize)
            val current = gfx.spritePalettes[region.id]?.let { Base64.getDecoder().decode(it) } ?: original.copyOf()
            bytes.copyInto(current, parser.snesToPc(address) - region.offset)
            val old = gfx.spritePalettes[region.id]
            if (current.contentEquals(original)) gfx.spritePalettes.remove(region.id)
            else gfx.spritePalettes[region.id] = Base64.getEncoder().encodeToString(current)
            if (old != gfx.spritePalettes[region.id]) onDirty()
        } else {
            val key = NativeSpriteAssets.key(address)
            val old = gfx.visualPalettes[key]
            if (bytes.contentEquals(RomVisualAssets.raw(parser, address, 32))) gfx.visualPalettes.remove(key)
            else gfx.visualPalettes[key] = Base64.getEncoder().encodeToString(bytes)
            if (old != gfx.visualPalettes[key]) onDirty()
        }
    }

    fun resetPalette(parser: RomParser, address: Int) = writePaletteRow(parser, address, RomVisualAssets.raw(parser, address, 32))
    fun hasPalette(parser: RomParser, address: Int): Boolean =
        if (address == NativeSpriteAssets.shipPalette(parser)) customGfx().spritePalettes.containsKey("enemy_pal:D07F")
        else paletteRegion(parser, address)?.let { customGfx().spritePalettes.containsKey(it.id) }
            ?: customGfx().visualPalettes.containsKey(NativeSpriteAssets.key(address))
}
