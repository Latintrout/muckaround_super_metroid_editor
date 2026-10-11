package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("asm-symbol-coverage")
class AsmSymbolCoverageIntegrationTest {
    @Test
    fun `every emitted symbol referenced by pinned source resolves in SMEDIT`() {
        val disassembly = TestRomHelper.requireDisassemblyDir()
        val source = File(disassembly, "src")
        val symbolsFile = File(disassembly, "symbols.sym")
        assertTrue(symbolsFile.isFile, "Run ./gradlew parityBuildReference to generate symbols.sym")

        val index = AsmSourceParser().parse(source, File(disassembly, "data"), emptyList())
        val compiled = AsmWlaSymbolParser.parse(symbolsFile.readText())
        val coverage = AsmSourceReferenceAuditor.audit(index, compiled)
        val sample = coverage.unresolved.take(40).joinToString("\n") { unresolved ->
            "${unresolved.fileId}:${unresolved.lineIndex + 1}:${unresolved.column + 1} " +
                "${unresolved.token} — ${unresolved.sourceLine}"
        }

        assertTrue(coverage.referenceCount >= 20_000, "Expected broad source coverage, found ${coverage.referenceCount}")
        assertTrue(
            coverage.unresolved.isEmpty(),
            "${coverage.unresolved.size} source references are not navigable:\n$sample",
        )
    }
}
