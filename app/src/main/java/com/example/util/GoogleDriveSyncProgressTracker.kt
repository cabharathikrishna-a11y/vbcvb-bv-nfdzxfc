package com.example.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Data representation of active Google Drive synchronization status.
 */
data class GoogleDriveSyncStatus(
    val isRunning: Boolean = false,
    val operationType: String = "",       // e.g. "Cloud Backup", "Cloud Restore", "Full Sync", "Vault Cleanup"
    val phase: String = "Idle",           // "Preparing", "Reading", "Compressing", "Sending", "Downloading", "Receiving", "Finalizing", "Completed", "Failed"
    val progress: Int = 0,                // 0 to 100
    val statusMessage: String = "",       // Detailed readable status description
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * GoogleDriveSyncProgressTracker
 *
 * Singleton state manager and broadcaster for Google Drive tasks.
 * Ensures every sync event posts silent progress updates to the system notification
 * and broadcasts live state to Compose UI components.
 */
object GoogleDriveSyncProgressTracker {

    private val _syncStatus = MutableStateFlow(GoogleDriveSyncStatus())
    val syncStatus: StateFlow<GoogleDriveSyncStatus> = _syncStatus.asStateFlow()

    private val trackerScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var dismissJob: Job? = null

    /**
     * Updates the sync progress and automatically posts a silent notification on the user device.
     */
    fun updateProgress(
        context: Context,
        operationType: String,
        phase: String,
        progress: Int,
        message: String,
        isFinished: Boolean = false,
        isError: Boolean = false
    ) {
        val clampedProgress = progress.coerceIn(0, 100)
        val status = GoogleDriveSyncStatus(
            isRunning = !isFinished,
            operationType = operationType,
            phase = phase,
            progress = clampedProgress,
            statusMessage = message,
            error = if (isError) message else null,
            timestamp = System.currentTimeMillis()
        )

        _syncStatus.value = status

        // Post silent system notification
        val notifTitle = if (operationType.isNotBlank()) "Google Drive: $operationType" else "Google Drive Sync"
        val notifMessage = if (isFinished) {
            if (isError) "⚠️ $message" else "✅ $message"
        } else {
            "[$phase - $clampedProgress%] $message"
        }

        GoogleDriveSyncNotificationHelper.notifyProgress(
            context = context,
            title = notifTitle,
            message = notifMessage,
            progress = clampedProgress,
            indeterminate = clampedProgress == 0 && !isFinished,
            isFinished = isFinished,
            isError = isError
        )

        // If finished successfully, auto-dismiss notification after 6 seconds so status bar stays tidy
        dismissJob?.cancel()
        if (isFinished && !isError) {
            dismissJob = trackerScope.launch {
                delay(6000L)
                GoogleDriveSyncNotificationHelper.cancelNotification(context)
            }
        }
    }

    /**
     * Resets the status to Idle.
     */
    fun reset() {
        _syncStatus.value = GoogleDriveSyncStatus()
    }
}
