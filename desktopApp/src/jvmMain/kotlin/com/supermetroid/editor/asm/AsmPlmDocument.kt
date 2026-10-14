package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser

/** A lossless, deliberately bounded lens over an authored PLM declaration, not generated ASM. */
internal data class AsmPlmDocument(
    val text: String,
    val label: String,
    val lineIndex: Int,
    val records: List<Record>,
) {
    data class Record(val entry: RomParser.PlmEntry, val fields: List<IntRange>, val scrollResource: String? = null)
    val entries: List<RomParser.PlmEntry> get() = records.map { it.entry }

    /** Only changed scalar tokens are rewritten. All other bytes (including comments/CRLF) survive. */
    fun replace(index: Int, value: RomParser.PlmEntry): String {
        validateEntry(value)
        val record = records[index]
        val before = record.entry.values()
        val after = value.values()
        var result = text
        for (field in 3 downTo 0) {
            if (before[field] == after[field]) continue
            val range = record.fields[field]
            val old = text.substring(range)
            require(literal(old) != null) {
                "${label}: '${old}' is symbolic. Edit that expression in ASM before changing this field visually."
            }
            val width = if (field == 1 || field == 2) 2 else 4
            val token = when {
                old.startsWith('$') -> "$" + after[field].toString(16).uppercase().padStart(width, '0')
                old.startsWith('%') -> "%" + after[field].toString(2).padStart(width * 4, '0')
                else -> after[field].toString()
            }
            result = result.replaceRange(range, token)
        }
        return result
    }

    companion object {
        private val labelPattern = Regex("(?m)^([A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*")
        private val entryPattern = Regex("%PLMPopEntry\\(\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+)\\s*\\)")

        fun literal(token: String): Int? = when {
            token.matches(Regex("\\$[0-9a-fA-F]+")) -> token.drop(1).toIntOrNull(16)
            token.matches(Regex("%[01]+")) -> token.drop(1).toIntOrNull(2)
            token.matches(Regex("[0-9]+")) -> token.toIntOrNull()
            else -> null
        }

        fun validateEntry(entry: RomParser.PlmEntry) {
            require(entry.id in 1..0xFFFF && entry.x in 0..255 && entry.y in 0..255 && entry.param in 0..0xFFFF) {
                "PLMs require a nonzero word ID, byte X/Y coordinates, and a word parameter."
            }
        }

        fun parse(text: String, label: String, symbols: Map<String, Int> = emptyMap()): AsmPlmDocument {
            return Source(text).parse(label, symbols)
        }

        /** Index a bank once per revision, not once per room on every recomposition. */
        class Source(val text: String) {
            private val labels = labelPattern.findAll(text).groupBy { it.groupValues[1] }
            private val addressLabels by lazy {
                val addressPattern = Regex(";8F([0-9A-Fa-f]{4});")
                buildMap {
                    for (declaration in labels.values.flatten()) {
                        if (!declaration.groupValues[1].startsWith("PLMPopulation_")) continue
                        var address = addressPattern.find(declaration.value)
                        var start = text.indexOf('\n', declaration.range.last + 1)
                        // Only the first emitted line may supply a missing address annotation.
                        while (address == null && start >= 0 && start + 1 < text.length) {
                            start++
                            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                            val line = text.substring(start, end)
                            if (line.substringBefore(';').isNotBlank()) {
                                address = addressPattern.find(line)
                                break
                            }
                            start = if (end == text.length) -1 else end
                        }
                        address?.groupValues?.get(1)?.toIntOrNull(16)?.let { pointer ->
                            put(pointer, declaration.groupValues[1])
                        }
                    }
                }
            }

            fun parse(label: String, symbols: Map<String, Int>): AsmPlmDocument =
                parseDeclaration(text, label, symbols, labels[label].orEmpty())

            fun labelAtPointer(pointer: Int): String? {
                return addressLabels[pointer]
            }
        }

        private fun parseDeclaration(text: String, label: String, symbols: Map<String, Int>, labels: List<MatchResult>): AsmPlmDocument {
            require(labels.size == 1) { "Expected one definition of $label; found ${labels.size}." }
            val declaration = labels.single()
            val records = mutableListOf<Record>()
            var offset = text.indexOf('\n', declaration.range.last + 1).let { if (it < 0) text.length else it + 1 }
            while (offset < text.length) {
                val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
                val line = text.substring(offset, end)
                val code = line.substringBefore(';').trim()
                if (code.matches(Regex("dw\\s+(\\$0+|0+|%0+)", RegexOption.IGNORE_CASE))) {
                    return AsmPlmDocument(text, label, text.take(declaration.range.first).count { it == '\n' }, records)
                }
                if (code.isNotEmpty()) {
                    val match = entryPattern.matchEntire(code)
                        ?: error("$label: unsupported or incomplete PLM declaration. Expected %PLMPopEntry(ID, X, Y, Param) and dw \$0000.")
                    val start = offset + line.indexOf(code)
                    val ranges = (1..4).map { field ->
                        val group = checkNotNull(match.groups[field])
                        val leading = group.value.length - group.value.trimStart().length
                        val token = group.value.trim()
                        start + group.range.first + leading until start + group.range.first + leading + token.length
                    }
                    val values = ranges.map { range ->
                        val token = text.substring(range)
                        literal(token) ?: symbols[token]
                        ?: error("$label: '$token' is not a supported literal or verified source symbol.")
                    }
                    val entry = RomParser.PlmEntry(values[0], values[1], values[2], values[3])
                    validateEntry(entry)
                    records += Record(entry, ranges)
                }
                offset = end + 1
            }
            error("$label: missing dw \$0000 terminator.")
        }

        /** Original pinned address annotations locate declarations; never infer ownership by proximity. */
        fun labelAtPointer(text: String, pointer: Int): String? {
            return Source(text).labelAtPointer(pointer)
        }
    }
}

private fun RomParser.PlmEntry.values() = listOf(id, x, y, param)
