package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmPlmDocument
import com.supermetroid.editor.asm.AsmPlmAuthority
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.data.ProjectRomBuildMode

/** Normal visual object fields in either mode; source navigation stays a separate action. */
@Composable
internal fun RoomObjectsPanel(editor: EditorState, pointer: Int, onOpenAsm: (() -> Unit)?) {
    val sourceMode = editor.project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE
    val bridge = editor.asmPlmSourceBridge
    val fs = LocalEditorTheme.current.fontSize.value
    // Read the edit version for GUI ownership changes; the bridge observes the source revision itself.
    editor.editVersion
    val managed = editor.hasManagedPlms()
    val managedState = editor.currentPlmStateId()
    val authority = bridge?.takeIf { sourceMode }?.authority(pointer)
    val editable = managed || authority?.authority in setOf(AsmPlmAuthority.UNCHANGED, AsmPlmAuthority.SOURCE)
    val hasLegacyBinding = editor.project.asmWorkspace.plmSourceBindings.any { it.pointer == pointer }
    var open by remember(editor.currentRoomId, editor.currentStateIndex) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (sourceMode && authority?.authority == AsmPlmAuthority.ROOM_EDITOR &&
                !editor.ensureCurrentPlmSource(bridge.catalog.parser.readRoomHeader(editor.currentRoomId)?.name ?: "Room")) return@TextButton
            open = true
        }, enabled = !sourceMode || bridge != null, modifier = Modifier.height(26.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("Edit Objects", fontSize = fs.detail)
        }
        Spacer(Modifier.weight(1f))
        if (editor.project.asmWorkspace.enabled && onOpenAsm != null) Text("ASM", fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpenAsm).padding(horizontal = 4.dp, vertical = 1.dp))
    }
    if (!managed && authority?.authority == AsmPlmAuthority.CONFLICT) {
        Text("Source and room data need review before building.", color = MaterialTheme.colorScheme.error, fontSize = fs.detail)
    }
    if (managed) managedState?.let { bridge?.managedView(editor.currentRoomId, it)?.problem }?.let { problem ->
        Text("Object source needs attention: $problem", color = MaterialTheme.colorScheme.error, fontSize = fs.detail)
    }
    if (!open) return
    if (!sourceMode) {
        RoomObjectListDialog(editor, { open = false })
        return
    }
    if (bridge == null || authority == null) return
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
        title = { Text("Edit Objects", fontSize = fs.heading) },
        text = {
            Column(Modifier.widthIn(min = 380.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (managed == null) {
                    val consumers = bridge.catalog.consumers[pointer].orEmpty()
                    Text("${consumers.size} state(s) share this list: " + consumers.map { it.roomName }.distinct().joinToString(), fontSize = fs.detail)
                }
                Text(if (managed != null) "States sharing this list update together. Tile X/Y are zero-based."
                    else "Shared lists update together. Tile X/Y are zero-based.", fontSize = fs.detail)
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
    RoomObjectEntryDialog(document.entries[index], (0..3).map { AsmPlmDocument.literal(document.text.substring(document.records[index].fields[it])) == null },
        onApply, onClose)
}

private data class RoomObjectSelection(val roomId: Int, val stateId: String?, val entries: List<RomParser.PlmEntry>, val index: Int)

@Composable
private fun RoomObjectListDialog(editor: EditorState, onClose: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    editor.editVersion
    val entries = editor.workingPlms.toList()
    var selection by remember { mutableStateOf<RoomObjectSelection?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit Objects", fontSize = fs.heading) }, text = {
        Column(Modifier.widthIn(min = 380.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("Edits keep each object's state scope. Tile X/Y are zero-based.", fontSize = fs.detail)
            entries.forEachIndexed { index, entry ->
                TextButton(onClick = { selection = RoomObjectSelection(editor.currentRoomId, editor.currentPlmStateId(), entries, index) }) {
                    Text("${index + 1}. ${RomParser.plmDisplayName(entry.id)}  (${entry.x}, ${entry.y})", fontSize = fs.body)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done", fontSize = fs.body) } })
    selection?.let { (roomId, stateId, expected, index) ->
        RoomObjectEntryDialog(expected[index], listOf(false, false, false, false), { entry ->
            editor.updateRoomObjectFields(roomId, stateId, expected, index, entry)
        }) { selection = null }
    }
}

@Composable
private fun RoomObjectEntryDialog(entry: RomParser.PlmEntry, readOnlyFields: List<Boolean>, onApply: (RomParser.PlmEntry) -> Unit, onClose: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val fields = remember { mutableStateListOf("$" + entry.id.toString(16).uppercase(), entry.x.toString(), entry.y.toString(), "$" + entry.param.toString(16).uppercase()) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(RomParser.plmDisplayName(entry.id), fontSize = fs.heading) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PLM ID", "Tile X (zero-based)", "Tile Y (zero-based)", "Parameter").forEachIndexed { i, title ->
                    val symbolic = readOnlyFields[i]
                    OutlinedTextField(fields[i], { fields[i] = it }, label = { Text(if (symbolic) "$title · Expression" else title, fontSize = fs.detail) },
                        singleLine = true, readOnly = symbolic, textStyle = TextStyle(fontSize = fs.body))
                }
                Text("Decimal, \$hex or %binary." + if (readOnlyFields.any { it }) " Expressions are read-only here." else "", fontSize = fs.detail)
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
