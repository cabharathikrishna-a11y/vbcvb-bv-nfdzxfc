package com.example.util

import android.content.Context
import android.util.Log
import com.example.api.FocusLogManager
import com.example.widget.WidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified Snapshot of Today's Total Focus Time.
 */
data class TodayFocusTimeSnapshot(
    val totalSeconds: Int = 0,
    val totalMillis: Long = 0L,
    val completedSeconds: Int = 0,
    val pendingSeconds: Int = 0,
    val activeSessionSeconds: Int = 0,
    val formattedHms: String = "00m 00s",       // e.g. "01h 45m 12s" or "45m 12s"
    val formattedShort: String = "0 min",       // e.g. "1 hr 45 min" or "45 min"
    val formattedDigital: String = "00:00:00",   // e.g. "01:45:12" or "45:12"
    val dateString: String = "",
    val timestampMs: Long = 0L
)

/**
 * Single Source of Truth for calculating and simultaneously broadcasting
 * Today's Total Focus Time to all 5 core subsystems:
 *  1. Widget (Glance / AppWidget via WidgetManager)
 *  2. Timer Tab (TimerView & FocusTimerManager UI)
 *  3. Friends Focus Details (Friends Leaderboard & Live Sphere)
 *  4. Timer Logs (FocusLogManager & Anomaly Checker)
 *  5. Telemetry (TimerDisplayTelemetryManager 77-day records)
 */
object TodayTotalFocusTimeManager {
    private const val TAG = "TodayTotalFocusTime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _todaySnapshot = MutableStateFlow(TodayFocusTimeSnapshot())
    val todaySnapshot: StateFlow<TodayFocusTimeSnapshot> = _todaySnapshot.asStateFlow()

    private val _todayTotalSeconds = MutableStateFlow(0)
    val todayTotalSeconds: StateFlow<Int> = _todayTotalSeconds.asStateFlow()

    private val _todayFormattedHms = MutableStateFlow("00m 00s")
    val todayFormattedHms: StateFlow<String> = _todayFormattedHms.asStateFlow()

    private var previousAnomalyCheckSecs: Int? = null

    /**
     * Formats seconds into clean readable string, e.g. "01h 45m 12s" or "45m 12s".
     */
    fun formatSecondsToHms(totalSecs: Int): String {
        val safeSecs = maxOf(0, totalSecs)
        val h = safeSecs / 3600
        val m = (safeSecs % 3600) / 60
        val s = safeSecs % 60
        return if (h > 0) {
            String.format(Locale.US, "%02dh %02dm %02ds", h, m, s)
        } else {
            String.format(Locale.US, "%02dm %02ds", m, s)
        }
    }

    /**
     * Formats seconds into short human-readable string, e.g. "1 hr 45 min" or "45 min".
     */
    fun formatSecondsToShort(totalSecs: Int): String {
        val safeSecs = maxOf(0, totalSecs)
        val h = safeSecs / 3600
        val m = (safeSecs % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            else -> "$m min"
        }
    }

