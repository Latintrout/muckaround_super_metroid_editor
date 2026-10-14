package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.random.Random

class NativeSpriteEditorStateTest {
    @TempDir lateinit var directory: File

    @Test
    fun `beam pixel edits preview save reopen export and reset without modifying loaded ROM`() {
        val parser = TestRomHelper.requireRomParser()
        val original = parser.getRomData().copyOf()
        var project = SmEditProject("base.smc")
        var changes = 0
        val state = NativeSpriteEditorState({ project.customGfx }, { changes++ })
        val sheet = state.loadSheet(parser, "9AF200", 0x90C3E1)
        state.applyPixels(parser, sheet, sheet.pixels.copyOf())
        assertEquals(0, changes, "No-op Apply must not create an override")
        val pixels = sheet.pixels.copyOf()
        for (i in pixels.indices) if (sheet.indices[i] >= 0) pixels[i] = sheet.palette[(sheet.indices[i] + 1) % 16]
        val before = requireNotNull(ProjectileSprites(parser).render(ProjectileSprites.WEAPONS.first()))
        state.applyPixels(parser, sheet, pixels)
        assertTrue(state.hasTiles("9AF200"))
        assertEquals(1, changes)
        val preview = state.previewParser(parser)
        val after = requireNotNull(ProjectileSprites(preview).render(ProjectileSprites.WEAPONS.first()))
        assertFalse(before.frames.first().pixels.contentEquals(after.frames.first().pixels))
        assertArrayEquals(original, parser.getRomData())

        val file = File(directory, "native.smedit")
        assertTrue(ProjectFileService.saveProject(project, file.absolutePath, null, false) {})
        project = ProjectFileService.loadProject(file)
        assertTrue(state.hasTiles("9AF200"), "The state accessor must follow the reopened project")
        val logs = mutableListOf<String>()
        val build = requireNotNull(RomExporter(project, parser, onLog = logs::add).build()) { logs.joinToString("\n") }
        val exported = RomParser(build.resultRom)
        assertArrayEquals(RomVisualAssets.raw(preview, 0x9AF200, 0x200), RomVisualAssets.raw(exported, 0x9AF200, 0x200))
        state.resetTiles("9AF200")
        assertFalse(state.hasTiles("9AF200"))
        assertArrayEquals(original, state.previewParser(parser).getRomData())
    }

    @Test
    fun `source encoder preserves ambiguous palette indexes and padding`() {
        val state = NativeSpriteEditorState({ TilesetGfxData() }, {})
        val source = NativeSpriteAssets.Source("Synthetic", 0x9AF200, 32, false, false)
        val palette = IntArray(16) { if (it == 0) 0 else 0xFFFFFFFF.toInt() }
        val raw = ByteArray(32) { 0xFF.toByte() }
        val indices = IntArray(128 * 8) { if (it % 128 < 8) 15 else -1 }
        val pixels = IntArray(indices.size) { if (indices[it] < 0) 0 else palette[indices[it]] }
        val sheet = NativeSpriteEditorState.Sheet(source, raw, palette, indices, pixels, 128, 8)
        assertArrayEquals(raw, state.encodePixels(sheet, pixels.copyOf()))
        assertThrows(IllegalArgumentException::class.java) { state.encodePixels(sheet, pixels.copyOf().also { it[8] = palette[1] }) }
        val erased = state.encodePixels(sheet, pixels.copyOf().also { it[0] = 0 })
        assertEquals(0x7F, erased[0].toInt() and 255)
        assertEquals(0x7F, erased[1].toInt() and 255)
        assertEquals(0x7F, erased[16].toInt() and 255)
        assertEquals(0x7F, erased[17].toInt() and 255)
    }

