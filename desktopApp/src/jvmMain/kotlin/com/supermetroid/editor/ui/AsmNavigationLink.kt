package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.formatSnesAddress
import com.supermetroid.editor.asm.snesLoRomToPc
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser

internal fun semanticAsmAddress(bankBase: Int, pointer: Int): Int? =
    (bankBase or (pointer and 0xFFFF)).takeIf {
        pointer in 0x8000..0xFFFE && snesLoRomToPc(it) != null
    }

@Composable
internal fun AsmNavigationLink(
    snesAddress: Int,
    onNavigateToAsm: (Int) -> Unit,
    label: String = "ASM ${formatSnesAddress(snesAddress)}",
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        fontSize = LocalEditorTheme.current.fontSize.value.detail,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable { onNavigateToAsm(snesAddress) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Placement is the primary action; the shared PLM implementation is deliberately secondary. */
@Composable
internal fun AsmPlmNavigationLinks(
    entry: RomParser.PlmEntry,
    onPlacement: ((RomParser.PlmEntry) -> Unit)?,
    onBehavior: ((Int) -> Unit)?,
) {
    val address = semanticAsmAddress(RomConstants.BANK_PLM, entry.id)
    var expanded by remember(entry) { mutableStateOf(false) }
    val fs = LocalEditorTheme.current.fontSize.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onPlacement != null) {
            Text(
                "ASM", fontSize = fs.detail, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onPlacement(entry) }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (address != null && onBehavior != null) {
            Box {
                Text(
                    "▾", fontSize = fs.detail, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = true }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (onPlacement != null) DropdownMenuItem(
                        text = { Text("Placement in this room", fontSize = fs.body) },
                        onClick = { expanded = false; onPlacement(entry) },
                    )
                    DropdownMenuItem(
                        text = { Text("Behavior · ${formatSnesAddress(address)}", fontSize = fs.body) },
                        onClick = { expanded = false; onBehavior(address) },
                    )
                }
            }
        }
    }
}
