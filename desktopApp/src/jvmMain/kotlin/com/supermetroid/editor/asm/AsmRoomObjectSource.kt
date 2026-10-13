package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SourcePlmResource
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.displaySummary
import com.supermetroid.editor.rom.ensureStateManifest

/** Authored, relocatable PLM macros. The planner owns addresses; these tokens own object values.
 * State wrappers preserve resource sharing; common-item macros share values across states.
 * This bounded grammar is checked before Asar and again against its actual output. */
internal class AsmRoomObjectSource(val text: String, private val symbols: Map<String, Int> = emptyMap()) {
    data class Macro(val name: String, val start: Int, val bodyStart: Int, val end: Int, val bodyEnd: Int)
    data class ObjectRecord(val record: AsmPlmDocument.Record, val lineRange: IntRange, val commonMacro: String?)
    data class Table(val root: Macro, val resource: Macro, val objects: List<ObjectRecord>, val document: AsmPlmDocument)

    private val macros = Regex("(?m)^macro (SmeditObjects_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n")
        .findAll(text).map { match ->
            val end = Regex("(?m)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").find(text, match.range.last + 1)
                ?: error("${match.groupValues[1]} has no endmacro")
            Macro(match.groupValues[1], match.range.first, match.range.last + 1, end.range.last + 1, end.range.first)
        }.toList().also { all ->
            require(all.map { it.name }.distinct().size == all.size) { "Duplicate authored room-object macro." }
            require(all.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Nested room-object macro definitions are not supported." }
        }.associateBy { it.name }

    fun macro(name: String): Macro = macros[name] ?: error("Missing authored object source $name")
    fun contains(name: String): Boolean = name in macros

    /** A semantic edit may simplify our helpers, but never rebuild handwritten source wholesale.
     * External references (including disabled modules) pin helpers. Names are not renumbered and
     * independent equal-valued entries/lists are never merged. Merely browsing does not call this. */
    fun tidy(room: RoomEdits, externalReferences: Set<String> = emptySet()): String {
        val before = resources(room)
        val activeStates = room.states.map { stateMacro(room.roomId, it.id) }.toSet()
        val helperName = Regex("${prefix(room.roomId)}(?:(?:Common|List)_[0-9]+|State_[0-9a-f]+)")
        val helpers = macros.values.filter { helperName.matches(it.name) && it.name !in activeStates }.associateBy { it.name }
        // Everything outside these owned definitions is a root, including other rooms and custom
        // code in this bank. Preserve unsupported helper bodies rather than treating them as trash.
        var outside = text
        for (helper in helpers.values.sortedByDescending { it.start }) {
            outside = outside.removeRange(helper.start, helper.end)
        }
        val live = (referencedHelpers(outside) + externalReferences).toMutableSet()
        helpers.values.filter { helper ->
            val body = lines(helper).map { it.second }
            if (helper.name.contains("_State_")) body.size != 1 || !CALL.matches(body.single())
            else if (helper.name.contains("_Common_")) body.size != 1 || !ENTRY.matches(body.single())
            else body.lastOrNull()?.matches(TERMINATOR) != true ||
                body.dropLast(1).any { !ENTRY.matches(it) && !CALL.matches(it) }
        }.forEach { live += it.name }
        val pending = ArrayDeque(live)
        while (pending.isNotEmpty()) {
            val helper = helpers[pending.removeFirst()] ?: continue
            referencedHelpers(text.substring(helper.bodyStart, helper.bodyEnd)).forEach {
                if (live.add(it)) pending.addLast(it)
            }
        }
        val edits = helpers.values.filter { it.name !in live }.map {
            removalRange(it) to retainedComments(text.substring(it.start, it.end))
        }
        var result = replaceRanges(edits)
        val source = AsmRoomObjectSource(result, symbols)
        val uses = room.states.flatMap { source.table(room.roomId, it.id).objects }
            .filter { it.commonMacro != null }.groupBy { it.commonMacro!! }
        val inlines = mutableListOf<Pair<IntRange, String>>()
        for ((name, consumers) in uses) {
            if (consumers.size != 1 || name in externalReferences) continue
            val helper = source.macro(name)
            val withoutDefinition = result.removeRange(helper.start, helper.end)
            // A second textual reference may be in a custom macro or an inactive state wrapper.
            if (HELPER_REFERENCE.findAll(withoutDefinition).count { it.value == name } != 1) continue
            val call = consumers.single()
            val notes = source.retainedComments(result.substring(helper.start, helper.bodyStart)) +
                source.retainedComments(result.substring(helper.bodyEnd, helper.end)) +
                source.retainedComments(result.substring(call.lineRange))
            inlines += call.lineRange to (notes + result.substring(helper.bodyStart, helper.bodyEnd))
            inlines += source.removalRange(helper) to ""
        }
        result = source.replaceRanges(inlines)
        check(AsmRoomObjectSource(result, symbols).resources(room) == before) {
            "Room-object cleanup changed values or resource sharing; no source was replaced."
        }
        return result
    }

    private fun replaceRanges(edits: List<Pair<IntRange, String>>): String {
        var result = text
        for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }
        return result
    }

