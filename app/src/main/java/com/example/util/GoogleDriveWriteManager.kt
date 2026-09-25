package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GoogleDriveWriteManager
 *
 * Dedicated manager for writing, mutating, deleting, creating Docs/Sheets,
 * and managing permissions and file metadata on Google Drive.
 */
object GoogleDriveWriteManager {

    private const val TAG = "GoogleDriveWrite"
    private val client by lazy { NetworkTrafficManager.createOkHttpClientBuilder(NetworkTrafficManager.TrafficCategory.CLOUD_BACKUP).build() }
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    class ProgressRequestBody(
        private val file: java.io.File,
        private val contentType: okhttp3.MediaType?,
        private val onProgress: (bytesSent: Long, totalBytes: Long, percent: Int) -> Unit
    ) : okhttp3.RequestBody() {
        override fun contentType(): okhttp3.MediaType? = contentType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: okio.BufferedSink) {
            val totalBytes = file.length()
            if (totalBytes <= 0) return
            var bytesSent = 0L
            val buffer = ByteArray(8192)
            var lastPercent = -1

            file.inputStream().use { inputStream ->
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    sink.write(buffer, 0, read)
                    sink.flush()
                    bytesSent += read
                    val percent = ((bytesSent * 100) / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(bytesSent, totalBytes, percent)
                    }
                }
            }
        }
    }

    /**
     * Creates new file metadata in Google Drive AppData / Root.
     */
    fun createFileMetadata(accessToken: String, fileName: String): String? {
        val metaJson = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put("appDataFolder"))
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: return null
                    JSONObject(bodyStr).optString("id")
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file metadata for $fileName", e)
            null
        }
    }

    /**
     * Deletes a file from Google Drive by fileId.
     */
    suspend fun deleteGoogleDriveFile(
        context: Context,
        fileId: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext false
        deleteGoogleDriveFileDirect(token, fileId)
    }

    /**
     * Synchronous direct deletion of a Drive file using access token.
     */
    fun deleteGoogleDriveFileDirect(token: String, fileId: String): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting Drive file $fileId", e)
            false
        }
    }

    /**
     * Renames a Google Drive file by fileId.
     */
    suspend fun renameGoogleDriveFile(
        context: Context,
        fileId: String,
        newName: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext false

        val metaJson = JSONObject().apply {
            put("name", newName)
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .patch(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming Drive file $fileId to $newName", e)
            false
        }
    }

    /**
     * Creates a Google Doc with specified title and content.
     */
    suspend fun createGoogleDocWithContent(
        context: Context,
        title: String,
        content: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext null

        try {
            val metaJson = JSONObject().apply {
                put("name", title)
                put("mimeType", "application/vnd.google-apps.document")
            }

            val metaReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var docId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    docId = JSONObject(res.body?.string() ?: "{}").optString("id")
                }
            }

            if (docId == null) return@withContext null

            if (content.isNotBlank()) {
                val docUpdateJson = JSONObject().apply {
                    val requests = JSONArray()
                    val insertReq = JSONObject().apply {
                        val insertText = JSONObject().apply {
                            put("text", content)
                            put("location", JSONObject().apply { put("index", 1) })
                        }
                        put("insertText", insertText)
                    }
                    requests.put(insertReq)
                    put("requests", requests)
                }

                val docsReq = Request.Builder()
                    .url("https://docs.googleapis.com/v1/documents/$docId:batchUpdate")
                    .addHeader("Authorization", "Bearer $token")
                    .post(docUpdateJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(docsReq).execute().close()
            }

            "https://docs.google.com/document/d/$docId/edit"
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google Doc with content", e)
            null
        }
    }

    /**
     * Creates a Google Sheet with title and rows of data.
     */
    suspend fun createGoogleSheetWithContent(
        context: Context,
        title: String,
        rows: List<List<String>>,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext null

        try {
            val metaJson = JSONObject().apply {
                put("properties", JSONObject().apply { put("title", title) })
            }

            val metaReq = Request.Builder()
                .url("https://sheets.googleapis.com/v4/spreadsheets")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var sheetId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    sheetId = JSONObject(res.body?.string() ?: "{}").optString("spreadsheetId")
                }
            }

            if (sheetId == null) return@withContext null

            if (rows.isNotEmpty()) {
                val valueJson = JSONObject().apply {
                    put("range", "Sheet1!A1")
                    put("majorDimension", "ROWS")
                    val valuesArray = JSONArray()
                    rows.forEach { row ->
                        val rowArray = JSONArray()
                        row.forEach { cell -> rowArray.put(cell) }
                        valuesArray.put(rowArray)
                    }
                    put("values", valuesArray)
                }

                val appendReq = Request.Builder()
                    .url("https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/Sheet1!A1:append?valueInputOption=USER_ENTERED")
                    .addHeader("Authorization", "Bearer $token")
                    .post(valueJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(appendReq).execute().close()
            }

            "https://docs.google.com/spreadsheets/d/$sheetId/edit"
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google Sheet with content", e)
            null
        }
    }

    /**
     * Creates a new folder in Google Drive.
     */
    suspend fun createGoogleDriveFolder(
        context: Context,
        folderName: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext null

        try {
            val metaJson = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    JSONObject(res.body?.string() ?: "{}").optString("id")
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Drive folder", e)
            null
        }
    }

    /**
     * Sets public read permission on a Drive file.
     */
    fun makeFilePublic(token: String, fileId: String) {
        val permJson = JSONObject().apply {
            put("role", "reader")
            put("type", "anyone")
        }

        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/permissions")
            .addHeader("Authorization", "Bearer $token")
            .post(permJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "Error making file public: $fileId", e)
        }
    }

    /**
     * Sets public writer/editor permission on a Drive file.
     */
    fun makeFilePublicAndEditor(token: String, fileId: String) {
        val permJson = JSONObject().apply {
            put("role", "writer")
            put("type", "anyone")
        }

        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/permissions")
            .addHeader("Authorization", "Bearer $token")
            .post(permJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "Error making file public and editor: $fileId", e)
        }
    }

    /**
     * Recursively sets public editor permission on a folder and its contents.
     */
    suspend fun makeFolderAndContentsPublicAndEditorRecursive(
        token: String,
        folderId: String,
        collectedItems: MutableList<JSONObject>? = null
    ): Unit = withContext(Dispatchers.IO) {
        try {
            makeFilePublicAndEditor(token, folderId)
            val queryUrl = "https://www.googleapis.com/drive/v3/files?q='$folderId'+in+parents+and+trashed=false&fields=files(id,name,mimeType,webViewLink,size,modifiedTime)"
            val req = Request.Builder().url(queryUrl).addHeader("Authorization", "Bearer $token").get().build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val filesArr = JSONObject(res.body?.string() ?: "{}").optJSONArray("files")
                    if (filesArr != null) {
                        for (i in 0 until filesArr.length()) {
                            val f = filesArr.getJSONObject(i)
                            collectedItems?.add(f)
                            val subId = f.optString("id")
                            val mime = f.optString("mimeType")
                            makeFilePublicAndEditor(token, subId)
                            if (mime == "application/vnd.google-apps.folder") {
                                makeFolderAndContentsPublicAndEditorRecursive(token, subId, collectedItems)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recursive permissions update", e)
        }
    }

    /**
     * Extracts a file ID from a Google Drive web URL.
     */
    fun extractIdFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val patterns = listOf(
            Regex("[?&]id=([a-zA-Z0-9_-]+)"),
            Regex("/d/([a-zA-Z0-9_-]+)"),
            Regex("/folders/([a-zA-Z0-9_-]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return if (url.length in 20..60 && !url.contains("/")) url else null
    }

    /**
     * Deletes older duplicate files in a folder, keeping the latest file ID.
     */
    fun deleteOlderDuplicateFiles(token: String, folderId: String, fileName: String, keepLatestId: String) {
        try {
            val query = "name = '$fileName' and '$folderId' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id, name)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    for (i in 0 until files.length()) {
                        val fObj = files.getJSONObject(i)
                        val id = fObj.getString("id")
                        if (id != keepLatestId) {
                            deleteGoogleDriveFileDirect(token, id)
                            Log.i(TAG, "Deleted older duplicate file $id ($fileName) from Google Drive subfolder")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteOlderDuplicateFiles", e)
        }
    }

    /**
     * Deletes media on Google Drive by URL or file name.
     */
    suspend fun deleteMediaByUrlOrName(context: Context, urlOrName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = GoogleDriveReadManager.getAccessToken(context) ?: return@withContext false
            val extractedId = extractIdFromUrl(urlOrName)
            if (!extractedId.isNullOrEmpty()) {
                val success = deleteGoogleDriveFileDirect(token, extractedId)
                if (success) {
                    Log.i(TAG, "Successfully deleted Drive file $extractedId via URL deletion request")
                    return@withContext true
                }
            }

            val fileName = java.io.File(urlOrName).name
            if (fileName.isNotBlank()) {
                val query = "name = '$fileName' and trashed = false"
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id, name)"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val files = JSONObject(body).getJSONArray("files")
                        var anyDeleted = false
                        for (i in 0 until files.length()) {
                            val fObj = files.getJSONObject(i)
                            val id = fObj.getString("id")
                            if (deleteGoogleDriveFileDirect(token, id)) {
                                anyDeleted = true
                                Log.i(TAG, "Deleted Drive file $id matching name $fileName")
                            }
                        }
                        return@withContext anyDeleted
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteMediaByUrlOrName for $urlOrName", e)
        }
        return@withContext false
    }

    /**
     * Sends a system notification.
     */
    fun sendNotification(context: Context, title: String, message: String) {
        try {
            val channelId = "lifeos_drive_sync"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Google Drive Sync", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification: ${e.message}")
        }
    }

    /**
     * Creates an empty Google Doc and returns web link.
     */
    suspend fun createGoogleDoc(
        context: Context,
        title: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val link = createGoogleDocWithContent(context, title, "", onAuthResolutionRequired)
        if (link != null) Pair(true, link) else Pair(false, "Failed to create Google Doc.")
    }

    /**
     * Creates an empty Google Sheet and returns web link.
     */
    suspend fun createGoogleSheet(
        context: Context,
        title: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val link = createGoogleSheetWithContent(context, title, emptyList(), onAuthResolutionRequired)
        if (link != null) Pair(true, link) else Pair(false, "Failed to create Google Sheet.")
    }

    /**
     * Serializes FocusRecord list to pipe-delimited string.
     */
    private fun serializeFocusRecords(records: List<com.example.ui.FocusRecord>): String {
        return records.joinToString("\n") { r ->
            val encodedNotes = try {
                android.util.Base64.encodeToString(r.notes.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } catch (e: Exception) { "" }
            "${r.startTime}|${r.endTime}|${r.taskTitle}|${r.durationMinutes}|${r.dateString}|$encodedNotes|${r.durationSeconds}"
        }
    }

    /**
     * Backs up focus data to Google Drive AppData folder.
     */
    suspend fun backupFocusData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Preparing", 5, "Preparing Google Drive connection...")
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Authorization required. Please connect your Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Reading", 25, "Reading local focus timer and pomodoro history...")
            val localRecords = FocusTimerManager.loadFocusRecords(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val totalMinutes = prefs.getSafeInt("total_focus_minutes", 0)
            val pomosCount = prefs.getSafeInt("today_pomos_count", 0)

            val serializedRecords = serializeFocusRecords(localRecords)
            val backupJson = JSONObject().apply {
                put("focus_records_list", serializedRecords)
                put("total_focus_minutes", totalMinutes)
                put("today_pomos_count", pomosCount)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Connecting", 50, "Locating Google Drive Focus vault...")
            val vault = GoogleDriveUploadManager.ensureVaultStructureAndReadme(token)
            val targetFolderId = vault?.focusDataId
            val fileName = "focus_backup.json"

            var fileId = if (targetFolderId != null) GoogleDriveUploadManager.findFileInFolder(token, fileName, targetFolderId) else GoogleDriveReadManager.findFileId(token, fileName)
            if (fileId == null) {
                fileId = if (targetFolderId != null) GoogleDriveUploadManager.createFileMetadataInFolder(token, fileName, targetFolderId) else createFileMetadata(token, fileName)
                if (fileId == null) {
                    val msg = "Failed to initialize backup slot on Google Drive."
                    GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Failed", 0, msg, isFinished = true, isError = true)
                    return@withContext Pair(false, msg)
                }
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Sending", 75, "Sending focus records to Google Drive...")
            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .patch(backupJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    if (targetFolderId != null) {
                        deleteOlderDuplicateFiles(token, targetFolderId, fileName, keepLatestId = fileId)
                    }
                    makeFilePublic(token, fileId)
                    prefs.edit().putLong("gd_focus_last_sync_timestamp", System.currentTimeMillis()).apply()
                    val successMsg = "Successfully backed up focus data to Google Drive (${GoogleDriveUploadManager.PRIMARY_VAULT_FOLDER_NAME}/Focus_Data)."
                    GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Completed", 100, successMsg, isFinished = true)
                    Pair(true, successMsg)
                } else {
                    val errMsg = "Failed to upload focus backup to Google Drive."
                    GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Failed", 0, errMsg, isFinished = true, isError = true)
                    Pair(false, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up focus data", e)
            val errMsg = "Backup Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Focus Backup", "Failed", 0, errMsg, isFinished = true, isError = true)
            Pair(false, errMsg)
        }
    }

    /**
     * Backs up full app data and files to Google Drive.
     */
    suspend fun backupAllAppData(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Preparing", 5, "Preparing Google Drive connection...")
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Authorization required. Please connect your Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Reading & Compressing", 15, "Reading local database, tasks, journals, finances & files...")
            val tempFile = java.io.File(context.cacheDir, "temp_app_data_backup.zip")
            if (tempFile.exists()) tempFile.delete()

            val exportSuccess = tempFile.outputStream().use { fos ->
                DatabaseBackupHelper.exportDataToStream(context, database, fos)
            }

            if (!exportSuccess) {
                if (tempFile.exists()) tempFile.delete()
                val msg = "Failed to compile backup package locally."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Connecting", 45, "Locating LifeOS AppData folder in Google Drive...")
            val vault = GoogleDriveUploadManager.ensureVaultStructureAndReadme(token)
            val targetFolderId = vault?.backupsId

            val fileName = "app_data_backup.zip"
            var fileId = if (targetFolderId != null) GoogleDriveUploadManager.findFileInFolder(token, fileName, targetFolderId) else GoogleDriveReadManager.findFileId(token, fileName)
            if (fileId == null) {
                fileId = if (targetFolderId != null) GoogleDriveUploadManager.createFileMetadataInFolder(token, fileName, targetFolderId) else createFileMetadata(token, fileName)
                if (fileId == null) {
                    tempFile.delete()
                    val msg = "Failed to initialize backup slot in Google Drive."
                    GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Failed", 0, msg, isFinished = true, isError = true)
                    return@withContext Pair(false, msg)
                }
            }

            val totalFileSize = tempFile.length()
            val formattedTotal = android.text.format.Formatter.formatFileSize(context, totalFileSize)

            val requestBody = ProgressRequestBody(tempFile, "application/zip".toMediaType()) { bytesSent, total, percent ->
                val calculatedOverall = 50 + (percent * 42 / 100)
                val sentFormatted = android.text.format.Formatter.formatFileSize(context, bytesSent)
                GoogleDriveSyncProgressTracker.updateProgress(
                    context,
                    "Cloud Backup",
                    "Sending",
                    calculatedOverall,
                    "Sending backup package to Google Drive ($percent% | $sentFormatted of $formattedTotal)..."
                )
            }

            val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/zip")
                .patch(requestBody)
                .build()

            var uploadSuccess = false
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    uploadSuccess = true
                } else {
                    Log.e(TAG, "Error uploading zip: code=${response.code}")
                }
            }

            tempFile.delete()

            if (uploadSuccess) {
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Finalizing", 95, "Cleaning duplicate files & updating permissions...")
                if (targetFolderId != null) {
                    deleteOlderDuplicateFiles(token, targetFolderId, fileName, keepLatestId = fileId)
                }
                makeFilePublic(token, fileId)
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("gd_all_last_sync_timestamp", System.currentTimeMillis()).apply()
                val successMsg = "Successfully backed up all app data ($formattedTotal) to Google Drive (${GoogleDriveUploadManager.PRIMARY_VAULT_FOLDER_NAME}/App_Backups)."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Completed", 100, successMsg, isFinished = true)
                Pair(true, successMsg)
            } else {
                val errMsg = "Failed to upload backup package to Google Drive."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Failed", 0, errMsg, isFinished = true, isError = true)
                Pair(false, errMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up all app data", e)
            val errMsg = "Backup Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Cloud Backup", "Failed", 0, errMsg, isFinished = true, isError = true)
            Pair(false, errMsg)
        }
    }

    /**
     * Performs a 2-way sync for Google Keep Notes.
     */
    suspend fun syncKeepNotes(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Preparing", 5, "Connecting to Google Drive...")
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Authorization required. Please connect your Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Reading", 25, "Reading local Google Keep notes...")
            val keepNoteDao = database.keepNoteDao()
            val localNotes = keepNoteDao.getAllKeepNotesDirect()

            val fileName = "google_keep_notes.json"
            var fileId = GoogleDriveReadManager.findFileId(token, fileName)
            val remoteNotes = mutableListOf<com.example.data.KeepNote>()

            if (fileId != null) {
                GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Downloading", 50, "Downloading remote notes from Google Drive...")
                val cloudContent = GoogleDriveReadManager.downloadBackupFileContent(token, fileId)
                if (!cloudContent.isNullOrBlank()) {
                    val jsonArray = JSONArray(cloudContent)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        remoteNotes.add(
                            com.example.data.KeepNote(
                                title = obj.optString("title", ""),
                                content = obj.optString("content", ""),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                isPinned = obj.optBoolean("isPinned", false),
                                colorHex = obj.optString("colorHex", "#202124"),
                                isSynced = true,
                                websiteUrl = if (obj.isNull("websiteUrl")) null else obj.optString("websiteUrl"),
                                customLogoUrl = if (obj.isNull("customLogoUrl")) null else obj.optString("customLogoUrl")
                            )
                        )
                    }
                }
            } else {
                fileId = createFileMetadata(token, fileName)
                if (fileId == null) {
                    val msg = "Failed to initialize Google Keep Notes space in Google Drive."
                    GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Failed", 0, msg, isFinished = true, isError = true)
                    return@withContext Pair(false, msg)
                }
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Merging", 70, "Merging notes and resolving edits...")
            val mergedMap = mutableMapOf<String, com.example.data.KeepNote>()
            for (note in localNotes) {
                if (DeletedKeepNoteLogHelper.isNoteDeletedLocally(context, note.title, note.content)) {
                    continue
                }
                val signature = "${note.title.trim()}|${note.content.trim()}"
                mergedMap[signature] = note
            }
            for (remote in remoteNotes) {
                if (DeletedKeepNoteLogHelper.isNoteDeletedLocally(context, remote.title, remote.content)) {
                    continue
                }
                val signature = "${remote.title.trim()}|${remote.content.trim()}"
                val existing = mergedMap[signature]
                if (existing == null || remote.timestamp > existing.timestamp) {
                    mergedMap[signature] = remote
                }
            }

            val mergedList = mergedMap.values.toList()

            val uploadArray = JSONArray()
            for (note in mergedList) {
                val obj = JSONObject().apply {
                    put("title", note.title)
                    put("content", note.content)
                    put("timestamp", note.timestamp)
                    put("isPinned", note.isPinned)
                    put("colorHex", note.colorHex)
                    put("websiteUrl", note.websiteUrl ?: JSONObject.NULL)
                    put("customLogoUrl", note.customLogoUrl ?: JSONObject.NULL)
                }
                uploadArray.put(obj)
            }

            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Sending", 85, "Uploading updated notes to Google Drive...")
            val contentStr = uploadArray.toString()

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .patch(contentStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val uploadSuccess = client.newCall(request).execute().use { it.isSuccessful }
            if (!uploadSuccess) {
                val msg = "Failed to write synchronized notes back to Google Drive."
                GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Failed", 0, msg, isFinished = true, isError = true)
                return@withContext Pair(false, msg)
            }

            keepNoteDao.clearAllKeepNotes()
            for (note in mergedList) {
                keepNoteDao.insertKeepNote(note.copy(isSynced = true))
            }

            val successMsg = "Successfully merged and synchronized ${mergedList.size} notes!"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Completed", 100, successMsg, isFinished = true)
            Pair(true, successMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Keep notes", e)
            val errMsg = "Sync Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Keep Notes Sync", "Failed", 0, errMsg, isFinished = true, isError = true)
            Pair(false, errMsg)
        }
    }

    /**
     * Backs up and syncs application settings to Google Drive using deterministic constant UIDs.
     */
    suspend fun backupAppSettingsToDrive(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Google Drive authorization required to sync settings."
            return@withContext Pair(false, msg)
        }

        try {
            GoogleDriveSettingsRegistryManager.synchronizeSettingsWithDrive(context, token)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing settings to Drive", e)
            Pair(false, "Drive Sync Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
