package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class AsmPlmSourceView(
    val document: AsmPlmDocument?,
    val problem: String?,
)

/** GUI lens over AsmWorkspaceState's document. Project JSON persists identity only, not object values. */
internal class AsmPlmSourceBridge(
    val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    registerSaveParticipant: Boolean = true,
    private val project: () -> SmEditProject,
) : AsmSourceSaveParticipant {
    var onMetadataChanged: () -> Unit = {}
    var describePlm: (RomParser.PlmEntry) -> String = { AsmRoomObjectSource.defaultDescription(it) }
    private val lastValid = mutableMapOf<Int, AsmPlmDocument>()
    private var sourceRevision: Triple<String, String, AsmPlmSourceCatalog.SourceRevision>? = null
    private var managedRevision: Pair<String, AsmRoomObjectSource>? = null
    private val lastManaged = mutableMapOf<Pair<Int, String>, AsmPlmDocument>()
    private var projectedText: String? = null
    private var projectedMacros: String? = null
    private val projections = mutableMapOf<Int, AsmRoomProjection>()

    private fun projectionRepository(): AsmRoomProjectionRepository? = workspace.projectWorkspace?.root?.parentFile?.let {
        AsmRoomProjectionRepository(File(ProjectPaths.asmCacheIn(it), "projections"))
    }

    /** Capture on the UI thread; load/repair disposable projections on a worker, then publish only if still current. */
    suspend fun preloadProjections() {
        val owner = project()
        val text = sourceText()
        val macros = workspace.projectSourceText("macros.asm")
        val rooms = owner.rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        val repository = projectionRepository() ?: return
        val loaded = withContext(Dispatchers.Default) { runCatching { repository.load(text, macros, rooms, catalog) }.getOrDefault(emptyMap()) }
        if (project() !== owner || sourceText() != text || workspace.projectSourceText("macros.asm") != macros) return
        projectedText = text
        projectedMacros = macros
        projections.clear()
        projections.putAll(loaded.filter { (id, projection) ->
            owner.rooms[owner.roomKey(id)]?.let { runCatching { roomObjectIdentity(it) == projection.identity }.getOrDefault(false) } == true
        })
    }

    override fun afterSaved(fileId: String, text: String): (() -> Unit)? {
        if (fileId != PLM_SOURCE_FILE && fileId != "macros.asm") return null
        val repository = projectionRepository() ?: return null
        val source = if (fileId == PLM_SOURCE_FILE) text else workspace.projectSourceText(PLM_SOURCE_FILE)
        val macros = if (fileId == "macros.asm") text else workspace.projectSourceText("macros.asm")
        val rooms = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        // Only publish durable saved text; unsaved buffers must never masquerade as a startup snapshot.
        if (workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE) || workspace.hasUnsavedSourceChanges("macros.asm")) return null
        return { repository.load(source, macros, rooms, catalog); Unit }
    }

    init { if (registerSaveParticipant) workspace.sourceSaveParticipant = this }

    override val sourceFileIds: Set<String> get() = if (project().rooms.values.any { it.asmPlmSourceVersion != 0 }) {
        setOf(PLM_SOURCE_FILE)
    } else emptySet()

    override fun prepare(fileId: String, text: String): () -> String {
        if (fileId != PLM_SOURCE_FILE || fileId !in sourceFileIds) return { text }
        val rooms = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        val macrosText = workspace.projectSourceText("macros.asm")
        val readers = workspace.projectSourceReadersExcept(PLM_SOURCE_FILE)
        val describe = describePlm
        return transform@{
            // Save must still persist an invalid draft; Build remains the full validation boundary.
            if (runCatching { catalog.requireStandardMacro(macrosText) }.isFailure) return@transform text
            val references = readers.flatMap { AsmRoomObjectSource.referencedHelpers(it()) }.toSet()
            var result = text
            for (room in rooms) {
                result = runCatching {
                    val tidy = AsmRoomObjectSource(result, catalog.symbols).tidy(room, references)
                    AsmRoomObjectSource(tidy, catalog.symbols).withComments(room.roomId, describe)
                }.getOrDefault(result)
            }
            result
        }
    }

    fun isManaged(roomId: Int): Boolean = (project().rooms[project().roomKey(roomId)]?.asmPlmSourceVersion ?: 0) != 0

    fun sourceText(): String = workspace.projectSourceText(PLM_SOURCE_FILE)
    fun undoSource(expected: String) {
        require(sourceText() == expected) { "Source changed; undo the newer edit in ASM first." }
        check(workspace.undoSourceEdit(PLM_SOURCE_FILE)) { "Could not undo this source edit." }
    }
    fun redoSource(expected: String) {
        require(sourceText() == expected) { "Source changed; redo this edit in ASM instead." }
        check(workspace.redoSourceEdit(PLM_SOURCE_FILE)) { "Could not redo this source edit." }
    }
    fun hasStateSource(roomId: Int, stateId: String): Boolean = isManaged(roomId) && managedSource().contains(AsmRoomObjectSource.stateMacro(roomId, stateId))

    private fun managedSource(): AsmRoomObjectSource {
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val text = sourceText()
        return managedRevision?.takeIf { it.first == text }?.second
            ?: AsmRoomObjectSource(text, catalog.symbols).also { managedRevision = text to it }
    }

    fun managedView(roomId: Int, stateId: String): AsmPlmSourceView { return try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val identity = roomObjectIdentity(room)
        val macros = workspace.projectSourceText("macros.asm")
        val text = sourceText()
        catalog.requireStandardMacro(macros)
        if (projectedText == text && projectedMacros == macros) {
            projections[roomId]?.takeIf { it.identity == identity }?.documents?.get(stateId)?.let { document ->
                lastManaged[roomId to stateId] = document
                return AsmPlmSourceView(document, null)
            }
        } else {
            projectedText = text
            projectedMacros = macros
            projections.clear()
        }
        val source = managedSource()
        source.resources(room)
        val documents = room.states.associate { it.id to source.table(roomId, it.id).document }
        projections[roomId] = AsmRoomProjection(identity, documents)
        val document = requireNotNull(documents[stateId]) { "Missing authored state $stateId" }
        lastManaged[roomId to stateId] = document
        AsmPlmSourceView(document, null)
    } catch (problem: Exception) {
        AsmPlmSourceView(lastManaged[roomId to stateId], problem.message ?: "Invalid room-object source")
    } }

    fun managedPlacement(roomId: Int, stateId: String, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        val view = managedView(roomId, stateId)
        return if (view.problem != null) AsmPlmPlacementTarget.Unavailable(view.problem)
        else view.document?.placementTarget(entry) ?: AsmPlmPlacementTarget.Unavailable("No current object source.")
    }

    /** Membership, not implicit linking: equal rows in independent lists remain independently editable. */
    fun managedIsInAllStates(roomId: Int, stateId: String, entry: RomParser.PlmEntry): Boolean = runCatching {
        val room = project().rooms.getValue(project().roomKey(roomId))
        if (managedView(roomId, stateId).let { it.problem != null || it.document?.entries?.count { row -> row == entry } != 1 }) return@runCatching false
        room.states.all { managedView(roomId, it.id).let { view ->
            view.problem == null && view.document?.entries?.count { row -> row == entry } == 1
        } }
    }.getOrDefault(false)

    private fun externalObjectReferences(): Set<String> = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE)
        .flatMap { AsmRoomObjectSource.referencedHelpers(it).asSequence() }.toSet()

    fun managedEntries(): Sequence<RomParser.PlmEntry> = project().rooms.values.asSequence()
        .filter { it.asmPlmSourceVersion != 0 }.flatMap { room ->
            room.states.asSequence().flatMap { managedView(room.roomId, it.id).document?.entries.orEmpty().asSequence() }
        }

    fun editManaged(roomId: Int, stateId: String, allStates: Boolean,
                    remove: (RomParser.PlmEntry) -> Boolean, add: RomParser.PlmEntry?) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val source = managedSource()
        source.resources(room)
        val edited = AsmRoomObjectSource(source.edit(room, stateId, allStates, remove, add, externalObjectReferences()), catalog.symbols)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, edited.withComments(roomId, describePlm))
    }

    fun updateManaged(roomId: Int, stateId: String, expected: AsmPlmDocument, index: Int, entry: RomParser.PlmEntry) {
        val view = managedView(roomId, stateId)
        require(view.problem == null && view.document?.text == expected.text) { view.problem ?: "Source changed; reopen this object." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val updated = AsmRoomObjectSource(AsmRoomObjectSource(expected.replace(index, entry), catalog.symbols)
            .tidy(room, externalObjectReferences()), catalog.symbols)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected.text, updated.withComments(roomId, describePlm))
    }

    fun cloneManagedState(roomId: Int, template: String, stateId: String) {
        if (!isManaged(roomId)) return
        val source = managedSource()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, source.cloneState(roomId, template, stateId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == stateId } == true) {
                "Delete state $stateId in Room Info before undoing its object-source declaration."
            }
        })
    }

    /** Stage source and retire only its GUI object streams. No ROM/build/filesystem writes. */
    fun migrateRoom(roomId: Int, name: String, isItem: (Int) -> Boolean = { RomParser.isItemPlm(it) }) {
        if (isManaged(roomId)) return
        val current = project()
        require(current.asmWorkspace.enabled && current.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        require(current.newRooms.isEmpty()) { "Object source migration is not supported in projects with added rooms yet; their room graph still uses the ROM backend." }
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val room = current.rooms[current.roomKey(roomId)] ?: com.supermetroid.editor.data.RoomEdits(roomId)
        for ((pointer, consumers) in catalog.consumers) if (consumers.any { it.roomId == roomId }) {
            val owner = authority(pointer)
            require(owner.authority in setOf(AsmPlmAuthority.ROOM_EDITOR, AsmPlmAuthority.UNCHANGED)) {
                owner.explanation ?: "Original PLM source already has edits; automatic migration will not overwrite them."
            }
        }
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        val migration = AsmRoomObjectSource.migrate(before, room, catalog.parser, name, catalog.symbols, isItem, describePlm,
            externalObjectReferences())
        val oldCommon = room.plmChanges.toList()
        val oldStates = room.states.toList()
        val oldChanges = migration.states.associate { it.id to it.plmChanges.toList() }
        fun adopt() {
            require(room.asmPlmSourceVersion == 0 && room.plmChanges == oldCommon &&
                room.states == oldStates && migration.states.all { it.plmChanges == oldChanges[it.id] }) {
                "Room objects changed after migration was undone; migrate again instead of replaying stale source."
            }
            current.rooms[current.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(migration.states)
            room.plmChanges.clear()
            room.states.forEach { it.plmChanges.clear() }
            room.asmPlmSourceVersion = 1
            current.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION
            onMetadataChanged()
        }
        fun restore() {
            require(room.asmPlmSourceVersion == 1 && room.states.map { it.id } == migration.states.map { it.id } &&
                room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() }) {
                "Room state membership changed after migration. Undo those state changes before undoing object migration."
            }
            room.asmPlmSourceVersion = 0
            room.plmChanges.addAll(oldCommon)
            room.states.forEach { it.plmChanges.addAll(oldChanges[it.id].orEmpty()) }
            if (oldStates.isEmpty()) room.states.clear()
            onMetadataChanged()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, migration.text, ::restore, ::adopt)
        adopt()
    }

    fun authority(pointer: Int): AsmPlmAuthorityView {
        val current = project()
        if (!current.asmWorkspace.enabled || current.asmWorkspace.buildMode != ProjectRomBuildMode.ASM_SOURCE)
            return AsmPlmAuthorityView(AsmPlmAuthority.UNAVAILABLE, explanation = "Room objects use the loaded-ROM workflow.")
        val source = sourceText()
        val macros = workspace.projectSourceText("macros.asm")
        val revision = sourceRevision?.takeIf { it.first == source && it.second == macros }?.third ?: catalog.SourceRevision(source, macros)
            .also { sourceRevision = Triple(source, macros, it) }
        return revision.authority(current, pointer)
    }

    fun isLinked(pointer: Int): Boolean = authority(pointer).authority in setOf(AsmPlmAuthority.SOURCE, AsmPlmAuthority.CONFLICT)

    fun placementTarget(roomId: Int, stateIndex: Int, pointer: Int, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        if (catalog.consumers[pointer].orEmpty().none { it.roomId == roomId && it.stateIndex == stateIndex }) {
            return AsmPlmPlacementTarget.Unavailable("No verified ASM placement list matches this room state.")
        }
        val current = authority(pointer)
        return when (current.authority) {
            AsmPlmAuthority.SOURCE, AsmPlmAuthority.UNCHANGED -> current.document?.placementTarget(entry)
                ?: AsmPlmPlacementTarget.Unavailable("The current ASM placement list could not be read.")
            AsmPlmAuthority.ROOM_EDITOR -> AsmPlmPlacementTarget.Unavailable(
                "This room's placements are managed in the room editor. Editable ASM placement source is not available for this list yet.",
            )
            else -> AsmPlmPlacementTarget.Unavailable(current.explanation ?: "No verified ASM source matches this placement.")
        }
        // Deliberately do not use view(): its last-valid document may be older than an invalid draft.
    }

    /** Navigate to the authored declaration even when an invalid body cannot be decoded. */
    fun sourceLine(pointer: Int): Int? {
        val binding = runCatching { catalog.binding(pointer) }.getOrNull() ?: return null
        return workspace.projectSourceText(PLM_SOURCE_FILE).lineSequence()
            .indexOfFirst { it.substringBefore(';').trim() == "${binding.label}:" }.takeIf { it >= 0 }
    }

    fun sourceOwnedEntries(): Sequence<RomParser.PlmEntry> = catalog.consumers.keys.asSequence()
        .filter(::isLinked).flatMap { view(it).document?.entries.orEmpty().asSequence() } + managedEntries()

    fun link(pointer: Int) {
        val binding = catalog.binding(pointer)
        catalog.validateOwnership(project(), binding)
        val document = catalog.document(binding, workspace.projectSourceText(PLM_SOURCE_FILE), workspace.projectSourceText("macros.asm"))
        if (project().asmWorkspace.plmSourceBindings.none { it.pointer == pointer }) project().asmWorkspace.plmSourceBindings += binding
        lastValid[pointer] = document
    }

    fun unlink(pointer: Int) {
        val binding = project().asmWorkspace.plmSourceBindings.single { it.pointer == pointer }
        val view = view(pointer)
        require(view.problem == null && view.document?.let { catalog.isOriginal(binding, it) } == true) {
            "Restore this list's original values in ASM (or Undo its edits) before unlinking. Otherwise the room preview would lose your source edits."
        }
        project().asmWorkspace.plmSourceBindings.remove(binding)
        lastValid.remove(pointer)
    }

    fun view(pointer: Int): AsmPlmSourceView {
        val view = authority(pointer)
        return when (view.authority) {
            AsmPlmAuthority.SOURCE, AsmPlmAuthority.UNCHANGED -> {
                view.document?.let { lastValid[pointer] = it }
                AsmPlmSourceView(view.document, null)
            }
            AsmPlmAuthority.CONFLICT -> AsmPlmSourceView(
                lastValid[pointer].takeIf { catalog.guiOwner(project(), pointer) == null }, view.explanation)
            // Never project original ASM over GUI additions, per-state differences, or a rewritten state graph.
            else -> AsmPlmSourceView(null, view.explanation)
        }
    }

    fun update(pointer: Int, expected: AsmPlmDocument, index: Int, entry: RomParser.PlmEntry) {
        catalog.validateOwnership(project(), catalog.binding(pointer))
        val view = view(pointer)
        require(view.problem == null) { view.problem.orEmpty() }
        require(view.document?.text == expected.text) { "Source changed while this object was open. Reopen it before applying changes." }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected.text, expected.replace(index, entry))
    }
}
