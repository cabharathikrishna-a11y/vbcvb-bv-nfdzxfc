package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * GoogleDriveReadManager
 *
 * Dedicated manager for reading, fetching, querying, and downloading data from Google Drive.
 */
object GoogleDriveReadManager {

    private const val TAG = "GoogleDriveRead"
    private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.readonly"
    private val client by lazy { NetworkTrafficManager.createOkHttpClientBuilder(NetworkTrafficManager.TrafficCategory.CLOUD_BACKUP).build() }

    /**
     * Checks whether the user has signed in and granted the Drive scope.
     */
    fun hasDrivePermission(context: Context): Boolean {
        return try {
            val account = GmsUtils.getLastSignedInAccount(context)
            account != null && account.grantedScopes.any {
                it.scopeUri.equals("https://www.googleapis.com/auth/drive.appdata", ignoreCase = true) ||
                it.scopeUri.equals("https://www.googleapis.com/auth/drive.file", ignoreCase = true)
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Obtains the OAuth2 access token for the signed-in Google account.
     */
    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (!GmsUtils.isGmsAvailable(context)) return@withContext null
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var email = prefs.getString("selected_file_backup_account", null)
            if (email.isNullOrBlank()) {
                val account = GmsUtils.getLastSignedInAccount(context)
                email = account?.email
            }
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found for Drive operations.")
                return@withContext null
            }
            GoogleAuthUtil.getToken(context, email, DRIVE_SCOPE)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered.", recoverable)
            recoverable.intent?.let { intent ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onAuthResolutionRequired(intent)
                }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error obtaining Google OAuth2 token: ${e.message}", e)
            null
        }
    }

