package com.supermetroid.editor.asm

internal enum class AsmCompletionKind {
    INSTRUCTION,
    DIRECTIVE,
    MACRO,
    LOCAL_LABEL,
    LABEL,
    DEFINE,
}

internal data class AsmCompletionItem(
    val text: String,
    val kind: AsmCompletionKind,
    val detail: String,
    val sortPriority: Int,
    val replacement: String = text,
    val caretBacktrack: Int = 0,
)

internal data class AsmCompletionResult(
    val prefix: String,
    val replaceStart: Int,
    val replaceEnd: Int,
    val items: List<AsmCompletionItem>,
)

internal data class AsmMacroCallHint(
    val macro: AsmMacro,
    val argumentIndex: Int,
) {
    val parameterIndex: Int?
        get() = when {
            macro.parameters.isEmpty() -> null
            macro.variadic -> argumentIndex.coerceAtMost(macro.parameters.lastIndex)
            else -> argumentIndex.takeIf { it in macro.parameters.indices }
        }
}

/**
 * Finds the innermost unfinished macro call at the caret. Calls may contain
 * nested parentheses and quoted commas; comments never produce hints.
 */
internal object AsmMacroCallAnalyzer {
    private val callStart = Regex("%([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

    fun hint(
        index: AsmReferenceIndex,
        text: String,
        caretOffset: Int,
        excludedFileIds: Set<String> = emptySet(),
    ): AsmMacroCallHint? {
        val caret = caretOffset.coerceIn(0, text.length)
        val lineStart = if (caret == 0) {
            0
        } else {
            text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
        }
        val beforeCaret = text.substring(lineStart, caret)
        val code = asmCodeBeforeComment(beforeCaret)
        if (code.length != beforeCaret.length) return null

        return callStart.findAll(code).toList().asReversed().firstNotNullOfOrNull { match ->
            if (asmOffsetInsideString(code, match.range.first)) return@firstNotNullOfOrNull null
            val macro = index.resolveMacro("%${match.groupValues[1]}")
                ?.takeUnless { it.fileId in excludedFileIds }
                ?: return@firstNotNullOfOrNull null
            val openParenthesis = match.range.last
            val argumentIndex = activeArgumentIndex(code, openParenthesis) ?: return@firstNotNullOfOrNull null
            AsmMacroCallHint(macro, argumentIndex)
        }
    }

    private fun activeArgumentIndex(code: String, openParenthesis: Int): Int? {
        var depth = 1
        var argumentIndex = 0
        var quoted = false
        var escaped = false
        for (cursor in openParenthesis + 1 until code.length) {
            val character = code[cursor]
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                quoted -> Unit
                character == '(' -> depth++
                character == ')' -> {
                    depth--
                    if (depth == 0) return null
                }
                character == ',' && depth == 1 -> argumentIndex++
            }
        }
        return argumentIndex
    }
}

/**
 * Lightweight, scope-aware completion for the bounded source page currently
 * owned by the editor. It uses the already-built project index and never
 * reparses an 18k-line bank while the user types.
 */
internal object AsmCompletionEngine {
    private val tokenCharacters = { character: Char ->
        character.isLetterOrDigit() || character == '_' || character == '.' || character == '!' || character == '%'
    }

    fun complete(
        index: AsmReferenceIndex,
        fileId: String,
        pageStartLine: Int,
        text: String,
        caretOffset: Int,
        explicit: Boolean,
        excludedFileIds: Set<String> = emptySet(),
        limit: Int = 12,
    ): AsmCompletionResult? {
        require(limit > 0) { "Completion limit must be positive" }
        val caret = caretOffset.coerceIn(0, text.length)
        val lineStart = if (caret == 0) {
            0
        } else {
            text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
        }
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val column = caret - lineStart
        val commentStart = asmCompletionCommentStart(line)
        if (column > commentStart || asmOffsetInsideString(line, column)) return null

        var replaceStart = caret
        while (replaceStart > lineStart && tokenCharacters(text[replaceStart - 1])) replaceStart--
        var replaceEnd = caret
        while (replaceEnd < lineStart + commentStart && tokenCharacters(text[replaceEnd])) replaceEnd++
        val prefix = text.substring(replaceStart, caret)
        if (!explicit && prefix.length < 2) return null

        val lineIndex = pageStartLine + text.substring(0, lineStart).count { it == '\n' }
        val beforeToken = text.substring(lineStart, replaceStart)
        val instructionPosition = beforeToken.matches(
            Regex("\\s*(?:(?:[A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z0-9_]+):\\s*)?"),
        )
        val candidates = if (instructionPosition) {
            if (prefix.startsWith('%')) {
                macroCandidates(index, prefix, excludedFileIds)
            } else if (prefix.startsWith('.') || prefix.startsWith('!')) {
                symbolCandidates(index, fileId, lineIndex, prefix, excludedFileIds)
            } else {
                instructionCandidates(prefix) + directiveCandidates(prefix)
            }
        } else {
            symbolCandidates(index, fileId, lineIndex, prefix, excludedFileIds) +
                if (explicit) instructionCandidates(prefix) + directiveCandidates(prefix) else emptyList()
        }
        val distinct = candidates.asSequence()
            .filterNot { it.text == prefix }
            .distinctBy { it.text }
            .sortedWith(compareBy<AsmCompletionItem>({ it.sortPriority }, { it.text.lowercase() }, { it.text }))
            .take(limit)
            .toList()
        if (distinct.isEmpty()) return null
        return AsmCompletionResult(prefix, replaceStart, replaceEnd, distinct)
    }

