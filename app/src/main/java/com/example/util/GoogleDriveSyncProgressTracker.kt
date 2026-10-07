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
 * Ensures every sync event smoothly updates live state in Compose UI
 * while maintaining a single, quiet, in-place updating system notification that auto-dismisses.
 */
object GoogleDriveSyncProgressTracker {

    private val _syncStatus = MutableStateFlow(GoogleDriveSyncStatus())
    val syncStatus: StateFlow<GoogleDriveSyncStatus> = _syncStatus.asStateFlow()

    private val trackerScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var dismissJob: Job? = null

    // Throttling fields to prevent notification subsystem stutter and rapid text flickering
    private var lastNotifiedProgress = -1
    private var lastNotifiedPhase = ""
    private var lastNotifyTimestamp = 0L

    /**
     * Updates the sync progress and updates the single silent notification in-place.
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

        // Always update Compose UI state immediately
        _syncStatus.value = status

        val now = System.currentTimeMillis()
        val shouldNotifySystem = isFinished || isError ||
                phase != lastNotifiedPhase ||
                Math.abs(clampedProgress - lastNotifiedProgress) >= 10 ||
                (now - lastNotifyTimestamp >= 1200L)

        if (shouldNotifySystem) {
            lastNotifiedProgress = clampedProgress
            lastNotifiedPhase = phase
            lastNotifyTimestamp = now

            val notifTitle = if (operationType.isNotBlank()) "Google Drive: $operationType" else "Google Drive Sync"
            val cleanMessage = when {
                isError -> {
                    val safeMsg = when {
                        message.contains("connection", ignoreCase = true) || message.contains("connect", ignoreCase = true) -> "Sync paused (waiting for connection)"
                        message.contains("401") || message.contains("Auth", ignoreCase = true) -> "Drive authorization required"
                        message.contains("timeout", ignoreCase = true) -> "Sync temporarily delayed"
                        message.contains("Pull succeeded, but Push failed", ignoreCase = true) -> "Sync paused (will retry)"
                        else -> {
                            val sanitized = message.replace(Regex("failed\\s*\\d+", RegexOption.IGNORE_CASE), "retrying")
                            if (sanitized.length > 75) sanitized.take(72) + "..." else sanitized
                        }
                    }
                    "⚠️ $safeMsg"
                }
                isFinished -> "✅ $message"
                else -> "[$phase - $clampedProgress%] $message"
            }

            GoogleDriveSyncNotificationHelper.notifyProgress(
                context = context,
                title = notifTitle,
                message = cleanMessage,
                progress = clampedProgress,
                indeterminate = clampedProgress == 0 && !isFinished,
                isFinished = isFinished,
                isError = isError
            )
        }

        // Auto-dismiss the notification for both success and error after 4.5 seconds so status bar stays tidy
        dismissJob?.cancel()
        if (isFinished) {
            dismissJob = trackerScope.launch {
                delay(4500L)
                GoogleDriveSyncNotificationHelper.cancelNotification(context)
            }
        }
    }

    /**
     * Resets the status to Idle and cleans up any persistent notification.
     */
    fun reset(context: Context? = null) {
        _syncStatus.value = GoogleDriveSyncStatus()
        lastNotifiedProgress = -1
        lastNotifiedPhase = ""
        context?.let { GoogleDriveSyncNotificationHelper.cancelNotification(it) }
    }
}
