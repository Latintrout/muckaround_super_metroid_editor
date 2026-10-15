package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject

/** Session-only snapshots. Authored FX values are never copied into project JSON. */
internal data class AsmRoomThemePlan(
    val owner: SmEditProject,
    val room: RoomEdits,
    val beforeModel: RoomEdits,
    val afterModel: RoomEdits,
    val sourceRevision: Long,
    val replacements: Map<String, Pair<String, String>>,
    val externalReferences: Set<String>,
)
