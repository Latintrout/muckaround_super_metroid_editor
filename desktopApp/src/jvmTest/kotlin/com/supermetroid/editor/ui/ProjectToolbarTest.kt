package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Surface
import kotlin.test.*

class ProjectToolbarTest {
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single {
        it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
    }

    @Test fun `whole toolbar controls wrap without tall labels clipping or excess empty height`() {
        for (width in listOf(420, 700, 1280, 1920)) for (font in listOf(FontSize.SMALL, FontSize.LARGER)) {
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
                                ProjectToolbar(Modifier.fillMaxWidth(), leadingContent = {
                                    labels.take(2).forEach { label ->
                                        Button(onClick = {}, modifier = Modifier.testTag(label)) { Text(label, fontSize = font.body, softWrap = false) }
                                    }
                                    Text("Loaded: Super Metroid ASM Sandbox with a long name", fontSize = font.detail,
                                        modifier = Modifier.widthIn(max = 360.dp).testTag("project"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }) {
                                    labels.drop(2).forEach { label ->
                                        Button(onClick = {}, contentPadding = ProjectToolbarActionPadding,
                                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth).testTag(label)) {
                                            Text(label, fontSize = font.body, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(6) { scene.render(surface.canvas, System.nanoTime()) }
                val texts = nodes(scene).filter { it.config.contains(SemanticsProperties.Text) }
                for (label in labels) {
                    val node = texts.single { it.config[SemanticsProperties.Text].single().text == label }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                    assertEquals(1, layouts.single().lineCount, "$width/$font/$label")
                    assertTrue(node.boundsInRoot.right <= width && node.boundsInRoot.left >= 0, "$label clipped")
                }
                val rows = texts.map { it.boundsInRoot.top }.distinct().size
                val bottom = texts.maxOf { it.boundsInRoot.bottom }
                assertTrue(bottom < if (width == 420) 280 else 180, "Toolbar grew empty space at $width/$font: $bottom")
                if (width == 700) assertTrue(rows > 1, "Narrow toolbar must wrap whole controls")
                if (width == 1920) assertTrue(bottom < 60, "Wide toolbar should stay on one row")
                val leading = listOf("NEW", "OPEN", "project").map { tagged(scene, it).boundsInRoot }
                val actions = labels.drop(2).map { tagged(scene, it).boundsInRoot }
                assertEquals(0f, leading.first().left, "Project controls must stay at the start")
                assertEquals(width.toFloat(), actions.last().right, "Settings must stay at the end")
                actions.groupBy { it.top }.values.forEach { row ->
                    assertEquals(width.toFloat(), row.last().right, "Each wrapped action row must end at the right edge")
                    row.zipWithNext().forEach { (left, right) -> assertTrue(right.left >= left.right + 8, "Buttons collided") }
                }
                for (left in leading) for (right in actions) assertFalse(left.overlaps(right), "Left/right controls overlap")
                if (width == 1920) assertTrue(actions.first().left - leading.last().right > 8, "Wide view must have a flexible middle gap")
            } finally { scene.close(); surface.close() }
        }
    }

    @Test fun `actions wrap only after the exact fit boundary and stay centered without shrinking`() {
        for (width in listOf(276, 275)) {
            val scene = ComposeScene()
            val surface = Surface.makeRasterN32Premul(width, 150)
            try {
                scene.constraints = Constraints.fixed(width, 150)
                scene.setContent {
                    Box {
                        ProjectToolbar(Modifier.fillMaxWidth(), leadingContent = {
                            Box(Modifier.size(100.dp, 20.dp).testTag("leading"))
                        }) {
                            Box(Modifier.size(80.dp, 40.dp).testTag("first"))
                            Box(Modifier.size(80.dp, 30.dp).testTag("second"))
                        }
                    }
                }
                repeat(6) { scene.render(surface.canvas, System.nanoTime()) }
                val leading = tagged(scene, "leading").boundsInRoot
                val first = tagged(scene, "first").boundsInRoot
                val second = tagged(scene, "second").boundsInRoot
                assertEquals(100f, leading.width)
                assertEquals(80f, first.width)
                assertEquals(80f, second.width)
                assertEquals(10f, leading.top, "Shorter controls are vertically centered")
                assertEquals(width.toFloat(), second.right)
                if (width == 276) {
                    assertEquals(108f, first.left)
                    assertEquals(5f, second.top)
                } else {
                    assertEquals(width.toFloat(), first.right)
                    assertEquals(44f, second.top, "Wrap adds only the normal row gap")
                }
            } finally { scene.close(); surface.close() }
        }
    }

    @Test fun `compact action padding reduces width without changing height or font size`() {
        for (font in listOf(FontSize.SMALL, FontSize.LARGER)) {
            val scene = ComposeScene()
            val surface = Surface.makeRasterN32Premul(700, 200)
            try {
                scene.constraints = Constraints.fixed(700, 200)
                scene.setContent {
                    MaterialTheme {
                        Column {
                            for (label in listOf("BUILD*", "RUN", "Export ROM")) Row {
                                for (compact in listOf(false, true)) {
                                    val modifier = (if (compact) Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth) else Modifier)
                                        .testTag("$label/$compact")
                                    val padding = if (compact) ProjectToolbarActionPadding else ButtonDefaults.ContentPadding
                                    if (label == "BUILD*") OutlinedButton(onClick = {}, modifier = modifier, contentPadding = padding) {
                                        Text(label, fontSize = font.body, softWrap = false)
                                    } else Button(onClick = {}, modifier = modifier, contentPadding = padding) {
                                        Text(label, fontSize = font.body, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(6) { scene.render(surface.canvas, System.nanoTime()) }
                for (label in listOf("BUILD*", "RUN", "Export ROM")) {
                    val regular = tagged(scene, "$label/false").boundsInRoot
                    val compact = tagged(scene, "$label/true").boundsInRoot
                    assertTrue(compact.width < regular.width, "$font/$label horizontal padding did not shrink")
                    assertEquals(regular.height, compact.height, "$font/$label height changed")
                }
            } finally { scene.close(); surface.close() }
        }
    }
}
