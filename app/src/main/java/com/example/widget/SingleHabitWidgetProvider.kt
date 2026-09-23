package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.HabitCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SingleHabitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return

        when (action) {
            ACTION_TOGGLE_SINGLE_HABIT -> {
                val habitId = intent.getIntExtra(EXTRA_HABIT_ID, -1)
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

                if (habitId != -1) {
                    providerScope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getInstance(context)
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            val todayStr = sdf.format(Date())

                            val existing = db.habitDao().getCompletionDirect(habitId, todayStr)
                            val habit = db.habitDao().getHabitById(habitId)

                            if (existing != null) {
                                db.habitDao().deleteCompletion(habitId, todayStr)
                            } else {
                                db.habitDao().insertCompletion(HabitCompletion(habitId = habitId, dateString = todayStr))
                            }

                            if (habit != null) {
                                val allComps = db.habitDao().getAllCompletionsDirect()
                                val newStreak = com.example.util.HabitStreakHelper.calculateStreak(habit, allComps)
                                db.habitDao().updateHabit(
                                    habit.copy(
                                        streakCount = newStreak,
                                        lastCompletedTimestamp = if (existing == null) System.currentTimeMillis() else habit.lastCompletedTimestamp
                                    )
                                )
                            }

                            updateAllSingleHabitWidgets(context)
                            HabitsWidgetProvider.updateAllHabitsWidgets(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error toggling single habit: ${e.message}", e)
                        }
                    }
                }
            }

            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                updateAllSingleHabitWidgets(context)
            }
        }
    }

    companion object {
        private const val TAG = "SingleHabitWidget"
        private val providerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        const val ACTION_TOGGLE_SINGLE_HABIT = "com.example.widget.ACTION_TOGGLE_SINGLE_HABIT"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_WIDGET_ID = "EXTRA_WIDGET_ID"

        private const val PREFS_NAME = "single_habit_widget_prefs"
        private const val KEY_HABIT_ID = "habit_id_"

        fun getSavedHabitId(context: Context, appWidgetId: Int): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_HABIT_ID + appWidgetId, -1)
        }

        fun saveHabitId(context: Context, appWidgetId: Int, habitId: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_HABIT_ID + appWidgetId, habitId)
                .apply()
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            providerScope.launch(Dispatchers.IO) {
                try {
                    val views = RemoteViews(context.packageName, R.layout.widget_single_habit)
                    val bgRes = WidgetManager.getBackgroundDrawableRes(context)
                    views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

                    val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    // Settings intent to pick habit
                    val configIntent = Intent(context, SingleHabitWidgetConfigActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                    val configPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId * 100 + 1,
                        configIntent,
                        pendingFlags
                    )
                    views.setOnClickPendingIntent(R.id.btn_single_habit_settings, configPendingIntent)

                    // Open habits app on title click
                    val openAppIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("NAVIGATE_TO", "HABITS")
                    }
                    val openAppPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId * 100 + 2,
                        openAppIntent,
                        pendingFlags
                    )
                    views.setOnClickPendingIntent(R.id.single_habit_content_area, openAppPendingIntent)

                    val db = AppDatabase.getInstance(context)
                    var targetHabitId = getSavedHabitId(context, appWidgetId)

                    val allHabits = db.habitDao().getAllHabitsDirect()
                    val targetHabit = if (targetHabitId != -1) {
                        allHabits.firstOrNull { it.id == targetHabitId }
                    } else {
                        val firstHabit = allHabits.firstOrNull()
                        if (firstHabit != null) {
                            saveHabitId(context, appWidgetId, firstHabit.id)
                            firstHabit
                        } else null
                    }

                    if (targetHabit != null) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val todayStr = sdf.format(Date())
                        val isCompletedToday = db.habitDao().getCompletionDirect(targetHabit.id, todayStr) != null

                        views.setTextViewText(R.id.single_habit_title, targetHabit.name.ifEmpty { "Untitled Habit" })

                        val subParts = mutableListOf<String>()
                        if (targetHabit.timeOfDay.isNotEmpty()) subParts.add(targetHabit.timeOfDay)
                        if (targetHabit.listCategory.isNotEmpty()) subParts.add(targetHabit.listCategory)
                        if (subParts.isEmpty()) subParts.add("Daily")
                        views.setTextViewText(R.id.single_habit_subtitle, subParts.joinToString(" · "))

                        views.setTextViewText(R.id.single_habit_streak_text, "${targetHabit.streakCount}d")
                        views.setViewVisibility(R.id.single_habit_streak_pill, View.VISIBLE)

                        if (isCompletedToday) {
                            views.setTextViewText(R.id.single_habit_status_text, "Completed today! 🎉")
                            views.setTextColor(R.id.single_habit_status_text, Color.parseColor("#4ADE80"))
                            views.setInt(R.id.btn_single_habit_toggle, "setBackgroundResource", R.drawable.bg_widget_check_active)
                        } else {
                            views.setTextViewText(R.id.single_habit_status_text, "Tap to complete today")
                            views.setTextColor(R.id.single_habit_status_text, Color.parseColor("#94A3B8"))
                            views.setInt(R.id.btn_single_habit_toggle, "setBackgroundResource", R.drawable.bg_widget_check_inactive)
                        }

                        // Toggle button pending intent (interactive without opening app)
                        val toggleIntent = Intent(context, SingleHabitWidgetProvider::class.java).apply {
                            action = ACTION_TOGGLE_SINGLE_HABIT
                            putExtra(EXTRA_HABIT_ID, targetHabit.id)
                            putExtra(EXTRA_WIDGET_ID, appWidgetId)
                        }
                        val togglePendingIntent = PendingIntent.getBroadcast(
                            context,
                            appWidgetId * 100 + 3,
                            toggleIntent,
                            pendingFlags
                        )
                        views.setOnClickPendingIntent(R.id.btn_single_habit_toggle, togglePendingIntent)
                    } else {
                        views.setTextViewText(R.id.single_habit_title, "Select a Habit")
                        views.setTextViewText(R.id.single_habit_subtitle, "Tap settings ⚙ to select")
                        views.setViewVisibility(R.id.single_habit_streak_pill, View.GONE)
                        views.setTextViewText(R.id.single_habit_status_text, "No habit selected")
                    }

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating single habit widget $appWidgetId: ${e.message}", e)
                }
            }
        }

        fun updateAllSingleHabitWidgets(context: Context) {
            providerScope.launch(Dispatchers.IO) {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                    val thisWidget = ComponentName(context, SingleHabitWidgetProvider::class.java)
                    val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    if (allWidgetIds.isEmpty()) return@launch

                    for (widgetId in allWidgetIds) {
                        updateWidget(context, appWidgetManager, widgetId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateAllSingleHabitWidgets: ${e.message}", e)
                }
            }
        }
    }
}
