package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.rom.BossPoseScanner
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import com.supermetroid.editor.rom.ShipSpritemap
import com.supermetroid.editor.rom.PirateSpritemap
import com.supermetroid.editor.rom.RoomBossPreview
import com.supermetroid.editor.rom.CrawlerSpritemap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Assembles and caches enemy sprites from ROM data (OAM spritemaps + tile data + palette).
 * Falls back to bundled PNG resources if ROM assembly fails.
 *
 * Call [setRomParser] when a ROM is loaded to enable native rendering.
 * The cache is keyed by species ID hex string (e.g. "D47F").
 */
internal data class AnchoredEnemySprite(val image: BufferedImage, val originX: Int, val originY: Int)

internal object EnemySpriteCache {
    /** Cache key: "speciesHex" for default, "speciesHex:initParam" for directional variants. */
    private val cache = mutableMapOf<String, AnchoredEnemySprite?>()
    private var romParser: RomParser? = null

    /** Set (or clear) the ROM parser. Clears the cache so sprites are re-assembled. */
    fun setRomParser(rp: RomParser?) {
        if (rp !== romParser) {
            romParser = rp
            cache.clear()
        }
    }

    /** Get the default sprite for a species (no direction). */
    fun get(hexId: String): BufferedImage? = preview(hexId, 0)?.image

    /**
     * Get a direction-aware sprite for a species + initParam.
     * If initParam selects a valid direction from the species' direction table,
     * a different spritemap is rendered (e.g. left-facing vs right-facing).
     * Falls back to the default sprite if no direction table exists.
     */
    fun get(hexId: String, initParam: Int): BufferedImage? = preview(hexId, initParam)?.image

    private fun preview(hexId: String, initParam: Int): AnchoredEnemySprite? {
        val key = if (initParam == 0) hexId else "$hexId:$initParam"
        return cache.getOrPut(key) {
            val id = hexId.toIntOrNull(16) ?: return@getOrPut null
            assembleFromRom(id, initParam)
                ?: if (id in RoomBossPreview.SPECIES) null else if (initParam == 0) loadPngFallback(hexId) else preview(hexId, 0)
        }
    }

    fun getPreview(enemy: RomParser.EnemyEntry): AnchoredEnemySprite? {
        val hexId = enemy.id.toString(16).uppercase().padStart(4, '0')
        if (enemy.id == ShipSpritemap.TOP_SPECIES || enemy.id == ShipSpritemap.BOTTOM_SPECIES) {
            val pad = enemy.id == ShipSpritemap.BOTTOM_SPECIES && enemy.extra3 != 0
            return cache.getOrPut("ship:$hexId:$pad") {
                romParser?.let { ShipSpritemap(it).renderPart(enemy.id, entrancePad = pad) }
                    ?.let(::assembledSpriteToImage)
            }
        }
        if (PirateSpritemap.supports(enemy.id)) {
            return cache.getOrPut("pirate:$hexId:${enemy.extra2 and 1}") {
                romParser?.let { PirateSpritemap(it).renderInitial(enemy.id, enemy.extra2) }
                    ?.let(::assembledSpriteToImage)
            }
        }
        if (enemy.id in CrawlerSpritemap.SPECIES) {
            return cache.getOrPut("crawler:$hexId:${enemy.initParam and 3}") {
                romParser?.let { CrawlerSpritemap(it).renderInitial(enemy.id, enemy.initParam) }?.let(::assembledSpriteToImage)
            }
        }
        return preview(hexId, enemy.initParam)
    }

    private fun assembleFromRom(speciesId: Int, initParam: Int): AnchoredEnemySprite? {
        val rp = romParser ?: return null
        if (speciesId in RoomBossPreview.SPECIES) return RoomBossPreview(rp).render(speciesId)?.let(::assembledSpriteToImage)

        // Load tile data and palette from ROM
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)
        val previewTileSource = EnemySpriteGraphics.loadEnemyPreviewTileSource(rp, speciesId, tileData)
        val td = previewTileSource?.bytes ?: return null
        val paletteSource = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(rp, speciesId)
        val palette = paletteSource?.colors ?: return null

        // Boss special rendering (Torizos)
        if (BossPoseScanner.usesSpecialAssembledPreview(speciesId)) {
            val renderTd = EnemySpriteGraphics.loadEnemyRenderTileData(rp, speciesId, td) ?: td
            val scanner = BossPoseScanner(rp)
            val pose = scanner.scanPoses(speciesId, minEntries = 3).firstOrNull() ?: return null
            val assembled = scanner.renderPose(pose, renderTd, palette) ?: return null
            return assembledSpriteToImage(assembled)
        }

        // Standard OAM spritemap rendering
        val smap = EnemySpritemap(rp)
        smap.renderSpecialEnemyPreview(speciesId, td, palette)?.let {
            return assembledSpriteToImage(it)
        }

