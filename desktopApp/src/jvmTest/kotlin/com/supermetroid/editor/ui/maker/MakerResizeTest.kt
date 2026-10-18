package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.MakerStartPoint
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.ui.ProjectLifecycle
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in (V1_ROM): templates in a real room, and adding/removing screens on every side. */
class MakerResizeTest {
    @Test
    fun `resize a maker room on every side`() {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val open = ProjectLifecycle.create("Resize", Files.createTempDirectory("maker-resize").toFile(), rom)
        val e = EditorState().apply { testMode = true }
        e.initForProject(open.file, open.project)
        var parser = e.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        e.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))

        // Rooms with doors leading in only change on the right or bottom.
        assertNotNull(e.resizeMakerRoom(parser, MakerSide.LEFT, grow = true))

        // A Shaft room, 1x2.
        val plan = requireNotNull(planMakerNewRoom(e, parser, 0x91F8, 1, 2, MakerNewRoomStart.SHAFT, "Shaft", seed = 4))
        // Somewhere on the Crateria map with free cells all round, so the room can grow on every side.
        val (created, ws) = (2 until 26).asSequence().flatMap { y -> (2 until 58).asSequence().map { x -> x to y } }.firstNotNullOf { (x, y) ->
            val clear = (x - 2..x + 3).all { cx -> (y - 2..y + 4).all { cy -> runCatching {
                parser.roomCatalog.rooms.none { info -> parser.readRoomHeader(info.getRoomIdAsInt())?.let(e::applyHeaderChanges)
                    ?.let { it.area == 0 && cx in it.mapX until it.mapX + it.width && cy in it.mapY until it.mapY + it.height } == true }
            }.getOrDefault(false) } }
            if (!clear) null else runCatching { e.createNewRoomWorkspace(plan.request.copy(mapX = x, mapY = y), parser) }.getOrNull()
        }
        parser = ws
        val id = created.previewRoomId
        fun reload() { parser = e.prepareWorkspaceParser(); e.loadRoom(id, parser, requireNotNull(parser.readRoomHeader(id))) }
        e.loadRoom(id, parser, requireNotNull(parser.readRoomHeader(id)))
        e.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        applyMakerNewRoomLook(e, requireNotNull(plan.look))
        val mask = makerTemplateSolid(MakerNewRoomStart.SHAFT, 16, 32, 4)
        assertTrue((0 until 32).all { y -> (0 until 16).all { x -> ((e.readBlockWord(x, y) shr 12) and 0xF == 0x8) == mask[y * 16 + x] } }, "the shaft layout")
        fun solid(x: Int, y: Int) = (e.readBlockWord(x, y) shr 12) and 0xF == 0x8
        e.setMakerStartPoint(MakerStartPoint(8, 28))
        val map0 = parser.readRoomHeader(id)!!.let(e::applyHeaderChanges).let { it.mapX to it.mapY }

        // Grow right: 2x2, the floor carries on, the wall moves to the new edge.
        assertNull(e.resizeMakerRoom(parser, MakerSide.RIGHT, grow = true)); reload()
        assertEquals(32 to 32, e.workingBlocksWide to e.workingBlocksTall)
        assertTrue((0 until 32).all { solid(it, 31) && solid(it, 29) }, "floor across")
        assertTrue((0 until 32).all { solid(31, it) }, "new right wall")

        // Grow left: content shifts right, the map position moves left.
        assertNull(e.resizeMakerRoom(parser, MakerSide.LEFT, grow = true)); reload()
        assertEquals(48, e.workingBlocksWide)
        assertEquals(MakerStartPoint(24, 28), e.makerStartPoint(), "start point moved with the room")
        assertTrue((0 until 32).all { solid(0, it) }, "new left wall")
        assertEquals(map0.first - 1 to map0.second, parser.readRoomHeader(id)!!.let(e::applyHeaderChanges).let { it.mapX to it.mapY })

        // Grow bottom then remove it again.
        assertNull(e.resizeMakerRoom(parser, MakerSide.BOTTOM, grow = true)); reload()
        assertEquals(48, e.workingBlocksTall)
        assertTrue((0 until 48).all { solid(it, 47) && solid(it, 45) }, "floor at the new bottom")
        assertNull(e.resizeMakerRoom(parser, MakerSide.BOTTOM, grow = false)); reload()
        assertEquals(32, e.workingBlocksTall)

        // Remove the left screen: back where it was on the map; the start point slides back.
        assertNull(e.resizeMakerRoom(parser, MakerSide.LEFT, grow = false)); reload()
        assertEquals(32, e.workingBlocksWide)
        assertEquals(MakerStartPoint(8, 28), e.makerStartPoint())
        assertEquals(map0, parser.readRoomHeader(id)!!.let(e::applyHeaderChanges).let { it.mapX to it.mapY })

        // Removing the top screen slides everything up; removing the screen the Start point is on removes it.
        assertNull(e.resizeMakerRoom(parser, MakerSide.TOP, grow = false)); reload()
        assertEquals(MakerStartPoint(8, 12), e.makerStartPoint())
        assertNull(e.resizeMakerRoom(parser, MakerSide.LEFT, grow = false)); reload()
        assertNull(e.makerStartPoint(), "its screen was removed")
        assertNotNull(e.resizeMakerRoom(parser, MakerSide.TOP, grow = false), "a room keeps at least one screen")
    }
}
