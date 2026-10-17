package com.supermetroid.editor.maker

import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal target-neutral Maker project (Phase 0A vertical slice V1).
 *
 * It holds only what V1 needs: layer-1 terrain, item placements and the door list
 * of each room. The Classic exporter turns differences into ordinary SMEDIT edits,
 * so the ROM is still written only by SMEDIT's own exporter.
 */
@Serializable
data class MakerProject(
    val formatVersion: Int = 1,
    val mode: String = "classic",
    val rooms: List<MakerRoom> = emptyList(),
)

@Serializable
data class MakerRoom(
    val roomId: Int,
    val widthBlocks: Int,
    val heightBlocks: Int,
    /** Layer-1 block words (block type << 12 | flips | metatile), row-major. */
    val terrain: List<Int>,
    /** Layer-1 BTS bytes, row-major. */
    val bts: List<Int>,
    val items: List<MakerItem>,
    val doors: List<MakerDoor>,
) {
    fun blockIndex(x: Int, y: Int): Int = y * widthBlocks + x
}

@Serializable
data class MakerItem(val plmId: Int, val x: Int, val y: Int)

@Serializable
data class MakerDoor(val index: Int, val destRoomPtr: Int, val doorDefPtr: Int)

object MakerJson {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    fun encode(project: MakerProject): String = json.encodeToString(MakerProject.serializer(), project)
    fun decode(text: String): MakerProject = json.decodeFromString(MakerProject.serializer(), text)
}

object MakerImporter {
    /** Reads the room currently loaded in [editorState] (its working, possibly edited, state). */
    fun fromEditor(editorState: EditorState): MakerRoom {
        val data = requireNotNull(editorState.workingLevelData) { "No room loaded" }
        val w = editorState.workingBlocksWide
        val h = editorState.workingBlocksTall
        val n = w * h
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        require(layer1Size == n * 2) { "Unexpected layer-1 size $layer1Size for ${w}x$h blocks" }
        val terrain = List(n) { i -> (data[2 + 2 * i].toInt() and 0xFF) or ((data[3 + 2 * i].toInt() and 0xFF) shl 8) }
        val bts = List(n) { i -> data[2 + 2 * n + i].toInt() and 0xFF }
        val items = editorState.workingPlms
            .filter { editorState.isEditorItemPlm(it.id) }
            .map { MakerItem(it.id, it.x, it.y) }
        val doors = editorState.doorEntries.mapIndexed { i, d -> MakerDoor(i, d.destRoomPtr, d.doorDefPtr) }
        return MakerRoom(editorState.currentRoomId, w, h, terrain, bts, items, doors)
    }
}

/** Classic exporter, step 1: translate a Maker room into SMEDIT edits on the loaded room. */
object ClassicMakerTranslator {
    data class Result(val tileEdits: Int, val itemsRemoved: Int, val itemsAdded: Int)

    fun apply(target: MakerRoom, editorState: EditorState): Result {
        val current = MakerImporter.fromEditor(editorState)
        require(target.roomId == current.roomId) { "Room mismatch: loaded ${current.roomId}, target ${target.roomId}" }
        require(target.widthBlocks == current.widthBlocks && target.heightBlocks == current.heightBlocks) {
            "Room resize is not supported by the V1 Classic exporter"
        }
        require(target.doors == current.doors) { "Door edits are not supported by the V1 Classic exporter" }

        val edits = mutableListOf<TileEdit>()
        for (i in target.terrain.indices) {
            if (target.terrain[i] != current.terrain[i] || target.bts[i] != current.bts[i]) {
                edits += TileEdit(
                    blockX = i % target.widthBlocks,
                    blockY = i / target.widthBlocks,
                    oldBlockWord = current.terrain[i],
                    newBlockWord = target.terrain[i],
                    oldBts = current.bts[i],
                    newBts = target.bts[i],
                )
            }
        }
        // A Maker room is one layout: edits apply to every state that shares it, and items to every state.
        editorState.applyBulkEdits("Maker terrain", edits)
        if (editorState.pendingLayoutEditScope != null) editorState.resolvePendingLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)

        val removed = current.items - target.items.toSet()
        val added = target.items - current.items.toSet()
        for (item in removed) editorState.removePlm(item.x, item.y, item.plmId, ItemStateScope.ALL_STATES)
        // Param 0 matches the editor's own item placement; the exporter assigns the collection bit.
        for (item in added) editorState.addPlm(item.plmId, item.x, item.y, 0, ItemStateScope.ALL_STATES)
        val after = MakerImporter.fromEditor(editorState)
        check(after.terrain == target.terrain && after.bts == target.bts) { "Terrain did not apply as requested" }
        check(after.items.toSet() == target.items.toSet()) { "Items did not apply as requested: ${after.items}" }
        return Result(edits.size, removed.size, added.size)
    }
}

