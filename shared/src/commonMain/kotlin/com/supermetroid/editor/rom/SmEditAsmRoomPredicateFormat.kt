package com.supermetroid.editor.rom

/** Tagged adapter for trusted, authored bank-$8F code: 16-bit entry, carry result, RTS.
 * The adapter restores P/DB/D/X/Y before touching the engine's selector pointer.
 * The predicate must balance its own stack and must not change emulation mode.
 */
object SmEditAsmRoomPredicateFormat {
    const val ENTRY_SIZE_BYTES = 4 // adapter pointer + state pointer
    const val ADAPTER_SIZE_BYTES = 41
    private const val TARGET_OFFSET = 6
    private val template = byteArrayOf(
        0x08, 0x8B.toByte(), 0x0B, 0xDA.toByte(), 0x5A, // PHP PHB PHD PHX PHY
        0x20, 0, 0, // JSR predicate
        0xC2.toByte(), 0x30, 0x90.toByte(), 0x0C, // REP #$30; BCC false
        0x7A, 0xFA.toByte(), 0x2B, 0xAB.toByte(), 0x28, // PLY PLX PLD PLB PLP
        0xBD.toByte(), 0, 0, 0xAA.toByte(), 0x4C, 0xE6.toByte(), 0xE5.toByte(), // select [X]
        0x7A, 0xFA.toByte(), 0x2B, 0xAB.toByte(), 0x28,
        0xE8.toByte(), 0xE8.toByte(), 0x60, // skip state pointer; RTS to selector loop
        0x53, 0x4D, 0x45, 0x44, 0x49, 0x54, 0x41, 0x50, 0x01, // SMEDITAP v1
    )
    /** Native dispatcher and success-exit contract, $8F:E5D2–E5EA. */
    val engineBytes = byteArrayOf(0x8A.toByte(), 0x18, 0x69, 0x0B, 0, 0xAA.toByte(),
        0xBD.toByte(), 0, 0, 0x8D.toByte(), 0xB7.toByte(), 7, 0xE8.toByte(), 0xE8.toByte(),
        0xF4.toByte(), 0xD7.toByte(), 0xE5.toByte(), 0x6C, 0xB7.toByte(), 7, 0x8E.toByte(), 0xBB.toByte(), 7, 0x68, 0x6B)

    fun adapterBytes(snesAddress: Int): ByteArray {
        require(snesAddress in 0x8F8000..0x8FFFFF) { "Predicate target is outside bank \$8F." }
        return template.copyOf().also { it[TARGET_OFFSET] = snesAddress.toByte(); it[TARGET_OFFSET + 1] = (snesAddress ushr 8).toByte() }
    }

    /** Only this exact adapter ABI has a known argument width; never guess custom code. */
    fun targetAt(bytes: ByteArray, pc: Int): Int? {
        if (pc < 0 || pc + template.size > bytes.size) return null
        if (template.indices.any { it !in TARGET_OFFSET..TARGET_OFFSET + 1 && bytes[pc + it] != template[it] }) return null
        val target = (bytes[pc + TARGET_OFFSET].toInt() and 255) or ((bytes[pc + TARGET_OFFSET + 1].toInt() and 255) shl 8)
        return target.takeIf { it in 0x8000..0xFFFF }?.let { 0x8F0000 or it }
    }
}
