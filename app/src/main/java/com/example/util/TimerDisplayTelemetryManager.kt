package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data model representing a single minute-by-minute timer display telemetry record.
 */
data class TimerDisplayTelemetryEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val clockTimeString: String, // e.g. "02:01 AM"
    val dateString: String,      // e.g. "2026-08-17"
    val status: String,          // "IDLE", "FOCUSING", "PAUSED", "BREAK", "OVERTIME"
    val mode: String,            // "Stopwatch", "Pomodoro", "Break", "Minus Timer"
    val displayedValue: String,  // e.g. "00:01", "00:02", "24:59", "00:00", "-00:15"
    val rawSeconds: Int = 0,
    val taskTitle: String = "",
    val tag: String = ""
)

/**
 * Dedicated Background & Foreground Timer Display Telemetry Recorder.
 *
 * Requirements:
 * 1. Strictly local on-device storage only (no cloud backup needed).
 * 2. 77-day retention policy: any records older than 77 days are automatically pruned.
 * 3. Date-wise grouped and sorted records viewing & clipboard formatting.
 * 4. Minute-by-minute independent monitoring of displayed values and status.
 */
object TimerDisplayTelemetryManager {

    private const val TAG = "TimerTelemetryManager"
    private const val TELEMETRY_FILE_NAME = "timer_display_telemetry_logs.json"
    private const val PREFS_NAME = "timer_telemetry_prefs"
    private const val KEY_MONITORING_ENABLED = "is_telemetry_monitoring_enabled"

