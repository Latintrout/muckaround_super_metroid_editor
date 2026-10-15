package com.supermetroid.editor.asm

internal data class AsmSourceInclude(val path: String, val range: IntRange)

private val ASM_SOURCE_INCLUDE_REGEX = Regex("^\\s*incsrc\\s+(\"[^\"\\r\\n]+\"|[^\\s\";]+)\\s*$", RegexOption.IGNORE_CASE)
private val ASM_SOURCE_DRIVE_REGEX = Regex("^[A-Za-z]:")

/** Literal includes only: never guess which file an Asar expression will expand to. */
internal fun asmSourceInclude(line: String): AsmSourceInclude? {
    val code = asmCodeBeforeComment(line)
    val match = ASM_SOURCE_INCLUDE_REGEX.matchEntire(code) ?: return null
    val operand = requireNotNull(match.groups[1])
    return AsmSourceInclude(operand.value.removeSurrounding("\""), operand.range)
}

/** Uses live page text, not the possibly stale saved source index. */
internal fun asmSourceIncludeAt(text: String, offset: Int): AsmSourceInclude? {
    if (offset !in 0..text.length) return null
    val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
    val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val include = asmSourceInclude(text.substring(start, end)) ?: return null
    return include.takeIf { offset - start in it.range.first..it.range.last + 1 }
}

internal fun AsmReferenceIndex.resolveSourceInclude(fileId: String, path: String): AsmSourceFile? {
    val normalized = path.replace('\\', '/')
    if (normalized.startsWith('/') || ASM_SOURCE_DRIVE_REGEX.containsMatchIn(normalized)) return null
    val parts = ArrayDeque<String>()
    val relative = fileId.substringBeforeLast('/', "").let { if (it.isEmpty()) normalized else "$it/$normalized" }
    for (part in relative.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty()) return null else parts.removeLast()
        else -> parts.addLast(part)
    }
    return file(parts.joinToString("/"))
}