        // Try initParam-specific direction if non-zero
        val renderTd = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, speciesId, td) ?: td
        if (initParam != 0) {
            val dirSmap = smap.findSpritemapForInitParam(speciesId, initParam)
            if (dirSmap != null) {
                val assembled = smap.renderSpritemap(dirSmap, renderTd, palette)
                if (assembled != null && isValidSprite(assembled)) {
                    return assembledSpriteToImage(assembled)
                }
            }
        }

        val defaultSmap = smap.findDefaultSpritemap(speciesId)
            ?: smap.findAnimationFrames(speciesId, maxFrames = 1).firstOrNull()?.spritemap
            ?: return null
        val assembled = smap.renderSpritemap(defaultSmap, renderTd, palette) ?: return null
        if (!isValidSprite(assembled)) return null

        return assembledSpriteToImage(assembled)
    }

    private fun isValidSprite(sprite: EnemySpritemap.AssembledSprite): Boolean {
        val totalPixels = sprite.width * sprite.height
        if (totalPixels <= 64) return true
        val nonTransparent = sprite.pixels.count { (it ushr 24) > 0 }
        return nonTransparent >= totalPixels * 5 / 100
    }

    private fun assembledSpriteToImage(sprite: EnemySpritemap.AssembledSprite): AnchoredEnemySprite {
        val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
        return AnchoredEnemySprite(img, sprite.originX, sprite.originY)
    }

    private fun loadPngFallback(hexId: String): AnchoredEnemySprite? {
        val stream = EnemySpriteCache::class.java.getResourceAsStream("/enemies/$hexId.png")
        return stream?.use { ImageIO.read(it) }?.let { AnchoredEnemySprite(it, it.width / 2, it.height / 2) }
    }
}

internal object ItemSpriteSheetCache {
    private var sheet: BufferedImage? = null
    private var loaded = false

    fun get(): BufferedImage? {
        if (!loaded) {
            loaded = true
            sheet = ItemSpriteSheetCache::class.java.getResourceAsStream("/item_sprites.png")
                ?.use { ImageIO.read(it) }
        }
        return sheet
    }
}

internal data class RoomItemSpriteCoord(val x: Int, val y: Int)

internal data class ItemOverlayDef(
    val name: String,
    val shortLabel: String,
    val sprite: RoomItemSpriteCoord?,
)

internal val ROOM_ITEM_SPRITE_COORDS = mapOf(
    "Morph Ball" to RoomItemSpriteCoord(0, 0),
    "Bomb" to RoomItemSpriteCoord(32, 0),
    "Energy Tank" to RoomItemSpriteCoord(64, 0),
    "Missile" to RoomItemSpriteCoord(0, 16),
    "Super Missile" to RoomItemSpriteCoord(32, 16),
    "Power Bomb" to RoomItemSpriteCoord(64, 16),
    "Reserve Tank" to RoomItemSpriteCoord(96, 16),
    "Hi-Jump Boots" to RoomItemSpriteCoord(0, 32),
    "Speed Booster" to RoomItemSpriteCoord(32, 32),
    "Grapple Beam" to RoomItemSpriteCoord(64, 32),
    "X-Ray Scope" to RoomItemSpriteCoord(96, 32),
    "Spring Ball" to RoomItemSpriteCoord(0, 48),
    "Space Jump" to RoomItemSpriteCoord(32, 48),
    "Screw Attack" to RoomItemSpriteCoord(64, 48),
    "Charge Beam" to RoomItemSpriteCoord(96, 48),
    "Spazer" to RoomItemSpriteCoord(0, 64),
    "Wave Beam" to RoomItemSpriteCoord(32, 64),
    "Ice Beam" to RoomItemSpriteCoord(64, 64),
    "Plasma Beam" to RoomItemSpriteCoord(96, 64),
    "Varia Suit" to RoomItemSpriteCoord(0, 80),
    "Gravity Suit" to RoomItemSpriteCoord(32, 80),
)

internal fun buildItemOverlayDefs(customItems: List<CustomItemDef>): Map<Int, ItemOverlayDef> = buildMap {
    for (item in RomParser.ITEM_DEFS) {
        val def = ItemOverlayDef(
            name = item.name,
            shortLabel = item.shortLabel,
            sprite = ROOM_ITEM_SPRITE_COORDS[item.name],
        )
        put(item.chozoId, def)
        put(item.visibleId, def)
        put(item.hiddenId, def)
    }
    for (item in customItems) {
        val def = ItemOverlayDef(
            name = item.name,
            shortLabel = item.shortLabel,
            sprite = RoomItemSpriteCoord(item.iconX, item.iconY),
        )
        item.chozoPlmId?.let { put(it, def) }
        item.visiblePlmId?.let { put(it, def) }
        item.hiddenPlmId?.let { put(it, def) }
    }
}