    /**
     * Finds a file ID in Google Drive by filename.
     */
    fun findFileId(accessToken: String, fileName: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files?q=name='$fileName'+and+trashed=false&spaces=drive,appDataFolder"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: return null
                    val json = JSONObject(bodyStr)
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        files.getJSONObject(0).optString("id")
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding file ID for $fileName", e)
            null
        }
    }

    /**
     * Downloads file content as String from Google Drive by fileId.
     */
    fun downloadBackupFileContent(accessToken: String, fileId: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup file content for fileId $fileId", e)
            null
        }
    }

    data class GoogleDriveFileItem(
        val id: String,
        val name: String,
        val mimeType: String = "",
        val modifiedTime: String = "",
        val webViewLink: String = "",
        val size: Long = 0L,
        val isFolder: Boolean = false
    )

    /**
     * Lists files from Google Drive matching mimeTypes or folders.
     */
    suspend fun listGoogleDriveFiles(
        context: Context,
        mimeType: String? = null,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): List<GoogleDriveFileItem> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired) ?: return@withContext emptyList<GoogleDriveFileItem>()
        val q = if (mimeType != null) "mimeType='$mimeType'+and+trashed=false" else "trashed=false"
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,mimeType,modifiedTime,size,webViewLink)"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val items = mutableListOf<GoogleDriveFileItem>()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val files = json.optJSONArray("files") ?: return@withContext emptyList<GoogleDriveFileItem>()
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        items.add(
                            GoogleDriveFileItem(
                                id = f.optString("id"),
                                name = f.optString("name"),
                                mimeType = f.optString("mimeType"),
                                modifiedTime = f.optString("modifiedTime"),
                                size = f.optLong("size", 0L),
                                webViewLink = f.optString("webViewLink")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing Google Drive files", e)
        }
        items
    }

    /**
     * Lists Google Docs in user's Drive.
     */
    suspend fun listGoogleDocs(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): List<GoogleDriveFileItem> {
        return listGoogleDriveFiles(context, "application/vnd.google-apps.document", onAuthResolutionRequired)
    }

    /**
     * Lists Google Sheets in user's Drive.
     */
    suspend fun listGoogleSheets(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): List<GoogleDriveFileItem> {
        return listGoogleDriveFiles(context, "application/vnd.google-apps.spreadsheet", onAuthResolutionRequired)
    }

    /**
     * Checks backup sizes on Google Drive.
     */
    suspend fun getBackupSizes(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Long>()
        val token = getAccessToken(context, onAuthResolutionRequired) ?: return@withContext result
        try {
            val url = "https://www.googleapis.com/drive/v3/files?fields=files(id,name,size)&spaces=drive,appDataFolder"
            val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val json = JSONObject(res.body?.string() ?: "{}")
                    val files = json.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            val name = f.optString("name")
                            val size = f.optLong("size", 0L)
                            result[name] = size
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching backup sizes", e)
        }
        result
    }

    /**
     * Checks whether an existing backup exists in Google Drive.
     */
    suspend fun hasExistingBackupData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired) ?: return@withContext false
        findFileId(token, "app_data_backup.zip") != null || findFileId(token, "focus_backup.json") != null
    }

    /**
     * Searches for 'focus_backup.json' in AppData folder.
     */
    fun findBackupFileId(accessToken: String): String? {
        return findFileId(accessToken, "focus_backup.json")
    }

    /**
     * Parsed serialized string back to FocusRecord list.
     */
    private fun parseSerializedFocusRecords(serialized: String): List<com.example.ui.FocusRecord> {
        if (serialized.isBlank()) return emptyList()
        return try {
            serialized.split("\n").mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split("|")
                if (parts.size >= 4) {
                    val dateValue = if (parts.size >= 5) parts[4] else ""
                    val notesValue = if (parts.size >= 6) {
                        try {
                            String(android.util.Base64.decode(parts[5], android.util.Base64.NO_WRAP))
                        } catch (e: Exception) { "" }
                    } else ""
                    val originalMins = parts[3].toInt()
                    val originalSecs = if (parts.size >= 7) parts[6].toIntOrNull() ?: (originalMins * 60) else (originalMins * 60)

                    val durationMins = if (originalMins > 720) 720 else originalMins
                    val durationSecs = if (originalSecs > 43200) 43200 else originalSecs

                    com.example.ui.FocusRecord(parts[0], parts[1], parts[2], durationMins, dateValue, notesValue, durationSecs)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing serialized focus records: ${e.message}")
            emptyList()
        }
    }

    /**
     * Restores focus data from Google Drive.
     */
    suspend fun restoreFocusData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Preparing", 5, "Connecting to Google Drive...")
        val token = getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Authorization required. Please connect your Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Locating", 20, "Searching for focus data backup in Google Drive...")
            val fileId = findBackupFileId(token)
            if (fileId == null) {
                val msg = "No backup file found on your Google Drive. Save a backup first."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Downloading", 50, "Downloading focus records from Google Drive...")
            val contentStr = downloadBackupFileContent(token, fileId)
            if (contentStr.isNullOrBlank()) {
                val msg = "Failed to read backup from Google Drive."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Restoring", 75, "Parsing and merging focus sessions...")
            val backupJson = JSONObject(contentStr)
            val remoteSerializedRecords = backupJson.optString("focus_records_list", "")
            val remoteTotalMinutes = backupJson.optInt("total_focus_minutes", 0)
            val remotePomosCount = backupJson.optInt("today_pomos_count", 0)

            val localRecords = FocusTimerManager.loadFocusRecords(context)
            val remoteRecords = parseSerializedFocusRecords(remoteSerializedRecords)

            val mergedRecords = (localRecords + remoteRecords).distinctBy { record ->
                "${record.startTime}_${record.endTime}_${record.taskTitle}_${record.durationSeconds}"
            }

            FocusTimerManager.saveFocusRecords(context, mergedRecords)

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val finalTotalMinutes = maxOf(prefs.getSafeInt("total_focus_minutes", 0), remoteTotalMinutes, mergedRecords.sumOf { it.durationMinutes })
            val finalPomosCount = maxOf(prefs.getSafeInt("today_pomos_count", 0), remotePomosCount)

            prefs.edit().apply {
                putInt("total_focus_minutes", finalTotalMinutes)
                putInt("today_pomos_count", finalPomosCount)
                putLong("gd_focus_last_sync_timestamp", System.currentTimeMillis())
                apply()
            }

            withContext(Dispatchers.Main) {
                FocusTimerManager.setFocusRecords(mergedRecords)
                FocusTimerManager.setTotalFocusMinutes(finalTotalMinutes)
                FocusTimerManager.setTodayPomosCount(finalPomosCount)
            }

            val successMsg = "Successfully restored and merged ${remoteRecords.size} focus records from Google Drive!"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Completed", 100, successMsg, isFinished = true)
            Pair(true, successMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring focus data: ${e.message}", e)
            val errMsg = "Restore Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Restore", "Failed", 0, errMsg, isFinished = true, isError = true)
            Pair(false, errMsg)
        }
    }

    /**
     * Restores full app database and files from Google Drive.
     */
    suspend fun restoreAllAppData(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Preparing", 5, "Preparing Google Drive connection...")
        val token = getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Authorization required. Please connect your Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Locating", 20, "Locating latest cloud backup in Google Drive...")
            val vault = GoogleDriveUploadManager.ensureVaultStructureAndReadme(token)
            val fileName = "app_data_backup.zip"
            val fileId = (if (vault != null) GoogleDriveUploadManager.findFileInFolder(token, fileName, vault.backupsId) else null)
                ?: findFileId(token, fileName)
            
            if (fileId == null) {
                val msg = "No full app data backup found on Google Drive. Save a backup first."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val tempFile = java.io.File(context.cacheDir, "temp_app_data_restore.zip")
            if (tempFile.exists()) tempFile.delete()

            var downloadSuccess = false
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body
                    val contentLength = responseBody?.contentLength() ?: -1L
                    val formattedTotal = if (contentLength > 0) android.text.format.Formatter.formatFileSize(context, contentLength) else "archive"

                    responseBody?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            var lastPercent = -1

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (contentLength > 0) {
                                    val streamPercent = ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                    if (streamPercent != lastPercent) {
                                        lastPercent = streamPercent
                                        val overallProgress = 25 + (streamPercent * 45 / 100)
                                        val readFormatted = android.text.format.Formatter.formatFileSize(context, totalBytesRead)
                                        GoogleDriveSyncProgressTracker.updateProgress(
                                            context,
                                            "Cloud Restore",
                                            "Downloading",
                                            overallProgress,
                                            "Downloading backup package from Google Drive ($streamPercent% | $readFormatted of $formattedTotal)..."
                                        )
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    downloadSuccess = true
                } else {
                    Log.e(TAG, "Failed downloading zip backup: code=${response.code}")
                }
            }

            if (!downloadSuccess) {
                tempFile.delete()
                val msg = "Failed to download backup package from Google Drive."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Extracting & Reading", 75, "Extracting and restoring database records...")
            val importSuccess = tempFile.inputStream().use { fis ->
                DatabaseBackupHelper.importDataFromStream(context, database, fis)
            }

            tempFile.delete()

            if (importSuccess) {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("gd_all_last_sync_timestamp", System.currentTimeMillis()).apply()
                val successMsg = "Successfully restored all app data and files from Google Drive!"
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Completed", 100, successMsg, isFinished = true)
                Pair(true, successMsg)
            } else {
                val errMsg = "Failed to import backup package into local database."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Failed", 0, errMsg, isFinished = true, isError = true)
                Pair(false, errMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring all app data", e)
            val errMsg = "Restore Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Restore", "Failed", 0, errMsg, isFinished = true, isError = true)
            Pair(false, errMsg)
        }
    }

    /**
     * Checks and retrieves all available Drive data.
     */
    suspend fun checkAndRetrieveDriveData(
        context: Context,
        database: com.example.data.AppDatabase
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context)
            ?: return@withContext Pair(false, "Authentication required.")

        try {
            val focusId = findFileId(token, "focus_backup.json")
            val dbId = findFileId(token, "app_data_backup.zip")

            if (focusId == null && dbId == null) {
                return@withContext Pair(false, "No existing backup files found.")
            }

            val results = mutableListOf<String>()
            var anySuccess = false

            if (dbId != null) {
                val (success, msg) = restoreAllAppData(context, database)
                if (success) {
                    anySuccess = true
                    results.add("App database restored.")
                } else {
                    results.add("App database restore failed: $msg")
                }
            }

            if (focusId != null) {
                val (success, msg) = restoreFocusData(context)
                if (success) {
                    anySuccess = true
                    results.add("Focus data restored.")
                } else {
                    results.add("Focus data restore failed: $msg")
                }
            }

            Pair(anySuccess, results.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndRetrieveDriveData", e)
            Pair(false, e.localizedMessage ?: "Unknown restore error.")
        }
    }

    /**
     * Restores application settings and preferences from Google Drive.
     */
    suspend fun restoreAppSettingsFromDrive(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired) ?: return@withContext Pair(false, "Google Drive auth required.")
        try {
            val vault = GoogleDriveUploadManager.ensureVaultStructureAndReadme(token)
            val backupsId = vault?.backupsId
            val fileName = "app_settings_sync.json"
            val fileId = if (backupsId != null) {
                GoogleDriveUploadManager.findFileInFolder(token, fileName, backupsId)
            } else {
                findFileId(token, fileName)
            } ?: return@withContext Pair(false, "No settings backup found on Google Drive.")

            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@withContext Pair(false, "Failed to download settings (HTTP ${res.code}).")
                val body = res.body?.string() ?: return@withContext Pair(false, "Empty settings file.")
                val rootJson = JSONObject(body)

                fun applyJsonToSharedPrefs(prefName: String, jsonKey: String) {
                    val subObj = rootJson.optJSONObject(jsonKey) ?: return
                    val editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
                    val keys = subObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = subObj.get(k)
                        when (v) {
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Double -> editor.putFloat(k, v.toFloat())
                            is String -> editor.putString(k, v)
                            else -> editor.putString(k, v.toString())
                        }
                    }
                    editor.apply()
                }

                applyJsonToSharedPrefs("app_prefs", "app_prefs")
                applyJsonToSharedPrefs("app_settings", "app_settings")
                applyJsonToSharedPrefs("countdown_settings_prefs", "countdown_settings_prefs")
                applyJsonToSharedPrefs("strict_mode_prefs", "strict_mode_prefs")
                applyJsonToSharedPrefs("app_calendar_prefs", "app_calendar_prefs")

                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("gd_settings_last_restore_timestamp", System.currentTimeMillis()).apply()

                Pair(true, "Settings restored successfully from Google Drive.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring settings from Drive", e)
            Pair(false, "Drive Restore Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
