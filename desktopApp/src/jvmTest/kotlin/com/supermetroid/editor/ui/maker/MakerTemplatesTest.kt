package com.supermetroid.editor.ui.maker

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** New-room templates: always enclosed with a visible floor, somewhere to stand, repeatable for a seed. */
class MakerTemplatesTest {
    private val templates = MakerNewRoomStart.entries.filter { it.isTemplate }

    @Test
    fun `every template is enclosed with a 3-row floor and has room to stand`() {
        for (t in templates) for ((sw, sh) in listOf(t.size, 1 to 1, 2 to 2, 3 to 1, 1 to 3)) for (seed in 1..5) {
            val w = sw * 16; val h = sh * 16
            val s = makerTemplateSolid(t, w, h, seed)
            fun solid(x: Int, y: Int) = s[y * w + x]
            val label = "$t ${sw}x$sh seed $seed"
            assertTrue((0 until w).all { solid(it, 0) && solid(it, h - 1) && solid(it, h - 2) && solid(it, h - 3) }, "$label: ceiling and floor")
            assertTrue((0 until h).all { solid(0, it) && solid(w - 1, it) }, "$label: walls")
            // Somewhere Samus (3 blocks tall) can stand: solid under, 3 open above.
            val standable = (1 until w - 1).any { x -> (1 until h - 3).any { y -> solid(x, y + 1) && !solid(x, y) && !solid(x, y - 1) && (y < 2 || !solid(x, y - 2)) } }
            assertTrue(standable, "$label: somewhere to stand")
            val open = s.count { !it }
            assertTrue(open > w * h / 5, "$label: mostly open (open $open of ${w * h})")
        }
    }

    @Test
    fun `templates are repeatable for a seed and differ from empty`() {
        for (t in templates) {
            val (sw, sh) = t.size
            assertContentEquals(makerTemplateSolid(t, sw * 16, sh * 16, 3), makerTemplateSolid(t, sw * 16, sh * 16, 3), "$t repeatable")
            if (t != MakerNewRoomStart.EMPTY) {
                assertTrue(!makerTemplateSolid(t, sw * 16, sh * 16, 3).contentEquals(makerTemplateSolid(MakerNewRoomStart.EMPTY, sw * 16, sh * 16, 3)), "$t has a layout")
            }
        }
    }
}
