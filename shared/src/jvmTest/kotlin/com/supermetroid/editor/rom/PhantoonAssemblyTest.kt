package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Test that we can read Phantoon's body from ROM by:
 * 1. Loading the room tileset (variable tiles contain the body graphics)
 * 2. Parsing the BG2 tilemap at $A7:E0AA
 * 3. Comparing independent tilemap assembly to the production renderer, without image fixtures
 */
class PhantoonAssemblyTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `find Phantoon room tileset index`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        // Phantoon room = $8F:CD13
        val roomPc = parser.snesToPc(0x8FCD13)
        println("Phantoon room at PC 0x${roomPc.toString(16)}")

        // Room header is 11 bytes
        val area = rom[roomPc + 1].toInt() and 0xFF
        println("Area: $area (Wrecked Ship = 3)")
        assertEquals(3, area, "Phantoon should be in Wrecked Ship")

        // After room header, state select begins. Find default state (E5E6)
        // and read 26-byte state data. Tileset index is at offset +3.
        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        println("State data offsets: ${stateOffsets.map { "0x${it.toString(16)}" }}")

        for ((idx, stateOff) in stateOffsets.withIndex()) {
            val tilesetIdx = rom[stateOff + 3].toInt() and 0xFF
            println("State $idx tileset: $tilesetIdx")
        }

        // The default (or first) state tileset
        val defaultTileset = rom[stateOffsets.last() + 3].toInt() and 0xFF
        println("Default tileset index: $defaultTileset")
        assertTrue(defaultTileset in 0 until 29, "Tileset index should be valid")
    }

    @Test
    fun `parse body BG2 tilemap at A7-E0AA`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val tilemapPc = parser.snesToPc(0xA7E0AA)
        println("Body tilemap at PC 0x${tilemapPc.toString(16)}")

        // Header word
        val header = readWord(rom, tilemapPc)
        println("Header: 0x${header.toString(16)} (FFFE = BG2 extended tilemap)")
        assertEquals(0xFFFE, header, "Should be FFFE extended tilemap format")

        var offset = tilemapPc + 2
        var rowIdx = 0
        val entries = mutableListOf<TilemapEntry>()
        val allTileNums = mutableSetOf<Int>()

        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4

            val rowEntries = mutableListOf<Int>()
            for (i in 0 until count) {
                val tileWord = readWord(rom, offset)
                offset += 2
                rowEntries.add(tileWord)

                val tileNum = tileWord and 0x03FF
                val hFlip = (tileWord shr 14) and 1
                val vFlip = (tileWord shr 15) and 1
                val palette = (tileWord shr 10) and 7

                if (tileNum != 0x338) {
                    allTileNums.add(tileNum)
                    entries.add(TilemapEntry(
                        gridX = i,
                        gridY = rowIdx,
                        tileNum = tileNum,
                        hFlip = hFlip != 0,
                        vFlip = vFlip != 0,
                        paletteRow = palette
                    ))
                }
            }

            val tileStr = rowEntries.joinToString(" ") { "0x${it.toString(16).padStart(4, '0')}" }
            println("Row $rowIdx: dest=0x${dest.toString(16)}, count=$count: $tileStr")
            rowIdx++
        }

        println("\nTotal non-empty entries: ${entries.size}")
        println("Unique tile numbers: ${allTileNums.sorted().joinToString(", ") { "0x${it.toString(16)}" }}")
        println("Tile number range: 0x${allTileNums.min().toString(16)} - 0x${allTileNums.max().toString(16)}")
        println("Grid rows: $rowIdx")

        assertTrue(entries.isNotEmpty(), "Should have tilemap entries")
        assertTrue(allTileNums.min() >= 0x30, "Tile numbers should be in variable tile range")
    }

    @Test
    fun `independent ROM tilemap assembly matches the native Phantoon body`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val tileGfx = TileGraphics(parser)

        // Get tileset index from Phantoon room
        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        val tilesetIdx = rom[stateOffsets.last() + 3].toInt() and 0xFF
        println("Loading tileset $tilesetIdx")
        assertTrue(tileGfx.loadTileset(tilesetIdx))

        // Read the active full-health Phantoon palette from $A7:CC21.
        val palPc = parser.snesToPc(PhantoonSpritemap.PALETTE_SNES)
        val snesPalette = IntArray(16)
        snesPalette[0] = 0x00000000
        for (i in 1 until 16) {
            val bgr555 = readWord(rom, palPc + i * 2)
            snesPalette[i] = EnemySpriteGraphics.snesColorToArgb(bgr555)
        }
        println("SNES palette loaded:")
        for (i in snesPalette.indices) {
            println("  [$i] 0x${(snesPalette[i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }

        // Parse body tilemap at $A7:E0AA
        val tilemapPc = parser.snesToPc(0xA7E0AA)
        val header = readWord(rom, tilemapPc)
        assertEquals(0xFFFE, header)

        var offset = tilemapPc + 2
        var rowIdx = 0
        val entries = mutableListOf<TilemapEntry>()
        var maxCol = 0

        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4
            maxCol = maxOf(maxCol, count)

            for (i in 0 until count) {
                val tileWord = readWord(rom, offset)
                offset += 2
                val tileNum = tileWord and 0x03FF
                val hFlip = (tileWord shr 14) and 1
                val vFlip = (tileWord shr 15) and 1
                val palette = (tileWord shr 10) and 7
                entries.add(TilemapEntry(i, rowIdx, tileNum, hFlip != 0, vFlip != 0, palette))
            }
            rowIdx++
        }

        println("Tilemap: ${maxCol}x$rowIdx tiles = ${maxCol * 8}x${rowIdx * 8} pixels")

        // Render assembled image
        val imgW = maxCol * 8
        val imgH = rowIdx * 8
        val assembled = IntArray(imgW * imgH)

        for (entry in entries) {
            if (entry.tileNum == 0x338) continue

            val tileIndices = tileGfx.readTileIndices(entry.tileNum)
            if (tileIndices == null) {
                println("WARN: tile 0x${entry.tileNum.toString(16)} not found in tileset")
                continue
            }

            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (entry.hFlip) 7 - px else px
                    val sy = if (entry.vFlip) 7 - py else py
                    val ci = tileIndices[sy * 8 + sx]
                    val argb = if (ci == 0) 0x00000000 else snesPalette[ci]
                    val dx = entry.gridX * 8 + px
                    val dy = entry.gridY * 8 + py
                    if (dx < imgW && dy < imgH) {
                        assembled[dy * imgW + dx] = argb
                    }
                }
            }
        }

        // Count non-transparent pixels
        val nonTransparent = assembled.count { ((it ushr 24) and 0xFF) > 128 }
        println("Assembled image: ${imgW}x${imgH}, $nonTransparent non-transparent pixels")
        assertTrue(nonTransparent > 1000, "Should have substantial visible content")

        // Save assembled image for visual verification
        val outDir = File("build/test-output")
        outDir.mkdirs()
        val img = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, imgW, imgH, assembled, 0, imgW)
        ImageIO.write(img, "png", File(outDir, "phantoon_body_assembled.png"))
        println("Saved assembled image to build/test-output/phantoon_body_assembled.png")

        // Independent tilemap assembly must agree pixel-for-pixel with the production renderer.
        val renderer = PhantoonSpritemap(parser)
        assertTrue(renderer.load())
        val native = requireNotNull(renderer.renderComponent(PhantoonSpritemap.BODY_TILEMAPS.single()))
        assertEquals(imgW, native.width)
        assertEquals(imgH, native.height)
        assertArrayEquals(assembled, native.pixels)
    }

    data class TilemapEntry(
        val gridX: Int,
        val gridY: Int,
        val tileNum: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val paletteRow: Int
    )

    private fun readWord(rom: ByteArray, offset: Int): Int =
        (rom[offset].toInt() and 0xFF) or ((rom[offset + 1].toInt() and 0xFF) shl 8)
}
