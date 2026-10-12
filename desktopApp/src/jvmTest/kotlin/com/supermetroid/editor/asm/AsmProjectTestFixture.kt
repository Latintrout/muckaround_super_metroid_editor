package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import java.io.File

/** A disposable project; no tests read or publish build artifacts into a user's hack. */
internal data class AsmProjectTestFixture(
    val projectFile: File,
    val romFile: File,
    val rom: ByteArray,
    val workspace: AsmProjectBuildWorkspace,
) {
    companion object {
        fun create(directory: File): AsmProjectTestFixture {
            val rom = TestRomHelper.requireRomBytes()
            val checkout = TestRomHelper.requireDisassemblyDir()
            val referenceRoot = File(directory, "reference")
            File(checkout, "src").copyRecursively(File(referenceRoot, "src"))
            val assets = AsmAssetManifest.loadBundled()
            assets.forEach { asset ->
                File(referenceRoot, "data/${asset.path}").apply {
                    parentFile.mkdirs()
                    writeBytes(rom.copyOfRange(asset.pcOffset, asset.endExclusive))
                }
            }
            val reference = AsmReferenceWorkspace(
                referenceRoot,
                AsmReferenceMetadata(
                    AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                    "test-fixture", "fixture.sfc", TestRomHelper.sha256(rom), rom.size, assets.size,
                ),
                AsmSourceParser().parse(File(referenceRoot, "src"), File(referenceRoot, "data"), assets),
            )
            val projectFile = File(directory, "Fixture.smedit").apply { writeText("{}") }
            val romFile = File(directory, "Fixture.sfc").apply { writeBytes(rom) }
            val repository = AsmProjectWorkspaceRepository(starterModules = emptyList())
            repository.initialize(projectFile.absolutePath, reference)
            val workspace = requireNotNull(repository.loadForBuild(projectFile.absolutePath))
            return AsmProjectTestFixture(projectFile, romFile, rom, workspace)
        }
    }
}
