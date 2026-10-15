package com.supermetroid.editor.asm

/** A verified native consumer, not a room guessed from a filename or a nearby address. */
internal data class AsmRoomSourceLink(
    val roomId: Int,
    val roomName: String,
    val kind: RoomAsmSourceKind,
    val sourceStateIndex: Int? = null,
    val detail: String = "Room-wide resource",
) {
    val title: String get() = "$roomName · ${kind.title}"
}

/** Native source context is usable only while the displayed text matches the indexed document. */
internal fun asmRoomSourceLinksAt(
    index: AsmReferenceIndex,
    source: AsmSourceFile,
    lineIndex: Int,
    semanticIndex: AsmSemanticIndex?,
    displayedText: String? = null,
): List<AsmRoomSourceLink> {
    if (semanticIndex == null || lineIndex !in source.lines.indices) return emptyList()
    // readLines omits the final empty line; tolerate only that and CRLF normalization.
    if (displayedText != null && displayedText.replace("\r\n", "\n").trimEnd('\n') != source.lines.joinToString("\n").trimEnd('\n')) return emptyList()
    val scope = source.scopePathByLine.getOrNull(lineIndex)?.firstOrNull() ?: return emptyList()
    // Native layout labels often have no recorded address: resolve their actual whole-file
    // incbin through the asset manifest instead. Reject sliced/mixed payloads, not just names.
    var start = lineIndex
    var end = lineIndex + 1
    while (start > 0 && source.scopePathByLine[start - 1].firstOrNull() == scope) start--
    while (end < source.lines.size && source.scopePathByLine[end].firstOrNull() == scope) end++
    val code = source.lines.subList(start, end).map { it.substringBefore(';').trim() }
        .filter { it.isNotEmpty() && !it.endsWith(':') }
    val include = code.singleOrNull()?.let { Regex("incbin\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).matchEntire(it) }
    include?.groupValues?.get(1)?.let(index::resolveAsset)?.let { asset ->
        return semanticIndex.roomSourcesFor(asset.range.snesAddress)
    }
    val anchor = index.addressAtlas.contextAt(source.id, lineIndex) ?: return emptyList()
    if (source.scopePathByLine.getOrNull(anchor.lineIndex)?.firstOrNull() != scope) return emptyList()
    return semanticIndex.roomSourcesFor(anchor.snesAddress)
}
