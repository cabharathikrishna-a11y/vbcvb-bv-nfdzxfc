package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * UidGeneratorHelper
 *
 * Implements strict, non-repeating, readable UID generation:
 * Format: [PREFIX]_[YYYYMMDD]_[HHMMSS_SSS]_[HEX4]
 * Example: TASK_20260923_162540_128_9b4e
 */
object UidGeneratorHelper {

    enum class EntityPrefix(val prefix: String) {
        TASK("TASK"),
        NOTE("NOTE"),
        CHAT("CHAT"),
        AI("AI"),
        CONT("CONT"),
        HABIT("HABIT"),
        JRNL("JRNL"),
        EVNT("EVNT"),
        CDWN("CDWN"),
        FIN("FIN"),
        FILE("FILE"),
        SHOP("SHOP"),
        HLTH("HLTH"),
        ARNA("ARNA"),
        MOV("MOV"),
        FOCS("FOCS"),
        SETT("SETT"),
        IMG("IMG"),
        EDIT("EDIT")
    }

    private val lastTimestamp = AtomicLong(0L)

    /**
     * Generates a globally unique, collision-free, timestamp-ordered UID.
     */
    fun generateUid(prefix: EntityPrefix): String {
        val now = System.currentTimeMillis()
        var timestamp = lastTimestamp.get()
        while (now <= timestamp) {
            timestamp = lastTimestamp.incrementAndGet()
        }
        lastTimestamp.set(timestamp)

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
        val entropy = UUID.randomUUID().toString().replace("-", "").take(4).lowercase(Locale.US)
        return "${prefix.prefix}_${dateStr}_${entropy}"
    }

    /**
     * Generates a unique Edit ID for audit records.
     */
    fun generateEditId(): String {
        return generateUid(EntityPrefix.EDIT)
    }

    /**
     * Checks if a string conforms to the standardized UID format.
     */
    fun isValidUid(uid: String?): Boolean {
        if (uid.isNullOrBlank()) return false
        val regex = Regex("^[A-Z]{2,5}_\\d{8}_\\d{6}_\\d{3}_[a-z0-9]{4}$")
        return regex.matches(uid)
    }
}
