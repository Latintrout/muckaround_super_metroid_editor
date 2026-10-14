package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ScrollCommand

/** Transient source input; identity is a label, never an allocated ROM address. */
data class SourceScrollResource(val identity: String, val commands: List<ScrollCommand>)

fun validateScrollCommands(commands: List<ScrollCommand>, screenCount: Int) {
    require(screenCount in 1..128) { "Scroll commands require a supported room screen count." }
    require(commands.all { it.screenIndex in 0 until screenCount && it.screenIndex < 0x80 && it.scrollValue in 0..2 }) {
        "Scroll commands require an in-room screen index below \$80 and a Red/Blue/Green value (0/1/2)."
    }
    require(commands.map { it.screenIndex }.distinct().size == commands.size) {
        "Repeated screen writes need review before visual editing; no command was discarded."
    }
}

fun serializeScrollCommands(commands: List<ScrollCommand>): ByteArray {
    validateScrollCommands(commands, 128)
    return (commands.flatMap { listOf(it.screenIndex.toByte(), it.scrollValue.toByte()) } + 0x80.toByte()).toByteArray()
}
