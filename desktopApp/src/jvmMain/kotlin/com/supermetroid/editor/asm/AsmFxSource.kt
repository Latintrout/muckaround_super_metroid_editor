package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*

internal const val FX_SOURCE_FILE = "bank_83.asm"

/** Bounded native-header lens. No general Asar interpretation or duplicate persisted FX values. */
internal class AsmFxSource(val text: String) {
    private data class Macro(val name: String, val range: IntRange, val bodyStart: Int, val body: String)
    private data class Field(val span: IntRange, val value: Int, val width: Int)
    private val macros: Map<String, Macro> by lazy {
        val found = Regex("(?m)^macro (SmeditFx_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)")
            .findAll(text).map { m -> Macro(m.groupValues[1], m.range, m.groups[2]!!.range.first, m.groupValues[2]) }.toList()
        require(found.map { it.name }.distinct().size == found.size) { "Duplicate FX source macros." }
        found.associateBy { it.name }
    }
    private val fieldCache = mutableMapOf<String, List<Field>>()
    private fun macro(name: String) = requireNotNull(macros[name]) { "Missing FX source $name." }
    private fun located(position: Int, error: Exception): IllegalArgumentException {
        if (error is IllegalArgumentException && error.message?.startsWith("$FX_SOURCE_FILE:") == true) return error
        val line = text.take(position.coerceAtLeast(0)).count { it == '\n' } + 1
        return IllegalArgumentException("$FX_SOURCE_FILE:$line: ${error.message ?: "Invalid FX source."}", error)
    }
    private fun reference(roomId: Int, stateId: String): String {
        val body = macro(stateName(roomId, stateId)).body.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList()
        require(body.size == 1) { "FX state must reference exactly one table." }
        return requireNotNull(Regex("%(${prefix(roomId)}Table_[0-9]+)\\(\\)").matchEntire(body.single())?.groupValues?.get(1)) {
            "FX state must reference its room's table."
        }
    }
    private fun fields(name: String): List<Field> = fieldCache.getOrPut(name) {
        val m = macro(name)
        val result = mutableListOf<Field>()
        var offset = m.bodyStart
        for (line in m.body.split('\n')) {
            val code = line.substringBefore(';').trimEnd('\r')
            if (code.isNotBlank()) {
                val directive = requireNotNull(Regex("^[ \\t]*(dw|db)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected native dw/db FX fields." }
                val width = if (directive.groupValues[1].equals("dw", true)) 2 else 1
                var start = directive.range.last + 1
                for (token in code.substring(start).split(',')) {
                    val clean = token.trim()
                    val local = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val value = AsmPlmDocument.literal(clean)
                    require(value != null && value in 0..if (width == 2) 0xFFFF else 255) { "$name: invalid FX literal '$clean'." }
                    result += Field(offset + local until offset + local + clean.length, value, width)
                    start += token.length + 1
                }
            }
            offset += line.length + 1
        }
        result.toList()
    }
    fun resource(roomId: Int, stateId: String): SourceFxResource {
        var position = text.indexOf("macro ${stateName(roomId, stateId)}()").coerceAtLeast(0)
        try {
            val name = reference(roomId, stateId)
            position = macro(name).range.first
            val fields = fields(name)
            val stop = fields.lastOrNull()?.let { it.width == 2 && it.value == 0xFFFF && (fields.size - 1) % 12 == 0 } == true
            val data = if (stop) fields.dropLast(1) else fields
            require(data.size % 12 == 0 && fields.isNotEmpty()) { "$name: incomplete FX record or missing stop." }
            data.forEachIndexed { index, f -> require(f.width == if (index % 12 < 4) 2 else 1) { "$name: FX layout is four words followed by eight bytes." } }
            val resource = SourceFxResource(name, data.chunked(12).map { record ->
                try { fxEntryFromFields(record.map(Field::value)) }
                catch (error: Exception) {
                    val field = if (record[5].value !in 0..0x2C || record[5].value % 2 != 0) record[5] else record[0]
                    throw located(field.span.first, error)
                }
            })
            require(stop == (resource.entries.lastOrNull()?.doorSelect != 0)) { "$name: Default stops lookup itself; otherwise end with dw \$FFFF." }
            return resource
        } catch (error: Exception) { throw located(position, error) }
    }
    fun resources(room: RoomEdits): Map<String, SourceFxResource> {
        require(room.asmFxSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported/missing FX source ownership." }
        require(room.fxChange == null && room.states.all { it.fxChange == null && it.doorFxChanges.isEmpty() }) { "FX has both GUI overrides and authored source." }
        require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate FX state identities." }
        return room.states.associate { it.id to resource(room.roomId, it.id) }
    }
    fun hasState(roomId: Int, stateId: String) = stateName(roomId, stateId) in macros
    fun cloneState(roomId: Int, template: String, newId: String): String {
        require(!hasState(roomId, newId)) { "FX state declaration already exists." }
        return text.trimEnd() + "\n\n" + renderState(roomId, newId, reference(roomId, template))
    }
    fun line(roomId: Int, stateId: String, door: Int? = null): Int {
        val table = runCatching { macro(reference(roomId, stateId)) }.getOrElse { macro(stateName(roomId, stateId)) }
        val position = runCatching {
            val index = resource(roomId, stateId).entries.indexOfFirst { it.doorSelect == door }
            if (index >= 0) fields(table.name)[index * 12].span.first else table.range.first
        }.getOrDefault(table.range.first)
        return text.take(position).count { it == '\n' }
    }
    fun replaceEntry(room: RoomEdits, stateId: String, door: Int, replacement: RomParser.FxEntry, detach: Boolean = true): String {
        val inputs = resources(room)
        val selected = inputs.getValue(stateId)
        require(replacement.doorSelect == door) { "Scalar FX edits cannot reorder/change door selectors." }
        val index = selected.entries.indexOfFirst { it.doorSelect == door }
        require(index >= 0 || door == 0) { "No FX header for this incoming door." }
        val entries = selected.entries.toMutableList().also { if (index >= 0) it[index] = replacement else it.add(replacement) }
        SourceFxResource(selected.identity, entries) // Validate before changing any text.
        if (index >= 0 && selected.entries[index] == replacement) return text
        val shared = detach && inputs.values.count { it.identity == selected.identity } > 1
        var name = selected.identity
        if (shared) {
            var ordinal = 1
            while (prefix(room.roomId) + "Table_$ordinal" in macros) ordinal++
            name = prefix(room.roomId) + "Table_$ordinal"
        }
        val old = macro(selected.identity)
        var body = text.substring(old.range)
        if (index >= 0) {
            val current = fields(selected.identity).subList(index * 12, index * 12 + 12)
            val changes = current.zip(replacement.fields()).filter { it.first.value != it.second }
            changes.sortedByDescending { it.first.span.first }.forEach { (field, value) ->
                body = body.replaceRange(field.span.first - old.range.first until field.span.last + 1 - old.range.first, hex(value, field.width))
            }
        } else {
            // Replace only the final no-FX stop, retaining all existing door records and notes.
            val stop = fields(selected.identity).last()
            body = body.replaceRange(stop.span.first - old.range.first until stop.span.last + 1 - old.range.first, "\$0000")
            val following = renderEntry(replacement).lineSequence().drop(1).joinToString("\n").trimEnd()
            body = body.replace("; No FX for other entrances", "; Incoming door (0000 = Default)")
            val end = requireNotNull(Regex("(?m)^endmacro").find(body))
            body = body.replaceRange(end.range, "$following\nendmacro")
        }
        if (!shared) return text.replaceRange(old.range, body)
        val wrapper = macro(stateName(room.roomId, stateId))
        return text.replaceRange(wrapper.range, renderState(room.roomId, stateId, name)).trimEnd() + "\n\n" +
            body.replaceFirst("macro ${old.name}()", "macro $name()")
    }
    fun tidy(room: RoomEdits, external: Set<String> = emptySet()): String {
        val owned = macros.values.filter { it.name.startsWith(prefix(room.roomId)) }
        val outside = owned.sortedByDescending { it.range.first }.fold(text) { current, m -> current.removeRange(m.range) }
        val live = (room.states.map { stateName(room.roomId, it.id) } + references(outside) + external).toMutableSet()
        val queue = java.util.ArrayDeque(live)
        while (queue.isNotEmpty()) macros[queue.removeFirst()]?.let { m -> references(m.body).forEach { if (live.add(it)) queue.add(it) } }
        return owned.filter { it.name !in live }.sortedByDescending { it.range.first }.fold(text) { current, m -> current.removeRange(m.range) }
    }
    /** Refresh generated type names only; scalar source, CRLF and semicolon-separated notes survive. */
    fun withComments(room: RoomEdits): String {
        val changes = linkedMapOf<IntRange, String>()
        for (table in resources(room).values.distinctBy { it.identity }) {
            val fields = fields(table.identity)
            table.entries.forEachIndexed { index, entry ->
                val field = fields[index * 12 + 5]
                val start = text.lastIndexOf('\n', field.span.first - 1) + 1
                val end = text.indexOf('\n', field.span.last + 1).let { if (it < 0) text.length else it }
                val original = text.substring(start, end)
                val line = original.removeSuffix("\r")
                val comment = "; FX Type - ${NativeFxTypes.commentName(entry.fxType)}"
                val generated = Regex(";[ \\t]*FX Type(?:[ \\t]*[-·][ \\t]*[^;\\r\\n]*)?").find(line)
                val updated = if (generated == null) "$line $comment" else line.replaceRange(generated.range, comment)
                if (updated != line) changes[start until end] = updated + if (original.endsWith('\r')) "\r" else ""
            }
        }
        return changes.entries.sortedByDescending { it.key.first }.fold(text) { result, (range, replacement) -> result.replaceRange(range, replacement) }
    }
    companion object {
        private val names = listOf("Incoming door (0000 = Default)", "Liquid Start (FFFF = none)", "Liquid Target", "Liquid Speed (signed 8.8)",
            "Liquid Delay (frames; 00 = stopped)", "FX Type", "FX A · default layer blend", "FX B · Layer 3 blend", "FX C · liquid options",
            "Palette FX bitset", "Animated tiles bitset", "Palette Blend (byte offset)")
        fun prefix(roomId: Int) = "SmeditFx_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun stateName(roomId: Int, stateId: String) = prefix(roomId) + "State_" + stateId.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun renderState(roomId: Int, stateId: String, table: String) = "macro ${stateName(roomId, stateId)}()\n    %$table()\nendmacro\n"
        private fun renderEntry(entry: RomParser.FxEntry): String = entry.fields().mapIndexed { index, value ->
            val description = if (index == 5) "FX Type - ${NativeFxTypes.commentName(value)}" else names[index]
            "    ${if (index < 4) "dw" else "db"} ${hex(value, if (index < 4) 2 else 1)} ; $description"
        }.joinToString("\n", postfix = "\n")
        fun renderTable(resource: SourceFxResource) = buildString {
            appendLine("macro ${resource.identity}()")
            resource.entries.forEach { entry ->
                appendLine(if (entry.doorSelect == 0) "; Default FX" else "; Incoming door ${hex(entry.doorSelect, 2)}")
                append(renderEntry(entry))
            }
            if (resource.entries.lastOrNull()?.doorSelect != 0) appendLine("    dw \$FFFF ; No FX for other entrances")
            appendLine("endmacro")
        }
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width * 2, '0')}"
        fun references(text: String): Set<String> = Regex("%?(SmeditFx_[A-Za-z0-9_]+)").findAll(text.lineSequence().joinToString("\n") { it.substringBefore(';') })
            .map { it.groupValues[1] }.toSet()
    }
}
