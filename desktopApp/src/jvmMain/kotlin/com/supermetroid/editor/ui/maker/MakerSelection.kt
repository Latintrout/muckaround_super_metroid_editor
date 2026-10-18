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

/**
 * A built-in part (two or more tiles) whose tiles are all in place around block ([bx], [by]): its footprint.
 * Matches each pattern at every offset that puts one of its tiles on this block (metatile and flips).
 */
fun EditorState.patternPieceAt(bx: Int, by: Int): MakerBox? {
    for (pattern in project.patterns.filter { it.builtIn && !isDoorPattern(it.id) }) {
        val cells = pattern.cells.withIndex().filter { it.value != null }
        if (cells.size < 2) continue
        for ((i, cell) in cells) {
            val ox = bx - i % pattern.cols; val oy = by - i / pattern.cols
            if (ox < 0 || oy < 0 || ox + pattern.cols > workingBlocksWide || oy + pattern.rows > workingBlocksTall) continue
            if (cell == null) continue
            val all = cells.all { (j, c) ->
                val word = readBlockWord(ox + j % pattern.cols, oy + j / pattern.cols)
                c != null && (word and 0x3FF) == c.metatile && ((word shr 10) and 1 == 1) == c.hFlip && ((word shr 11) and 1 == 1) == c.vFlip
            }
            if (all) return MakerBox(ox, oy, ox + pattern.cols - 1, oy + pattern.rows - 1)
        }
    }
    return null
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
    /** Items, door caps and station PLMs on the blocks, relative to the box. */
    val items: List<RomParser.PlmEntry>,
    val enemies: List<RomParser.EnemyEntry>,
)

private fun EditorState.itemsIn(box: MakerBox) = workingPlms.filter { isPartPlm(it.id) && (it.x to it.y) in box }
private fun EditorState.enemiesIn(box: MakerBox) = workingEnemies.filter { (it.x / 16 to it.y / 16) in box }

