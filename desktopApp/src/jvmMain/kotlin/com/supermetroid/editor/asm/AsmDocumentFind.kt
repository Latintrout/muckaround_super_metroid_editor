package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class AsmDocumentMatch(val offset: Int, val length: Int, val line: Int, val column: Int)

internal fun asmDocumentLineOffsets(lines: List<String>): IntArray {
    var offset = 0
    return IntArray(lines.size) { line -> offset.also { offset += lines[line].length + 1 } }
}

/** Literal, document-local search. Includes comments and unsaved text; no source index required. */
internal fun findAsmDocumentText(text: String, query: String, matchCase: Boolean = false): List<AsmDocumentMatch> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<AsmDocumentMatch>()
    var cursor = 0
    var line = 0
    var lineStart = 0
    var scanned = 0
    while (cursor <= text.length - query.length) {
        val offset = text.indexOf(query, cursor, ignoreCase = !matchCase)
        if (offset < 0) break
        while (scanned < offset) {
            if (text[scanned] == '\n') { line++; lineStart = scanned + 1 }
            scanned++
        }
        matches += AsmDocumentMatch(offset, query.length, line, offset - lineStart)
        cursor = offset + query.length
    }
    return matches
}

/** Each split view owns its own find session, even when the document is shared. */
internal class AsmDocumentFindState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var matches by mutableStateOf(emptyList<AsmDocumentMatch>())
        private set
    var selectedIndex by mutableStateOf(-1)
        private set
    var focusSerial by mutableStateOf(0L)
        private set
    val selected: AsmDocumentMatch? get() = matches.getOrNull(selectedIndex)

    fun matchesInRange(start: Int, end: Int): List<AsmDocumentMatch> {
        if (start >= end) return emptyList()
        // Source panes are virtualized. Do not scan thousands of off-screen hits
        // for every visible line while painting a common query such as "0".
        var low = 0
        var high = matches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (matches[mid].offset + matches[mid].length <= start) low = mid + 1 else high = mid
        }
        var last = low
        while (last < matches.size && matches[last].offset < end) last++
        return matches.subList(low, last)
    }

    fun show() { open = true; focusSerial++ }
    fun close() { open = false; matches = emptyList(); selectedIndex = -1 }
    fun updateMatches(result: List<AsmDocumentMatch>, restart: Boolean) {
        val previous = selected?.offset ?: 0
        matches = result
        selectedIndex = when {
            result.isEmpty() -> -1
            restart -> 0
            else -> result.indexOfFirst { it.offset >= previous }.takeIf { it >= 0 } ?: result.lastIndex
        }
    }
    fun step(direction: Int): AsmDocumentMatch? {
        if (matches.isEmpty()) return null
        selectedIndex = Math.floorMod(selectedIndex + direction, matches.size)
        return selected
    }
}
