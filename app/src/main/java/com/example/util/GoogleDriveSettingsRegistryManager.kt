package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.widget.WidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GoogleDriveSettingsRegistryManager
 *
 * Implements deterministic constant UIDs for all settings and preferences with
 * per-setting last-updated timestamp tracking.
 *
 * When an offline device comes online:
 * - Compares local setting timestamps against Google Drive remote timestamps for each fixed UID.
 * - If local is newer -> Google Drive is outdated -> updates Google Drive with local setting.
 * - If Google Drive is newer -> local system is outdated -> updates local SharedPreferences.
 * - Uses fixed, permanent constant UIDs (settings cannot be deleted).
 */
object GoogleDriveSettingsRegistryManager {

    private const val TAG = "SettingsRegistryManager"
    private const val PREFS_METADATA = "settings_sync_timestamps_prefs"
    private const val SETTINGS_FILE_NAME = "app_settings_sync.json"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // Canonical list of preference files to synchronize
    val MONITORED_PREF_FILES = listOf(
        "app_prefs",
        "app_settings",
        "countdown_settings_prefs",
        "strict_mode_prefs",
        "app_calendar_prefs"
    )

    // Deterministic constant UIDs for core settings
    private val FIXED_UID_MAPPINGS = mapOf(
        // Task Settings
        "task_hide_completed" to "SETTING_TASK_01_HIDE_COMPLETED",
        "task_show_details" to "SETTING_TASK_02_SHOW_DETAILS",
        "task_group_by_mode" to "SETTING_TASK_03_GROUP_BY",
        "task_sort_by_mode" to "SETTING_TASK_04_SORT_BY",
        "task_view_mode" to "SETTING_TASK_05_VIEW_MODE",
        "show_completed_tasks" to "SETTING_TASK_06_SHOW_COMPLETED",
        "no_date_tasks_at_last" to "SETTING_TASK_07_NO_DATE_AT_LAST",
        "default_task_priority" to "SETTING_TASK_08_DEFAULT_PRIORITY",
        "task_date_filter" to "SETTING_TASK_09_DATE_FILTER",

        // Timer & Focus Session Settings
        "timer_duration_minutes" to "SETTING_TIMER_01_FOCUS_DURATION",
        "break_duration" to "SETTING_TIMER_02_BREAK_DURATION",
        "long_break_duration" to "SETTING_TIMER_03_LONG_BREAK_DURATION",
        "auto_start_breaks" to "SETTING_TIMER_04_AUTO_START_BREAKS",
        "auto_start_next_timer" to "SETTING_TIMER_05_AUTO_START_NEXT_TIMER",
        "sound_enabled" to "SETTING_TIMER_06_SOUND_ENABLED",
        "vibration_enabled" to "SETTING_TIMER_07_VIBRATION_ENABLED",
        "ticking_sound_enabled" to "SETTING_TIMER_08_TICKING_SOUND",
        "show_overlay_on_exit" to "SETTING_TIMER_09_SHOW_OVERLAY",
        "fullscreen_alarm_enabled" to "SETTING_TIMER_10_FULLSCREEN_ALARM",
        "bedtime_reminder_enabled" to "SETTING_TIMER_11_BEDTIME_REMINDER",
        "scheduled_bedtime" to "SETTING_TIMER_12_SCHEDULED_BEDTIME",

        // Strict Mode Settings
        "strict_mode_enabled" to "SETTING_STRICT_01_ENABLED",
        "prevent_app_switch" to "SETTING_STRICT_02_PREVENT_APP_SWITCH",
        "block_notifications" to "SETTING_STRICT_03_BLOCK_NOTIFICATIONS",
        "whitelist_apps" to "SETTING_STRICT_04_WHITELIST_APPS",

        // App Lock & Security Settings
        "app_lock_enabled" to "SETTING_SECURITY_01_APP_LOCK",
        "biometric_enabled" to "SETTING_SECURITY_02_BIOMETRIC",
        "pin_code" to "SETTING_SECURITY_03_PIN_CODE",
        "auto_lock_interval" to "SETTING_SECURITY_04_AUTO_LOCK_INTERVAL",

        // UI & Navigation Settings
        "dark_theme" to "SETTING_THEME_01_DARK_MODE",
        "tab_order" to "SETTING_TAB_01_ORDER",
        "overflow_menu_tabs" to "SETTING_TAB_02_OVERFLOW_TABS",
        "hidden_tabs" to "SETTING_TAB_03_HIDDEN_TABS",
        "tab_bar_position" to "SETTING_TAB_04_BAR_POSITION",
        "app_theme_color" to "SETTING_THEME_02_ACCENT_COLOR",

        // Countdowns Settings
        "countdown_sort_mode" to "SETTING_COUNTDOWN_01_SORT_MODE",
        "countdown_group_mode" to "SETTING_COUNTDOWN_02_GROUP_MODE",
        "show_past_countdowns" to "SETTING_COUNTDOWN_03_SHOW_PAST",

        // Calendar Sync Settings
        "calendar_sync_enabled" to "SETTING_CALENDAR_01_SYNC_ENABLED",
        "calendar_auto_import" to "SETTING_CALENDAR_02_AUTO_IMPORT",

        // Habit & General Notification Settings
        "habit_reminder_time" to "SETTING_HABIT_01_REMINDER_TIME",
        "daily_summary_enabled" to "SETTING_NOTIFICATION_01_DAILY_SUMMARY",
        "weekly_report_enabled" to "SETTING_NOTIFICATION_02_WEEKLY_REPORT"
    )

