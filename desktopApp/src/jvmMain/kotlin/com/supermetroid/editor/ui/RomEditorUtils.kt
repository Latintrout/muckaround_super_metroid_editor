package com.supermetroid.editor.ui

internal fun bytesSha256(bytes: List<Int>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (b in bytes) digest.update((b and 0xFF).toByte())
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

internal fun bytesSha256(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/**
 * Canonical parity hash for an Asar-generated build input. The internal SNES
 * checksum/complement are derived build metadata and are recalculated when
 * generated source changes; every semantic byte remains part of the proof.
 */
internal fun asmGeneratedParitySha256(bytes: ByteArray): String {
    val headerSize = if (bytes.size % 0x8000 == 0x200) 0x200 else 0
    val checksumStart = headerSize + 0x7FDC
    val checksumEndExclusive = checksumStart + 4
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    bytes.forEachIndexed { index, byte ->
        val canonicalByte: Byte = if (index in checksumStart until checksumEndExclusive) 0 else byte
        digest.update(canonicalByte)
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

internal fun isSnesInternalChecksumOffset(bytes: ByteArray, fileOffset: Int): Boolean {
    val headerSize = if (bytes.size % 0x8000 == 0x200) 0x200 else 0
    return fileOffset in (headerSize + 0x7FDC)..(headerSize + 0x7FDF)
}
