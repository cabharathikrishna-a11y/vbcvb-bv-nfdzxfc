package com.example.widget

import android.content.Context
import android.content.SharedPreferences

enum class TaskDateFilter(val displayName: String, val shortName: String) {
    TODAY("Today", "TODAY"),
    NEXT_3_DAYS("Next 3 Days", "3 DAYS"),
    NEXT_7_DAYS("Next 7 Days", "7 DAYS"),
    NEXT_30_DAYS("Next 30 Days", "30 DAYS"),
    ALL("All Tasks", "ALL");

    fun next(): TaskDateFilter {
        val vals = values()
        return vals[(ordinal + 1) % vals.size]
    }

    companion object {
        fun fromString(value: String): TaskDateFilter {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                TODAY
            }
        }
    }
}

data class TaskWidgetConfig(
    val dateFilter: TaskDateFilter = TaskDateFilter.TODAY,
    val showCompleted: Boolean = false,
    val folder: String = "ALL", // "ALL" or specific category name
    val noDateTasksAtLast: Boolean = true,
    val glassStyle: String = "black_glass"
)

object TasksWidgetPreferences {
    private const val PREFS_NAME = "tasks_widget_prefs"
    private const val KEY_DATE_FILTER = "date_filter_"
    private const val KEY_SHOW_COMPLETED = "show_completed_"
    private const val KEY_FOLDER = "folder_"
    private const val KEY_NO_DATE_LAST = "no_date_last_"
    private const val KEY_GLASS_STYLE = "glass_style_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadConfig(context: Context, appWidgetId: Int): TaskWidgetConfig {
        val prefs = getPrefs(context)
        val defaultGlass = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("widget_glass_style", "black_glass") ?: "black_glass"

        val filterStr = prefs.getString(KEY_DATE_FILTER + appWidgetId, null)
            ?: prefs.getString(KEY_DATE_FILTER + "global", TaskDateFilter.TODAY.name)
            ?: TaskDateFilter.TODAY.name
        val showCompleted = prefs.getBoolean(KEY_SHOW_COMPLETED + appWidgetId, prefs.getBoolean(KEY_SHOW_COMPLETED + "global", false))
        val folder = prefs.getString(KEY_FOLDER + appWidgetId, prefs.getString(KEY_FOLDER + "global", "ALL")) ?: "ALL"
        val noDateLast = prefs.getBoolean(KEY_NO_DATE_LAST + appWidgetId, prefs.getBoolean(KEY_NO_DATE_LAST + "global", true))
        val glassStyle = prefs.getString(KEY_GLASS_STYLE + appWidgetId, defaultGlass) ?: defaultGlass

        return TaskWidgetConfig(
            dateFilter = TaskDateFilter.fromString(filterStr),
            showCompleted = showCompleted,
            folder = folder,
            noDateTasksAtLast = noDateLast,
            glassStyle = glassStyle
        )
    }

    fun saveConfig(context: Context, appWidgetId: Int, config: TaskWidgetConfig) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_DATE_FILTER + appWidgetId, config.dateFilter.name)
            .putBoolean(KEY_SHOW_COMPLETED + appWidgetId, config.showCompleted)
            .putString(KEY_FOLDER + appWidgetId, config.folder)
            .putBoolean(KEY_NO_DATE_LAST + appWidgetId, config.noDateTasksAtLast)
            .putString(KEY_GLASS_STYLE + appWidgetId, config.glassStyle)
            // Also store as global fallback
            .putString(KEY_DATE_FILTER + "global", config.dateFilter.name)
            .putBoolean(KEY_SHOW_COMPLETED + "global", config.showCompleted)
            .putString(KEY_FOLDER + "global", config.folder)
            .putBoolean(KEY_NO_DATE_LAST + "global", config.noDateTasksAtLast)
            .apply()
    }

    fun cycleDateFilter(context: Context, appWidgetId: Int): TaskDateFilter {
        val current = loadConfig(context, appWidgetId)
        val nextFilter = current.dateFilter.next()
        saveConfig(context, appWidgetId, current.copy(dateFilter = nextFilter))
        return nextFilter
    }
}
