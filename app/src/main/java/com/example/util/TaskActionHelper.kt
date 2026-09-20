package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.Habit
import com.example.data.Task

data class TaskActionData(
    val type: String = "", // "CALL", "SMS", "WHATSAPP", or ""
    val contactName: String = "",
    val contactPhone: String = "",
    val message: String = ""
)

object TaskActionHelper {
    private val metaActionRegex = Regex("""\[Action: ([^\]]+)\]""")

    fun parseActionData(task: Task): TaskActionData {
        // 1. Check Task entity fields first
        if (task.actionType.isNotEmpty()) {
            return TaskActionData(
                type = task.actionType,
                contactName = task.actionContactName,
                contactPhone = task.actionContactPhone,
                message = task.actionMessage
            )
        }
        // 2. Fallback to description tag parsing
        val match = metaActionRegex.find(task.description) ?: return TaskActionData()
        val raw = match.groupValues.getOrNull(1) ?: return TaskActionData()
        val parts = raw.split("|")
        return TaskActionData(
            type = parts.getOrNull(0) ?: "",
            contactName = parts.getOrNull(1) ?: "",
            contactPhone = parts.getOrNull(2) ?: "",
            message = parts.getOrNull(3) ?: ""
        )
    }

    fun parseActionData(habit: Habit): TaskActionData {
        if (habit.actionType.isNotEmpty()) {
            return TaskActionData(
                type = habit.actionType,
                contactName = habit.actionContactName,
                contactPhone = habit.actionContactPhone,
                message = habit.actionMessage
            )
        }
        return TaskActionData()
    }

    fun cleanDescription(description: String): String {
        return description.replace(metaActionRegex, "").trim()
    }

    fun applyActionToDescription(description: String, actionData: TaskActionData): String {
        val clean = cleanDescription(description)
        if (actionData.type.isEmpty()) return clean
        val tag = "[Action: ${actionData.type}|${actionData.contactName}|${actionData.contactPhone}|${actionData.message}]"
        return if (clean.isEmpty()) tag else "$clean\n$tag"
    }

    fun executeAction(context: Context, actionData: TaskActionData) {
        if (actionData.type.isEmpty() || actionData.contactPhone.isEmpty()) return
        val cleanPhone = actionData.contactPhone.replace(Regex("[^0-9+]"), "")
        try {
            when (actionData.type.uppercase()) {
                "CALL" -> {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                }
                "SMS" -> {
                    val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
                        putExtra("sms_body", actionData.message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(smsIntent)
                }
                "WHATSAPP" -> {
                    val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(actionData.message)}"
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(waIntent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not launch action: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
