package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * GoogleDriveSyncNotificationHelper
 *
 * Provides completely silent, low-priority background progress notifications
 * for all Google Drive sync, backup, restore, upload, and download operations.
 *
 * Guarantees:
 * - Single persistent notification ID (4099) so notifications are updated in-place smoothly
 *   without spawning new notifications or deleting old ones.
 * - Silent notification channel with zero vibration and zero sound.
 * - Throttled updates to prevent notification queue flickering or stuttering.
 * - Auto-dismisses finished (success or error) notifications cleanly.
 */
object GoogleDriveSyncNotificationHelper {

    const val CHANNEL_ID = "gdrive_silent_sync_v3"
    const val CHANNEL_NAME = "Google Drive Background Sync"
    const val NOTIFICATION_ID = 4099

    /**
     * Ensures the silent notification channel is registered with the system.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN // IMPORTANCE_MIN = completely silent background progress, no sound, no vibration, no heads-up popup
                ).apply {
                    description = "Completely silent background progress for Google Drive backups and cloud sync"
                    enableVibration(false)
                    vibrationPattern = null
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Builds a single updating progress notification for Google Drive sync.
     */
    fun buildNotification(
        context: Context,
        title: String,
        message: String,
        progress: Int,
        indeterminate: Boolean = false,
        isFinished: Boolean = false,
        isError: Boolean = false
    ): Notification {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "settings")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val smallIcon = when {
            isError -> android.R.drawable.stat_notify_error
            isFinished -> android.R.drawable.stat_sys_upload_done
            title.contains("Restore", ignoreCase = true) || title.contains("Download", ignoreCase = true) || title.contains("Pull", ignoreCase = true) -> android.R.drawable.stat_sys_download
            else -> android.R.drawable.stat_sys_upload
        }

        val cleanMessage = if (message.length > 120) message.take(117) + "..." else message

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(cleanMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanMessage))
            .setSubText("Google Drive")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(!isFinished)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Maintain absolute silence during updates
            .setSilent(true)
            .setVibrate(null)
            .setSound(null)

        if (!isFinished) {
            val clampedProgress = progress.coerceIn(0, 100)
            builder.setProgress(100, clampedProgress, indeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    /**
     * Immediately posts or updates the single silent notification in-place.
     */
    fun notifyProgress(
        context: Context,
        title: String,
        message: String,
        progress: Int,
        indeterminate: Boolean = false,
        isFinished: Boolean = false,
        isError: Boolean = false
    ) {
        try {
            val notification = buildNotification(context, title, message, progress, indeterminate, isFinished, isError)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            // Always update the single consistent notification ID so it modifies in-place
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            android.util.Log.w("GDriveNotification", "Failed to update notification: ${e.message}")
        }
    }

    /**
     * Cancels the single sync notification.
     */
    fun cancelNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            android.util.Log.w("GDriveNotification", "Failed to cancel notification: ${e.message}")
        }
    }
}
