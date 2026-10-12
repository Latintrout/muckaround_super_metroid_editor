package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmBuildArtifactRepository

import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.asm.AsmAssetManifest
import com.supermetroid.editor.asm.AsmWlaSymbolParser
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.ensureStateManifest
import java.io.File
import java.util.Base64
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Explicit clean ROM/source fixtures; every project and compiler artifact lives in @TempDir. */
class AsmBuildPipelineIntegrationTest {
    @TempDir lateinit var tempDirectory: File

    @Test
    fun `changed selectors and reordered states keep source validation and final room data consistent`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        for (reorder in listOf(false, true)) {
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            val states = edits.ensureStateManifest(parser)
            if (reorder) {
                val first = edits.states.removeAt(0)
                edits.states.add(1, first)
                edits.stateGraphChanged = true
            } else {
                states[2].condition = states[2].condition.copy(argument = 2)
                states[2].conditionChanged = true
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(project, parser, logs::add, logs::add, fixture.projectFile.path),
                logs.joinToString("\n"))
            assertTrue(result.sourceRoomPatched > 0)
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom))
            val data = assertNotNull(AsmBuildArtifactRepository().loadRoomData(fixture.projectFile.path))
            assertTrue(data.isRoomDataSnapshot)
            assertEquals(parser.roomCatalog.rooms.size, data.groups().size)
            assertEquals("Landing Site", data.groups().single { it.roomId == 0x91F8 }.title)
        }
    }

    @Test
    fun `PLM growth sharing selected-state shrink and empty lists compile with symbolic pointers`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val before = parser.parseRoomStatesWithData(0x91F8)
        val originals = before.map { parser.parsePlmSet(it.plmSetPtr) }
        val sharedStateIndex = before.indices.first { i -> before.count { it.plmSetPtr == before[i].plmSetPtr } > 1 }
        val item = PlmChange("add", 0xEEDB, 8, 8, 0x100)
        val cases = listOf("all-state growth", "selected-state growth", "selected-state shrink", "empty lists")
        cases.forEach { scenario ->
            val project = sourceProject(fixture)
            val edits = project.getOrCreateRoom(0x91F8)
            when (scenario) {
                "all-state growth" -> edits.plmChanges += item
                "selected-state growth" -> edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges += item
                "selected-state shrink" -> {
                    val removed = originals[sharedStateIndex].last()
                    edits.ensureStateManifest(parser)[sharedStateIndex].plmChanges +=
                        PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
                else -> originals.flatten().distinct().forEach { removed ->
                    edits.plmChanges += PlmChange("remove", removed.id, removed.x, removed.y, removed.param)
                }
            }
            val oracle = assertNotNull(RomExporter(ProjectFileService.snapshotProject(project), parser).build())
            val logs = mutableListOf<String>()
            val result = assertNotNull(ProjectFileService.buildRom(
                project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
            ), "$scenario\n${logs.joinToString("\n")}")
            assertEquals(asmGeneratedParitySha256(oracle.resultRom), asmGeneratedParitySha256(result.resultRom), scenario)
            assertTrue(result.sourceRoomPatched > 0)
            assertTrue(result.writeReport.writes.any { it.kind == RomWriteKind.ASM_ROOM })
            assertTrue(result.writeReport.writes.none {
                it.owner == "room-graph:project" && it.kind in setOf(RomWriteKind.ROOM, RomWriteKind.ALLOCATION)
            })
            val buildDirectory = ProjectPaths(fixture.projectFile).asmBuild
            val generated = File(buildDirectory, "generated-rooms.asm").readText()
            assertTrue(generated.contains("dw SmeditRoom_91F8_State"), generated)
            val snapshot = assertNotNull(AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path))
            assertEquals(generated.lines(), snapshot.lines)
            assertTrue(snapshot.sections.any { it.roomId == 0x91F8 }, "actual emitted tables must link back to Landing Site")
            val symbols = AsmWlaSymbolParser.parse(File(buildDirectory, "symbols.sym").readText())
            val tableAddresses = symbols.labels.filterKeys { it.startsWith("SmeditRoom_") && it.endsWith("_Plms") }
                .values.map { it and 0xFFFF }.toSet()
            assertTrue(tableAddresses.isNotEmpty())
            val built = RomParser(result.resultRom)
            val after = built.parseRoomStatesWithData(0x91F8)
            val lists = after.map { built.parsePlmSet(it.plmSetPtr) }
            when (scenario) {
                "all-state growth" -> {
                    assertEquals(before.size, after.size)
                    before.indices.forEach { i ->
                        assertEquals(originals[i] + RomParser.PlmEntry(item.plmId, item.x, item.y, item.param), lists[i])
                        assertNotEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                        assertTrue(after[i].plmSetPtr in tableAddresses)
                        before.indices.forEach { j ->
                            assertEquals(before[i].plmSetPtr == before[j].plmSetPtr, after[i].plmSetPtr == after[j].plmSetPtr)
                        }
                    }
                }
                "selected-state growth", "selected-state shrink" -> {
                    assertNotEquals(before[sharedStateIndex].plmSetPtr, after[sharedStateIndex].plmSetPtr)
                    assertTrue(after[sharedStateIndex].plmSetPtr in tableAddresses)
                    before.indices.filter { it != sharedStateIndex }.forEach { i ->
                        assertEquals(originals[i], lists[i])
                        assertEquals(before[i].plmSetPtr, after[i].plmSetPtr)
                    }
                    val expectedSize = originals[sharedStateIndex].size + if (scenario.endsWith("growth")) 1 else -1
                    assertEquals(expectedSize, lists[sharedStateIndex].size)
                }
                else -> assertTrue(lists.all { it.isEmpty() })
            }
        }
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    @Test
    fun `room source asset and patch backends compose and reuse an unchanged build`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val parser = RomParser(fixture.rom)
        val project = sourceProject(fixture)
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDB, 8, 8, 0x100)
        project.patches += SmPatch(
            "config_samus_physics", "Samus Physics", enabled = true,
            configType = "samus_physics", configData = mutableMapOf("gravity" to 0x2C),
        )
        val asset = AsmAssetManifest.loadBundled().single { it.path == "Tiles_Zoomer.bin" }
        val replacement = fixture.rom.copyOfRange(asset.pcOffset, asset.endExclusive)
        replacement[0] = (replacement[0].toInt() xor 1).toByte()
        project.customGfx.spriteTileBlocks["enemy:DCFF"] = Base64.getEncoder().encodeToString(replacement)
        val logs = mutableListOf<String>()
        val result = assertNotNull(ProjectFileService.buildRom(
            project, parser, logs::add, logs::add, fixture.projectFile.absolutePath,
        ), logs.joinToString("\n"))
        assertTrue(result.sourceAssetPatched > 0 && result.sourceRoomPatched > 0 && result.sourcePatchPatched > 0,
            "assets=${result.sourceAssetPatched}, rooms=${result.sourceRoomPatched}, patches=${result.sourcePatchPatched}\n${logs.joinToString("\n")}")
        assertEquals(0x2C, result.resultRom[0x081EA2].toInt() and 0xFF)
        val generatedFile = File(ProjectPaths(fixture.projectFile).asmBuild, "generated-rooms.asm")
        val generated = generatedFile.readText()
        generatedFile.writeText("; report from a different build\n")
        val cachedLogs = mutableListOf<String>()
        val cached = assertNotNull(ProjectFileService.buildRom(
            project, parser, cachedLogs::add, cachedLogs::add, fixture.projectFile.absolutePath,
        ), cachedLogs.joinToString("\n"))
        assertContentEquals(result.resultRom, cached.resultRom)
        assertEquals(generated, generatedFile.readText())
        assertEquals(generated.lines(), AsmBuildArtifactRepository().loadGeneratedRooms(fixture.projectFile.path)?.lines)
        assertTrue(cachedLogs.any { it.contains("unchanged — reusing prepared source base") }, cachedLogs.joinToString("\n"))
        assertTrue(cachedLogs.none { it.contains("Compiling project ASM source") })

        // A successful Asar pass must not hide a later planner failure in the IDE.
        project.patches += SmPatch(
            "fixture-invalid-range", "Invalid range fixture", enabled = true,
            writes = mutableListOf(PatchWrite(Int.MAX_VALUE.toLong(), listOf(0xFF))),
        )
        val failureLogs = mutableListOf<String>()
        assertNull(ProjectFileService.buildRom(
            project, parser, failureLogs::add, failureLogs::add, fixture.projectFile.path,
        ))
        val failedReport = assertNotNull(AsmBuildArtifactRepository().load(fixture.projectFile.path))
        assertEquals(false, failedReport.succeeded)
        assertTrue(failedReport.diagnostics.any { it.message.contains("range", ignoreCase = true) }, failureLogs.joinToString("\n"))
    }

    @Test
    fun `renamed project exports from either backend without changing base or authored paths`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val renamedProject = File(tempDirectory, "My New Hack.smedit")
        assertTrue(fixture.projectFile.renameTo(renamedProject))
        val project = sourceProject(fixture)
        assertTrue(ProjectFileService.saveProject(project, renamedProject.path, null, false) {})
        val paths = ProjectPaths(renamedProject)
        val parser = RomParser(fixture.rom)
        val logs = mutableListOf<String>()
        val output = assertNotNull(ProjectFileService.exportToRom(project, parser, logs::add, logs::add, renamedProject.path), logs.joinToString("\n"))
        assertEquals(File(tempDirectory, "My New Hack-v1.0.smc").absolutePath, output)
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
        assertContentEquals(fixture.rom, File(output).readBytes())
        assertTrue(File(paths.asmBuild, "symbols.sym").isFile)
        val roomData = assertNotNull(AsmBuildArtifactRepository().loadRoomData(renamedProject.path))
        assertEquals(parser.roomCatalog.rooms.size, roomData.groups().size, "unchanged builds must also publish all room data")
        val reopened = ProjectFileService.loadProject(renamedProject)
        assertEquals(fixture.romFile.absolutePath, reopened.romPath)
        ProjectLifecycle.verify(reopened, fixture.romFile)
        reopened.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        reopened.buildName = "rom"
        val patchedOutput = assertNotNull(ProjectFileService.exportToRom(reopened, parser, logs::add, logs::add, renamedProject.path))
        assertEquals("My New Hack-rom-v1.0.smc", File(patchedOutput).name)
        assertContentEquals(File(output).readBytes(), File(patchedOutput).readBytes())
        assertContentEquals(fixture.rom, fixture.romFile.readBytes())
    }

    private fun sourceProject(fixture: AsmProjectTestFixture) = SmEditProject(fixture.romFile.absolutePath).also {
        it.asmWorkspace.enabled = true
        it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
    }
}
