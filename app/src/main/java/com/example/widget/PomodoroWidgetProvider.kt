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

class PomodoroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updatePomodoroWidget(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updatePomodoroWidget(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return

        // Native chronometer countdown runs directly inside launcher process without needing TIME_TICK IPC
        if (action == Intent.ACTION_TIME_TICK) {
            return
        }

        Log.d("PomodoroWidgetProvider", "Pomo widget received action: $action")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FocusTimerManager.init(context)
                when (action) {
                    "com.example.widget.ACTION_POMO_START_PAUSE" -> {
                        val isTimerRunning = FocusTimerManager.isTimerRunning.value
                        val isPaused = FocusTimerManager.isPaused.value
                        val wasStartedFromStopwatch = FocusTimerManager.wasStartedFromStopwatch.value

                        if (isTimerRunning && !isPaused && !wasStartedFromStopwatch) {
                            FocusTimerManager.pauseTimer(context)
                        } else {
                            if (wasStartedFromStopwatch) {
                                FocusTimerManager.resetStopwatch(context, saveSession = true)
                                FocusTimerManager.startTimer(context, isResuming = false)
                            } else {
                                val isPausedOrMidSession = isPaused || FocusTimerManager.accumulatedSessionTimeMs.value > 0L
                                FocusTimerManager.startTimer(context, isResuming = isPausedOrMidSession)
                            }
                        }
                        WidgetManager.updatePomodoroWidget(context)
                        WidgetManager.updateStopwatchWidget(context)
                    }
                    "com.example.widget.ACTION_POMO_BREAK" -> {
                        if (FocusTimerManager.isFocusPhase.value) {
                            FocusTimerManager.takeBreakFromPomodoro(context)
                        } else {
                            FocusTimerManager.skipOrEndBreak(context)
                        }
                        WidgetManager.updatePomodoroWidget(context)
                        WidgetManager.updateStopwatchWidget(context)
                    }
                    "com.example.widget.ACTION_POMO_RESET" -> {
                        FocusTimerManager.resetTimer(context, saveSession = true)
                        WidgetManager.updatePomodoroWidget(context)
                        WidgetManager.updateStopwatchWidget(context)
                    }
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        WidgetManager.updatePomodoroWidget(context, isPartialUpdate = true)
                    }
                }
            } catch (e: Throwable) {
                Log.e("PomodoroWidgetProvider", "Error handling action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