/** Keep the PLM badge visually aligned with the cap color rendered in the room. */
internal fun doorCapOverlayBorderColor(plmId: Int): java.awt.Color? =
    RomParser.doorCapColor(plmId)?.let { java.awt.Color(it, true) }

internal const val SCREEN_PX = 16 * 16  // 256 — one screen in pixels

internal fun hiddenRoomEnemyCompanions(enemies: List<RomParser.EnemyEntry>): Set<Int> =
    enemies.flatMap { RoomBossPreview.COMPANIONS[it.id].orEmpty() }.toSet()

/** Only the preview moves; stored population coordinates and export data are untouched. */
internal fun roomEnemyDisplayPosition(enemy: RomParser.EnemyEntry, width: Int, height: Int, preview: AnchoredEnemySprite?): Pair<Int, Int> {
    if (enemy.id !in RoomBossPreview.SPECIES || preview == null) return enemy.x to enemy.y
    val image = preview.image
    val left = enemy.x - preview.originX
    val top = enemy.y - preview.originY
    val outside = enemy.x <= 0 || enemy.y <= 0 || enemy.x >= width || enemy.y >= height ||
        left + image.width <= 0 || top + image.height <= 0 || left >= width || top >= height
    return if (outside) (width / 2 + preview.originX - image.width / 2) to (height / 2 + preview.originY - image.height / 2)
        else enemy.x to enemy.y
}

/**
 * Determine whether an enemy sprite should be horizontally flipped based on its
 * population data fields. Returns true if the sprite should be drawn mirrored.
 *
 * Most enemies determine facing at runtime (AI reacts to Samus position, walls, etc.)
 * so we can only resolve facing for species whose population data encodes it:
 *
 * - **Namihe / Fune**: `extra2` bit 4 (0x0010) = wall side. Set = left wall (flip sprite).
 * - **Ripper variants**: `initParam` non-zero = moving left (flip sprite).
 *
 * This function is intentionally conservative — returning false for unknown species
 * means we show the default ROM spritemap direction.
 */
internal fun shouldFlipEnemySprite(enemy: RomParser.EnemyEntry): Boolean {
    // Their source-selected initial poses already contain the correct OAM facing.
    if (PirateSpritemap.supports(enemy.id)) return false
    return when (enemy.id) {
        // Namihe / Fune: extra2 bit 4 indicates left-wall mounting
        0xE73F, 0xE6FF -> (enemy.extra2 and 0x0010) != 0
        // Ripper / Ripper 2 / Ripper (gold): initParam non-zero = leftward
        0xD3FF, 0xD43F, 0xD47F -> enemy.initParam != 0
        // Geruta: initParam non-zero = facing left
        0xD2FF -> enemy.initParam != 0
        // Waver: initParam non-zero = facing left
        0xD63F -> enemy.initParam != 0
        // Holtz: initParam non-zero = facing left
        0xD33F -> enemy.initParam != 0
        else -> false
    }
}

/**
 * Describes how facing direction is encoded for a specific enemy species,
 * allowing the UI to display a simple Left/Right toggle and read/write
 * the correct underlying field and bit.
 */
internal data class EnemyFacingInfo(
    val fieldName: String,
    /** Read the current facing from the hex field values. True = facing right. */
    val isFacingRight: (initParamHex: String, extra2Hex: String) -> Boolean,
    /** Apply a facing and return the updated (initParamHex, extra2Hex). */
    val applyFacing: (initParamHex: String, extra2Hex: String, facingRight: Boolean) -> Pair<String, String>,
)

/**
 * Return facing-field info for species that encode direction in population data,
 * or null if the species determines facing at runtime (no user control).
 */
