package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SamusPreviewAnimationsTest {
    @Test
    fun `Samus owns both death directions without treating them as gameplay pose IDs`() {
        val parser = TestRomHelper.requireRomParser()
        val decoder = SamusSpriteDecoder(parser)
        val death = SAMUS_PREVIEW_GROUPS.single { it.name == "Death" }
        assertEquals(listOf(SAMUS_DEATH_RIGHT, SAMUS_DEATH_LEFT), death.animationIds)
        assertEquals(SamusSpriteDecoder.ANIMATION_GROUPS, SAMUS_PREVIEW_GROUPS.dropLast(1))
        assertTrue(VISUAL_SPRITE_WORKSPACES.none { it.group == "Death" })
        assertTrue(samusMatchesSpriteQuery("death"))
        for (suit in SamusSpriteDecoder.SuitType.entries) for (id in death.animationIds) {
            val expected = requireNotNull(SamusDeathSprites(parser).render(suit.ordinal, id == SAMUS_DEATH_LEFT))
            val actual = requireNotNull(samusPreviewAnimation(parser, decoder, id, suit))
            assertTrue(expected == actual, "Death pixels and timing match the native renderer: $suit / $id")
            assertEquals(9, actual.frames.size)
            assertFalse(actual.loop)
            assertTrue(actual.frames.all { it.durationTicks > 0 && it.pixels.any { color -> color ushr 24 != 0 } })
            val target = samusDeathTarget(parser, suit)
            assertEquals("9B8000", target.sourceKey)
            assertTrue(target.paletteBase in NativeSpriteAssets.paletteRows(parser))
        }
        val ordinary = SamusSpriteDecoder.ANIMATION_GROUPS.first().animationIds.first()
        assertTrue(decoder.buildAnimation(ordinary, SamusSpriteDecoder.SuitType.POWER, renderSize = 96) ==
            samusPreviewAnimation(parser, decoder, ordinary, SamusSpriteDecoder.SuitType.POWER), "Gameplay pose routing is unchanged")
    }

    @Test
    fun `death source editing updates Samus preview and exports exact native graphics`() {
        val parser = TestRomHelper.requireRomParser()
        val original = parser.getRomData().copyOf()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val suit = SamusSpriteDecoder.SuitType.VARIA
        val target = samusDeathTarget(parser, suit)
        val sheet = state.loadSheet(parser, target.sourceKey, target.paletteBase)
        val editedPixels = sheet.pixels.mapIndexed { index, color ->
            if (sheet.indices[index] <= 0) color else sheet.palette.first { it ushr 24 != 0 && it != color }
        }.toIntArray()
        state.applyPixels(parser, sheet, editedPixels)
        val preview = state.previewParser(parser)
        val before = requireNotNull(samusPreviewAnimation(parser, SamusSpriteDecoder(parser), SAMUS_DEATH_LEFT, suit))
        val after = requireNotNull(samusPreviewAnimation(preview, SamusSpriteDecoder(preview), SAMUS_DEATH_LEFT, suit))
        assertNotEquals(before, after)
        assertArrayEquals(original, parser.getRomData(), "Previews never change the loaded ROM")
        val exported = RomParser(requireNotNull(RomExporter(project, parser).build()).resultRom)
        assertTrue(after == samusPreviewAnimation(exported, SamusSpriteDecoder(exported), SAMUS_DEATH_LEFT, suit), "Export matches the edited death preview")
        assertArrayEquals(state.encodePixels(sheet, editedPixels), RomVisualAssets.raw(exported, 0x9B8000, 0x1400))
    }
}
