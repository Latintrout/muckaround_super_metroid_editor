package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Surface
import kotlin.test.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class ProjectToolbarTest {
    @Test fun `whole toolbar controls wrap without tall labels clipping or excess empty height`() {
        for (width in listOf(700, 1280, 1920)) for (font in listOf(FontSize.SMALL, FontSize.LARGER)) {
            val scene = ComposeScene()
            val surface = Surface.makeRasterN32Premul(width, 400)
            val theme = EditorThemeState().apply { fontSize.value = font }
            val labels = listOf("NEW", "OPEN", "BUILD*", "RUN", "EMU", "Validate", "Save*", "Export ROM", "Export IPS", "Settings")
            try {
                scene.constraints = Constraints.fixed(width, 400)
                scene.setContent {
                    CompositionLocalProvider(LocalEditorTheme provides theme) {
                        MaterialTheme {
                            Box {
                                ProjectToolbar(Modifier.fillMaxWidth()) {
                                    labels.forEachIndexed { index, label ->
                                        if (index == 2) Text("Loaded: Super Metroid ASM Sandbox with a long name", fontSize = font.detail,
                                            modifier = Modifier.widthIn(max = 360.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Button(onClick = {}) { Text(label, fontSize = font.body, softWrap = false) }
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(6) { scene.render(surface.canvas, System.nanoTime()) }
                fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                val nodes = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
                val texts = nodes.filter { it.config.contains(SemanticsProperties.Text) }
                for (label in labels) {
                    val node = texts.single { it.config[SemanticsProperties.Text].single().text == label }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                    assertEquals(1, layouts.single().lineCount, "$width/$font/$label")
                    assertTrue(node.boundsInRoot.right <= width && node.boundsInRoot.left >= 0, "$label clipped")
                }
                val rows = texts.map { it.boundsInRoot.top }.distinct().size
                val bottom = texts.maxOf { it.boundsInRoot.bottom }
                assertTrue(bottom < 180, "Toolbar grew empty space at $width/$font: $bottom")
                if (width == 700) assertTrue(rows > 1, "Narrow toolbar must wrap whole controls")
                if (width == 1920) assertTrue(bottom < 60, "Wide toolbar should stay on one row")
            } finally { scene.close(); surface.close() }
        }
    }
}
