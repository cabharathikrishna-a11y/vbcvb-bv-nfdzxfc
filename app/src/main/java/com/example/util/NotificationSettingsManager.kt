package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.api.UserSettingsSyncEngine

object NotificationSettingsManager {
    private const val TAG = "NotifSettingsManager"
    private const val PREFS_NAME = "app_prefs"
    private const val APP_SETTINGS = "app_settings"

    // Master switch
    const val KEY_MASTER_NOTIFICATIONS = "master_notifications_enabled"

    // Social & Friends
    const val KEY_FRIEND_FOCUS = "friend_focus_notifications_enabled"
    const val KEY_PEER_NUDGE = "peer_nudge_notifications_enabled"
    const val KEY_CHAT_MESSAGES = "chat_notifications_enabled"
    const val KEY_FCM_CLOUD = "fcm_cloud_notifications_enabled"

    // Timers & Productivity
    const val KEY_TIMER_END = "timer_end_notifications_enabled"
    const val KEY_FOCUS_FOREGROUND = "focus_foreground_notifications_enabled"

    // Tasks & Habits
    const val KEY_TASK_REMINDERS = "task_reminders_enabled"
    const val KEY_ALL_DAY_TASKS = "all_day_notification_enabled"
    const val KEY_HABIT_REMINDERS = "habit_reminders_enabled"
    const val KEY_COUNTDOWN_ALERTS = "countdown_notifications_enabled"

    // Wellness & Smart Nudges
    const val KEY_STUDY_MOTIVATION = "study_motivation_notifications_enabled"
    const val KEY_WATER_REMINDERS = "water_reminder_enabled"
    const val KEY_BEDTIME_REMINDER = "bedtime_reminder_enabled"
    const val KEY_WAKEUP_ALARM = "wakeup_alarm_enabled"
    const val KEY_ON_THIS_DAY = "on_this_day_notification_enabled"

    data class NotificationItemConfig(
        val key: String,
        val title: String,
        val description: String,
        val category: String,
        val defaultEnabled: Boolean = true,
        val channelId: String = "general_notifications"
    )

    val ALL_CONFIGS = listOf(
        // Category: Friends & Study Group
        NotificationItemConfig(
            key = KEY_FRIEND_FOCUS,
            title = "Study Group & Peer Focus Alerts",
            description = "Notify when a friend or study buddy starts a live focus session.",
            category = "Friends & Social",
            channelId = "peer_focus_channel"
        ),
        NotificationItemConfig(
            key = KEY_PEER_NUDGE,
            title = "Peer Nudges & Salutes",
            description = "Instant alerts when study partners ring your bell or send a study salute.",
            category = "Friends & Social",
            channelId = "bell_ring_channel"
        ),
        NotificationItemConfig(
            key = KEY_CHAT_MESSAGES,
            title = "Chat & Direct Messages",
            description = "Incoming message notifications and automated assistant replies.",
            category = "Friends & Social",
            channelId = "chat_messages_channel"
        ),
        NotificationItemConfig(
            key = KEY_FCM_CLOUD,
            title = "Firebase Cloud & Multi-Device Sync",
            description = "Push notifications for remote data updates and device triggers.",
            category = "Friends & Social",
            channelId = "fcm_default_channel"
        ),

        // Category: Productivity & Timers
        NotificationItemConfig(
            key = KEY_TIMER_END,
            title = "Focus & Pomodoro Session Finish",
            description = "Strong chime and notification alert when your focus or break timer completes.",
            category = "Timers & Productivity",
            channelId = "timer_alerts"
        ),
        NotificationItemConfig(
            key = KEY_FOCUS_FOREGROUND,
            title = "Active Timer Status Bar",
            description = "Persistent status bar countdown while a focus session is in progress.",
            category = "Timers & Productivity",
            channelId = "focus_foreground_channel"
        ),

        // Category: Tasks & Planning
        NotificationItemConfig(
            key = KEY_TASK_REMINDERS,
            title = "Task Due Reminders & Timed Alarms",
            description = "Scheduled alert triggers and notifications for high/medium priority tasks.",
            category = "Tasks & Planning",
            channelId = "task_reminders"
        ),
        NotificationItemConfig(
            key = KEY_ALL_DAY_TASKS,
            title = "Morning All-Day Tasks Summary",
            description = "Daily morning briefing summarizing all pending tasks scheduled for today.",
            category = "Tasks & Planning",
            channelId = "task_reminders"
        ),
        NotificationItemConfig(
            key = KEY_HABIT_REMINDERS,
            title = "Habits & Daily Streak Nudges",
            description = "Reminders to check in and preserve your active habit streaks.",
            category = "Tasks & Planning",
            channelId = "task_reminders"
        ),
        NotificationItemConfig(
            key = KEY_COUNTDOWN_ALERTS,
            title = "Countdowns & Target Date Events",
            description = "Milestone notifications as your target dates and countdown events approach.",
            category = "Tasks & Planning",
            channelId = "task_reminders"
        ),

        // Category: Wellness & Life OS
        NotificationItemConfig(
            key = KEY_STUDY_MOTIVATION,
            title = "Smart Study Motivation Nudges",
            description = "Automatic motivational nudges when you have been inactive for a while.",
            category = "Wellness & Life OS",
            channelId = "study_reminders"
        ),
        NotificationItemConfig(
            key = KEY_WATER_REMINDERS,
            title = "Hydration & Water Drink Reminders",
            description = "Periodic gentle reminders to stay hydrated during long work sessions.",
            category = "Wellness & Life OS",
            channelId = "water_reminder_channel"
        ),
        NotificationItemConfig(
            key = KEY_BEDTIME_REMINDER,
            title = "Bedtime & Sleep Routine Alerts",
            description = "Nighttime alerts reminding you to wind down for your target sleep schedule.",
            category = "Wellness & Life OS",
            channelId = "task_reminders"
        ),
        NotificationItemConfig(
            key = KEY_WAKEUP_ALARM,
            title = "Morning Wake-Up Alarms",
            description = "High-priority alarms and wake-up notifications to start your day.",
            category = "Wellness & Life OS",
            channelId = "task_reminders"
        ),
        NotificationItemConfig(
            key = KEY_ON_THIS_DAY,
            title = "On This Day Memory Anniversaries",
            description = "Historic memory reflections from your Life OS Journal on this exact calendar day.",
            category = "Wellness & Life OS",
            channelId = "task_reminders"
        )
    )

