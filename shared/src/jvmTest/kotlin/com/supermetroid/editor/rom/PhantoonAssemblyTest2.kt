package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Deep investigation of Phantoon assembly: compare tileset palette vs SNES palette,
 * render body with both, and verify exact agreement with the production renderer.
 */
class PhantoonAssemblyTest2 {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readWord(rom: ByteArray, offset: Int): Int =
        (rom[offset].toInt() and 0xFF) or ((rom[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `compare tileset palette row 7 with active Phantoon palette`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val tileGfx = TileGraphics(parser)

        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        val tilesetIdx = rom[stateOffsets.last() + 3].toInt() and 0xFF
        tileGfx.loadTileset(tilesetIdx)

        val palettes = tileGfx.getPalettes()!!
        println("Tileset $tilesetIdx palette row 7:")
        for (i in palettes[7].indices) {
            println("  [$i] 0x${(palettes[7][i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }

        val palPc = parser.snesToPc(PhantoonSpritemap.PALETTE_SNES)
        println("\nActive SNES palette at \$A7:CC21:")
        for (i in 0 until 16) {
            val bgr555 = readWord(rom, palPc + i * 2)
            val argb = EnemySpriteGraphics.snesColorToArgb(bgr555)
            println("  [$i] 0x${(argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }

        // Also check what the tileset palette row 6 looks like (in case the palette row in tilemap is wrong)
        println("\nTileset palette row 6:")
        for (i in palettes[6].indices) {
            println("  [$i] 0x${(palettes[6][i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }
    }

    @Test
    fun `render body with tileset palette row 7 and save`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val tileGfx = TileGraphics(parser)

        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        val tilesetIdx = rom[stateOffsets.last() + 3].toInt() and 0xFF
        tileGfx.loadTileset(tilesetIdx)

        val palettes = tileGfx.getPalettes()!!
        val pal7 = palettes[7]

        // Parse body tilemap
        val tilemapPc = parser.snesToPc(0xA7E0AA)
        val entries = parseTilemap(rom, tilemapPc)

        // Render with tileset palette
        val imgW = 80
        val imgH = 96
        val assembled = IntArray(imgW * imgH)

        for (entry in entries) {
            if (entry.tileNum == 0x338) continue
            val indices = tileGfx.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) for (px in 0 until 8) {
                val sx = if (entry.hFlip) 7 - px else px
                val sy = if (entry.vFlip) 7 - py else py
                val ci = indices[sy * 8 + sx]
                val argb = if (ci == 0) 0x00000000 else pal7[ci]
                val dx = entry.gridX * 8 + px
                val dy = entry.gridY * 8 + py
                if (dx < imgW && dy < imgH) assembled[dy * imgW + dx] = argb
            }
        }

        val outDir = File("build/test-output")
        outDir.mkdirs()
        val img = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, imgW, imgH, assembled, 0, imgW)
        ImageIO.write(img, "png", File(outDir, "phantoon_body_tilesetpal.png"))

        // Also render with the active $CC21 health palette for comparison.
        val palPc = parser.snesToPc(PhantoonSpritemap.PALETTE_SNES)
        val cc21 = IntArray(16)
        cc21[0] = 0x00000000
        for (i in 1 until 16) {
            val bgr555 = readWord(rom, palPc + i * 2)
            cc21[i] = EnemySpriteGraphics.snesColorToArgb(bgr555)
        }

        val assembled2 = IntArray(imgW * imgH)
        for (entry in entries) {
            if (entry.tileNum == 0x338) continue
            val indices = tileGfx.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) for (px in 0 until 8) {
                val sx = if (entry.hFlip) 7 - px else px
                val sy = if (entry.vFlip) 7 - py else py
                val ci = indices[sy * 8 + sx]
                val argb = if (ci == 0) 0x00000000 else cc21[ci]
                val dx = entry.gridX * 8 + px
                val dy = entry.gridY * 8 + py
                if (dx < imgW && dy < imgH) assembled2[dy * imgW + dx] = argb
            }
        }

        val img2 = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        img2.setRGB(0, 0, imgW, imgH, assembled2, 0, imgW)
        ImageIO.write(img2, "png", File(outDir, "phantoon_body_cc21pal.png"))

        // Show tile usage stats
        val usedTiles = entries.filter { it.tileNum != 0x338 }.map { it.tileNum }.toSet()
        println("Used tiles: ${usedTiles.sorted().joinToString(", ") { "0x${it.toString(16)}" }}")

        // Check a specific tile's raw indices to verify data is present
        for (t in listOf(0x132, 0x140, 0x150, 0x160, 0x170, 0x178)) {
            val indices = tileGfx.readTileIndices(t)
            if (indices == null) {
                println("Tile 0x${t.toString(16)}: NULL")
                continue
            }
            val nonZero = indices.count { it != 0 }
            val uniqueIndices = indices.filter { it != 0 }.toSet()
            println("Tile 0x${t.toString(16)}: $nonZero/64 non-zero pixels, unique indices: $uniqueIndices")
        }

        // Independent tilemap assembly must agree pixel-for-pixel with the production renderer.
        val renderer = PhantoonSpritemap(parser)
        assertTrue(renderer.load())
        val native = requireNotNull(renderer.renderComponent(PhantoonSpritemap.BODY_TILEMAPS.single()))
        assertEquals(imgW, native.width)
        assertEquals(imgH, native.height)
        assertArrayEquals(assembled2, native.pixels)
    }

    data class TilemapEntry(
        val gridX: Int, val gridY: Int, val tileNum: Int,
        val hFlip: Boolean, val vFlip: Boolean, val paletteRow: Int
    )

    private fun parseTilemap(rom: ByteArray, tilemapPc: Int): List<TilemapEntry> {
        val entries = mutableListOf<TilemapEntry>()
        var offset = tilemapPc + 2 // skip FFFE header
        var rowIdx = 0
        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4
            for (i in 0 until count) {
                val tw = readWord(rom, offset)
                offset += 2
                entries.add(TilemapEntry(
                    i, rowIdx, tw and 0x03FF,
                    (tw shr 14) and 1 != 0,
                    (tw shr 15) and 1 != 0,
                    (tw shr 10) and 7
                ))
            }
            rowIdx++
        }
        return entries
    }
}