    /** Strict 77-day local device retention window (in milliseconds) */
    const val RETENTION_DAYS = 77
    private const val RETENTION_PERIOD_MS = 77L * 24L * 60L * 60L * 1000L

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val logListAdapter = moshi.adapter<List<TimerDisplayTelemetryEntry>>(
        Types.newParameterizedType(List::class.java, TimerDisplayTelemetryEntry::class.java)
    )

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val timeWithSecsFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val fullDateTimeFormat = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault())

    private val _telemetryLogs = MutableStateFlow<List<TimerDisplayTelemetryEntry>>(emptyList())
    val telemetryLogs: StateFlow<List<TimerDisplayTelemetryEntry>> = _telemetryLogs.asStateFlow()

    private val _isMonitoringEnabled = MutableStateFlow(true)
    val isMonitoringEnabled: StateFlow<Boolean> = _isMonitoringEnabled.asStateFlow()

    private var applicationContext: Context? = null
    private var lastRecordedMinuteKey: Long = -1L
    private var lastLoggedStatus: String = ""
    private var lastLoggedDisplayValue: String = ""
    private var isInitialized = false

    /**
     * Initializes the telemetry manager from local storage and applies the 77-day retention policy.
     */
    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        this.applicationContext = appContext

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isMonitoringEnabled.value = prefs.getBoolean(KEY_MONITORING_ENABLED, true)

        managerScope.launch {
            loadLogsFromDisk(appContext)
            // Immediately take initial snapshot if enabled
            if (_isMonitoringEnabled.value) {
                recordMinuteSnapshot(appContext, force = false)
            }
        }
        isInitialized = true
        Log.d(TAG, "TimerDisplayTelemetryManager initialized with 77-day retention. MonitoringEnabled=${_isMonitoringEnabled.value}")
    }

    /**
     * Called every second by ticker loops (e.g. LiveTimerDisplayRelay / KeepAliveService)
     * to check if a new minute boundary has crossed or status changed.
     */
    fun onSecondTick(context: Context) {
        if (!_isMonitoringEnabled.value) return
        val currentMillis = System.currentTimeMillis()
        val currentMinuteKey = currentMillis / 60000L

        // Record if minute rolled over
        if (currentMinuteKey != lastRecordedMinuteKey) {
            recordMinuteSnapshot(context, force = false)
        }
    }

    /**
     * Immediately records a snapshot when timer state changes (e.g. Started, Paused, Resumed, Reset).
     */
    fun recordImmediateStateChange(context: Context) {
        if (!_isMonitoringEnabled.value) return
        managerScope.launch {
            recordMinuteSnapshot(context, force = true)
        }
    }

    /**
     * Records a single display telemetry snapshot and ensures 77-day retention.
     */
    @Synchronized
    fun recordMinuteSnapshot(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!_isMonitoringEnabled.value && !force) return

        val now = System.currentTimeMillis()
        val currentMinuteKey = now / 60000L

        // Get current display snapshot from LiveTimerDisplayRelay
        val snapshot = try {
            LiveTimerDisplayRelay.computeCurrentDisplaySnapshot()
        } catch (e: Exception) {
            null
        }

        val isMinusActive = FocusTimerManager.isMinusTimerActive.value
        val isTimerOn = FocusTimerManager.isTimerRunning.value
        val isStopwatchOn = FocusTimerManager.isStopwatchActive.value
        val isPausedState = FocusTimerManager.isPaused.value
        val isFocusPhase = FocusTimerManager.isFocusPhase.value
        val isTabFocusTimer = FocusTimerManager.isTabFocusTimerSelected.value

        // Resolve current timer status string
        val statusString = when {
            isMinusActive -> "OVERTIME"
            !isFocusPhase -> if (isTimerOn || isStopwatchOn) "BREAK" else "PAUSED"
            isTimerOn || isStopwatchOn -> "FOCUSING"
            isPausedState -> "PAUSED"
            else -> "IDLE"
        }

        // Resolve current mode
        val modeString = when {
            isMinusActive -> "Minus Timer"
            !isFocusPhase -> "Break"
            isTabFocusTimer -> "Pomodoro"
            else -> "Stopwatch"
        }

        // Resolve displayed value
        val displayedTime = snapshot?.formattedTimeText ?: run {
            if (isTabFocusTimer) {
                val secs = FocusTimerManager.timerSecondsLeft.value
                val m = secs / 60
                val s = secs % 60
                String.format(Locale.US, "%02d:%02d", m, s)
            } else {
                val secs = FocusTimerManager.stopwatchSeconds.value
                val m = secs / 60
                val s = secs % 60
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }

        // Check if duplicate in the exact same minute unless force is true or status changed
        if (!force && currentMinuteKey == lastRecordedMinuteKey && statusString == lastLoggedStatus && displayedTime == lastLoggedDisplayValue) {
            return
        }

        lastRecordedMinuteKey = currentMinuteKey
        lastLoggedStatus = statusString
        lastLoggedDisplayValue = displayedTime

        val clockTimeStr = timeFormat.format(Date(now))
        val dateStr = dateFormat.format(Date(now))
        val taskTitle = FocusTimerManager.attachedTask.value?.title ?: ""
        val tagStr = FocusTimerManager.attachedTag.value

        val entry = TimerDisplayTelemetryEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestampMillis = now,
            clockTimeString = clockTimeStr,
            dateString = dateStr,
            status = statusString,
            mode = modeString,
            displayedValue = displayedTime,
            rawSeconds = snapshot?.rawSeconds ?: 0,
            taskTitle = taskTitle,
            tag = tagStr
        )

        val currentList = _telemetryLogs.value.toMutableList()
        currentList.add(0, entry) // Prepend latest at top

        // Enforce 77-Day Retention: discard records older than 77 days
        val cutoffTimestamp = now - RETENTION_PERIOD_MS
        val retentionPrunedList = currentList.filter { it.timestampMillis >= cutoffTimestamp }

        _telemetryLogs.value = retentionPrunedList
        saveLogsToDisk(appContext, retentionPrunedList)
        Log.d(TAG, "Recorded telemetry: [$clockTimeStr] Status=$statusString | Mode=$modeString | Display=$displayedTime (Retention: ${retentionPrunedList.size} logs)")
    }

    /**
     * Toggles whether telemetry monitoring is active.
     */
    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        _isMonitoringEnabled.value = enabled
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MONITORING_ENABLED, enabled)
            .apply()

        if (enabled) {
            recordMinuteSnapshot(appContext, force = true)
        }
    }

    /**
     * Clears all recorded telemetry logs locally.
     */
    fun clearLogs(context: Context) {
        val appContext = context.applicationContext
        _telemetryLogs.value = emptyList()
        lastRecordedMinuteKey = -1L
        lastLoggedStatus = ""
        lastLoggedDisplayValue = ""
        managerScope.launch {
            val file = File(appContext.filesDir, TELEMETRY_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        }
        Log.d(TAG, "All local timer display telemetry logs cleared.")
    }

    /**
     * Formats telemetry records into a date-wise structured text representation.
     * Only encompasses past 77 days records.
     */
    fun generateFormattedLogText(filterStatus: String? = null, targetDate: String? = null): String {
        val list = _telemetryLogs.value
        val statusFiltered = if (filterStatus.isNullOrEmpty() || filterStatus.equals("ALL", ignoreCase = true)) {
            list
        } else {
            list.filter { it.status.equals(filterStatus, ignoreCase = true) }
        }

        val dateFiltered = if (targetDate.isNullOrEmpty() || targetDate.equals("ALL", ignoreCase = true)) {
            statusFiltered
        } else {
            statusFiltered.filter { it.dateString == targetDate }
        }

        val sb = StringBuilder()
        val nowFormatted = fullDateTimeFormat.format(Date())

        sb.appendLine("=================================================================")
        sb.appendLine("        TIMER DISPLAY TELEMETRY LOGS (PAST 77 DAYS RECORDS)     ")
        sb.appendLine("=================================================================")
        sb.appendLine("Storage Mode: Strictly Local Device Storage (No Cloud Backup)")
        sb.appendLine("Retention Window: Past $RETENTION_DAYS Days Maximum")
        sb.appendLine("Exported At: $nowFormatted")
        sb.appendLine("Total Records: ${dateFiltered.size} (Date Filter: ${targetDate ?: "All Dates"}, Status: ${filterStatus ?: "ALL"})")
        sb.appendLine("Current Monitoring: ${if (_isMonitoringEnabled.value) "ACTIVE" else "PAUSED"}")
        sb.appendLine("=================================================================")

        // Group date-wise, descending (latest date first)
        val groupedByDate = dateFiltered.groupBy { it.dateString }.toSortedMap(compareByDescending { it })

        if (groupedByDate.isEmpty()) {
            sb.appendLine("No telemetry logs found for the selected criteria.")
        } else {
            for ((date, entries) in groupedByDate) {
                sb.appendLine()
                sb.appendLine("-----------------------------------------------------------------")
                sb.appendLine(">>> DATE RECORD: $date (${entries.size} records)")
                sb.appendLine("-----------------------------------------------------------------")
                sb.appendLine("TIME       | STATUS    | MODE       | DISPLAY | TASK / TAG")
                sb.appendLine("-----------------------------------------------------------------")

                // Sort chronologically within the date for readable timeline
                val sortedEntries = entries.sortedBy { it.timestampMillis }
                for (item in sortedEntries) {
                    val taskInfo = if (item.taskTitle.isNotEmpty()) {
                        "${item.taskTitle}${if (item.tag.isNotEmpty()) " (${item.tag})" else ""}"
                    } else if (item.tag.isNotEmpty()) {
                        item.tag
                    } else {
                        "-"
                    }
                    val paddedTime = item.clockTimeString.padEnd(10)
                    val paddedStatus = item.status.padEnd(9)
                    val paddedMode = item.mode.padEnd(10)
                    val paddedDisplay = item.displayedValue.padEnd(7)

                    sb.appendLine("[$paddedTime] Status: $paddedStatus | Mode: $paddedMode | Display: $paddedDisplay | Task: $taskInfo")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("=================================================================")
        sb.appendLine("End of Local 77-Day Timer Display Telemetry Export.")
        return sb.toString()
    }

    /**
     * Copies telemetry logs to system clipboard formatted date-wise and shows feedback Toast.
     */
    fun copyToClipboard(context: Context, filterStatus: String? = null, targetDate: String? = null): Boolean {
        return try {
            val formattedText = generateFormattedLogText(filterStatus, targetDate)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clipLabel = if (targetDate != null && targetDate != "ALL") "Timer Telemetry ($targetDate)" else "Timer Telemetry (77 Days)"
                val clip = ClipData.newPlainText(clipLabel, formattedText)
                clipboard.setPrimaryClip(clip)
                val count = _telemetryLogs.value.count {
                    (filterStatus.isNullOrEmpty() || filterStatus == "ALL" || it.status.equals(filterStatus, ignoreCase = true)) &&
                    (targetDate.isNullOrEmpty() || targetDate == "ALL" || it.dateString == targetDate)
                }
                val dateMsg = if (targetDate != null && targetDate != "ALL") "for $targetDate " else ""
                Toast.makeText(context, "Copied $count timer telemetry logs ${dateMsg}to clipboard!", Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying telemetry to clipboard", e)
            Toast.makeText(context, "Failed to copy logs: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun loadLogsFromDisk(context: Context) {
        try {
            val file = File(context.filesDir, TELEMETRY_FILE_NAME)
            if (file.exists()) {
                val json = file.readText()
                val list = logListAdapter.fromJson(json) ?: emptyList()

                // Apply 77-day retention immediately on load
                val now = System.currentTimeMillis()
                val cutoff = now - RETENTION_PERIOD_MS
                val retainedList = list.filter { it.timestampMillis >= cutoff }

                _telemetryLogs.value = retainedList
                if (retainedList.size < list.size) {
                    saveLogsToDisk(context, retainedList)
                    Log.d(TAG, "Pruned ${list.size - retainedList.size} logs older than 77 days. Retained: ${retainedList.size}")
                } else {
                    Log.d(TAG, "Loaded ${retainedList.size} telemetry logs from disk (past 77 days).")
                }
            } else {
                _telemetryLogs.value = emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading telemetry logs from disk", e)
            _telemetryLogs.value = emptyList()
        }
    }

    private fun saveLogsToDisk(context: Context, list: List<TimerDisplayTelemetryEntry>) {
        managerScope.launch {
            try {
                val file = File(context.filesDir, TELEMETRY_FILE_NAME)
                val json = logListAdapter.toJson(list)
                file.writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving telemetry logs to disk", e)
            }
        }
    }
}
