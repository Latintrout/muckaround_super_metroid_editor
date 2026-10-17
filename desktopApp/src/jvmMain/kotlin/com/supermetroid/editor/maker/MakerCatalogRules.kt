package com.supermetroid.editor.maker

/**
 * Parts kept out of the Classic library before any test, with the reason shown in the catalogue report.
 * These are bosses and their parts, scripted/cutscene actors, and engine helpers that are spawned by a parent
 * enemy or room code rather than placed on their own.
 */
object MakerCatalogRules {
    private val BOSSES = setOf(
        0xE2BF, 0xE2FF, 0xE33F, 0xE37F, 0xE3BF, 0xE3FF, 0xE43F, 0xE47F, // Kraid + parts
        0xE4BF, 0xE4FF, 0xE53F, 0xE57F,                                 // Phantoon + parts
        0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF,                                 // Draygon + parts
        0xE17F, 0xE13F,                                                 // Ridley, Ceres Ridley
        0xDDBF, 0xDDFF,                                                 // Crocomire
        0xF293, 0xDF3F,                                                 // Botwoon, Spore Spawn
        0xEC3F, 0xEC7F,                                                 // Mother Brain
        0xEEFF, 0xEF7F,                                                 // Bomb Torizo, Golden Torizo
    )
    private val SCRIPTED = mapOf(
        0xEEBF to "the baby Metroid cutscene actor",
        0xF07F to "Shaktool is scripted to its room",
        0xE27F to "Zebetites are part of Mother Brain's room",
        0xEFFF to "the Tourian statue is a cutscene object",
        0xD07F to "part of Samus' ship", 0xD0BF to "part of Samus' ship",
        0xD73F to "elevators need a matching elevator door setup",
        0xE23F to "a Ceres door effect", 0xE1FF to "a Ceres smoke effect",
        0xF0FF to "the Chozo statue enemy is scripted",
        0xF313 to "only appears in the escape sequence", 0xF2D3 to "only appears in the escape sequence",
        0xED3F to "the Torizo corpse is a scripted room effect",
        0xF0BF to "the Maridia glass tube effect",
        0xD4FF to "door shutters are tied to a specific door", 0xD53F to "door shutters are tied to a specific door",
        0xD5BF to "door shutters are tied to a specific door", 0xD5FF to "door shutters are tied to a specific door",
    )
    private val HELPERS = mapOf(
        0xE67F to "Evir's projectile, spawned by Evir",
        0xE03F to "Kzan's collision helper, spawned with Kzan",
        0xEBFF to "Kihunter wings, spawned with the Kihunter", 0xEAFF to "Kihunter wings, spawned with the Kihunter",
        0xEB7F to "Kihunter wings, spawned with the Kihunter",
    )

    fun excludedEnemyReason(speciesId: Int, name: String): String? = when {
        speciesId in BOSSES -> "boss or boss part (needs its own boss room setup)"
        speciesId in SCRIPTED -> SCRIPTED[speciesId]
        speciesId in HELPERS -> HELPERS[speciesId]
        // SMEDIT has no name for it: the name is just its hex id.
        name.equals("%04X".format(speciesId), ignoreCase = true) -> "unnamed engine object (no known role outside its vanilla room)"
        else -> null
    }
}
