package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

/** A literal prefix-tree lens. No executed macros, arbitrary expressions or compiled addresses. */
internal class AsmRoomSelectorsSource(val text: String) {
    private data class Definition(val range: IntRange, val bodyStart: Int, val body: String)
    private data class Field(val range: IntRange, val size: Int, val value: Int)
    private val definitions by lazy {
        val matches = Regex("(?m)^macro (SmeditSelectors_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(matches.map { it.groupValues[1] }.distinct().size == matches.size) { "Duplicate selector declarations." }
        matches.associate { it.groupValues[1] to Definition(it.range, it.groups[2]!!.range.first, it.groupValues[2]) }
    }
    private fun definition(name: String) = requireNotNull(definitions[name]) { "Missing selector declaration $name." }
    private fun fields(name: String): List<Field> {
        val d = definition(name)
        var offset = d.bodyStart
        return buildList {
            for (line in d.body.split('\n')) {
                val code = line.substringBefore(';').trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(db|dw)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal db/dw fields." }
                    val size = if (directive.groupValues[1].equals("db", true)) 1 else 2
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val clean = token.trim()
                        val first = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(clean)
                        require(value != null && value in 0..if (size == 1) 255 else 65535) { "$name: invalid ${size * 8}-bit literal '$clean'." }
                        add(Field(offset + first until offset + first + clean.length, size, value))
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    fun resource(room: RoomEdits): SourceRoomSelectors {
        require(room.asmStateSelectorsSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported selector source ownership." }
        require(room.states.all { !it.conditionChanged && it.condition == baseline(it) }) { "Selectors have both source and GUI condition overrides." }
        val order = definition(orderName(room.roomId)).body.lineSequence().mapNotNull { line ->
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) null else requireNotNull(Regex("^%${prefix(room.roomId)}Condition_([0-9a-f]+)\\(\\)$").matchEntire(code)) { "Selector order must contain only state-condition macro references." }
                .groupValues[1].let(::decodeId)
        }.toList()
        val conditions = room.states.associate { state ->
            val f = fields(conditionName(room.roomId, state.id))
            var cursor = 0
            var nodes = 0
            fun read(depth: Int): ProjectRoomStateCondition {
                require(depth <= 8 && ++nodes <= 64 && cursor + 4 <= f.size) { "Truncated or oversized condition tree." }
                val record = f.subList(cursor, cursor + 4); cursor += 4
                require(record.map { it.size } == listOf(1, 1, 1, 2)) { "Selector node widths must be db kind, NOT, children; dw argument." }
                val kind = requireNotNull(KINDS.getOrNull(record[0].value)) { "Unknown selector kind." }
                val not = record[1].value
                val children = record[2].value
                require(not in 0..1 && children <= 64) { "Invalid selector NOT/child count." }
                val argument = record[3].value
                val typed = projectRoomStateCondition(kind)
                require(typed.argumentKind !in setOf(ProjectRoomStateConditionArgumentKind.NONE, ProjectRoomStateConditionArgumentKind.CHILDREN) || argument == 0) { "Argument must be zero for ${kind.name}." }
                return projectRoomStateCondition(kind, argument.takeUnless { typed.argumentKind in setOf(ProjectRoomStateConditionArgumentKind.NONE, ProjectRoomStateConditionArgumentKind.CHILDREN) },
                    not == 1, List(children) { read(depth + 1) })
            }
            val condition = read(0)
            require(cursor == f.size) { "Trailing selector fields." }
            state.id to condition
        }
        val result = SourceRoomSelectors(order, conditions)
        require(conditions.keys == room.states.map { it.id }.toSet())
        require(result.order.last() == room.states.single { baseline(it).kind == ProjectRoomStateConditionKind.DEFAULT }.id) { "The mandatory default state's identity cannot change." }
        return result
    }
    fun editCondition(room: RoomEdits, id: String, condition: ProjectRoomStateCondition): String {
        val current = resource(room)
        require(current.conditions.getValue(id).kind != ProjectRoomStateConditionKind.DEFAULT && condition.kind != ProjectRoomStateConditionKind.DEFAULT) { "The mandatory default cannot be changed." }
        SourceRoomSelectors(current.order, current.conditions + (id to condition))
        val name = conditionName(room.roomId, id)
        val replacements = nodeFields(condition)
        val existing = fields(name)
        return if (replacements.map { it.first } == existing.map { it.size }) {
            val literals = existing.mapIndexedNotNull { index, field ->
                if (field.value == replacements[index].second) null else field.range to hex(replacements[index].second, field.size * 2)
            }
            // Refresh only our generated kind hint. Handwritten comments and unrelated spelling survive.
            val hints = condition.flattened().mapIndexedNotNull { index, node ->
                val old = KINDS[existing[index * 4].value]
                if (old == node.kind) null else {
                    val start = existing[index * 4 + 2].range.last + 1
                    val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
                    Regex("; ${old.name}: kind, NOT, child count").find(text.substring(start, end))?.let {
                        (start + it.range.first..start + it.range.last) to "; ${node.kind.name}: kind, NOT, child count"
                    }
                }
            }
            (literals + hints).sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
        } else {
            val d = definition(name)
            val notes = d.body.lines().mapNotNull { it.substringAfter(';', "").trim().takeIf(String::isNotBlank) }.joinToString("") { "    ; Retained note: $it\n" }
            text.replaceRange(d.bodyStart until d.bodyStart + d.body.length, notes + renderCondition(condition))
        }
    }
    fun editOrder(room: RoomEdits, order: List<String>): String {
        val current = resource(room)
        SourceRoomSelectors(order, current.conditions)
        require(order.last() == current.order.last()) { "The default branch must remain last." }
        val d = definition(orderName(room.roomId))
        val refs = Regex("%${prefix(room.roomId)}Condition_([0-9a-f]+)\\(\\)").findAll(d.body).toList()
        return refs.mapIndexed { index, match -> (d.bodyStart + match.range.first..d.bodyStart + match.range.last) to "%${conditionName(room.roomId, order[index])}()" }
            .sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
    }
    fun line(roomId: Int, stateId: String? = null) = text.take(definition(if (stateId == null) orderName(roomId) else conditionName(roomId, stateId)).range.first).count { it == '\n' }
    companion object {
        // Explicit, versioned wire order: never derive these IDs from enum ordinals.
        val KINDS = "DEFAULT INCOMING_DOOR AREA_MAIN_BOSS_DEAD NEVER EVENT_SET AREA_BOSS_BIT_SET MORPH_BALL_COLLECTED MORPH_BALL_AND_MISSILES POWER_BOMBS_COLLECTED SPEED_BOOSTER_COLLECTED EQUIPMENT_COLLECTED BEAM_COLLECTED MISSILE_CAPACITY_AT_LEAST SUPER_MISSILE_CAPACITY_AT_LEAST POWER_BOMB_CAPACITY_AT_LEAST ENERGY_CAPACITY_AT_LEAST RESERVE_CAPACITY_AT_LEAST ITEM_PICKUP_COLLECTED BOSS_DEFEATED EQUIPMENT_EQUIPPED BEAM_EQUIPPED CURRENT_ENERGY_AT_LEAST CURRENT_MISSILES_AT_LEAST CURRENT_SUPER_MISSILES_AT_LEAST CURRENT_POWER_BOMBS_AT_LEAST CURRENT_RESERVE_ENERGY_AT_LEAST DOOR_BIT_SET CHOZO_BLOCK_DESTROYED ESCAPE_ACTIVE ALL_OF ANY_OF".split(' ').map(ProjectRoomStateConditionKind::valueOf)
        fun baseline(state: RoomStateEdits) = state.sourceCondition ?: projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER)
        fun prefix(roomId: Int) = "SmeditSelectors_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun orderName(roomId: Int) = prefix(roomId) + "Order"
        private fun idHex(id: String) = id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun decodeId(hex: String): String { require(hex.length % 2 == 0); return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8) }
        fun conditionName(roomId: Int, id: String) = prefix(roomId) + "Condition_" + idHex(id)
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
        private fun nodeFields(c: ProjectRoomStateCondition): List<Pair<Int, Int>> = listOf(1 to KINDS.indexOf(c.kind), 1 to if (c.negated) 1 else 0, 1 to c.children.size, 2 to (c.argument ?: 0)) + c.children.flatMap(::nodeFields)
        private fun renderCondition(c: ProjectRoomStateCondition): String = buildString {
            appendLine("    db ${hex(KINDS.indexOf(c.kind), 2)}, ${hex(if (c.negated) 1 else 0, 2)}, ${hex(c.children.size, 2)} ; ${c.kind}: kind, NOT, child count")
            appendLine("    dw ${hex(c.argument ?: 0, 4)} ; argument (zero for NONE/compound)")
            c.children.forEach { append(renderCondition(it)) }
        }
        fun render(roomId: Int, name: String, selectors: SourceRoomSelectors): String = buildString {
            appendLine("; SMEDIT selectors v1 · ${name.replace('\n', ' ').replace('\r', ' ')} · typed input, not executable placement")
            KINDS.forEachIndexed { index, kind -> appendLine("; ${hex(index, 2)} = $kind") }
            appendLine("macro ${orderName(roomId)}()")
            selectors.order.forEach { appendLine("    %${conditionName(roomId, it)}()") }
            appendLine("endmacro\n")
            selectors.conditions.forEach { (id, c) -> appendLine("macro ${conditionName(roomId, id)}()"); append(renderCondition(c)); appendLine("endmacro\n") }
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditSelectors_([0-9A-F]{4})_(Order|Condition_([0-9a-f]+))\\(\\)").find(lines[index])?.let {
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16), stateId = it.groupValues[3].takeIf(String::isNotEmpty)?.let(::decodeId))
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
    }
}