internal fun enemyFacingFieldInfo(speciesId: Int): EnemyFacingInfo? {
    return when (speciesId) {
        // Namihe / Fune: extra2 bit 4 (0x0010). Clear = right wall, Set = left wall.
        0xE73F, 0xE6FF -> EnemyFacingInfo(
            fieldName = "Speed (extra2) bit 4",
            isFacingRight = { _, extra2Hex ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                (v and 0x0010) == 0
            },
            applyFacing = { initP, extra2Hex, facingRight ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                val updated = if (facingRight) v and 0x0010.inv() else v or 0x0010
                initP to updated.toString(16).uppercase().padStart(4, '0')
            },
        )
        // Wall pirates: clear = left wall, facing right; set = right wall, facing left.
        // $B2:EF9F chooses MovingDownLeftWall / MovingDownRightWall, not left/right-facing.
        0xF353, 0xF393, 0xF3D3, 0xF413, 0xF453, 0xF493 -> EnemyFacingInfo(
            fieldName = "Speed (extra2) bit 0",
            isFacingRight = { _, extra2Hex ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                (v and 0x0001) == 0
            },
            applyFacing = { initP, extra2Hex, facingRight ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                val updated = if (facingRight) v and 0x0001.inv() else v or 0x0001
                initP to updated.toString(16).uppercase().padStart(4, '0')
            },
        )
        // Ninja and walking pirates: clear = facing left; set = facing right.
        0xF4D3, 0xF513, 0xF553, 0xF593, 0xF5D3, 0xF613,
        0xF653, 0xF693, 0xF6D3, 0xF713,
        0xF753, 0xF793,
        -> EnemyFacingInfo(
            fieldName = "Speed (extra2) bit 0",
            isFacingRight = { _, extra2Hex ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                (v and 0x0001) != 0
            },
            applyFacing = { initP, extra2Hex, facingRight ->
                val v = extra2Hex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                val updated = if (facingRight) v or 0x0001 else v and 0x0001.inv()
                initP to updated.toString(16).uppercase().padStart(4, '0')
            },
        )
        // Ripper / Ripper 2 / Ripper (gold), Geruta, Waver, Holtz:
        // initParam 0 = right, non-zero = left
        0xD3FF, 0xD43F, 0xD47F, 0xD2FF, 0xD63F, 0xD33F -> EnemyFacingInfo(
            fieldName = "Tilemaps (initParam)",
            isFacingRight = { initParamHex, _ ->
                val v = initParamHex.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                v == 0
            },
            applyFacing = { _, extra2, facingRight ->
                val ip = if (facingRight) "0000" else "0001"
                ip to extra2
            },
        )
        else -> null
    }
}


internal fun drawSpeedBoosterOverlay(g2: java.awt.Graphics2D, x: Int, y: Int, size: Int, color: java.awt.Color) {
    // Background
    g2.color = java.awt.Color(0, 0, 0, 200)
    g2.fillRect(x, y, size, size)

    // Arrow: right-pointing chevron like the speed booster icon
    val m = size / 8f  // unit
    val cx = x + size / 2f
    val cy = y + size / 2f

    // Double chevron arrow (two >>)
    val arrowXs1 = floatArrayOf(cx - 2 * m, cx, cx - 2 * m)
    val arrowYs1 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)
    val arrowXs2 = floatArrayOf(cx + 0.5f * m, cx + 2.5f * m, cx + 0.5f * m)
    val arrowYs2 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)

    g2.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2.color = color
    g2.drawPolyline(arrowXs1.map { it.toInt() }.toIntArray(), arrowYs1.map { it.toInt() }.toIntArray(), 3)
    g2.drawPolyline(arrowXs2.map { it.toInt() }.toIntArray(), arrowYs2.map { it.toInt() }.toIntArray(), 3)

    // Colored border
    g2.stroke = java.awt.BasicStroke(1f)
    g2.drawRect(x, y, size - 1, size - 1)
}

/**
 * Draw the actual collision profile for a slope tile, matching SMILE's overlay style.
 *
 * Uses the ROM height table to draw the exact solid area polygon per tile.
 * BTS bit 6 (0x40) is the collision engine's X-flip flag ($94:87C0).
 * BTS bit 7 (0x80) selects ceiling vs floor.
 * Shapes with all-zero heights (passthrough/air slopes) draw an orange square.
 */
internal fun drawSlopeOverlay(g2: java.awt.Graphics2D, px: Int, py: Int, bts: Int, color: java.awt.Color) {
    val s = 16
    val shape = bts and 0x1F
    val isCeiling = (bts and 0x80) != 0
    val xFlip = (bts and 0x40) != 0

    val heights = SLOPE_HEIGHTS[shape]
    if (heights.all { it == 0 }) {
        val bg = java.awt.Color(color.red, color.green, color.blue, 80)
        val border = java.awt.Color(color.red, color.green, color.blue, 200)
        g2.color = bg
        g2.fillRect(px, py, s, s)
        g2.color = border
        g2.stroke = java.awt.BasicStroke(1.5f)
        g2.drawRect(px, py, s, s)
        g2.stroke = java.awt.BasicStroke(1f)
        return
    }

    val bg = java.awt.Color(color.red, color.green, color.blue, 80)
    val border = java.awt.Color(color.red, color.green, color.blue, 200)

    val xPts = mutableListOf<Int>()
    val yPts = mutableListOf<Int>()

    // Build the height profile for each pixel column
    val profile = IntArray(s) { screenX ->
        val col = if (xFlip) screenX else (s - 1 - screenX)
        heights[col].coerceIn(0, s)
    }

    if (!isCeiling) {
        // Find first and last column with height > 0 to avoid baseline overshoot
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py + s)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + s - profile[screenX])
        }
        xPts.add(px + last); yPts.add(py + s)
    } else {
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + profile[screenX])
        }
        xPts.add(px + last); yPts.add(py)
    }

    g2.color = bg
    g2.fillPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.color = border
    g2.stroke = java.awt.BasicStroke(1.5f)
    g2.drawPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.stroke = java.awt.BasicStroke(1f)
}

