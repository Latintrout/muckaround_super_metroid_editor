package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.SourceScrollResource
import com.supermetroid.editor.rom.serializeScrollCommands

/** Placement-only generated source: command values remain in the authored macro. */
internal data class AsmRoomScrollTable(val resource: SourceScrollResource, val pcOffset: Int) {
    fun claim(): AsmGeneratedRoomClaim {
        val bytes = serializeScrollCommands(resource.commands)
        require(pcOffset >= 0x78000 && pcOffset + bytes.size <= 0x80000) { "Scroll resource must fit bank \$8F." }
        require(AsmScrollCommandSource.reference(resource.identity) == resource.identity) { "Invalid scroll resource identity." }
        return AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, resource.identity, pcOffset, bytes)
    }

    fun renderSource(): String = buildString {
        val claim = claim()
        val name = resource.identity
        appendLine("; Scroll command placement; values live in bank_8F.asm.")
        appendLine("org \$${checkNotNull(pcToSnesLoRom(pcOffset)).toString(16).uppercase()}")
        appendLine("$name:")
        appendLine("    %$name()")
        appendLine("${name}_End:")
        appendLine("assert ($name>>16) == \$8F")
        appendLine("assert ${name}_End-$name == ${claim.bytes.size}")
    }
}

internal fun discoverAsmRoomScrollTables(
    report: RomWritePlanReport, plannedRom: ByteArray, resources: Map<String, SourceScrollResource>,
): List<AsmRoomScrollTable> {
    val header = if (plannedRom.size % 0x8000 == 0x200) 0x200 else 0
    return resources.map { (name, resource) ->
        val allocation = report.writes.singleOrNull {
            it.owner == ASM_ROOM_GRAPH_OWNER && it.kind == RomWriteKind.ALLOCATION && it.label.endsWith(" source scrolls $name")
        } ?: error("Scroll source $name has no unique checked allocation.")
        val table = AsmRoomScrollTable(resource, allocation.offset)
        val claim = table.claim()
        require(allocation.bytes.size == claim.bytes.size && allocation.bytes.map(Int::toByte).toByteArray().contentEquals(claim.bytes) &&
            plannedRom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive).contentEquals(claim.bytes)) {
            "Scroll source $name differs from the planned command stream."
        }
        require(report.writes.none { it.owner != ASM_ROOM_GRAPH_OWNER && it.offset < claim.endExclusive && claim.pcOffset <= it.endInclusive }) {
            "Scroll source $name overlaps another write owner."
        }
        table
    }
}
