package com.supermetroid.editor.rom

import com.supermetroid.editor.data.*

/** Source-owned branch order and typed predicates; stable identities still own the resources. */
data class SourceRoomSelectors(val order: List<String>, val conditions: Map<String, ProjectRoomStateCondition>) {
    init {
        require(order.size in 1..64 && order.distinct().size == order.size && order.toSet() == conditions.keys) { "Selector identities must cover every state exactly once." }
        require(conditions.filterValues { it.kind == ProjectRoomStateConditionKind.DEFAULT }.keys == setOf(order.last())) { "Exactly one mandatory default selector must be last." }
        var nodes = 0
        fun validate(c: ProjectRoomStateCondition, depth: Int) {
            require(depth <= 8 && ++nodes <= 64) { "Condition tree exceeds 8 levels or 64 nodes." }
            require(c == projectRoomStateCondition(c.kind, c.argument, c.negated, c.children)) { "Inconsistent typed selector." }
            val argument = c.argument
            val valid = when (c.argumentKind) {
                ProjectRoomStateConditionArgumentKind.NONE -> argument == null && c.children.isEmpty() && (c.kind != ProjectRoomStateConditionKind.DEFAULT || !c.negated && depth == 0)
                ProjectRoomStateConditionArgumentKind.CHILDREN -> argument == null && c.children.size >= 2 && c.children.none { it.kind == ProjectRoomStateConditionKind.DEFAULT }
                ProjectRoomStateConditionArgumentKind.EVENT_ID, ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> argument in 0..255
                ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> argument in 0x8000..0xFFFF
                ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK, ProjectRoomStateConditionArgumentKind.BEAM_MASK -> argument != null && argument in 1..65535 && argument.countOneBits() == 1
                ProjectRoomStateConditionArgumentKind.CAPACITY -> argument in 0..65535
                ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX, ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX, ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX -> argument in 0..511
                ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK -> argument != null && argument in 0..65535 && argument ushr 8 in 0..7 && argument and 255 in RoomStateCondition.BOSS_FLAG_MASKS
            }
            require(valid && (c.argumentKind == ProjectRoomStateConditionArgumentKind.CHILDREN || c.children.isEmpty())) { "Invalid ${c.kind} selector argument/tree." }
            c.children.forEach { validate(it, depth + 1) }
        }
        conditions.values.forEach { nodes = 0; validate(it, 0) }
    }
}
