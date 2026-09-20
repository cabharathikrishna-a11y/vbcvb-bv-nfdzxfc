package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * GoogleDriveUploadManager
 *
 * Dedicated manager for handling all upload operations, folder organization,
 * single-folder enforcement, and duplicate cleanup on Google Drive.
 * Enforces a SINGLE root folder ('LifeOS_AppData') with structured subfolders,
 * automatically purging old duplicate folders and obsolete backups.
 */
object GoogleDriveUploadManager {

    private const val TAG = "GoogleDriveUpload"
    const val PRIMARY_VAULT_FOLDER_NAME = "LifeOS_AppData"
    private val LEGACY_VAULT_FOLDER_NAMES = listOf("LifeOS_Cloud_Vault", "LifeOS_Backup", "LifeOS_Files", "LifeOS_Data")

    private val client by lazy { NetworkTrafficManager.createOkHttpClientBuilder(NetworkTrafficManager.TrafficCategory.CLOUD_BACKUP).build() }
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    data class VaultFolders(
        val rootId: String,
        val backupsId: String,
        val focusDataId: String,
        val taskAttachmentsId: String,
        val sharedMediaId: String,
        val generalFilesId: String
    )

    /**
     * Uploads full application data backup (JSON/ZIP) to the single dedicated App_Backups subfolder.
     */
    suspend fun uploadAppDataBackup(
        context: Context,
        database: AppDatabase,
        backupJsonString: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
                ?: return@withContext Pair(false, "Failed to acquire Google Drive access token.")

            val vault = ensureVaultStructureAndReadme(token)
            val targetFolderId = vault?.backupsId
                ?: return@withContext Pair(false, "Could not initialize LifeOS AppData folder in Google Drive.")

            val fileName = "app_data_backup.zip"
            var fileId = findFileInFolder(token, fileName, targetFolderId)
            if (fileId == null) {
                fileId = createFileMetadataInFolder(token, fileName, targetFolderId)
                    ?: return@withContext Pair(false, "Could not create backup metadata in Google Drive.")
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(backupJsonString.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Successfully uploaded app data backup to Google Drive App_Backups.")
                    GoogleDriveWriteManager.deleteOlderDuplicateFiles(token, targetFolderId, fileName, keepLatestId = fileId)
                    GoogleDriveWriteManager.makeFilePublic(token, fileId)
                    Pair(true, "App data uploaded successfully to Google Drive ($PRIMARY_VAULT_FOLDER_NAME/App_Backups).")
                } else {
                    val err = response.body?.string() ?: ""
                    Log.e(TAG, "Failed to upload backup content: ${response.code} $err")
                    Pair(false, "Drive upload failed with status ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading app data backup to Drive", e)
            Pair(false, "Upload error: ${e.message}")
        }
    }

    /**
     * Uploads focus session records to the single dedicated Focus_Data subfolder.
     */
    suspend fun uploadFocusData(
        context: Context,
        focusJsonString: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
                ?: return@withContext Pair(false, "No Google access token.")

            val vault = ensureVaultStructureAndReadme(token)
            val targetFolderId = vault?.focusDataId
                ?: return@withContext Pair(false, "Failed to initialize Focus_Data folder in Google Drive.")

            val fileName = "focus_backup.json"
            var fileId = findFileInFolder(token, fileName, targetFolderId)
            if (fileId == null) {
                fileId = createFileMetadataInFolder(token, fileName, targetFolderId)
                    ?: return@withContext Pair(false, "Failed to initialize Drive file metadata.")
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(focusJsonString.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    GoogleDriveWriteManager.deleteOlderDuplicateFiles(token, targetFolderId, fileName, keepLatestId = fileId)
                    GoogleDriveWriteManager.makeFilePublic(token, fileId)
                    Pair(true, "Focus data backup uploaded successfully ($PRIMARY_VAULT_FOLDER_NAME/Focus_Data).")
                } else {
                    Pair(false, "Upload failed HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Focus data upload error", e)
            Pair(false, "Focus upload error: ${e.message}")
        }
    }

    /**
     * Uploads a public media file directly to Google Drive into its designated subfolder and sets public read permissions.
     */
    suspend fun uploadPublicMediaFileDirect(
        context: Context,
        token: String,
        file: File,
        mimeType: String = "image/jpeg",
        categoryFolder: String = "General_Files"
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "Media file does not exist: ${file.absolutePath}")
                return@withContext null
            }

            val vault = ensureVaultStructureAndReadme(token)
            val folderId = when (categoryFolder) {
                "Task_Attachments" -> vault?.taskAttachmentsId
                "Shared_Media" -> vault?.sharedMediaId
                "App_Backups" -> vault?.backupsId
                "Focus_Data" -> vault?.focusDataId
                else -> vault?.generalFilesId
            }

            if (folderId != null) {
                return@withContext uploadPublicMediaFileToFolderDirect(context, token, file, folderId, mimeType)
            }

            // Fallback Create Metadata in root if vault could not be prepared
            val metaJson = JSONObject().apply {
                put("name", file.name)
                put("mimeType", mimeType)
            }

            val metaReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var createdFileId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    val respObj = JSONObject(res.body?.string() ?: "{}")
                    createdFileId = respObj.optString("id")
                }
            }

            val fileId = createdFileId ?: return@withContext null

            // Upload Content
            val mediaType = mimeType.toMediaType()
            val uploadReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mediaType))
                .build()

            client.newCall(uploadReq).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.e(TAG, "Media content upload failed: ${res.code}")
                    return@withContext null
                }
            }

            // Set Public Permissions
            GoogleDriveWriteManager.makeFilePublic(token, fileId)

            val sharingUrl = "https://drive.google.com/uc?export=view&id=$fileId"
            Log.i(TAG, "Successfully uploaded public media file. Sharing URL: $sharingUrl")
            sharingUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error in uploadPublicMediaFileDirect", e)
            null
        }
    }

    /**
     * Uploads a media file directly to a specific folder in Google Drive.
     */
    suspend fun uploadPublicMediaFileToFolderDirect(
        context: Context,
        token: String,
        file: File,
        folderId: String,
        mimeType: String = "image/jpeg"
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null

            val metaJson = JSONObject().apply {
                put("name", file.name)
                put("mimeType", mimeType)
                put("parents", org.json.JSONArray().put(folderId))
            }

            val metaReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var createdFileId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    val respObj = JSONObject(res.body?.string() ?: "{}")
                    createdFileId = respObj.optString("id")
                }
            }

            val fileId = createdFileId ?: return@withContext null

            val mediaType = mimeType.toMediaType()
            val uploadReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mediaType))
                .build()

            client.newCall(uploadReq).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
            }

            GoogleDriveWriteManager.makeFilePublicAndEditor(token, fileId)
            "https://drive.google.com/uc?export=view&id=$fileId"
        } catch (e: Exception) {
            Log.e(TAG, "Error in uploadPublicMediaFileToFolderDirect", e)
            null
        }
    }

    /**
     * High-level maintenance command to consolidate multiple Drive app folders,
     * enforce the single 'LifeOS_AppData' root vault, migrate files, and purge obsolete backups.
     */
    suspend fun manageAndCleanDriveAppData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext Pair(false, "Authorization required. Please connect your Google Drive.")

        try {
            val vault = consolidateAndCleanDriveAppData(token)
            if (vault != null) {
                Pair(
                    true,
                    "✅ Google Drive successfully organized!\n- Root Vault: $PRIMARY_VAULT_FOLDER_NAME\n- Subfolders: App_Backups, Focus_Data, Task_Attachments, Shared_Media, General_Files\n- All duplicate and legacy folders purged."
                )
            } else {
                Pair(false, "Failed to organize Drive folders. Please check connection.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Drive vault cleanup", e)
            Pair(false, "Cleanup Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Ensures exactly ONE root folder ('LifeOS_AppData') and the subfolders exist,
     * merging contents from duplicates and deleting redundant folders.
     */
    fun ensureVaultStructureAndReadme(token: String): VaultFolders? {
        return consolidateAndCleanDriveAppData(token)
    }

    /**
     * Consolidates duplicate root folders and subfolders into a single clean structure.
     */
    fun consolidateAndCleanDriveAppData(token: String): VaultFolders? {
        try {
            // 1. Find all candidate root folders (primary and legacy)
            val candidateNames = listOf(PRIMARY_VAULT_FOLDER_NAME) + LEGACY_VAULT_FOLDER_NAMES
            val allFoundRootFolders = mutableListOf<JSONObject>()

            for (name in candidateNames) {
                val folders = queryFoldersByName(token, name)
                allFoundRootFolders.addAll(folders)
            }

            // Pick or create the single primary root folder
            val primaryFolderId: String
            if (allFoundRootFolders.isEmpty()) {
                primaryFolderId = createFolder(token, PRIMARY_VAULT_FOLDER_NAME) ?: return null
            } else {
                // Keep the first one found, prefer PRIMARY_VAULT_FOLDER_NAME
                val best = allFoundRootFolders.find { it.optString("name") == PRIMARY_VAULT_FOLDER_NAME }
                    ?: allFoundRootFolders.first()
                primaryFolderId = best.optString("id")

                // Ensure primary folder is named PRIMARY_VAULT_FOLDER_NAME
                if (best.optString("name") != PRIMARY_VAULT_FOLDER_NAME) {
                    renameFileOrFolder(token, primaryFolderId, PRIMARY_VAULT_FOLDER_NAME)
                }

                // Delete all other duplicate root folders after moving any contents
                for (otherFolder in allFoundRootFolders) {
                    val otherId = otherFolder.optString("id")
                    if (otherId != primaryFolderId && otherId.isNotEmpty()) {
                        Log.i(TAG, "Consolidating duplicate root folder: ${otherFolder.optString("name")} ($otherId)")
                        migrateAllContentsToFolder(token, sourceFolderId = otherId, destFolderId = primaryFolderId)
                        deleteGoogleDriveFileDirect(token, otherId)
                    }
                }
            }

            // 2. Ensure and consolidate subfolders inside the single root
            val backupsId = findOrCreateSingleSubFolder(token, primaryFolderId, "App_Backups") ?: primaryFolderId
            val focusDataId = findOrCreateSingleSubFolder(token, primaryFolderId, "Focus_Data") ?: primaryFolderId
            val taskAttachmentsId = findOrCreateSingleSubFolder(token, primaryFolderId, "Task_Attachments") ?: primaryFolderId
            val sharedMediaId = findOrCreateSingleSubFolder(token, primaryFolderId, "Shared_Media") ?: primaryFolderId
            val generalFilesId = findOrCreateSingleSubFolder(token, primaryFolderId, "General_Files") ?: primaryFolderId

            // 3. Purge older duplicate backup archives in App_Backups
            cleanDuplicateFilesByPattern(token, backupsId, "app_data_backup.zip")
            cleanDuplicateFilesByPattern(token, backupsId, "lifeos_full_data_backup.zip")

            // 4. Purge older duplicate focus backups in Focus_Data
            cleanDuplicateFilesByPattern(token, focusDataId, "focus_backup.json")

            // 5. Clean orphaned loose app files in Drive root and move to proper subfolders
            cleanRootOrphanedAppFiles(token, backupsFolderId = backupsId, focusFolderId = focusDataId)

            // 6. Update README in the single root folder
            ensureReadmeFile(token, primaryFolderId)

            return VaultFolders(
                rootId = primaryFolderId,
                backupsId = backupsId,
                focusDataId = focusDataId,
                taskAttachmentsId = taskAttachmentsId,
                sharedMediaId = sharedMediaId,
                generalFilesId = generalFilesId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error consolidating Drive App Data", e)
            return null
        }
    }

    private fun findOrCreateSingleSubFolder(token: String, parentId: String, subFolderName: String): String? {
        try {
            val query = "name = '$subFolderName' and '$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            var primarySubFolderId: String? = null
            val duplicateSubFolderIds = mutableListOf<String>()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    for (i in 0 until files.length()) {
                        val fId = files.getJSONObject(i).getString("id")
                        if (i == 0) {
                            primarySubFolderId = fId
                        } else {
                            duplicateSubFolderIds.add(fId)
                        }
                    }
                }
            }

            if (primarySubFolderId == null) {
                // Create subfolder
                primarySubFolderId = createFolderInParent(token, subFolderName, parentId)
            } else if (duplicateSubFolderIds.isNotEmpty()) {
                // Merge duplicate subfolders into the primary subfolder and delete them
                for (dupId in duplicateSubFolderIds) {
                    Log.i(TAG, "Merging duplicate subfolder $subFolderName ($dupId) into $primarySubFolderId")
                    migrateAllContentsToFolder(token, sourceFolderId = dupId, destFolderId = primarySubFolderId)
                    deleteGoogleDriveFileDirect(token, dupId)
                }
            }

            return primarySubFolderId
        } catch (e: Exception) {
            Log.e(TAG, "Error in findOrCreateSingleSubFolder $subFolderName", e)
            return null
        }
    }

    private fun cleanDuplicateFilesByPattern(token: String, folderId: String, fileName: String) {
        try {
            val query = "name = '$fileName' and '$folderId' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,modifiedTime)&orderBy=modifiedTime desc"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    // Keep the first (most recent) file, delete the older ones
                    if (files.length() > 1) {
                        for (i in 1 until files.length()) {
                            val oldId = files.getJSONObject(i).getString("id")
                            Log.i(TAG, "Purging old duplicate file $oldId ($fileName) from folder $folderId")
                            deleteGoogleDriveFileDirect(token, oldId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanDuplicateFilesByPattern for $fileName", e)
        }
    }

    private fun cleanRootOrphanedAppFiles(token: String, backupsFolderId: String, focusFolderId: String) {
        try {
            // Find any loose app_data_backup.zip or focus_backup.json at the root of drive
            val query = "(name = 'app_data_backup.zip' or name = 'focus_backup.json' or name = 'lifeos_full_data_backup.zip') and 'root' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,parents)"
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
                        val name = fObj.getString("name")
                        val destFolderId = if (name.contains("focus")) focusFolderId else backupsFolderId
                        Log.i(TAG, "Relocating orphaned root file $name ($id) to subfolder $destFolderId")
                        moveFile(token, fileId = id, oldParentId = "root", newParentId = destFolderId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning root orphaned app files", e)
        }
    }

    private fun migrateAllContentsToFolder(token: String, sourceFolderId: String, destFolderId: String) {
        try {
            val query = "'$sourceFolderId' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,mimeType)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val fileId = f.getString("id")
                        moveFile(token, fileId = fileId, oldParentId = sourceFolderId, newParentId = destFolderId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating contents from $sourceFolderId to $destFolderId", e)
        }
    }

    private fun moveFile(token: String, fileId: String, oldParentId: String, newParentId: String): Boolean {
        try {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?addParents=$newParentId&removeParents=$oldParentId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { res ->
                return res.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file $fileId", e)
            return false
        }
    }

    private fun renameFileOrFolder(token: String, fileId: String, newName: String): Boolean {
        try {
            val body = JSONObject().put("name", newName)
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .addHeader("Authorization", "Bearer $token")
                .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { res ->
                return res.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file/folder $fileId to $newName", e)
            return false
        }
    }

    private fun queryFoldersByName(token: String, name: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        try {
            val query = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,createdTime)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val array = JSONObject(body).getJSONArray("files")
                    for (i in 0 until array.length()) {
                        result.add(array.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying folders by name: $name", e)
        }
        return result
    }

    private fun createFolder(token: String, folderName: String): String? {
        try {
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val newFolderId = JSONObject(response.body?.string() ?: "").getString("id")
                    GoogleDriveWriteManager.makeFilePublicAndEditor(token, newFolderId)
                    return newFolderId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder $folderName", e)
        }
        return null
    }

    private fun createFolderInParent(token: String, folderName: String, parentId: String): String? {
        try {
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", org.json.JSONArray().apply { put(parentId) })
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val newFolderId = JSONObject(response.body?.string() ?: "").getString("id")
                    GoogleDriveWriteManager.makeFilePublicAndEditor(token, newFolderId)
                    return newFolderId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder $folderName in parent $parentId", e)
        }
        return null
    }

    fun deleteGoogleDriveFileDirect(token: String, fileId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting Drive file/folder: $fileId", e)
            false
        }
    }

    private fun ensureReadmeFile(token: String, rootFolderId: String) {
        try {
            val readmeName = "README_DO_NOT_DELETE.txt"
            val existingId = findFileInFolder(token, readmeName, rootFolderId)

            val readmeContent = """
===================================================================
⚠️ OFFICIAL LIFEOS CLOUD DATA VAULT ⚠️
===================================================================

WHY IS THIS FOLDER STORED HERE?
This single designated Google Drive folder ('$PRIMARY_VAULT_FOLDER_NAME') serves as the unified
cloud synchronization vault, media asset storage, and disaster recovery backup hub for LifeOS.
All app data is consolidated here to keep your Google Drive clean and uncluttered.

ORGANIZED FOLDER STRUCTURE:
-------------------------------------------------------------------
1. README_DO_NOT_DELETE.txt
   Manifest explaining the cloud architecture, folder structure, and safety rules.

2. App_Backups/
   Contains the latest database & app configuration backup package (app_data_backup.zip).
   Older duplicate backup versions are automatically purged.

3. Focus_Data/
   Contains Pomodoro and focus timer history logs (focus_backup.json).

4. Task_Attachments/
   Stores photo, video, audio, and document media attached directly to tasks.

5. Shared_Media/
   Stores images, videos, and media files uploaded during peer messaging or journal reflections.

6. General_Files/
   Stores documents, notes, and user files uploaded through the in-app File Explorer.

===================================================================
Managed Automatically by LifeOS Cloud Vault Sync Engine
===================================================================
            """.trimIndent()

            val fileId = existingId ?: createFileMetadataInFolder(token, readmeName, rootFolderId)
            if (fileId != null) {
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
                val requestBody = readmeContent.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "text/plain; charset=utf-8")
                    .patch(requestBody)
                    .build()
                client.newCall(request).execute().close()
                GoogleDriveWriteManager.makeFilePublic(token, fileId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureReadmeFile", e)
        }
    }

    fun findOrCreateSharedFolder(token: String, folderName: String): String? {
        val vault = ensureVaultStructureAndReadme(token)
        return vault?.rootId
    }

    fun findFileInFolder(token: String, name: String, folderId: String): String? {
        try {
            val query = "name = '$name' and '$folderId' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    if (files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding file $name in folder $folderId", e)
        }
        return null
    }

    fun createFileMetadataInFolder(token: String, name: String, folderId: String): String? {
        try {
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", name)
                put("parents", org.json.JSONArray().apply { put(folderId) })
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    return JSONObject(response.body?.string() ?: "").getString("id")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file metadata $name in folder $folderId", e)
        }
        return null
    }
}
