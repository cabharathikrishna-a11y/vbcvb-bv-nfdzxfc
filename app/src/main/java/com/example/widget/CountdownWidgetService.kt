package com.example.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.data.AppDatabase
import com.example.ui.components.BUILT_IN_FESTIVALS
import com.example.ui.components.parseDateStringToCalendar
import com.example.ui.components.hasYearMentioned
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CountdownWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return CountdownRemoteViewsFactory(this.applicationContext, intent)
    }
}

data class CountdownWidgetEvent(
    val id: String,
    val name: String,
    val category: String,
    val daysRemaining: Int,
    val detailSubtitle: String,
    val emoji: String,
    val targetTimestamp: Long
)

class CountdownRemoteViewsFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<CountdownWidgetEvent> = emptyList()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        try {
            val db = AppDatabase.getInstance(context)
            val contacts: List<com.example.data.Contact> = runBlocking {
                try {
                    db.contactDao().getAllContactsDirect()
                } catch (e: Exception) {
                    Log.e("CountdownWidget", "Error fetching contacts", e)
                    emptyList<com.example.data.Contact>()
                }
            }
            val deadlines: List<com.example.data.Deadline> = runBlocking {
                try {
                    db.deadlineDao().getAllDeadlinesDirect()
                } catch (e: Exception) {
                    Log.e("CountdownWidget", "Error fetching deadlines", e)
                    emptyList<com.example.data.Deadline>()
                }
            }

            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayMillis = today.timeInMillis
            val dateFmt = SimpleDateFormat("dd/MM", Locale.getDefault())

            val allEvents = mutableListOf<CountdownWidgetEvent>()

