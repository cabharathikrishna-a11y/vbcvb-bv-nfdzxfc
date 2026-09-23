package com.example.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Task
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TasksRemoteViewsFactory(this.applicationContext, intent)
    }
}

class TasksRemoteViewsFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var items: List<Task> = emptyList()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    private fun loadData() {
        try {
            val db = AppDatabase.getInstance(context)
            val allTasks = runBlocking {
                try {
                    db.taskDao().getAllTasksDirect()
                } catch (e: Exception) {
                    Log.e("TasksWidgetService", "Error loading tasks", e)
                    emptyList()
                }
            }

            val config = TasksWidgetPreferences.loadConfig(context, appWidgetId)

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = sdf.format(Date())
            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayTime = todayCal.timeInMillis

            // 1. Filter completed
            var filtered = if (!config.showCompleted) {
                allTasks.filter { !it.isCompleted }
            } else {
                allTasks
            }

            // 2. Filter folder / listCategory
            if (!config.folder.equals("ALL", ignoreCase = true)) {
                filtered = filtered.filter { it.listCategory.equals(config.folder, ignoreCase = true) }
            }

            // 3. Filter by date range
            val maxDaysAhead = when (config.dateFilter) {
                TaskDateFilter.TODAY -> 0
                TaskDateFilter.NEXT_3_DAYS -> 3
                TaskDateFilter.NEXT_7_DAYS -> 7
                TaskDateFilter.NEXT_30_DAYS -> 30
                TaskDateFilter.ALL -> null
            }

            if (maxDaysAhead != null) {
                val maxLimitCal = Calendar.getInstance().apply {
                    timeInMillis = todayTime
                    add(Calendar.DAY_OF_YEAR, maxDaysAhead)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                }
                val maxLimitTime = maxLimitCal.timeInMillis

                filtered = filtered.filter { task ->
                    if (task.dueDateString.isEmpty()) {
                        // Include no-date tasks if config allows or user has them enabled
                        true
                    } else {
                        try {
                            val taskDate = sdf.parse(task.dueDateString)
                            if (taskDate != null) {
                                val taskCal = Calendar.getInstance().apply {
                                    time = taskDate
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val taskTime = taskCal.timeInMillis
                                // Overdue (before today) or within the maxLimit window
                                taskTime <= maxLimitTime
                            } else {
                                true
                            }
                        } catch (e: Exception) {
                            true
                        }
                    }
                }
            }

            // 4. Sort: No date tasks at last or inline
            items = if (config.noDateTasksAtLast) {
                val withDate = filtered.filter { it.dueDateString.isNotEmpty() }
                    .sortedWith(compareBy({ it.dueDateString }, { it.orderIndex }, { -it.id }))
                val noDate = filtered.filter { it.dueDateString.isEmpty() }
                    .sortedWith(compareBy({ it.orderIndex }, { -it.id }))
                withDate + noDate
            } else {
                filtered.sortedWith(compareBy(
                    { if (it.dueDateString.isEmpty()) "9999-99-99" else it.dueDateString },
                    { it.orderIndex },
                    { -it.id }
                ))
            }
        } catch (e: Exception) {
            Log.e("TasksWidgetService", "Error in loadData", e)
            items = emptyList()
        }
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null
        val task = items[position]

        val views = RemoteViews(context.packageName, R.layout.widget_tasks_item)

        // Title text and strikethrough for completed
        if (task.isCompleted) {
            val span = SpannableString(task.title.ifEmpty { "Untitled Task" })
            span.setSpan(StrikethroughSpan(), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            views.setTextViewText(R.id.item_task_title, span)
            views.setTextColor(R.id.item_task_title, Color.parseColor("#94A3B8"))
            views.setInt(R.id.btn_task_toggle, "setBackgroundResource", R.drawable.bg_widget_check_active)
        } else {
            views.setTextViewText(R.id.item_task_title, task.title.ifEmpty { "Untitled Task" })
            views.setTextColor(R.id.item_task_title, Color.parseColor("#FFFFFF"))
            views.setInt(R.id.btn_task_toggle, "setBackgroundResource", R.drawable.bg_widget_check_inactive)
        }

        // Due date badge formatting
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = sdf.format(Date())
        if (task.dueDateString.isNotEmpty()) {
            views.setViewVisibility(R.id.item_task_due, View.VISIBLE)
            try {
                val taskDate = sdf.parse(task.dueDateString)
                val todayDate = sdf.parse(todayStr)
                if (taskDate != null && todayDate != null) {
                    val diffDays = ((taskDate.time - todayDate.time) / (24 * 3600 * 1000L)).toInt()
                    when {
                        diffDays < 0 -> {
                            views.setTextViewText(R.id.item_task_due, "Overdue")
                            views.setTextColor(R.id.item_task_due, Color.parseColor("#EF4444"))
                        }
                        diffDays == 0 -> {
                            views.setTextViewText(R.id.item_task_due, "Today")
                            views.setTextColor(R.id.item_task_due, Color.parseColor("#38BDF8"))
                        }
                        diffDays == 1 -> {
                            views.setTextViewText(R.id.item_task_due, "Tomorrow")
                            views.setTextColor(R.id.item_task_due, Color.parseColor("#38BDF8"))
                        }
                        diffDays in 2..7 -> {
                            views.setTextViewText(R.id.item_task_due, "In ${diffDays}d")
                            views.setTextColor(R.id.item_task_due, Color.parseColor("#A78BFA"))
                        }
                        else -> {
                            val displayFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
                            views.setTextViewText(R.id.item_task_due, displayFmt.format(taskDate))
                            views.setTextColor(R.id.item_task_due, Color.parseColor("#94A3B8"))
                        }
                    }
                } else {
                    views.setTextViewText(R.id.item_task_due, task.dueDateString)
                    views.setTextColor(R.id.item_task_due, Color.parseColor("#94A3B8"))
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.item_task_due, task.dueDateString)
                views.setTextColor(R.id.item_task_due, Color.parseColor("#94A3B8"))
            }
        } else {
            views.setViewVisibility(R.id.item_task_due, View.GONE)
        }

        // Folder badge
        if (task.listCategory.isNotEmpty()) {
            views.setViewVisibility(R.id.item_task_folder, View.VISIBLE)
            views.setTextViewText(R.id.item_task_folder, "📁 ${task.listCategory}")
        } else {
            views.setViewVisibility(R.id.item_task_folder, View.GONE)
        }

        // Priority badge
        if (task.priority.equals("HIGH", ignoreCase = true)) {
            views.setViewVisibility(R.id.item_task_priority, View.VISIBLE)
            views.setTextViewText(R.id.item_task_priority, "HIGH")
            views.setTextColor(R.id.item_task_priority, Color.parseColor("#EF4444"))
        } else {
            views.setViewVisibility(R.id.item_task_priority, View.GONE)
        }

        // 1. Fill-in intent for interactive checkbox click (Ticks off right in widget without opening app)
        val toggleFillIn = Intent().apply {
            putExtra(TasksWidgetProvider.EXTRA_ACTION_TYPE, TasksWidgetProvider.ACTION_TYPE_TOGGLE)
            putExtra(TasksWidgetProvider.EXTRA_TASK_ID, task.id)
            putExtra(TasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        }
        views.setOnClickFillInIntent(R.id.btn_task_toggle, toggleFillIn)

        // 2. Fill-in intent for tapping the task row (Opens app to Tasks screen)
        val openFillIn = Intent().apply {
            putExtra(TasksWidgetProvider.EXTRA_ACTION_TYPE, TasksWidgetProvider.ACTION_TYPE_OPEN)
            putExtra(TasksWidgetProvider.EXTRA_TASK_ID, task.id)
            putExtra(TasksWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        }
        views.setOnClickFillInIntent(R.id.task_item_click_area, openFillIn)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return if (position in items.indices) items[position].id.toLong() else position.toLong()
    }

    override fun hasStableIds(): Boolean = true
}