    private fun instructionCandidates(prefix: String): List<AsmCompletionItem> {
        val mnemonicPrefix = prefix.substringBefore('.').uppercase()
        val hasSizeSuffix = '.' in prefix
        val sizePrefix = prefix.substringAfter('.', missingDelimiterValue = "").uppercase()
        return AsmInstructionReference.instructions.values.asSequence()
            .filter { instruction ->
                if (hasSizeSuffix) instruction.mnemonic == mnemonicPrefix
                else instruction.mnemonic.startsWith(mnemonicPrefix, ignoreCase = true)
            }
            .flatMap { instruction ->
                if (hasSizeSuffix) {
                    ASM_INSTRUCTION_SIZE_SUFFIXES.asSequence()
                        .filter { it.startsWith(sizePrefix) }
                        .map { suffix ->
                            AsmCompletionItem(
                                text = "${instruction.mnemonic}.$suffix",
                                kind = AsmCompletionKind.INSTRUCTION,
                                detail = "${instruction.name} · $suffix operand size",
                                sortPriority = 20,
                            )
                        }
                } else {
                    sequenceOf(
                        AsmCompletionItem(
                            text = instruction.mnemonic,
                            kind = AsmCompletionKind.INSTRUCTION,
                            detail = instruction.name,
                            sortPriority = 20,
                        ),
                    )
                }
            }
            .toList()
    }

    private fun directiveCandidates(prefix: String): List<AsmCompletionItem> {
        val normalized = prefix.lowercase()
        return ASM_COMPLETION_DIRECTIVES.asSequence()
            .filter { it.startsWith(normalized) }
            .map { directive ->
                AsmCompletionItem(
                    text = directive,
                    kind = AsmCompletionKind.DIRECTIVE,
                    detail = "Asar directive",
                    sortPriority = 30,
                )
            }
            .toList()
    }

    private fun macroCandidates(
        index: AsmReferenceIndex,
        prefix: String,
        excludedFileIds: Set<String>,
    ): List<AsmCompletionItem> =
        index.macros.asSequence()
            .filterNot { it.fileId in excludedFileIds }
            .filter { macro -> "%${macro.name}".startsWith(prefix, ignoreCase = true) }
            .map { macro ->
                AsmCompletionItem(
                    text = "%${macro.name}",
                    kind = AsmCompletionKind.MACRO,
                    detail = "${macro.signature} · ${macro.fileId}:${macro.lineIndex + 1}",
                    sortPriority = 0,
                    replacement = "%${macro.name}()",
                    caretBacktrack = 1,
                )
            }
            .toList()

    private fun symbolCandidates(
        index: AsmReferenceIndex,
        fileId: String,
        lineIndex: Int,
        prefix: String,
        excludedFileIds: Set<String>,
    ): List<AsmCompletionItem> {
        val source = index.file(fileId) ?: return emptyList()
        val scope = source.scopeByLine.getOrNull(lineIndex)
        return index.labels.asSequence()
            .filterNot { it.fileId in excludedFileIds }
            .filter { label ->
                when {
                    prefix.startsWith('.') -> label.fileId == fileId && label.localScope == scope && label.name.startsWith('.')
                    prefix.startsWith('!') -> label.localScope == null && label.name.startsWith('!')
                    else -> label.localScope == null && !label.name.startsWith('!')
                }
            }
            .filter { it.name.startsWith(prefix, ignoreCase = true) }
            .map { label ->
                val kind = when {
                    label.name.startsWith('.') -> AsmCompletionKind.LOCAL_LABEL
                    label.name.startsWith('!') -> AsmCompletionKind.DEFINE
                    else -> AsmCompletionKind.LABEL
                }
                val sourceFile = index.file(label.fileId)
                val location = "${sourceFile?.displayName ?: label.fileId}:${label.lineIndex + 1}"
                val priority = when {
                    kind == AsmCompletionKind.LOCAL_LABEL -> 0
                    label.fileId == fileId -> 5
                    kind == AsmCompletionKind.DEFINE -> 8
                    else -> 10
                }
                AsmCompletionItem(label.name, kind, location, priority)
            }
            .toList()
    }
}

private fun asmCompletionCommentStart(line: String): Int {
    var quoted = false
    var escaped = false
    line.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
            character == ';' && !quoted -> return index
        }
    }
    return line.length
}

private val ASM_COMPLETION_DIRECTIVES = listOf(
    "assert", "base", "cleartable", "db", "dd", "dl", "dw", "else", "elseif", "endif",
    "endmacro", "endstruct", "endwhile", "fill", "fillbyte", "hirom", "if", "incbin", "incsrc",
    "lorom", "macro", "math", "namespace", "org", "pad", "padbyte", "print", "reset", "skip",
    "struct", "table", "warnpc", "while",
)

private val ASM_INSTRUCTION_SIZE_SUFFIXES = listOf("B", "W", "L")