internal fun isFlatSurfaceBlock(blockType: Int, bts: Int): Boolean {
    return blockType == 0x8 || (blockType == 0xE && bts == 0x00)
}

internal fun drawFlatSurfaceOverlay(g2: java.awt.Graphics2D, data: RoomRenderData, color: java.awt.Color) {
    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall
    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) return

    fun hasFlatSurfaceAt(x: Int, y: Int): Boolean {
        if (x !in 0 until blocksWide || y !in 0 until blocksTall) return false
        val idx = y * blocksWide + x
        val bts = if (idx < data.btsData.size) data.btsData[idx].toInt() and 0xFF else 0
        return idx < data.blockTypes.size && isFlatSurfaceBlock(data.blockTypes[idx], bts)
    }

    val lineColor = java.awt.Color(color.red, color.green, color.blue, 220)
    val oldStroke = g2.stroke
    g2.color = lineColor
    g2.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_SQUARE, java.awt.BasicStroke.JOIN_MITER)

    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            if (!hasFlatSurfaceAt(bx, by)) continue

            val px = bx * 16
            val py = by * 16
            val right = px + 16
            val bottom = py + 16

            if (by > 0 && !hasFlatSurfaceAt(bx, by - 1)) g2.drawLine(px, py, right, py)
            if (by < blocksTall - 1 && !hasFlatSurfaceAt(bx, by + 1)) g2.drawLine(px, bottom, right, bottom)
            if (bx > 0 && !hasFlatSurfaceAt(bx - 1, by)) g2.drawLine(px, py, px, bottom)
            if (bx < blocksWide - 1 && !hasFlatSurfaceAt(bx + 1, by)) g2.drawLine(right, py, right, bottom)
        }
    }

    g2.stroke = oldStroke
}

