package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.ui.EditorTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MakerModelTest {
    @Test
    fun `every tool has a unique shortcut that maps back to it`() {
        assertEquals(MakerTool.entries.size, MakerTool.entries.map { it.key.uppercaseChar() }.toSet().size)
        for (t in MakerTool.entries) {
            assertEquals(t, MakerTool.forKey(t.key))
            assertEquals(t, MakerTool.forKey(t.key.lowercaseChar()))
        }
        assertNull(MakerTool.forKey('Q'))
        assertEquals(EditorTool.SAMPLE, MakerTool.PICK.editorTool)
    }

    @Test
    fun `hint says what a click does for each tool and part kind`() {
        val tile = MakerPart.Tile("ground", "Ground", "solid", MakerCategory.TERRAIN, 1)
        val item = MakerPart.Item("item-1", "Missile", "+5", 0xEEDB)
        assertTrue(makerHint(MakerTool.PAINT, tile).startsWith("Click or drag to place Ground."))
        assertTrue("Right-click" in makerHint(MakerTool.PAINT, tile))
        assertTrue(makerHint(MakerTool.PAINT, item).startsWith("Click to place Missile."))
        assertTrue("Choose a part" in makerHint(MakerTool.PAINT, null))
        assertTrue("flood" in makerHint(MakerTool.FILL, tile))
        assertTrue("empty air" in makerHint(MakerTool.ERASE, null))
        for (t in MakerTool.entries) assertTrue(makerHint(t, tile).isNotBlank())
    }

    @Test
    fun `block types read in player terms`() {
        assertEquals("Air", blockTypeName(0x00FF))
        assertEquals("Solid", blockTypeName(0x8111))
        assertEquals("Door", blockTypeName(0x9040))
        assertEquals("Spikes", blockTypeName(0xA000))
        assertEquals("Bomb block", blockTypeName(0xF000))
    }
}

class MakerReadyTest {
    @kotlin.test.Test
    fun `ready checklist matches what Play can do`() {
        val rom = java.io.File(System.getenv("V1_ROM") ?: "")
        org.junit.jupiter.api.Assumptions.assumeTrue(rom.isFile, "V1_ROM not set")
        val parser = com.supermetroid.editor.rom.RomParser.loadRom(rom.absolutePath)
        val names = parser.roomCatalog.rooms.associate { it.getRoomIdAsInt() to it.name }
        val landing = readyChecks(parser, 0x91F8) { names[it] ?: "?" }
        println("[READY] 91F8 $landing")
        kotlin.test.assertTrue(landing.all { it.ok })
        kotlin.test.assertEquals("From the save station in this room", landing[0].detail)
        val parlor = readyChecks(parser, 0x92FD) { names[it] ?: "?" }
        println("[READY] 92FD $parlor")
        kotlin.test.assertTrue(parlor.all { it.ok })
        kotlin.test.assertTrue(parlor[0].detail.startsWith("Enters through the door from"))
    }
}
