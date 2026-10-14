package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class RomVisualAssetsTest {
    @Test
    fun `every cinematic and effect recipe produces visible pixels from the vanilla ROM`() {
        val parser = TestRomHelper.requireRomParser()
        for (asset in RomVisualAssets.ASSETS) {
            val frame = assertNotNull(RomVisualAssets.render(parser, asset), asset.name)
            assertEquals(frame.width * frame.height, frame.pixels.size, asset.name)
            assertTrue(frame.pixels.any { it ushr 24 != 0 }, asset.name)
        }
    }

    @Test
    fun `Mode7 keeps palette entries divisible by sixteen opaque`() {
        val parser = TestRomHelper.requireRomParser()
        val colors = RomVisualAssets.palette(parser, 0x8CE1E9, transparentRows = false)
        assertTrue(colors.all { it ushr 24 == 255 })
    }

    @Test
    fun `Mother Brain effects expose only their source attack palette and not adjacent code`() {
        val parser = TestRomHelper.requireRomParser()
        val asset = RomVisualAssets.ASSETS.single { it.graphics == 0xB7A000 }
        assertEquals(0xA994B2, asset.palette)
        assertEquals(16, asset.paletteColors)
        assertTrue(0xA994B2 in NativeSpriteAssets.paletteRows(parser))
        assertFalse(0xA99552 in NativeSpriteAssets.paletteRows(parser), "Mother Brain AI starts here, not another palette row")
    }
}
