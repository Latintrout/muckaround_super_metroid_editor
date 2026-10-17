package com.supermetroid.editor.ui.maker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.MakerModeScreen
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.theme.MakerBrightness
import com.supermetroid.editor.ui.theme.MakerThemeState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in offscreen render of the real Maker screen (no window, no input):
 * MAKER_UI_PROJECT=<.smedit file>, MAKER_UI_SHOTS=<output dir>, optional MAKER_UI_ROOM (hex id).
 */
class MakerScreenRenderTest {
    @Test
    fun `render maker screen offscreen`() {
        val projectPath = System.getenv("MAKER_UI_PROJECT")
        val outDir = System.getenv("MAKER_UI_SHOTS")
        assumeTrue(projectPath != null && outDir != null && File(projectPath).isFile)
        val projectFile = File(projectPath!!)
        val project = ProjectFileService.loadProject(projectFile)
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(projectFile, project)
        val parser = editor.prepareWorkspaceParser(RomParser.loadRom(File(projectFile.parentFile, File(project.romPath).name).absolutePath))
        val roomId = (System.getenv("MAKER_UI_ROOM") ?: "91F8").toInt(16)
        editor.loadRoom(roomId, parser, requireNotNull(parser.readRoomHeader(roomId)))
        val rooms = parser.roomCatalog.rooms
        val room = rooms.first { it.getRoomIdAsInt() == roomId }
        val themeState = MakerThemeState(userThemesDir = Files.createTempDirectory("t").toFile(), appearanceFile = null)
        editor.hoverBlockX = 69; editor.hoverBlockY = 66

        val combos = listOf("modret" to MakerBrightness.LIGHT, "modret" to MakerBrightness.DARK, "sage" to MakerBrightness.LIGHT, "slate" to MakerBrightness.DARK)
        for ((theme, brightness) in combos) {
            themeState.update { it.copy(themeId = theme, brightness = brightness) }
            val scene = ImageComposeScene(width = 1600, height = 960, density = Density(1f)) {
                MakerModeScreen(
                    editorState = editor, romParser = parser, rooms = rooms, selectedRoom = room,
                    emulatorWorkspaceState = EmulatorWorkspaceState(),
                    onRoomSelected = {}, onWorkspaceChanged = {}, onPlay = {}, onExit = {},
                    themeState = themeState,
                )
            }
            // Let the room render (it loads off the UI thread) before capturing.
            var t = 0L
            repeat(40) { scene.render(t); t += 100_000_000L; Thread.sleep(100) }
            val file = File(outDir, "maker-$theme-${brightness.name.lowercase()}.png").apply { parentFile.mkdirs() }
            file.writeBytes(requireNotNull(scene.render(t).encodeToData(EncodedImageFormat.PNG)).bytes)
            scene.close()
            assertTrue(file.length() > 0)
        }
    }
}
