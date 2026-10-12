package com.supermetroid.editor.asm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AsmProjectWorkspaceTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `source text changes round trip insert delete and replacement deltas`() {
        listOf(
            "abcde" to "abXYcde",
            "abcde" to "abde",
            "abcde" to "abXYde",
        ).forEach { (before, after) ->
            val change = requireNotNull(AsmSourceTextChange.between(before, after))
            assertEquals(after, change.redo(before))
            assertEquals(before, change.undo(after))
        }
    }

    @Test
    fun `project workspace is isolated persistent and restorable from its offline snapshot`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "My Hack.smedit").apply { writeText("{}") }
        val referenceSource = File(reference.root, "src/bank_80.asm")
        val originalText = referenceSource.readText()

        val created = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(created.originalDirectory, "src/bank_80.asm").isFile)
        assertTrue(File(created.workingDirectory, "src/bank_80.asm").isFile)
        assertEquals(File(tempDirectory, "asm"), created.root)
        assertEquals(File(tempDirectory, "smedit/asm/workspace"), created.workingDirectory)
        assertEquals(emptySet<String>(), created.modifiedFileIds)

        val editedText = originalText.replace("RTS", "NOP\n    RTS")
        val saved = repository.saveSource(projectFile.absolutePath, "bank_80.asm", editedText)

        assertEquals(originalText, referenceSource.readText(), "project edits must never mutate the global reference")
        assertEquals(editedText, File(saved.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(editedText, File(saved.root, "overrides/src/bank_80.asm").readText())
        assertEquals("*\n", File(tempDirectory, "smedit/.gitignore").readText())
        assertEquals(setOf("bank_80.asm"), saved.modifiedFileIds)
        assertEquals(setOf("bank_80.asm"), repository.sourceOverrideFileIds(projectFile.absolutePath))
        val reopened = requireNotNull(repository.load(projectFile.absolutePath))
        assertNotNull(reopened)
        assertEquals(editedText, reopened.referenceWorkspace.index.file("bank_80.asm")?.file?.readText())

        // The immutable project snapshot, not the global cache, owns restore.
        referenceSource.writeText("Global reference changed after project creation")
        val restored = repository.restoreSource(projectFile.absolutePath, "bank_80.asm")
        assertEquals(originalText, File(restored.workingDirectory, "src/bank_80.asm").readText())
        assertFalse(File(restored.root, "overrides/src/bank_80.asm").exists())
        assertEquals(emptySet<String>(), restored.modifiedFileIds)
        assertEquals(emptySet<String>(), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `source writes cannot escape the managed project tree`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Safe.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.absolutePath, reference)

        assertThrows<IllegalArgumentException> {
            repository.saveSource(projectFile.absolutePath, "../escaped.asm", "nope")
        }
        assertFalse(File(tempDirectory, "smedit/asm/workspace/escaped.asm").exists())
    }

    @Test
    fun `failed initialization does not activate a partial workspace`() {
        val range = AsmAssetRange("Tiles_Missing.bin", 0x10, 4)
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Broken.smedit").apply { writeText("{}") }

        assertThrows<IllegalArgumentException> {
            repository.initialize(projectFile.absolutePath, reference)
        }
        assertFalse(File(tempDirectory, "smedit/asm").exists())
        assertTrue(File(tempDirectory, "smedit").listFiles().orEmpty().none { it.name.startsWith(".asm.installing-") })
    }

    @Test
    fun `new workspaces include the objectives reference module disabled`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Starter.smedit").apply { writeText("{}") }

        val workspace = repository.initialize(projectFile.absolutePath, reference)
        val fileId = "project/objectives_pause_screen.asm"

        assertEquals(listOf(fileId), workspace.projectModuleFileIds)
        assertEquals(setOf(fileId), workspace.disabledProjectModuleFileIds)
        assertTrue(File(workspace.root, "modules/objectives_pause_screen.asm").readText().contains("Objectives_MainPauseRoutine"))
        assertEquals(emptySet<String>(), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `repair preserves an incomplete prior sidecar as a recoverable backup`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Repair.smedit").apply { writeText("{}") }
        val incomplete = File(tempDirectory, "smedit/asm").apply { mkdirs() }
        File(incomplete, "user-notes.txt").writeText("preserve me")

        val repaired = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(repaired.workingDirectory, "src/main.asm").isFile)
        val backup = File(tempDirectory, "smedit").listFiles().orEmpty()
            .single { it.name.startsWith(".asm.incomplete-backup-") }
        assertEquals("preserve me", File(backup, "user-notes.txt").readText())
    }

    @Test
    fun `recreating a local tree reapplies trackable source overrides`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Portable.smedit").apply { writeText("{}") }
        val root = File(tempDirectory, "asm")
        File(root, "overrides/src").mkdirs()
        val overrideText = "org ${'$'}808000\nBoot:\n    NOP\n    RTS\n"
        File(root, "overrides/src/bank_80.asm").writeText(overrideText)

        val recreated = repository.initialize(projectFile.absolutePath, reference)

        assertEquals(overrideText, File(recreated.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(setOf("bank_80.asm"), recreated.modifiedFileIds)
        assertEquals(overrideText, File(recreated.root, "overrides/src/bank_80.asm").readText())
    }

    @Test
    fun `project modules are portable ordered editable and separate from bank overrides`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Modules.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.absolutePath, reference)

        val (withFirst, firstId) = repository.createModule(projectFile.absolutePath, "first hook")
        val (withSecond, secondId) = repository.createModule(projectFile.absolutePath, "second_hook.asm")

        assertEquals("project/first_hook.asm", firstId)
        assertEquals("project/second_hook.asm", secondId)
        assertEquals(listOf(firstId), withFirst.projectModuleFileIds)
        assertEquals(listOf(firstId, secondId), withSecond.projectModuleFileIds)
        assertTrue(File(withSecond.root, "modules/first_hook.asm").isFile)
        assertTrue(File(withSecond.workingDirectory, "src/project/first_hook.asm").isFile)
        assertFalse(File(withSecond.root, "overrides/src/project/first_hook.asm").exists())

        val moduleText = "; first module\norg ${'$'}808000\nProjectHook:\n    NOP\n"
        val saved = repository.saveSource(projectFile.absolutePath, firstId, moduleText)
        assertEquals(moduleText, File(saved.root, "modules/first_hook.asm").readText())
        assertEquals(moduleText, File(saved.workingDirectory, "src/project/first_hook.asm").readText())
        assertEquals(setOf(firstId, secondId), repository.sourceOverrideFileIds(projectFile.absolutePath))

        val disabled = repository.setModuleEnabled(projectFile.absolutePath, firstId, enabled = false)
        assertEquals(setOf(firstId), disabled.disabledProjectModuleFileIds)
        assertEquals("first_hook.asm\n", File(disabled.root, "modules/.disabled").readText())
        assertEquals("first_hook.asm\n", File(disabled.workingDirectory, "src/project/.disabled").readText())
        assertEquals(setOf(secondId), repository.sourceOverrideFileIds(projectFile.absolutePath))
        assertEquals(setOf(firstId), requireNotNull(repository.load(projectFile.absolutePath)).disabledProjectModuleFileIds)

        val reordered = repository.moveModule(projectFile.absolutePath, secondId, -1)
        assertEquals(listOf(secondId, firstId), reordered.projectModuleFileIds)
        assertEquals("second_hook.asm\nfirst_hook.asm\n", File(reordered.root, "modules/.order").readText())

        val (renamed, renamedId) = repository.renameModule(projectFile.absolutePath, secondId, "setup")
        assertEquals("project/setup.asm", renamedId)
        assertEquals(listOf(renamedId, firstId), renamed.projectModuleFileIds)
        assertFalse(File(renamed.root, "modules/second_hook.asm").exists())
        assertTrue(File(renamed.root, "modules/setup.asm").isFile)

        val deleted = repository.deleteModule(projectFile.absolutePath, firstId)
        assertEquals(listOf(renamedId), deleted.projectModuleFileIds)
        assertEquals(emptySet<String>(), deleted.disabledProjectModuleFileIds)
        assertFalse(File(deleted.root, "modules/.disabled").exists())
        assertEquals(listOf(renamedId), requireNotNull(repository.load(projectFile.absolutePath)).projectModuleFileIds)
        assertEquals(setOf(renamedId), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `cache repair preserves authored overrides modules order and disabled state`() {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Repair authored.smedit").apply { writeText("{}") }
        val initial = repository.initialize(projectFile.path, reference)
        val (created, moduleId) = repository.createModule(projectFile.path, "keep_me")
        repository.setModuleEnabled(projectFile.path, moduleId, enabled = false)
        val override = File(initial.originalDirectory, "src/bank_80.asm").readText().replace("RTS", "NOP\n    RTS")
        repository.saveSource(projectFile.path, "bank_80.asm", override)
        val authored = created.root.walkTopDown().filter(File::isFile)
            .associate { it.relativeTo(created.root).path to it.readBytes().toList() }
        // Simulate moving only the portable project inputs to another machine.
        check(requireNotNull(repository.cacheFor(projectFile.path)).deleteRecursively())
        val repaired = repository.initialize(projectFile.path, reference)
        assertEquals(authored, repaired.root.walkTopDown().filter(File::isFile)
            .associate { it.relativeTo(repaired.root).path to it.readBytes().toList() })
        assertEquals(override, File(repaired.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(setOf(moduleId), repaired.disabledProjectModuleFileIds)
    }

    @Test
    fun `generated module mirror never resurrects a removed authored module`() {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "No resurrection.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.path, reference)
        val (workspace, _) = repository.createModule(projectFile.path, "temporary_hook")
        val mirror = File(workspace.workingDirectory, "src/project/temporary_hook.asm")
        assertTrue(mirror.exists())
        check(File(workspace.root, "modules").deleteRecursively())
        val loaded = requireNotNull(repository.load(projectFile.path))
        assertEquals(emptyList<String>(), loaded.projectModuleFileIds)
        assertFalse(mirror.exists())
        assertFalse(File(workspace.root, "modules/temporary_hook.asm").exists())
    }

    @Test
    fun `workspace state tracks unsaved saved and restored project source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "State.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val buildArtifacts = AsmBuildArtifactRepository(projectRepository)
        buildArtifacts.publishSuccess(
            projectFilePath = projectFile.absolutePath,
            symbols = ByteArray(0),
            output = listOf("Build succeeded"),
            assemblerVersion = "fixture",
        )
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
            buildArtifactRepository = buildArtifacts,
        )

        state.bindProject(projectFile.absolutePath, enabled = true)
        state.openSource("bank_80.asm", lineIndex = 3, columnIndex = 4)
        assertEquals(
            AsmWorkspaceKind.PROJECT,
            (state.locationSnapshot() as AsmWorkspaceLocation.Source).workspaceKind,
        )
        assertEquals(4, (state.locationSnapshot() as AsmWorkspaceLocation.Source).columnIndex)
        assertTrue(state.isProjectSourceEditable)
        assertFalse(state.sourceBuildRequired)
        val original = state.sourceEditText("bank_80.asm")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")

        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired)
        assertTrue(state.hasProjectSourceChanges)
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        state.discardSourceBuffer("bank_80.asm")
        assertFalse(state.sourceBuildRequired, "reverting an unsaved edit retains the matching successful build")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")
        assertTrue(state.saveSource("bank_80.asm"))
        assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired, "newly saved source requires an explicit build")
        assertEquals(setOf("bank_80.asm"), state.projectModifiedFileIds)

        assertTrue(state.restoreOriginalSource("bank_80.asm"))
        assertEquals(original, state.sourceEditText("bank_80.asm"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
        assertFalse(state.hasProjectSourceChanges)
    }

    @Test
    fun `project source undo and redo operate on the shared document buffer`() = runBlocking {
        val reference = fakeReference(emptyList())
        val projectRepository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Undo.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-undo-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = emptyList(),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)
        state.openSource("bank_80.asm")
        val primaryPane = state.activeSourcePaneId
        state.openSourceInSplit("bank_80.asm", lineIndex = 2)
        val original = state.sourceEditText("bank_80.asm")

        state.updateSourceEditText("bank_80.asm", "$original; first\n")
        state.activateSourcePane(primaryPane)
        assertEquals(
            "$original; first\n",
            state.sourceEditText(requireNotNull(state.sourcePane(primaryPane)?.fileId)),
            "both panes must address the same source document",
        )
        state.updateSourceEditText("bank_80.asm", "$original; first\n; second\n")
        assertTrue(state.canUndoSource("bank_80.asm"))
        assertTrue(state.undoSourceEdit("bank_80.asm"))
        assertEquals("$original; first\n", state.sourceEditText("bank_80.asm"))
        assertTrue(state.undoSourceEdit("bank_80.asm"))
        assertEquals(original, state.sourceEditText("bank_80.asm"))
        assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))

        assertTrue(state.redoSourceEdit("bank_80.asm"))
        assertEquals("$original; first\n", state.sourceEditText("bank_80.asm"))
        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
    }

    @Test
    fun `workspace state manages project modules as saved build inputs`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "ModuleState.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-module-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)

        assertTrue(state.createProjectModule("door hook", "Hooks a custom Landing Site door."))
        val fileId = "project/door_hook.asm"
        assertEquals(listOf(fileId), state.projectModuleFileIds)
        assertEquals(fileId, state.selectedFileId)
        assertTrue(state.isProjectModule(fileId))
        assertTrue(state.isProjectModuleEnabled(fileId))
        assertTrue(state.hasProjectSourceChanges)
        assertTrue(state.sourceBuildRequired)
        assertEquals("Hooks a custom Landing Site door.", state.workspace?.index?.file(fileId)?.description)
        assertEquals(
            "Hooks a custom Landing Site door.",
            projectModuleDescription(state.sourceEditText(fileId)),
        )

        assertTrue(state.setProjectModuleEnabled(fileId, enabled = false))
        assertFalse(state.isProjectModuleEnabled(fileId))
        assertEquals(setOf(fileId), state.disabledProjectModuleFileIds)
        assertFalse(state.hasProjectSourceChanges)
        assertTrue(state.setProjectModuleEnabled(fileId, enabled = true))
        assertTrue(state.isProjectModuleEnabled(fileId))

        val original = state.sourceEditText(fileId)
        state.updateSourceEditText(fileId, "$original\nDoorHook:\n    RTS\n")
        assertTrue(state.hasUnsavedSourceChanges(fileId))
        assertTrue(state.saveSource(fileId))
        assertFalse(state.hasUnsavedSourceChanges(fileId))
        assertTrue(state.setProjectModuleEnabled(fileId, enabled = false))

        assertTrue(
            state.updateProjectModuleSettings(
                fileId,
                "door_runtime",
                "Runtime door hooks\nfor the custom room.",
            ),
        )
        val renamed = "project/door_runtime.asm"
        assertEquals(listOf(renamed), state.projectModuleFileIds)
        assertEquals(renamed, state.selectedFileId)
        assertFalse(state.isProjectModuleEnabled(renamed), "settings must preserve the ON/OFF manifest")
        assertEquals("Runtime door hooks for the custom room.", state.workspace?.index?.file(renamed)?.description)
        val renamedText = state.sourceEditText(renamed)
        assertTrue(renamedText.contains("; Project module: door runtime"))
        assertTrue(renamedText.contains("; SMEDIT description: Runtime door hooks for the custom room."))
        assertTrue(renamedText.contains("DoorHook:\n    RTS"), "settings must preserve authored source")
        assertTrue(state.deleteProjectModule(renamed))
        assertEquals(emptyList<String>(), state.projectModuleFileIds)
        assertFalse(state.hasProjectSourceChanges)
    }

    @Test
    fun `reviewed project replacement stages buffers without writing source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Replace.smedit").apply { writeText("{}") }
        val project = projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)
        val diskSource = File(project.workingDirectory, "src/bank_80.asm")
        val original = diskSource.readText()
        val preview = state.previewProjectSourceReplacement("RTS", "NOP", caseSensitive = true)

        assertEquals(1, preview.occurrenceCount)
        assertEquals(1, state.stageProjectSourceReplacement(preview))
        assertEquals(original, diskSource.readText(), "staging must not write the working tree")
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        assertTrue(state.sourceEditText("bank_80.asm").contains("NOP"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
    }

    @Test
    fun `generated room navigation is read only and preserves source history and buffers`() = runBlocking {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Generated.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.path, reference)
        val artifacts = AsmBuildArtifactRepository(repository)
        artifacts.publishSuccess(
            projectFile.path, ByteArray(0), emptyList(), "fixture",
            "; Generated room\nSmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n",
        )
        artifacts.publishRoomData(projectFile.path,
            "${AsmRoomDataSnapshot.MARKER}\nSmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n")
        val state = AsmWorkspaceState(projectRepository = repository, buildArtifactRepository = artifacts)
        state.bindProject(projectFile.path, enabled = true)
        state.openSource("bank_80.asm", 3)
        val sourceLocation = state.locationSnapshot()
        val original = state.sourceEditText("bank_80.asm")
        state.updateSourceEditText("bank_80.asm", "$original\n; unsaved bank work")
        state.openGeneratedRooms(1)
        assertTrue(state.roomDataExpanded)
        assertTrue(state.expandedRoomDataGroups[roomDataGroupKey(0x91F8, false)] == true)
        state.toggleRoomDataExpanded()
        assertFalse(state.roomDataExpanded, "selection must not force a collapsed tree open on every recomposition")
        assertEquals(AsmBrowserMode.GENERATED_ROOMS, state.browserMode)
        assertFalse(state.isProjectSourceEditable)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        assertTrue(state.workspace?.index?.file(AsmGeneratedRoomSource.FILE_NAME) == null)
        state.updateSourceEditText(AsmGeneratedRoomSource.FILE_NAME, "must not become an editable buffer")
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        state.goBack()
        assertEquals(sourceLocation, state.locationSnapshot())
        assertTrue(state.isProjectSourceEditable)
        assertTrue(state.sourceEditText("bank_80.asm").endsWith("; unsaved bank work"))
        state.goForward()
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        state.openGeneratedRooms(2, buildWrites = true)
        assertTrue(state.showRoomBuildWrites)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(2, true), state.locationSnapshot())
        state.goBack()
        assertFalse(state.showRoomBuildWrites)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        state.openGeneratedRooms(Int.MAX_VALUE)
        assertEquals(state.generatedRoomSource?.lines?.lastIndex, state.generatedRoomLineIndex)
        state.bindProject(File(tempDirectory, "Other.smedit").path, enabled = false)
        assertTrue(state.generatedRoomSource == null, "snapshots must not leak between projects")
        assertTrue(state.generatedRoomInput == null)
        assertFalse(state.roomDataExpanded)
        assertEquals(AsmBrowserMode.SOURCE, state.browserMode)
    }

    private fun fakeReference(ranges: List<AsmAssetRange>): AsmReferenceWorkspace {
        val root = File(tempDirectory, "reference-${ranges.size}").apply { mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_80.asm ; Engine\n")
        File(source, "bank_80.asm").writeText(
            """
            ; Engine
            org ${'$'}808000
            Boot:
                RTS
            """.trimIndent(),
        )
        val data = File(root, "data").apply { mkdirs() }
        ranges.forEach { range ->
            File(data, range.path).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(range.length) { it.toByte() })
            }
        }
        val metadata = AsmReferenceMetadata(
            repositoryUrl = AsmReferenceContract.REPOSITORY_URL,
            commit = AsmReferenceContract.COMMIT,
            installedAt = "2026-10-08T00:00:00Z",
            romName = "fixture.sfc",
            romSha256 = "ab".repeat(32),
            romSize = 0x300000,
            assetCount = ranges.size,
        )
        return AsmReferenceWorkspace(
            root = root,
            metadata = metadata,
            index = AsmSourceParser().parse(source, data, ranges),
        )
    }
}
