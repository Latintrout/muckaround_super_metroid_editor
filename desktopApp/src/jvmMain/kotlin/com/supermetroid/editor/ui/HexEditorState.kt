package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
/**
 * A named bookmark to a known WRAM address range for quick navigation.
 * [size] is how many bytes to highlight (default 2 for a 16-bit word).
 */
data class MemoryBookmark(
    val label: String,
    val address: Int,
    val size: Int = 2,
    val category: String = "General",
    val description: String = "",
)

/**
 * State holder for the hex/memory editor panel.
 *
 * Reads and writes emulator WRAM through [EmulatorWorkspaceState], which
 * handles backend selection and WRAM address bank-prefix conversion.
 */
class HexEditorState {

    // ── View state ──────────────────────────────────────────────────────────

    /** Base WRAM address currently displayed at row 0 of the hex grid. */
    var baseAddress by mutableStateOf(0x0000)

    /** Number of bytes per row (16 is the classic hex-editor default). */
    val bytesPerRow: Int = 16

    /** Number of visible rows in the grid. */
    var visibleRows by mutableStateOf(32)

    /** The currently highlighted address range (start, length). */
    var highlightAddress by mutableStateOf<Int?>(null)
    var highlightSize by mutableStateOf(1)

    /** The address the cursor/selection is on (for editing). */
    var selectedAddress by mutableStateOf<Int?>(null)

    /** Whether the left bookmark nav is expanded. */
    var navExpanded by mutableStateOf(true)

    /** Most recently read memory block (baseAddress .. baseAddress + windowSize). */
    var memoryBlock by mutableStateOf(ByteArray(0))
        private set

    /** True while a read is in-flight. */
    var reading by mutableStateOf(false)
        private set

    /** Status / error message. */
    var statusMessage by mutableStateOf("")

    /** Whether auto-refresh is enabled. */
    var autoRefresh by mutableStateOf(true)

    /** Editable text for the "Go to address" field. */
    var goToAddressText by mutableStateOf("")

    // ── Bookmarks ───────────────────────────────────────────────────────────

    val bookmarks = mutableStateListOf<MemoryBookmark>().apply {
        addAll(defaultBookmarks())
    }

    /** Grouped bookmarks by category for the nav panel. */
    fun bookmarksByCategory(): Map<String, List<MemoryBookmark>> =
        bookmarks.groupBy { it.category }

    // ── Memory access ───────────────────────────────────────────────────────

    /** Total bytes in the current view window. */
    val windowSize: Int get() = bytesPerRow * visibleRows

    /**
     * Read [windowSize] bytes from [baseAddress] via the emulator workspace.
     * Updates [memoryBlock] on success.
     */
    suspend fun refresh(emu: EmulatorWorkspaceState) {
        if (!emu.isMemoryAccessible) {
            statusMessage = "Emulator not connected"
            return
        }
        reading = true
        try {
            memoryBlock = emu.readMemory(baseAddress, windowSize)
            statusMessage = ""
        } catch (e: Exception) {
            statusMessage = "Read error: ${e.message}"
        } finally {
            reading = false
        }
    }

    /**
     * Write a single byte at [address] to the emulator.
     */
    suspend fun writeByte(emu: EmulatorWorkspaceState, address: Int, value: Byte) {
        if (!emu.isMemoryAccessible) {
            statusMessage = "Emulator not connected"
            return
        }
        try {
            emu.writeMemory(address, byteArrayOf(value))
            statusMessage = "Wrote 0x${"%02X".format(value.toInt() and 0xFF)} to \$${"%04X".format(address)}"
        } catch (e: Exception) {
            statusMessage = "Write error: ${e.message}"
        }
    }