internal fun buildCompositeImage(
    data: RoomRenderData,
    activeOverlays: Set<TileOverlay>,
    showGrid: Boolean,
    scrollData: IntArray? = null,
    roomWidthScreens: Int = 0,
    roomHeightScreens: Int = 0,
    layer3Pixels: IntArray? = null,
    layer3Width: Int = 256,
    layer3Height: Int = 264,
    layer2Pixels: IntArray? = null,
    customItems: List<CustomItemDef> = emptyList(),
    showItemNames: Boolean = true,
    showMetaNames: Boolean = true,
    highlightItems: Boolean = true,
    showEnemyNames: Boolean = true,
    showFlatSlopeSurfaces: Boolean = true,
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)

    // Layer 2 background: draw behind Layer 1 (composite L2 then L1 on top)
    val pixels = if (activeOverlays.contains(TileOverlay.LAYER2) && layer2Pixels != null && layer2Pixels.size == data.pixels.size) {
        // Compose: L2 as base, then L1 pixels on top (L1 pixel 0 = transparent → show L2)
        val l1 = if (activeOverlays.contains(TileOverlay.LIGHTEN)) {
            IntArray(data.pixels.size) { i ->
                val argb = data.pixels[i]
                val a = argb ushr 24
                val r = minOf(((argb shr 16) and 0xFF) * 3, 255)
                val g = minOf(((argb shr 8) and 0xFF) * 3, 255)
                val b = minOf((argb and 0xFF) * 3, 255)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else data.pixels
        IntArray(data.pixels.size) { i ->
            val l1px = l1[i]
            val l2px = layer2Pixels[i]
            // Show L2 where L1 is bgColor AND L2 has visible content (non-transparent)
            if (l1px == RomConstants.ROM_BG_COLOR && l2px != 0) l2px
            else l1px
        }
    } else if (activeOverlays.contains(TileOverlay.LIGHTEN)) {
        IntArray(data.pixels.size) { i ->
            val argb = data.pixels[i]
            val a = argb ushr 24
            val r = minOf(((argb shr 16) and 0xFF) * 3, 255)
            val g = minOf(((argb shr 8) and 0xFF) * 3, 255)
            val b = minOf((argb and 0xFF) * 3, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    } else {
        data.pixels
    }
    img.setRGB(0, 0, data.width, data.height, pixels, 0, data.width)

    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

    // Layer 3 overlay: tile the L3 image across the room
    if (activeOverlays.contains(TileOverlay.LAYER3) && layer3Pixels != null && layer3Width > 0 && layer3Height > 0) {
        val l3Img = BufferedImage(layer3Width, layer3Height, BufferedImage.TYPE_INT_ARGB)
        l3Img.setRGB(0, 0, layer3Width, layer3Height, layer3Pixels, 0, layer3Width)
        val oldComposite = g.composite
        g.composite = java.awt.AlphaComposite.SrcOver
        for (ty in 0 until data.height step layer3Height) {
            for (tx in 0 until data.width step layer3Width) {
                g.drawImage(l3Img, tx, ty, null)
            }
        }
        g.composite = oldComposite
    }

    // Draw per-screen scroll color overlay
    if (activeOverlays.contains(TileOverlay.SCROLLS) && scrollData != null && roomWidthScreens > 0) {
        val scrollColors = arrayOf(
            java.awt.Color(200, 40, 40, 40),   // Red (hidden)
            java.awt.Color(40, 80, 200, 40),    // Blue (explorable)
            java.awt.Color(40, 160, 50, 40),    // Green (open with lower vertical clamp)
        )
        val scrollBorderColors = arrayOf(
            java.awt.Color(200, 40, 40, 120),
            java.awt.Color(40, 80, 200, 120),
            java.awt.Color(40, 160, 50, 120),
        )
        val g2 = g as java.awt.Graphics2D
        for (sy in 0 until roomHeightScreens) {
            for (sx in 0 until roomWidthScreens) {
                val idx = sy * roomWidthScreens + sx
                val scrollVal = scrollData.getOrElse(idx) { 0x01 }.coerceIn(0, 2)
                val px = sx * SCREEN_PX
                val py = sy * SCREEN_PX
                g2.color = scrollColors[scrollVal]
                g2.fillRect(px, py, SCREEN_PX, SCREEN_PX)
                g2.color = scrollBorderColors[scrollVal]
                g2.stroke = java.awt.BasicStroke(2f)
                g2.drawRect(px + 1, py + 1, SCREEN_PX - 3, SCREEN_PX - 3)
                g2.stroke = java.awt.BasicStroke(1f)
            }
        }
    }

    // Draw room screen boundaries without coupling them to coordinate labels.
    if (showGrid) {
        g.color = java.awt.Color(255, 255, 255, 0x30)
        var x = 0
        while (x <= data.width) {
            g.drawLine(x, 0, x, data.height)
            x += SCREEN_PX
        }
        var y = 0
        while (y <= data.height) {
            g.drawLine(0, y, data.width, y)
            y += SCREEN_PX
        }
    }

    // Draw one-based screen coordinates independently of the grid. Screen (1, 1) is top-left.
    if (activeOverlays.contains(TileOverlay.SCREEN_COORDINATES)) {
        val g2 = g as java.awt.Graphics2D
        g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 12)
        for (screenY in 0 until roomHeightScreens) {
            for (screenX in 0 until roomWidthScreens) {
                val label = oneBasedRoomCoordinate(screenX, screenY)
                val labelX = screenX * SCREEN_PX + 6
                val labelY = screenY * SCREEN_PX + 17
                val width = g2.fontMetrics.stringWidth(label) + 8
                g2.color = java.awt.Color(0, 0, 0, 140)
                g2.fillRoundRect(labelX - 3, labelY - 13, width, 17, 5, 5)
                g2.color = java.awt.Color(255, 255, 255, 190)
                g2.drawString(label, labelX, labelY)
            }
        }
    }

    if (activeOverlays.isEmpty()) {
        g.dispose()
        return img
    }

    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall

    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) {
        g.dispose()
        return img
    }
    val btsData = data.btsData
    val itemBlocks = data.itemBlocks

    if (showFlatSlopeSurfaces && activeOverlays.contains(TileOverlay.SLOPE)) {
        val slopeOverlay = TileOverlay.SLOPE
        val flatColor = java.awt.Color(
            ((slopeOverlay.color shr 16) and 0xFF).toInt(),
            ((slopeOverlay.color shr 8) and 0xFF).toInt(),
            (slopeOverlay.color and 0xFF).toInt(),
            ((slopeOverlay.color shr 24) and 0xFF).toInt(),
        )
        val flatGraphics = g as java.awt.Graphics2D
        flatGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        drawFlatSurfaceOverlay(flatGraphics, data, flatColor)
    }

    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue

            val blockType = data.blockTypes[idx]
            val bts = if (idx < btsData.size) btsData[idx].toInt() and 0xFF else 0
            val px = bx * 16
            val py = by * 16

            val matchingOverlays = mutableListOf<TileOverlay>()
            if (activeOverlays.contains(TileOverlay.SOLID) && blockType == 0x8) matchingOverlays.add(TileOverlay.SOLID)
            if (activeOverlays.contains(TileOverlay.SLOPE) && blockType == 0x1) matchingOverlays.add(TileOverlay.SLOPE)
            if (activeOverlays.contains(TileOverlay.DOOR) && blockType == 0x9) matchingOverlays.add(TileOverlay.DOOR)
            if (activeOverlays.contains(TileOverlay.SPIKE) && blockType == 0xA) matchingOverlays.add(TileOverlay.SPIKE)
            if (activeOverlays.contains(TileOverlay.BOMB) && blockType == 0xF) matchingOverlays.add(TileOverlay.BOMB)
            if (blockType == 0xC) {
                when (shotBlockCategory(bts)) {
                    ShotCategory.BEAM -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.SUPER -> if (activeOverlays.contains(TileOverlay.SHOT_SUPER)) matchingOverlays.add(TileOverlay.SHOT_SUPER)
                    ShotCategory.PB -> if (activeOverlays.contains(TileOverlay.SHOT_PB)) matchingOverlays.add(TileOverlay.SHOT_PB)
                    ShotCategory.HIDDEN -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.DOOR -> {}
                }
            }
            if (blockType == 0xB) {
                val isSpeedBts = bts == 0x0E || bts == 0x0F
                if (isSpeedBts && activeOverlays.contains(TileOverlay.SPEED)) matchingOverlays.add(TileOverlay.SPEED)
                else if (!isSpeedBts && activeOverlays.contains(TileOverlay.CRUMBLE)) matchingOverlays.add(TileOverlay.CRUMBLE)
            }
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.TREADMILL) && blockType == 0x3) matchingOverlays.add(TileOverlay.TREADMILL)
            if (activeOverlays.contains(TileOverlay.ITEMS) && itemBlocks.contains(idx)) matchingOverlays.add(TileOverlay.ITEMS)

            // Overlay icons: 1/4 tile size (8×8 in a 16×16 tile), bottom-right quadrant
            val iconSize = 8
            var iconX = px + 16 - iconSize
            val iconY = py + 16 - iconSize

            val g2 = g as java.awt.Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 7)

            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )

                if (overlay == TileOverlay.SLOPE) {
                    drawSlopeOverlay(g2, px, py, bts, color)
                } else if (overlay == TileOverlay.SPEED) {
                    drawSpeedBoosterOverlay(g2, iconX, iconY, iconSize, color)
                    iconX -= (iconSize + 1)
                } else {
                    val label = richOverlayLabel(overlay, bts)
                    val fm = g2.fontMetrics
                    val labelW = fm.stringWidth(label)
                    val cellW = maxOf(iconSize, labelW + 2)

                    g2.color = java.awt.Color(0, 0, 0, 200)
                    g2.fillRect(iconX + iconSize - cellW, iconY, cellW, iconSize)
                    g2.color = color
                    g2.stroke = java.awt.BasicStroke(1.5f)
                    g2.drawRect(iconX + iconSize - cellW + 1, iconY + 1, cellW - 2, iconSize - 2)
                    g2.stroke = java.awt.BasicStroke(1f)
                    g2.color = java.awt.Color.WHITE
                    g2.drawString(label, iconX + iconSize - cellW + (cellW - labelW) / 2,
                        iconY + (iconSize + fm.ascent - fm.descent) / 2)
                    iconX -= (cellW + 1)
                }
            }
        }
    }

    // Draw item / station / gate / door cap labels (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.ITEMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val itemColor = java.awt.Color(0xFF, 0xCC, 0x00)       // gold
        val stationColor = java.awt.Color(0x44, 0xCC, 0xFF)    // cyan
        val gateColor = java.awt.Color(0xCC, 0x66, 0xFF)       // purple
        val itemSpriteSheet = ItemSpriteSheetCache.get()
        val itemDefs = buildItemOverlayDefs(customItems)
        for (plm in data.plmEntries) {
            val itemDef = itemDefs[plm.id]
            val isItem = itemDef != null
            val isStation = RomParser.isStationPlm(plm.id)
            val isGate = RomParser.isGatePlm(plm.id)
            val isDoorCap = RomParser.doorCapColor(plm.id) != null
            if (!isItem && !isStation && !isGate && !isDoorCap) continue
            val name = when {
                isItem -> itemDef?.name ?: continue
                isStation -> RomParser.stationNameForPlm(plm.id) ?: continue
                isDoorCap -> RomParser.doorCapDisplayName(plm.id) ?: continue
                else -> RomParser.gateNameForPlm(plm.id, plm.param) ?: continue
            }
            val badgeBorder = when {
                isStation -> stationColor
                isGate -> gateColor
                isDoorCap -> doorCapOverlayBorderColor(plm.id) ?: continue
                else -> itemColor
            }
            val horiz = isDoorCap && RomParser.doorCapIsHorizontal(plm.id)
            val cx = if (horiz) plm.x * 16 + 32 else plm.x * 16 + 8
            val cy = when {
                horiz -> plm.y * 16 + 8
                isDoorCap -> plm.y * 16 + 32
                else -> plm.y * 16 + 8
            }
            if (isItem) {
                val sprite = itemDef?.sprite
                if (itemSpriteSheet != null && sprite != null) {
                    if (highlightItems) {
                        g2.color = java.awt.Color(0, 0, 0, 160)
                        g2.fillRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                        g2.color = itemColor
                        g2.drawRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                    }
                    g2.drawImage(
                        itemSpriteSheet,
                        cx - 8,
                        cy - 8,
                        cx + 8,
                        cy + 8,
                        sprite.x,
                        sprite.y,
                        sprite.x + 16,
                        sprite.y + 16,
                        null,
                    )
                } else {
                    val label = itemDef?.shortLabel?.take(2).orEmpty()
                    g2.color = java.awt.Color(0, 0, 0, 200)
                    g2.fillRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                    g2.color = itemColor
                    g2.drawRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                    g2.color = java.awt.Color.WHITE
                    val labelW = fm.stringWidth(label)
                    g2.drawString(label, cx - labelW / 2, cy + (fm.ascent - fm.descent) / 2)
                }
            }
            val shouldDrawName = if (isItem) showItemNames else showMetaNames
            if (shouldDrawName) {
                val textWidth = fm.stringWidth(name)
                val badgeW = textWidth + 6
                val badgeH = fm.height + 2
                val bx = (cx - badgeW / 2).coerceIn(0, maxOf(0, data.width - badgeW))
                val rawBadgeY = if (isItem) cy + 12 else cy - badgeH / 2
                val by = rawBadgeY.coerceIn(0, maxOf(0, data.height - badgeH))
                g2.color = java.awt.Color(0, 0, 0, 200)
                g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = badgeBorder
                g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = java.awt.Color.WHITE
                g2.drawString(name, bx + 3, by + fm.ascent + 1)
            }
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw scroll PLM badges (positioned at PLM block coordinates)
    if (showMetaNames && activeOverlays.contains(TileOverlay.SCROLL_PLMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val scrollBadgeColor = java.awt.Color(0xFF, 0x80, 0x40)
        for (plm in data.plmEntries) {
            if (!RomParser.isScrollPlm(plm.id)) continue
            val name = RomParser.scrollPlmName(plm.id) ?: continue
            val cx = plm.x * 16 + 8
            val cy = plm.y * 16 + 8
            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = cx - badgeW / 2
            val by = cy - badgeH / 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = scrollBadgeColor
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw enemy sprites / markers (positioned at enemy pixel coordinates)
    if (activeOverlays.contains(TileOverlay.ENEMIES) && data.enemyEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val markerColor = java.awt.Color(0xFF, 0x66, 0x44)
        val hiddenCompanions = hiddenRoomEnemyCompanions(data.enemyEntries)
        for (enemy in data.enemyEntries) {
            if (enemy.id in hiddenCompanions) continue
            val preview = EnemySpriteCache.getPreview(enemy)
            val (ex, ey) = roomEnemyDisplayPosition(enemy, data.width, data.height, preview)
            if (ex < 0 || ex >= data.width || ey < 0 || ey >= data.height) continue
            val sprite = preview?.image
            if (preview != null && sprite != null) {
                val flipH = shouldFlipEnemySprite(enemy)
                val sx = ex - if (flipH) sprite.width - preview.originX else preview.originX
                val sy = ey - preview.originY
                if (flipH) {
                    g2.drawImage(sprite, sx + sprite.width, sy, sx, sy + sprite.height,
                        0, 0, sprite.width, sprite.height, null)
                } else {
                    g2.drawImage(sprite, sx, sy, null)
                }
            } else {
                val diamondSize = 6
                val dx = intArrayOf(ex, ex + diamondSize, ex, ex - diamondSize)
                val dy = intArrayOf(ey - diamondSize, ey, ey + diamondSize, ey)
                g2.color = java.awt.Color(0xFF, 0x44, 0x22, 180)
                g2.fillPolygon(dx, dy, 4)
                g2.color = markerColor
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawPolygon(dx, dy, 4)
                g2.stroke = java.awt.BasicStroke(1f)
            }

            // The top, bottom and entrance pad are one ship, so only its top entry owns a label.
            if (showEnemyNames && enemy.id != ShipSpritemap.BOTTOM_SPECIES &&
                (showMetaNames || !RomParser.isMapMetaEnemy(enemy.id))) {
                val name = RomParser.enemyName(enemy.id)
                val textWidth = fm.stringWidth(name)
                val badgeW = textWidth + 6
                val badgeH = fm.height + 2
                val labelCenter = if (enemy.id in RoomBossPreview.SPECIES && preview != null)
                    ex - preview.originX + preview.image.width / 2 else ex
                val bx = (labelCenter - badgeW / 2).coerceIn(0, maxOf(0, data.width - badgeW - 1))
                val by = (ey - (preview?.originY ?: 6) - badgeH - 2).coerceIn(0, maxOf(0, data.height - badgeH - 1))
                g2.color = java.awt.Color(0, 0, 0, 200)
                g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = markerColor
                g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = java.awt.Color.WHITE
                g2.drawString(name, bx + 3, by + fm.ascent + 1)
            }
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    g.dispose()
    return img
}
