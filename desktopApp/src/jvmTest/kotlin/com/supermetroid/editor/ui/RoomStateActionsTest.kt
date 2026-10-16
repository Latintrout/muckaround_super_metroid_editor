package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import org.jetbrains.skia.Surface
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class RoomStateActionsTest {
    @Test fun `state controls lock stale selectors recover automatically and always protect the default`() {
        val editable = mutableStateOf(true)
        val default = mutableStateOf(false)
        val clicks = mutableListOf<String>()
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(750, 120)
        try {
            scene.constraints = Constraints.fixed(750, 120)
            scene.setContent { MaterialTheme {
                RoomStateActions(editable.value, default.value, 1, 4,
                    { clicks += "add" }, { clicks += "duplicate" }, { clicks += "move:$it" },
                    { clicks += "delete" }, { clicks += "simulate" })
            } }
            var time = System.nanoTime()
            fun render() { repeat(4) { time += 16_666_667; scene.render(surface.canvas, time) } }
            fun button(label: String) = nodes(scene).first { node ->
                node.config.contains(SemanticsActions.OnClick) && descendants(node).any {
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
                }
            }
            val labels = listOf("+ Add", "Duplicate", "↑", "↓", "Delete", "Simulate")
            render()
            labels.forEach { label -> assertFalse(button(label).config.contains(SemanticsProperties.Disabled), label) }
            assertTrue(button("Duplicate").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(listOf("duplicate"), clicks)
            editable.value = false; render()
            labels.forEach { label -> assertTrue(button(label).config.contains(SemanticsProperties.Disabled), label) }
            editable.value = true; default.value = true; render()
            listOf("↑", "↓", "Delete").forEach { label -> assertTrue(button(label).config.contains(SemanticsProperties.Disabled), label) }
            listOf("+ Add", "Duplicate", "Simulate").forEach { label -> assertFalse(button(label).config.contains(SemanticsProperties.Disabled), label) }
            default.value = false; render()
            labels.forEach { label -> assertFalse(button(label).config.contains(SemanticsProperties.Disabled), label) }
        } finally { scene.close(); surface.close() }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
}
