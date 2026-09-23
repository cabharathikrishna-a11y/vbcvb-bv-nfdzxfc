package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.util.GoogleDriveReadManager
import com.example.util.GoogleDriveSyncNotificationHelper
import com.example.util.GoogleDriveSyncProgressTracker
import com.example.util.GoogleDriveUploadManager
import com.example.util.GoogleDriveWriteManager
import kotlinx.coroutines.*

/**
 * GoogleDriveSyncService
 *
 * Dedicated Foreground Service with FOREGROUND_SERVICE_DATA_SYNC.
 * Guarantees that user-initiated Google Drive sync, backup, restore, and cleanup operations
 * continue executing in the background even if the user exits, minimizes, or leaves the application.
 *
 * Emits dynamic silent notifications (preparing, reading, progress %, sending, receiving, downloading)
 * to keep the user informed without intrusive sounds or vibrations.
 */
class GoogleDriveSyncService : Service() {

    companion object {
        private const val TAG = "GDriveSyncService"

        const val ACTION_FULL_SYNC = "com.example.action.GDRIVE_FULL_SYNC"
        const val ACTION_BACKUP_ALL = "com.example.action.GDRIVE_BACKUP_ALL"
        const val ACTION_RESTORE_ALL = "com.example.action.GDRIVE_RESTORE_ALL"
        const val ACTION_CLEAN_VAULT = "com.example.action.GDRIVE_CLEAN_VAULT"
        const val ACTION_BACKUP_FOCUS = "com.example.action.GDRIVE_BACKUP_FOCUS"
        const val ACTION_RESTORE_FOCUS = "com.example.action.GDRIVE_RESTORE_FOCUS"
        const val ACTION_SYNC_KEEP_NOTES = "com.example.action.GDRIVE_SYNC_KEEP_NOTES"

        fun startFullSync(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_FULL_SYNC
            }
            startServiceCompat(context, intent)
        }

