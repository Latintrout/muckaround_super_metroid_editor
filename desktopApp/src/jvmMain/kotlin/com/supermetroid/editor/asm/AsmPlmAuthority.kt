package com.supermetroid.editor.asm

/** Authority is determined from the immutable baseline, never by which editor was opened last. */
internal enum class AsmPlmAuthority { UNCHANGED, ROOM_EDITOR, SOURCE, CONFLICT, UNAVAILABLE }

internal data class AsmPlmAuthorityView(
    val authority: AsmPlmAuthority,
    val document: AsmPlmDocument? = null,
    val explanation: String? = null,
)
