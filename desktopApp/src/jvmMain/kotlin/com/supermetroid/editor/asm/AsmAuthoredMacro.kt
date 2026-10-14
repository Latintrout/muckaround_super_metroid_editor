package com.supermetroid.editor.asm

/** Small shared envelope reader for typed resource codecs, not a general Asar interpreter. */
internal fun authoredMacroBody(text: String, name: String): String {
    val starts = Regex("(?m)^macro ${Regex.escape(name)}\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n").findAll(text).toList()
    require(starts.size == 1) { "Expected one authored macro $name." }
    val start = starts.single().range.last + 1
    val end = Regex("(?m)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").find(text, start)?.range?.first
        ?: error("Missing endmacro for $name.")
    return text.substring(start, end)
}
