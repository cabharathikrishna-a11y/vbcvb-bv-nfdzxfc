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

class TimerStopwatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updateStopwatchWidget(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updateStopwatchWidget(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        
        // Native chronometer runs directly inside launcher process without needing TIME_TICK IPC
        if (action == Intent.ACTION_TIME_TICK) {
            return
        }

        Log.d("TimerStopwatchWidget", "Widget received broadcast action: $action")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FocusTimerManager.init(context)
                when (action) {
                    "com.example.widget.ACTION_STOPWATCH_START_PAUSE" -> {
                        val isStopwatchActive = FocusTimerManager.isStopwatchActive.value
                        val isPaused = FocusTimerManager.isPaused.value
                        val wasStartedFromStopwatch = FocusTimerManager.wasStartedFromStopwatch.value
                        val isTimerRunning = FocusTimerManager.isTimerRunning.value

                        if (isStopwatchActive && !isPaused) {
                            FocusTimerManager.pauseStopwatch(context)
                        } else {
                            if (isTimerRunning || (!wasStartedFromStopwatch && FocusTimerManager.accumulatedSessionTimeMs.value > 0L)) {
                                FocusTimerManager.resetTimer(context, saveSession = true)
                                FocusTimerManager.startStopwatch(context, isResuming = false)
                            } else {
                                val isPausedOrMidSession = isPaused || FocusTimerManager.accumulatedSessionTimeMs.value > 0L || FocusTimerManager.stopwatchSeconds.value > 0 || FocusTimerManager.cumulativeSessionFocusSeconds.value > 0
                                FocusTimerManager.startStopwatch(context, isResuming = isPausedOrMidSession)
                            }
                        }
                        WidgetManager.updateStopwatchWidget(context)
                        WidgetManager.updatePomodoroWidget(context)
                    }
                    "com.example.widget.ACTION_STOPWATCH_BREAK" -> {
                        FocusTimerManager.takeBreakFromStopwatch(context)
                        WidgetManager.updateStopwatchWidget(context)
                        WidgetManager.updatePomodoroWidget(context)
                    }
                    "com.example.widget.ACTION_STOPWATCH_RESET" -> {
                        FocusTimerManager.resetStopwatch(context, saveSession = true)
                        WidgetManager.updateStopwatchWidget(context)
                        WidgetManager.updatePomodoroWidget(context)
                    }
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        WidgetManager.updateStopwatchWidget(context, isPartialUpdate = true)
                    }
                }
            } catch (e: Throwable) {
                Log.e("TimerStopwatchWidget", "Error handling action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
