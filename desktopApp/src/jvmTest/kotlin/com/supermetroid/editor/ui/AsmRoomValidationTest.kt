package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCompiledBuildBase
import com.supermetroid.editor.asm.AsmGeneratedRoomClaim
import com.supermetroid.editor.asm.ASM_ROOM_GRAPH_OWNER
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ensureStateManifest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsmRoomValidationTest {
    @Test
    fun `generated selector edits are not checked against their own output as source identity`() {
        assertFalse(buildWithSourceMismatch(false).any { "no longer matches source state" in it })
    }

    @Test
    fun `real source mismatches still block the generated room backend`() {
        val logs = buildWithSourceMismatch(true)
        assertTrue(logs.any { "no longer matches source state" in it }, logs.joinToString("\n"))
    }

    private fun buildWithSourceMismatch(mismatch: Boolean): List<String> {
        val original = ByteArray(0x300000)
        fun word(pc: Int, value: Int) {
            original[pc] = value.toByte()
            original[pc + 1] = (value ushr 8).toByte()
        }
        original[0x791FC] = 1
        original[0x791FD] = 1
        word(0x79203, 0xE612)
        word(0x79206, 0x9230)
        word(0x79208, 0xE5E6)
        val parser = RomParser(original)
        val project = SmEditProject("synthetic.sfc")
        val state = project.getOrCreateRoom(0x91F8).ensureStateManifest(parser).first()
        state.condition = state.condition.copy(argument = 1)
        state.conditionChanged = true
        val generated = original.copyOf().also { it[0x79205] = 1 }
        val compiled = AsmCompiledBuildBase(
            romBytes = generated, referenceRomBody = original, referenceRomSha256 = bytesSha256(original),
            sourceOwnedRanges = emptyList(),
            generatedRoomClaims = listOf(AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, "Selector", 0x79205, byteArrayOf(1))),
            generatedRoomIds = setOf("91F8"), assemblerVersion = "synthetic", compilerOutput = emptyList(),
            roomValidationRomBytes = original.copyOf().also { if (mismatch) it[0x79205] = 2 },
        )
        val logs = mutableListOf<String>()
        RomExporter(project, parser, onLog = logs::add, compiledAsmBase = compiled).build()
        return logs
    }
}
