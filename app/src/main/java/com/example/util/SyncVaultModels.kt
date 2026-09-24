package com.example.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * SyncVaultModels
 *
 * Data models for the Bookkeeping Audit Trail, Tombstones, and Multi-Device Reconciliation.
 */
data class EditHistoryEntry(
    val editId: String = UidGeneratorHelper.generateEditId(),
    val timestamp: Long = System.currentTimeMillis(),
    val device: String = android.os.Build.MODEL ?: "Android Device",
    val fieldChanged: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val summary: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("editId", editId)
            put("timestamp", timestamp)
            put("device", device)
            put("fieldChanged", fieldChanged)
            put("oldValue", oldValue ?: JSONObject.NULL)
            put("newValue", newValue ?: JSONObject.NULL)
            put("summary", summary)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): EditHistoryEntry {
            return EditHistoryEntry(
                editId = json.optString("editId", UidGeneratorHelper.generateEditId()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                device = json.optString("device", "Unknown"),
                fieldChanged = json.optString("fieldChanged", "general"),
                oldValue = if (json.isNull("oldValue")) null else json.optString("oldValue"),
                newValue = if (json.isNull("newValue")) null else json.optString("newValue"),
                summary = json.optString("summary", "")
            )
        }
    }
}

data class SyncAuditRecord(
    val uid: String,
    val entityType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val createdOnDevice: String = android.os.Build.MODEL ?: "Android Device",
    val initialContent: String = "",
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val editHistory: List<EditHistoryEntry> = emptyList(),
    val payloadJson: JSONObject = JSONObject()
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("uid", uid)
        root.put("entityType", entityType)
        root.put("createdAt", createdAt)
        root.put("createdOnDevice", createdOnDevice)
        root.put("initialContent", initialContent)
        root.put("lastModifiedAt", lastModifiedAt)
        root.put("isDeleted", isDeleted)

        val historyArr = JSONArray()
        editHistory.forEach { historyArr.put(it.toJson()) }
        root.put("editHistory", historyArr)
        root.put("payload", payloadJson)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): SyncAuditRecord {
            val historyList = mutableListOf<EditHistoryEntry>()
            val arr = json.optJSONArray("editHistory")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entryJson = arr.optJSONObject(i)
                    if (entryJson != null) {
                        historyList.add(EditHistoryEntry.fromJson(entryJson))
                    }
                }
            }

            return SyncAuditRecord(
                uid = json.optString("uid"),
                entityType = json.optString("entityType"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                createdOnDevice = json.optString("createdOnDevice", "Unknown Device"),
                initialContent = json.optString("initialContent", ""),
                lastModifiedAt = json.optLong("lastModifiedAt", System.currentTimeMillis()),
                isDeleted = json.optBoolean("isDeleted", false),
                editHistory = historyList,
                payloadJson = json.optJSONObject("payload") ?: JSONObject()
            )
        }
    }
}

data class TombstoneItem(
    val uid: String,
    val entityType: String,
    val deletedAt: Long = System.currentTimeMillis(),
    val deletedOnDevice: String = android.os.Build.MODEL ?: "Android Device"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("uid", uid)
            put("entityType", entityType)
            put("deletedAt", deletedAt)
            put("deletedOnDevice", deletedOnDevice)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TombstoneItem {
            return TombstoneItem(
                uid = json.optString("uid"),
                entityType = json.optString("entityType"),
                deletedAt = json.optLong("deletedAt", System.currentTimeMillis()),
                deletedOnDevice = json.optString("deletedOnDevice", "Unknown Device")
            )
        }
    }
}

data class TombstoneRegistry(
    val version: Int = 1,
    val lastUpdated: Long = System.currentTimeMillis(),
    val tombstones: Map<String, TombstoneItem> = emptyMap()
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("version", version)
        root.put("lastUpdated", lastUpdated)
        val arr = JSONArray()
        tombstones.values.forEach { arr.put(it.toJson()) }
        root.put("tombstones", arr)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): TombstoneRegistry {
            val map = mutableMapOf<String, TombstoneItem>()
            val arr = json.optJSONArray("tombstones")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val itemJson = arr.optJSONObject(i)
                    if (itemJson != null) {
                        val item = TombstoneItem.fromJson(itemJson)
                        if (item.uid.isNotBlank()) {
                            map[item.uid] = item
                        }
                    }
                }
            }
            return TombstoneRegistry(
                version = json.optInt("version", 1),
                lastUpdated = json.optLong("lastUpdated", System.currentTimeMillis()),
                tombstones = map
            )
        }
    }
}

data class SyncSummaryReport(
    val success: Boolean,
    val message: String,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val deletedCount: Int = 0,
    val verifiedParity: Boolean = true,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
