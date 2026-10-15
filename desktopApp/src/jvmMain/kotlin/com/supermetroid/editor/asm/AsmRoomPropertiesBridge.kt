package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmRoomPropertiesView(val resource: SourceRoomProperties?, val problem: String?)

internal fun SourceRoomProperties.projectInto(room: RoomEdits) {
    room.asmHeaderPropertiesProjection = header
    room.states.forEach { it.asmStatePropertiesProjection = states[it.id] }
}

/** One authored buffer for header scalars and state-local ordinary metadata. */
internal class AsmRoomPropertiesBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val changed: () -> Unit,
) {
    private var revision: Pair<String, AsmRoomPropertiesSource>? = null
    private val lastGood = mutableMapOf<Int, SourceRoomProperties>()
    private fun source(): AsmRoomPropertiesSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmRoomPropertiesSource(text).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmRoomPropertiesSourceVersion ?: 0) != 0
    fun view(roomId: Int): AsmRoomPropertiesView = try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val resource = source().resource(room)
        lastGood[roomId] = resource
        AsmRoomPropertiesView(resource, null)
    } catch (error: Exception) { AsmRoomPropertiesView(lastGood[roomId], error.message ?: "Invalid room properties.") }

    /** Transient previews make existing room consumers source-aware without serializing source values. */
    fun refreshAll() {
        for (room in project().rooms.values) {
            if (room.asmRoomPropertiesSourceVersion != 0) view(room.roomId).resource?.projectInto(room)
            else {
                room.asmHeaderPropertiesProjection = null
                room.states.forEach { it.asmStatePropertiesProjection = null }
            }
        }
    }
    fun nativeHeader(roomId: Int): RoomHeaderChange {
        val h = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return RoomHeaderChange(index = h.index, area = h.area, mapX = h.mapX, mapY = h.mapY,
            upScroller = h.upScroller, downScroller = h.downScroller, creBitflag = h.creBitflag)
    }
    fun nativeState(room: RoomEdits, stateId: String): StateDataChange {
        val index = requireNotNull(room.states.single { it.id == stateId }.baseSourceStateIndex())
        val state = catalog.parser.parseRoomStatesWithData(room.roomId)[index]
        return StateDataChange(state.tileset, state.musicData, state.musicTrack, state.bgScrolling)
    }
    fun preview(room: RoomEdits, name: String): String = AsmRoomPropertiesSource.render(room, name, effectiveProperties(room))
    private fun effectiveProperties(room: RoomEdits) = SourceRoomProperties(nativeHeader(room.roomId).over(room.effectiveHeaderChange), room.states.associate {
        it.id to nativeState(room, it.id).over(room.effectiveStateDataChange).over(it.effectiveStateDataChange)
    })
    private fun StateDataChange.over(override: StateDataChange?) = copy(tileset = override?.tileset ?: tileset,
        musicData = override?.musicData ?: musicData, musicTrack = override?.musicTrack ?: musicTrack, bgScrolling = override?.bgScrolling ?: bgScrolling)
    private fun RoomHeaderChange.over(override: RoomHeaderChange?) = copy(index = override?.index ?: index,
        area = override?.area ?: area, mapX = override?.mapX ?: mapX, mapY = override?.mapY ?: mapY,
        upScroller = override?.upScroller ?: upScroller, downScroller = override?.downScroller ?: downScroller,
        creBitflag = override?.creBitflag ?: creBitflag)

    fun migrate(roomId: Int, name: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room properties require ASM source mode." }
        require(owner.newRooms.isEmpty()) { "Room-property source for added-room projects needs stable source identities first." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        room.ensureStateManifest(catalog.parser)
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val resource = effectiveProperties(room)
        staged.roomHeaderChange = room.roomHeaderChange?.let { RoomHeaderChange(width = it.width, height = it.height, doorOut = it.doorOut) }?.takeUnless { it == RoomHeaderChange() }
        staged.stateDataChange = null; staged.states.forEach { it.stateDataChange = null }
        staged.asmRoomPropertiesSourceVersion = 1
        resource.projectInto(staged)
        val current = source()
        require(!Regex("(?m)^macro ${AsmRoomPropertiesSource.prefix(roomId)}").containsMatchIn(current.text)) { "Existing room-property declarations need review before adoption." }
        val after = current.text.trimEnd() + "\n\n" + AsmRoomPropertiesSource.render(room, name, resource)
        AsmRoomPropertiesSource(after).resource(staged)
        fun transition(expected: RoomEdits, target: RoomEdits) {
            require(project() === owner && owner.rooms[owner.roomKey(roomId)] === room && room == expected) { "Undo newer room/state changes before restoring room-property ownership." }
            room.asmRoomPropertiesSourceVersion = target.asmRoomPropertiesSourceVersion
            room.roomHeaderChange = target.roomHeaderChange
            room.stateDataChange = target.stateDataChange
            room.asmHeaderPropertiesProjection = target.asmHeaderPropertiesProjection
            room.states.zip(target.states).forEach { (state, saved) ->
                state.stateDataChange = saved.stateDataChange
                state.asmStatePropertiesProjection = saved.asmStatePropertiesProjection
            }
            owner.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after,
            beforeUndo = { transition(staged, beforeModel) }, beforeRedo = { transition(beforeModel, staged) })
        transition(beforeModel, staged)
    }
    fun editHeader(roomId: Int, change: RoomHeaderChange) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        require((change.width ?: header.width) == (room.roomHeaderChange?.width ?: header.width) &&
            (change.height ?: header.height) == (room.roomHeaderChange?.height ?: header.height) &&
            (change.doorOut ?: header.doorOut) == (room.roomHeaderChange?.doorOut ?: header.doorOut)) { "Use Resize or Doors to change dimensions or door-list ownership together." }
        require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid room properties." }
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.editHeader(room, nativeHeader(roomId).over(change)))
        refreshAll()
    }
    fun editState(roomId: Int, stateId: String, change: StateDataChange?) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid room properties." }
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.editStates(room, mapOf(stateId to nativeState(room, stateId).over(change))))
        refreshAll()
    }
    fun cloneState(roomId: Int, template: String, newId: String) {
        if (!isManaged(roomId)) return
        val room = project().rooms.getValue(project().roomKey(roomId))
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.cloneState(room, template, newId), beforeUndo = {
            require(room.states.none { it.id == newId }) { "Delete the new state before undoing its property declaration." }
        })
    }
    fun line(roomId: Int, stateId: String? = null): Int = source().line(roomId, stateId)
    fun hasState(roomId: Int, stateId: String): Boolean = isManaged(roomId) && source().hasState(roomId, stateId)
}