fun EditorState.copyBox(box: MakerBox): MakerClip = MakerClip(
    box.width, box.height,
    (box.y0..box.y1).flatMap { y -> (box.x0..box.x1).map { x -> readBlockWord(x, y) } },
    (box.y0..box.y1).flatMap { y -> (box.x0..box.x1).map { x -> readBts(x, y) } },
    itemsIn(box).map { it.copy(x = it.x - box.x0, y = it.y - box.y0) },
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
        for (p in clip.items) {
            val x = box.x0 + p.x; val y = box.y0 + p.y
            if (workingPlms.none { it.x == x && it.y == y && isPartPlm(it.id) }) {
                // Items get param 0 as the Maker places them (the exporter assigns each its own collection bit);
                // door caps and stations keep theirs.
                addPlm(p.id, x, y, if (isEditorItemPlm(p.id)) 0 else p.param, ItemStateScope.ALL_STATES)
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


/** PLMs that belong to placed parts and travel with them: items, door caps, and the PLMs of built-in patterns (stations…). */
fun EditorState.isPartPlm(id: Int): Boolean =
    isEditorItemPlm(id) || RomParser.isDoorCapPlm(id) || project.patterns.any { p -> p.builtIn && p.cells.any { it?.plmId == id } }

/** The door blocks (rows) beside or under a door cap at ([x], [y]): the doorway it closes, if any. */
private fun EditorState.doorwayForCap(x: Int, y: Int): MakerDoorway? =
    listOf(x, x - 1, x + 1).firstNotNullOfOrNull { cx -> makerDoorwayAt(cx, y)?.takeIf { it.top == y } }

/** Door caps whose doorway is gone (the door blocks were erased or moved away). */
fun EditorState.orphanDoorCaps(): List<RomParser.PlmEntry> =
    workingPlms.filter { RomParser.isDoorCapPlm(it.id) && doorwayForCap(it.x, it.y) == null }

/** Removes [orphanDoorCaps] as one undo step; returns how many. */
fun EditorState.removeOrphanDoorCaps(): Int {
    val orphans = orphanDoorCaps()
    if (orphans.isEmpty()) return 0
    asOneUndoStep { for (c in orphans) removePlm(c.x, c.y, c.id, ItemStateScope.ALL_STATES) }
    return orphans.size
}

/** An enemy's hitbox in blocks, from its species header (bank $A0: x radius at +8, y radius at +10). */
fun RomParser.enemyBlockBox(e: RomParser.EnemyEntry): MakerBox {
    val (rx, ry) = runCatching {
        val pc = snesToPc(0xA00000 or e.id)
        readUInt16At(pc + 8).coerceIn(4, 128) to readUInt16At(pc + 10).coerceIn(4, 128)
    }.getOrDefault(8 to 8)
    return MakerBox((e.x - rx) / 16, (e.y - ry) / 16, (e.x + rx - 1) / 16, (e.y + ry - 1) / 16)
}

/**
 * The whole placed object under block ([bx], [by]), for Select: a door (its blocks and cap), a part placed
 * from a built-in pattern (a station: found by its PLM and the pattern's footprint), or a door cap / PLM left
 * on its own. Null for plain blocks.
 */
fun EditorState.objectBoxAt(bx: Int, by: Int, parser: RomParser?): MakerBox? {
    fun clamp(b: MakerBox) = MakerBox(b.x0.coerceAtLeast(0), b.y0.coerceAtLeast(0), b.x1.coerceAtMost(workingBlocksWide - 1), b.y1.coerceAtMost(workingBlocksTall - 1))
    // A door: its blocks, plus its cap when the cap sits one column inside.
    val doorway = makerDoorwayAt(bx, by)
        ?: workingPlms.firstOrNull { RomParser.isDoorCapPlm(it.id) && it.x == bx && it.y == by }?.let { doorwayForCap(it.x, it.y) }
    if (doorway != null) {
        val cap = workingPlms.firstOrNull { RomParser.isDoorCapPlm(it.id) && it.y == doorway.top && kotlin.math.abs(it.x - doorway.x) <= 1 }
        val x0 = minOf(doorway.x, cap?.x ?: doorway.x); val x1 = maxOf(doorway.x, cap?.x ?: doorway.x)
        return clamp(MakerBox(x0, doorway.top, x1, doorway.top + doorway.height - 1))
    }
    // A part from a built-in pattern found by its tiles around this block (the Chozo statue, refills, stations…).
    patternPieceAt(bx, by)?.let { return clamp(it) }
    // A part from a built-in pattern: any of its PLMs whose footprint covers this block.
    for (plm in workingPlms) {
        if (isEditorItemPlm(plm.id) || RomParser.isDoorCapPlm(plm.id)) continue
        for (pattern in project.patterns.filter { it.builtIn }) {
            val i = pattern.cells.indexOfFirst { it?.plmId == plm.id }
            if (i < 0) continue
            val r = i / pattern.cols; val c = i % pattern.cols
            val box = clamp(MakerBox(plm.x - c, plm.y - r, plm.x - c + pattern.cols - 1, plm.y - r + pattern.rows - 1))
            if ((bx to by) in box) return box
        }
    }
    // A lone door cap or other part PLM.
    workingPlms.firstOrNull { it.x == bx && it.y == by && !isEditorItemPlm(it.id) && isPartPlm(it.id) }?.let { return MakerBox(bx, by, bx, by) }
    // Its name badge (drawn beside it on the room view) counts as the part too.
    partLabelAt(bx, by)?.takeIf { it.x != bx || it.y != by }?.let { return objectBoxAt(it.x, it.y, parser) }
    return null
}

/**
 * The name badge the room view draws for a door cap, station or gate (same font and placement as the
 * canvas: centred on the block, kept inside the room), in room pixels: x0, y0, x1, y1. Null when it has none.
 */
fun EditorState.partLabelRect(plm: RomParser.PlmEntry): IntArray? {
    val name = when {
        RomParser.isStationPlm(plm.id) -> RomParser.stationNameForPlm(plm.id)
        RomParser.doorCapColor(plm.id) != null -> RomParser.doorCapDisplayName(plm.id)
        RomParser.isGatePlm(plm.id) -> RomParser.gateNameForPlm(plm.id, plm.param)
        else -> null
    } ?: return null
    val fm = labelMetrics
    val badgeW = fm.stringWidth(name) + 6; val badgeH = fm.height + 2
    val roomW = workingBlocksWide * 16; val roomH = workingBlocksTall * 16
    val cx = plm.x * 16 + 8; val cy = plm.y * 16 + 8
    val x = (cx - badgeW / 2).coerceIn(0, maxOf(0, roomW - badgeW))
    val y = (cy - badgeH / 2).coerceIn(0, maxOf(0, roomH - badgeH))
    return intArrayOf(x, y, x + badgeW, y + badgeH)
}

private val labelMetrics: java.awt.FontMetrics by lazy {
    java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics().let { g ->
        g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.fontMetrics.also { g.dispose() }
    }
}

/** The door cap, station or gate whose name badge covers block ([bx], [by]), if any. */
fun EditorState.partLabelAt(bx: Int, by: Int): RomParser.PlmEntry? = workingPlms.firstOrNull { plm ->
    val r = partLabelRect(plm) ?: return@firstOrNull false
    bx * 16 + 16 > r[0] && bx * 16 < r[2] && by * 16 + 16 > r[1] && by * 16 < r[3]
}
