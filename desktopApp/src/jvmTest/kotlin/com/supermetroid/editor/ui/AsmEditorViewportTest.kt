package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** The editor must scroll a large bank without needing a ROM or touching a user's project. */
@OptIn(ExperimentalComposeUiApi::class)
class AsmEditorViewportTest {
    @TempDir lateinit var directory: File

    @Test fun `reopening a target near EOF keeps preceding context visible`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_751)
        withEditor(state) { scene, render ->
            val editor = editableNode(scene)
            val text = editor.config[SemanticsProperties.EditableText].text
            assertTrue(text.lines().size >= 40, "Tail target must not isolate a four-line page: ${text.lines().size} lines")
            val before = visibleLine(scene)
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 300f), scrollDelta = Offset(0f, -1f))
            render()
            val after = visibleLine(scene)
            assertTrue(before - after in 1..12, "One wheel notch must scroll without jumping a whole page: $before -> $after")
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `wheel scrolling through page boundaries is continuous in both directions`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_240)
        withEditor(state) { scene, render ->
            assertTrue(editableNode(scene).config[SemanticsActions.OnClick].action!!.invoke())
            render()
            for (direction in listOf(1f, -1f)) {
                repeat(30) {
                    val before = visibleLine(scene)
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 300f), scrollDelta = Offset(0f, direction))
                    render()
                    val after = visibleLine(scene)
                    assertTrue((after - before) * direction in 0f..12f,
                        "Wheel direction $direction skipped or reversed rows: $before -> $after")
                }
            }
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `large font five digit gutter has one visual row per source row`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_620)
        withEditor(state) { scene, _ ->
            val gutter = nodes(scene).first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.lineSequence()
                    ?.all { it.toIntOrNull() != null } == true
            }
            val text = gutter.config[SemanticsProperties.Text].single().text
            val layout = textLayout(gutter)
            assertEquals(text.lines().size, layout.lineCount, "Line numbers must not wrap at the largest font size")
            val editor = editableNode(scene)
            val editorLayout = textLayout(editor)
            assertEquals(editorLayout.lineCount, layout.lineCount)
            for (line in listOf(0, 125, 249)) {
                assertEquals(editor.positionInRoot.y + editorLayout.getLineTop(line),
                    gutter.positionInRoot.y + layout.getLineTop(line), 1f, "Gutter row $line must align with its source")
            }
        }
    }

    @Test fun `scrollbar drag reaches end and returns to start without moving source or history`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 0)
        val original = state.sourceEditText("bank_8F.asm")
        val navigation = state.navigationSerial
        withEditor(state) { scene, render ->
            val top = editableNode(scene).positionInRoot.y + 6f
            scene.sendPointerEvent(PointerEventType.Press, Offset(995f, top), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(995f, 780f))
            render()
            scene.sendPointerEvent(PointerEventType.Release, Offset(995f, 780f), button = PointerButton.Primary)
            render()
            assertTrue(visibleLine(scene) > 15_700, "Dragging the thumb to the bottom must reach the actual end")
            scene.sendPointerEvent(PointerEventType.Press, Offset(995f, 780f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(995f, top))
            render()
            scene.sendPointerEvent(PointerEventType.Release, Offset(995f, top), button = PointerButton.Primary)
            render()
            val topLine = visibleLine(scene)
            assertTrue(topLine < 10, "Dragging back to the top must not recenter on the previous target: $topLine")
            assertEquals(original, state.sourceEditText("bank_8F.asm"))
            assertEquals(navigation, state.navigationSerial)
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `typing at a window boundary keeps the inserted line visible and preserves following source`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 249, columnIndex = "; source line 249".length)
        val original = state.sourceEditText("bank_8F.asm")
        withEditor(state) { scene, render ->
            val node = editableNode(scene)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(node.config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("\n")))
            render()
            assertEquals(original.replace("; source line 249\n", "; source line 249\n\n"), state.sourceEditText("bank_8F.asm"))
            val editor = editableNode(scene)
            val layout = textLayout(editor)
            val selection = editor.config[SemanticsProperties.TextSelectionRange]
            val cursor = layout.getCursorRect(selection.end)
            val caretY = editor.positionInRoot.y + cursor.top
            assertTrue(caretY in 60f..780f, "Typing across the window boundary must keep the new caret visible: $caretY")
        }
    }

    private suspend fun workspace(): AsmWorkspaceState {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_8F.asm\n")
        File(src, "bank_8F.asm").writeText((0 until 15_754).joinToString("\n") { "; source line $it" })
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val projectFile = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(projectFile.path, reference)
        // Bind a new workspace, as when the app reopens a saved project.
        return AsmWorkspaceState(projectRepository = repository).also { it.bindProject(projectFile.path, true) }
    }

    private fun withEditor(state: AsmWorkspaceState, test: (ComposeScene, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1000, 800)
        try {
            scene.constraints = Constraints.fixed(1000, 800)
            val theme = EditorThemeState().apply { fontSize.value = FontSize.LARGER }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides theme) {
                    MaterialTheme { AsmWorkspaceCanvas(state) }
                }
            }
            var frameTime = System.nanoTime()
            val render = { repeat(12) { frameTime += 16_666_667; scene.render(surface.canvas, frameTime) }; Unit }
            render()
            test(scene, render)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    private fun editableNode(scene: ComposeScene) = nodes(scene).first { it.config.contains(SemanticsProperties.EditableText) }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        return layouts.single()
    }

    private fun visibleLine(scene: ComposeScene): Int {
        val node = editableNode(scene)
        val layout = textLayout(node)
        val localLine = layout.getLineForVerticalPosition((70f - node.positionInRoot.y).coerceAtLeast(0f))
        return layout.layoutInput.text.text.lines()[localLine].substringAfterLast(' ').toInt()
    }
}
