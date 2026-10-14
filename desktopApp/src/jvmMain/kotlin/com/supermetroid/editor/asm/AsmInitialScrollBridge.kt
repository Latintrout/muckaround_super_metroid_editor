package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.SourceInitialScrollResource
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.ensureStateManifest

internal data class AsmInitialScrollView(val resource: SourceInitialScrollResource?, val problem: String?)

/** Initial grids are independently adopted; no PLM migration is required. */
internal class AsmInitialScrollBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val metadataChanged: () -> Unit,
) {
    private var revision: Pair<String, AsmInitialScrollSource>? = null
    private val lastGood = mutableMapOf<Pair<Int, String>, SourceInitialScrollResource>()
    private fun source(): AsmInitialScrollSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmInitialScrollSource(text, catalog.symbols).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmInitialScrollSourceVersion ?: 0) != 0
    private fun dimensions(roomId: Int): Pair<Int, Int> {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return (room.roomHeaderChange?.width ?: header.width) to (room.roomHeaderChange?.height ?: header.height)
    }
    fun view(roomId: Int, stateId: String): AsmInitialScrollView = try {
        val (width, height) = dimensions(roomId)
        val inputs = source().resources(project().rooms.getValue(project().roomKey(roomId)), width, height)
        val resource = inputs.getValue(stateId)
        lastGood[roomId to stateId] = resource
        AsmInitialScrollView(resource, null)
    } catch (error: Exception) { AsmInitialScrollView(lastGood[roomId to stateId], error.message ?: "Invalid initial scroll source.") }

    fun migrate(roomId: Int, roomName: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Initial scroll source requires ASM mode." }
        require(owner.newRooms.isEmpty()) { "Initial scroll source for added rooms is not supported yet." }
        val room = owner.rooms[owner.roomKey(roomId)] ?: com.supermetroid.editor.data.RoomEdits(roomId)
        val staged = room.copy(states = room.states.toMutableList())
        staged.ensureStateManifest(catalog.parser)
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val width = room.roomHeaderChange?.width ?: header.width
        val height = room.roomHeaderChange?.height ?: header.height
        val inspected = catalog.parser.inspectRoomStates(roomId).states
        val tables = linkedMapOf<Pair<Int, List<Int>>, SourceInitialScrollResource>()
        val before = source().text
        val prefix = AsmInitialScrollSource.prefix(roomId)
        require(!Regex("(?m)^macro ${prefix}").containsMatchIn(before)) { "Existing initial scroll declarations need review before adoption." }
        val addition = buildString {
            appendLine("; SMEDIT initial screen scrolls · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
            appendLine("; Row-major db bytes: 00 Red, 01 Blue, 02 Green. No terminator.")
            appendLine("; State wrappers share tables; visual edits detach only the selected state.")
            for (state in staged.states) {
                val index = requireNotNull(state.baseSourceStateIndex()) { "Initial scroll state has no source/template." }
                val pc = requireNotNull(inspected.getOrNull(index)?.stateDataPcOffset)
                val pointer = catalog.parser.readStateData(pc).getValue("roomScrollsPtr")
                require(pointer in 0..1 || pointer in 0x8000..0xFFFF) { "Custom initial scroll presets need a matching codec; this field is not a ROM table address." }
                val original = catalog.parser.parseScrollData(pointer, header.width, header.height)
                val values = MutableList(width * height) { 1 }
                for (y in 0 until minOf(height, header.height)) for (x in 0 until minOf(width, header.width)) values[y * width + x] = original[y * header.width + x]
                for (change in room.scrollChanges + state.scrollChanges) {
                    require(change.screenX in 0 until width && change.screenY in 0 until height && change.newValue in 0..2) { "Initial scroll edit is outside the grid." }
                    values[change.screenY * width + change.screenX] = change.newValue
                }
                val table = tables.getOrPut(pointer to values.toList()) {
                    SourceInitialScrollResource(prefix + "Table_${tables.size + 1}", width, height, values.toList()).also { appendLine(AsmInitialScrollSource.renderTable(it)) }
                }
                appendLine(AsmInitialScrollSource.renderState(roomId, state.id, table.identity))
            }
        }
        val after = before.trimEnd() + "\n\n" + addition
        val oldStates = room.states.toList()
        val oldCommon = room.scrollChanges.toList()
        val oldScoped = staged.states.associate { it.id to it.scrollChanges.toList() }
        fun adopt() {
            require(room.asmInitialScrollSourceVersion == 0 && room.states == oldStates && room.scrollChanges == oldCommon &&
                staged.states.all { it.scrollChanges == oldScoped[it.id] }) { "Scroll metadata changed; adopt the current grid again." }
            owner.rooms[owner.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(staged.states)
            room.scrollChanges.clear()
            room.states.forEach { it.scrollChanges.clear() }
            room.asmInitialScrollSourceVersion = 1
            metadataChanged()
        }
        fun restore() {
            require(room.asmInitialScrollSourceVersion == 1 && room.states.map { it.id } == staged.states.map { it.id } &&
                room.scrollChanges.isEmpty() && room.states.all { it.scrollChanges.isEmpty() }) { "Undo newer scroll/state changes first." }
            room.asmInitialScrollSourceVersion = 0
            room.scrollChanges.addAll(oldCommon)
            room.states.forEach { it.scrollChanges.addAll(oldScoped[it.id].orEmpty()) }
            if (oldStates.isEmpty()) room.states.clear()
            metadataChanged()
        }
        AsmInitialScrollSource(after, catalog.symbols).resources(staged.copy(asmInitialScrollSourceVersion = 1,
            scrollChanges = mutableListOf(), states = staged.states.map { it.copy(scrollChanges = mutableListOf()) }.toMutableList()), width, height)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after, ::restore, ::adopt)
        adopt()
    }
    fun edit(roomId: Int, stateId: String, index: Int, value: Int) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val current = source()
        val edited = current.edit(room, stateId, width, height, index, value)
        val external = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE).flatMap { AsmInitialScrollSource.references(it).asSequence() }.toSet()
        val tidy = AsmInitialScrollSource(edited, catalog.symbols).tidy(room, external)
        val after = AsmInitialScrollSource(tidy, catalog.symbols).withComments(room, width, height)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after)
    }
    fun cloneState(roomId: Int, templateId: String, newId: String) {
        if (!isManaged(roomId)) return
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.cloneState(roomId, templateId, newId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == newId } == true) { "Delete the new state before undoing its scroll declaration." }
        })
    }
    fun hasState(roomId: Int, stateId: String): Boolean = isManaged(roomId) && source().hasState(roomId, stateId)
    fun line(roomId: Int, stateId: String): Int {
        return source().line(roomId, stateId)
    }
}
