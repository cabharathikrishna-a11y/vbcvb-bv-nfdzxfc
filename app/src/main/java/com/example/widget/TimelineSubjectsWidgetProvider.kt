package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.util.FocusTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for the Timeline + Subject-Wise Details Home Screen Widget.
 * Displays a 24-hour chronological visual timeline bar of today's focus sessions
 * and detailed subject-wise breakdown of focused session subjects in total below the timeline.
 */
class TimelineSubjectsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updateTimelineSubjectsWidget(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updateTimelineSubjectsWidget(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Log.d("TimelineSubjectsWidget", "Widget received action: $action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FocusTimerManager.init(context)
                when (action) {
                    "com.example.widget.ACTION_REFRESH_TIMELINE_SUBJECTS",
                    "com.example.widget.ACTION_REFRESH_TOTAL_FOCUS" -> {
                        WidgetManager.updateTimelineSubjectsWidget(context)
                    }
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        WidgetManager.updateTimelineSubjectsWidget(context, isPartialUpdate = true)
                    }
                }
            } catch (e: Throwable) {
                Log.e("TimelineSubjectsWidget", "Error handling action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
