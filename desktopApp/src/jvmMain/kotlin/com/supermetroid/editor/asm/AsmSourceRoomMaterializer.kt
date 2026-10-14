package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.ProjectRoomExporter
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport

internal const val ASM_ROOM_GRAPH_OWNER = "room-graph:project"

internal data class AsmGeneratedRoomClaim(
    val owner: String,
    val label: String,
    val pcOffset: Int,
    val bytes: ByteArray,
) {
    val length: Int get() = bytes.size
    val endExclusive: Int get() = pcOffset + length

    override fun equals(other: Any?): Boolean =
        other is AsmGeneratedRoomClaim &&
            owner == other.owner &&
            label == other.label &&
            pcOffset == other.pcOffset &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = owner.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + pcOffset
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal data class AsmRoomMaterialization(
    val source: String,
    val claims: List<AsmGeneratedRoomClaim>,
    val roomIds: Set<String>,
    val eligibleWrites: Int,
    val skippedWrites: Int,
    val plmTables: List<AsmRoomPlmTable> = emptyList(),
    val scrollTables: List<AsmRoomScrollTable> = emptyList(),
    val saveTables: List<AsmRoomSaveTable> = emptyList(),
) {
    val isEmpty: Boolean get() = claims.isEmpty()

    companion object {
        val EMPTY = AsmRoomMaterialization("", emptyList(), emptySet(), 0, 0)
    }
}

/**
 * Promotes the already validated existing-room graph into the ASM input stage.
 *
 * The established room exporter remains the allocation/pointer oracle for this
 * first source-native layer. Its complete final byte set is emitted atomically:
 * either every room-graph write is assembled before export, or the complete
 * graph remains on the post-compile backend. This gives later label/table based
 * emitters one guarded integration boundary without changing ROM output today.
 *
 * Project-created rooms deliberately remain on the ROM backend until they have
 * stable source identities independent from their preview and allocated IDs.
 */
internal class AsmSourceRoomMaterializer {
    fun materialize(
        project: SmEditProject,
        report: RomWritePlanReport,
        plannedRom: ByteArray? = null,
        compiledRomSize: Int = AsmProjectCompiler.ASM_ROM_SIZE,
        sourceScrolls: Map<String, com.supermetroid.editor.rom.SourceScrollResource> = emptyMap(),
        sourceSaves: Map<String, com.supermetroid.editor.rom.SourceSaveResource> = emptyMap(),
    ): AsmRoomMaterialization {
        if (!ProjectRoomExporter.hasRoomEdits(project)) return AsmRoomMaterialization.EMPTY

        val writes = report.writes.filter { it.owner == ASM_ROOM_GRAPH_OWNER }
        if (writes.isEmpty()) return AsmRoomMaterialization.EMPTY
        if (project.newRooms.isNotEmpty() || writes.any {
                it.kind !in MATERIALIZABLE_KINDS || it.offset < 0 || it.endInclusive >= compiledRomSize
            }
        ) {
            return AsmRoomMaterialization(
                source = "",
                claims = emptyList(),
                roomIds = emptySet(),
                eligibleWrites = writes.size,
                skippedWrites = writes.size,
            )
        }

        val finalBytes = sortedMapOf<Int, Int>()
        writes.forEach { write ->
            write.bytes.forEachIndexed { index, value ->
                finalBytes[write.offset + index] = value and 0xFF
            }
        }
        if (finalBytes.isEmpty() || finalBytes.keys.any { pcToSnesLoRom(it) == null }) {
            return AsmRoomMaterialization(
                source = "",
                claims = emptyList(),
                roomIds = emptySet(),
                eligibleWrites = writes.size,
                skippedWrites = writes.size,
            )
        }

        val plmTables = plannedRom?.let { discoverAsmRoomPlmTables(project, report, it) }.orEmpty()
        val scrollTables = if (sourceScrolls.isEmpty()) emptyList() else discoverAsmRoomScrollTables(report, requireNotNull(plannedRom), sourceScrolls)
        val saveTables = if (sourceSaves.isEmpty()) emptyList() else discoverAsmRoomSaveTables(report, requireNotNull(plannedRom), sourceSaves)
        val typedClaims = plmTables.flatMap(AsmRoomPlmTable::claims) + scrollTables.map(AsmRoomScrollTable::claim) + saveTables.map(AsmRoomSaveTable::claim)
        val typedOffsets = hashSetOf<Int>()
        typedClaims.forEach { claim ->
            (claim.pcOffset until claim.endExclusive).forEach { pc ->
                require(typedOffsets.add(pc)) { "Generated PLM resources overlap at PC ${pc.toString(16)}" }
                finalBytes.remove(pc)
            }
        }
        val ranges = contiguousRanges(finalBytes.keys)
        val rawClaims = ranges.mapIndexed { index, range ->
            val contributing = writes.filter { it.overlaps(range) }
            val allocationLabels = contributing
                .filter { it.kind == RomWriteKind.ALLOCATION }
                .map(RomWriteIntent::label)
                .distinct()
            val labels = (allocationLabels.ifEmpty { contributing.map(RomWriteIntent::label).distinct() })
                .take(MAX_LABELS_PER_CLAIM)
            AsmGeneratedRoomClaim(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = buildString {
                    append("Generated room source")
                    if (labels.isNotEmpty()) append(" · ").append(labels.joinToString(" + "))
                    if (ranges.size > 1) append(" · ").append(index + 1)
                },
                pcOffset = range.first,
                bytes = ByteArray(range.last - range.first + 1) { offset ->
                    checkNotNull(finalBytes[range.first + offset]).toByte()
                },
            )
        }
        val roomIds = project.rooms.entries.asSequence()
            .filter { it.value.hasEdits }
            .mapNotNull { (key, _) -> key.toIntOrNull(16) }
            .map { it.toString(16).uppercase().padStart(4, '0') }
            .toCollection(linkedSetOf())

        return AsmRoomMaterialization(
            source = renderSource(rawClaims) + scrollTables.joinToString("\n", prefix = "\n") { it.renderSource() } +
                plmTables.joinToString("\n", prefix = "\n") { it.renderSource() } + saveTables.joinToString("\n", prefix = "\n") { it.renderSource() },
            claims = rawClaims + typedClaims,
            roomIds = roomIds,
            eligibleWrites = writes.size,
            skippedWrites = 0,
            plmTables = plmTables,
            scrollTables = scrollTables,
            saveTables = saveTables,
        )
    }

    private fun renderSource(claims: List<AsmGeneratedRoomClaim>): String = buildString {
        appendLine("; Generated placement plan for this build. Do not edit this artifact.")
        appendLine("; Authored object macros own their values; other resources still come from the room model.")
        appendLine("; Typed PLM tables use symbolic state pointers; other resources retain byte overlays.")
        claims.forEachIndexed { index, claim ->
            val snesAddress = checkNotNull(pcToSnesLoRom(claim.pcOffset))
            appendLine()
            appendLine("; ${claim.label.replace('\n', ' ')}")
            appendLine("org \$${snesAddress.toString(16).uppercase().padStart(6, '0')}")
            appendLine("SmeditRoomGraph_${(index + 1).toString().padStart(3, '0')}:")
            claim.bytes.asList().chunked(BYTES_PER_LINE).forEach { row ->
                append("db ")
                appendLine(row.joinToString(",") { "\$${(it.toInt() and 0xFF).hex2()}" })
            }
        }
    }

    private fun contiguousRanges(indices: Collection<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.sorted()
        val result = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        for (index in sorted.drop(1)) {
            if (index != previous + 1 || index % 0x8000 == 0) {
                result += start..previous
                start = index
            }
            previous = index
        }
        result += start..previous
        return result
    }

    private fun RomWriteIntent.overlaps(range: IntRange): Boolean =
        offset <= range.last && range.first <= endInclusive

    private fun Int.hex2(): String = toString(16).uppercase().padStart(2, '0')

    private companion object {
        const val BYTES_PER_LINE = 16
        const val MAX_LABELS_PER_CLAIM = 2
        val MATERIALIZABLE_KINDS = setOf(RomWriteKind.ROOM, RomWriteKind.ALLOCATION)
    }
}
