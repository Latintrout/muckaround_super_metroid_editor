package com.supermetroid.editor.ui.maker

import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState

/** A box of blocks in the room, inclusive. */
data class MakerBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0 + 1
    val height get() = y1 - y0 + 1
    operator fun contains(b: Pair<Int, Int>) = b.first in x0..x1 && b.second in y0..y1
    fun moved(dx: Int, dy: Int) = MakerBox(x0 + dx, y0 + dy, x1 + dx, y1 + dy)

    companion object {
        fun of(a: Pair<Int, Int>, b: Pair<Int, Int>) =
            MakerBox(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }
}

/** The editor's selected box, if any. */
fun EditorState.makerSelection(): MakerBox? {
    val s = mapSelStart ?: return null
    val e = mapSelEnd ?: return null
    return MakerBox.of(s, e)
}

fun EditorState.selectBox(box: MakerBox?) {
    mapSelStart = box?.let { it.x0 to it.y0 }
    mapSelEnd = box?.let { it.x1 to it.y1 }
}

/** What Copy holds: blocks (word, BTS) row-major, and the items and enemies on them, relative to the box. */
data class MakerClip(
    val width: Int, val height: Int,
    val words: List<Int>, val bts: List<Int>,
    /** (plmId, dx, dy) */
    val items: List<Triple<Int, Int, Int>>,
    val enemies: List<RomParser.EnemyEntry>,
)

private fun EditorState.itemsIn(box: MakerBox) = workingPlms.filter { isEditorItemPlm(it.id) && (it.x to it.y) in box }
private fun EditorState.enemiesIn(box: MakerBox) = workingEnemies.filter { (it.x / 16 to it.y / 16) in box }

fun EditorState.copyBox(box: MakerBox): MakerClip = MakerClip(
    box.width, box.height,
    (box.y0..box.y1).flatMap { y -> (box.x0..box.x1).map { x -> readBlockWord(x, y) } },
    (box.y0..box.y1).flatMap { y -> (box.x0..box.x1).map { x -> readBts(x, y) } },
    itemsIn(box).map { Triple(it.id, it.x - box.x0, it.y - box.y0) },
    enemiesIn(box).map { it.copy(x = it.x - box.x0 * 16, y = it.y - box.y0 * 16) },
)

/** Clamps a box of [w] × [h] with its top-left at ([x], [y]) into the room. */
private fun EditorState.clampedBox(x: Int, y: Int, w: Int, h: Int): MakerBox? {
    if (w > workingBlocksWide || h > workingBlocksTall) return null
    val cx = x.coerceIn(0, workingBlocksWide - w)
    val cy = y.coerceIn(0, workingBlocksTall - h)
    return MakerBox(cx, cy, cx + w - 1, cy + h - 1)
}

/** Pastes [clip] with its top-left near ([x], [y]) (kept inside the room) as one undo step; returns where it went. */
fun EditorState.pasteClip(clip: MakerClip, x: Int, y: Int, description: String = "Maker paste"): MakerBox? {
    val box = clampedBox(x, y, clip.width, clip.height) ?: return null
    asOneUndoStep {
        val edits = (0 until clip.height).flatMap { ry -> (0 until clip.width).map { rx -> rx to ry } }.mapNotNull { (rx, ry) ->
            val bx = box.x0 + rx; val by = box.y0 + ry
            val word = clip.words[ry * clip.width + rx]; val bts = clip.bts[ry * clip.width + rx]
            val oldWord = readBlockWord(bx, by); val oldBts = readBts(bx, by)
            if (oldWord == word && oldBts == bts) null else TileEdit(bx, by, oldWord, word, oldBts, bts)
        }
        applyBulkEdits(description, edits)
        for ((id, dx, dy) in clip.items) {
            if (workingPlms.none { it.x == box.x0 + dx && it.y == box.y0 + dy && isEditorItemPlm(it.id) }) {
                // Param 0 as the Maker places items; the exporter assigns each its own collection bit.
                addPlm(id, box.x0 + dx, box.y0 + dy, 0, ItemStateScope.ALL_STATES)
            }
        }
        for (e in clip.enemies) addEnemy(e.id, e.x + box.x0 * 16, e.y + box.y0 * 16, e.initParam, e.properties)
    }
    return box
}

/**
 * Moves the blocks in [box] by [dx], [dy] as one undo step, with the items, enemies and Start point on them.
 * The area left behind becomes air; the move is clamped to the room. Returns the new box.
 */
