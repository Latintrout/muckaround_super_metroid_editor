package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RomEditorUtilsTest {
    @Test
    fun `ASM parity ignores only derived internal checksum bytes`() {
        val original = ByteArray(0x10000)
        val checksumOnly = original.copyOf().also {
            it[0x7FDC] = 1
            it[0x7FDD] = 2
            it[0x7FDE] = 3
            it[0x7FDF] = 4
        }
        val semanticChange = checksumOnly.copyOf().also { it[0x8000] = 1 }

        assertEquals(asmGeneratedParitySha256(original), asmGeneratedParitySha256(checksumOnly))
        assertNotEquals(asmGeneratedParitySha256(original), asmGeneratedParitySha256(semanticChange))
    }

    @Test
    fun `ASM parity accounts for copier header when locating checksum`() {
        val original = ByteArray(0x10200)
        val checksumOnly = original.copyOf().also { it[0x200 + 0x7FDC] = 1 }
        val headerChange = checksumOnly.copyOf().also { it[0x10] = 1 }

        assertEquals(asmGeneratedParitySha256(original), asmGeneratedParitySha256(checksumOnly))
        assertNotEquals(asmGeneratedParitySha256(original), asmGeneratedParitySha256(headerChange))
    }
}
