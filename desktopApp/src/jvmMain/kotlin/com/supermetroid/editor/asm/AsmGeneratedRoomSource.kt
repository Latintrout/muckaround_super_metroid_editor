package com.supermetroid.editor.asm

/** A build artifact, deliberately absent from the editable source index/buffers.
 * This indexes only SMEDIT's own emitted labels, not arbitrary Asar syntax. */
internal data class AsmGeneratedRoomSource(
    val lines: List<String>,
    val generatedAt: String,
    val definitions: Map<String, Int>,
    val sections: List<AsmGeneratedRoomSection>,
) {
    fun sectionAt(lineIndex: Int): AsmGeneratedRoomSection? =
        sections.lastOrNull { it.lineIndex <= lineIndex }

    companion object {
        const val FILE_NAME = "generated-rooms.asm"
        private val label = Regex("^(SmeditRoom[A-Za-z0-9_]+):$")
        private val plm = Regex("SmeditRoom_([0-9A-F]+)_State([0-9]+)_Plms")

        fun parse(source: String, generatedAt: String): AsmGeneratedRoomSource? {
            val lines = source.lines()
            val definitions = lines.mapIndexedNotNull { index, line ->
                label.matchEntire(line.trim())?.groupValues?.get(1)?.let { it to index }
            }.toMap()
            if (definitions.isEmpty()) return null
            val sections = definitions.filterKeys { !it.endsWith("_End") }.map { (name, line) ->
                val match = plm.matchEntire(name)
                val roomId = match?.groupValues?.get(1)?.toIntOrNull(16)
                AsmGeneratedRoomSection(
                    label = name,
                    lineIndex = line,
                    roomId = roomId,
                    title = if (match != null) {
                        "Room $${match.groupValues[1]} · source state ${match.groupValues[2]} · PLMs"
                    } else "Room bytes ${name.substringAfterLast('_')}",
                )
            }
            return AsmGeneratedRoomSource(lines, generatedAt, definitions, sections)
        }
    }
}

internal data class AsmGeneratedRoomSection(
    val label: String,
    val lineIndex: Int,
    val roomId: Int?,
    val title: String,
)