fun EditorState.moveBox(box: MakerBox, dx: Int, dy: Int): MakerBox {
    val mx = dx.coerceIn(-box.x0, workingBlocksWide - 1 - box.x1)
    val my = dy.coerceIn(-box.y0, workingBlocksTall - 1 - box.y1)
    if (mx == 0 && my == 0) return box
    val to = box.moved(mx, my)
    val clip = copyBox(box)
    val items = itemsIn(box)
    val enemies = enemiesIn(box)
    val start = makerStartPoint()?.takeIf { (it.x to it.y) in box }
    asOneUndoStep {
        val target = LinkedHashMap<Pair<Int, Int>, Pair<Int, Int>>()
        for (y in box.y0..box.y1) for (x in box.x0..box.x1) target[x to y] = RomConstants.AIR_TILE_WORD to 0
        for (ry in 0 until clip.height) for (rx in 0 until clip.width) {
            target[to.x0 + rx to to.y0 + ry] = clip.words[ry * clip.width + rx] to clip.bts[ry * clip.width + rx]
        }
        val edits = target.mapNotNull { (at, v) ->
            val oldWord = readBlockWord(at.first, at.second); val oldBts = readBts(at.first, at.second)
            if (oldWord == v.first && oldBts == v.second) null else TileEdit(at.first, at.second, oldWord, v.first, oldBts, v.second)
        }
        applyBulkEdits("Maker move blocks", edits)
        // Remove every item first, then add them back moved, so overlapping old/new spots can't clash.
        for (it in items) removePlm(it.x, it.y, it.id, ItemStateScope.ALL_STATES)
        for (it in items) addPlm(it.id, it.x + mx, it.y + my, it.param, ItemStateScope.ALL_STATES)
        for (e in enemies) updateEnemy(e, e.copy(x = e.x + mx * 16, y = e.y + my * 16))
        if (start != null) setMakerStartPoint(start.copy(x = start.x + mx, y = start.y + my), "Maker move start point")
    }
    return to
}

/** Clears [box] to air and removes the items, enemies and Start point on it, as one undo step. */
fun EditorState.clearBox(box: MakerBox) {
    val items = itemsIn(box)
    val enemies = enemiesIn(box)
    val start = makerStartPoint()?.takeIf { (it.x to it.y) in box }
    asOneUndoStep {
        val edits = (box.y0..box.y1).flatMap { y -> (box.x0..box.x1).map { x -> x to y } }.mapNotNull { (x, y) ->
            val oldWord = readBlockWord(x, y); val oldBts = readBts(x, y)
            if (oldWord == RomConstants.AIR_TILE_WORD && oldBts == 0) null else TileEdit(x, y, oldWord, RomConstants.AIR_TILE_WORD, oldBts, 0)
        }
        applyBulkEdits("Maker clear blocks", edits)
        for (it in items) removePlm(it.x, it.y, it.id, ItemStateScope.ALL_STATES)
        for (e in enemies) removeEnemy(e)
        if (start != null) setMakerStartPoint(null, "Maker remove start point")
    }
}

/** Where Duplicate puts a copy: just right of the box, else below it, else left or above. */
fun EditorState.duplicateSpot(box: MakerBox): Pair<Int, Int> = when {
    box.x1 + box.width < workingBlocksWide -> box.x1 + 1 to box.y0
    box.y1 + box.height < workingBlocksTall -> box.x0 to box.y1 + 1
    box.x0 - box.width >= 0 -> box.x0 - box.width to box.y0
    else -> box.x0 to (box.y0 - box.height).coerceAtLeast(0)
}

/** Cells a Fill click at ([x], [y]) would change: the 4-connected blocks with the same word (capped at [limit]). */
fun EditorState.fillRegion(x: Int, y: Int, limit: Int = 8192): Set<Pair<Int, Int>> {
    if (x !in 0 until workingBlocksWide || y !in 0 until workingBlocksTall) return emptySet()
    val word = readBlockWord(x, y)
    val seen = HashSet<Pair<Int, Int>>()
    val queue = ArrayDeque(listOf(x to y))
    while (queue.isNotEmpty() && seen.size < limit) {
        val c = queue.removeFirst()
        if (c in seen || c.first !in 0 until workingBlocksWide || c.second !in 0 until workingBlocksTall) continue
        if (readBlockWord(c.first, c.second) != word) continue
        seen += c
        queue += listOf(c.first + 1 to c.second, c.first - 1 to c.second, c.first to c.second + 1, c.first to c.second - 1)
    }
    return seen
}

/** A straight line from [from] towards [to]: along whichever axis the pointer moved further. */
fun straightLine(from: Pair<Int, Int>, to: Pair<Int, Int>): MakerBox =
    if (kotlin.math.abs(to.first - from.first) >= kotlin.math.abs(to.second - from.second)) MakerBox.of(from, to.first to from.second)
    else MakerBox.of(from, from.first to to.second)

