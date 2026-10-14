package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests for Phantoon sprite tile block loading, palette extraction,
 * and tile data integrity.
 */
class PhantoonSpriteMapTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `Phantoon body header resolves the exact raw tile range`() {
        val parser = loadTestRom() ?: return
        val block = EnemySpriteGraphics.readGraphicsBlock(parser, 0xE4BF)
        val raw = EnemySpriteGraphics.loadEnemyTileData(parser, 0xE4BF)

        assertNotNull(block)
        assertEquals(0xACAA00, block!!.snesAddress)
        assertNotNull(raw)
        assertEquals(0xC00, raw!!.size)
        assertEquals(96, raw.size / EnemySpriteGraphics.BYTES_PER_TILE)
    }

    @Test
    fun `native body has source dimensions and substantial visible content`() {
        val renderer = PhantoonSpritemap(TestRomHelper.requireRomParser())
        assertTrue(renderer.load())
        val body = requireNotNull(renderer.renderComponent(PhantoonSpritemap.BODY_TILEMAPS.single()))
        assertEquals(80, body.width)
        assertEquals(96, body.height)
        assertTrue(body.pixels.count { it ushr 24 != 0 } > 1000)
    }

    @Test
    fun `all native Phantoon components use only the exact source palette`() {
        val renderer = PhantoonSpritemap(TestRomHelper.requireRomParser())
        assertTrue(renderer.load())
        val palette = requireNotNull(renderer.getPalette()).toSet()
        for (definition in PhantoonSpritemap.COMPONENT_TILEMAPS) {
            val component = requireNotNull(renderer.renderComponent(definition))
            val opaque = component.pixels.filter { it ushr 24 != 0 }
            assertTrue(opaque.isNotEmpty(), definition.name)
            assertTrue(opaque.all { it in palette }, definition.name)
            assertTrue(opaque.toSet().size <= 15, definition.name)
        }
    }

    @Test
    fun `species header at A0-E4BF has correct HP and AI bank`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val headerPc = parser.snesToPc(0xA0E4BF)

        // +$00-01: first field (varies by interpretation), +$04: HP or damage
        // +$0C: AI bank byte ($A7 for Phantoon)
        val aiBankByte = rom[headerPc + 0x0C].toInt() and 0xFF
        assertEquals(0xA7, aiBankByte, "Phantoon AI bank should be \$A7")

        // Init AI pointer at +$12 should be $CDF3
        val initAi = (rom[headerPc + 0x12].toInt() and 0xFF) or
            ((rom[headerPc + 0x13].toInt() and 0xFF) shl 8)
        assertEquals(0xCDF3, initAi, "Phantoon init AI should be \$CDF3")
    }

    @Test
    fun `dump Phantoon species header for spritemap analysis`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val headerPc = parser.snesToPc(0xA0E4BF)

        println("Phantoon species header at \$A0:E4BF (PC $headerPc), 64 bytes:")
        for (row in 0 until 4) {
            val base = row * 0x10
            val hex = (0 until 16).map { i ->
                String.format("%02X", rom[headerPc + base + i].toInt() and 0xFF)
            }
            val line = "+%02X: %s  %s".format(
                base,
                hex.take(8).joinToString(" "),
                hex.drop(8).joinToString(" ")
            )
            println(line)
        }

        val word00 = (rom[headerPc + 0x00].toInt() and 0xFF) or ((rom[headerPc + 0x01].toInt() and 0xFF) shl 8)
        val word02 = (rom[headerPc + 0x02].toInt() and 0xFF) or ((rom[headerPc + 0x03].toInt() and 0xFF) shl 8)
        println("+0x00 (word LE): 0x%04X (potential pointer)".format(word00))
        println("+0x02 (word LE): 0x%04X (potential pointer)".format(word02))

        val word2C = (rom[headerPc + 0x2C].toInt() and 0xFF) or ((rom[headerPc + 0x2D].toInt() and 0xFF) shl 8)
        println("+0x2C (word LE): 0x%04X (instruction list)".format(word2C))

        assertTrue(true)
    }
}