    @Test
    fun `native palette edits retain transparent entry and reuse existing named palette owners`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        for (address in listOf(0x90C3E1, 0x9B9400, NativeSpriteAssets.shipPalette(parser))) {
            val original = RomVisualAssets.raw(parser, address, 32)
            val palette = state.palette(parser, address).also { it[1] = 0xFF2050A0.toInt() }
            state.savePalette(parser, address, palette)
            val effective = state.previewParser(parser)
            val changed = RomVisualAssets.raw(effective, address, 32)
            assertEquals(original[0], changed[0]); assertEquals(original[1], changed[1])
            assertArrayEquals(original.copyOfRange(4, 32), changed.copyOfRange(4, 32))
            assertEquals(EnemySpriteGraphics.snesColorToArgb(EnemySpriteGraphics.argbToSnesColor(palette[1])), state.palette(parser, address)[1])
            if (address == 0x9B9400) {
                assertTrue(project.customGfx.spritePalettes.containsKey("samus_power"))
                assertFalse(project.customGfx.visualPalettes.containsKey("9B9400"))
            }
            if (address == NativeSpriteAssets.shipPalette(parser)) assertTrue(project.customGfx.spritePalettes.containsKey("enemy_pal:D07F"))
            val build = requireNotNull(RomExporter(project, parser).build())
            assertArrayEquals(changed, RomVisualAssets.raw(RomParser(build.resultRom), address, 32))
            state.resetPalette(parser, address)
            assertArrayEquals(original, RomVisualAssets.raw(state.previewParser(parser), address, 32))
        }
    }

    @Test
    fun `ship edits retain the original enemy-owned export path`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val sheet = state.loadSheet(parser, "ADB600", NativeSpriteAssets.shipPalette(parser))
        val pixels = sheet.pixels.copyOf().also { it[0] = sheet.palette.first { color -> color != sheet.pixels[0] } }
        state.applyPixels(parser, sheet, pixels)
        assertTrue(project.customGfx.spriteTileBlocks.containsKey("enemy:D07F"))
        assertFalse(project.customGfx.visualTileBlocks.containsKey("ADB600"))
        val build = requireNotNull(RomExporter(project, parser).build())
        assertArrayEquals(RomVisualAssets.raw(state.previewParser(parser), 0xADB600, 0x1000), RomVisualAssets.raw(RomParser(build.resultRom), 0xADB600, 0x1000))
    }

    @Test
    fun `compressed source overflow is rejected atomically and validator blocks malformed native edits`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        var changes = 0
        val state = NativeSpriteEditorState({ project.customGfx }, { changes++ })
        val sheet = state.loadSheet(parser, "9580D8", 0x8CE309)
        val random = Random(42)
        val pixels = IntArray(sheet.pixels.size) { sheet.palette[random.nextInt(16)] }
        val failure = assertThrows(IllegalArgumentException::class.java) { state.applyPixels(parser, sheet, pixels) }
        assertTrue(failure.message.orEmpty().contains("available"), failure.message)
        assertTrue(project.customGfx.visualTileBlocks.isEmpty())
        assertEquals(0, changes)
        project.customGfx.visualTileBlocks["FFFFFF"] = Base64.getEncoder().encodeToString(byteArrayOf(1))
        assertTrue(RomValidator.checkProjectNativeSpriteEdits(parser, project).any { it.severity == RomValidator.Severity.ERROR })
        assertNull(RomExporter(project, parser).build())
    }

    @Test
    fun `compressed 4bpp and Mode7 edits survive project reload and export`() {
        val parser = TestRomHelper.requireRomParser()
        var project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val sources = listOf("9580D8" to 0x8CE309, "94E000" to 0x8CE1E9)
        val expected = sources.associate { (key, palette) ->
            val sheet = state.loadSheet(parser, key, palette)
            assertArrayEquals(sheet.raw, state.encodePixels(sheet, sheet.pixels.copyOf()), "$key no-op")
            // A highly compressible edit fits without requiring asset relocation.
            val pixels = IntArray(sheet.pixels.size) { if (sheet.indices[it] >= 0) sheet.palette[1] else 0 }
            val raw = state.encodePixels(sheet, pixels)
            assertFalse(raw.contentEquals(sheet.raw))
            state.applyPixels(parser, sheet, pixels)
            assertArrayEquals(raw, NativeSpriteAssets.source(key).read(state.previewParser(parser)))
            if (sheet.source.mode7) assertTrue(raw.all { it.toInt() == 1 }, "Mode 7 is chunky indexed 8bpp, not SNES 4bpp")
            key to raw
        }
        val palette = state.palette(parser, 0x8CE309).also { it[1] = 0xFF20A050.toInt() }
        state.savePalette(parser, 0x8CE309, palette)
        val expectedPalette = RomVisualAssets.raw(state.previewParser(parser), 0x8CE309, 32)
        val file = File(directory, "compressed.smedit")
        assertTrue(ProjectFileService.saveProject(project, file.absolutePath, null, false) {})
        project = ProjectFileService.loadProject(file)
        val build = requireNotNull(RomExporter(project, parser).build())
        val exported = RomParser(build.resultRom)
        for ((key, raw) in expected) assertArrayEquals(raw, NativeSpriteAssets.source(key).read(exported), key)
        assertArrayEquals(expectedPalette, RomVisualAssets.raw(exported, 0x8CE309, 32))
        for ((key, _) in sources) state.resetTiles(key)
        state.resetPalette(parser, 0x8CE309)
        assertArrayEquals(parser.getRomData(), state.previewParser(parser).getRomData())
    }

    @Test
    fun `preview shares existing enemy palette owners and rejects aliased native writes`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val header = parser.snesToPc(0xA0F353)
        val address = (parser.readByteAt(header + 0x0C) shl 16) or parser.readUInt16At(header + 2)
        val raw = RomVisualAssets.raw(parser, address, 32).also { it[2] = (it[2].toInt() xor 0x1F).toByte() }
        project.customGfx.spritePalettes["enemy_pal:F353"] = Base64.getEncoder().encodeToString(raw)
        assertArrayEquals(raw, RomVisualAssets.raw(state.previewParser(parser), address, 32))
        project.customGfx.spritePalettes.clear()

        // A malformed project must not silently overwrite the existing Samus palette owner.
        val samus = requireNotNull(SpritePalettes.findRegion("samus_power"))
        project.customGfx.spritePalettes[samus.id] = Base64.getEncoder().encodeToString(parser.getRomData().copyOfRange(samus.offset, samus.offset + samus.byteSize))
        project.customGfx.visualPalettes["9B9400"] = Base64.getEncoder().encodeToString(RomVisualAssets.raw(parser, 0x9B9400, 32).also { it[2] = (it[2].toInt() xor 0x1F).toByte() })
        assertThrows(RomWritePlanException::class.java) { state.previewParser(parser) }
        assertNull(RomExporter(project, parser).build())
    }

    @Test
    fun `all native workspace choices resolve whitelisted editable owners and palette rows`() {
        val parser = TestRomHelper.requireRomParser()
        val rows = NativeSpriteAssets.paletteRows(parser)
        for (workspace in VISUAL_SPRITE_WORKSPACES) for (index in visualSpriteNames(workspace).indices) {
            val targets = visualSpriteTargets(parser, workspace, index)
            assertTrue(targets.isNotEmpty(), "${workspace.name} choice $index")
            for (target in targets) {
                assertNotNull(NativeSpriteAssets.source(target.sourceKey))
                assertTrue(target.defaultRow in 0 until target.rows, target.name)
                for (row in 0 until target.rows) assertTrue(target.paletteBase + row * 32 in rows, target.name)
            }
        }
    }
}
