package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCompiledBuildBase
import com.supermetroid.editor.asm.AsmBuildArtifactRepository
import com.supermetroid.editor.asm.AsmCompilationException
import com.supermetroid.editor.asm.ASM_ROOM_GRAPH_OWNER
import com.supermetroid.editor.asm.AsmProjectCompiler
import com.supermetroid.editor.asm.AsmPatchBackendRegistry
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmRoomObjectSource
import com.supermetroid.editor.asm.AsmScrollCommandSource
import com.supermetroid.editor.asm.AsmWlaSymbolParser
import com.supermetroid.editor.asm.PLM_SOURCE_FILE
import com.supermetroid.editor.asm.AsmSourceAssetMaterializer
import com.supermetroid.editor.asm.AsmSourcePatchMaterializer
import com.supermetroid.editor.asm.AsmSourceRoomMaterializer
import com.supermetroid.editor.asm.copyForReuse
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.words
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

internal object ProjectFileService {
    private val asmProjectCompiler = AsmProjectCompiler()
    private const val PREPARED_ASM_CACHE_VERSION = "smedit-prepared-asm-base-v6-save-resources"
    private const val MAX_PREPARED_ASM_BASES = 2
    private val preparedAsmBases = object : LinkedHashMap<String, AsmCompiledBuildBase>(
        MAX_PREPARED_ASM_BASES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AsmCompiledBuildBase>?,
        ): Boolean = size > MAX_PREPARED_ASM_BASES
    }
    private val json = Json {
        // Project files can contain hundreds of thousands of generated tile edits.
        // Keep saves compact; use jq or an editor formatter when human-readable JSON is needed.
        prettyPrint = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun loadProject(file: File): SmEditProject =
        SmEditProjectFormat.decode(json, file.readText()).let { saved ->
            val project = saved.copy(
                romPath = ProjectPaths(file).resolveBaseRom(saved.romPath).absolutePath,
                // Keep preferences stable even before a metadata-less project
                // has been saved once; that first save persists the ID on moves.
                projectId = saved.projectId.ifBlank {
                    java.util.UUID.nameUUIDFromBytes(file.canonicalPath.toByteArray()).toString()
                },
            )
            // Markerless beta files use the same semantic model with defaulted fields.
            // Normalize the in-memory project so its next save writes the current schema.
            project.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION
            project
        }

    /** Isolate non-writing previews from exporter metadata hydration. */
    fun snapshotProject(project: SmEditProject): SmEditProject =
        SmEditProjectFormat.decode(json, json.encodeToString(SmEditProject.serializer(), project))

    /** Capture mutable editor data on its caller thread; decode/persist this detached snapshot on a worker. */
    fun serializeProject(project: SmEditProject): String = json.encodeToString(SmEditProject.serializer(), project)

    fun saveSerializedProject(
        text: String,
        projectFilePath: String,
        onLog: (String) -> Unit,
        romParser: RomParser? = null,
        savePatternLibrary: Boolean = false,
    ): Boolean = saveProject(SmEditProjectFormat.decode(json, text), projectFilePath, romParser, savePatternLibrary, onLog)

    fun saveProject(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser?,
        savePatternLibrary: Boolean,
        onLog: (String) -> Unit,
    ): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            val emptyKeys = project.rooms.entries.filter { !it.value.hasEdits }.map { it.key }
            for (key in emptyKeys) project.rooms.remove(key)

            val projectFile = File(projectFilePath)
            if (projectFile.isFile) {
                val existingVersion = runCatching {
                    SmEditProjectFormat.decode(json, projectFile.readText()).projectFormatVersion
                }.getOrNull()
                if (existingVersion != null && existingVersion < project.projectFormatVersion) {
                    val backup = File(projectFile.parentFile, "${projectFile.name}.before-schema-upgrade.backup")
                    if (!backup.exists()) {
                        projectFile.copyTo(backup, overwrite = false)
                        onLog("Backed up project before schema upgrade: ${backup.absolutePath}")
                    }
                }
            }
            val paths = ProjectPaths(projectFile)
            val base = paths.resolveBaseRom(project.romPath)
            val portable = project.copy(
                romPath = paths.relativeBaseRom(base),
                baseRom = project.baseRom ?: base.takeIf(File::isFile)?.let(ProjectLifecycle::identity),
            )
            writeBytesAtomically(projectFile, json.encodeToString(SmEditProject.serializer(), portable).toByteArray())
            onLog("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(project, projectFilePath, it, onLog) }
            if (savePatternLibrary) PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) {
            onLog("Save failed: ${e.message}")
            false
        }
    }

    fun exportToRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? = withBuildFailureReport(project, projectFilePath, onLog, onStatus, "Export failed safely") { status ->
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, status)
        val exporter = RomExporter(project, romParser, onLog, status, compiledAsmBase = compiledAsmBase, projectFilePath = projectFilePath)
        val built = exporter.build()
        exporter.export(built).also { path ->
            if (path != null && built != null) publishRoomData(project, projectFilePath, romParser, built, onLog)
        }
    }

    fun buildRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): RomBuildResult? = withBuildFailureReport(project, projectFilePath, onLog, onStatus, "Export preview failed safely") { status ->
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, status)
        RomExporter(
            project = project,
            romParser = romParser,
            onLog = onLog,
            onStatus = status,
            logWritePlanDetails = false,
            compiledAsmBase = compiledAsmBase,
        ).build()?.also { publishRoomData(project, projectFilePath, romParser, it, onLog) }
    }

    private fun publishRoomData(project: SmEditProject, projectFilePath: String, inputParser: RomParser, build: RomBuildResult, onLog: (String) -> Unit) {
        if (project.asmWorkspace.buildMode != ProjectRomBuildMode.ASM_SOURCE || projectFilePath.isBlank()) return
        runCatching {
            // Keep new-room builds explicit until stable source identities are supported.
            val source = if (project.newRooms.isEmpty()) {
                // Headers retain their input identities. Rediscovering the built ROM can
                // drop rooms with generated selector redirects from the heuristic scanner.
                com.supermetroid.editor.asm.AsmRoomDataSnapshot.render(build.resultRom, inputParser.roomCatalog.rooms)
            } else "; Room Data is not available for projects with added rooms yet. Room edits still use the ROM backend.\n"
            AsmBuildArtifactRepository().publishRoomData(projectFilePath, source)
        }.onFailure { onLog("Could not retain Room Data snapshot: ${it.message}") }
    }

    /** Retain failures from planning/final export as well as from Asar itself.
     * Both the emulator export and non-writing Build use the same reporting path. */
    private fun <T> withBuildFailureReport(
        project: SmEditProject,
        projectFilePath: String,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        failurePrefix: String,
        build: ((String) -> Unit) -> T?,
    ): T? {
        var latestStatus = ""
        fun status(message: String) {
            latestStatus = message
            onStatus(message)
        }
        val result = try {
            build(::status)
        } catch (problem: Exception) {
            val message = "$failurePrefix: ${problem.message ?: problem::class.simpleName}"
            onLog("ERROR: $message")
            status(message)
            null
        }
        if (result == null && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && projectFilePath.isNotBlank()) {
            runCatching {
                AsmBuildArtifactRepository().publishPipelineFailure(
                    projectFilePath,
                    latestStatus.ifBlank { "$failurePrefix. No ROM was built." },
                )
            }.onFailure { onLog("Could not retain ASM build failure: ${it.message}") }
        }
        return result
    }

    fun exportToIps(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? {
        val original = romParser.getRomData()
        val smcPath = exportToRom(project, romParser, onLog, onStatus, projectFilePath) ?: return null
        val patched = File(smcPath).readBytes()
        val ipsData = buildIpsPatch(original, patched)
        val ipsFile = File(smcPath).let { File(it.parentFile, "${it.nameWithoutExtension}.ips") }
        try {
            writeBytesAtomically(ipsFile, ipsData)
        } catch (e: Exception) {
            val message = "Export failed safely while writing IPS: ${e.message ?: e::class.simpleName}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }
        val message = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        onLog(message)
        onStatus(message)
        return ipsFile.absolutePath
    }

    private fun prepareBuildBase(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): AsmCompiledBuildBase? {
        if (project.asmWorkspace.buildMode == ProjectRomBuildMode.PATCHED_ROM) {
            require(project.rooms.values.none { it.asmPlmSourceVersion != 0 }) { "Authored room objects require ASM source mode." }
            require(project.asmWorkspace.plmSourceBindings.isEmpty()) { "Linked room objects require ASM source mode." }
            val sourceEdits = if (project.asmWorkspace.enabled && projectFilePath.isNotBlank()) {
                AsmProjectWorkspaceRepository().sourceOverrideFileIds(projectFilePath)
            } else {
                emptySet()
            }
            require(sourceEdits.isEmpty()) {
                "Loaded ROM mode cannot ignore saved ASM source edits (${sourceEdits.sorted().joinToString()}). " +
                    "Restore those files to their original source or select ASM source mode."
            }
            return null
        }
        require(project.asmWorkspace.enabled) { "Enable Project ASM before selecting the ASM source build" }
        require(projectFilePath.isNotBlank()) { "Save the SMEDIT project before building from ASM source" }
        onLog("[ASM-BUILD] Preparing clean source build for $projectFilePath")
        val compiler = asmProjectCompiler
        val loadedRom = romParser.copyRomData()
        val sourceInputs = compiler.sourceInputsFingerprint(projectFilePath, loadedRom) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        }
        // Automatic ownership must work even when this room was never visited and no binding was saved.
        val linkedWorkspace = requireNotNull(AsmProjectWorkspaceRepository().loadForBuild(projectFilePath))
        linkedWorkspace.let { tree ->
            for (fileId in listOf(PLM_SOURCE_FILE, "macros.asm")) {
                val override = File(tree.root, "overrides/src/$fileId")
                val authored = override.takeIf { it.isFile } ?: File(tree.originalDirectory, "src/$fileId")
                require(authored.readText() == File(tree.workingDirectory, "src/$fileId").readText()) {
                    "$fileId changed outside SMEDIT. Reconcile the source and its workspace copy before building room objects."
                }
            }
        }
        val preparedKey = preparedAsmCacheKey(project, sourceInputs)
        getPreparedAsmBase(preparedKey)?.let { cached ->
            // A different prepared build may have replaced the on-disk report.
            // Restore symbols and generated source together when revisiting this build.
            runCatching {
                AsmBuildArtifactRepository().publishSuccess(
                    projectFilePath, cached.compilerSymbols, cached.compilerOutput, cached.assemblerVersion,
                    generatedRoomSource = cached.generatedRoomSource,
                )
            }
            val message = "ASM/project inputs unchanged — reusing prepared source base…"
            onLog("[ASM-BUILD] $message")
            onStatus(message)
            return cached
        }
        val linkedCatalog = AsmPlmSourceCatalog.load(linkedWorkspace.originalDirectory, romParser)
        val linkedSource = File(linkedWorkspace.workingDirectory, "src/$PLM_SOURCE_FILE").readText()
        val linkedMacros = File(linkedWorkspace.workingDirectory, "src/macros.asm").readText()
        val managedRooms = project.rooms.values.filter { it.asmPlmSourceVersion != 0 }
        if (managedRooms.isNotEmpty()) linkedCatalog.requireStandardMacro(linkedMacros)
        val managedSource = AsmRoomObjectSource(linkedSource, linkedCatalog.symbols)
        val sourcePlmSets = managedRooms.associate { room ->
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            room.roomId to managedSource.resources(room, (room.roomHeaderChange?.width ?: header.width) * (room.roomHeaderChange?.height ?: header.height))
        }
        val sourceScrolls = sourcePlmSets.values.flatMap { states -> states.values.flatMap { it.scrollReferences.values } }
            .groupBy { it.identity }.mapValues { (name, resources) ->
                require(resources.distinct().size == 1) { "Shared scroll source $name has inconsistent values." }
                resources.first()
            }
        val sourceSaves = sourcePlmSets.values.flatMap { states -> states.values.flatMap { it.saves } }.groupBy { it.identity }
            .mapValues { (_, references) ->
                require(references.distinct().size == 1) { "Shared save source has inconsistent fields." }
                references.first().also { com.supermetroid.editor.rom.validateSourceSaveOwnership(it, romParser) }
            }
        // Numeric original pointers are fixed-layout references. Validate their current source too;
        // raw byte syntax alone would happily compile invalid colors or a shifted command stream.
        val originalScrollInputs = linkedMapOf<Int, List<ScrollCommand>>()
        for (room in managedRooms) {
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            val screens = (room.roomHeaderChange?.width ?: header.width) * (room.roomHeaderChange?.height ?: header.height)
            for (resource in sourcePlmSets.getValue(room.roomId).values) resource.entries.forEachIndexed { index, entry ->
                if (entry.id == 0xB703 && index !in resource.scrollReferences) {
                    val commands = AsmScrollCommandSource.original(linkedSource, linkedCatalog.symbols,
                        entry.param, romParser, screens, linkedCatalog.originalText)
                    val prior = originalScrollInputs.put(entry.param, commands)
                    require(prior == null || prior == commands) { "Shared original scroll commands have inconsistent values." }
                }
            }
        }
        // Resolve known collisions before spending time in Asar. GUI-only lists stay GUI-owned.
        linkedCatalog.sourceBindings(project, linkedSource, linkedMacros)
        fun protectLinkedObjects(base: AsmCompiledBuildBase): AsmCompiledBuildBase = linkedCatalog.protectBuild(
            project,
            linkedSource,
            linkedMacros,
            base,
        )
        val initial = protectLinkedObjects(compiler.compile(
            projectFilePath = projectFilePath,
            loadedRom = loadedRom,
        ) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        })
        val compiledSaveParser = RomParser(initial.romBytes)
        for (save in sourceSaves.values) {
            val baseline = requireNotNull(romParser.readSaveEntry(save.area, save.slot))
            val compiled = requireNotNull(compiledSaveParser.readSaveEntry(save.area, save.slot))
            require(baseline.words() == compiled.words()) {
                "Save slot ${save.area}:${save.slot} also changed in original Bank \$80 source. Resolve ownership before building the authored save record."
            }
        }
        if (originalScrollInputs.isNotEmpty()) {
            val built = RomParser(initial.romBytes)
            val labels = AsmWlaSymbolParser.parse(initial.compilerSymbols.toString(Charsets.UTF_8)).labels
            for ((pointer, expected) in originalScrollInputs) {
                val name = linkedCatalog.symbols.entries.firstOrNull { it.value == pointer && it.key.startsWith("RoomPLM_") }?.key
                require(name == null || labels[name] == (0x8F0000 or pointer)) {
                    "$name moved from its original address. Use Edit commands to create a relocatable scroll resource."
                }
                val actual = AsmScrollCommandSource.original("", emptyMap(), pointer, built, 128)
                require(actual == expected) {
                    "Scroll pointer \$${pointer.toString(16).uppercase()} no longer matches the room preview. Use Edit commands to import a named resource."
                }
            }
        }
        onLog("[ASM-BUILD] Planning editor-owned source assets…")
        val planningLog = mutableListOf<String>()
        val planned = RomExporter(
            project = project,
            romParser = romParser,
            onLog = planningLog::add,
            onStatus = {},
            logWritePlanDetails = false,
            compiledAsmBase = initial,
            sourcePlmSets = sourcePlmSets,
        ).build() ?: throw AsmCompilationException(
            planningLog.lastOrNull { it.startsWith("ERROR:") }
                ?.removePrefix("ERROR: ")
                ?: "Could not validate editor data against the compiled ASM source"
        )
        val roomMaterialization = AsmSourceRoomMaterializer().materialize(
            project, planned.writeReport, planned.resultRom, initial.referenceRomBody.size, sourceScrolls, sourceSaves,
        )
        val authoredResources = managedRooms.flatMap { room -> room.states.map { state ->
            AsmRoomObjectSource.stateMacro(room.roomId, state.id) to managedSource.table(room.roomId, state.id).resource.name
        } }.toMap()
        val emitted = roomMaterialization.plmTables.filter { it.authoredMacro != null }
        require(roomMaterialization.saveTables.map { it.resource.identity }.toSet() == sourceSaves.keys) {
            "Authored save records could not be included atomically in this build; no ROM was produced."
        }
        require(roomMaterialization.scrollTables.map { it.resource.identity }.toSet() == sourceScrolls.keys) {
            "Authored scroll source could not be included atomically in this room build; no ROM was produced."
        }
        require(emitted.map { authoredResources.getValue(it.authoredMacro!!) }.toSet() == authoredResources.values.toSet() &&
            emitted.sumOf { it.pointerPcOffsets.size } == authoredResources.size) {
            "Authored object source could not be included atomically in this room build; no ROM was produced."
        }
        val materialization = AsmSourceAssetMaterializer().materialize(
            projectFilePath = projectFilePath,
            report = planned.writeReport,
            excludedOwners = if (roomMaterialization.isEmpty) emptySet() else setOf(ASM_ROOM_GRAPH_OWNER),
        )
        val patchMaterialization = AsmSourcePatchMaterializer().materialize(
            report = planned.writeReport,
            sourceOwners = AsmPatchBackendRegistry.sourceOwners(project.patches),
            compiledRomSize = AsmProjectCompiler.ASM_ROM_SIZE,
        )
        val compiled = if (materialization.isEmpty && roomMaterialization.isEmpty && patchMaterialization.isEmpty) {
            onLog(
                "[ASM-BUILD] No fixed source assets or source-capable patches required generation " +
                    "(${materialization.skippedWrites}/${materialization.eligibleWrites} asset candidate writes " +
                    "and ${roomMaterialization.skippedWrites}/${roomMaterialization.eligibleWrites} room writes " +
                    "remain post-compile)"
            )
            initial
        } else {
            val generatedBytes = materialization.claims.sumOf { it.length }
            val generatedRoomBytes = roomMaterialization.claims.sumOf { it.length }
            val generatedPatchBytes = patchMaterialization.claims.sumOf { it.length }
            if (!materialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Staging ${materialization.assetOverrides.size} source asset(s): " +
                        "$generatedBytes byte(s) across ${materialization.claims.size} range(s); " +
                        "${materialization.skippedWrites} candidate write(s) remain post-compile"
                )
            }
            if (!roomMaterialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Selected atomic ASM backend for ${roomMaterialization.roomIds.size} existing room(s): " +
                        "$generatedRoomBytes byte(s) across ${roomMaterialization.claims.size} range(s), " +
                        "${roomMaterialization.plmTables.size} symbolic PLM table(s)"
                )
            } else if (roomMaterialization.skippedWrites > 0) {
                onLog(
                    "[ASM-BUILD] ${roomMaterialization.skippedWrites} room write(s) remain on the ROM backend; " +
                        "this graph requires room identities, write kinds, or ROM space outside the current ASM backend"
                )
            }
            if (!patchMaterialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Selected ASM backend for ${patchMaterialization.claims.map { it.owner }.distinct().size} " +
                        "patch(es): $generatedPatchBytes byte(s) across ${patchMaterialization.claims.size} range(s); " +
                        "${patchMaterialization.skippedWrites} write(s) remain on the ROM backend"
                )
            }
            val materializedCompile = protectLinkedObjects(compiler.compile(
                projectFilePath = projectFilePath,
                loadedRom = loadedRom,
                materialization = materialization,
                patchMaterialization = patchMaterialization,
                roomMaterialization = roomMaterialization,
                referenceRomBody = initial.referenceRomBody,
            ) { progress ->
                onLog("[ASM-BUILD] $progress")
                onStatus(progress)
            })
            materializedCompile.copy(
                compilerOutput = (initial.compilerOutput + materializedCompile.compilerOutput).distinct(),
                expectedFinalParitySha256 = asmGeneratedParitySha256(planned.resultRom),
                expectedFinalRomBytes = planned.resultRom.copyOf(),
                roomValidationRomBytes = if (roomMaterialization.isEmpty) null else initial.romBytes.copyOf(),
            )
        }
        compiled.compilerOutput
            .filter { it.contains("warn", ignoreCase = true) || it.contains("error", ignoreCase = true) }
            .forEach { onLog("[ASAR] $it") }
        onLog(
            "[ASM-BUILD] Source base ready: ${compiled.sourceOwnedRanges.sumOf { it.length }} source-owned byte(s) " +
                "across ${compiled.sourceOwnedRanges.size} authored range(s), " +
                "${compiled.generatedAssetClaims.sumOf { it.length }} generated asset byte(s), " +
                "${compiled.generatedRoomClaims.sumOf { it.length }} generated room byte(s), " +
                "${compiled.generatedPatchClaims.sumOf { it.length }} ASM patch byte(s)"
        )
        putPreparedAsmBase(preparedKey, compiled)
        // The exporter may hydrate deterministic patch safety metadata on its
        // isolated project snapshot. Accept that equivalent post-plan snapshot
        // too, without allowing unrelated project changes to hit this entry.
        val hydratedPreparedKey = preparedAsmCacheKey(project, sourceInputs)
        if (hydratedPreparedKey != preparedKey) putPreparedAsmBase(hydratedPreparedKey, compiled)
        return compiled
    }

    private fun preparedAsmCacheKey(project: SmEditProject, sourceInputs: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: ByteArray) {
            val size = value.size
            digest.update((size ushr 24).toByte())
            digest.update((size ushr 16).toByte())
            digest.update((size ushr 8).toByte())
            digest.update(size.toByte())
            digest.update(value)
        }
        update(PREPARED_ASM_CACHE_VERSION.toByteArray())
        update(sourceInputs.toByteArray())
        update(json.encodeToString(SmEditProject.serializer(), project).toByteArray())
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Synchronized
    private fun getPreparedAsmBase(key: String): AsmCompiledBuildBase? =
        preparedAsmBases[key]?.copyForReuse()

    @Synchronized
    private fun putPreparedAsmBase(key: String, base: AsmCompiledBuildBase) {
        preparedAsmBases[key] = base.copyForReuse()
    }

    @Synchronized
    internal fun clearAsmBuildCachesForTests() {
        preparedAsmBases.clear()
        AsmProjectCompiler.clearBuildCacheForTests()
    }

    private fun exportCustomGfxPngs(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
    ) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return

        val folder = File(ProjectPaths.forProject(projectFilePath).exports, "graphics")
        folder.mkdirs()
        val tileGraphics = TileGraphics(romParser)

        for ((tilesetId, base64) in gfx.varGfx) {
            if (!tileGraphics.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tileGraphics.applyCustomVarGfx(java.util.Base64.getDecoder().decode(base64))
                val result = tileGraphics.renderTileSheet(0, tileGraphics.getVarTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }

        if (hasCre && gfx.creGfx != null && tileGraphics.loadTileset(0)) {
            try {
                tileGraphics.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                val result = tileGraphics.renderTileSheet(tileGraphics.getCreOffset(), tileGraphics.getCreTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "cre.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }
    }
}
