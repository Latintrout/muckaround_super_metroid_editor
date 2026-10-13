package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmPlmPlacementSnapshot
import com.supermetroid.editor.asm.formatSnesAddress
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser

/** A small inspection fallback, not a second editable/generated ASM listing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AsmPlmPlacementPane(
    snapshot: AsmPlmPlacementSnapshot,
    onBehavior: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val entry = snapshot.entry
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${snapshot.roomName} · Placement", fontSize = fs.heading, fontWeight = FontWeight.SemiBold)
                Text("${snapshot.stateName} · ${formatSnesAddress(RomConstants.BANK_ROOM_DATA or snapshot.roomId)}",
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(RomParser.plmDisplayName(entry.id, entry.param), fontSize = fs.heading, fontWeight = FontWeight.SemiBold)
            Text(snapshot.reason, fontSize = fs.body)
            Text("Room preview at selection · Read-only", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((label, value) in listOf(
                        "PLM type" to "$${entry.id.toString(16).uppercase().padStart(4, '0')}",
                        "Tile X" to "${entry.x} ($${entry.x.toString(16).uppercase().padStart(2, '0')})",
                        "Tile Y" to "${entry.y} ($${entry.y.toString(16).uppercase().padStart(2, '0')})",
                        "Parameter" to "$${entry.param.toString(16).uppercase().padStart(4, '0')}",
                    )) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, fontSize = fs.body, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Text("These are the selected room's values, not a build result. Return to the room to edit this placement.",
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            semanticAsmAddress(RomConstants.BANK_PLM, entry.id)?.let { address ->
                OutlinedButton(onClick = { onBehavior(address) }, shape = RoundedCornerShape(4.dp)) {
                    Text("Behavior · ${formatSnesAddress(address)}", fontSize = fs.body)
                }
                Text("Shared setup and instructions for this PLM type; not its placement in this room.",
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
