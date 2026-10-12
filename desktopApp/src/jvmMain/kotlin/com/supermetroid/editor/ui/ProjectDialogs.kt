package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

internal fun pickProjectOrRom(): File? = pickProjectFile("Open SMEDIT project or ROM", true)

internal fun pickProjectFile(title: String, includeProjects: Boolean = false): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    try {
        dialog.setFilenameFilter { _, name ->
            File(name).extension.lowercase() in if (includeProjects) setOf("smedit", "smc", "sfc") else setOf("smc", "sfc")
        }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    } finally { dialog.dispose() }
}

@Composable
internal fun NewProjectDialog(
    initialRom: File?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, File, File) -> Unit,
) {
    var name by remember { mutableStateOf(initialRom?.nameWithoutExtension.orEmpty()) }
    var parent by remember { mutableStateOf(initialRom?.parentFile?.absolutePath ?: System.getProperty("user.home")) }
    var base by remember { mutableStateOf(initialRom?.absolutePath.orEmpty()) }
    val fs = LocalEditorTheme.current.fontSize.value
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(8.dp),
        title = { Text("New project", fontSize = fs.heading) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(parent, { parent = it }, label = { Text("Parent folder") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = !busy, shape = RoundedCornerShape(4.dp), onClick = {
                        val picker = JFileChooser(parent).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) parent = picker.selectedFile.absolutePath
                    }) { Text("Browse") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(base, { base = it }, label = { Text("Base ROM") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = !busy, shape = RoundedCornerShape(4.dp), onClick = {
                        pickProjectFile("Choose the base ROM")?.let { base = it.absolutePath }
                    }) { Text("Browse") }
                }
                Text("Creates ${File(parent, name.ifBlank { "Project name" }).path}\nCopies the original ROM without renaming it. ASM can be enabled later.", fontSize = fs.detail)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.body) }
            }
        },
        confirmButton = { Button(enabled = !busy && name.isNotBlank() && base.isNotBlank(), shape = RoundedCornerShape(4.dp), onClick = { onCreate(name.trim(), File(parent), File(base)) }) { Text(if (busy) "Creating…" else "Create") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun LocateProjectRomDialog(open: OpenProject, busy: Boolean, error: String?, onDismiss: () -> Unit, onLocate: (File) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val expected = open.project.baseRom
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(8.dp),
        title = { Text("Locate base ROM", fontSize = fs.heading) },
        text = { Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${open.file.nameWithoutExtension} needs ${expected?.originalFileName ?: File(open.project.romPath).name}.", fontSize = fs.body)
            Text(if (expected != null) "A renamed copy is fine. SMEDIT will verify its contents and copy it into the project folder. Use the original, not an exported build."
                else "This project has no recorded ROM fingerprint. Select its original base ROM carefully; this selection establishes its identity.", fontSize = fs.detail)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.body) }
        } },
        confirmButton = { Button(enabled = !busy, shape = RoundedCornerShape(4.dp), onClick = { pickProjectFile("Locate original base ROM")?.let(onLocate) }) { Text(if (busy) "Checking…" else "Locate ROM") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
