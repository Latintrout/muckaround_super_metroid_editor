package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.ProjectLifecycle
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Box operations behind Select (move with contents, copy/paste/duplicate, clear) and Fill/line previews. */
class MakerSelectionTest {
    @Test
    fun `straight lines follow the longer axis`() {
        assertEquals(MakerBox(2, 5, 9, 5), straightLine(2 to 5, 9 to 7))
        assertEquals(MakerBox(2, 1, 2, 5), straightLine(2 to 5, 3 to 1))
        assertEquals(MakerBox(2, 5, 2, 5), straightLine(2 to 5, 2 to 5))
    }

    private fun landing(): EditorState? {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val open = ProjectLifecycle.create("Sel", Files.createTempDirectory("maker-sel").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        // As the Maker screen does: a Maker room is one layout, edits apply to every state sharing it.
        editor.setCurrentLayoutEditScope(com.supermetroid.editor.ui.LayoutEditScope.ALL_SHARING_STATES)
        return editor
    }

    private fun EditorState.words(box: MakerBox) = (box.y0..box.y1).map { y -> (box.x0..box.x1).map { x -> readBlockWord(x, y) } }

    /** Opt-in (V1_ROM): a box moves with its item, enemy and Start point, and one undo puts it all back. */
    @Test
    fun `move box carries its contents as one undo step`() {
        val e = landing() ?: return
        val box = MakerBox(40, 56, 43, 59)
        e.addPlm(0xEED7, 41, 57, 0, ItemStateScope.ALL_STATES) // Energy Tank
        e.addEnemy(e.workingEnemies.firstOrNull()?.id ?: 0xDCFF, 42 * 16 + 8, 58 * 16 + 8)
        e.setMakerStartPoint(MakerStartPoint(40, 56))
        val before = e.words(box)
        val enemy = e.workingEnemies.last()
        val undoDepth = e.undoStack.size

        val to = e.moveBox(box, 0, -10)
        assertEquals(box.moved(0, -10), to)
        assertEquals(before, e.words(to), "the blocks moved")
        assertTrue((56..59).all { y -> (40..43).all { x -> e.readBlockWord(x, y) == RomConstants.AIR_TILE_WORD } }, "left air behind")
        assertTrue(e.workingPlms.any { it.id == 0xEED7 && it.x == 41 && it.y == 47 }, "item moved")
        assertTrue(e.workingEnemies.any { it.id == enemy.id && it.x == enemy.x && it.y == enemy.y - 160 }, "enemy moved")
        assertEquals(MakerStartPoint(40, 46), e.makerStartPoint(), "start point moved")

        e.undo()
        assertEquals(undoDepth, e.undoStack.size, "one undo step")
        assertEquals(before, e.words(box))
        assertTrue(e.workingPlms.any { it.id == 0xEED7 && it.x == 41 && it.y == 57 })
        assertTrue(e.workingEnemies.any { it.id == enemy.id && it.x == enemy.x && it.y == enemy.y })
        assertEquals(MakerStartPoint(40, 56), e.makerStartPoint())
        e.redo()
        assertEquals(MakerStartPoint(40, 46), e.makerStartPoint(), "one redo moves it all again")
        assertTrue(e.workingPlms.any { it.id == 0xEED7 && it.x == 41 && it.y == 47 })
    }

    /** Opt-in: copy/paste, duplicate beside, clear with contents, fill preview. */
    @Test
    fun `copy paste duplicate clear and fill region`() {
        val e = landing() ?: return
        val box = MakerBox(40, 56, 42, 58)
        e.addPlm(0xEED7, 41, 57, 0, ItemStateScope.ALL_STATES)
        val clip = e.copyBox(box)
        assertEquals(1, clip.items.size)
        val pasted = requireNotNull(e.pasteClip(clip, 10, 50))
        assertEquals(MakerBox(10, 50, 12, 52), pasted)
        assertEquals(e.words(box), e.words(pasted))
        assertTrue(e.workingPlms.any { it.id == 0xEED7 && it.x == 11 && it.y == 51 }, "item pasted too")
        e.undo()
        assertTrue(e.workingPlms.none { it.x == 11 && it.y == 51 }, "one undo removes the whole paste")

        assertEquals(43 to 56, e.duplicateSpot(box))
        val nearEdge = MakerBox(e.workingBlocksWide - 3, 10, e.workingBlocksWide - 1, 12)
        assertEquals(nearEdge.x0 to 13, e.duplicateSpot(nearEdge), "no room on the right: below")
        assertNull(e.pasteClip(e.copyBox(MakerBox(0, 0, e.workingBlocksWide - 1, 0)).copy(width = e.workingBlocksWide + 1), 0, 0), "too big")

        e.clearBox(box)
        assertTrue((56..58).all { y -> (40..42).all { x -> e.readBlockWord(x, y) == RomConstants.AIR_TILE_WORD } })
        assertTrue(e.workingPlms.none { it.x == 41 && it.y == 57 }, "item cleared")
        e.undo()
        assertTrue(e.workingPlms.any { it.x == 41 && it.y == 57 }, "one undo restores blocks and item")

        val region = e.fillRegion(60, 20)
        assertTrue(region.isNotEmpty() && region.all { (x, y) -> e.readBlockWord(x, y) == e.readBlockWord(60, 20) })
        assertTrue((60 to 20) in region)
    }
}
