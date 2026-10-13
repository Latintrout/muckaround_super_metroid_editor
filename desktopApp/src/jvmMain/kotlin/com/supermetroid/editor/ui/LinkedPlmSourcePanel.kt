package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmPlmDocument
import com.supermetroid.editor.asm.AsmPlmAuthority
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.rom.RomParser

/** Authority is visible, but ordinary rooms never require a separate linking workflow. */
@Composable
internal fun LinkedPlmSourcePanel(editor: EditorState, pointer: Int, onOpenAsm: (() -> Unit)?) {
    val bridge = editor.asmPlmSourceBridge ?: return
    val fs = LocalEditorTheme.current.fontSize.value
    // Read the edit version for GUI ownership changes; the bridge observes the source revision itself.
    editor.editVersion
    val managed = editor.hasManagedPlms()
    val managedState = editor.currentPlmStateId()
    val authority = bridge.authority(pointer)
    val editable = managed || authority.authority in setOf(AsmPlmAuthority.UNCHANGED, AsmPlmAuthority.SOURCE)
    val hasLegacyBinding = editor.project.asmWorkspace.plmSourceBindings.any { it.pointer == pointer }
    var open by remember(pointer) { mutableStateOf(false) }
    Row {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (managed) "Edit objects · ASM" else when (authority.authority) {
                AsmPlmAuthority.UNCHANGED -> "Edit objects…"
                AsmPlmAuthority.SOURCE -> "Edit objects · ASM"
                AsmPlmAuthority.ROOM_EDITOR -> "Objects · Room editor"
                AsmPlmAuthority.CONFLICT -> "Objects · Needs attention"
                AsmPlmAuthority.UNAVAILABLE -> "Objects · Source unavailable"
            }, fontSize = fs.detail)
        }
        if (editable && onOpenAsm != null) TextButton(onClick = onOpenAsm) { Text("ASM", fontSize = fs.detail) }
    }
    if (!managed && authority.authority == AsmPlmAuthority.CONFLICT) {
        Text("Source and room data need review before building.", color = MaterialTheme.colorScheme.error, fontSize = fs.detail)
    }
    if (managed) managedState?.let { bridge.managedView(editor.currentRoomId, it).problem }?.let { problem ->
        Text("Object source needs attention: $problem", color = MaterialTheme.colorScheme.error, fontSize = fs.detail)
    }
    if (!open) return
    if (!editable) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(if (authority.authority == AsmPlmAuthority.ROOM_EDITOR) "Objects use room edits" else "Object source", fontSize = fs.heading) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(authority.explanation.orEmpty(), fontSize = fs.body)
                    if (authority.authority == AsmPlmAuthority.ROOM_EDITOR) Text(
                        "Click ASM on an object in the room to open its placement source. Supported room edits move into editable source together, preserving state differences.", fontSize = fs.body)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done", fontSize = fs.body) } },
            dismissButton = { if (onOpenAsm != null) TextButton(onClick = { open = false; onOpenAsm() }) {
                Text(if (authority.authority == AsmPlmAuthority.ROOM_EDITOR) "View ASM baseline" else "View ASM", fontSize = fs.body)
            } },
        )
    } else {
        LinkedPlmListDialog(bridge, pointer, if (managed) editor.currentRoomId to managedState.orEmpty() else null,
            onOpenAsm, { editor.refreshLinkedPlms() }, if (hasLegacyBinding && !managed) ({
            bridge.unlink(pointer)
            editor.notifyPlmSourceLinked()
            open = false
        }) else null, { open = false })
    }
}

@Composable
private fun LinkedPlmListDialog(bridge: AsmPlmSourceBridge, pointer: Int, managed: Pair<Int, String>?, onOpenAsm: (() -> Unit)?, onChanged: () -> Unit, onUnlink: (() -> Unit)?, onClose: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val view = managed?.let { bridge.managedView(it.first, it.second) } ?: bridge.view(pointer)
    var selection by remember { mutableStateOf<Pair<AsmPlmDocument, Int>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Objects · ASM fields", fontSize = fs.heading) },
        text = {
            Column(Modifier.widthIn(min = 380.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (managed == null) {
                    val consumers = bridge.catalog.consumers[pointer].orEmpty()
                    Text("${consumers.size} state(s) share this list: " + consumers.map { it.roomName }.distinct().joinToString(), fontSize = fs.detail)
                }
                Text(if (managed != null) "Changes edit this list in Bank \$8F. States sharing the list update together; use All states in the room view to change every list. Tile X/Y are zero-based."
                    else "Changes edit Bank \$8F directly. Tile X/Y are zero-based; list size stays fixed. Undo in ASM to restore room-editor ownership.", fontSize = fs.detail)
                view.problem?.let {
                    Text("Preview out of date: $it", color = MaterialTheme.colorScheme.error, fontSize = fs.body)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.body) }
                view.document?.let { document ->
                    document.entries.forEachIndexed { index, entry ->
                        TextButton(enabled = view.problem == null, onClick = { selection = document to index }) {
                            Text("${index + 1}. ${RomParser.plmDisplayName(entry.id)}  (${entry.x}, ${entry.y})", fontSize = fs.body)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", fontSize = fs.body) } },
        dismissButton = {
            Row {
                if (onUnlink != null) TextButton(onClick = { runCatching(onUnlink).onFailure { problem = it.message } }) {
                    Text("Use room editor", fontSize = fs.body)
                }
                if (onOpenAsm != null) TextButton(onClick = { onClose(); onOpenAsm() }) { Text("View ASM", fontSize = fs.body) }
            }
        },
    )
    selection?.let { (document, index) ->
        LinkedPlmEntryDialog(document, index, { entry ->
            if (managed == null) bridge.update(pointer, document, index, entry)
            else bridge.updateManaged(managed.first, managed.second, document, index, entry)
            onChanged()
        }) { selection = null }
    }
}

@Composable
private fun LinkedPlmEntryDialog(document: AsmPlmDocument, index: Int, onApply: (RomParser.PlmEntry) -> Unit, onClose: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val entry = document.entries[index]
    val fields = remember { mutableStateListOf("$" + entry.id.toString(16).uppercase(), entry.x.toString(), entry.y.toString(), "$" + entry.param.toString(16).uppercase()) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(RomParser.plmDisplayName(entry.id), fontSize = fs.heading) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PLM ID", "Tile X (zero-based)", "Tile Y (zero-based)", "Parameter").forEachIndexed { i, title ->
                    val symbolic = AsmPlmDocument.literal(document.text.substring(document.records[index].fields[i])) == null
                    OutlinedTextField(fields[i], { fields[i] = it }, label = { Text(if (symbolic) "$title · ASM expression" else title, fontSize = fs.detail) },
                        singleLine = true, readOnly = symbolic, textStyle = TextStyle(fontSize = fs.body))
                }
                Text("Decimal, \$hex or %binary. Symbolic fields remain editable in ASM.", fontSize = fs.detail)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.body) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val values = fields.map { requireNotNull(AsmPlmDocument.literal(it.trim())) { "Enter decimal, \$hex or %binary values." } }
                    onApply(RomParser.PlmEntry(values[0], values[1], values[2], values[3]))
                }.onSuccess { onClose() }.onFailure { problem = it.message }
            }) { Text("Apply", fontSize = fs.body) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", fontSize = fs.body) } },
    )
}
