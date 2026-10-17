package com.supermetroid.editor.ui.maker

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.EnemySpriteCache
import com.supermetroid.editor.ui.ItemSpriteSheetCache
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.MapCanvasControls
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.ROOM_ITEM_SPRITE_COORDS
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (V1_ROM): the full-game library in the Maker. Every tab is filled from the ROM sweep, every card has
 * real game art, and Room look applies an area effect. Screenshots go to MAKER_UI_SHOTS when set.
 */
@OptIn(ExperimentalTestApi::class)
class MakerLibraryUiTest {
    @Test
    fun `library tabs and room look`() = androidx.compose.ui.test.runDesktopComposeUiTest(width = 1600, height = 960) {
        val rom = File(System.getenv("V1_ROM") ?: "")
        assumeTrue(rom.isFile, "V1_ROM not set")
        val shots = System.getenv("MAKER_UI_SHOTS")?.let { File(it).apply { mkdirs() } }
        val open = ProjectLifecycle.create("Library", Files.createTempDirectory("maker-lib").toFile(), rom)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(open.file, open.project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(open.project.romPath))
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        val rooms = parser.roomCatalog.rooms
        val controls = MapCanvasControls()
        setContent {
            MakerModeScreen(editor, parser, rooms, rooms.first { it.getRoomIdAsInt() == 0x91F8 }, EmulatorWorkspaceState(), {}, {}, {}, {},
                themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null),
                canvasControls = controls)
        }
        waitUntil(15_000) { controls.openTilePropertiesAt != null }
        fun shot(name: String) { shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }
        val results = linkedMapOf<String, Boolean>()

        // Data: each tab is filled from the sweep and every card has real game art.
        EnemySpriteCache.setRomParser(parser)
        val library = MakerLibrary.build(parser)
        val tg = requireNotNull(editor.tileGraphics)
        for (cat in listOf(MakerCategory.TERRAIN, MakerCategory.HAZARDS, MakerCategory.DECOR, MakerCategory.ITEMS, MakerCategory.ENEMIES)) {
            val parts = makerPartsFor(cat, editor, library, parser)
            val noArt = parts.filter { part ->
                when (part) {
                    is MakerPart.Tile -> tg.renderMetatile(part.metatile) == null
                    is MakerPart.Item -> RomParser.ITEM_DEFS.firstOrNull { part.plmId in setOf(it.visibleId, it.chozoId, it.hiddenId) }
                        ?.let { ROOM_ITEM_SPRITE_COORDS[it.name] }?.let { ItemSpriteSheetCache.get() } == null
                    is MakerPart.Enemy -> EnemySpriteCache.get("%04X".format(part.enemyId), part.initParam) == null
                    else -> false
                }
            }.map { it.name }
            println("[LIB] ${cat.label}: ${parts.size} cards (${parts.filterIsInstance<MakerPart.Enemy>().count { it.blocked != null }} greyed); no art: $noArt")
            results["${cat.label} has cards"] = parts.isNotEmpty()
            results["${cat.label} cards all have game art (missing: $noArt)"] = noArt.isEmpty()
        }
        val items = makerPartsFor(MakerCategory.ITEMS, editor, library, parser)
        results["all 63 item variants are in Items (got ${items.size})"] = items.size == 63

        // UI: each tab renders.
        for ((label, file) in listOf("Terrain" to "library-terrain", "Hazards" to "library-hazards", "Items" to "library-items",
            "Enemies" to "library-enemies", "Decor" to "library-decor")) {
            onAllNodesWithText(label).onFirst().performClick()
            waitForIdle()
            shot(file)
        }

        // Room look: open, apply Water, close; the room's FX is now water.
        onNodeWithText("Room look: tileset & effects").performClick()
        waitForIdle()
        onNodeWithText("Tilesets and effects the game uses", substring = true).assertExists()
        shot("room-look")
        onNodeWithText("Water").performClick()
        waitForIdle()
        onNodeWithText("Done").performClick()
        waitForIdle()
        val fx = editor.effectiveFxEntries(editor.currentRoomId, parser).firstOrNull { it.doorSelect == 0 }?.fxType
        results["Room look applies Water (fx=${fx?.let { "%02X".format(it) }})"] = fx == 0x06
        shot("room-look-water-applied")

        results.forEach { (k, v) -> println("[LIB] ${if (v) "PASS" else "FAIL"} $k") }
        assertTrue(results.values.all { it }, results.filterValues { !it }.keys.toString())
    }
}
