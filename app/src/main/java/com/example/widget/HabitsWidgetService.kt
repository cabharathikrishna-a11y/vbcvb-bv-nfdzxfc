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
import com.example.data.Habit
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HabitsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return HabitsRemoteViewsFactory(this.applicationContext, intent)
    }
}

class HabitsRemoteViewsFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var items: List<Habit> = emptyList()
    private var completedHabitIds: Set<Int> = emptySet()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    override fun onDestroy() {
        items = emptyList()
        completedHabitIds = emptySet()
    }

    override fun getCount(): Int = items.size

    private fun loadData() {
        try {
            val db = AppDatabase.getInstance(context)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = sdf.format(Date())
            val cal = Calendar.getInstance()
            val todayDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

            runBlocking {
                try {
                    val allHabits = db.habitDao().getAllHabitsDirect()
                    val completionsToday = db.habitDao().getCompletionsForDateDirect(todayStr)
                    completedHabitIds = completionsToday.map { it.habitId }.toSet()

                    // Filter only habits that are scheduled for today
                    items = allHabits.filter { habit ->
                        val freq = habit.frequency.uppercase()
                        when (freq) {
                            "DAILY" -> true
                            "WEEKLY" -> habit.weeklyDay == todayDayOfWeek
                            "MONTHLY", "MONTHLY_ONCE" -> {
                                val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                                dayOfMonth in habit.monthlyStartDate..habit.monthlyEndDate
                            }
                            else -> true
                        }
                    }.sortedWith(compareBy({ completedHabitIds.contains(it.id) }, { it.orderIndex }, { it.id }))
                } catch (e: Exception) {
                    Log.e("HabitsWidgetService", "Error loading habits", e)
                    items = emptyList()
                    completedHabitIds = emptySet()
                }
            }
        } catch (e: Exception) {
            Log.e("HabitsWidgetService", "Error in loadData", e)
            items = emptyList()
        }
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null
        val habit = items[position]
        val isCompleted = completedHabitIds.contains(habit.id)

        val views = RemoteViews(context.packageName, R.layout.widget_habits_item)

        // Habit title with strike-through if completed
        val habitName = habit.name.ifEmpty { "Untitled Habit" }
        if (isCompleted) {
            val span = SpannableString(habitName)
            span.setSpan(StrikethroughSpan(), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            views.setTextViewText(R.id.item_habit_title, span)
            views.setTextColor(R.id.item_habit_title, Color.parseColor("#94A3B8"))
            views.setInt(R.id.btn_habit_toggle, "setBackgroundResource", R.drawable.bg_widget_check_active)
        } else {
            views.setTextViewText(R.id.item_habit_title, habitName)
            views.setTextColor(R.id.item_habit_title, Color.parseColor("#FFFFFF"))
            views.setInt(R.id.btn_habit_toggle, "setBackgroundResource", R.drawable.bg_widget_check_inactive)
        }

        // Subtitle: Time & Category
        val subParts = mutableListOf<String>()
        if (habit.timeOfDay.isNotEmpty()) subParts.add(habit.timeOfDay)
        if (habit.listCategory.isNotEmpty()) subParts.add(habit.listCategory)
        if (subParts.isEmpty()) subParts.add("Daily")
        views.setTextViewText(R.id.item_habit_subtitle, subParts.joinToString(" · "))

        // Streak count
        views.setTextViewText(R.id.item_habit_streak_text, "${habit.streakCount}d")

        // 1. Fill-in intent for interactive checkbox click (Ticks off right in widget without opening app)
        val toggleFillIn = Intent().apply {
            putExtra(HabitsWidgetProvider.EXTRA_ACTION_TYPE, HabitsWidgetProvider.ACTION_TYPE_TOGGLE)
            putExtra(HabitsWidgetProvider.EXTRA_HABIT_ID, habit.id)
            putExtra(HabitsWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        }
        views.setOnClickFillInIntent(R.id.btn_habit_toggle, toggleFillIn)

        // 2. Fill-in intent for tapping the habit row (Opens app to Habits screen)
        val openFillIn = Intent().apply {
            putExtra(HabitsWidgetProvider.EXTRA_ACTION_TYPE, HabitsWidgetProvider.ACTION_TYPE_OPEN)
            putExtra(HabitsWidgetProvider.EXTRA_HABIT_ID, habit.id)
            putExtra(HabitsWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        }
        views.setOnClickFillInIntent(R.id.habit_item_click_area, openFillIn)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return if (position in items.indices) items[position].id.toLong() else position.toLong()
    }

    override fun hasStableIds(): Boolean = true
}