            // 1. Built-in Festivals
            for (fest in BUILT_IN_FESTIVALS) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.MONTH, fest.month - 1)
                    set(Calendar.DAY_OF_MONTH, fest.day)
                    set(Calendar.YEAR, today.get(Calendar.YEAR))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // If festival already passed this year, roll over to next year
                if (cal.timeInMillis < todayMillis) {
                    cal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
                }
                val diffMs = cal.timeInMillis - todayMillis
                val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())
                allEvents.add(
                    CountdownWidgetEvent(
                        id = "fest_${fest.name.lowercase().replace(" ", "_")}",
                        name = fest.name,
                        category = "Festivals",
                        daysRemaining = daysRemaining,
                        detailSubtitle = "Festival · ${dateFmt.format(cal.time)}",
                        emoji = "🎉",
                        targetTimestamp = cal.timeInMillis
                    )
                )
            }

            // 2. Contacts Birthdays
            for (contact in contacts) {
                val dobStr = contact.dobString.trim()
                if (dobStr.isEmpty()) continue
                val parsed = parseDateStringToCalendar(dobStr) ?: continue

                val dobMonth = parsed.get(Calendar.MONTH)
                val dobDay = parsed.get(Calendar.DAY_OF_MONTH)
                val hasYear = hasYearMentioned(dobStr)
                val birthYear = if (hasYear) parsed.get(Calendar.YEAR) else null

                val bdayCal = Calendar.getInstance().apply {
                    set(Calendar.MONTH, dobMonth)
                    set(Calendar.DAY_OF_MONTH, dobDay)
                    set(Calendar.YEAR, today.get(Calendar.YEAR))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // If birthday already happened this year (prior to today), move to next year
                if (bdayCal.timeInMillis < todayMillis) {
                    bdayCal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
                }

                val upcomingYear = bdayCal.get(Calendar.YEAR)
                val ageStr = if (birthYear != null && birthYear > 0) "${upcomingYear - birthYear}th Birthday" else "Birthday"
                val diffMs = bdayCal.timeInMillis - todayMillis
                val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())

                val displayName = "${contact.firstName} ${contact.lastName}".trim().ifEmpty { "Contact" }
                allEvents.add(
                    CountdownWidgetEvent(
                        id = "bday_${contact.id}",
                        name = displayName,
                        category = "Birthdays",
                        daysRemaining = daysRemaining,
                        detailSubtitle = "$ageStr · ${dateFmt.format(bdayCal.time)}",
                        emoji = "🎂",
                        targetTimestamp = bdayCal.timeInMillis
                    )
                )
            }

            // 3. Contacts Anniversaries
            for (contact in contacts) {
                val annivStr = contact.anniversaryString.trim()
                if (annivStr.isEmpty()) continue
                val parsed = parseDateStringToCalendar(annivStr) ?: continue

                val month = parsed.get(Calendar.MONTH)
                val day = parsed.get(Calendar.DAY_OF_MONTH)
                val hasYear = hasYearMentioned(annivStr)
                val annivYear = if (hasYear) parsed.get(Calendar.YEAR) else null

                val annivCal = Calendar.getInstance().apply {
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.YEAR, today.get(Calendar.YEAR))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (annivCal.timeInMillis < todayMillis) {
                    annivCal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
                }

                val upcomingYear = annivCal.get(Calendar.YEAR)
                val yrStr = if (annivYear != null && annivYear > 0) "${upcomingYear - annivYear}th Anniversary" else "Anniversary"
                val diffMs = annivCal.timeInMillis - todayMillis
                val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())

                val displayName = "${contact.firstName} ${contact.lastName}".trim().ifEmpty { "Contact" }
                allEvents.add(
                    CountdownWidgetEvent(
                        id = "anniv_${contact.id}",
                        name = displayName,
                        category = "Anniversaries",
                        daysRemaining = daysRemaining,
                        detailSubtitle = "$yrStr · ${dateFmt.format(annivCal.time)}",
                        emoji = "💍",
                        targetTimestamp = annivCal.timeInMillis
                    )
                )
            }

            // 4. Deadlines & User Countdowns
            for (deadline in deadlines) {
                if (deadline.isCompleted) continue

                val isFestival = deadline.name.startsWith("[Festival] ") || deadline.name.startsWith("[Festivals] ")
                val isBirthday = deadline.name.startsWith("[Birthday] ") || deadline.name.startsWith("[Birthdays] ")
                val isAnniversary = deadline.name.startsWith("[Anniversary] ") || deadline.name.startsWith("[Anniversaries] ")
                val isRecurring = isFestival || isBirthday || isAnniversary || deadline.name.contains("recurring", ignoreCase = true)

                val cleanName = when {
                    isFestival -> deadline.name.substringAfter("] ")
                    isBirthday -> deadline.name.substringAfter("] ")
                    isAnniversary -> deadline.name.substringAfter("] ")
                    else -> deadline.name
                }
                val cat = when {
                    isFestival -> "Festivals"
                    isBirthday -> "Birthdays"
                    isAnniversary -> "Anniversaries"
                    else -> "Others"
                }
                val emoji = when {
                    isFestival -> "🎉"
                    isBirthday -> "🎂"
                    isAnniversary -> "💍"
                    else -> "⏳"
                }

                var targetTime = deadline.targetTimestamp
                if (isRecurring) {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = targetTime
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        set(Calendar.YEAR, today.get(Calendar.YEAR))
                    }
                    if (cal.timeInMillis < todayMillis) {
                        cal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
                    }
                    targetTime = cal.timeInMillis
                }

                val diffMs = targetTime - todayMillis
                val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())
                val targetDate = Date(targetTime)

                allEvents.add(
                    CountdownWidgetEvent(
                        id = "deadline_${deadline.id}",
                        name = cleanName,
                        category = cat,
                        daysRemaining = daysRemaining,
                        detailSubtitle = "$cat · ${dateFmt.format(targetDate)}",
                        emoji = emoji,
                        targetTimestamp = targetTime
                    )
                )
            }

            // Deduplicate items with identical name & target day
            val deduplicated = mutableListOf<CountdownWidgetEvent>()
            for (item in allEvents) {
                val exists = deduplicated.any {
                    it.name.equals(item.name, ignoreCase = true) && it.daysRemaining == item.daysRemaining
                }
                if (!exists) {
                    deduplicated.add(item)
                }
            }

            // CRITICAL REQUIREMENT: "in countdown order in widget or in list view show todays bd or things at start"
            // Filter strictly upcoming items: daysRemaining >= 0 (today: 0, tomorrow: 1, and future)
            // Prioritize items occurring TODAY (daysRemaining == 0) at the very top, followed by daysRemaining ascending, then name
            this.items = deduplicated
                .filter { it.daysRemaining >= 0 }
                .sortedWith(compareBy(
                    { if (it.daysRemaining == 0) 0 else 1 },
                    { it.daysRemaining },
                    { it.name }
                ))

        } catch (e: Exception) {
            Log.e("CountdownWidget", "Error during loadData", e)
            this.items = emptyList()
        }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null
        val item = items[position]

        val rv = RemoteViews(context.packageName, R.layout.widget_countdown_item)

        rv.setTextViewText(R.id.item_emoji, item.emoji)
        rv.setTextViewText(R.id.item_title, item.name)
        rv.setTextViewText(R.id.item_subtitle, item.detailSubtitle)

        // CRITICAL REQUIREMENTS:
        // "today in green coulor tmr means in orange only upcoming shows today tooo"
        when (item.daysRemaining) {
            0 -> {
                // TODAY: Vibrant Green Pill & Text
                rv.setTextViewText(R.id.item_badge, "TODAY 🥳")
                rv.setInt(R.id.item_badge, "setBackgroundResource", R.drawable.bg_countdown_pill_today_green)
                rv.setTextColor(R.id.item_badge, Color.parseColor("#4ADE80"))
            }
            1 -> {
                // TOMORROW: Vibrant Orange Pill & Text
                rv.setTextViewText(R.id.item_badge, "TOMORROW")
                rv.setInt(R.id.item_badge, "setBackgroundResource", R.drawable.bg_countdown_pill_tomorrow_orange)
                rv.setTextColor(R.id.item_badge, Color.parseColor("#FB923C"))
            }
            else -> {
                // Upcoming: Sleek dark capsule
                rv.setTextViewText(R.id.item_badge, "${item.daysRemaining} DAYS")
                rv.setInt(R.id.item_badge, "setBackgroundResource", R.drawable.bg_countdown_pill_upcoming)
                val badgeTextColor = when (item.category) {
                    "Birthdays" -> Color.parseColor("#F48FB1")
                    "Anniversaries" -> Color.parseColor("#CE93D8")
                    "Festivals" -> Color.parseColor("#FBBF24")
                    else -> Color.parseColor("#38BDF8")
                }
                rv.setTextColor(R.id.item_badge, badgeTextColor)
            }
        }

        // Fill-in Intent for clicking this row
        val fillInIntent = Intent().apply {
            putExtra("SHOW_COUNTDOWN_PAGE", true)
            putExtra("COUNTDOWN_ID", item.id)
            putExtra("NAVIGATE_TO", "COUNTDOWN")
        }
        rv.setOnClickFillInIntent(R.id.countdown_item_root, fillInIntent)

        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        if (position in items.indices) {
            return items[position].id.hashCode().toLong()
        }
        return position.toLong()
    }

    override fun hasStableIds(): Boolean = true
}
