package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.Serializable
import java.io.File

internal fun snapshotRoomObjectMetadata(room: RoomEdits): RoomEdits = room.copy(
    plmChanges = room.plmChanges.toMutableList(),
    customScrollCommands = room.customScrollCommands.mapValuesTo(linkedMapOf()) { it.value.toMutableList() },
    states = room.states.map { state -> state.copy(
        plmChanges = state.plmChanges.toMutableList(),
        customScrollCommands = state.customScrollCommands.mapValuesTo(linkedMapOf()) { it.value.toMutableList() },
    ) }.toMutableList(),
)

/** Only metadata read by the object lens; unrelated layout/enemy/FX edits do not invalidate it. */
internal fun roomObjectIdentity(room: RoomEdits): String {
    require(room.asmPlmSourceVersion == 1 && room.states.isNotEmpty()) { "Unsupported or missing room-object ownership" }
    require(room.states.map { it.id }.distinct().size == room.states.size) { "Duplicate object-source state identities" }
    require(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() }) { "Room objects have both GUI deltas and authored source" }
    require(room.customScrollCommands.isEmpty() && room.states.all { it.customScrollCommands.isEmpty() }) {
        "Custom scroll commands cannot be mixed with authored object lists yet"
    }
    return "${room.roomId}:${room.asmPlmSourceVersion}:" + room.states.map { it.id }.sorted().joinToString { "${it.length}:$it" }
}

internal data class AsmRoomProjection(val identity: String, val documents: Map<String, AsmPlmDocument>)

/** Typed decoded views, verified against exact source/dependencies. Neither builds nor saves consume cache values. */
internal class AsmRoomProjectionRepository(
    directory: File,
    private val decode: (String, Map<String, Int>) -> AsmRoomObjectSource = ::AsmRoomObjectSource,
) {
    private val cache = AsmVerifiedCache(directory)
    @Serializable private data class Span(val start: Int, val end: Int)
    @Serializable private data class Row(val id: Int, val x: Int, val y: Int, val param: Int, val fields: List<Span>)
    @Serializable private data class Table(val stateId: String, val label: String, val line: Int, val rows: List<Row>)
    @Serializable private data class Snapshot(val roomId: Int, val identity: String, val tables: List<Table>)

    fun load(text: String, macros: String, rooms: List<RoomEdits>, catalog: AsmPlmSourceCatalog): Map<Int, AsmRoomProjection> {
        catalog.requireStandardMacro(macros)
        val dependencies = AsmVerifiedCache.fingerprint(mapOf(
            "format" to "room-projection-v1", "source" to text, "macros" to macros,
            "symbols" to catalog.sourceFingerprint, "base-rom" to catalog.romFingerprint,
        ))
        val source by lazy { decode(text, catalog.symbols) }
        return buildMap {
            for (room in rooms) runCatching {
                val identity = roomObjectIdentity(room)
                fun Snapshot.documents(): Map<String, AsmPlmDocument> {
                    require(roomId == room.roomId && this.identity == identity)
                    require(tables.map { it.stateId }.toSet() == room.states.map { it.id }.toSet() && tables.size == room.states.size)
                    return tables.associate { table ->
                        require(table.label.startsWith(AsmRoomObjectSource.prefix(roomId) + "List_") && table.line >= 0)
                        val records = table.rows.map { row ->
                            val entry = RomParser.PlmEntry(row.id, row.x, row.y, row.param)
                            AsmPlmDocument.validateEntry(entry)
                            require(row.fields.size == 4)
                            val expected = listOf(row.id, row.x, row.y, row.param)
                            val fields = row.fields.mapIndexed { index, span ->
                                require(span.start >= 0 && span.end > span.start && span.end <= text.length)
                                val token = text.substring(span.start, span.end)
                                require((AsmPlmDocument.literal(token) ?: catalog.symbols[token]) == expected[index])
                                span.start until span.end
                            }
                            AsmPlmDocument.Record(entry, fields)
                        }
                        table.stateId to AsmPlmDocument(text, table.label, table.line, records)
                    }
                }
                val snapshot = cache.loadOrBuild("room-${room.roomId.toString(16)}", "$dependencies:$identity", Snapshot.serializer(),
                    validate = { it.documents() },
                ) {
                    source.resources(room) // Validate ownership and every active list, not just the selected state.
                    Snapshot(room.roomId, identity, room.states.map { state ->
                        val doc = source.table(room.roomId, state.id).document
                        Table(state.id, doc.label, doc.lineIndex, doc.records.map { record ->
                            val entry = record.entry
                            Row(entry.id, entry.x, entry.y, entry.param, record.fields.map { Span(it.first, it.last + 1) })
                        })
                    })
                }
                put(room.roomId, AsmRoomProjection(identity, snapshot.documents()))
            }
        }
    }
}
