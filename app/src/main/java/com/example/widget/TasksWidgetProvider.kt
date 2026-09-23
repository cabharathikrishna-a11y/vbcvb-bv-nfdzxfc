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
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TasksWidgetProvider : AppWidgetProvider() {

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
                val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

                if (actionType == ACTION_TYPE_TOGGLE && taskId != -1) {
                    // Toggle task completion directly in background without opening app
                    providerScope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getInstance(context)
                            val task = db.taskDao().getTaskById(taskId)
                            if (task != null) {
                                val newCompleted = !task.isCompleted
                                val cleanDesc = task.description.replace(Regex("""\[CompletedAt:\s*\d+\]"""), "").trim()
                                val newDescription = if (newCompleted) {
                                    if (cleanDesc.isEmpty()) "[CompletedAt: ${System.currentTimeMillis()}]" else "$cleanDesc\n\n[CompletedAt: ${System.currentTimeMillis()}]"
                                } else {
                                    cleanDesc
                                }
                                val updatedTask = task.copy(isCompleted = newCompleted, description = newDescription)
                                db.taskDao().updateTask(updatedTask)
                                updateAllTasksWidgets(context)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error toggling task from widget: ${e.message}", e)
                        }
                    }
                } else if (actionType == ACTION_TYPE_OPEN) {
                    // Open the main app on Tasks screen
                    try {
                        val openAppIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("NAVIGATE_TO", "TASKS")
                            putExtra("OPEN_TASK_ID", taskId)
                        }
                        context.startActivity(openAppIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app from task row: ${e.message}", e)
                    }
                }
            }

            ACTION_CYCLE_DATE_FILTER -> {
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    TasksWidgetPreferences.cycleDateFilter(context, appWidgetId)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, appWidgetId)
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.tasks_widget_list)
                }
            }

            ACTION_REFRESH_TASKS,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                updateAllTasksWidgets(context)
            }
        }
    }

    companion object {
        private const val TAG = "TasksWidgetProvider"
        private val providerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        const val ACTION_LIST_ITEM_CLICK = "com.example.widget.ACTION_TASK_LIST_ITEM_CLICK"
        const val ACTION_CYCLE_DATE_FILTER = "com.example.widget.ACTION_TASK_CYCLE_DATE_FILTER"
        const val ACTION_REFRESH_TASKS = "com.example.widget.ACTION_REFRESH_TASKS"

        const val EXTRA_ACTION_TYPE = "EXTRA_ACTION_TYPE"
        const val ACTION_TYPE_TOGGLE = "ACTION_TYPE_TOGGLE"
        const val ACTION_TYPE_OPEN = "ACTION_TYPE_OPEN"

        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_WIDGET_ID = "EXTRA_WIDGET_ID"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            try {
                val config = TasksWidgetPreferences.loadConfig(context, appWidgetId)
                val views = RemoteViews(context.packageName, R.layout.widget_tasks)

                // Background Glass
                val bgRes = when (config.glassStyle.lowercase()) {
                    "clear_glass" -> R.drawable.widget_background_clear_glass
                    "dark_glass" -> R.drawable.widget_background
                    else -> R.drawable.widget_background_black_glass
                }
                views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

                // Quick Date Filter text
                views.setTextViewText(R.id.txt_task_date_filter, config.dateFilter.shortName)

                // Sub-filter indicator if custom folder or completed is shown
                val subFilterParts = mutableListOf<String>()
                if (!config.folder.equals("ALL", ignoreCase = true)) {
                    subFilterParts.add("📁 ${config.folder}")
                }
                if (config.showCompleted) {
                    subFilterParts.add("✓ Done visible")
                }
                if (config.noDateTasksAtLast) {
                    subFilterParts.add("📅 No-date at end")
                }

                if (subFilterParts.isNotEmpty()) {
                    views.setViewVisibility(R.id.tasks_sub_filter_info, View.VISIBLE)
                    views.setTextViewText(R.id.tasks_sub_filter_info, subFilterParts.joinToString(" · "))
                } else {
                    views.setViewVisibility(R.id.tasks_sub_filter_info, View.GONE)
                }

                // Adapter Service Intent
                val serviceIntent = Intent(context, TasksWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.tasks_widget_list, serviceIntent)
                views.setEmptyView(R.id.tasks_widget_list, R.id.tasks_empty_view)

                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                // PendingIntent Template for List items (Toggling checkbox or opening task)
                val itemClickIntent = Intent(context, TasksWidgetProvider::class.java).apply {
                    action = ACTION_LIST_ITEM_CLICK
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val itemClickPendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    itemClickIntent,
                    pendingFlags
                )
                views.setPendingIntentTemplate(R.id.tasks_widget_list, itemClickPendingIntent)

                // 1. Quick Date Filter cycle button
                val cycleIntent = Intent(context, TasksWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_DATE_FILTER
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val cyclePendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 100 + 1,
                    cycleIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btn_task_date_cycle, cyclePendingIntent)

                // 2. Settings button (Opens TasksWidgetConfigActivity)
                val settingsIntent = Intent(context, TasksWidgetConfigActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val settingsPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId * 100 + 2,
                    settingsIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btn_task_settings, settingsPendingIntent)

                // 3. Add Task (+) button (Opens MainActivity to Tasks screen)
                val addIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("NAVIGATE_TO", "TASKS")
                    putExtra("OPEN_ADD_TASK_DIALOG", true)
                }
                val addPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId * 100 + 3,
                    addIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btn_task_add, addPendingIntent)

                // 4. Refresh button
                val refreshIntent = Intent(context, TasksWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH_TASKS
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 100 + 4,
                    refreshIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btn_task_refresh, refreshPendingIntent)

                // Also update count badge asynchronously
                providerScope.launch(Dispatchers.IO) {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val count = db.taskDao().getAllTasksDirect().count { !it.isCompleted }
                        val badgeViews = RemoteViews(context.packageName, R.layout.widget_tasks)
                        badgeViews.setTextViewText(R.id.tasks_count_badge, "$count")
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, badgeViews)
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.tasks_widget_list)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating tasks widget $appWidgetId: ${e.message}", e)
            }
        }

        fun updateAllTasksWidgets(context: Context) {
            providerScope.launch(Dispatchers.IO) {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
                    val thisWidget = ComponentName(context, TasksWidgetProvider::class.java)
                    val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    if (allWidgetIds.isEmpty()) return@launch

                    for (widgetId in allWidgetIds) {
                        updateWidget(context, appWidgetManager, widgetId)
                        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.tasks_widget_list)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateAllTasksWidgets: ${e.message}", e)
                }
            }
        }
    }
}