        fun startBackup(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_BACKUP_ALL
            }
            startServiceCompat(context, intent)
        }

        fun startRestore(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_RESTORE_ALL
            }
            startServiceCompat(context, intent)
        }

        fun startCleanVault(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_CLEAN_VAULT
            }
            startServiceCompat(context, intent)
        }

        fun startFocusBackup(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_BACKUP_FOCUS
            }
            startServiceCompat(context, intent)
        }

        fun startFocusRestore(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_RESTORE_FOCUS
            }
            startServiceCompat(context, intent)
        }

        fun startKeepNotesSync(context: Context) {
            val intent = Intent(context, GoogleDriveSyncService::class.java).apply {
                action = ACTION_SYNC_KEEP_NOTES
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            try {
                if (!com.example.util.AuthGatekeeper.isUserLoggedIn(context)) {
                    Log.d(TAG, "Aborting GoogleDriveSyncService start: User is not logged in.")
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start GoogleDriveSyncService: ${e.message}", e)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GoogleDriveSyncNotificationHelper.createNotificationChannel(this)
        val initialNotif = GoogleDriveSyncNotificationHelper.buildNotification(
            context = this,
            title = "Google Drive Sync",
            message = "Preparing background synchronization...",
            progress = 0,
            indeterminate = true,
            isFinished = false
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    GoogleDriveSyncNotificationHelper.NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(GoogleDriveSyncNotificationHelper.NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting initial foreground service in onCreate: ${e.message}", e)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LifeOS:GDriveSyncWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minute safety limit
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition exception: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.example.util.AuthGatekeeper.isUserLoggedIn(this)) {
            Log.d(TAG, "GoogleDriveSyncService onStartCommand aborted: User is not logged in.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping foreground: ${e.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_FULL_SYNC

        val initialTitle = when (action) {
            ACTION_BACKUP_ALL -> "Cloud Backup"
            ACTION_RESTORE_ALL -> "Cloud Restore"
            ACTION_CLEAN_VAULT -> "Cleaning Vault Folders"
            ACTION_BACKUP_FOCUS -> "Backing up Focus Data"
            ACTION_RESTORE_FOCUS -> "Restoring Focus Data"
            ACTION_SYNC_KEEP_NOTES -> "Syncing Keep Notes"
            else -> "Google Drive Sync"
        }

        val notification = GoogleDriveSyncNotificationHelper.buildNotification(
            context = this,
            title = "Google Drive: $initialTitle",
            message = "Preparing background synchronization...",
            progress = 0,
            indeterminate = true,
            isFinished = false
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    GoogleDriveSyncNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(GoogleDriveSyncNotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)

                when (action) {
                    ACTION_FULL_SYNC -> {
                        GoogleDriveSyncProgressTracker.updateProgress(
                            applicationContext,
                            "Full Sync",
                            "Preparing",
                            5,
                            "Initiating full two-way Google Drive synchronization..."
                        )

                        // 1. Pull / Restore phase
                        val (restoreSuccess, restoreMsg) = GoogleDriveReadManager.restoreAllAppData(applicationContext, db)
                        
                        if (restoreSuccess || restoreMsg.contains("No full app data backup found") || restoreMsg.contains("No existing backup files found")) {
                            // 2. Push / Backup phase
                            val (backupSuccess, backupMsg) = GoogleDriveWriteManager.backupAllAppData(applicationContext, db)
                            if (backupSuccess) {
                                GoogleDriveSyncProgressTracker.updateProgress(
                                    applicationContext,
                                    "Full Sync",
                                    "Completed",
                                    100,
                                    "Full sync completed successfully! Pulled & pushed latest data.",
                                    isFinished = true
                                )
                            } else {
                                GoogleDriveSyncProgressTracker.updateProgress(
                                    applicationContext,
                                    "Full Sync",
                                    "Failed",
                                    0,
                                    "Pull succeeded, but Push failed: $backupMsg",
                                    isFinished = true,
                                    isError = true
                                )
                            }
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Full Sync",
                                "Failed",
                                0,
                                "Pull failed: $restoreMsg",
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_BACKUP_ALL -> {
                        val (success, msg) = GoogleDriveWriteManager.backupAllAppData(applicationContext, db)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Cloud Backup",
                                "Completed",
                                100,
                                "All app data and files successfully backed up to Google Drive!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Cloud Backup",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_RESTORE_ALL -> {
                        val (success, msg) = GoogleDriveReadManager.restoreAllAppData(applicationContext, db)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Cloud Restore",
                                "Completed",
                                100,
                                "All app data and files successfully restored from Google Drive!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Cloud Restore",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_CLEAN_VAULT -> {
                        val (success, msg) = GoogleDriveUploadManager.manageAndCleanDriveAppData(applicationContext)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Vault Cleanup",
                                "Completed",
                                100,
                                "Drive vault organized and duplicate files cleaned successfully!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Vault Cleanup",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_BACKUP_FOCUS -> {
                        val (success, msg) = GoogleDriveWriteManager.backupFocusData(applicationContext)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Focus Backup",
                                "Completed",
                                100,
                                "Focus timers & pomodoro records backed up to Google Drive!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Focus Backup",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_RESTORE_FOCUS -> {
                        val (success, msg) = GoogleDriveReadManager.restoreFocusData(applicationContext)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Focus Restore",
                                "Completed",
                                100,
                                "Focus timer records restored from Google Drive!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Focus Restore",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }

                    ACTION_SYNC_KEEP_NOTES -> {
                        val (success, msg) = GoogleDriveWriteManager.syncKeepNotes(applicationContext, db)
                        if (success) {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Keep Notes Sync",
                                "Completed",
                                100,
                                "Google Keep Notes synchronized successfully!",
                                isFinished = true
                            )
                        } else {
                            GoogleDriveSyncProgressTracker.updateProgress(
                                applicationContext,
                                "Keep Notes Sync",
                                "Failed",
                                0,
                                msg,
                                isFinished = true,
                                isError = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled error during GoogleDriveSyncService execution: ${e.message}", e)
                GoogleDriveSyncProgressTracker.updateProgress(
                    applicationContext,
                    "Sync",
                    "Failed",
                    0,
                    "Sync error: ${e.localizedMessage ?: "Unknown error"}",
                    isFinished = true,
                    isError = true
                )
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } else {
                            stopForeground(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "stopForeground error: ${e.message}")
                    }
                    stopSelf(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error: ${e.message}")
        }
    }
}
