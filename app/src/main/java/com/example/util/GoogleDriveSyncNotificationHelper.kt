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
 * Ensures:
 * - Silent notification channel with zero vibration and zero sound.
 * - Dynamic stage progress (0% - 100%, preparing, reading, sending, receiving, downloading, etc.).
 * - Persistent notification during foreground service execution.
 */
object GoogleDriveSyncNotificationHelper {

    const val CHANNEL_ID = "gdrive_sync_channel"
    const val CHANNEL_NAME = "Google Drive Sync & Backup"
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
                    NotificationManager.IMPORTANCE_LOW // Low importance = silent, no sound, no head-up popup
                ).apply {
                    description = "Silent background progress for Google Drive backups and cloud sync"
                    enableVibration(false)
                    vibrationPattern = null
                    setSound(null, null)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Builds a progress notification for Google Drive sync.
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSubText("Google Drive")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(!isFinished)
            .setAutoCancel(isFinished)
            .setOnlyAlertOnce(true) // Absolute silence during multiple 1% ticks
            .setSilent(true)

        if (!isFinished) {
            val clampedProgress = progress.coerceIn(0, 100)
            builder.setProgress(100, clampedProgress, indeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    /**
     * Immediately posts or updates the silent notification on the user device.
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
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            android.util.Log.e("GDriveNotification", "Failed to update notification: ${e.message}")
        }
    }

    /**
     * Cancels the sync notification.
     */
    fun cancelNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            android.util.Log.e("GDriveNotification", "Failed to cancel notification: ${e.message}")
        }
    }
}
