package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ensureStateManifest
import kotlin.test.*

/** Synthetic room, two states sharing a list; no external ROM/source checkout required. */
internal fun objectSourceTestRom(): ByteArray = ByteArray(0x300000).also { rom ->
    fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value shr 8).toByte() }
    val header = 0x791F8
    rom[header + 4] = 2; rom[header + 5] = 2
    word(header + 11, 0xE612); rom[header + 13] = 1; word(header + 14, 0x9300)
    word(header + 16, 0xE5E6)
    word(header + 18 + 20, 0x8000); word(0x79300 + 20, 0x8000)
    RomParser.serializePlmSet(listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5))).forEachIndexed { i, value ->
        rom[0x78000 + i] = value.toByte()
    }
}

class AsmRoomObjectSourceTest {
    private val parser get() = RomParser(objectSourceTestRom())
    private val missile = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
    private val extra = RomParser.PlmEntry(0xEEDF, 8, 9, 12)
    private fun migrated(room: RoomEdits): AsmRoomObjectSource {
        val result = AsmRoomObjectSource.migrate("; unrelated source\r\n", room, parser, "Test room", emptyMap())
        if (room.states.isEmpty()) room.states.addAll(result.states)
        room.plmChanges.clear(); room.states.forEach { it.plmChanges.clear() }
        room.asmPlmSourceVersion = 1
        return AsmRoomObjectSource(result.text)
    }