    /**
     * Write a 16-bit little-endian word at [address].
     */
    suspend fun writeWord(emu: EmulatorWorkspaceState, address: Int, value: Int) {
        if (!emu.isMemoryAccessible) {
            statusMessage = "Emulator not connected"
            return
        }
        try {
            emu.writeMemory(address, byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
            ))
            statusMessage = "Wrote 0x${"%04X".format(value and 0xFFFF)} to \$${"%04X".format(address)}"
        } catch (e: Exception) {
            statusMessage = "Write error: ${e.message}"
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    /** Navigate to a bookmark: set base address so the bookmark is visible, and highlight it. */
    fun goToBookmark(bookmark: MemoryBookmark) {
        // Strip the 7E bank prefix for display — our grid shows WRAM offsets 0x0000..0x1FFFF
        val addr = bookmark.address and 0x1FFFF
        // Align base to a row boundary a few rows above the target
        val targetRow = addr / bytesPerRow
        val topRow = (targetRow - 4).coerceAtLeast(0)
        baseAddress = topRow * bytesPerRow
        highlightAddress = addr
        highlightSize = bookmark.size
        selectedAddress = addr
    }

    /** Navigate to an arbitrary WRAM address. */
    fun goToAddress(address: Int) {
        val addr = address and 0x1FFFF
        val targetRow = addr / bytesPerRow
        val topRow = (targetRow - 4).coerceAtLeast(0)
        baseAddress = topRow * bytesPerRow
        highlightAddress = addr
        highlightSize = 1
        selectedAddress = addr
    }

    /** Parse [goToAddressText] and navigate. Returns false if the text is not a valid hex address. */
    fun goToAddressFromText(): Boolean {
        val text = goToAddressText.trim().removePrefix("$").removePrefix("0x").removePrefix("0X")
        val parsed = text.toIntOrNull(16) ?: return false
        goToAddress(parsed)
        return true
    }

    fun pageUp() {
        baseAddress = (baseAddress - windowSize).coerceAtLeast(0)
    }

    fun pageDown() {
        baseAddress = (baseAddress + windowSize).coerceAtMost(0x1FFFF)
    }

    fun scrollUp(rows: Int = 1) {
        baseAddress = (baseAddress - bytesPerRow * rows).coerceAtLeast(0)
    }

    fun scrollDown(rows: Int = 1) {
        baseAddress = (baseAddress + bytesPerRow * rows).coerceAtMost(0x1FFFF)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Get the byte value at [address] from the current [memoryBlock], or null
     * if the address is outside the loaded range.
     */
    fun byteAt(address: Int): Byte? {
        val offset = address - baseAddress
        return if (offset in memoryBlock.indices) memoryBlock[offset] else null
    }

    /** Read a 16-bit LE word from the memory block at a given WRAM address. */
    fun wordAt(address: Int): Int? {
        val lo = byteAt(address) ?: return null
        val hi = byteAt(address + 1) ?: return null
        return (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
    }

    companion object {
        /** Well-known Super Metroid WRAM addresses for the bookmark sidebar. */
        fun defaultBookmarks(): List<MemoryBookmark> = listOf(
            // ── Game state ──
            MemoryBookmark("Game State", 0x0998, 2, "Game", "Main game state machine value"),
            MemoryBookmark("Room ID", 0x079B, 2, "Game", "Current room pointer (bank \$8F)"),
            MemoryBookmark("Area Index", 0x079F, 1, "Game", "Current area (0=Crateria, 1=Brinstar, ...)"),
            MemoryBookmark("Door Pointer", 0x078D, 2, "Game", "Current door definition pointer"),

            // ── Samus stats ──
            MemoryBookmark("Energy", 0x09C2, 2, "Samus", "Current energy"),
            MemoryBookmark("Max Energy", 0x09C4, 2, "Samus", "Maximum energy"),
            MemoryBookmark("Missiles", 0x09C6, 2, "Samus", "Current missiles"),
            MemoryBookmark("Max Missiles", 0x09C8, 2, "Samus", "Maximum missiles"),
            MemoryBookmark("Supers", 0x09CA, 2, "Samus", "Current super missiles"),
            MemoryBookmark("Max Supers", 0x09CC, 2, "Samus", "Maximum super missiles"),
            MemoryBookmark("Power Bombs", 0x09CE, 2, "Samus", "Current power bombs"),
            MemoryBookmark("Max Power Bombs", 0x09D0, 2, "Samus", "Maximum power bombs"),
            MemoryBookmark("Reserve Energy", 0x09D6, 2, "Samus", "Current reserve energy"),
            MemoryBookmark("Max Reserve", 0x09D4, 2, "Samus", "Maximum reserve energy"),

            // ── Samus position / physics ──
            MemoryBookmark("Samus X", 0x0AF6, 2, "Position", "Samus X position (pixels)"),
            MemoryBookmark("Samus Y", 0x0AFA, 2, "Position", "Samus Y position (pixels)"),
            MemoryBookmark("Samus X Sub", 0x0AF8, 2, "Position", "Samus X sub-pixel"),
            MemoryBookmark("Samus Y Sub", 0x0AFC, 2, "Position", "Samus Y sub-pixel"),
            MemoryBookmark("Samus X Speed", 0x0B42, 2, "Position", "Samus X speed (signed)"),
            MemoryBookmark("Samus Y Speed", 0x0B2E, 2, "Position", "Samus Y speed (signed)"),
            MemoryBookmark("Samus Pose", 0x0A1C, 2, "Position", "Current pose/animation index"),
            MemoryBookmark("Movement Type", 0x0A1F, 1, "Position", "Movement type (running, falling, etc.)"),

            // ── Items / beams ──
            MemoryBookmark("Collected Items", 0x09A4, 2, "Items", "Item bit flags (Varia, Morph, etc.)"),
            MemoryBookmark("Equipped Items", 0x09A2, 2, "Items", "Currently equipped item flags"),
            MemoryBookmark("Collected Beams", 0x09A8, 2, "Items", "Beam bit flags (Wave, Ice, etc.)"),
            MemoryBookmark("Equipped Beams", 0x09A6, 2, "Items", "Currently equipped beam flags"),

            // ── Boss flags ──
            MemoryBookmark("Boss Flags: Crateria", 0xD828, 2, "Bosses", "Crateria boss bit flags"),
            MemoryBookmark("Boss Flags: Brinstar", 0xD82C, 2, "Bosses", "Brinstar boss bit flags"),
            MemoryBookmark("Boss Flags: Norfair", 0xD830, 2, "Bosses", "Norfair boss bit flags"),
            MemoryBookmark("Boss Flags: Wrecked Ship", 0xD834, 2, "Bosses", "Wrecked Ship boss bit flags"),
            MemoryBookmark("Boss Flags: Maridia", 0xD838, 2, "Bosses", "Maridia boss bit flags"),
            MemoryBookmark("Boss Flags: Tourian", 0xD83C, 2, "Bosses", "Tourian boss bit flags"),

            // ── Scroll / camera ──
            MemoryBookmark("BG1 X Scroll", 0x0911, 2, "Camera", "Layer 1 X scroll position"),
            MemoryBookmark("BG1 Y Scroll", 0x0915, 2, "Camera", "Layer 1 Y scroll position"),

            // ── Timers ──
            MemoryBookmark("IGT Frames", 0x09DA, 2, "Timer", "In-game timer: frames"),
            MemoryBookmark("IGT Seconds", 0x09DC, 2, "Timer", "In-game timer: seconds"),
            MemoryBookmark("IGT Minutes", 0x09DE, 2, "Timer", "In-game timer: minutes"),
            MemoryBookmark("IGT Hours", 0x09E0, 2, "Timer", "In-game timer: hours"),
        )
    }
}
