package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.rom.validateSourceSaveOwnership
import com.supermetroid.editor.rom.words
import com.supermetroid.editor.rom.validateSourceSaveLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class AsmPlmSourceView(
    val document: AsmPlmDocument?,
    val problem: String?,
)

/** A pattern stroke is planned in memory, then published as one source/model edit. */
internal data class AsmPlmStamp(
    val entry: RomParser.PlmEntry,
    val allStates: Boolean,
    val companions: List<RomParser.PlmEntry> = emptyList(),
    val replaceIds: Set<Int> = setOf(entry.id),
    val save: SourceSaveResource? = null,
)

/** GUI lens over AsmWorkspaceState's document. Project JSON persists identity only, not object values. */
internal class AsmPlmSourceBridge(
    val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    registerSaveParticipant: Boolean = true,
    private val project: () -> SmEditProject,
) : AsmSourceSaveParticipant {
    var onMetadataChanged: () -> Unit = {}
    val initialScrolls = AsmInitialScrollBridge(catalog, workspace, project) { onMetadataChanged() }
    val fx = AsmFxBridge(catalog, workspace, project) { onMetadataChanged() }
    val enemies = AsmEnemyBridge(catalog, workspace, project) { onMetadataChanged() }
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

    override val sourceFileIds: Set<String> get() = buildSet {
        if (project().rooms.values.any { it.asmPlmSourceVersion != 0 || it.asmInitialScrollSourceVersion != 0 }) add(PLM_SOURCE_FILE)
        if (project().rooms.values.any { it.asmFxSourceVersion != 0 }) add(FX_SOURCE_FILE)
        if (project().rooms.values.any { it.asmEnemySourceVersion != 0 }) add(ENEMY_SOURCE_FILE)
    }

    override fun prepare(fileId: String, text: String): () -> String {
        if (fileId == FX_SOURCE_FILE && fileId in sourceFileIds) return fx.prepare(text)
        if (fileId == ENEMY_SOURCE_FILE && fileId in sourceFileIds) return enemies.prepare(text)
        if (fileId != PLM_SOURCE_FILE || fileId !in sourceFileIds) return { text }
        val rooms = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        val initialRooms = project().rooms.values.filter { it.asmInitialScrollSourceVersion != 0 }.map { room ->
            room.copy(scrollChanges = room.scrollChanges.toMutableList(), states = room.states.map { it.copy(scrollChanges = it.scrollChanges.toMutableList()) }.toMutableList())
        }
        val macrosText = workspace.projectSourceText("macros.asm")
        val readers = workspace.projectSourceReadersExcept(PLM_SOURCE_FILE)
        val describe = describePlm
        return transform@{
            // Save must still persist an invalid draft; Build remains the full validation boundary.
            val otherTexts = readers.map { it() }
            val initialReferences = otherTexts.flatMap { AsmInitialScrollSource.references(it) }.toSet()
            var result = text
            for (room in initialRooms) result = runCatching {
                val header = requireNotNull(catalog.parser.readRoomHeader(room.roomId))
                val width = room.roomHeaderChange?.width ?: header.width
                val height = room.roomHeaderChange?.height ?: header.height
                val source = AsmInitialScrollSource(result, catalog.symbols)
                source.resources(room, width, height)
                AsmInitialScrollSource(source.tidy(room, initialReferences), catalog.symbols).withComments(room, width, height)
            }.getOrDefault(result)
            if (runCatching { catalog.requireStandardMacro(macrosText) }.isFailure) return@transform result
            val references = otherTexts.flatMap { AsmRoomObjectSource.referencedHelpers(it) }.toSet()
            for (room in rooms) {
                result = runCatching {
                    val tidy = AsmRoomObjectSource(result, catalog.symbols).tidy(room, references)
                    val annotated = AsmRoomObjectSource(tidy, catalog.symbols).withComments(room.roomId, describe)
                    val header = requireNotNull(catalog.parser.readRoomHeader(room.roomId))
                    AsmRoomObjectSource(annotated, catalog.symbols).withScrollComments(room,
                        room.roomHeaderChange?.width ?: header.width, room.roomHeaderChange?.height ?: header.height)
                }.getOrDefault(result)
            }
            result
        }
    }

    fun isManaged(roomId: Int): Boolean = (project().rooms[project().roomKey(roomId)]?.asmPlmSourceVersion ?: 0) != 0

    fun sourceText(): String = workspace.projectSourceText(PLM_SOURCE_FILE)
    fun sourceUndoHead(fileId: String = PLM_SOURCE_FILE): AsmSourceTextChange? = workspace.sourceUndoHead(fileId)
    fun undoSource(expected: String, fileId: String = PLM_SOURCE_FILE, change: AsmSourceTextChange? = null) {
        require(workspace.projectSourceText(fileId) == expected) { "Source changed; undo the newer edit in ASM first." }
        require(change == null || workspace.sourceUndoHead(fileId) === change) { "Source history changed; undo the newer edit in ASM first." }
        check(workspace.undoSourceEdit(fileId)) { "Could not undo this source edit." }
    }
    fun redoSource(expected: String, fileId: String = PLM_SOURCE_FILE, change: AsmSourceTextChange? = null) {
        require(workspace.projectSourceText(fileId) == expected) { "Source changed; redo this edit in ASM instead." }
        require(change == null || workspace.sourceRedoHead(fileId) === change) { "Source history changed; redo this edit in ASM instead." }
        check(workspace.redoSourceEdit(fileId)) { "Could not redo this source edit." }
    }
    fun hasStateSource(roomId: Int, stateId: String): Boolean = isManaged(roomId) && managedSource().contains(AsmRoomObjectSource.stateMacro(roomId, stateId))

    private fun managedSource(): AsmRoomObjectSource {
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val text = sourceText()
        return managedRevision?.takeIf { it.first == text }?.second
            ?: AsmRoomObjectSource(text, catalog.symbols).also { managedRevision = text to it }
    }

    private fun dimensions(roomId: Int): Pair<Int, Int> {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return (room.roomHeaderChange?.width ?: header.width) to (room.roomHeaderChange?.height ?: header.height)
    }

    fun scrollCommands(roomId: Int, stateId: String, entry: RomParser.PlmEntry): List<ScrollCommand> {
        require(entry.id == 0xB703) { "Only a scroll trigger has a command list." }
        val view = managedView(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid room object source." }
        val record = requireNotNull(view.document).records.singleOrNull { it.entry == entry }
            ?: error("Select one unambiguous current scroll trigger.")
        val (width, height) = dimensions(roomId)
        return record.scrollResource?.let { managedSource().scrollResource(roomId, it, width * height).commands }
            ?: AsmScrollCommandSource.original(sourceText(), catalog.symbols, entry.param, catalog.parser, width * height, catalog.originalText)
    }

    fun editScroll(roomId: Int, stateId: String, x: Int, y: Int, commands: List<ScrollCommand>, allStates: Boolean = false) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val source = managedSource()
        val result = source.editScroll(room, stateId, x, y, commands, width, height, externalObjectReferences(), allStates)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, AsmRoomObjectSource(result, catalog.symbols).withComments(roomId, describePlm))
    }

    fun saveResources(roomId: Int): List<SourceSaveResource> = managedSource().saveResources(roomId)

    fun saveSpawn(roomId: Int, area: Int, slot: Int): SourceSaveResource? {
        val save = saveResources(roomId).singleOrNull { it.area == area && it.slot == slot } ?: return null
        validateSourceSaveOwnership(save, catalog.parser)
        require(save.area == (project().rooms[project().roomKey(roomId)]?.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(roomId)?.area)) {
            "Save source area changed; cross-area migration is not source-aware yet."
        }
        return save
    }

    private fun replaceSaveSource(roomId: Int, source: AsmRoomObjectSource, result: String, slots: Set<Pair<Int, Int>>) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val retired = room.saveStationSpawns.filter { it.area to it.saveIndex in slots }
        fun retire() {
            require(room.saveStationSpawns.filter { it.area to it.saveIndex in slots } == retired) { "Save overrides changed; reopen the station." }
            room.saveStationSpawns.removeAll(retired.toSet())
            if (retired.isNotEmpty()) onMetadataChanged()
        }
        fun restore() {
            require(room.saveStationSpawns.none { it.area to it.saveIndex in slots }) { "Undo newer save changes first." }
            room.saveStationSpawns.addAll(retired)
            if (retired.isNotEmpty()) onMetadataChanged()
        }
        // Validate the prospective source and retired metadata before publishing either.
        val candidate = room.copy(saveStationSpawns = room.saveStationSpawns.filterNot { it in retired }.toMutableList())
        val checked = AsmRoomObjectSource(result, catalog.symbols)
        val checkedResources = checked.resources(candidate)
        validateSourceSaveLinks(roomId, candidate.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(roomId)!!.area,
            checkedResources.values.flatMap { it.entries }, checked.saveResources(roomId), candidate.saveStationSpawns, catalog.parser)
        checked.saveResources(roomId).forEach { validateSourceSaveOwnership(it, catalog.parser) }
        project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { checked.saveResources(it.roomId) }
            .groupBy { it.area to it.slot }.forEach { (_, owners) ->
                require(owners.distinct().size == 1) { "Another source room already owns this save slot." }
            }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, checked.withComments(roomId, describePlm), ::restore, ::retire)
        retire()
    }

    fun updateSaveSpawn(resource: SourceSaveResource) {
        val source = managedSource()
        source.resources(project().rooms.getValue(project().roomKey(resource.ownerRoomId)))
        validateSourceSaveOwnership(resource, catalog.parser)
        replaceSaveSource(resource.ownerRoomId, source, source.withSave(resource), setOf(resource.area to resource.slot))
    }

    fun editSaveStation(roomId: Int, stateId: String, remove: (RomParser.PlmEntry) -> Boolean,
                        add: RomParser.PlmEntry?, spawn: SourceSaveResource, allStates: Boolean = false) {
        val source = managedSource()
        val room = project().rooms.getValue(project().roomKey(roomId))
        val stagedRoom = room.copy(saveStationSpawns = room.saveStationSpawns.filterNot { it.area == spawn.area && it.saveIndex == spawn.slot }.toMutableList())
        validateSourceSaveOwnership(spawn, catalog.parser)
        // Adopt the spawn first so deleting an original station can write an explicit released record.
        val staged = AsmRoomObjectSource(source.withSave(spawn), catalog.symbols)
        val result = staged.editSaveStation(stagedRoom, stateId, remove, add, spawn, externalObjectReferences(), allStates)
        replaceSaveSource(roomId, source, result, setOf(spawn.area to spawn.slot))
    }

    fun availableSaveSlot(roomId: Int, area: Int, reservedSlots: Set<Int> = emptySet()): Int? {
        require(area in 0..5) { "Ceres/debug load records are not ordinary save slots." }
        val all = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { room -> saveResources(room.roomId) }
        val entries = project().rooms.values.filter { it.asmPlmSourceVersion != 0 &&
            (it.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(it.roomId)?.area) == area
        }.flatMap { room -> room.states.flatMap { managedView(room.roomId, it.id).let { view ->
            require(view.problem == null) { view.problem ?: "Invalid save source." }
            view.document!!.entries.filter { it.id == 0xB76F }
        } } }
        val legacy = project().rooms.values.flatMap { it.saveStationSpawns }
        for (slot in 0 until catalog.parser.saveStationSlotCount(area)) {
            if (slot in reservedSlots) continue
            val claim = all.singleOrNull { it.area == area && it.slot == slot }
            if (all.count { it.area == area && it.slot == slot } > 1) error("Conflicting authored save slots need review.")
            if (claim != null) {
                if (claim.ownerRoomId == roomId && claim.cleared) return slot
                continue
            }
            if (catalog.parser.readSaveEntry(area, slot)?.roomId != 0 || legacy.any { it.area == area && it.saveIndex == slot } ||
                entries.any { (it.param and 255) == slot }) continue
            return slot
        }
        return null
    }

    fun saveTarget(roomId: Int, area: Int, slot: Int): AsmPlmPlacementTarget = try {
        val resource = requireNotNull(saveSpawn(roomId, area, slot)) { "No authored spawn record yet." }
        require(!resource.cleared) { "This save slot is released." }
        AsmPlmPlacementTarget.Source(sourceText().take(managedSource().macro(resource.identity).start).count { it == '\n' }, 0)
    } catch (error: Exception) { AsmPlmPlacementTarget.Unavailable(error.message ?: "Invalid save source") }

    fun hasSaveSource(roomId: Int, area: Int, slot: Int) = managedSource().contains(AsmSaveStationSource.name(roomId, area, slot))

    fun scrollTarget(roomId: Int, stateId: String, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        return try {
            scrollCommands(roomId, stateId, entry) // Do not navigate to stale/invalid source.
            val record = managedView(roomId, stateId).document!!.records.single { it.entry == entry }
            val (width, height) = dimensions(roomId)
            val name = record.scrollResource ?: AsmScrollCommandSource.verifiedOriginalName(catalog.originalText, catalog.symbols,
                entry.param, catalog.parser, width * height)
                ?: return AsmPlmPlacementTarget.Unavailable("This ROM stream has no verified named ASM declaration yet; Edit commands to create one.")
            val line = if (record.scrollResource != null) sourceText().take(managedSource().macro(name).start).count { it == '\n' }
                else sourceText().lineSequence().indexOfFirst { it.startsWith("$name:") }
            AsmPlmPlacementTarget.Source(line, 0)
        } catch (error: Exception) { AsmPlmPlacementTarget.Unavailable(error.message ?: "Invalid scroll source") }
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
        val (width, height) = dimensions(roomId)
        val inputs = source.resources(room, width * height)
        validateSourceSaveLinks(roomId, room.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(roomId)!!.area,
            inputs.values.flatMap { it.entries }, source.saveResources(roomId), room.saveStationSpawns, catalog.parser)
        source.saveResources(roomId).forEach { save ->
            require(save.area == (room.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(roomId)?.area)) { "Save source area ownership changed." }
            validateSourceSaveOwnership(save, catalog.parser)
        }
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
        val commands = if (entry.id == 0xB703) scrollCommands(roomId, stateId, entry) else null
        room.states.all { managedView(roomId, it.id).let { view ->
            view.problem == null && view.document?.entries?.count { row -> row == entry } == 1 &&
                (commands == null || scrollCommands(roomId, it.id, entry) == commands)
        } }
    }.getOrDefault(false)

    private fun externalObjectReferences(): Set<String> = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE)
        .flatMap { AsmRoomObjectSource.referencedHelpers(it).asSequence() }.toSet()

    fun managedEntries(): Sequence<RomParser.PlmEntry> = project().rooms.values.asSequence()
        .filter { it.asmPlmSourceVersion != 0 }.flatMap { room ->
            room.states.asSequence().flatMap { managedView(room.roomId, it.id).document?.entries.orEmpty().asSequence() }
        }

    fun editManaged(roomId: Int, stateId: String, allStates: Boolean,
                    remove: (RomParser.PlmEntry) -> Boolean, add: RomParser.PlmEntry?, companions: List<RomParser.PlmEntry> = emptyList()) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val source = managedSource()
        source.resources(room)
        val edited = AsmRoomObjectSource(source.edit(room, stateId, allStates, remove, add, externalObjectReferences(), companions = companions), catalog.symbols)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, edited.withComments(roomId, describePlm))
    }

    /** No workspace or model mutation until every row, spawn and resource link is checked. */
    fun applyStamps(roomId: Int, stateId: String, expected: String, stamps: List<AsmPlmStamp>,
                    beforeUndo: () -> Unit, beforeRedo: () -> Unit): Boolean {
        require(sourceText() == expected) { "Object source changed during painting; try the stroke again." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val external = externalObjectReferences()
        var source = managedSource()
        source.resources(room, width * height)
        for (stamp in stamps) {
            val entry = stamp.entry
            val remove: (RomParser.PlmEntry) -> Boolean = { it.x == entry.x && it.y == entry.y && it.id in stamp.replaceIds }
            val states = if (stamp.allStates) room.states.map { it.id } else listOf(stateId)
            val target = (stamp.companions + entry).groupingBy { it }.eachCount()
            if (states.all { source.table(roomId, it).document.entries.filter(remove).groupingBy { it }.eachCount() == target } &&
                (stamp.save == null || stamp.save in source.saveResources(roomId))) continue
            val updated = if (stamp.save != null) {
                validateSourceSaveOwnership(stamp.save, catalog.parser)
                require(room.saveStationSpawns.none { it.area == stamp.save.area && it.saveIndex == stamp.save.slot }) {
                    "Adopt the save spawn before stamping over its GUI override."
                }
                val staged = AsmRoomObjectSource(source.withSave(stamp.save), catalog.symbols)
                staged.editSaveStation(room, stateId, remove, entry, stamp.save, external, stamp.allStates)
            } else source.edit(room, stateId, stamp.allStates, remove, entry, external, companions = stamp.companions)
            source = AsmRoomObjectSource(updated, catalog.symbols)
        }
        val resources = source.resources(room, width * height)
        val saves = source.saveResources(roomId)
        validateSourceSaveLinks(roomId, room.roomHeaderChange?.area ?: catalog.parser.readRoomHeader(roomId)!!.area,
            resources.values.flatMap { it.entries }, saves, room.saveStationSpawns, catalog.parser)
        saves.forEach { validateSourceSaveOwnership(it, catalog.parser) }
        project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { source.saveResources(it.roomId) }
            .groupBy { it.area to it.slot }.forEach { (_, owners) ->
                require(owners.distinct().size == 1) { "Another source room already owns this save slot." }
            }
        val after = source.withComments(roomId, describePlm)
        if (after == expected) return false
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected, after, beforeUndo, beforeRedo)
        return true
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
        AsmRoomObjectSource(migration.text, catalog.symbols).saveResources(roomId).forEach { validateSourceSaveOwnership(it, catalog.parser) }
        val oldCommon = room.plmChanges.toList()
        val oldSpawns = room.saveStationSpawns.toList()
        val oldScrolls = room.customScrollCommands.mapValues { it.value.toList() }
        val oldStates = room.states.toList()
        val oldChanges = migration.states.associate { it.id to it.plmChanges.toList() }
        val oldStateScrolls = migration.states.associate { it.id to it.customScrollCommands.mapValues { (_, value) -> value.toList() } }
        fun adopt() {
            require(room.asmPlmSourceVersion == 0 && room.plmChanges == oldCommon &&
                room.states == oldStates && room.customScrollCommands == oldScrolls && room.saveStationSpawns == oldSpawns &&
                migration.states.all { it.plmChanges == oldChanges[it.id] && it.customScrollCommands == oldStateScrolls[it.id] }) {
                "Room objects changed after migration was undone; migrate again instead of replaying stale source."
            }
            current.rooms[current.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(migration.states)
            room.plmChanges.clear()
            room.customScrollCommands.clear()
            room.saveStationSpawns.clear()
            room.states.forEach { it.plmChanges.clear(); it.customScrollCommands.clear() }
            room.asmPlmSourceVersion = 1
            current.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION
            onMetadataChanged()
        }
        fun restore() {
            require(room.asmPlmSourceVersion == 1 && room.states.map { it.id } == migration.states.map { it.id } &&
                room.plmChanges.isEmpty() && room.customScrollCommands.isEmpty() && room.saveStationSpawns.isEmpty() &&
                room.states.all { it.plmChanges.isEmpty() && it.customScrollCommands.isEmpty() }) {
                "Room state membership changed after migration. Undo those state changes before undoing object migration."
            }
            room.asmPlmSourceVersion = 0
            room.plmChanges.addAll(oldCommon)
            room.customScrollCommands.putAll(oldScrolls.mapValues { it.value.toMutableList() })
            room.saveStationSpawns.addAll(oldSpawns)
            room.states.forEach {
                it.plmChanges.addAll(oldChanges[it.id].orEmpty())
                it.customScrollCommands.putAll(oldStateScrolls[it.id].orEmpty().mapValues { (_, value) -> value.toMutableList() })
            }
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
