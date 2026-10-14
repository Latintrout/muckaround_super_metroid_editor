package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.SourceSaveResource

/** Same authored bank buffer as placements; the build supplies the fixed bank-$80 slot address. */
internal object AsmSaveStationSource {
    private val identity = Regex("SmeditObjects_([0-9A-F]+)_Save_([0-5])_([0-7])")
    val notes = listOf("Room · bank \$8F", "Incoming door · bank \$83", "Door BTS", "Camera X", "Camera Y", "Samus Y · screen top", "Samus X · screen center")
    fun name(roomId: Int, area: Int, slot: Int) = "${AsmRoomObjectSource.prefix(roomId)}Save_${area}_$slot"

    fun parse(name: String, body: String, symbols: Map<String, Int> = emptyMap()): SourceSaveResource {
        val match = requireNotNull(identity.matchEntire(name)) { "Unsupported save resource identity $name." }
        val words = body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            require(line.startsWith("dw ", true)) { "$name: expected seven dw fields for the load-station record." }
            line.drop(3).split(',').map { token ->
                AsmPlmDocument.literal(token.trim()) ?: symbols[token.trim()] ?: error("$name: unsupported save field '${token.trim()}'.")
            }
        }.toList()
        return SourceSaveResource(name, match.groupValues[1].toInt(16), match.groupValues[2].toInt(), match.groupValues[3].toInt(), words)
            .also(SourceSaveResource::validate)
    }

    fun render(resource: SourceSaveResource): String {
        resource.validate()
        require(resource.identity == name(resource.ownerRoomId, resource.area, resource.slot))
        return buildString {
            appendLine("macro ${resource.identity}()")
            if (resource.cleared) appendLine("    ; Released save slot · area ${resource.area}, slot ${resource.slot}")
            resource.words.forEachIndexed { index, word -> appendLine("    dw \$${word.toString(16).uppercase().padStart(4, '0')} ; ${notes[index]}") }
            appendLine("endmacro")
        }
    }
}
