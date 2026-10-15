package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmPlmSourceCatalogTest {
    @TempDir lateinit var directory: File
    private val missile = RomParser.PlmEntry(0xB6EB, 4, 10, 0x14)
    private val scroll = RomParser.PlmEntry(0xB703, 46, 107, 0x9000)
    private val symbols = mapOf("PLMEntries_missileStation" to missile.id, "PLMEntries_ScrollPLM" to scroll.id)
    private val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
    // Preserve the pinned reference's typo: Big Pink incorrectly repeats the refill's address.
    private val populations = """
        PLMPopulation_GreenBrinstarMissileRefill: ;8F8550;
            %PLMPopEntry(PLMEntries_missileStation, ${'$'}04, ${'$'}0A, ${'$'}0014)
            dw ${'$'}0000
        PLMPopulation_BigPink: ;8F8550;
            %PLMPopEntry(PLMEntries_ScrollPLM, ${'$'}2E, ${'$'}6B, ${'$'}9000)
            dw ${'$'}0000
    """.trimIndent() + "\n"
    private val states = """
        RoomState_GreenBrinstarMissileRefill: ;8F9C96;
            %StateHeader(\
            %PLMPop(PLMPopulation_GreenBrinstarMissileRefill),
            %setupASM(0))
        RoomState_BigPink: ;8F9D26;
            %StateHeader(\
            ; %PLMPop(PLMPopulation_GreenBrinstarMissileRefill),
            %PLMPop(PLMPopulation_BigPink),
            %setupASM(0))
    """.trimIndent() + "\n"
    private val source = populations + states

    private fun catalog(text: String = source, header: Int = 0, shared: Boolean = false): AsmPlmSourceCatalog {
        val rom = ByteArray(0x300000)
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        for ((roomId, pointer) in listOf(0x9C89 to 0x8550, 0x9D19 to if (shared) 0x8550 else 0x85E4)) {
            val pc = 0x78000 + (roomId and 0x7FFF)
            rom[pc + 4] = 1; rom[pc + 5] = 1
            word(pc + 11, 0xE5E6)
            word(pc + 13 + 20, pointer)
        }
        for ((pc, entry) in listOf(0x78550 to missile, 0x785E4 to scroll)) {
            RomParser.serializePlmSet(listOf(entry)).forEachIndexed { index, value -> rom[pc + index] = value.toByte() }
        }
        val parser = RomParser(ByteArray(header) + rom)
        val consumers = listOf(0x9C89, 0x9D19).flatMap { roomId ->
            parser.findAllStateDataOffsets(roomId).mapIndexed { index, pc ->
                parser.readStateData(pc).getValue("plmSetPtr") to AsmPlmConsumer(roomId, "Test room", index, pc)
            }
        }.groupBy({ it.first }, { it.second })
        return AsmPlmSourceCatalog(text, macros, symbols, parser, consumers)
    }

    @Test fun `room-state references recover both populations despite a duplicated address comment`() {
        for (header in listOf(0, 0x200)) {
            val catalog = catalog(header = header)
            assertEquals("PLMPopulation_GreenBrinstarMissileRefill", catalog.binding(0x8550).label)
            assertEquals("PLMPopulation_BigPink", catalog.binding(0x85E4).label)
            for ((pointer, entry) in listOf(0x8550 to missile, 0x85E4 to scroll)) {
                val document = catalog.document(catalog.binding(pointer), source, macros)
                val target = assertIs<AsmPlmPlacementTarget.Source>(document.placementTarget(entry))
                assertContains(source.lines()[target.lineIndex], "%PLMPopEntry(")
                assertTrue(target.lineIndex > document.lineIndex)
            }
        }
    }

    @Test fun `address-only fallback accepts only a unique full ROM match`() {
        assertEquals("PLMPopulation_GreenBrinstarMissileRefill", catalog(populations).binding(0x8550).label)
        val identical = populations.replace("PLMEntries_ScrollPLM, ${'$'}2E, ${'$'}6B, ${'$'}9000",
            "PLMEntries_missileStation, ${'$'}04, ${'$'}0A, ${'$'}0014")
        assertContains(assertFails { catalog(identical).binding(0x8550) }.message.orEmpty(), "No unique source declaration")
    }

    @Test fun `a state reference disambiguates identical lists but never bypasses ROM verification`() {
        val identical = source.replace("PLMEntries_ScrollPLM, ${'$'}2E, ${'$'}6B, ${'$'}9000",
            "PLMEntries_missileStation, ${'$'}04, ${'$'}0A, ${'$'}0014")
        assertEquals("PLMPopulation_GreenBrinstarMissileRefill", catalog(identical).binding(0x8550).label)
        val mismatched = source.replace("%PLMPop(PLMPopulation_GreenBrinstarMissileRefill)", "%PLMPop(PLMPopulation_BigPink)")
        assertContains(assertFails { catalog(mismatched).binding(0x8550) }.message.orEmpty(), "does not match the base ROM")
        assertContains(assertFails { catalog(shared = true).binding(0x8550) }.message.orEmpty(), "references disagree")
    }

    @Test fun `malformed and duplicate source state references cannot fall back to address comments`() {
        val variants = listOf(
            source.replace("%PLMPop(PLMPopulation_GreenBrinstarMissileRefill),", "%PLMPop(Unknown + 1),"),
            source.replace("%PLMPop(PLMPopulation_GreenBrinstarMissileRefill),", "%PLMPop(PLMPopulation_GreenBrinstarMissileRefill),\n%PLMPop(PLMPopulation_BigPink),"),
            source.replace(";8F9D26;", ";8F9C96;"),
            source.replace("%StateHeader(\\", "%DifferentHeader(\\"),
        )
        for (text in variants) assertFails { catalog(text).binding(0x8550) }
    }

    @Test fun `placement navigation resolves the selected refill and current unsaved coordinates`(): Unit = runBlocking {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, PLM_SOURCE_FILE).writeText(source)
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val projectFile = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(projectFile.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(projectFile.path, true)
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val bridge = AsmPlmSourceBridge(catalog(), workspace) { project }
        val original = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x9C89, 0, 0x8550, missile))
        assertEquals(1, original.lineIndex)
        val edited = "; unsaved heading\n" + source.replace("${'$'}04, ${'$'}0A", "${'$'}05, ${'$'}0A")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, edited)
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x9C89, 0, 0x8550, missile.copy(x = 5)))
        assertEquals(2, target.lineIndex)
        assertContains(edited.lines()[target.lineIndex], "%PLMPopEntry(PLMEntries_missileStation, ${'$'}05")
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x9C89, 0, 0x8550, missile))
    }

    @Test fun `pinned reference resolves the real missile refill and Big Pink placements`() {
        val parser = TestRomHelper.requireRomParser()
        val disassembly = TestRomHelper.requireDisassemblyDir()
        val catalog = AsmPlmSourceCatalog.load(disassembly, parser)
        val macros = File(disassembly, "src/macros.asm").readText()
        for ((pointer, label) in listOf(0x8550 to "PLMPopulation_GreenBrinstarMissileRefill", 0x85E4 to "PLMPopulation_BigPink")) {
            val binding = catalog.binding(pointer)
            assertEquals(label, binding.label)
            val document = catalog.document(binding, catalog.originalText, macros)
            val target = assertIs<AsmPlmPlacementTarget.Source>(document.placementTarget(parser.parsePlmSet(pointer).first()))
            assertContains(catalog.originalText.lines()[target.lineIndex], "%PLMPopEntry(")
        }
    }
}
