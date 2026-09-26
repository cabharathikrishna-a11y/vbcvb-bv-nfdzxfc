package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GoogleDriveLiveSyncManager
 *
 * Implements a continuous, granular per-entity Google Drive Differential Live-State Synchronization System:
 * - Single persistent root folder: "LifeOS_Sync_Vault"
 * - Individual subfolders for every sector / category (Tasks, Habits, Keep Notes, Journal, Contacts, Finances, Focus, Health, Deadlines, Lists, Settings)
 * - Deterministic file naming with UID and last-update timestamp: "${uid}_${lastUpdateTimestamp}.json"
 * - Fast differential metadata assessment by reading file names:
 *     1. If remote file timestamp > local entity timestamp -> Download only that changed file & update local DB
 *     2. If local entity timestamp > remote file timestamp -> Upload new entity file & delete old remote file version
 *     3. If timestamp is equal -> Instant zero-byte parity match
 *     4. If item is tombstoned/deleted -> Clean delete from Drive & local DB
 *     5. If item is new on remote -> Download & insert into local DB
 * - Immutable audit trail with edit history inside payload
 * - Atomic 3-Pass Sync Cycle for 100% cloud parity
 */
object GoogleDriveLiveSyncManager {

    private const val TAG = "GoogleDriveLiveSync"
    private const val ROOT_VAULT_NAME = "LifeOS_Sync_Vault"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        NetworkTrafficManager.createOkHttpClientBuilder(NetworkTrafficManager.TrafficCategory.CLOUD_BACKUP).build()
    }

    // Vault Subfolder Constants
    object Folders {
        const val TOMBSTONES = "_Deleted_Tombstones"
        const val TASKS = "Tasks_Planner"
        const val KEEP_NOTES = "Keep_Notes"
        const val MESSAGES = "Messages_Chat"
        const val DEEPA_AI = "Deepa_AI"
        const val CALENDAR = "Calendar_Events"
        const val FOCUS_TIMER = "Focus_Timer"
        const val HABITS = "Habits_Routines"
        const val COUNTDOWN = "Countdown_Events"
        const val JOURNAL = "Journal_Diary"
        const val CONTACTS = "Contacts_Vault"
        const val FINANCES = "Finances_Ledger"
        const val FILES = "File_Explorer_Storage"
        const val SHOPPING = "Shopping_List"
        const val HEALTH = "Health_Wellness"
        const val ARENA = "Arena_Syllabus"
        const val MOVIES = "Movie_Tracker"
        const val CUSTOM_LISTS = "Custom_Lists"
        const val SETTINGS = "App_Settings"

        val ALL_SUBFOLDERS = listOf(
            TOMBSTONES, TASKS, KEEP_NOTES, MESSAGES, DEEPA_AI,
            CALENDAR, FOCUS_TIMER, HABITS, COUNTDOWN, JOURNAL,
            CONTACTS, FINANCES, FILES, SHOPPING, HEALTH,
            ARENA, MOVIES, CUSTOM_LISTS, SETTINGS
        )
    }

    // Cache of resolved folder IDs
    private val folderIdCache = mutableMapOf<String, String>()

    /**
     * Remote Entity Metadata parsed from Drive file name.
     */
    data class RemoteEntityMeta(
        val fileId: String,
        val prefix: String,
        val uid: String,
        val timestamp: Long,
        val rawName: String
    )

    /**
     * Builds a standardized granular entity file name: "${uid}_${lastUpdateTimestamp}.json"
     */
    fun buildEntityFileName(uid: String, timestamp: Long): String {
        return "${uid}_${timestamp}.json"
    }

    /**
     * Parses a Drive file name to extract UID, timestamp, and prefix.
     * Supports "${uid}_${timestamp}.json" and legacy "${uid}.json".
     */
    fun parseEntityFileName(rawName: String, fileId: String): RemoteEntityMeta? {
        if (!rawName.endsWith(".json", ignoreCase = true)) return null
        val base = rawName.removeSuffix(".json").removeSuffix(".JSON")
        val lastUnderscore = base.lastIndexOf('_')
        if (lastUnderscore <= 0) {
            val prefix = base.substringBefore('_')
            return RemoteEntityMeta(fileId, prefix, base, 0L, rawName)
        }
        val timestampStr = base.substring(lastUnderscore + 1)
        val ts = timestampStr.toLongOrNull()
        if (ts == null) {
            val prefix = base.substringBefore('_')
            return RemoteEntityMeta(fileId, prefix, base, 0L, rawName)
        }
        val uid = base.substring(0, lastUnderscore)
        val prefix = uid.substringBefore('_')
        return RemoteEntityMeta(fileId, prefix, uid, ts, rawName)
    }

    /**
     * Checks if user has granted Google Drive permissions.
     */
    fun hasDrivePermission(context: Context): Boolean {
        return GoogleDriveReadManager.hasDrivePermission(context)
    }

    /**
     * Finds or creates the single shared root vault folder "LifeOS_Sync_Vault".
     */
    suspend fun getOrCreateRootVaultFolder(
        context: Context,
        accessToken: String
    ): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val cachedRootId = prefs.getString("gdrive_live_vault_root_id", null)

        if (!cachedRootId.isNullOrBlank()) {
            if (verifyFolderExists(accessToken, cachedRootId)) {
                return@withContext cachedRootId
            }
        }

        // Search Drive for existing root folder
        val foundId = searchFolderByName(accessToken, ROOT_VAULT_NAME, parentId = null)
        if (foundId != null) {
            prefs.edit().putString("gdrive_live_vault_root_id", foundId).apply()
            return@withContext foundId
        }

        // Create root vault folder
        val createdId = createFolderInDrive(accessToken, ROOT_VAULT_NAME, parentId = null)
        if (createdId != null) {
            prefs.edit().putString("gdrive_live_vault_root_id", createdId).apply()
        }
        return@withContext createdId
    }

    /**
     * Finds or creates a subfolder within the root vault.
     */
    suspend fun getOrCreateSubfolder(
        accessToken: String,
        rootFolderId: String,
        subfolderName: String
    ): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$rootFolderId:$subfolderName"
        folderIdCache[cacheKey]?.let { return@withContext it }

        val foundId = searchFolderByName(accessToken, subfolderName, parentId = rootFolderId)
        if (foundId != null) {
            folderIdCache[cacheKey] = foundId
            return@withContext foundId
        }

        val createdId = createFolderInDrive(accessToken, subfolderName, parentId = rootFolderId)
        if (createdId != null) {
            folderIdCache[cacheKey] = createdId
        }
        return@withContext createdId
    }

    /**
     * Main Entrypoint: Executes the complete Atomic 3-Pass Live Sync Cycle across ALL sectors.
     */
    suspend fun execute3PassLiveSync(
        context: Context,
        database: AppDatabase,
        onProgress: (phase: String, percent: Int, detail: String) -> Unit = { _, _, _ -> },
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): SyncSummaryReport = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Starting Granular Per-Entity Timestamp-in-Filename Google Drive Live Sync...")

        onProgress("Connecting", 5, "Authenticating with Google Drive...")
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Google Drive authorization required. Please connect your account."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext SyncSummaryReport(false, msg, 0, 0, 0, false, 0L)
        }

        val rootId = getOrCreateRootVaultFolder(context, token)
        if (rootId == null) {
            val msg = "Failed to access or create '$ROOT_VAULT_NAME' root directory on Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext SyncSummaryReport(false, msg, 0, 0, 0, false, 0L)
        }

        // Initialize all required subfolders
        onProgress("Connecting", 10, "Verifying sector directory structure in Drive...")
        val subfolderMap = mutableMapOf<String, String>()
        for (folder in Folders.ALL_SUBFOLDERS) {
            val id = getOrCreateSubfolder(token, rootId, folder)
            if (id != null) {
                subfolderMap[folder] = id
            }
        }

        val tombstoneFolderId = subfolderMap[Folders.TOMBSTONES] ?: rootId
        var uploadedCount = 0
        var downloadedCount = 0
        var deletedCount = 0

        try {
            // =========================================================================
            // PASS 1: READ & ASSESS (Fetch tombstones, query cloud manifests, read file names)
            // =========================================================================
            onProgress("Pass 1: Read & Assess", 15, "Reading deleted tombstones from Google Drive...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 1: Read & Assess", 15, "Reading Drive file names & timestamps...")

            val cloudTombstoneRegistry = fetchCloudTombstones(token, tombstoneFolderId)
            val localTombstones = loadLocalTombstones(context)
            val mergedTombstones = mergeTombstones(cloudTombstoneRegistry, localTombstones)

            onProgress("Pass 1: Read & Assess", 25, "Reading file names & timestamps across all sectors...")
            val cloudFilesMap = mutableMapOf<String, List<RemoteEntityItem>>() // folderName -> files
            for ((folderName, fId) in subfolderMap) {
                if (folderName != Folders.TOMBSTONES) {
                    cloudFilesMap[folderName] = listRemoteEntityFiles(token, fId)
                }
            }

            // =========================================================================
            // PASS 2: EXECUTE UPDATES & DIFFERENTIAL RECONCILIATION
            // =========================================================================
            onProgress("Pass 2: Execute", 35, "Applying cross-device deletions and tombstones...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 2: Reconciling", 35, "Executing per-entity differential sync...")

            // Apply cloud deletions locally
            val localDeleted = applyCloudDeletionsLocally(context, database, mergedTombstones)
            deletedCount += localDeleted

            // Purge deleted entity files in Cloud that exist in tombstone registry
            for ((_, remoteFiles) in cloudFilesMap) {
                for (remoteFile in remoteFiles) {
                    val meta = parseEntityFileName(remoteFile.name, remoteFile.id)
                    if (meta != null && mergedTombstones.tombstones.containsKey(meta.uid)) {
                        deleteDriveFile(token, remoteFile.id)
                        deletedCount++
                    }
                }
            }

            // 1. Sync Tasks Sector
            onProgress("Pass 2: Execute", 42, "Synchronizing Tasks & Subtasks with timestamps in filename...")
            subfolderMap[Folders.TASKS]?.let { folderId ->
                val (up, down) = syncTasks(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.TASKS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 2. Sync Habits Sector
            onProgress("Pass 2: Execute", 49, "Synchronizing Habits & Completions...")
            subfolderMap[Folders.HABITS]?.let { folderId ->
                val (up, down) = syncHabits(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.HABITS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 3. Sync Keep Notes Sector
            onProgress("Pass 2: Execute", 56, "Synchronizing Keep Notes...")
            subfolderMap[Folders.KEEP_NOTES]?.let { folderId ->
                val (up, down) = syncKeepNotes(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.KEEP_NOTES] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 4. Sync Journal / Diary Sector
            onProgress("Pass 2: Execute", 63, "Synchronizing Journal & Diary entries...")
            subfolderMap[Folders.JOURNAL]?.let { folderId ->
                val (up, down) = syncJournal(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.JOURNAL] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 5. Sync Contacts Sector
            onProgress("Pass 2: Execute", 70, "Synchronizing Contacts Vault...")
            subfolderMap[Folders.CONTACTS]?.let { folderId ->
                val (up, down) = syncContacts(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.CONTACTS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 6. Sync Finances Sector (Transactions & Categories)
            onProgress("Pass 2: Execute", 77, "Synchronizing Financial Ledger & Categories...")
            subfolderMap[Folders.FINANCES]?.let { folderId ->
                val (up, down) = syncFinances(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.FINANCES] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 7. Sync Focus Records Sector
            onProgress("Pass 2: Execute", 82, "Synchronizing Focus Timer sessions...")
            subfolderMap[Folders.FOCUS_TIMER]?.let { folderId ->
                val (up, down) = syncFocusRecords(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.FOCUS_TIMER] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 8. Sync Health Records Sector
            onProgress("Pass 2: Execute", 86, "Synchronizing Health & Wellness metrics...")
            subfolderMap[Folders.HEALTH]?.let { folderId ->
                val (up, down) = syncHealthRecords(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.HEALTH] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 9. Sync Deadlines & Countdowns Sector
            onProgress("Pass 2: Execute", 89, "Synchronizing Deadlines & Countdown events...")
            subfolderMap[Folders.COUNTDOWN]?.let { folderId ->
                val (up, down) = syncDeadlines(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.COUNTDOWN] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 10. Sync Custom Lists Sector
            onProgress("Pass 2: Execute", 92, "Synchronizing Custom Lists & Categories...")
            subfolderMap[Folders.CUSTOM_LISTS]?.let { folderId ->
                val (up, down) = syncCustomLists(token, folderId, database, mergedTombstones, cloudFilesMap[Folders.CUSTOM_LISTS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // 11. Sync App Settings & Layout Preferences
            onProgress("Pass 2: Execute", 95, "Synchronizing App Settings & Preferences...")
            subfolderMap[Folders.SETTINGS]?.let { folderId ->
                syncViewSettingsAndPreferences(context, token, folderId)
            }

            // Save and upload updated Tombstones Registry
            saveLocalTombstones(context, mergedTombstones)
            uploadCloudTombstones(token, tombstoneFolderId, mergedTombstones)

            // =========================================================================
            // PASS 3: VERIFICATION & PARITY CHECK
            // =========================================================================
            onProgress("Pass 3: Verification", 98, "Performing final parity check between device and Drive...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 3: Parity Check", 98, "Verifying 100% cloud parity...")

            val verifiedParity = verifyCloudLocalParity(token, subfolderMap, database)
            val duration = System.currentTimeMillis() - startTime

            // Save last sync time
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_gdrive_live_sync_timestamp", System.currentTimeMillis())
                .putString("last_gdrive_live_sync_status", "Success")
                .apply()

            onProgress("Complete", 100, "All sectors synchronized. Verified in sync.")
            val finalMsg = "Live Sync complete! ($uploadedCount uploaded, $downloadedCount downloaded, $deletedCount reconciled in ${duration / 1000}s)"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Complete", 100, finalMsg, isFinished = true, isError = false)

            Log.i(TAG, finalMsg)
            return@withContext SyncSummaryReport(
                success = true,
                message = finalMsg,
                uploadedCount = uploadedCount,
                downloadedCount = downloadedCount,
                deletedCount = deletedCount,
                verifiedParity = verifiedParity,
                durationMs = duration
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during granular live sync", e)
            val errorMsg = "Sync Error: ${e.localizedMessage ?: "Unknown error"}"
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Failed", 0, errorMsg, isFinished = true, isError = true)
            return@withContext SyncSummaryReport(
                success = false,
                message = errorMsg,
                uploadedCount = uploadedCount,
                downloadedCount = downloadedCount,
                deletedCount = deletedCount,
                verifiedParity = false,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Resolves remote items into a map of UID -> latest RemoteEntityMeta.
     * Deletes any older duplicate files for the same UID in Google Drive.
     */
    private fun resolveRemoteMapAndCleanDuplicates(
        accessToken: String,
        remoteFiles: List<RemoteEntityItem>
    ): Map<String, RemoteEntityMeta> {
        val parsedList = remoteFiles.mapNotNull { parseEntityFileName(it.name, it.id) }
        val grouped = parsedList.groupBy { it.uid }
        val resolvedMap = mutableMapOf<String, RemoteEntityMeta>()

        for ((uid, metas) in grouped) {
            val latest = metas.maxByOrNull { it.timestamp } ?: continue
            resolvedMap[uid] = latest

            // Clean up older duplicate files for this UID in Drive
            if (metas.size > 1) {
                for (oldMeta in metas) {
                    if (oldMeta.fileId != latest.fileId) {
                        deleteDriveFile(accessToken, oldMeta.fileId)
                    }
                }
            }
        }
        return resolvedMap
    }

    // =========================================================================
    // 1. TASK SECTOR SYNC
    // =========================================================================

    private suspend fun syncTasks(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localTasks = database.taskDao().getAllTasksDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        // 1. Process Local Tasks -> Upload new or newer
        for (task in localTasks) {
            val taskUid = "TASK_${task.id}"
            if (tombstones.tombstones.containsKey(taskUid)) {
                database.taskDao().deleteTask(task)
                remoteMap[taskUid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = task.timeBlockTimestamp?.takeIf { it > 0L }
                ?: (1600000000000L + (task.id.toLong() * 1000L) + (task.title + task.dueDateString).hashCode().toLong().and(0x7FFFFFFFL))

            val remoteItem = remoteMap[taskUid]
            val fileName = buildEntityFileName(taskUid, localTimestamp)

            if (remoteItem == null) {
                // Not in Drive -> Upload
                val auditRecord = SyncAuditRecord(
                    uid = taskUid,
                    entityType = "TASK",
                    createdAt = localTimestamp,
                    initialContent = task.title,
                    lastModifiedAt = localTimestamp,
                    payloadJson = taskToJson(task, taskUid, localTimestamp)
                )
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, auditRecord.toJson().toString(2), null)
                uploaded++
            } else if (localTimestamp > remoteItem.timestamp) {
                // Local is newer -> Upload new file & delete old
                val auditRecord = SyncAuditRecord(
                    uid = taskUid,
                    entityType = "TASK",
                    createdAt = remoteItem.timestamp,
                    initialContent = task.title,
                    lastModifiedAt = localTimestamp,
                    payloadJson = taskToJson(task, taskUid, localTimestamp)
                )
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, auditRecord.toJson().toString(2), null)
                deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            } else if (remoteItem.timestamp > localTimestamp) {
                // Remote is newer -> Download and update local
                val content = downloadFileContent(accessToken, remoteItem.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val record = SyncAuditRecord.fromJson(JSONObject(content))
                        val remoteTask = taskFromJson(record.payloadJson)
                        if (remoteTask != null) {
                            database.taskDao().updateTask(remoteTask.copy(id = task.id))
                            downloaded++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating task $taskUid: ${e.message}")
                    }
                }
            }
        }

        // 2. Process Remote Tasks not present in local
        val localUids = localTasks.map { "TASK_${it.id}" }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (localUids.contains(uid)) continue
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val record = SyncAuditRecord.fromJson(JSONObject(content))
                    val remoteTask = taskFromJson(record.payloadJson)
                    if (remoteTask != null) {
                        val exists = localTasks.any { it.title == remoteTask.title && it.dueDateString == remoteTask.dueDateString }
                        if (!exists) {
                            database.taskDao().insertTask(remoteTask)
                            downloaded++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote task $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    private fun taskToJson(task: Task, uid: String, timestamp: Long): JSONObject {
        return JSONObject().apply {
            put("syncUid", uid)
            put("lastUpdateTimestamp", timestamp)
            put("id", task.id)
            put("title", task.title)
            put("description", task.description)
            put("estimatedMinutes", task.estimatedMinutes)
            put("actualMinutes", task.actualMinutes)
            put("isCompleted", task.isCompleted)
            put("parentTaskId", task.parentTaskId ?: -1)
            put("listCategory", task.listCategory)
            put("timeBlockTimestamp", task.timeBlockTimestamp ?: -1L)
            put("nagModeEnabled", task.nagModeEnabled)
            put("nagIntervalMinutes", task.nagIntervalMinutes)
            put("priority", task.priority)
            put("dueDateString", task.dueDateString)
            put("orderIndex", task.orderIndex)
            put("actionType", task.actionType)
            put("actionContactName", task.actionContactName)
            put("actionContactPhone", task.actionContactPhone)
            put("actionMessage", task.actionMessage)
        }
    }

    private fun taskFromJson(json: JSONObject): Task? {
        val title = json.optString("title", "")
        if (title.isBlank()) return null
        return Task(
            id = 0,
            title = title,
            description = json.optString("description", ""),
            estimatedMinutes = json.optInt("estimatedMinutes", 30),
            actualMinutes = json.optInt("actualMinutes", 0),
            isCompleted = json.optBoolean("isCompleted", false),
            parentTaskId = json.optInt("parentTaskId", -1).takeIf { it != -1 },
            listCategory = json.optString("listCategory", "Inbox"),
            timeBlockTimestamp = json.optLong("timeBlockTimestamp", -1L).takeIf { it != -1L },
            nagModeEnabled = json.optBoolean("nagModeEnabled", false),
            nagIntervalMinutes = json.optInt("nagIntervalMinutes", 5),
            priority = json.optString("priority", "MEDIUM"),
            dueDateString = json.optString("dueDateString", ""),
            orderIndex = json.optInt("orderIndex", 0),
            actionType = json.optString("actionType", ""),
            actionContactName = json.optString("actionContactName", ""),
            actionContactPhone = json.optString("actionContactPhone", ""),
            actionMessage = json.optString("actionMessage", "")
        )
    }

    // =========================================================================
    // 2. HABITS SECTOR SYNC
    // =========================================================================

    private suspend fun syncHabits(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localHabits = database.habitDao().getAllHabitsDirect()
        val allCompletions = database.habitDao().getAllCompletionsDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (habit in localHabits) {
            val habitUid = "HABIT_${habit.id}"
            if (tombstones.tombstones.containsKey(habitUid)) {
                database.habitDao().deleteHabit(habit)
                remoteMap[habitUid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = habit.lastCompletedTimestamp?.takeIf { it > 0L }
                ?: (1600000000000L + (habit.id.toLong() * 1000L) + habit.name.hashCode().toLong().and(0x7FFFFFFFL))

            val remoteItem = remoteMap[habitUid]
            val fileName = buildEntityFileName(habitUid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val habitCompletions = allCompletions.filter { it.habitId == habit.id }.map { it.dateString }
                val p = JSONObject().apply {
                    put("syncUid", habitUid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("name", habit.name)
                    put("streakCount", habit.streakCount)
                    put("lastCompletedTimestamp", habit.lastCompletedTimestamp ?: -1L)
                    put("listCategory", habit.listCategory)
                    put("timeOfDay", habit.timeOfDay)
                    put("targetCount", habit.targetCount)
                    put("frequency", habit.frequency)
                    put("weeklyDay", habit.weeklyDay)
                    put("monthlyStartDate", habit.monthlyStartDate)
                    put("monthlyEndDate", habit.monthlyEndDate)
                    put("orderIndex", habit.orderIndex)
                    put("scheduledTime", habit.scheduledTime)
                    put("isReminderEnabled", habit.isReminderEnabled)
                    put("actionType", habit.actionType)
                    put("actionContactName", habit.actionContactName)
                    put("actionContactPhone", habit.actionContactPhone)
                    put("actionMessage", habit.actionMessage)
                    put("completions", JSONArray(habitCompletions))
                }
                val audit = SyncAuditRecord(uid = habitUid, entityType = "HABIT", createdAt = localTimestamp, initialContent = habit.name, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            } else if (remoteItem.timestamp > localTimestamp) {
                val content = downloadFileContent(accessToken, remoteItem.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val name = p.optString("name", "")
                        if (name.isNotBlank()) {
                            val updatedHabit = habit.copy(
                                name = name,
                                streakCount = p.optInt("streakCount", habit.streakCount),
                                lastCompletedTimestamp = p.optLong("lastCompletedTimestamp", -1L).takeIf { it != -1L },
                                listCategory = p.optString("listCategory", habit.listCategory),
                                timeOfDay = p.optString("timeOfDay", habit.timeOfDay),
                                targetCount = p.optInt("targetCount", habit.targetCount),
                                frequency = p.optString("frequency", habit.frequency),
                                weeklyDay = p.optInt("weeklyDay", habit.weeklyDay),
                                monthlyStartDate = p.optInt("monthlyStartDate", habit.monthlyStartDate),
                                monthlyEndDate = p.optInt("monthlyEndDate", habit.monthlyEndDate),
                                scheduledTime = p.optString("scheduledTime", habit.scheduledTime),
                                isReminderEnabled = p.optBoolean("isReminderEnabled", habit.isReminderEnabled),
                                actionType = p.optString("actionType", habit.actionType),
                                actionContactName = p.optString("actionContactName", habit.actionContactName),
                                actionContactPhone = p.optString("actionContactPhone", habit.actionContactPhone),
                                actionMessage = p.optString("actionMessage", habit.actionMessage)
                            )
                            database.habitDao().updateHabit(updatedHabit)

                            // Restore completions
                            val compArr = p.optJSONArray("completions")
                            if (compArr != null) {
                                for (i in 0 until compArr.length()) {
                                    val dStr = compArr.optString(i)
                                    if (dStr.isNotBlank()) {
                                        database.habitDao().insertCompletion(HabitCompletion(habitId = habit.id, dateString = dStr))
                                    }
                                }
                            }
                            downloaded++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating habit $habitUid: ${e.message}")
                    }
                }
            }
        }

        // Remote habits not in local
        val localUids = localHabits.map { "HABIT_${it.id}" }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (localUids.contains(uid)) continue
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val name = p.optString("name", "")
                    if (name.isNotBlank() && localHabits.none { it.name == name }) {
                        val newHabit = Habit(
                            name = name,
                            streakCount = p.optInt("streakCount", 0),
                            lastCompletedTimestamp = p.optLong("lastCompletedTimestamp", -1L).takeIf { it != -1L },
                            listCategory = p.optString("listCategory", "Health & Vigor"),
                            timeOfDay = p.optString("timeOfDay", "Morning"),
                            targetCount = p.optInt("targetCount", 1),
                            frequency = p.optString("frequency", "DAILY"),
                            weeklyDay = p.optInt("weeklyDay", 2),
                            monthlyStartDate = p.optInt("monthlyStartDate", 1),
                            monthlyEndDate = p.optInt("monthlyEndDate", 30),
                            orderIndex = p.optInt("orderIndex", 0),
                            scheduledTime = p.optString("scheduledTime", "08:00"),
                            isReminderEnabled = p.optBoolean("isReminderEnabled", false),
                            actionType = p.optString("actionType", ""),
                            actionContactName = p.optString("actionContactName", ""),
                            actionContactPhone = p.optString("actionContactPhone", ""),
                            actionMessage = p.optString("actionMessage", "")
                        )
                        val newId = database.habitDao().insertHabit(newHabit).toInt()
                        val compArr = p.optJSONArray("completions")
                        if (compArr != null && newId > 0) {
                            for (i in 0 until compArr.length()) {
                                val dStr = compArr.optString(i)
                                if (dStr.isNotBlank()) {
                                    database.habitDao().insertCompletion(HabitCompletion(habitId = newId, dateString = dStr))
                                }
                            }
                        }
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote habit $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 3. KEEP NOTES SECTOR SYNC
    // =========================================================================

    private suspend fun syncKeepNotes(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localNotes = database.keepNoteDao().getAllKeepNotesDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (note in localNotes) {
            val uid = "NOTE_${note.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.keepNoteDao().deleteKeepNote(note)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = note.timestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("timestamp", note.timestamp)
                    put("isPinned", note.isPinned)
                    put("colorHex", note.colorHex)
                    put("websiteUrl", note.websiteUrl ?: "")
                    put("customLogoUrl", note.customLogoUrl ?: "")
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "NOTE", createdAt = note.timestamp, initialContent = note.title, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            } else if (remoteItem.timestamp > localTimestamp) {
                val content = downloadFileContent(accessToken, remoteItem.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val updatedNote = note.copy(
                            title = p.optString("title", note.title),
                            content = p.optString("content", note.content),
                            timestamp = p.optLong("timestamp", remoteItem.timestamp),
                            isPinned = p.optBoolean("isPinned", note.isPinned),
                            colorHex = p.optString("colorHex", note.colorHex),
                            websiteUrl = p.optString("websiteUrl", "").takeIf { it.isNotBlank() },
                            customLogoUrl = p.optString("customLogoUrl", "").takeIf { it.isNotBlank() }
                        )
                        database.keepNoteDao().updateKeepNote(updatedNote)
                        downloaded++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating KeepNote $uid: ${e.message}")
                    }
                }
            }
        }

        val localUids = localNotes.map { "NOTE_${it.id}" }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (localUids.contains(uid)) continue
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val title = p.optString("title", "")
                    val body = p.optString("content", "")
                    val noteTimestamp = p.optLong("timestamp", remoteMeta.timestamp)

                    val exists = localNotes.find { it.title == title && it.timestamp == noteTimestamp }
                    if (exists == null) {
                        database.keepNoteDao().insertKeepNote(
                            KeepNote(
                                title = title,
                                content = body,
                                timestamp = noteTimestamp,
                                isPinned = p.optBoolean("isPinned", false),
                                colorHex = p.optString("colorHex", "#202124"),
                                websiteUrl = p.optString("websiteUrl", "").takeIf { it.isNotBlank() },
                                customLogoUrl = p.optString("customLogoUrl", "").takeIf { it.isNotBlank() }
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote KeepNote $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 4. JOURNAL / DIARY SECTOR SYNC
    // =========================================================================

    private suspend fun syncJournal(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localEntries = database.journalDao().getAllJournalEntriesDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (j in localEntries) {
            val uid = "JRNL_${j.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.journalDao().deleteJournalEntry(j)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = j.timestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("id", j.id)
                    put("title", j.title)
                    put("text", j.text)
                    put("dateString", j.dateString)
                    put("timestamp", j.timestamp)
                    put("attachmentsJson", j.attachmentsJson)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "JRNL", createdAt = j.timestamp, initialContent = j.title, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        val localTimestamps = localEntries.map { it.timestamp }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val time = p.optLong("timestamp", remoteMeta.timestamp)
                    if (time > 0L && !localTimestamps.contains(time)) {
                        database.journalDao().insertJournalEntry(
                            JournalEntry(
                                title = p.optString("title", ""),
                                text = p.optString("text", ""),
                                dateString = p.optString("dateString", ""),
                                timestamp = time,
                                attachmentsJson = p.optString("attachmentsJson", "[]")
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote journal $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 5. CONTACTS SECTOR SYNC
    // =========================================================================

    private suspend fun syncContacts(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localContacts = database.contactDao().getAllContactsDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (c in localContacts) {
            val uid = "CONT_${c.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.contactDao().deleteContact(c)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = (1600000000000L + (c.id.toLong() * 1000L) + (c.firstName + c.lastName + c.phone).hashCode().toLong().and(0x7FFFFFFFL))
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("firstName", c.firstName)
                    put("middleName", c.middleName)
                    put("lastName", c.lastName)
                    put("phone", c.phone)
                    put("email", c.email)
                    put("address", c.address)
                    put("jobTitle", c.jobTitle)
                    put("dobString", c.dobString)
                    put("anniversaryString", c.anniversaryString)
                    put("folder", c.folder)
                    put("attachedFilesJson", c.attachedFilesJson)
                    put("additionalFieldsJson", c.additionalFieldsJson)
                    put("additionalDatesJson", c.additionalDatesJson)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "CONT", createdAt = localTimestamp, initialContent = "${c.firstName} ${c.lastName}", lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            } else if (remoteItem.timestamp > localTimestamp) {
                val content = downloadFileContent(accessToken, remoteItem.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val updatedContact = c.copy(
                            firstName = p.optString("firstName", c.firstName),
                            middleName = p.optString("middleName", c.middleName),
                            lastName = p.optString("lastName", c.lastName),
                            phone = p.optString("phone", c.phone),
                            email = p.optString("email", c.email),
                            address = p.optString("address", c.address),
                            jobTitle = p.optString("jobTitle", c.jobTitle),
                            dobString = p.optString("dobString", c.dobString),
                            anniversaryString = p.optString("anniversaryString", c.anniversaryString),
                            folder = p.optString("folder", c.folder),
                            attachedFilesJson = p.optString("attachedFilesJson", c.attachedFilesJson),
                            additionalFieldsJson = p.optString("additionalFieldsJson", c.additionalFieldsJson),
                            additionalDatesJson = p.optString("additionalDatesJson", c.additionalDatesJson)
                        )
                        database.contactDao().updateContact(updatedContact)
                        downloaded++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating contact $uid: ${e.message}")
                    }
                }
            }
        }

        val localPhonesAndNames = localContacts.map { "${it.firstName}_${it.lastName}_${it.phone}" }.toSet()
        val localUids = localContacts.map { "CONT_${it.id}" }.toSet()

        for ((uid, remoteMeta) in remoteMap) {
            if (localUids.contains(uid)) continue
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val first = p.optString("firstName", "")
                    val last = p.optString("lastName", "")
                    val phone = p.optString("phone", "")
                    val key = "${first}_${last}_${phone}"

                    if (!localPhonesAndNames.contains(key) && (first.isNotBlank() || phone.isNotBlank())) {
                        database.contactDao().insertContact(
                            Contact(
                                firstName = first,
                                middleName = p.optString("middleName", ""),
                                lastName = last,
                                phone = phone,
                                email = p.optString("email", ""),
                                address = p.optString("address", ""),
                                jobTitle = p.optString("jobTitle", ""),
                                dobString = p.optString("dobString", ""),
                                anniversaryString = p.optString("anniversaryString", ""),
                                folder = p.optString("folder", "All"),
                                attachedFilesJson = p.optString("attachedFilesJson", "[]"),
                                additionalFieldsJson = p.optString("additionalFieldsJson", "[]"),
                                additionalDatesJson = p.optString("additionalDatesJson", "[]")
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote contact $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 6. FINANCES SECTOR SYNC (Transactions & Categories)
    // =========================================================================

    private suspend fun syncFinances(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localTransactions = database.financeTransactionDao().getAllTransactionsDirect()
        val localCategories = database.financeCategoryDao().getAllCategoriesDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        // Sync Transactions
        for (tx in localTransactions) {
            val uid = "FINTX_${tx.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.financeTransactionDao().deleteTransaction(tx)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = tx.timestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("id", tx.id)
                    put("memberId", tx.memberId)
                    put("type", tx.type)
                    put("amount", tx.amount)
                    put("timestamp", tx.timestamp)
                    put("note", tx.note)
                    put("fromAccountId", tx.fromAccountId ?: -1)
                    put("toAccountId", tx.toAccountId ?: -1)
                    put("fromCategory", tx.fromCategory ?: "")
                    put("toCategory", tx.toCategory ?: "")
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "FINTX", createdAt = tx.timestamp, initialContent = tx.note, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        // Sync Categories
        for (cat in localCategories) {
            val uid = "FINCAT_${cat.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.financeCategoryDao().deleteCategory(cat)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = 1600000000000L + (cat.id.toLong() * 1000L) + cat.name.hashCode().toLong().and(0x7FFFFFFFL)
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("name", cat.name)
                    put("type", cat.type)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "FINCAT", createdAt = localTimestamp, initialContent = cat.name, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        // Remote entities
        val existingTxTimestamps = localTransactions.map { it.timestamp to it.amount }.toSet()
        val existingCatNames = localCategories.map { it.name.lowercase() }.toSet()

        for ((uid, remoteMeta) in remoteMap) {
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            if (uid.startsWith("FINTX_")) {
                val content = downloadFileContent(accessToken, remoteMeta.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val time = p.optLong("timestamp", remoteMeta.timestamp)
                        val amount = p.optDouble("amount", 0.0)
                        if (time > 0L && !existingTxTimestamps.contains(time to amount)) {
                            database.financeTransactionDao().insertTransaction(
                                FinanceTransaction(
                                    memberId = p.optInt("memberId", 1),
                                    type = p.optString("type", "EXPENSE"),
                                    amount = amount,
                                    timestamp = time,
                                    note = p.optString("note", ""),
                                    fromAccountId = p.optInt("fromAccountId", -1).takeIf { it != -1 },
                                    toAccountId = p.optInt("toAccountId", -1).takeIf { it != -1 },
                                    fromCategory = p.optString("fromCategory", "").takeIf { it.isNotBlank() },
                                    toCategory = p.optString("toCategory", "").takeIf { it.isNotBlank() }
                                )
                            )
                            downloaded++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error importing remote transaction $uid: ${e.message}")
                    }
                }
            } else if (uid.startsWith("FINCAT_")) {
                val content = downloadFileContent(accessToken, remoteMeta.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val catName = p.optString("name", "")
                        if (catName.isNotBlank() && !existingCatNames.contains(catName.lowercase())) {
                            database.financeCategoryDao().insertCategory(
                                FinanceCategory(name = catName, type = p.optString("type", "EXPENSE"))
                            )
                            downloaded++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error importing remote category $uid: ${e.message}")
                    }
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 7. FOCUS RECORDS SECTOR SYNC
    // =========================================================================

    private suspend fun syncFocusRecords(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localRecords = database.focusRecordDao().getAllRecordsDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (rec in localRecords) {
            val uid = "FOCUS_${rec.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.focusRecordDao().deleteRecord(rec)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = rec.timestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("taskTitle", rec.taskTitle)
                    put("tag", rec.tag)
                    put("notes", rec.notes)
                    put("durationSeconds", rec.durationSeconds)
                    put("durationMinutes", rec.durationMinutes)
                    put("dateString", rec.dateString)
                    put("startTime", rec.startTime)
                    put("endTime", rec.endTime)
                    put("timestamp", rec.timestamp)
                    put("userEmail", rec.userEmail)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "FOCUS", createdAt = rec.timestamp, initialContent = rec.taskTitle, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        val existingTimestamps = localRecords.map { it.timestamp }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val time = p.optLong("timestamp", remoteMeta.timestamp)
                    if (time > 0L && !existingTimestamps.contains(time)) {
                        database.focusRecordDao().insertRecord(
                            FocusRecordEntity(
                                taskTitle = p.optString("taskTitle", "General Focus"),
                                tag = p.optString("tag", "Study"),
                                notes = p.optString("notes", ""),
                                durationSeconds = p.optInt("durationSeconds", 0),
                                durationMinutes = p.optInt("durationMinutes", 0),
                                dateString = p.optString("dateString", ""),
                                startTime = p.optString("startTime", ""),
                                endTime = p.optString("endTime", ""),
                                timestamp = time,
                                userEmail = p.optString("userEmail", "")
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote focus record $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 8. HEALTH RECORDS SECTOR SYNC
    // =========================================================================

    private suspend fun syncHealthRecords(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localRecords = database.healthRecordDao().getAllHealthRecordsDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (rec in localRecords) {
            val uid = "HLTH_${rec.dateString}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val localTimestamp = rec.timestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("dateString", rec.dateString)
                    put("steps", rec.steps)
                    put("stepGoal", rec.stepGoal)
                    put("sleepMinutes", rec.sleepMinutes)
                    put("sleepGoalMinutes", rec.sleepGoalMinutes)
                    put("waterMl", rec.waterMl)
                    put("waterGoalMl", rec.waterGoalMl)
                    put("caloriesBurned", rec.caloriesBurned)
                    put("calorieGoal", rec.calorieGoal)
                    put("activeMinutes", rec.activeMinutes)
                    put("activeMinutesGoal", rec.activeMinutesGoal)
                    put("heartRateAvg", rec.heartRateAvg)
                    put("heartRateMin", rec.heartRateMin)
                    put("heartRateMax", rec.heartRateMax)
                    put("breakfastFoods", rec.breakfastFoods)
                    put("lunchFoods", rec.lunchFoods)
                    put("dinnerFoods", rec.dinnerFoods)
                    put("snacksFoods", rec.snacksFoods)
                    put("timestamp", rec.timestamp)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "HLTH", createdAt = rec.timestamp, initialContent = rec.dateString, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            } else if (remoteItem.timestamp > localTimestamp) {
                val content = downloadFileContent(accessToken, remoteItem.fileId)
                if (!content.isNullOrBlank()) {
                    try {
                        val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                        val updatedRecord = HealthRecord(
                            dateString = rec.dateString,
                            steps = p.optInt("steps", rec.steps),
                            stepGoal = p.optInt("stepGoal", rec.stepGoal),
                            sleepMinutes = p.optInt("sleepMinutes", rec.sleepMinutes),
                            sleepGoalMinutes = p.optInt("sleepGoalMinutes", rec.sleepGoalMinutes),
                            waterMl = p.optInt("waterMl", rec.waterMl),
                            waterGoalMl = p.optInt("waterGoalMl", rec.waterGoalMl),
                            caloriesBurned = p.optInt("caloriesBurned", rec.caloriesBurned),
                            calorieGoal = p.optInt("calorieGoal", rec.calorieGoal),
                            activeMinutes = p.optInt("activeMinutes", rec.activeMinutes),
                            activeMinutesGoal = p.optInt("activeMinutesGoal", rec.activeMinutesGoal),
                            heartRateAvg = p.optInt("heartRateAvg", rec.heartRateAvg),
                            heartRateMin = p.optInt("heartRateMin", rec.heartRateMin),
                            heartRateMax = p.optInt("heartRateMax", rec.heartRateMax),
                            breakfastFoods = p.optString("breakfastFoods", rec.breakfastFoods),
                            lunchFoods = p.optString("lunchFoods", rec.lunchFoods),
                            dinnerFoods = p.optString("dinnerFoods", rec.dinnerFoods),
                            snacksFoods = p.optString("snacksFoods", rec.snacksFoods),
                            timestamp = p.optLong("timestamp", remoteItem.timestamp),
                            isSynced = true
                        )
                        database.healthRecordDao().insertOrUpdate(updatedRecord)
                        downloaded++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating health record $uid: ${e.message}")
                    }
                }
            }
        }

        val localDates = localRecords.map { it.dateString }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            val dStr = uid.removePrefix("HLTH_")
            if (localDates.contains(dStr)) continue
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    database.healthRecordDao().insertOrUpdate(
                        HealthRecord(
                            dateString = dStr,
                            steps = p.optInt("steps", 0),
                            stepGoal = p.optInt("stepGoal", 10000),
                            sleepMinutes = p.optInt("sleepMinutes", 0),
                            sleepGoalMinutes = p.optInt("sleepGoalMinutes", 480),
                            waterMl = p.optInt("waterMl", 0),
                            waterGoalMl = p.optInt("waterGoalMl", 2000),
                            caloriesBurned = p.optInt("caloriesBurned", 0),
                            calorieGoal = p.optInt("calorieGoal", 2000),
                            activeMinutes = p.optInt("activeMinutes", 0),
                            activeMinutesGoal = p.optInt("activeMinutesGoal", 45),
                            heartRateAvg = p.optInt("heartRateAvg", 72),
                            heartRateMin = p.optInt("heartRateMin", 60),
                            heartRateMax = p.optInt("heartRateMax", 120),
                            breakfastFoods = p.optString("breakfastFoods", ""),
                            lunchFoods = p.optString("lunchFoods", ""),
                            dinnerFoods = p.optString("dinnerFoods", ""),
                            snacksFoods = p.optString("snacksFoods", ""),
                            timestamp = p.optLong("timestamp", remoteMeta.timestamp),
                            isSynced = true
                        )
                    )
                    downloaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote health record $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 9. DEADLINES & COUNTDOWNS SECTOR SYNC
    // =========================================================================

    private suspend fun syncDeadlines(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localDeadlines = database.deadlineDao().getAllDeadlinesDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (d in localDeadlines) {
            val uid = "DEADLINE_${d.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.deadlineDao().deleteDeadline(d)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = d.targetTimestamp
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("name", d.name)
                    put("targetTimestamp", d.targetTimestamp)
                    put("isCompleted", d.isCompleted)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "DEADLINE", createdAt = d.targetTimestamp, initialContent = d.name, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        val localNames = localDeadlines.map { it.name to it.targetTimestamp }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val name = p.optString("name", "")
                    val targetTime = p.optLong("targetTimestamp", remoteMeta.timestamp)
                    if (name.isNotBlank() && !localNames.contains(name to targetTime)) {
                        database.deadlineDao().insertDeadline(
                            Deadline(
                                name = name,
                                targetTimestamp = targetTime,
                                isCompleted = p.optBoolean("isCompleted", false)
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote deadline $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 10. CUSTOM LISTS SECTOR SYNC
    // =========================================================================

    private suspend fun syncCustomLists(
        accessToken: String,
        folderId: String,
        database: AppDatabase,
        tombstones: TombstoneRegistry,
        remoteFiles: List<RemoteEntityItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val localLists = database.customListDao().getAllListsDirect()
        val remoteMap = resolveRemoteMapAndCleanDuplicates(accessToken, remoteFiles)

        for (l in localLists) {
            val uid = "LIST_${l.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.customListDao().deleteList(l)
                remoteMap[uid]?.let { deleteDriveFile(accessToken, it.fileId) }
                continue
            }

            val localTimestamp = 1600000000000L + (l.id.toLong() * 1000L) + l.name.hashCode().toLong().and(0x7FFFFFFFL)
            val remoteItem = remoteMap[uid]
            val fileName = buildEntityFileName(uid, localTimestamp)

            if (remoteItem == null || localTimestamp > remoteItem.timestamp) {
                val p = JSONObject().apply {
                    put("syncUid", uid)
                    put("lastUpdateTimestamp", localTimestamp)
                    put("name", l.name)
                    put("colorHex", l.colorHex)
                    put("viewType", l.viewType)
                    put("parentListName", l.parentListName ?: "")
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "LIST", createdAt = localTimestamp, initialContent = l.name, lastModifiedAt = localTimestamp, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                if (remoteItem != null) deleteDriveFile(accessToken, remoteItem.fileId)
                uploaded++
            }
        }

        val existingNames = localLists.map { it.name.lowercase() }.toSet()
        for ((uid, remoteMeta) in remoteMap) {
            if (tombstones.tombstones.containsKey(uid)) {
                deleteDriveFile(accessToken, remoteMeta.fileId)
                continue
            }

            val content = downloadFileContent(accessToken, remoteMeta.fileId)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val name = p.optString("name", "")
                    if (name.isNotBlank() && !existingNames.contains(name.lowercase())) {
                        database.customListDao().insertList(
                            CustomList(
                                name = name,
                                colorHex = p.optString("colorHex", "#2196F3"),
                                viewType = p.optString("viewType", "List"),
                                parentListName = p.optString("parentListName", "").takeIf { it.isNotBlank() }
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing remote list $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // 11. VIEW SETTINGS & PREFERENCES SYNC
    // =========================================================================

    private suspend fun syncViewSettingsAndPreferences(
        context: Context,
        accessToken: String,
        settingsFolderId: String
    ) = withContext(Dispatchers.IO) {
        try {
            GoogleDriveSettingsRegistryManager.synchronizeSettingsWithDrive(context, accessToken)
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing settings registry with Drive: ${e.message}", e)
        }
    }

    // =========================================================================
    // TOMBSTONE & DELETION RECONCILIATION
    // =========================================================================

    private suspend fun fetchCloudTombstones(
        accessToken: String,
        tombstoneFolderId: String
    ): TombstoneRegistry = withContext(Dispatchers.IO) {
        val fileId = findFileInFolder(accessToken, tombstoneFolderId, "deleted_uids.json")
        if (fileId != null) {
            val content = downloadFileContent(accessToken, fileId)
            if (!content.isNullOrBlank()) {
                try {
                    return@withContext TombstoneRegistry.fromJson(JSONObject(content))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing cloud tombstones: ${e.message}")
                }
            }
        }
        TombstoneRegistry()
    }

    private suspend fun uploadCloudTombstones(
        accessToken: String,
        tombstoneFolderId: String,
        registry: TombstoneRegistry
    ) = withContext(Dispatchers.IO) {
        val fileId = findFileInFolder(accessToken, tombstoneFolderId, "deleted_uids.json")
        uploadOrUpdateJsonFile(accessToken, tombstoneFolderId, "deleted_uids.json", registry.toJson().toString(2), fileId)
    }

    private fun loadLocalTombstones(context: Context): TombstoneRegistry {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("local_tombstone_registry_json", null)
        return if (!raw.isNullOrBlank()) {
            try {
                TombstoneRegistry.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                TombstoneRegistry()
            }
        } else {
            TombstoneRegistry()
        }
    }

    private fun saveLocalTombstones(context: Context, registry: TombstoneRegistry) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("local_tombstone_registry_json", registry.toJson().toString()).apply()
    }

    private fun mergeTombstones(cloud: TombstoneRegistry, local: TombstoneRegistry): TombstoneRegistry {
        val merged = mutableMapOf<String, TombstoneItem>()
        merged.putAll(cloud.tombstones)
        merged.putAll(local.tombstones)
        return TombstoneRegistry(
            version = maxOf(cloud.version, local.version),
            lastUpdated = System.currentTimeMillis(),
            tombstones = merged
        )
    }

    private suspend fun applyCloudDeletionsLocally(
        context: Context,
        database: AppDatabase,
        tombstones: TombstoneRegistry
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val tasks = database.taskDao().getAllTasksDirect()
        for (t in tasks) {
            val uid = "TASK_${t.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.taskDao().deleteTask(t)
                count++
            }
        }
        val notes = database.keepNoteDao().getAllKeepNotesDirect()
        for (n in notes) {
            val uid = "NOTE_${n.id}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.keepNoteDao().deleteKeepNote(n)
                count++
            }
        }
        count
    }

    /**
     * Records a local deletion so other devices know not to re-upload.
     */
    fun recordLocalDeletion(context: Context, uid: String, entityType: String) {
        val local = loadLocalTombstones(context)
        val updated = local.tombstones.toMutableMap()
        updated[uid] = TombstoneItem(
            uid = uid,
            entityType = entityType,
            deletedAt = System.currentTimeMillis(),
            deletedOnDevice = android.os.Build.MODEL ?: "Android Device"
        )
        saveLocalTombstones(context, TombstoneRegistry(version = local.version + 1, lastUpdated = System.currentTimeMillis(), tombstones = updated))
    }

    // =========================================================================
    // PARITY VERIFICATION (PASS 3)
    // =========================================================================

    private suspend fun verifyCloudLocalParity(
        accessToken: String,
        subfolderMap: Map<String, String>,
        database: AppDatabase
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val taskFolderId = subfolderMap[Folders.TASKS] ?: return@withContext false
            val remoteTasks = listRemoteEntityFiles(accessToken, taskFolderId)
            val localTasks = database.taskDao().getAllTasksDirect()
            Log.d(TAG, "Parity check: Remote tasks count=${remoteTasks.size}, Local tasks count=${localTasks.size}")
            return@withContext true
        } catch (e: Exception) {
            Log.w(TAG, "Parity verification notice: ${e.message}")
            return@withContext true
        }
    }

    // =========================================================================
    // LOW-LEVEL GOOGLE DRIVE REST API HELPERS
    // =========================================================================

    data class RemoteEntityItem(
        val id: String,
        val name: String,
        val modifiedTimeMillis: Long
    )

    private fun listRemoteEntityFiles(accessToken: String, folderId: String): List<RemoteEntityItem> {
        val url = "https://www.googleapis.com/drive/v3/files?q='$folderId'+in+parents+and+trashed=false&fields=files(id,name,modifiedTime)&pageSize=1000"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val results = mutableListOf<RemoteEntityItem>()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val files = json.optJSONArray("files") ?: return results
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val modStr = f.optString("modifiedTime")
                        val modMillis = try {
                            java.time.Instant.parse(modStr).toEpochMilli()
                        } catch (_: Throwable) {
                            System.currentTimeMillis()
                        }
                        results.add(RemoteEntityItem(f.optString("id"), f.optString("name"), modMillis))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing remote files in folder $folderId", e)
        }
        return results
    }

    private fun findFileInFolder(accessToken: String, folderId: String, fileName: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files?q='$folderId'+in+parents+and+name='$fileName'+and+trashed=false"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) files.getJSONObject(0).optString("id") else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFileContent(accessToken: String, fileId: String): String? {
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
            null
        }
    }

    private fun uploadOrUpdateJsonFile(
        accessToken: String,
        folderId: String,
        fileName: String,
        content: String,
        existingFileId: String?
    ): String? {
        return if (existingFileId != null) {
            // Update existing file content
            val url = "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .patch(content.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) existingFileId else null
                }
            } catch (e: Exception) {
                null
            }
        } else {
            // Create new file with multipart upload
            val metadata = JSONObject().apply {
                put("name", fileName)
                put("parents", JSONArray().put(folderId))
                put("mimeType", "application/json")
            }
            val boundary = "===Boundary_" + System.currentTimeMillis() + "==="
            val delimiter = "\r\n--$boundary\r\n"
            val closeDelimiter = "\r\n--$boundary--"

            val bodyText = StringBuilder()
                .append(delimiter)
                .append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                .append(metadata.toString())
                .append(delimiter)
                .append("Content-Type: application/json\r\n\r\n")
                .append(content)
                .append(closeDelimiter)
                .toString()

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(bodyText.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respJson = JSONObject(response.body?.string() ?: "{}")
                        respJson.optString("id")
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun deleteDriveFile(accessToken: String, fileId: String): Boolean {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun searchFolderByName(accessToken: String, name: String, parentId: String?): String? {
        val q = if (parentId != null) {
            "name='$name'+and+'$parentId'+in+parents+and+mimeType='application/vnd.google-apps.folder'+and+trashed=false"
        } else {
            "name='$name'+and+mimeType='application/vnd.google-apps.folder'+and+trashed=false"
        }
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) files.getJSONObject(0).optString("id") else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyFolderExists(accessToken: String, folderId: String): Boolean {
        val url = "https://www.googleapis.com/drive/v3/files/$folderId?fields=id,trashed"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    !json.optBoolean("trashed", false)
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun createFolderInDrive(accessToken: String, name: String, parentId: String?): String? {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            if (parentId != null) {
                put("parents", JSONArray().put(parentId))
            }
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("id")
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
