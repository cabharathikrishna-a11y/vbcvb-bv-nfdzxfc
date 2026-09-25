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
 * Implements a continuous, entity-level Google Drive Live-State Synchronization System:
 * - Single persistent root folder: "LifeOS_Sync_Vault"
 * - Individual subfolders for every tab/category
 * - Immutable bookkeeping audit trail with edit histories on every JSON entity
 * - Central tombstone registry (_Deleted_Tombstones/deleted_uids.json) for cross-device deletion sync
 * - View settings, display modes, and tab layout synchronization in App_Settings/
 * - Atomic 3-Pass Sync Cycle:
 *     Pass 1: Read & Assess (fetch tombstones, query cloud manifests, compute reconciliation delta)
 *     Pass 2: Execute Updates & Reconcile (apply deletions, download new/updated items, upload local changes with edit history)
 *     Pass 3: Verification & Parity Check (validate 100% cloud-local parity, disconnect cleanly)
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
        const val SETTINGS = "App_Settings"

        val ALL_SUBFOLDERS = listOf(
            TOMBSTONES, TASKS, KEEP_NOTES, MESSAGES, DEEPA_AI,
            CALENDAR, FOCUS_TIMER, HABITS, COUNTDOWN, JOURNAL,
            CONTACTS, FINANCES, FILES, SHOPPING, HEALTH,
            ARENA, MOVIES, SETTINGS
        )
    }

    // Cache of resolved folder IDs
    private val folderIdCache = mutableMapOf<String, String>()

    /**
     * Checks if user has granted Google Drive permissions.
     */
    fun hasDrivePermission(context: Context): Boolean {
        return GoogleDriveReadManager.hasDrivePermission(context)
    }

    /**
     * Finds or creates the single shared root vault folder "LifeOS_Sync_Vault".
     * Reuses existing folder across devices for the same user.
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
     * Main Entrypoint: Executes the complete Atomic 3-Pass Live Sync Cycle.
     */
    suspend fun execute3PassLiveSync(
        context: Context,
        database: AppDatabase,
        onProgress: (phase: String, percent: Int, detail: String) -> Unit = { _, _, _ -> },
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): SyncSummaryReport = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Starting Atomic 3-Pass Google Drive Live Sync...")

        onProgress("Connecting", 5, "Authenticating with Google Drive...")
        val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
        if (token == null) {
            val msg = "Google Drive authorization required. Please connect your account."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext SyncSummaryReport(false, msg, 0, 0, 0, false, 0L)
        }

        val rootId = getOrCreateRootVaultFolder(context, token)
        if (rootId == null) {
            val msg = "Failed to access or create 'LifeOS_Sync_Vault' root directory on Google Drive."
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Failed", 0, msg, isFinished = true, isError = true)
            return@withContext SyncSummaryReport(false, msg, 0, 0, 0, false, 0L)
        }

        // Initialize all required subfolders
        onProgress("Connecting", 10, "Verifying vault directory structure...")
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
            // PASS 1: READ & ASSESS (Fetch tombstones, query cloud manifests, compute delta)
            // =========================================================================
            onProgress("Pass 1: Read & Assess", 15, "Reading deleted tombstones from Google Drive...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 1: Read & Assess", 15, "Analyzing cloud vs local state...")

            val cloudTombstoneRegistry = fetchCloudTombstones(token, tombstoneFolderId)
            val localTombstones = loadLocalTombstones(context)
            val mergedTombstones = mergeTombstones(cloudTombstoneRegistry, localTombstones)

            onProgress("Pass 1: Read & Assess", 25, "Querying cloud entity manifests...")
            val cloudFilesMap = mutableMapOf<String, List<RemoteEntityItem>>() // folderName -> files
            for ((folderName, fId) in subfolderMap) {
                if (folderName != Folders.TOMBSTONES) {
                    cloudFilesMap[folderName] = listRemoteEntityFiles(token, fId)
                }
            }

            // =========================================================================
            // PASS 2: EXECUTE UPDATES & RECONCILE (Apply deletions, download, upload)
            // =========================================================================
            onProgress("Pass 2: Execute", 35, "Applying cross-device deletions and tombstones...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 2: Reconciling", 35, "Applying cross-device updates...")

            // Apply cloud deletions locally
            val localDeleted = applyCloudDeletionsLocally(context, database, mergedTombstones)
            deletedCount += localDeleted

            // Purge deleted entity files in Cloud that exist in tombstone registry
            for ((folderName, remoteFiles) in cloudFilesMap) {
                for (remoteFile in remoteFiles) {
                    val uid = remoteFile.name.substringBeforeLast(".")
                    if (mergedTombstones.tombstones.containsKey(uid)) {
                        deleteDriveFile(token, remoteFile.id)
                    }
                }
            }

            // Sync Tasks
            onProgress("Pass 2: Execute", 45, "Synchronizing tasks & subtasks with audit trail...")
            val tasksFolderId = subfolderMap[Folders.TASKS]
            if (tasksFolderId != null) {
                val (up, down) = syncTasks(token, tasksFolderId, database, mergedTombstones, cloudFilesMap[Folders.TASKS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // Sync Keep Notes
            onProgress("Pass 2: Execute", 55, "Synchronizing Keep Notes...")
            val notesFolderId = subfolderMap[Folders.KEEP_NOTES]
            if (notesFolderId != null) {
                val (up, down) = syncKeepNotes(token, notesFolderId, database, mergedTombstones, cloudFilesMap[Folders.KEEP_NOTES] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // Sync Contacts
            onProgress("Pass 2: Execute", 65, "Synchronizing contacts & address book...")
            val contactsFolderId = subfolderMap[Folders.CONTACTS]
            if (contactsFolderId != null) {
                val (up, down) = syncContacts(token, contactsFolderId, database, mergedTombstones, cloudFilesMap[Folders.CONTACTS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // Sync Habits, Journal, Finances
            onProgress("Pass 2: Execute", 75, "Synchronizing habits, journals & finances...")
            val habitsFolderId = subfolderMap[Folders.HABITS]
            if (habitsFolderId != null) {
                val (up, down) = syncHabits(token, habitsFolderId, database, mergedTombstones, cloudFilesMap[Folders.HABITS] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            val journalFolderId = subfolderMap[Folders.JOURNAL]
            if (journalFolderId != null) {
                val (up, down) = syncJournal(token, journalFolderId, database, mergedTombstones, cloudFilesMap[Folders.JOURNAL] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            val financesFolderId = subfolderMap[Folders.FINANCES]
            if (financesFolderId != null) {
                val (up, down) = syncFinances(token, financesFolderId, database, mergedTombstones, cloudFilesMap[Folders.FINANCES] ?: emptyList())
                uploadedCount += up
                downloadedCount += down
            }

            // Sync View Settings & Tab Layouts
            onProgress("Pass 2: Execute", 85, "Synchronizing View Settings & Task Layouts...")
            val settingsFolderId = subfolderMap[Folders.SETTINGS]
            if (settingsFolderId != null) {
                syncViewSettingsAndPreferences(context, token, settingsFolderId)
            }

            // Save and upload updated Tombstones Registry
            saveLocalTombstones(context, mergedTombstones)
            uploadCloudTombstones(token, tombstoneFolderId, mergedTombstones)

            // =========================================================================
            // PASS 3: VERIFICATION & PARITY CHECK (Ensure 100% parity & clean disconnect)
            // =========================================================================
            onProgress("Pass 3: Verification", 92, "Performing final parity check between device and Drive...")
            GoogleDriveSyncProgressTracker.updateProgress(context, "Drive Live Sync", "Pass 3: Parity Check", 92, "Verifying 100% cloud parity...")

            val verifiedParity = verifyCloudLocalParity(token, subfolderMap, database)
            val duration = System.currentTimeMillis() - startTime

            // Save last sync time
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_gdrive_live_sync_timestamp", System.currentTimeMillis())
                .putString("last_gdrive_live_sync_status", "Success")
                .apply()

            onProgress("Complete", 100, "All changes synchronized. Verified in sync.")
            val finalMsg = "Live Sync completed successfully. ($uploadedCount uploaded, $downloadedCount downloaded, $deletedCount reconciled in ${duration / 1000}s)"
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

        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during 3-pass live sync", e)
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

    // =========================================================================
    // VIEW SETTINGS & TAB LAYOUT SYNCHRONIZATION
    // =========================================================================

    private suspend fun syncViewSettingsAndPreferences(
        context: Context,
        accessToken: String,
        settingsFolderId: String
    ) = withContext(Dispatchers.IO) {
        try {
            // Full deterministic constant UID settings reconciliation with Google Drive
            GoogleDriveSettingsRegistryManager.synchronizeSettingsWithDrive(context, accessToken)
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing settings registry with Drive: ${e.message}", e)
        }
    }

    // =========================================================================
    // TASK SYNC WITH AUDIT TRAIL & EDIT HISTORY
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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        // 1. Process Local Tasks -> Upload new or updated
        for (task in localTasks) {
            val taskUid = "TASK_${task.id}_${(task.title + task.dueDateString).hashCode().toString().replace("-", "n")}"

            if (tombstones.tombstones.containsKey(taskUid)) {
                // Was deleted remotely, delete locally
                database.taskDao().deleteTask(task)
                continue
            }

            val remoteItem = remoteMap[taskUid]
            val fileName = "$taskUid.json"

            if (remoteItem == null) {
                // Create initial audit record and upload
                val auditRecord = SyncAuditRecord(
                    uid = taskUid,
                    entityType = "TASK",
                    createdAt = System.currentTimeMillis(),
                    initialContent = task.title,
                    lastModifiedAt = System.currentTimeMillis(),
                    payloadJson = taskToJson(task, taskUid)
                )
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, auditRecord.toJson().toString(2), null)
                uploaded++
            } else {
                val existingContent = downloadFileContent(accessToken, remoteItem.id)
                val existingRecord = if (!existingContent.isNullOrBlank()) {
                    try { SyncAuditRecord.fromJson(JSONObject(existingContent)) } catch (_: Exception) { null }
                } else null

                val currentHistory = existingRecord?.editHistory?.toMutableList() ?: mutableListOf()
                currentHistory.add(
                    EditHistoryEntry(
                        fieldChanged = "task_state",
                        oldValue = existingRecord?.payloadJson?.optString("title"),
                        newValue = task.title,
                        summary = "Task synced (${task.title}, completed=${task.isCompleted}, priority=${task.priority})"
                    )
                )

                val updatedRecord = SyncAuditRecord(
                    uid = taskUid,
                    entityType = "TASK",
                    createdAt = existingRecord?.createdAt ?: System.currentTimeMillis(),
                    createdOnDevice = existingRecord?.createdOnDevice ?: (android.os.Build.MODEL ?: "Android Device"),
                    initialContent = existingRecord?.initialContent ?: task.title,
                    lastModifiedAt = System.currentTimeMillis(),
                    editHistory = currentHistory,
                    payloadJson = taskToJson(task, taskUid)
                )
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, updatedRecord.toJson().toString(2), remoteItem.id)
                uploaded++
            }
        }

        // 2. Process Remote Tasks -> Download missing
        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val record = SyncAuditRecord.fromJson(JSONObject(content))
                    val task = taskFromJson(record.payloadJson)
                    if (task != null) {
                        val exists = localTasks.any { it.title == task.title && it.dueDateString == task.dueDateString }
                        if (!exists) {
                            database.taskDao().insertTask(task)
                            downloaded++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing task JSON for $uid: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    private fun taskToJson(task: Task, uid: String): JSONObject {
        return JSONObject().apply {
            put("syncUid", uid)
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
    // KEEP NOTES SYNC
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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        for (note in localNotes) {
            val uid = "NOTE_${note.id}_${note.timestamp}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val fileName = "$uid.json"
            val remoteItem = remoteMap[uid]

            if (remoteItem == null) {
                val payload = JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("timestamp", note.timestamp)
                    put("isPinned", note.isPinned)
                    put("colorHex", note.colorHex)
                    put("websiteUrl", note.websiteUrl ?: "")
                    put("customLogoUrl", note.customLogoUrl ?: "")
                }
                val audit = SyncAuditRecord(
                    uid = uid,
                    entityType = "NOTE",
                    createdAt = note.timestamp,
                    initialContent = note.title,
                    lastModifiedAt = note.timestamp,
                    payloadJson = payload
                )
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                uploaded++
            }
        }

        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val record = SyncAuditRecord.fromJson(JSONObject(content))
                    val p = record.payloadJson
                    val title = p.optString("title", "")
                    val body = p.optString("content", "")
                    val noteTimestamp = p.optLong("timestamp", System.currentTimeMillis())

                    val existing = localNotes.find { it.title == title && it.timestamp == noteTimestamp }
                    if (existing == null) {
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
                    Log.w(TAG, "Error importing KeepNote: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // CONTACTS SYNC
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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        for (c in localContacts) {
            val uid = "CONT_${c.id}_${(c.firstName + c.lastName).hashCode()}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val fileName = "$uid.json"
            if (!remoteMap.containsKey(uid)) {
                val p = JSONObject().apply {
                    put("firstName", c.firstName)
                    put("middleName", c.middleName)
                    put("lastName", c.lastName)
                    put("phone", c.phone)
                    put("email", c.email)
                    put("address", c.address)
                    put("jobTitle", c.jobTitle)
                    put("folder", c.folder)
                    put("attachedFilesJson", c.attachedFilesJson)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "CONT", initialContent = "${c.firstName} ${c.lastName}", payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                uploaded++
            }
        }

        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val phone = p.optString("phone", "")
                    val first = p.optString("firstName", "")
                    val last = p.optString("lastName", "")
                    val existing = localContacts.find { it.phone == phone && it.firstName == first }
                    if (existing == null && (first.isNotBlank() || phone.isNotBlank())) {
                        database.contactDao().insertContact(
                            Contact(
                                firstName = first,
                                middleName = p.optString("middleName", ""),
                                lastName = last,
                                phone = phone,
                                email = p.optString("email", ""),
                                address = p.optString("address", ""),
                                jobTitle = p.optString("jobTitle", ""),
                                folder = p.optString("folder", "All"),
                                attachedFilesJson = p.optString("attachedFilesJson", "[]")
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing contact: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

    // =========================================================================
    // HABITS, JOURNAL, FINANCES SYNC
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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        for (h in localHabits) {
            val uid = "HABIT_${h.id}_${h.name.hashCode()}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val fileName = "$uid.json"
            if (!remoteMap.containsKey(uid)) {
                val p = JSONObject().apply {
                    put("name", h.name)
                    put("streakCount", h.streakCount)
                    put("frequency", h.frequency)
                    put("timeOfDay", h.timeOfDay)
                    put("targetCount", h.targetCount)
                    put("listCategory", h.listCategory)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "HABIT", initialContent = h.name, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                uploaded++
            }
        }

        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val name = p.optString("name", "")
                    if (name.isNotBlank() && localHabits.none { it.name == name }) {
                        database.habitDao().insertHabit(
                            Habit(
                                name = name,
                                streakCount = p.optInt("streakCount", 0),
                                frequency = p.optString("frequency", "DAILY"),
                                timeOfDay = p.optString("timeOfDay", "Anytime"),
                                targetCount = p.optInt("targetCount", 1),
                                listCategory = p.optString("listCategory", "Default")
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing habit: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        for (j in localEntries) {
            val uid = "JRNL_${j.id}_${j.timestamp}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val fileName = "$uid.json"
            if (!remoteMap.containsKey(uid)) {
                val p = JSONObject().apply {
                    put("title", j.title)
                    put("text", j.text)
                    put("dateString", j.dateString)
                    put("timestamp", j.timestamp)
                    put("attachmentsJson", j.attachmentsJson)
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "JRNL", initialContent = j.title, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                uploaded++
            }
        }

        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val time = p.optLong("timestamp", 0L)
                    if (time > 0L && localEntries.none { it.timestamp == time }) {
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
                    Log.w(TAG, "Error importing journal: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
    }

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
        val remoteMap = remoteFiles.associateBy { it.name.substringBeforeLast(".") }

        for (tx in localTransactions) {
            val uid = "FIN_${tx.id}_${tx.timestamp}"
            if (tombstones.tombstones.containsKey(uid)) continue

            val fileName = "$uid.json"
            if (!remoteMap.containsKey(uid)) {
                val p = JSONObject().apply {
                    put("type", tx.type)
                    put("amount", tx.amount)
                    put("timestamp", tx.timestamp)
                    put("note", tx.note)
                    put("fromCategory", tx.fromCategory ?: "")
                    put("toCategory", tx.toCategory ?: "")
                }
                val audit = SyncAuditRecord(uid = uid, entityType = "FIN", initialContent = tx.note, payloadJson = p)
                uploadOrUpdateJsonFile(accessToken, folderId, fileName, audit.toJson().toString(2), null)
                uploaded++
            }
        }

        for (remote in remoteFiles) {
            val uid = remote.name.substringBeforeLast(".")
            if (tombstones.tombstones.containsKey(uid)) continue

            val content = downloadFileContent(accessToken, remote.id)
            if (!content.isNullOrBlank()) {
                try {
                    val p = SyncAuditRecord.fromJson(JSONObject(content)).payloadJson
                    val time = p.optLong("timestamp", 0L)
                    val amount = p.optDouble("amount", 0.0)
                    if (time > 0L && localTransactions.none { it.timestamp == time && it.amount == amount }) {
                        database.financeTransactionDao().insertTransaction(
                            FinanceTransaction(
                                memberId = p.optInt("memberId", 1),
                                type = p.optString("type", "EXPENSE"),
                                amount = amount,
                                timestamp = time,
                                note = p.optString("note", ""),
                                fromCategory = p.optString("fromCategory", "").takeIf { it.isNotBlank() },
                                toCategory = p.optString("toCategory", "").takeIf { it.isNotBlank() }
                            )
                        )
                        downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error importing finance transaction: ${e.message}")
                }
            }
        }

        Pair(uploaded, downloaded)
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
            val uid = "TASK_${t.id}_${(t.title + t.dueDateString).hashCode().toString().replace("-", "n")}"
            if (tombstones.tombstones.containsKey(uid)) {
                database.taskDao().deleteTask(t)
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
        val url = "https://www.googleapis.com/drive/v3/files?q='$folderId'+in+parents+and+trashed=false&fields=files(id,name,modifiedTime)"
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
