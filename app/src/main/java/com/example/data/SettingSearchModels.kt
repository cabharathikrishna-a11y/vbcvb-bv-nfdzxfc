package com.example.data

data class SettingSearchResult(
    val title: String,
    val subtitle: String,
    val categoryName: String,
    val pageId: Int, // The target activePage in SettingsView (e.g. 1 for General System, 24 for Notifications, etc.)
    val scrollPercent: Float = 0f, // 0.0f = top, 0.4f = middle, 0.65f = bottom half
    val keywords: List<String> = emptyList(),
    val iconName: String = "settings"
)

object SettingSearchRegistry {
    val ALL_SETTINGS = listOf(
        SettingSearchResult(
            title = "General System Settings",
            subtitle = "Tab alignment, navigation bar reordering, themes & network usage",
            categoryName = "Core Systems & AI",
            pageId = 1,
            scrollPercent = 0.0f,
            keywords = listOf("system", "general", "style", "theme", "dark mode", "light mode", "orientation", "anti burn"),
            iconName = "settings"
        ),
        SettingSearchResult(
            title = "Tab Alignment & Navigation Order",
            subtitle = "Reorder bottom navigation tabs, orientation & customize visibility",
            categoryName = "Core Systems & AI",
            pageId = 1,
            scrollPercent = 0.6f,
            keywords = listOf("tab", "tabs", "tab alignment", "navbar", "navigation", "reorder", "bottom bar", "hide tab", "navigation bar"),
            iconName = "tab"
        ),
        SettingSearchResult(
            title = "Network Usage & Diagnostics",
            subtitle = "Inspect mobile data, Wi-Fi traffic, background sync speed & bandwidth",
            categoryName = "Core Systems & AI",
            pageId = 1,
            scrollPercent = 0.1f,
            keywords = listOf("network", "data", "traffic", "wifi", "internet", "bandwidth", "usage", "diagnostics"),
            iconName = "data_usage"
        ),
        SettingSearchResult(
            title = "Notification Settings",
            subtitle = "Control automatic triggers, friend alerts, water reminders, sleep & task alarms",
            categoryName = "Core Systems & AI",
            pageId = 24,
            scrollPercent = 0.0f,
            keywords = listOf("notification", "notifications", "alerts", "triggers", "friend alerts", "alarm", "alarms", "battery optimization", "listener"),
            iconName = "notifications"
        ),
        SettingSearchResult(
            title = "Water Drinking Reminders",
            subtitle = "Hydration alert intervals, drink water notifications & chime reminders",
            categoryName = "Core Systems & AI",
            pageId = 24,
            scrollPercent = 0.65f,
            keywords = listOf("water", "drink water", "hydrate", "hydration", "water reminder", "drink"),
            iconName = "water"
        ),
        SettingSearchResult(
            title = "Diagnostics & Background Operations",
            subtitle = "Fix stopwatch lockscreen freeze & background recording on Samsung/Oppo/Lenovo/Moto",
            categoryName = "Core Systems & AI",
            pageId = 17,
            scrollPercent = 0.0f,
            keywords = listOf("diagnostics", "background", "stopwatch", "freeze", "lockscreen", "power", "battery", "samsung", "oem", "recording"),
            iconName = "info"
        ),
        SettingSearchResult(
            title = "Battery Optimization Whitelist",
            subtitle = "Prevent OS killing background timer, alarms & water notifications",
            categoryName = "Core Systems & AI",
            pageId = 17,
            scrollPercent = 0.55f,
            keywords = listOf("battery", "optimization", "whitelist", "kill", "background timer", "power saver", "battery optimization"),
            iconName = "battery"
        ),
        SettingSearchResult(
            title = "App Update Center",
            subtitle = "Check for updates, manage background downloads, authenticate tester",
            categoryName = "Core Systems & AI",
            pageId = 16,
            scrollPercent = 0.0f,
            keywords = listOf("update", "updates", "version", "download", "tester", "build", "check update", "patch", "apk"),
            iconName = "refresh"
        ),
        SettingSearchResult(
            title = "Deepa AI Brain & Memories Vault",
            subtitle = "Offline model caching, vector memories vault management & persona",
            categoryName = "Core Systems & AI",
            pageId = 11,
            scrollPercent = 0.0f,
            keywords = listOf("deepa", "ai", "brain", "model", "cache", "memory", "memories", "vault", "gemini", "assistant", "persona", "vectors"),
            iconName = "face"
        ),
        SettingSearchResult(
            title = "Backup & Restore Vault",
            subtitle = "JSON manual database import & security exports, data recovery",
            categoryName = "Core Systems & AI",
            pageId = 12,
            scrollPercent = 0.0f,
            keywords = listOf("backup", "restore", "export", "import", "json", "database", "save data", "cloud backup", "recover"),
            iconName = "backup"
        ),
        SettingSearchResult(
            title = "Timer Configuration & Pomodoro",
            subtitle = "Session periods, default break times, ticking sound & vibration toggles",
            categoryName = "Productivity Core",
            pageId = 2,
            scrollPercent = 0.0f,
            keywords = listOf("timer", "pomodoro", "focus", "break", "break time", "session", "stopwatch"),
            iconName = "timer"
        ),
        SettingSearchResult(
            title = "Timer Vibration & Sound Cues",
            subtitle = "Haptic feedback, ticking sounds, completion buzzes & alerts",
            categoryName = "Productivity Core",
            pageId = 2,
            scrollPercent = 0.65f,
            keywords = listOf("vibration", "vibrate", "ticking", "sound", "timer sound", "haptic", "buzz", "alert"),
            iconName = "volume"
        ),
        SettingSearchResult(
            title = "Tasks Engine & Reminders",
            subtitle = "Reminder frequencies, custom vibrators, default lists & task priority",
            categoryName = "Productivity Core",
            pageId = 3,
            scrollPercent = 0.0f,
            keywords = listOf("task", "tasks", "engine", "reminder", "frequency", "priority", "default list", "vibrator", "deadline", "todo"),
            iconName = "list"
        ),
        SettingSearchResult(
            title = "Calendar Planner Settings",
            subtitle = "Style layouts, display preferences, timeline filters & Google Calendar sync",
            categoryName = "Productivity Core",
            pageId = 4,
            scrollPercent = 0.0f,
            keywords = listOf("calendar", "planner", "layout", "timeline", "google calendar", "sync", "events", "display", "schedule"),
            iconName = "calendar"
        ),
        SettingSearchResult(
            title = "Habits Tracker Settings",
            subtitle = "Streak calculations, automatic midnight reset triggers & daily targets",
            categoryName = "Productivity Core",
            pageId = 5,
            scrollPercent = 0.0f,
            keywords = listOf("habit", "habits", "tracker", "streak", "reset", "midnight", "daily", "completion", "target"),
            iconName = "habits"
        ),
        SettingSearchResult(
            title = "Sleep & Wake-up Alarm",
            subtitle = "Bedtime reminders, wake-up alarms, snooze duration & sleep status logs",
            categoryName = "Productivity Core",
            pageId = 21,
            scrollPercent = 0.3f,
            keywords = listOf("sleep", "wake", "alarm", "bedtime", "snooze", "wake-up", "morning", "night", "rest", "circadian"),
            iconName = "alarm"
        ),
        SettingSearchResult(
            title = "Countdowns & Timed Alerts",
            subtitle = "Background notifications, custom alert parameters, event reminders",
            categoryName = "Logs & Utilities",
            pageId = 6,
            scrollPercent = 0.0f,
            keywords = listOf("countdown", "countdowns", "event", "target date", "timer alert", "anniversary", "days left"),
            iconName = "countdown"
        ),
        SettingSearchResult(
            title = "Life Journal Settings",
            subtitle = "Storage usage indexers, backup matching constraints & diary preferences",
            categoryName = "Logs & Utilities",
            pageId = 7,
            scrollPercent = 0.0f,
            keywords = listOf("journal", "diary", "life journal", "entry", "reflection", "storage"),
            iconName = "journal"
        ),
        SettingSearchResult(
            title = "Contacts Directory Settings",
            subtitle = "Full syncing filters, categories pairing, anniversaries & contact groups",
            categoryName = "Logs & Utilities",
            pageId = 8,
            scrollPercent = 0.0f,
            keywords = listOf("contact", "contacts", "directory", "sync", "categories", "phone", "birthday", "anniversary"),
            iconName = "contacts"
        ),
        SettingSearchResult(
            title = "Contacts Toolkit & Merging",
            subtitle = "Resolve duplicates & merge conflicts with matching phone, name, or Instagram IDs",
            categoryName = "Logs & Utilities",
            pageId = 25,
            scrollPercent = 0.0f,
            keywords = listOf("duplicate", "duplicates", "merge", "contacts toolkit", "conflict", "cleanup", "dedup"),
            iconName = "merge"
        ),
        SettingSearchResult(
            title = "File Explorer Settings",
            subtitle = "Workspace directories, index preferred storage & file manager",
            categoryName = "File & Financials",
            pageId = 9,
            scrollPercent = 0.0f,
            keywords = listOf("file", "files", "explorer", "storage", "folder", "directory", "documents", "path"),
            iconName = "folder"
        ),
        SettingSearchResult(
            title = "Financial Ledger Settings",
            subtitle = "Accounts, custom family members, categories reporting & currency (₹)",
            categoryName = "File & Financials",
            pageId = 10,
            scrollPercent = 0.0f,
            keywords = listOf("finance", "finances", "ledger", "money", "rupee", "currency", "accounts", "expenses", "income", "budget", "inr"),
            iconName = "monetization"
        ),
        SettingSearchResult(
            title = "Secure App Lock & Privacy",
            subtitle = "Verify code settings, PIN setups, biometric lock & recovery questions",
            categoryName = "Security & Privacy Settings",
            pageId = 13,
            scrollPercent = 0.0f,
            keywords = listOf("lock", "app lock", "pin", "password", "security", "biometric", "fingerprint", "privacy", "passcode"),
            iconName = "lock"
        ),
        SettingSearchResult(
            title = "Blocks & Screen Limits (App Blocker)",
            subtitle = "Establish application constraints, usage warnings, Instagram & YouTube blocks",
            categoryName = "Security & Privacy Settings",
            pageId = 14,
            scrollPercent = 0.55f,
            keywords = listOf("block", "blocks", "screen limit", "limit", "app block", "usage", "restriction", "detox", "app blocker", "instagram block", "youtube block"),
            iconName = "block"
        ),
        SettingSearchResult(
            title = "Permissions & API Connections",
            subtitle = "Manage system permissions, Google Drive sync, and Keep Notes integration",
            categoryName = "Security & Privacy Settings",
            pageId = 19,
            scrollPercent = 0.45f,
            keywords = listOf("permission", "permissions", "api", "google drive", "drive", "keep notes", "access", "cloud sync", "camera", "microphone"),
            iconName = "check_circle"
        ),
        SettingSearchResult(
            title = "User Info Profile",
            subtitle = "Configure profile name, nickname, email, bio, and custom avatar",
            categoryName = "Account & Sync",
            pageId = 15,
            scrollPercent = 0.0f,
            keywords = listOf("profile", "user", "name", "nickname", "avatar", "photo", "account", "email", "bio"),
            iconName = "person"
        ),
        SettingSearchResult(
            title = "Deep Links & Automation Routes",
            subtitle = "Copy application deep links, automation URI routes & intent triggers",
            categoryName = "Account & Sync",
            pageId = 18,
            scrollPercent = 0.0f,
            keywords = listOf("deep link", "deep links", "shortcuts", "uri", "route", "automation", "url", "intent"),
            iconName = "share"
        ),
        SettingSearchResult(
            title = "Fitness Sync & Health Trends",
            subtitle = "Health Connect sync, steps, heart rate & activity trends",
            categoryName = "Logs & Utilities",
            pageId = 20,
            scrollPercent = 0.0f,
            keywords = listOf("health", "fitness", "steps", "health connect", "heart rate", "activity"),
            iconName = "fitness"
        ),
        SettingSearchResult(
            title = "Focus Locker & Strict Session",
            subtitle = "Strict focus locking mode, emergency unlock rules & anti-distraction",
            categoryName = "Security & Privacy Settings",
            pageId = 23,
            scrollPercent = 0.0f,
            keywords = listOf("focus locker", "strict", "lockdown", "emergency unlock", "distraction"),
            iconName = "security"
        ),
        SettingSearchResult(
            title = "Keyboard Shortcuts Help",
            subtitle = "View all connected physical keyboard shortcuts & mappings",
            categoryName = "Core Systems & AI",
            pageId = 99,
            scrollPercent = 0.0f,
            keywords = listOf("keyboard", "shortcuts", "hotkeys", "key mapping", "hardware keyboard"),
            iconName = "keyboard"
        )
    )

    fun search(query: String): List<SettingSearchResult> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val tokens = q.split(" ").filter { it.isNotBlank() }

        return ALL_SETTINGS.filter { item ->
            val titleLower = item.title.lowercase()
            val subLower = item.subtitle.lowercase()
            val catLower = item.categoryName.lowercase()
            val allKeywords = item.keywords.map { it.lowercase() }

            // Match full query
            if (titleLower.contains(q) || subLower.contains(q) || catLower.contains(q) || allKeywords.any { it.contains(q) }) {
                return@filter true
            }

            // Match every word token in title, subtitle, category or keywords
            tokens.all { token ->
                titleLower.contains(token) ||
                subLower.contains(token) ||
                catLower.contains(token) ||
                allKeywords.any { it.contains(token) }
            }
        }
    }
}
