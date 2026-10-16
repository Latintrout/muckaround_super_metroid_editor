package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DoorBitflagTest {

    @Test
    fun `travel closure and elevator edits preserve every unrelated bit including escape gates`() {
        for (direction in 0..11) for (lowFlags in listOf(0, 0x40, 0x80, 0xFF)) {
            val original = (direction shl 8) or lowFlags
            assertEquals(original, editedDoorBitflag(original))
            for (travel in 0..3) assertEquals(((direction and 0xFC or travel) shl 8) or lowFlags,
                editedDoorBitflag(original, travel = travel))
            for (closure in 0..2) assertEquals(((closure * 4 + (direction and 3)) shl 8) or lowFlags,
                editedDoorBitflag(original, closure = closure))
            assertEquals(original or 0x80, editedDoorBitflag(original, elevator = true))
            assertEquals(original and 0xFF7F, editedDoorBitflag(original, elevator = false))
        }
    }

    @Test
    fun `matched vanilla orientation keeps closing cap while adding cross area`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0000,
            matchedOrientation = 0x04,
            crossArea = true,
        )

        assertEquals(0x0440, bitflag)
    }

    @Test
    fun `matched vanilla orientation preserves unrelated low flags`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0080,
            matchedOrientation = 0x05,
            crossArea = false,
        )

        assertEquals(0x0580, bitflag)
    }

    @Test
    fun `missing match preserves current orientation`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0540,
            matchedOrientation = null,
        )

        assertEquals(0x0540, bitflag)
    }
}