    @Test fun `migration preserves common additions state exceptions and original text`() {
        val room = RoomEdits(0x91F8)
        room.ensureStateManifest(parser)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        room.states.last().plmChanges += PlmChange("remove", extra.id, extra.x, extra.y, extra.param)
        val source = migrated(room)
        assertTrue(source.text.startsWith("; unrelated source\r\n"))
        assertEquals(listOf(missile, extra), source.table(room.roomId, "state-1").document.entries)
        assertEquals(listOf(missile), source.table(room.roomId, "state-2").document.entries)
        assertNull(source.table(room.roomId, "state-1").objects.last().commonMacro, "One remaining consumer needs no helper")
        assertTrue(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() })
    }

    @Test fun `generated comments name live entries and omit unused helpers without changing values`() {
        val room = RoomEdits(0x91F8)
        room.ensureStateManifest(parser)
        val additions = listOf(
            0xEEF7 to "Speed Booster (Visible)", 0xEF0B to "Gravity Suit (Visible)",
            0xEF23 to "Morph Ball (Visible)", 0xB703 to "Scroll trigger", 0xB647 to "Scroll ext ↑",
            0xC848 to "Door Cap: Grey Right", 0xF300 to "PLM 0xF300",
        )
        additions.forEachIndexed { index, (id, _) -> room.plmChanges += PlmChange("add", id, index + 4, 9, 12) }
        // A common addition removed from every state must not leave a helper behind.
        room.states.forEach { it.plmChanges += PlmChange("remove", 0xEEF7, 4, 9, 12) }
        val migration = AsmRoomObjectSource.migrate("; unchanged original\n", room, parser, "Landing Site", emptyMap())
        val source = AsmRoomObjectSource(migration.text)
        assertContains(source.text, "PLMPopEntry(type, tile X, tile Y, parameter)")
        assertContains(source.text, "; PLM: Missile (Visible)")
        for ((_, name) in additions.drop(1)) assertContains(source.text, "; PLM: $name")
        assertFalse(source.text.contains("SmeditObjects_91F8_Common_"))
        assertContains(source.text, "%PLMPopEntry(${'$'}EF0B, ${'$'}05, ${'$'}09, ${'$'}000C) ; PLM: Gravity Suit (Visible)")
        assertContains(source.text, "dw ${'$'}0000 ; End of PLM list")
        for ((stateId, expected) in migration.expected) {
            assertEquals(RomParser.serializePlmSet(expected), RomParser.serializePlmSet(source.table(room.roomId, stateId).document.entries))
        }
        assertEquals(source.text, source.withComments(room.roomId))
    }

    @Test fun `comment refresh preserves handwritten notes literals other rooms and CRLF`() {
        val room = RoomEdits(0x91F8)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        val original = migrated(room).text.replace(Regex(" ; PLM: [^\\r\\n]*"), "")
            .replace(AsmRoomObjectSource.renderEntry(extra), "%PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; my note; keep this too")
            .replace("\r\n", "\n")
        val otherRoom = "macro SmeditObjects_9999_Common_1()\n    ${AsmRoomObjectSource.renderEntry(extra)} ; Other room\nendmacro\n"
        val legacy = (original + otherRoom).replace("\n", "\r\n")
        val symbols = mapOf("ItemId" to extra.id)
        val annotated = AsmRoomObjectSource(legacy, symbols).withComments(room.roomId)
        assertContains(annotated, "%PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; PLM: Super Missile (Visible) ; my note; keep this too\r\n")
        assertTrue(annotated.endsWith(otherRoom.replace("\n", "\r\n")))
        assertEquals(legacy.lines().map { it.substringBefore(';').trimEnd() }, annotated.lines().map { it.substringBefore(';').trimEnd() })
        assertFalse(annotated.replace("\r\n", "").contains('\n'))
        assertEquals(annotated, AsmRoomObjectSource(annotated, symbols).withComments(room.roomId))
        val renamed = AsmRoomObjectSource(annotated, symbols + ("ItemId" to 0xEEE3)).withComments(room.roomId)
        assertContains(renamed, "; PLM: Power Bomb (Visible) ; my note; keep this too")
        assertContains(renamed, "%PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; PLM: Power Bomb (Visible)")
    }

    @Test fun `custom descriptions cannot introduce executable source or extra lines`() {
        val room = RoomEdits(0x91F8)
        val original = migrated(room)
        val annotated = original.withComments(room.roomId) { "Custom item;\r\norg ${'$'}808000\tdb 0\u2028extra\u2029text" }
        assertEquals(original.text.lines().size, annotated.lines().size)
        assertContains(annotated, "; PLM: Custom item org ${'$'}808000 db 0 extra text")
        assertEquals(original.resources(room), AsmRoomObjectSource(annotated).resources(room))
    }

    @Test fun `state-local editing forks shared lists and all-state editing updates each resource once`() {
        val room = RoomEdits(0x91F8)
        var source = migrated(room)
        assertEquals(source.table(room.roomId, "state-1").resource, source.table(room.roomId, "state-2").resource)
        source = AsmRoomObjectSource(source.edit(room, "state-1", false, { false }, extra))
        assertEquals(listOf(missile, extra), source.table(room.roomId, "state-1").document.entries)
        assertEquals(listOf(missile), source.table(room.roomId, "state-2").document.entries)
        val replacement = extra.copy(id = 0xEEE3)
        source = AsmRoomObjectSource(source.edit(room, "state-1", true, { it.x == extra.x && it.y == extra.y }, replacement))
        for (state in room.states) {
            val table = source.table(room.roomId, state.id)
            assertEquals(listOf(missile, replacement), table.document.entries)
            assertNull(table.objects.last().commonMacro)
        }
        source = AsmRoomObjectSource(source.edit(room, "state-1", true, { it == replacement }, null))
        assertTrue(source.resources(room).values.all { it.entries == listOf(missile) })
        assertFalse(source.text.contains("Common_"))
    }

    private fun singleStateRoom() = RoomEdits(0x91F8).also { room ->
        room.ensureStateManifest(parser)
        room.states.removeAt(0)
    }

    @Test fun `single default state migrates and repeatedly edits as direct entries`() {
        val room = singleStateRoom()
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        var source = migrated(room)
        assertFalse(source.text.contains("Common_"))
        val state = room.states.single().id
        repeat(10) { iteration ->
            source = AsmRoomObjectSource(source.edit(room, state, true, { it.x == extra.x }, extra.copy(param = 20 + iteration)))
            assertFalse(source.text.contains("Common_"))
            assertEquals(listOf(missile, extra.copy(param = 20 + iteration)), source.table(room.roomId, state).document.entries)
        }
        source = AsmRoomObjectSource(source.edit(room, state, true, { it.x == extra.x }, null))
        assertEquals(listOf(missile), source.table(room.roomId, state).document.entries)
    }

    @Test fun `repeated multi-state add remove leaves no helpers or accumulating whitespace`() {
        val room = RoomEdits(0x91F8)
        var source = migrated(room)
        val before = source.text
        repeat(10) {
            source = AsmRoomObjectSource(source.edit(room, "state-1", true, { false }, extra))
            assertNull(source.table(room.roomId, "state-1").objects.last().commonMacro)
            source = AsmRoomObjectSource(source.edit(room, "state-1", true, { it == extra }, null))
            assertEquals(before, source.text)
        }
    }

    @Test fun `single-state cleanup inlines legacy helpers and drops old duplicates without renumbering lists`() {
        val room = singleStateRoom()
        var source = migrated(room)
        val list = source.table(room.roomId, room.states.single().id).resource.name
        val helper = "SmeditObjects_91F8_Common_5"
        val legacy = source.text.replace("    dw", "    %$helper() ; PLM: Super Missile (Visible) ; call note\n    dw") +
            "\nmacro SmeditObjects_91F8_Common_2()\n    ${AsmRoomObjectSource.renderEntry(extra)}\nendmacro\n" +
            "\nmacro $helper() ; definition note\n    ; before record\n" +
            "    %PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; PLM: Super Missile (Visible) ; pickup note\n" +
            "    ; after record\nendmacro ; closing note\n"
        source = AsmRoomObjectSource(legacy.replace("\r\n", "\n").replace("\n", "\r\n"), mapOf("ItemId" to extra.id))
        val before = source.resources(room)
        val cleaned = source.tidy(room)
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "%PLMPopEntry(ItemId, 8, %1001, ${'$'}000c)")
        for (note in listOf("call note", "definition note", "before record", "pickup note", "after record", "closing note")) {
            assertContains(cleaned, note)
        }
        assertFalse(cleaned.replace("\r\n", "").contains('\n'))
        val decoded = AsmRoomObjectSource(cleaned, mapOf("ItemId" to extra.id))
        assertEquals(before, decoded.resources(room))
        assertEquals(list, decoded.table(room.roomId, room.states.single().id).resource.name)
        assertEquals(cleaned, decoded.tidy(room))
    }

    @Test fun `multi-state cleanup expands legacy object helpers while preserving whole-list sharing`() {
        val room = RoomEdits(0x91F8)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        var source = migrated(room)
        val common = "SmeditObjects_91F8_Common_1"
        val legacy = source.text.replace(AsmRoomObjectSource.renderEntry(extra), "%$common()") +
            "\nmacro $common()\n    ${AsmRoomObjectSource.renderEntry(extra)} ; shared pickup note\nendmacro\n"
        source = AsmRoomObjectSource(legacy)
        val resources = source.resources(room)
        val cleaned = source.tidy(room)
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "shared pickup note")
        source = AsmRoomObjectSource(cleaned)
        assertEquals(resources, source.resources(room))
        assertEquals(source.text, source.tidy(room))
        assertEquals(source.table(room.roomId, "state-1").resource, source.table(room.roomId, "state-2").resource)
        source = AsmRoomObjectSource(source.edit(room, "state-1", false, { it == extra }, null))
        assertFalse(source.contains(common))
        assertEquals(listOf(missile), source.table(room.roomId, "state-1").document.entries)
        assertEquals(listOf(missile, extra), source.table(room.roomId, "state-2").document.entries)
        assertNull(source.table(room.roomId, "state-2").objects.last().commonMacro)
    }

    @Test fun `legacy helpers expand into separate lists without breaking an external custom caller`() {
        val room = RoomEdits(0x91F8)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        val shared = migrated(room)
        val separate = AsmRoomObjectSource(shared.edit(room, "state-1", false, { false }, extra.copy(x = 20)))
        val helper = "SmeditObjects_91F8_Common_2"
        val legacy = separate.text.replace(AsmRoomObjectSource.renderEntry(extra), "%$helper() ; call note") +
            "\nmacro $helper()\n    %PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; pickup note\nendmacro\n"
        val symbols = mapOf("ItemId" to extra.id)
        val source = AsmRoomObjectSource(legacy, symbols)
        val before = source.resources(room)
        val cleaned = AsmRoomObjectSource(source.tidy(room, setOf(helper)), symbols)
        assertEquals(before, cleaned.resources(room))
        assertTrue(cleaned.contains(helper), "Custom ASM still needs its original helper definition")
        for (state in room.states) {
            val table = cleaned.table(room.roomId, state.id)
            assertTrue(table.objects.all { it.commonMacro == null })
            assertContains(cleaned.text.substring(table.resource.bodyStart, table.resource.bodyEnd),
                "%PLMPopEntry(ItemId, 8, %1001, ${'$'}000c) ; pickup note")
        }
        assertEquals(cleaned.text, cleaned.tidy(room, setOf(helper)))
        val unpinned = AsmRoomObjectSource(cleaned.tidy(room), symbols)
        assertFalse(unpinned.contains(helper))
        assertEquals(before, unpinned.resources(room))
    }

    @Test fun `cleanup respects other code external references unsupported bodies and handwritten notes`() {
        val room = singleStateRoom()
        val base = migrated(room)
        fun common(number: Int, body: String = "    ${AsmRoomObjectSource.renderEntry(extra)}\n") =
            "\nmacro SmeditObjects_91F8_Common_$number()\n${body}endmacro\n"
        val external = "SmeditObjects_91F8_Common_2"
        val text = base.text + common(1) + common(2) + common(3, "    db 7 ; custom body\n") +
            common(4, "    ${AsmRoomObjectSource.renderEntry(extra)} ; PLM: stale name ; keep my note\n") +
            "\nmacro MyOwnRoutine()\n    %SmeditObjects_91F8_Common_1()\nendmacro\n" +
            "\nmacro SmeditObjects_9999_Common_1()\n    ${AsmRoomObjectSource.renderEntry(extra)}\nendmacro\n"
        val cleaned = AsmRoomObjectSource(text).tidy(room, setOf(external))
        assertTrue(AsmRoomObjectSource(cleaned).contains(external))
        assertTrue(AsmRoomObjectSource(cleaned).contains("SmeditObjects_91F8_Common_1"))
        assertContains(cleaned, "db 7 ; custom body")
        assertFalse(cleaned.contains("SmeditObjects_91F8_Common_4"))
        assertContains(cleaned, "; keep my note")
        assertFalse(cleaned.contains("stale name"))
        assertTrue(cleaned.endsWith(text.substring(text.indexOf("\nmacro MyOwnRoutine"))))
    }

    @Test fun `unused list and its common helper are removed together`() {
        val room = singleStateRoom()
        val source = migrated(room)
        val unused = "\nmacro SmeditObjects_91F8_List_99()\n    %SmeditObjects_91F8_Common_99()\n    dw ${'$'}0000\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_99()\n    ${AsmRoomObjectSource.renderEntry(extra)}\nendmacro\n"
        assertEquals(source.text, AsmRoomObjectSource(source.text + unused).tidy(room))
    }

    @Test fun `adding a second state shares the simple list and later state-local edits fork it`() {
        val room = singleStateRoom()
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        var source = migrated(room)
        val originalState = room.states.single()
        source = AsmRoomObjectSource(source.cloneState(room.roomId, originalState.id, "new-state"))
        room.states.add(originalState.copy(id = "new-state"))
        assertEquals(source.table(room.roomId, originalState.id).resource, source.table(room.roomId, "new-state").resource)
        source = AsmRoomObjectSource(source.edit(room, "new-state", false, { it == extra }, null))
        assertEquals(listOf(missile, extra), source.table(room.roomId, originalState.id).document.entries)
        assertEquals(listOf(missile), source.table(room.roomId, "new-state").document.entries)
        source = AsmRoomObjectSource(source.edit(room, "new-state", true, { false }, extra.copy(x = 15)))
        assertFalse(source.text.contains("Common_"))
        for (state in room.states) {
            assertEquals(extra.copy(x = 15), source.table(room.roomId, state.id).document.entries.last())
            assertNull(source.table(room.roomId, state.id).objects.last().commonMacro)
        }
    }

    @Test fun `deleting a conditional state releases its private list and simplifies the remaining default`() {
        val room = RoomEdits(0x91F8)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        var source = migrated(room)
        source = AsmRoomObjectSource(source.edit(room, "state-1", false, { false }, extra.copy(x = 20)))
        val retiredList = source.table(room.roomId, "state-1").resource.name
        room.states.removeAt(0)
        val before = source.resources(room)
        val cleaned = AsmRoomObjectSource(source.tidy(room))
        assertEquals(before, cleaned.resources(room))
        assertFalse(cleaned.contains(retiredList))
        assertFalse(cleaned.contains(AsmRoomObjectSource.stateMacro(room.roomId, "state-1")))
        assertFalse(cleaned.text.contains("Common_"))
        assertEquals(listOf(missile, extra), cleaned.table(room.roomId, "state-2").document.entries)
        // A custom caller can deliberately retain a retired state wrapper and its complete graph.
        val pinned = AsmRoomObjectSource(source.tidy(room, setOf(AsmRoomObjectSource.stateMacro(room.roomId, "state-1"))))
        assertTrue(pinned.contains(retiredList))
        assertTrue(pinned.contains(AsmRoomObjectSource.stateMacro(room.roomId, "state-1")))
        assertEquals(before, pinned.resources(room))
    }

    @Test fun `new state inherits common removals additions and template exceptions`() {
        val room = RoomEdits(0x91F8)
        var source = migrated(room)
        source = AsmRoomObjectSource(source.edit(room, "state-1", true, { it == missile }, extra))
        source = AsmRoomObjectSource(source.edit(room, "state-1", false, { it == extra }, extra.copy(x = 10)))
        val added = room.states.first().copy(id = "new-id", sourceStateIndex = null, templateSourceStateIndex = 0)
        source = AsmRoomObjectSource(source.cloneState(room.roomId, "state-1", added.id))
        room.states.add(0, added)
        assertEquals(listOf(extra.copy(x = 10)), source.resources(room).getValue(added.id).entries)
        assertEquals(source.resources(room).getValue("state-1"), source.resources(room).getValue(added.id))
        assertEquals(listOf(extra), source.resources(room).getValue("state-2").entries)
    }

    @Test fun `token lenses and navigation point to direct rows in a shared list and permit list growth`() {
        val room = RoomEdits(0x91F8)
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        var source = migrated(room)
        val table = source.table(room.roomId, "state-1")
        val edited = table.document.replace(1, extra.copy(param = 20))
        source = AsmRoomObjectSource(edited)
        assertTrue(source.resources(room).values.all { it.entries.last().param == 20 })
        val target = assertIs<AsmPlmPlacementTarget.Source>(source.table(room.roomId, "state-2").document.placementTarget(extra.copy(param = 20)))
        assertContains(source.text.lines()[target.lineIndex], "%PLMPopEntry")
        source = AsmRoomObjectSource(source.edit(room, "state-1", false, { false }, extra.copy(x = 11)))
        assertEquals(3, source.table(room.roomId, "state-1").document.entries.size)
        assertEquals(2, source.table(room.roomId, "state-2").document.entries.size)
    }

    @Test fun `invalid layouts fail closed and unsupported migrations leave deltas untouched`() {
        val room = RoomEdits(0x91F8)
        val source = migrated(room)
        for (broken in listOf(
            source.text.replace("dw ${'$'}0000", "db ${'$'}00"),
            source.text.replace("${'$'}EEDB", "UnknownSymbol"),
            source.text.replace("${'$'}02", "${'$'}100"),
            source.text + source.text,
        )) assertFails { AsmRoomObjectSource(broken).resources(room) }
        room.plmChanges += PlmChange("add", extra.id, extra.x, extra.y, extra.param)
        assertFails { source.resources(room) }
        val unmigrated = RoomEdits(0x91F8)
        unmigrated.plmChanges.addAll(room.plmChanges)
        unmigrated.ensureStateManifest(parser)
        unmigrated.states.first().resourcesChanged = true
        assertFails { AsmRoomObjectSource.migrate("original", unmigrated, parser, "Test", emptyMap()) }
        assertEquals(room.plmChanges, unmigrated.plmChanges)
        assertEquals(0, unmigrated.asmPlmSourceVersion)
    }

    @Test fun `equal bytes in independent original lists do not acquire new sharing`() {
        val rom = objectSourceTestRom()
        rom[0x79300 + 20] = 0x20; rom[0x79300 + 21] = 0x80.toByte()
        rom.copyInto(rom, 0x78020, 0x78000, 0x78008)
        val room = RoomEdits(0x91F8)
        val result = AsmRoomObjectSource.migrate("", room, RomParser(rom), "Test", emptyMap())
        val source = AsmRoomObjectSource(result.text)
        assertNotEquals(source.table(room.roomId, "state-1").resource, source.table(room.roomId, "state-2").resource)
        assertEquals(source.table(room.roomId, "state-1").document.entries, source.table(room.roomId, "state-2").document.entries)
    }
}