    fun isMasterEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MASTER_NOTIFICATIONS, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appSettings = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MASTER_NOTIFICATIONS, enabled).apply()
        appSettings.edit().putBoolean(KEY_MASTER_NOTIFICATIONS, enabled).apply()

        if (!enabled) {
            // Dismiss active notifications when master is toggled off
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancelAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel notifications on master toggle off", e)
            }
        }

        // Sync with cloud
        syncPreferences(context)
    }

    fun isEnabled(context: Context, key: String, defaultVal: Boolean = true): Boolean {
        if (!isMasterEnabled(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(key, defaultVal)
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appSettings = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, enabled).apply()
        appSettings.edit().putBoolean(key, enabled).apply()

        // Also sync special keys with their corresponding service flags
        when (key) {
            KEY_ALL_DAY_TASKS -> {
                if (enabled) {
                    AlarmScheduler.scheduleAllDayNotification(context)
                }
            }
            KEY_ON_THIS_DAY -> {
                if (enabled) {
                    AlarmScheduler.scheduleOnThisDayNotification(context)
                }
            }
            KEY_COUNTDOWN_ALERTS -> {
                context.getSharedPreferences("countdown_settings_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("notification_reminder", enabled).apply()
            }
            KEY_BEDTIME_REMINDER -> {
                if (enabled) {
                    AlarmScheduler.scheduleBedtimeReminder(context)
                }
            }
            KEY_WAKEUP_ALARM -> {
                if (enabled) {
                    AlarmScheduler.scheduleWakeUpAlarm(context)
                }
            }
        }

        syncPreferences(context)
    }

    // Direct helper methods for all callers
    fun isFriendFocusAlertEnabled(context: Context): Boolean = isEnabled(context, KEY_FRIEND_FOCUS, true)
    fun isPeerNudgeAlertEnabled(context: Context): Boolean = isEnabled(context, KEY_PEER_NUDGE, true)
    fun isChatMessagesEnabled(context: Context): Boolean = isEnabled(context, KEY_CHAT_MESSAGES, true)
    fun isGeneralFcmEnabled(context: Context): Boolean = isEnabled(context, KEY_FCM_CLOUD, true)
    fun isTimerEndAlertEnabled(context: Context): Boolean = isEnabled(context, KEY_TIMER_END, true)
    fun isFocusForegroundEnabled(context: Context): Boolean = isEnabled(context, KEY_FOCUS_FOREGROUND, true)
    fun isTaskRemindersEnabled(context: Context): Boolean = isEnabled(context, KEY_TASK_REMINDERS, true)
    fun isAllDayTasksBriefingEnabled(context: Context): Boolean = isEnabled(context, KEY_ALL_DAY_TASKS, false)
    fun isHabitRemindersEnabled(context: Context): Boolean = isEnabled(context, KEY_HABIT_REMINDERS, true)
    fun isCountdownAlertsEnabled(context: Context): Boolean = isEnabled(context, KEY_COUNTDOWN_ALERTS, true)
    fun isStudyMotivationalEnabled(context: Context): Boolean = isEnabled(context, KEY_STUDY_MOTIVATION, true)
    fun isWaterReminderEnabled(context: Context): Boolean = isEnabled(context, KEY_WATER_REMINDERS, false)
    fun isBedtimeReminderEnabled(context: Context): Boolean = isEnabled(context, KEY_BEDTIME_REMINDER, true)
    fun isWakeupAlarmEnabled(context: Context): Boolean = isEnabled(context, KEY_WAKEUP_ALARM, false)
    fun isOnThisDayHistoryEnabled(context: Context): Boolean = isEnabled(context, KEY_ON_THIS_DAY, false)

    private fun syncPreferences(context: Context) {
        val userEmail = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("user_email", "") ?: ""
        if (userEmail.isNotBlank()) {
            UserSettingsSyncEngine.pushSettingsToCloud(context, userEmail)
        }
    }

    /**
     * Sends a quick test notification for verification right in the settings UI.
     */
    fun sendTestNotification(context: Context, config: NotificationItemConfig) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "test_notification_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Notification Preview Test",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Test notifications triggered from Notification Settings"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                config.key.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Test: ${config.title}")
                .setContentText(config.description)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

            notificationManager.notify(config.key.hashCode(), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send test notification for ${config.key}", e)
        }
    }
}
