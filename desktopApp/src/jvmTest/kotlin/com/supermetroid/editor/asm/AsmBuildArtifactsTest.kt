package com.supermetroid.editor.asm

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmBuildArtifactsTest {
    @Test
    fun `post assembler safety failures have visible diagnostics without losing source errors`() {
        val directory = createTempDirectory("smedit-pipeline-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { "fixture" })
            repository.publishSuccess(projectFile.path, ByteArray(0), listOf("Assembly complete"), "fixture")
            repository.publishPipelineFailure(projectFile.path, "PLM list does not match the supported six-byte format")
            val failure = requireNotNull(repository.load(projectFile.path))
            assertEquals(false, failure.succeeded)
            assertTrue(failure.diagnostics.single().message.contains("six-byte format"))
            assertNull(failure.diagnostics.single().fileId, "pipeline errors must not invent source lines")
            repository.publishFailure(projectFile.path, listOf("src/bank_90.asm:8: error: Unknown command"))
            repository.publishPipelineFailure(projectFile.path, "Compilation failed")
            val sourceError = requireNotNull(repository.load(projectFile.path)).diagnostics.single()
            assertEquals("bank_90.asm", sourceError.fileId)
            assertEquals(7, sourceError.lineIndex)
            assertEquals("Unknown command", sourceError.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `generated room snapshot is separate from live diagnostics and empty builds clear it`() {
        val directory = createTempDirectory("smedit-generated-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            var fingerprint = "source-v1"
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { fingerprint })
            val source = "SmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n"
            repository.publishSuccess(projectFile.path, ByteArray(0), emptyList(), "fixture", source)
            val snapshot = requireNotNull(repository.loadGeneratedRooms(projectFile.path))
            assertEquals(source.lines(), snapshot.lines)
            assertTrue(snapshot.generatedAt.isNotBlank())
            fingerprint = "source-v2"
            assertNull(repository.load(projectFile.path))
            assertEquals(snapshot, repository.loadGeneratedRooms(projectFile.path), "saved source must not relabel an old snapshot as current")
            repository.publishFailure(projectFile.path, listOf("Build failed"))
            assertEquals(false, repository.load(projectFile.path)?.succeeded)
            assertEquals(snapshot, repository.loadGeneratedRooms(projectFile.path), "an earlier compilation remains inspectable after failure")
            repository.publishSuccess(projectFile.path, ByteArray(0), emptyList(), "fixture")
            assertNull(repository.loadGeneratedRooms(projectFile.path), "a no-room compilation must not retain stale generated tables")
            assertNull(repository.loadGeneratedRooms(File(directory, "Other.smedit").path))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `WLA symbols retain labels and exact source line addresses`() {
        val symbols = AsmWlaSymbolParser.parse(
            """
                ; wla symbolic information file
                [labels]
                90:8029 AnimateSamus
                7E:0AFA SamusYSpeed

                [source files]
                0000 12345678 src/main.asm
                0001 abcdef01 src/bank_90.asm

                [rom checksum]
                deadbeef

                [addr-to-line mapping]
                90:8029 0001:0000001b
                90:802c 0001:0000001c
            """.trimIndent(),
        )

        assertEquals(0x908029, symbols.labels["AnimateSamus"])
        assertEquals(0x7E0AFA, symbols.labels["SamusYSpeed"])
        assertEquals(0x908029, symbols.addressAt("bank_90.asm", 26))
        assertEquals(0x90802C, symbols.addressAt("bank_90.asm", 27))
        assertEquals(2, symbols.mappedLineCount)
    }

    @Test
    fun `Asar diagnostics expose clickable source locations`() {
        val diagnostics = AsmBuildOutputParser.diagnostics(
            listOf(
                "src/bank_90.asm:108: error: (E5117): Unknown command. [NOPE]",
                "/tmp/build/src/bank_81.asm:27: warning: (W1001): Example warning",
                "Errors were detected while assembling the patch.",
            ),
        )

        assertEquals(2, diagnostics.size)
        assertEquals(AsmDiagnosticSeverity.ERROR, diagnostics[0].severity)
        assertEquals("bank_90.asm", diagnostics[0].fileId)
        assertEquals(107, diagnostics[0].lineIndex)
        assertEquals("Unknown command. [NOPE]", diagnostics[0].message)
        assertEquals(AsmDiagnosticSeverity.WARNING, diagnostics[1].severity)
        assertEquals("bank_81.asm", diagnostics[1].fileId)
        assertTrue(diagnostics[1].message.contains("Example warning"))
    }

    @Test
    fun `latest build report and symbol map round trip through the project sidecar`() {
        val directory = createTempDirectory("smedit-asm-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            var sourceFingerprint = "source-v1"
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { sourceFingerprint })
            repository.publishSuccess(
                projectFilePath = projectFile.absolutePath,
                symbols = """
                    [labels]
                    90:8029 AnimateSamus
                    [source files]
                    0001 abcdef01 src/bank_90.asm
                    [rom checksum]
                    deadbeef
                    [addr-to-line mapping]
                    90:8029 0001:0000001b
                """.trimIndent().toByteArray(),
                output = listOf("Assembly complete"),
                assemblerVersion = "Asar 1.81",
            )

            val report = requireNotNull(repository.load(projectFile.absolutePath))
            assertTrue(report.succeeded)
            assertEquals("Asar 1.81", report.assemblerVersion)
            assertEquals(listOf("Assembly complete"), report.output)
            assertEquals(0x908029, report.symbols.addressAt("bank_90.asm", 26))

            sourceFingerprint = "source-v2"
            assertNull(repository.load(projectFile.absolutePath), "feedback from older source must be ignored")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `source validation rejects silently truncated data literals`() {
        val diagnostics = AsmSourceLinter.lintText(
            "bank_90.asm",
            """
                dw ${'$'}9000000900*!SPF*${'$'}100,${'$'}0280
                db ${'$'}123 & ${'$'}FF
                dl ${'$'}7E2000
                db ${'$'}04,${'$'}04 : dw ${'$'}0000
            """.trimIndent(),
        )

        assertEquals(1, diagnostics.size)
        assertEquals(0, diagnostics.single().lineIndex)
        assertTrue(diagnostics.single().message.contains("does not fit dw"))
        assertTrue(diagnostics.single().message.contains("silently keep only the low 16 bits"))
    }

    @Test
    fun `source validation ignores disabled project modules`() {
        val directory = createTempDirectory("smedit-disabled-module-lint-").toFile()
        try {
            val sourceRoot = File(directory, "src").apply { mkdirs() }
            File(sourceRoot, "bank_80.asm").writeText("dw ${'$'}1234\n")
            File(sourceRoot, "project/unfinished.asm").apply {
                parentFile.mkdirs()
                writeText("dw ${'$'}123456\n")
            }

            assertEquals(
                emptyList(),
                AsmSourceLinter.lintTree(
                    sourceRoot,
                    excludedFileIds = setOf("project/unfinished.asm"),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `source validation checks complete macro calls without guessing at incomplete edits`() {
        val macros = listOf(
            AsmMacro("Pair", listOf("left", "right"), false, "macros.asm", 0),
            AsmMacro("Many", listOf("first", "..."), true, "macros.asm", 4),
        )
        val diagnostics = AsmSourceLinter.lintText(
            "project/example.asm",
            """
                %Pair(${ '$' }01, function(${ '$' }02, ${ '$' }03))
                %Pair(${ '$' }01)
                %Many()
                %Missing(${ '$' }00)
                %StillTyping(
                db "%Missing(${ '$' }00)"
            """.trimIndent(),
            macros,
        )

        assertEquals(3, diagnostics.size)
        assertTrue(diagnostics[0].message.contains("expects 2 arguments; found 1"))
        assertTrue(diagnostics[1].message.contains("expects at least 1 argument"))
        assertTrue(diagnostics[2].message.contains("Unknown macro %Missing"))
        assertEquals(listOf(1, 2, 3), diagnostics.mapNotNull(AsmBuildDiagnostic::lineIndex))
    }

    @Test
    fun `pinned source has no unsafe data literal diagnostics when local fixture exists`() {
        val cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        val sourceRoot = generateSequence(cursor) { it.parentFile }
            .take(6)
            .map { root ->
                File(
                    root,
                    "parity/work/sm_disassembly/src",
                )
            }
            .firstOrNull(File::isDirectory)
            ?: return

        assertEquals(emptyList(), AsmSourceLinter.lintTree(sourceRoot))
    }
}
