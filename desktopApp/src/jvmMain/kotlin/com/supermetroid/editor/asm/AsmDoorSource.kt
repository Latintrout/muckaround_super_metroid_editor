package com.supermetroid.editor.asm

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*

internal const val DOOR_SOURCE_FILE = FX_SOURCE_FILE

/** A twelve-byte DoorDef lens. Executable arrival code is a separate source resource. */
internal class AsmDoorSource(val text: String, private val arrivalSource: String = "", private val nativeSymbols: Map<String, Int> = emptyMap()) {
    private data class Field(val range: IntRange, val width: Int, val value: Int?, val symbol: String? = null)
    private fun validateArrival(label: String) {
        if (label.startsWith("SmeditArrival_")) AsmDoorArrivalsSource(arrivalSource).routine(label, validateBody = false)
        else require(label.startsWith("DoorASM_") && label in nativeSymbols) { "Unknown arrival label $label; use a managed arrival _Entry symbol or a verified native DoorASM label." }
    }
    private fun fields(name: String): List<Field> {
        val declarations = Regex("(?m)^macro ${Regex.escape(name)}\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(declarations.size == 1) { "Expected exactly one door declaration $name." }
        val body = declarations.single().groups[1]!!
        var offset = body.range.first
        return buildList {
            for (line in body.value.split('\n')) {
                val code = asmCodeBeforeComment(line).trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(db|dw)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal db/dw fields." }
                    val width = if (directive.groupValues[1].equals("dw", true)) 2 else 1
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val literal = token.trim()
                        val first = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(literal)
                        if (value == null && size == 8 && width == 2 && literal.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                            validateArrival(literal)
                            add(Field(offset + first until offset + first + literal.length, width, null, literal))
                            start += token.length + 1
                            continue
                        }
                        require(value != null && value in 0..if (width == 1) 255 else 65535) { "$name: '$literal' is not a ${width * 8}-bit literal." }
                        add(Field(offset + first until offset + first + literal.length, width, value))
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    fun resource(room: RoomEdits, parser: RomParser, dimensions: ((Int) -> Pair<Int, Int>?)? = null): SourceRoomDoors {
        val header = requireNotNull(parser.readRoomHeader(room.roomId))
        val native = parser.parseDoorList(header.doorOut)
        val declared = Regex("(?m)^macro (${prefix(room.roomId)}Door_[0-9]+)\\(").findAll(text).map { it.groupValues[1] }.toList()
        require(declared.size == native.size && declared.toSet() == native.indices.map { name(room.roomId, it) }.toSet()) {
            "Door source must cover the existing list exactly; added, missing or duplicate connection declarations need a structural workflow."
        }
        val entries = native.mapIndexed { index, original ->
            val name = name(room.roomId, index)
            val fields = fields(name)
            require(fields.map { it.width } == listOf(2, 1, 1, 1, 1, 1, 1, 2, 2)) { "$name: DoorDef field widths/order changed; expected 12 bytes." }
            val v = fields.map { it.value ?: 0 }
            SourceRoomDoor(original.doorDefPtr, DoorChange(index, v[0], v[1] or (v[2] shl 8), v[3] or (v[4] shl 8), v[5], v[6], v[7], v[8]),
                fields.last().symbol?.let { SourceDoorArrival(it) })
        }
        return SourceRoomDoors(header.doorOut, entries).also { value ->
            if (dimensions == null) validateSourceRoomDoors(room, value, parser) else validateSourceRoomDoors(room, value, parser, dimensions)
        }
    }
    fun edit(room: RoomEdits, parser: RomParser, index: Int, entry: RomParser.DoorEntry, dimensions: ((Int) -> Pair<Int, Int>?)? = null): String {
        val current = resource(room, parser, dimensions)
        require(current.entries[index].doorDefPtr == entry.doorDefPtr) { "DoorDef identity changed; reopen this connection." }
        require(current.entries[index].arrival == null || entry.entryCode == current.entries[index].change.entryCode) {
            "Arrival target is managed in ASM. Edit its symbolic word in bank_83.asm; other connection fields remain reciprocal."
        }
        val values = listOf(entry.destRoomPtr, entry.bitflag and 255, entry.bitflag ushr 8, entry.doorCapCode and 255,
            entry.doorCapCode ushr 8, entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode)
        val result = fields(name(room.roomId, index)).mapIndexedNotNull { i, field ->
            if (field.symbol != null || field.value == values[i]) null else field.range to hex(values[i], field.width * 2)
        }.sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
        AsmDoorSource(result, arrivalSource, nativeSymbols).resource(room, parser, dimensions)
        return result
    }
    fun withArrival(roomId: Int, index: Int, label: String): String {
        validateArrival(label)
        return text.replaceRange(fields(name(roomId, index)).last().range, label)
    }
    fun line(roomId: Int, index: Int = 0): Int {
        val name = name(roomId, index)
        val start = requireNotNull(Regex("(?m)^macro $name\\(\\)").find(text)) { "Missing door declaration $name." }.range.first
        return text.take(start).count { it == '\n' }
    }
    companion object {
        fun prefix(roomId: Int) = "SmeditDoors_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun name(roomId: Int, index: Int) = prefix(roomId) + "Door_$index"
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
        fun render(roomId: Int, roomName: String, source: SourceRoomDoors) = buildString {
            appendLine("; SMEDIT doors v1 · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
            appendLine("; Existing connection order and DoorDef identities stay fixed; one-way connections are allowed.")
            appendLine("; Arrival ASM is authored separately in bank_8F.asm; editing it does not disable these data fields.")
            source.entries.forEach { door ->
                val d = door.change
                appendLine("; Connection ${d.doorIndex + 1} · DoorDef \$83:${hex(door.doorDefPtr, 4).drop(1)}")
                appendLine("macro ${name(roomId, d.doorIndex)}()")
                appendLine("    dw ${hex(d.destRoomPtr, 4)} ; Destination room · bank \$8F")
                appendLine("    db ${hex(d.bitflag and 255, 2)}, ${hex(d.bitflag ushr 8, 2)} ; Flags, direction / closing door")
                appendLine("    db ${hex(d.doorCapCode and 255, 2)}, ${hex(d.doorCapCode ushr 8, 2)} ; Door cap tile X, Y (not an ASM pointer)")
                appendLine("    db ${hex(d.screenX, 2)}, ${hex(d.screenY, 2)} ; Arrival screen X, Y")
                appendLine("    dw ${hex(d.distFromDoor, 4)} ; Distance (\$8000 = default)")
                appendLine("    dw ${door.arrival?.label ?: hex(d.entryCode, 4)} ; Arrival ASM · bank \$8F (\$0000 = none)")
                appendLine("endmacro\n")
            }
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditDoors_([0-9A-F]{4})_Door_[0-9]+\\(\\)").find(lines[index])?.let {
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16))
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
    }
}
