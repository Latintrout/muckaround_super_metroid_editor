package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

internal val ProjectToolbarActionPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
// Match the minimum interaction width so compact short/icon buttons remain visibly aligned.
internal val ProjectToolbarActionMinWidth = 48.dp

private enum class ToolbarSlot { LEADING, ACTIONS }

private class ToolbarRow {
    val leading = mutableListOf<Placeable>()
    val actions = mutableListOf<Placeable>()
    var leadingWidth = 0
    var actionWidth = 0
    val height: Int get() = (leading + actions).maxOfOrNull { it.height } ?: 0
}

/** Two aligned sides, but independent controls: neither a weighted spacer nor a nested
 * action Row may squeeze the final button or manufacture an empty flow row. */
@Composable
internal fun ProjectToolbar(
    modifier: Modifier = Modifier,
    leadingContent: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val rowGap = 4.dp.roundToPx()
        // Measure against the whole viewport, not the remaining space in a row.
        val childConstraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val leading = subcompose(ToolbarSlot.LEADING, leadingContent).map { it.measure(childConstraints) }
        val controls = subcompose(ToolbarSlot.ACTIONS, actions).map { it.measure(childConstraints) }
        val all = leading + controls
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else
            constraints.constrainWidth(all.sumOf { it.width } + gap * (all.size - 1).coerceAtLeast(0))
        val rows = mutableListOf(ToolbarRow())
        for (item in leading) {
            var row = rows.last()
            val spacing = if (row.leading.isEmpty()) 0 else gap
            if (row.leading.isNotEmpty() && row.leadingWidth + spacing + item.width > width) {
                row = ToolbarRow().also(rows::add)
            }
            row.leadingWidth += (if (row.leading.isEmpty()) 0 else gap) + item.width
            row.leading.add(item)
        }
        for (item in controls) {
            var row = rows.last()
            val spacing = if (row.actions.isEmpty()) 0 else gap
            val separation = if (row.leading.isEmpty()) 0 else gap
            if ((row.leading.isNotEmpty() || row.actions.isNotEmpty()) &&
                row.leadingWidth + separation + row.actionWidth + spacing + item.width > width) {
                row = ToolbarRow().also(rows::add)
            }
            row.actionWidth += (if (row.actions.isEmpty()) 0 else gap) + item.width
            row.actions.add(item)
        }
        val height = rows.sumOf { it.height } + rowGap * (rows.size - 1)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            for (row in rows) {
                var x = 0
                for (item in row.leading) {
                    item.placeRelative(x, y + (row.height - item.height) / 2)
                    x += item.width + gap
                }
                x = width - row.actionWidth
                for (item in row.actions) {
                    item.placeRelative(x, y + (row.height - item.height) / 2)
                    x += item.width + gap
                }
                y += row.height + rowGap
            }
        }
    }
}