    private fun removalRange(macro: Macro): IntRange {
        // Consume at most one adjacent blank separator, so add/remove cycles do not grow whitespace.
        val previous = text.lastIndexOf('\n', macro.start - 2) + 1
        val start = if (text.substring(previous, macro.start).isBlank()) previous else macro.start
        return start until macro.end
    }

    private fun retainedComments(fragment: String): String = buildString {
        val newline = if (fragment.contains("\r\n")) "\r\n" else "\n"
        fragment.lineSequence().forEach { line ->
            var comment = line.substringAfter(';', "").trimStart()
            if (comment.startsWith("PLM: ") || comment.startsWith("End of PLM list")) {
                comment = comment.substringAfter(';', "").trimStart()
            }
            if (comment.isNotEmpty()) append("    ; ").append(comment).append(newline)
        }
    }

    /** Annotate only authored object macros, never the original bank or a user's free-form comments.
     * Invocations repeat the common object's name so a state list is readable on its own. */
    fun withComments(roomId: Int, describe: (RomParser.PlmEntry) -> String = ::defaultDescription): String {
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val commonPrefix = prefix(roomId) + "Common_"
        val listPrefix = prefix(roomId) + "List_"
        for (macro in macros.values.filter { it.name.startsWith(commonPrefix) || it.name.startsWith(listPrefix) }) {
            for ((range, code) in lines(macro)) {
                val entry = if (ENTRY.matches(code)) parseEntry(range, code).entry else {
                    val common = CALL.matchEntire(code)?.groupValues?.get(1)?.takeIf { it.startsWith(commonPrefix) }
                        ?: continue
                    val definition = lines(macro(common)).singleOrNull() ?: error("$common must contain exactly one PLMPopEntry record.")
                    parseEntry(definition.first, definition.second).entry
                }
                // Names are comments, never source code. Also reserve ';' for handwritten notes.
                val name = describe(entry).replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp};]+"), " ").trim()
                    .ifEmpty { defaultDescription(entry) }
                val line = text.substring(range)
                val content = line.trimEnd('\r', '\n')
                val commentStart = content.indexOf(';').let { if (it < 0) content.length else it }
                val codeAndSpacing = content.take(commentStart)
                val comment = content.substring(commentStart)
                val handwritten = if (comment.startsWith(NAME_COMMENT)) {
                    // Only replace our first comment; preserve any following '; my note' verbatim.
                    val next = comment.indexOf(';', NAME_COMMENT.length)
                    if (next < 0) "" else comment.substring(next)
                } else comment
                val updated = codeAndSpacing + (if (codeAndSpacing.lastOrNull()?.isWhitespace() == true) "" else " ") +
                    NAME_COMMENT + name + (if (handwritten.isEmpty()) "" else " $handwritten") + line.substring(content.length)
                if (updated != line) replacements += range to updated
            }
        }
        var result = text
        for ((range, replacement) in replacements.asReversed()) result = result.replaceRange(range, replacement)
        return result
    }

    private fun lines(macro: Macro): List<Pair<IntRange, String>> {
        val result = mutableListOf<Pair<IntRange, String>>()
        var start = macro.bodyStart
        while (start < macro.bodyEnd) {
            val end = text.indexOf('\n', start).let { if (it < 0) macro.bodyEnd else minOf(it + 1, macro.bodyEnd) }
            val code = text.substring(start, end).substringBefore(';').trim()
            if (code.isNotEmpty()) result += (start until end) to code
            start = end
        }
        return result
    }

    fun table(roomId: Int, stateId: String): Table {
        val root = macro(stateMacro(roomId, stateId))
        val calls = lines(root)
        val resourceName = calls.singleOrNull()?.second?.let { CALL.matchEntire(it)?.groupValues?.get(1) }
            ?: error("${root.name} must call one shared or private object-list macro.")
        require(resourceName.startsWith(prefix(roomId) + "List_")) { "${root.name} references another room or an unsupported resource." }
        val resource = macro(resourceName)
        val body = lines(resource)
        require(body.lastOrNull()?.second?.matches(TERMINATOR) == true) { "$resourceName must end with dw \$0000." }
        val objects = body.dropLast(1).map { (range, code) ->
            val common = CALL.matchEntire(code)?.groupValues?.get(1)
            if (common != null) {
                require(common.startsWith(prefix(roomId) + "Common_")) { "$resourceName: only common-object calls and PLMPopEntry records are supported." }
                val entry = lines(macro(common)).singleOrNull() ?: error("$common must contain exactly one PLMPopEntry record.")
                ObjectRecord(parseEntry(entry.first, entry.second), range, common)
            } else ObjectRecord(parseEntry(range, code), range, null)
        }
        return Table(root, resource, objects, AsmPlmDocument(text, resource.name,
            text.take(resource.start).count { it == '\n' }, objects.map { it.record }))
    }

    private fun parseEntry(line: IntRange, code: String): AsmPlmDocument.Record {
        val match = ENTRY.matchEntire(code) ?: error(
            "$PLM_SOURCE_FILE:${text.take(line.first).count { it == '\n' } + 1}: " +
                "Expected %PLMPopEntry(ID, X, Y, Param); unsupported object source: $code"
        )
        val start = line.first + text.substring(line).indexOf(code)
        val fields = (1..4).map { n ->
            val group = checkNotNull(match.groups[n])
            val token = group.value.trim()
            val offset = start + group.range.first + group.value.indexOf(token)
            offset until offset + token.length
        }
        val values = fields.map { range ->
            val token = text.substring(range)
            AsmPlmDocument.literal(token) ?: symbols[token] ?: error("Unsupported or unknown PLM field '$token'.")
        }
        val entry = RomParser.PlmEntry(values[0], values[1], values[2], values[3])
        AsmPlmDocument.validateEntry(entry)
        return AsmPlmDocument.Record(entry, fields)
    }

    fun resources(room: RoomEdits): Map<String, SourcePlmResource> {
        require(room.asmPlmSourceVersion == 1) { "Unsupported room-object source format ${room.asmPlmSourceVersion}" }
        require(room.states.isNotEmpty()) { "Authored room objects need stable state identities." }
        require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate object-source state identities." }
        require(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() }) { "Room objects have both GUI deltas and authored source." }
        require(room.customScrollCommands.isEmpty() && room.states.all { it.customScrollCommands.isEmpty() }) {
            "Custom scroll commands cannot be mixed with authored object lists yet."
        }
        return room.states.associate { state ->
            val table = table(room.roomId, state.id)
            state.id to SourcePlmResource(table.resource.name, table.document.entries)
        }
    }

    /** Changing one state first forks its list, but common object definitions remain shared. */
    fun edit(room: RoomEdits, stateId: String, allStates: Boolean, remove: (RomParser.PlmEntry) -> Boolean,
             add: RomParser.PlmEntry?, externalReferences: Set<String> = emptySet()): String {
        add?.let(AsmPlmDocument::validateEntry)
        val states = if (allStates) room.states.map { it.id } else listOf(stateId)
        var result = text
        var commonName: String? = null
        if (allStates && add != null && room.states.size > 1) {
            commonName = uniqueName(prefix(room.roomId) + "Common_")
            result += "\n" + renderMacro(commonName, listOf(renderEntry(add)))
        }
        val edited = mutableSetOf<String>()
        for (state in states) {
            var source = AsmRoomObjectSource(result, symbols)
            var table = source.table(room.roomId, state)
            if (!allStates && room.states.count { source.table(room.roomId, it.id).resource.name == table.resource.name } > 1) {
                val name = source.uniqueName(prefix(room.roomId) + "List_")
                val body = result.substring(table.resource.bodyStart, table.resource.bodyEnd)
                result = result.replaceRange(table.root.bodyStart, table.root.bodyEnd, "    %$name()\n") +
                    "\nmacro $name()\n${body}endmacro\n"
                source = AsmRoomObjectSource(result, symbols)
                table = source.table(room.roomId, state)
            }
            if (!edited.add(table.resource.name)) continue
            val replacements = table.objects.filter { remove(it.record.entry) }.map { it.lineRange to "" }.toMutableList()
            if (add != null) {
                val terminator = source.lines(table.resource).last().first.first
                replacements += (terminator until terminator) to "    ${commonName?.let { "%$it()" } ?: renderEntry(add)}\n"
            }
            for ((range, replacement) in replacements.sortedByDescending { it.first.first }) result = result.replaceRange(range, replacement)
        }
        return AsmRoomObjectSource(result, symbols).tidy(room, externalReferences)
    }

    fun cloneState(roomId: Int, template: String, stateId: String): String {
        require(!contains(stateMacro(roomId, stateId))) { "Source already defines state $stateId." }
        val table = table(roomId, template)
        return text + "\n" + renderMacro(stateMacro(roomId, stateId), listOf("%${table.resource.name}()"))
    }

    private fun uniqueName(stem: String): String = generateSequence(1) { it + 1 }.map { stem + it }.first { it !in macros }

    companion object {
        private const val NAME_COMMENT = "; PLM: "
        private val HELPER_REFERENCE = Regex("\\bSmeditObjects_[A-Za-z0-9_]+\\b")
        // Deliberately conservative: mentions inside strings/comments also protect custom references.
        fun referencedHelpers(text: String): Set<String> = if ("SmeditObjects_" !in text) emptySet()
            else HELPER_REFERENCE.findAll(text).map { it.value }.toSet()
        fun defaultDescription(entry: RomParser.PlmEntry): String = RomParser.plmDisplayName(entry.id, entry.param)
        private val ENTRY = Regex("%PLMPopEntry\\(\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+),\\s*([^,()]+)\\s*\\)")
        private val CALL = Regex("%(SmeditObjects_[A-Za-z0-9_]+)\\(\\)")
        private val TERMINATOR = Regex("dw\\s+(\\$0+|0+|%0+)", RegexOption.IGNORE_CASE)
        fun prefix(roomId: Int): String = "SmeditObjects_${roomId.toString(16).uppercase()}_"
        // Encode, do not sanitize: distinct state IDs must never become the same assembler identifier.
        fun stateMacro(roomId: Int, stateId: String): String = prefix(roomId) + "State_" + stateId.toByteArray(Charsets.UTF_8)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun renderEntry(entry: RomParser.PlmEntry): String = "%PLMPopEntry(" +
            listOf(entry.id to 4, entry.x to 2, entry.y to 2, entry.param to 4)
                .joinToString(", ") { (value, width) -> "$" + value.toString(16).uppercase().padStart(width, '0') } + ")"
        private fun renderMacro(name: String, lines: List<String>): String =
            "macro $name()\n" + lines.joinToString("\n", postfix = "\n") { "    $it" } + "endmacro\n"

        data class Migration(val text: String, val states: List<RoomStateEdits>, val expected: Map<String, List<RomParser.PlmEntry>>)

        fun migrate(text: String, room: RoomEdits, parser: RomParser, name: String,
                    symbols: Map<String, Int>, isItem: (Int) -> Boolean = { RomParser.isItemPlm(it) },
                    describe: (RomParser.PlmEntry) -> String = ::defaultDescription,
                    externalReferences: Set<String> = emptySet()): Migration {
            require(room.asmPlmSourceVersion == 0) { "Room already uses authored objects." }
            require(!text.contains("macro ${prefix(room.roomId)}")) { "Existing authored object declarations need review; none were overwritten." }
            require(room.customScrollCommands.isEmpty() && room.states.all { it.customScrollCommands.isEmpty() }) {
                "Custom scroll-command source migration is not supported yet; existing room edits were preserved."
            }
            val states = room.copy(states = room.states.toMutableList()).ensureStateManifest(parser).toList()
            require(states.none { it.resourcesChanged }) { "Changed object resource links need review before migration." }
            fun apply(base: List<RomParser.PlmEntry>, changes: List<PlmChange>): List<RomParser.PlmEntry> {
                if (changes.isEmpty()) return base
                val result = base.toMutableList()
                changes.forEach { change -> when (change.action) {
                    "add" -> result += RomParser.PlmEntry(change.plmId, change.x, change.y, change.param)
                    "remove" -> result.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                    else -> error("Unsupported PLM edit '${change.action}'.")
                } }
                val seen = mutableSetOf<Pair<Int, Int>>()
                return result.asReversed().filter { !isItem(it.id) || seen.add(it.x to it.y) }.asReversed()
            }
            val runtime = parser.parseRoomStatesWithData(room.roomId)
            val common = (if (states.size > 1) apply(emptyList(), room.plmChanges) else emptyList()).mapIndexed { index, entry ->
                entry to (prefix(room.roomId) + "Common_" + (index + 1))
            }.toMap()
            val expected = linkedMapOf<String, List<RomParser.PlmEntry>>()
            val resources = linkedMapOf<Pair<Int, List<String>>, String>()
            val body = StringBuilder("\n; SMEDIT authored objects · ${name.replace('\n', ' ').replace('\r', ' ')}\n")
            body.appendLine("; Values live here. State wrappers select lists; Common helpers share objects across states.")
            body.appendLine("; Placement addresses and state pointers are supplied by the checked build planner.")
            body.appendLine("; PLMPopEntry(type, tile X, tile Y, parameter): zero-based tiles; item parameter = pickup flag ID.")
            common.forEach { (entry, macro) -> body.appendLine(renderMacro(macro, listOf(renderEntry(entry)))) }
            for (state in states) {
                val base = runtime.getOrNull(state.baseSourceStateIndex() ?: -1) ?: error("${state.id} has no supported source state.")
                val entries = apply(apply(parser.parsePlmSet(base.plmSetPtr), room.plmChanges), state.plmChanges)
                entries.forEach(AsmPlmDocument::validateEntry)
                expected[state.id] = entries
                val lines = entries.map { entry ->
                    val overridden = state.plmChanges.any { it.plmId == entry.id && it.x == entry.x && it.y == entry.y }
                    common[entry]?.takeUnless { overridden }?.let { "%$it()" } ?: renderEntry(entry)
                }
                val key = base.plmSetPtr to lines
                val resource = resources.getOrPut(key) {
                    (prefix(room.roomId) + "List_" + (resources.size + 1)).also { macro ->
                        body.appendLine(renderMacro(macro, lines + "dw \$0000 ; End of PLM list"))
                    }
                }
                body.appendLine("; ${state.id} · ${state.condition.displaySummary(parser.readRoomHeader(room.roomId)?.area ?: 0)}")
                body.appendLine(renderMacro(stateMacro(room.roomId, state.id), listOf("%$resource()")))
            }
            val authoredRoom = room.copy(asmPlmSourceVersion = 1, plmChanges = mutableListOf(),
                states = states.map { it.copy(plmChanges = mutableListOf()) }.toMutableList())
            val tidy = AsmRoomObjectSource(text + "\n" + body, symbols).tidy(authoredRoom, externalReferences)
            val updated = AsmRoomObjectSource(tidy, symbols).withComments(room.roomId, describe)
            val decoded = AsmRoomObjectSource(updated, symbols)
            require(states.all { decoded.table(room.roomId, it.id).document.entries == expected[it.id] }) { "Room-object migration changed values." }
            return Migration(updated, states, expected)
        }
    }
}