    /**
     * Formats seconds into digital timer display, e.g. "01:45:12" or "45:12".
     */
    fun formatSecondsToDigital(totalSecs: Int): String {
        val safeSecs = maxOf(0, totalSecs)
        val h = safeSecs / 3600
        val m = (safeSecs % 3600) / 60
        val s = safeSecs % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /**
     * Calculates the definitive Today's Total Focus Time snapshot from state sources.
     */
    @Synchronized
    fun calculateTodaySnapshot(context: Context? = null): TodayFocusTimeSnapshot {
        val nowMs = System.currentTimeMillis()
        val todayStr = try {
            SystemTimeService.getTodayString()
        } catch (e: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        }

        // 1. Completed Records for Today + Adopted Multi-Device Time
        val records = FocusTimerManager.focusRecords.value
        val completedRecordsSecs = records.sumOf { r ->
            FocusTimerManager.getOverlapSecondsForDate(r, todayStr)
        }
        val adoptedSecs = (FocusTimerManager.adoptedTodayMs.value / 1000).toInt()
        val completedTodaySecs = completedRecordsSecs + adoptedSecs

        // 2. Pending Focus Review overlap
        val pendingReview = FocusTimerManager.pendingFocusReview.value
        val pendingSecs = pendingReview?.let { FocusTimerManager.getOverlapSecondsForDate(it, todayStr) } ?: 0

        // 3. Active Session overlap (Stopwatch or Cumulative Focus Timer)
        val isTimerOn = FocusTimerManager.isTimerRunning.value
        val isStopwatchOn = FocusTimerManager.isStopwatchActive.value
        val isPausedState = FocusTimerManager.isPaused.value
        val isFocusPhase = FocusTimerManager.isFocusPhase.value
        val isRunningOrPaused = isTimerOn || isStopwatchOn || isPausedState

        val activeSessionSecs = if (isFocusPhase && pendingReview == null && isRunningOrPaused) {
            if (FocusTimerManager.wasStartedFromStopwatch.value) {
                FocusTimerManager.stopwatchSeconds.value
            } else {
                FocusTimerManager.cumulativeSessionFocusSeconds.value
            }
        } else {
            0
        }

        val baseTotalSecs = completedTodaySecs + pendingSecs + activeSessionSecs
        val revisedTotalSecs = FocusTimerManager.globalVerificationRevisedTotalSeconds.value
        val finalTotalSecs = maxOf(0, maxOf(baseTotalSecs, revisedTotalSecs))
        val totalMs = finalTotalSecs * 1000L

        return TodayFocusTimeSnapshot(
            totalSeconds = finalTotalSecs,
            totalMillis = totalMs,
            completedSeconds = completedTodaySecs,
            pendingSeconds = pendingSecs,
            activeSessionSeconds = activeSessionSecs,
            formattedHms = formatSecondsToHms(finalTotalSecs),
            formattedShort = formatSecondsToShort(finalTotalSecs),
            formattedDigital = formatSecondsToDigital(finalTotalSecs),
            dateString = todayStr,
            timestampMs = nowMs
        )
    }

    /**
     * Primary entry point: Calculates today's total focus time and simultaneously
     * updates and broadcasts the data to all 5 subsystems.
     */
    @Synchronized
    fun recalculateAndBroadcast(context: Context? = null): TodayFocusTimeSnapshot {
        val snapshot = calculateTodaySnapshot(context)

        _todaySnapshot.value = snapshot
        _todayTotalSeconds.value = snapshot.totalSeconds
        _todayFormattedHms.value = snapshot.formattedHms

        // Broadcast to all 5 consumers simultaneously
        broadcastToAllConsumers(snapshot, context)

        return snapshot
    }

    /**
     * Synchronously broadcasts the snapshot across:
     * 1. Widget
     * 2. Timer Tab
     * 3. Friends Focus Details
     * 4. Timer Logs
     * 5. Telemetry
     */
    private fun broadcastToAllConsumers(snapshot: TodayFocusTimeSnapshot, context: Context?) {
        val appContext = context?.applicationContext

        // Consumer 1: Timer Tab Synchronous Sync
        FocusTimerManager.setGlobalTodayFocusSeconds(snapshot.totalSeconds)

        // Consumer 2: Friends Focus Details Synchronous Sync
        appContext?.let { ctx ->
            val email = com.example.api.DynamicCommandManager.activeEmail.ifEmpty {
                FocusTimerManager.getSanitizedEmail(ctx) ?: ""
            }
            if (email.isNotEmpty()) {
                scope.launch {
                    try {
                        com.example.api.DevicePresenceManager.updateDeviceFocusStatsWithExplicitTodayMs(
                            context = ctx,
                            email = email,
                            explicitTodayMs = snapshot.totalMillis
                        )
                    } catch (e: Throwable) {
                        // Non-critical if network engine initializing
                    }
                }
            }
        }

        // Consumer 3: Timer Logs & Anomaly Checker
        appContext?.let { ctx ->
            val prev = previousAnomalyCheckSecs
            if (prev != null) {
                val isTimerActive = FocusTimerManager.isFocusPhase.value || FocusTimerManager.isStopwatchActive.value
                FocusTimerAnomalyChecker.evaluateFocusTimeChange(
                    oldSecs = prev,
                    newSecs = snapshot.totalSeconds,
                    isTimerRunning = isTimerActive,
                    context = ctx
                )
            }
            previousAnomalyCheckSecs = snapshot.totalSeconds
        }

        // Consumer 4: Widget Subsystem Refresh
        appContext?.let { ctx ->
            scope.launch {
                try {
                    WidgetManager.updateTotalFocusTimeWidget(ctx, isPartialUpdate = true)
                } catch (e: Throwable) {
                    Log.w(TAG, "Widget update on broadcast skipped: ${e.message}")
                }
            }
        }

        // Consumer 5: Telemetry Subsystem (Logs will pull latest snapshot on tick)
        // TimerDisplayTelemetryManager continuously accesses todaySnapshot
    }

    /**
     * Direct query helper for Widget and other background components.
     */
    fun getTodayTotalSeconds(context: Context? = null): Int {
        val currentSecs = _todayTotalSeconds.value
        return if (currentSecs > 0) {
            currentSecs
        } else {
            calculateTodaySnapshot(context).totalSeconds
        }
    }

    /**
     * Direct query helper for formatted string e.g. "01h 45m 12s".
     */
    fun getTodayFormattedHms(context: Context? = null): String {
        val currentStr = _todayFormattedHms.value
        return if (currentStr.isNotEmpty() && currentStr != "00m 00s") {
            currentStr
        } else {
            calculateTodaySnapshot(context).formattedHms
        }
    }

    /**
     * Direct query helper returning the full snapshot.
     */
    fun getTodaySnapshot(context: Context? = null): TodayFocusTimeSnapshot {
        val snap = _todaySnapshot.value
        return if (snap.timestampMs > 0L) snap else calculateTodaySnapshot(context)
    }
}
