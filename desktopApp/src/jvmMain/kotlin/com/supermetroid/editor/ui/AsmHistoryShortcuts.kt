package com.supermetroid.editor.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.supermetroid.editor.asm.AsmWorkspaceState

/** Handle history before a focused text field consumes the arrows as caret movement.
 * Scope this to the ASM sidebar/canvas, never unrelated editor text fields. */
internal fun Modifier.asmHistoryShortcuts(state: AsmWorkspaceState): Modifier = onPreviewKeyEvent { event ->
    when (asmHistoryDirection(event)) {
        -1 -> { state.goBack(); true }
        1 -> { state.goForward(); true }
        else -> false
    }
}

internal fun asmHistoryDirection(
    event: KeyEvent,
    isMac: Boolean = System.getProperty("os.name").contains("Mac", ignoreCase = true),
): Int? {
    if (event.type != KeyEventType.KeyDown || event.isShiftPressed || event.isAltPressed) return null
    val command = if (isMac) event.isMetaPressed && !event.isCtrlPressed
        else event.isCtrlPressed && !event.isMetaPressed
    if (!command) return null
    return when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> null
    }
}
