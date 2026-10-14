package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Import source sheets, not flattened compositions whose pixels can have several owners. */
internal fun importNativeSpritePng(file: File, sheet: NativeSpriteEditorState.Sheet): IntArray {
    val image = requireNotNull(ImageIO.read(file)) { "Could not read PNG" }
    require(image.width == sheet.width && image.height == sheet.height) {
        "Expected source sheet ${sheet.width}×${sheet.height}px, got ${image.width}×${image.height}px. Import the source PNG, not an assembled frame/animation sheet."
    }
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val opaqueColors = sheet.palette.filter { it ushr 24 != 0 }
    return IntArray(pixels.size) { i ->
        val pixel = pixels[i]
        if (pixel ushr 24 < 128) 0 else if (pixel in opaqueColors) pixel
        else opaqueColors.minBy { color ->
            val r = (pixel shr 16 and 255) - (color shr 16 and 255)
            val g = (pixel shr 8 and 255) - (color shr 8 and 255)
            val b = (pixel and 255) - (color and 255)
            r * r + g * g + b * b
        }
    }
}

internal fun exportNativeSpritePng(file: File, sheet: NativeSpriteEditorState.Sheet) =
    exportFramePng(SpriteAnimationFrame(sheet.pixels, sheet.width, sheet.height), file)

internal fun chooseSpriteSourcePng(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import Sprite Source PNG"
        fileFilter = FileNameExtensionFilter("PNG source sheet", "png")
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Existing frame PNG / timed GIF / animation sheet controls for every native workspace. */
@Composable
internal fun NativeSpriteAnimationPlayer(animation: SpriteAnimation?, modifier: Modifier = Modifier, previewSize: Int = 440) {
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun export(file: File?, write: (File) -> Unit) {
        if (file == null) return
        scope.launch {
            status = "Exporting ${file.name}…"
            status = runCatching { withContext(Dispatchers.IO) { write(file) } }.fold(
                onSuccess = { "Exported ${file.name}" }, onFailure = { "Export failed: ${it.message}" },
            )
        }
    }
    val name = exportSafeName(animation?.name ?: "sprite")
    BoxWithConstraints(modifier) {
        // Reserve space for transport/export controls instead of letting a fixed preview push them out of view.
        val controlsHeight = 200 + LocalEditorTheme.current.fontSize.value.detail.value * 3
        val fittedSize = if (maxHeight.value.isFinite()) minOf(previewSize, (maxHeight.value - controlsHeight).toInt().coerceAtLeast(96)) else previewSize
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            AnimationPlayer(animation, previewSize = fittedSize,
                onExportPng = { frame, index -> export(choosePngFile("Export Sprite Frame", "${name}_${index + 1}.png")) { exportFramePng(frame, it) } },
                onExportGif = { frames -> export(chooseGifFile("Export Sprite Animation", "$name.gif")) { exportAnimationGif(frames, it) } },
                onExportSheet = { frames -> export(choosePngFile("Export Animation Sheet", "${name}_animation.png")) { exportAnimationSheet(frames, it) } },
            )
            if (status.isNotBlank()) Text(status, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
