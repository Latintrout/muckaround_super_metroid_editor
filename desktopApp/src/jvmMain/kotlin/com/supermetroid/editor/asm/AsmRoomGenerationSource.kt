package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomStateResourceLinks
import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser

/** Only Bank $8F ownership metadata participates; never overwrite actors, FX or layout history. */
internal data class AsmRoomBank8FMetadata(
    val objectsVersion: Int,
    val scrollsVersion: Int,
    val layoutsVersion: Int,
    val propertiesVersion: Int,
    val selectorsVersion: Int,
    val objects: List<PlmChange>,
    val scrolls: List<ScrollChange>,
    val commands: Map<String, List<ScrollCommand>>,
    val saves: List<SaveStationSpawnChange>,
    val states: List<State>,
) {
    data class State(val id: String, val sourceIndex: Int?, val templateIndex: Int?, val condition: ProjectRoomStateCondition,
                     val resources: RoomStateResourceLinks, val objects: List<PlmChange>, val scrolls: List<ScrollChange>, val commands: Map<String, List<ScrollCommand>>)
    fun applyTo(room: RoomEdits) {
        require(room.states.map { it.id } == states.map { it.id }) { "Room states changed; no metadata applied." }
        room.asmPlmSourceVersion = objectsVersion
        room.asmInitialScrollSourceVersion = scrollsVersion
        room.asmLayoutSourceVersion = layoutsVersion
        room.asmRoomPropertiesSourceVersion = propertiesVersion
        room.asmStateSelectorsSourceVersion = selectorsVersion
        room.plmChanges.clear(); room.plmChanges.addAll(objects)
        room.scrollChanges.clear(); room.scrollChanges.addAll(scrolls)
        room.customScrollCommands.clear(); room.customScrollCommands.putAll(commands.mapValues { it.value.toMutableList() })
        room.saveStationSpawns.clear(); room.saveStationSpawns.addAll(saves)
        room.states.zip(states).forEach { (target, source) ->
            target.plmChanges.clear(); target.plmChanges.addAll(source.objects)
            target.scrollChanges.clear(); target.scrollChanges.addAll(source.scrolls)
            target.customScrollCommands.clear(); target.customScrollCommands.putAll(source.commands.mapValues { it.value.toMutableList() })
        }
    }
    companion object {
        fun capture(room: RoomEdits) = AsmRoomBank8FMetadata(room.asmPlmSourceVersion, room.asmInitialScrollSourceVersion, room.asmLayoutSourceVersion, room.asmRoomPropertiesSourceVersion, room.asmStateSelectorsSourceVersion,
            room.plmChanges.toList(), room.scrollChanges.toList(), room.customScrollCommands.mapValues { it.value.toList() },
            room.saveStationSpawns.toList(), room.states.map { State(it.id, it.sourceStateIndex, it.templateSourceStateIndex, it.condition,
                it.resources, it.plmChanges.toList(), it.scrollChanges.toList(), it.customScrollCommands.mapValues { (_, v) -> v.toList() }) })
    }
}

/** Disposable, validated prospective values. The project persists ownership, not these projections. */
internal data class AsmRoomGenerationPlan(
    val owner: SmEditProject,
    val room: RoomEdits,
    val header: RoomHeaderChange?,
    val sourceRevision: Long,
    val before: String,
    val after: String,
    val metadataBefore: AsmRoomBank8FMetadata,
    val metadataAfter: AsmRoomBank8FMetadata,
    val gridsBefore: Map<String, List<Int>>,
    val gridsAfter: Map<String, List<Int>>,
    val objectsAfter: Map<String, List<RomParser.PlmEntry>>,
)
