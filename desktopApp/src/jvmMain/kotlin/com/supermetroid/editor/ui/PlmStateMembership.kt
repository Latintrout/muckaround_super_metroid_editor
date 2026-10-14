package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ItemStateScope

/** Observed membership, not the scope chosen for the next placement. */
data class PlmStateMembership(val matchingStates: Int, val totalStates: Int) {
    val allStates: Boolean get() = totalStates > 0 && matchingStates == totalStates
    val editScope: ItemStateScope get() = if (allStates) ItemStateScope.ALL_STATES else ItemStateScope.THIS_STATE
    val label: String get() = when {
        totalStates == 0 -> "State data unavailable"
        allStates -> "All states"
        matchingStates == 1 -> "This state"
        else -> "$matchingStates of $totalStates states"
    }
}
