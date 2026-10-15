package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomSourceLinkTest {
    @TempDir lateinit var directory: File

    @Test fun `native source and asset links identify resource families and all shared state consumers without filename guesses`() {
        val parser = RomParser(TestRomHelper.requireRomBytes())
        val semantic = AsmSemanticBridge.buildIndex(parser)
        val index = AsmSourceParser().parse(File(TestRomHelper.requireDisassemblyDir(), "src"),
            File(directory, "data").apply { mkdirs() }, AsmAssetManifest.loadBundled())
        val header = index.file("bank_8F.asm")!!
        val headerLine = header.lines.indexOfFirst { it.startsWith("RoomHeader_Flyway:") }
        val kinds = RoomAsmSourceKind.entries.filter { it != RoomAsmSourceKind.SAVE_SPAWN }.toSet()
        val headerLinks = asmRoomSourceLinksAt(index, header, headerLine + 2, semantic)
        assertEquals(kinds, headerLinks.map { it.kind }.toSet())
        assertTrue(headerLinks.all { it.roomId == 0x9879 && it.sourceStateIndex == null })
        val stateLine = header.lines.indexOfFirst { it.startsWith("RoomState_Flyway_1:") }
        val stateLinks = asmRoomSourceLinksAt(index, header, stateLine + 2, semantic)
        assertEquals(kinds, stateLinks.map { it.kind }.toSet())
        assertTrue(stateLinks.all { it.roomId == 0x9879 && it.sourceStateIndex == 1 })

        val asset = AsmAssetManifest.loadBundled().single { it.path == "LevelData_Flyway.bin" }
        val assets = semantic.roomSourcesFor(asset.snesAddress)
        assertEquals(setOf(0, 1, 2), assets.filter { it.roomId == 0x9879 }.map { it.sourceStateIndex }.toSet())
        assertTrue(assets.all { it.kind == RoomAsmSourceKind.LAYOUT })
        val layout = index.file("bank_B9..CE.asm")!!
        val line = layout.lines.indexOfFirst { it.startsWith("LevelData_Flyway:") }
        assertEquals(assets, asmRoomSourceLinksAt(index, layout, line, semantic))
        assertEquals(assets, asmRoomSourceLinksAt(index, layout, line + 1, semantic))
        val states = parser.parseRoomStatesWithData(0x9879)
        states.forEachIndexed { nativeIndex, state ->
            listOf(
                (RomConstants.BANK_FX or state.fxPtr) to RoomAsmSourceKind.FX,
                (RomConstants.BANK_ENEMY_SET or state.enemySetPtr) to RoomAsmSourceKind.ENEMIES,
                (RomConstants.BANK_ENEMY_GFX or state.enemyGfxPtr) to RoomAsmSourceKind.ENEMY_GRAPHICS,
                (RomConstants.BANK_ROOM_DATA or state.plmSetPtr) to RoomAsmSourceKind.PLMS,
            ).forEach { (address, kind) ->
                assertTrue(semantic.roomSourcesFor(address).any { it.roomId == 0x9879 && it.kind == kind && it.sourceStateIndex == nativeIndex })
            }
        }
        assertTrue(semantic.roomSourcesFor(0x8F91D4).isEmpty(), "Shared setup code is not owned room data")
        assertTrue(AsmSemanticIndex(emptyMap()).roomSourcesFor(asset.snesAddress).isEmpty(), "An asset name cannot invent a consumer")

        val sourceText = header.lines.joinToString("\n")
        assertEquals(headerLinks, asmRoomSourceLinksAt(index, header, headerLine + 2, semantic, sourceText.replace("\n", "\r\n") + "\r\n"))
        assertTrue(asmRoomSourceLinksAt(index, header, headerLine + 2, semantic, "; inserted unsaved line\n$sourceText").isEmpty(),
            "A stale line index must not offer adoption of the wrong room")
        val changedScope = header.copy(scopePathByLine = header.scopePathByLine.toMutableList().also { it[headerLine + 2] = listOf("UnrelatedCode") })
        assertTrue(asmRoomSourceLinksAt(index, changedScope, headerLine + 2, semantic).isEmpty(), "Do not adopt from a nearest address in a different label scope")
    }
}
