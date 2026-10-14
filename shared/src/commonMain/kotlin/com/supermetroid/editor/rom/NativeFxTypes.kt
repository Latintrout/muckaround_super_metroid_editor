package com.supermetroid.editor.rom

/** Verified against FXType_Function_Pointers at $83:AC18–AC45, not legacy SMILE labels. */
object NativeFxTypes {
    private val handlers = mapOf(
        0x00 to "None", 0x02 to "Lava", 0x04 to "Acid", 0x06 to "Water",
        0x08 to "Spores", 0x0A to "Rain", 0x0C to "Fog",
        0x20 to "Scrolling Sky", 0x22 to "Scrolling Sky (unused)",
        0x24 to "Fireflea", 0x26 to "Tourian Statues",
        0x28 to "Ceres Ridley", 0x2A to "Ceres Elevator", 0x2C to "Ceres Haze",
    )
    val names: Map<Int, String> = (0..0x2C step 2).associateWith { handlers[it] ?: "Unused 0x${it.toString(16).uppercase().padStart(2, '0')}" }
    fun commentName(type: Int): String = handlers[type] ?: if (type in names) "Unused (no native FX handler)" else "Unknown"
}
