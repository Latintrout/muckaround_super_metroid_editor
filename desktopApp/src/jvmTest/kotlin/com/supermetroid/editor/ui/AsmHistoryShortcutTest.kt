package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.AsmDocumentFindState
import org.jetbrains.skia.Surface
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

@OptIn(ExperimentalComposeUiApi::class)
class AsmHistoryShortcutTest {
    @Test
    fun `history shortcuts work while sidebar search owns focus`() {
        val state = historyState()
        state.requestSearchFocus()
        assertTrue(state.canGoBack)
        withScene({ AsmWorkspaceSidebar(state, null, null, "", false, {}) }) { scene ->
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_LEFT))
            assertEquals("LDA", state.selectedInstruction, "Back must work even with sidebar search focused")
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_RIGHT))
            assertEquals("STA", state.selectedInstruction)
        }
    }

    @Test
    fun `history shortcuts work in the file find field without changing its query`() {
        val state = historyState()
        val find = AsmDocumentFindState().also { it.show(); it.query = "JumpSpeed" }
        withScene({
            Box(Modifier.asmHistoryShortcuts(state)) {
                AsmDocumentFindBar(find, "JumpSpeed: dw ${'$'}0400", {}, {})
            }
        }) { scene ->
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_LEFT))
            assertEquals("LDA", state.selectedInstruction)
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_RIGHT))
            assertEquals("STA", state.selectedInstruction)
            assertEquals("JumpSpeed", find.query)
        }
    }

    @Test
    fun `library canvas keeps history shortcuts when a header control owns focus`() {
        val state = historyState()
        withScene({ AsmWorkspaceCanvas(state) }) { scene ->
            scene.sendKeyEvent(key(AwtKeyEvent.VK_TAB, 0))
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_LEFT))
            assertEquals("LDA", state.selectedInstruction)
            scene.sendKeyEvent(historyKey(AwtKeyEvent.VK_RIGHT))
            assertEquals("STA", state.selectedInstruction)
        }
    }

    @Test
    fun `platform modifiers are explicit and selection chords remain native`() {
        assertEquals(-1, asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, InputEvent.META_DOWN_MASK), isMac = true))
        assertEquals(1, asmHistoryDirection(key(AwtKeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK), isMac = false))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK), isMac = true))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, InputEvent.META_DOWN_MASK), isMac = false))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, 0)))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK), isMac = true))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK), isMac = false))
        assertNull(asmHistoryDirection(key(AwtKeyEvent.VK_LEFT, InputEvent.META_DOWN_MASK, AwtKeyEvent.KEY_RELEASED), isMac = true))
    }

    private fun historyState() = AsmWorkspaceState().also {
        it.showInstruction("LDX")
        it.showInstruction("LDA")
        it.showInstruction("STA")
    }

    private fun withScene(content: @Composable () -> Unit, test: (ComposeScene) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(600, 800)
        try {
            scene.constraints = Constraints.fixed(600, 800)
            scene.setContent {
                MaterialTheme { content() }
            }
            repeat(3) { scene.render(surface.canvas, System.nanoTime()) }
            test(scene)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun historyKey(code: Int): KeyEvent {
        val modifier = if (System.getProperty("os.name").contains("Mac", ignoreCase = true))
            InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        return key(code, modifier)
    }

    private fun key(code: Int, modifiers: Int, type: Int = AwtKeyEvent.KEY_PRESSED) =
        KeyEvent(AwtKeyEvent(Canvas(), type, 0L, modifiers, code, AwtKeyEvent.CHAR_UNDEFINED))
}
