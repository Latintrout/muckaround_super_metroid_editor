package com.supermetroid.editor.data

import java.io.File
import java.util.prefs.Preferences

/**
 * Simple preferences storage for ROM file path
 */
object RomPreferences {
    private const val PREFS_KEY_ROM_PATH = "last_rom_path"
    private const val PREFS_KEY_PROJECT_PATH = "last_project_path"
    
    private val prefs: Preferences = Preferences.userNodeForPackage(RomPreferences::class.java)
    
    fun getLastRomPath(): String? {
        val path = prefs.get(PREFS_KEY_ROM_PATH, null)
        return if (path != null && File(path).exists()) path else null
    }
    
    fun setLastRomPath(path: String) {
        prefs.put(PREFS_KEY_ROM_PATH, path)
    }

    fun getLastProjectPath(): String? = prefs.get(PREFS_KEY_PROJECT_PATH, null)
        ?.takeIf { File(it).isFile }

    fun setLastProjectPath(path: String) {
        prefs.put(PREFS_KEY_PROJECT_PATH, path)
    }

    fun clearLastProjectPath() { prefs.remove(PREFS_KEY_PROJECT_PATH) }
    
    fun clearLastRomPath() {
        prefs.remove(PREFS_KEY_ROM_PATH)
    }
}
