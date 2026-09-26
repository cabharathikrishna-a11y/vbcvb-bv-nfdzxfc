package com.example.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Universal Permission & Tester Mode Utility.
 * When Tester Mode is active ("is_tester_mode" in SharedPreferences or tester_mode_user username),
 * ALL system, runtime, special, and service permissions are assumed to be granted by default.
 * No permission prompts, dialogs, warnings, or restrictions are applied.
 */
object PermissionUtils {

    @Volatile
    var isTesterModeOverride: Boolean? = null

    /**
     * Checks if the app is currently running in Tester Mode.
     */
    fun isTesterMode(context: Context? = null): Boolean {
        if (isTesterModeOverride == true) return true
        if (context == null) return false

        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isTesterFlag = prefs.getBoolean("is_tester_mode", false)
            val currentUsername = prefs.getString("current_username", "") ?: ""
            val username = prefs.getString("username", "") ?: ""

            isTesterFlag ||
                    currentUsername == "tester_mode_user" ||
                    username == "tester_mode_user" ||
                    currentUsername.startsWith("tester_") ||
                    username.startsWith("tester_")
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Universal permission check for runtime permissions (Camera, Mic, Storage, Location, Notifications, Contacts, Calendar, etc.).
     * Always returns true if Tester Mode is active.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        if (isTesterMode(context)) return true
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Check multiple runtime permissions. Always returns true in Tester Mode.
     */
    fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        if (isTesterMode(context)) return true
        return permissions.all { hasPermission(context, it) }
    }

    /**
     * Returns PackageManager.PERMISSION_GRANTED or PERMISSION_DENIED.
     * In Tester Mode, always returns PackageManager.PERMISSION_GRANTED.
     */
    fun checkSelfPermission(context: Context, permission: String): Int {
        if (isTesterMode(context)) return PackageManager.PERMISSION_GRANTED
        return try {
            ContextCompat.checkSelfPermission(context, permission)
        } catch (e: Throwable) {
            PackageManager.PERMISSION_DENIED
        }
    }

    /**
     * Checks if notifications are enabled.
     * Always returns true in Tester Mode.
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Checks if system overlay (draw over other apps) permission is granted.
     * Always returns true in Tester Mode.
     */
    fun canDrawOverlays(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks if Usage Stats access is granted.
     * Always returns true in Tester Mode.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return AppBlockHelper.hasUsageStatsPermission(context)
    }

    /**
     * Checks if Accessibility Service is enabled.
     * Always returns true in Tester Mode.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return AppBlockHelper.isAccessibilityServiceEnabled(context)
    }

    /**
     * Checks if battery optimization is ignored.
     * Always returns true in Tester Mode.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Checks if exact alarm scheduling is allowed.
     * Always returns true in Tester Mode.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() ?: true
            } else {
                true
            }
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Checks if installing unknown apps is permitted.
     * Always returns true in Tester Mode.
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Checks local media permissions (Images, Video, Audio / External Storage).
     * Always returns true in Tester Mode.
     */
    fun checkLocalMediaPermissions(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                    hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO) &&
                    hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Checks if system calendar permissions (READ & WRITE) are granted.
     * Always returns true in Tester Mode.
     */
    fun hasSystemCalendarPermissions(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return hasPermission(context, Manifest.permission.READ_CALENDAR) &&
                hasPermission(context, Manifest.permission.WRITE_CALENDAR)
    }

    /**
     * Checks if system contacts permissions (READ & WRITE) are granted.
     * Always returns true in Tester Mode.
     */
    fun hasSystemContactsPermissions(context: Context): Boolean {
        if (isTesterMode(context)) return true
        return hasPermission(context, Manifest.permission.READ_CONTACTS) &&
                hasPermission(context, Manifest.permission.WRITE_CONTACTS)
    }
}
