package com.example.util

import android.content.Context
import android.util.Log

/**
 * Helper to log and track locally deleted Google Keep Notes.
 * Prevents notes deleted locally by the user from being resurrected during Google Drive or Cloud sync.
 */
object DeletedKeepNoteLogHelper {
    private const val PREFS_NAME = "deleted_keep_notes_log_prefs"
    private const val TAG = "DeletedKeepNoteLog"

    private fun noteKey(title: String, content: String): String {
        return "${title.trim().lowercase()}:::${content.trim().lowercase()}"
    }

    fun logDeletedNote(context: Context, title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = noteKey(title, content)
        prefs.edit().putBoolean(key, true).apply()
        Log.d(TAG, "Logged deleted note: $key")
    }

    fun isNoteDeletedLocally(context: Context, title: String, content: String): Boolean {
        if (title.isBlank() && content.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = noteKey(title, content)
        return prefs.getBoolean(key, false)
    }

    fun removeDeletedNoteFromLog(context: Context, title: String, content: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = noteKey(title, content)
        prefs.edit().remove(key).apply()
        Log.d(TAG, "Removed note from deleted log: $key")
    }

    fun clearLog(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
