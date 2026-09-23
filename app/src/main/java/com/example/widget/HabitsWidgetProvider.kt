package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HabitsWidgetProvider : AppWidgetProvider() {

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
            ACTION_LIST_ITEM_CLICK -> {
                val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE)
                val habitId = intent.getIntExtra(EXTRA_HABIT_ID, -1)

                if (actionType == ACTION_TYPE_TOGGLE && habitId != -1) {
                    // Toggle habit completion for today directly in background without opening app
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

                            updateAllHabitsWidgets(context)
                            SingleHabitWidgetProvider.updateAllSingleHabitWidgets(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error toggling habit from widget: ${e.message}", e)
                        }
                    }
                } else if (actionType == ACTION_TYPE_OPEN) {
                    try {
                        val openAppIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("NAVIGATE_TO", "HABITS")
                            putExtra("OPEN_HABIT_ID", habitId)
                        }
                        context.startActivity(openAppIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app from habit row: ${e.message}", e)
                    }
                }
            }

            ACTION_REFRESH_HABITS,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                updateAllHabitsWidgets(context)
            }
        }
    }

    companion object {
        private const val TAG = "HabitsWidgetProvider"
        private val providerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        const val ACTION_LIST_ITEM_CLICK = "com.example.widget.ACTION_HABIT_LIST_ITEM_CLICK"
        const val ACTION_REFRESH_HABITS = "com.example.widget.ACTION_REFRESH_HABITS"

        const val EXTRA_ACTION_TYPE = "EXTRA_ACTION_TYPE"
        const val ACTION_TYPE_TOGGLE = "ACTION_TYPE_TOGGLE"
        const val ACTION_TYPE_OPEN = "ACTION_TYPE_OPEN"

        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_WIDGET_ID = "EXTRA_WIDGET_ID"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_habits)

                val bgRes = WidgetManager.getBackgroundDrawableRes(context)
                views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

                // Adapter Service Intent
                val serviceIntent = Intent(context, HabitsWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.habits_widget_list, serviceIntent)
                views.setEmptyView(R.id.habits_widget_list, R.id.habits_empty_view)

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                // PendingIntent Template for List items
                val itemClickIntent = Intent(context, HabitsWidgetProvider::class.java).apply {
                    action = ACTION_LIST_ITEM_CLICK
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val itemClickPendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    itemClickIntent,
                    flags
                )
                views.setPendingIntentTemplate(R.id.habits_widget_list, itemClickPendingIntent)

                // Refresh button
                val refreshIntent = Intent(context, HabitsWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH_HABITS
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 100 + 1,
                    refreshIntent,
                    flags
                )
                views.setOnClickPendingIntent(R.id.btn_habits_refresh, refreshPendingIntent)

                // Update count badge asynchronously
                providerScope.launch(Dispatchers.IO) {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val todayStr = sdf.format(Date())
                        val cal = Calendar.getInstance()
                        val todayDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

                        val allHabits = db.habitDao().getAllHabitsDirect()
                        val completionsToday = db.habitDao().getCompletionsForDateDirect(todayStr).map { it.habitId }.toSet()

                        val todayHabits = allHabits.filter { habit ->
                            when (habit.frequency.uppercase()) {
                                "DAILY" -> true
                                "WEEKLY" -> habit.weeklyDay == todayDayOfWeek
                                "MONTHLY", "MONTHLY_ONCE" -> {
                                    val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                                    dayOfMonth in habit.monthlyStartDate..habit.monthlyEndDate
                                }
                                else -> true
                            }
                        }

                        val completedCount = todayHabits.count { completionsToday.contains(it.id) }
                        val totalCount = todayHabits.size

                        val badgeViews = RemoteViews(context.packageName, R.layout.widget_habits)
                        badgeViews.setTextViewText(R.id.habits_progress_badge, "$completedCount/$totalCount")
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, badgeViews)
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.habits_widget_list)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating habits widget $appWidgetId: ${e.message}", e)
            }
        }

        fun updateAllHabitsWidgets(context: Context) {
            providerScope.launch(Dispatchers.IO) {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                    val thisWidget = ComponentName(context, HabitsWidgetProvider::class.java)
                    val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    if (allWidgetIds.isEmpty()) return@launch

                    for (widgetId in allWidgetIds) {
                        updateWidget(context, appWidgetManager, widgetId)
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.habits_widget_list)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateAllHabitsWidgets: ${e.message}", e)
                }
            }
        }
    }
}