    data class SettingEntry(
        val uid: String,
        val prefFile: String,
        val key: String,
        val value: Any?,
        val valueType: String,
        val lastUpdatedTime: Long,
        val lastUpdatedDevice: String = Build.MODEL ?: "Android Device"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("uid", uid)
                put("prefFile", prefFile)
                put("key", key)
                put("valueType", valueType)
                put("lastUpdatedTime", lastUpdatedTime)
                put("lastUpdatedDevice", lastUpdatedDevice)
                when (value) {
                    is Boolean -> put("value", value)
                    is Int -> put("value", value)
                    is Long -> put("value", value)
                    is Float -> put("value", value.toDouble())
                    is Double -> put("value", value)
                    is String -> put("value", value)
                    null -> put("value", JSONObject.NULL)
                    else -> put("value", value.toString())
                }
            }
        }

        companion object {
            fun fromJson(json: JSONObject): SettingEntry? {
                val uid = json.optString("uid")
                val prefFile = json.optString("prefFile")
                val key = json.optString("key")
                val valueType = json.optString("valueType", "STRING")
                val lastUpdatedTime = json.optLong("lastUpdatedTime", System.currentTimeMillis())
                val lastUpdatedDevice = json.optString("lastUpdatedDevice", "Device")

                if (uid.isBlank() || prefFile.isBlank() || key.isBlank()) return null

                val value: Any? = when (valueType.uppercase()) {
                    "BOOLEAN" -> json.optBoolean("value")
                    "INT" -> json.optInt("value")
                    "LONG" -> json.optLong("value")
                    "FLOAT" -> json.optDouble("value").toFloat()
                    "DOUBLE" -> json.optDouble("value")
                    "STRING" -> json.optString("value")
                    else -> if (json.isNull("value")) null else json.opt("value")
                }

                return SettingEntry(
                    uid = uid,
                    prefFile = prefFile,
                    key = key,
                    value = value,
                    valueType = valueType,
                    lastUpdatedTime = lastUpdatedTime,
                    lastUpdatedDevice = lastUpdatedDevice
                )
            }
        }
    }

    // Reference to listeners to prevent garbage collection
    private val prefListeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    /**
     * Initializes automatic preference change listeners across all monitored SharedPreferences files.
     */
    fun initAutoTracking(context: Context) {
        val appContext = context.applicationContext
        synchronized(prefListeners) {
            if (prefListeners.isNotEmpty()) return
            for (prefFile in MONITORED_PREF_FILES) {
                val prefs = appContext.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key != null && !key.startsWith("gd_") && !key.startsWith("sync_") && !key.startsWith("cached_")) {
                        recordSettingChange(appContext, prefFile, key)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                prefListeners.add(listener)
            }
        }
        Log.d(TAG, "GoogleDriveSettingsRegistryManager initialized automatic change tracking on all monitored preference files.")
    }

    /**
     * Resolves a deterministic, fixed constant UID for any preference key.
     */
    fun resolveConstantUid(prefFile: String, key: String): String {
        val fixed = FIXED_UID_MAPPINGS[key]
        if (fixed != null) return fixed

        val cleanFile = prefFile.uppercase().replace("[^A-Z0-9]".toRegex(), "_").trim('_')
        val cleanKey = key.uppercase().replace("[^A-Z0-9]".toRegex(), "_").trim('_')
        return "SETTING_${cleanFile}_${cleanKey}"
    }

    /**
     * Records timestamp when a setting is updated locally.
     */
    fun recordSettingChange(context: Context, prefFile: String, key: String) {
        try {
            val uid = resolveConstantUid(prefFile, key)
            val now = System.currentTimeMillis()
            val metaPrefs = context.getSharedPreferences(PREFS_METADATA, Context.MODE_PRIVATE)
            metaPrefs.edit()
                .putLong("ts_$uid", now)
                .putString("dev_$uid", Build.MODEL ?: "Android Device")
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error recording setting change timestamp: ${e.message}")
        }
    }

    /**
     * Gets the last updated timestamp for a setting UID.
     */
    fun getSettingTimestamp(context: Context, uid: String): Long {
        val metaPrefs = context.getSharedPreferences(PREFS_METADATA, Context.MODE_PRIVATE)
        return metaPrefs.getLong("ts_$uid", 0L)
    }

    /**
     * Extracts all current local settings into a map of SettingEntry objects indexed by constant UID.
     */
    fun loadLocalSettingsRegistry(context: Context): MutableMap<String, SettingEntry> {
        val registry = mutableMapOf<String, SettingEntry>()
        val metaPrefs = context.getSharedPreferences(PREFS_METADATA, Context.MODE_PRIVATE)
        val defaultTs = metaPrefs.getLong("global_settings_baseline_ts", System.currentTimeMillis())

        for (prefFile in MONITORED_PREF_FILES) {
            try {
                val prefs = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
                val allEntries = prefs.all
                for ((key, value) in allEntries) {
                    if (key.startsWith("gd_") || key.startsWith("sync_") || key.startsWith("cached_")) continue

                    val uid = resolveConstantUid(prefFile, key)
                    val storedTs = metaPrefs.getLong("ts_$uid", defaultTs)
                    val storedDevice = metaPrefs.getString("dev_$uid", Build.MODEL ?: "Android Device") ?: "Device"

                    val (vType, cleanVal) = when (value) {
                        is Boolean -> Pair("BOOLEAN", value)
                        is Int -> Pair("INT", value)
                        is Long -> Pair("LONG", value)
                        is Float -> Pair("FLOAT", value)
                        is Double -> Pair("DOUBLE", value)
                        is String -> Pair("STRING", value)
                        else -> Pair("STRING", value?.toString())
                    }

                    registry[uid] = SettingEntry(
                        uid = uid,
                        prefFile = prefFile,
                        key = key,
                        value = cleanVal,
                        valueType = vType,
                        lastUpdatedTime = storedTs,
                        lastUpdatedDevice = storedDevice
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading local preferences for $prefFile: ${e.message}")
            }
        }
        return registry
    }

    /**
     * Applies a SettingEntry to the local SharedPreferences and updates its local timestamp.
     */
    fun applySettingEntryLocally(context: Context, entry: SettingEntry) {
        try {
            val prefs = context.getSharedPreferences(entry.prefFile, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            when (entry.valueType.uppercase()) {
                "BOOLEAN" -> {
                    val bVal = when (val v = entry.value) {
                        is Boolean -> v
                        is String -> v.toBooleanStrictOrNull() ?: false
                        is Number -> v.toInt() == 1
                        else -> false
                    }
                    editor.putBoolean(entry.key, bVal)
                }
                "INT" -> {
                    val iVal = when (val v = entry.value) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: 0
                        else -> 0
                    }
                    editor.putInt(entry.key, iVal)
                }
                "LONG" -> {
                    val lVal = when (val v = entry.value) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    editor.putLong(entry.key, lVal)
                }
                "FLOAT" -> {
                    val fVal = when (val v = entry.value) {
                        is Number -> v.toFloat()
                        is String -> v.toFloatOrNull() ?: 0f
                        else -> 0f
                    }
                    editor.putFloat(entry.key, fVal)
                }
                "DOUBLE" -> {
                    val sVal = entry.value?.toString() ?: "0.0"
                    editor.putString(entry.key, sVal)
                }
                else -> {
                    editor.putString(entry.key, entry.value?.toString() ?: "")
                }
            }
            editor.apply()

            // Save remote last updated timestamp locally
            val metaPrefs = context.getSharedPreferences(PREFS_METADATA, Context.MODE_PRIVATE)
            metaPrefs.edit()
                .putLong("ts_${entry.uid}", entry.lastUpdatedTime)
                .putString("dev_${entry.uid}", entry.lastUpdatedDevice)
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "Error applying setting entry locally for ${entry.uid}: ${e.message}")
        }
    }

    /**
     * Performs complete bidirectional synchronization for all settings with Google Drive.
     *
     * Algorithm:
     * - Checks each fixed UID's lastUpdatedTime.
     * - If local is newer -> GDrive is outdated -> updates GDrive.
     * - If GDrive is newer -> system is outdated -> updates local system.
     */
    suspend fun synchronizeSettingsWithDrive(
        context: Context,
        accessToken: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val localRegistry = loadLocalSettingsRegistry(context)

            val vault = GoogleDriveUploadManager.ensureVaultStructureAndReadme(accessToken)
            val targetFolderId = vault?.backupsId ?: vault?.rootId

            var fileId = if (targetFolderId != null) {
                GoogleDriveUploadManager.findFileInFolder(accessToken, SETTINGS_FILE_NAME, targetFolderId)
            } else {
                GoogleDriveReadManager.findFileId(accessToken, SETTINGS_FILE_NAME)
            }

            var remoteRegistry = mutableMapOf<String, SettingEntry>()
            var remoteFileExisted = false

            if (fileId != null) {
                // Download remote settings registry
                val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                val req = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        remoteFileExisted = true
                        val rootJson = JSONObject(bodyStr)
                        val registryArr = rootJson.optJSONArray("settings_registry")
                        if (registryArr != null) {
                            for (i in 0 until registryArr.length()) {
                                val itemJson = registryArr.optJSONObject(i)
                                if (itemJson != null) {
                                    val entry = SettingEntry.fromJson(itemJson)
                                    if (entry != null) {
                                        remoteRegistry[entry.uid] = entry
                                    }
                                }
                            }
                        } else {
                            // Backwards compatibility with legacy dictionaries
                            for (prefFile in MONITORED_PREF_FILES) {
                                val subObj = rootJson.optJSONObject(prefFile)
                                if (subObj != null) {
                                    val keys = subObj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        val v = subObj.opt(k)
                                        val uid = resolveConstantUid(prefFile, k)
                                        val entry = SettingEntry(
                                            uid = uid,
                                            prefFile = prefFile,
                                            key = k,
                                            value = v,
                                            valueType = if (v is Boolean) "BOOLEAN" else if (v is Int) "INT" else if (v is Long) "LONG" else "STRING",
                                            lastUpdatedTime = rootJson.optLong("timestamp", 0L),
                                            lastUpdatedDevice = "Remote GDrive"
                                        )
                                        remoteRegistry[uid] = entry
                                    }
                                }
                            }
                        }
                    }
                }
                res.close()
            }

            var localUpdatedCount = 0
            var gdriveNeedsUpload = !remoteFileExisted
            val mergedRegistry = mutableMapOf<String, SettingEntry>()

            val allUids = (localRegistry.keys + remoteRegistry.keys).toSet()

            for (uid in allUids) {
                val localEntry = localRegistry[uid]
                val remoteEntry = remoteRegistry[uid]

                when {
                    localEntry != null && remoteEntry != null -> {
                        if (remoteEntry.lastUpdatedTime > localEntry.lastUpdatedTime) {
                            // Google Drive remote is newer -> Local device is outdated -> Update local system
                            applySettingEntryLocally(context, remoteEntry)
                            mergedRegistry[uid] = remoteEntry
                            localUpdatedCount++
                            Log.d(TAG, "Reconciled setting $uid: remote newer (${remoteEntry.lastUpdatedTime} > ${localEntry.lastUpdatedTime}). Local updated.")
                        } else if (localEntry.lastUpdatedTime > remoteEntry.lastUpdatedTime) {
                            // Local device is newer -> Google Drive is outdated -> Update Google Drive
                            mergedRegistry[uid] = localEntry
                            gdriveNeedsUpload = true
                            Log.d(TAG, "Reconciled setting $uid: local newer (${localEntry.lastUpdatedTime} > ${remoteEntry.lastUpdatedTime}). GDrive will be updated.")
                        } else {
                            // Equal timestamps -> in-sync
                            mergedRegistry[uid] = localEntry
                        }
                    }
                    localEntry != null && remoteEntry == null -> {
                        // New local setting not on Drive yet -> Upload to GDrive
                        mergedRegistry[uid] = localEntry
                        gdriveNeedsUpload = true
                    }
                    localEntry == null && remoteEntry != null -> {
                        // Setting on Drive but missing locally -> Restore to local system
                        applySettingEntryLocally(context, remoteEntry)
                        mergedRegistry[uid] = remoteEntry
                        localUpdatedCount++
                    }
                }
            }

            if (gdriveNeedsUpload || !remoteFileExisted) {
                // Prepare consolidated GDrive JSON payload with deterministic UIDs and legacy map wrappers
                val payloadJson = JSONObject().apply {
                    put("schema_version", 2)
                    put("sync_protocol", "DETERMINISTIC_CONSTANT_UID_REGISTRY")
                    put("timestamp", System.currentTimeMillis())
                    put("device", Build.MODEL ?: "Android Device")

                    val regArr = JSONArray()
                    mergedRegistry.values.forEach { regArr.put(it.toJson()) }
                    put("settings_registry", regArr)

                    // Also embed legacy pref dictionaries for backward compatibility with older tools
                    for (prefFile in MONITORED_PREF_FILES) {
                        val dict = JSONObject()
                        mergedRegistry.values.filter { it.prefFile == prefFile }.forEach { entry ->
                            when (entry.value) {
                                is Boolean -> dict.put(entry.key, entry.value)
                                is Int -> dict.put(entry.key, entry.value)
                                is Long -> dict.put(entry.key, entry.value)
                                is Float -> dict.put(entry.key, entry.value.toDouble())
                                is Double -> dict.put(entry.key, entry.value)
                                is String -> dict.put(entry.key, entry.value)
                                null -> {}
                                else -> dict.put(entry.key, entry.value.toString())
                            }
                        }
                        put(prefFile, dict)
                    }
                }

                if (fileId == null) {
                    fileId = if (targetFolderId != null) {
                        GoogleDriveUploadManager.createFileMetadataInFolder(accessToken, SETTINGS_FILE_NAME, targetFolderId)
                    } else {
                        GoogleDriveWriteManager.createFileMetadata(accessToken, SETTINGS_FILE_NAME)
                    }
                }

                if (fileId != null) {
                    val patchReq = Request.Builder()
                        .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .addHeader("Content-Type", "application/json")
                        .patch(payloadJson.toString(2).toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val patchRes = client.newCall(patchReq).execute()
                    val uploadSuccess = patchRes.isSuccessful
                    patchRes.close()

                    if (uploadSuccess) {
                        GoogleDriveWriteManager.makeFilePublic(accessToken, fileId)
                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putLong("gd_settings_last_sync_timestamp", System.currentTimeMillis()).apply()
                        Log.i(TAG, "Uploaded latest settings registry (${mergedRegistry.size} constant UIDs) to Google Drive.")
                    } else {
                        Log.e(TAG, "Failed to upload settings registry to Google Drive (HTTP ${patchRes.code})")
                    }
                }
            }

            if (localUpdatedCount > 0) {
                // Notify widgets and live system of updated settings
                WidgetManager.updateAllWidgets(context)
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("gd_settings_last_restore_timestamp", System.currentTimeMillis()).apply()
            }

            Pair(true, "Settings synchronized: ${mergedRegistry.size} configurations verified (${localUpdatedCount} updated locally).")
        } catch (e: Exception) {
            Log.e(TAG, "Error in synchronizeSettingsWithDrive", e)
            Pair(false, "Settings Sync Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
