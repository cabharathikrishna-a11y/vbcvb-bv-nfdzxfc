package com.example.ui.components

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Deadline
import com.example.ui.AppViewModel
import com.example.ui.theme.Charcoal
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// --- COUNTDOWN DATA MODELS & HELPERS ---

data class CountdownReminder(
    val daysBefore: Int,
    val timeString: String // "HH:mm"
)

data class CountdownItem(
    val id: String,
    val name: String,
    val targetTimestamp: Long,
    val category: String, // Birthdays, Anniversaries, Others, Festivals
    val contactId: Int? = null,
    val isDbBacked: Boolean = false,
    val dbId: Int = 0,
    val originalDateStr: String = "" // DD/MM/YYYY representation
)

fun parseDateStringToCalendar(dateStr: String): Calendar? {
    if (dateStr.isBlank()) return null
    val cleaned = dateStr.trim().replace("-", "/") // normalize dividers
    val cal = Calendar.getInstance()
    val today = Calendar.getInstance()

    try {
        if (cleaned.contains("/")) {
            val parts = cleaned.split("/")
            if (parts.size >= 2) {
                if (parts[0].length == 4) { // YYYY/MM/DD
                    val year = parts[0].toIntOrNull() ?: today.get(Calendar.YEAR)
                    val month = parts[1].toIntOrNull() ?: 1
                    val day = parts[2].toIntOrNull() ?: 1
                    cal.set(Calendar.YEAR, year)
                    cal.set(Calendar.MONTH, month - 1)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                } else { // DD/MM/YYYY or DD/MM
                    val day = parts[0].toIntOrNull() ?: 1
                    val month = parts[1].toIntOrNull() ?: 1
                    val year = if (parts.size >= 3) parts[2].toIntOrNull() else null
                    cal.set(Calendar.DAY_OF_MONTH, day)
                    cal.set(Calendar.MONTH, month - 1)
                    if (year != null && parts.size >= 3 && parts[2].trim().length >= 4) {
                        cal.set(Calendar.YEAR, year)
                    } else {
                        cal.set(Calendar.YEAR, today.get(Calendar.YEAR))
                    }
                }
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal
            }
        }
    } catch (e: Exception) {
        // Fallback
    }
    return null
}

fun hasYearMentioned(dateStr: String): Boolean {
    if (dateStr.isBlank()) return false
    val cleaned = dateStr.trim().replace("-", "/")
    if (cleaned.contains("/")) {
        val parts = cleaned.split("/")
        if (parts.size >= 3) {
            val p0 = parts[0].trim()
            val p2 = parts[2].trim()
            return (p0.length >= 4 && p0.toIntOrNull() != null) ||
                   (p2.length >= 4 && p2.toIntOrNull() != null)
        }
    }
    return false
}

fun formatAutoDate(input: String, previous: String): String {
    if (input.length < previous.length) {
        return input
    }
    val clean = input.take(10)
    return buildString {
        for (i in clean.indices) {
            val char = clean[i]
            if (i == 2) {
                if (char != '/') append('/')
            } else if (i == 5) {
                if (char != '/') append('/')
            }
            append(char)
        }
        if (this.length == 2 && !this.endsWith("/")) {
            append('/')
        } else if (this.length == 5 && !this.endsWith("/")) {
            append('/')
        }
    }.take(10)
}

data class FestivalTemplate(val name: String, val month: Int, val day: Int)

val BUILT_IN_FESTIVALS = listOf(
    FestivalTemplate("New Year's Day", 1, 1),
    FestivalTemplate("Makar Sankranti / Pongal", 1, 14),
    FestivalTemplate("Republic Day", 1, 26),
    FestivalTemplate("Maha Shivratri", 2, 15),
    FestivalTemplate("Holi (Festival of Colors)", 3, 4),
    FestivalTemplate("Eid ul-Fitr", 3, 20),
    FestivalTemplate("Good Friday", 4, 3),
    FestivalTemplate("Easter Sunday", 4, 5),
    FestivalTemplate("Baisakhi / Vishu / Puthandu", 4, 14),
    FestivalTemplate("Eid al-Adha (Bakrid)", 5, 27),
    FestivalTemplate("Independence Day", 8, 15),
    FestivalTemplate("Raksha Bandhan", 8, 28),
    FestivalTemplate("Janmashtami", 9, 4),
    FestivalTemplate("Ganesh Chaturthi", 9, 14),
    FestivalTemplate("Gandhi Jayanti", 10, 2),
    FestivalTemplate("Navratri / Durga Puja", 10, 11),
    FestivalTemplate("Dussehra (Vijayadashami)", 10, 20),
    FestivalTemplate("Halloween", 10, 31),
    FestivalTemplate("Diwali (Deepavali)", 11, 8),
    FestivalTemplate("Govardhan Puja / Bhai Dooj", 11, 10),
    FestivalTemplate("Guru Nanak Jayanti", 11, 24),
    FestivalTemplate("Thanksgiving", 11, 26),
    FestivalTemplate("Christmas Day", 12, 25),
    FestivalTemplate("New Year's Eve", 12, 31)
)

// --- COUNTDOWN SCREEN COMPOSTABLE ---

@Composable
fun CountdownView(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val contacts by viewModel.contacts.collectAsState()
    val deadlines by viewModel.deadlines.collectAsState()
    val systemCalendarEvents by viewModel.systemCalendarEvents.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadSystemCalendarEvents(context)
    }

    var activeCategoryFilter by remember { mutableStateOf("All") }
    val categories = listOf("All", "Festivals", "Birthdays", "Anniversaries", "Others")
    var sortOption by remember { mutableStateOf("Soonest") }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newEventCategory by remember { mutableStateOf("Festivals") }
    var eventName by remember { mutableStateOf("") }
    var eventDateText by remember { mutableStateOf("") } // Input is dd/mm/yyyy

    // Selected item for pop-out details view (for "others"/manual)
    var selectedItemForDetail by remember { mutableStateOf<CountdownItem?>(null) }
    var detailEditMode by remember { mutableStateOf(false) }
    var detailNameEdit by remember { mutableStateOf("") }
    var detailDateEdit by remember { mutableStateOf("") }

    // Dynamic Festivals derived from built-in festival list + Google Calendar / systemCalendarEvents
    val derivedFestivalCountdowns = remember(systemCalendarEvents) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMillis = today.timeInMillis

        // 1. Built-in major festivals
        val builtInItems = BUILT_IN_FESTIVALS.map { fest ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.MONTH, fest.month - 1)
                set(Calendar.DAY_OF_MONTH, fest.day)
                set(Calendar.YEAR, today.get(Calendar.YEAR))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis < todayMillis - 24 * 3600 * 1000L) {
                cal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
            }
            val displayDateSdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val originalDateDisplay = displayDateSdf.format(cal.time)
            CountdownItem(
                id = "builtin_fest_${fest.name.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                name = fest.name,
                targetTimestamp = cal.timeInMillis,
                category = "Festivals",
                originalDateStr = originalDateDisplay
            )
        }

        // 2. Dynamic Calendar Holidays from system / Google Calendar
        val systemItems = systemCalendarEvents
            .filter { it.isHolidayOrFestival }
            .distinctBy { "${it.title.trim().lowercase()}_${it.dateStr}" }
            .mapNotNull { event ->
                val dateStr = event.dateStr
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val eventDate = try { sdf.parse(dateStr) } catch (e: Exception) { null }
                val cal = Calendar.getInstance()
                if (eventDate != null) {
                    cal.time = eventDate
                } else if (event.startMillis > 0) {
                    cal.timeInMillis = event.startMillis
                } else {
                    return@mapNotNull null
                }
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                // If the festival occurred earlier in current year, move to next year for upcoming countdown
                if (cal.timeInMillis < todayMillis - 24 * 3600 * 1000L) {
                    cal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
                }

                val displayDateSdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val originalDateDisplay = displayDateSdf.format(cal.time)

                CountdownItem(
                    id = "festival_${event.id}_${event.dateStr}",
                    name = com.example.util.GoogleCalendarSyncHelper.cleanDisplayEventTitle(event.title),
                    targetTimestamp = cal.timeInMillis,
                    category = "Festivals",
                    originalDateStr = originalDateDisplay
                )
            }

        val combined = (builtInItems + systemItems)
        val deduplicated = mutableListOf<CountdownItem>()
        for (item in combined) {
            val normName = com.example.util.GoogleCalendarSyncHelper.normalizeEventTitle(item.name)
            val exists = deduplicated.any { existing ->
                val exNorm = com.example.util.GoogleCalendarSyncHelper.normalizeEventTitle(existing.name)
                exNorm == normName || (exNorm.isNotEmpty() && normName.isNotEmpty() && (exNorm.contains(normName) || normName.contains(exNorm)))
            }
            if (!exists) {
                deduplicated.add(item)
            }
        }
        deduplicated
    }

    // Dynamic Birthdays derived directly from Contacts DOB formatted as DD/MM/YYYY
    val derivedBirthdayCountdowns = remember(contacts) {
        contacts.filter { it.dobString.isNotEmpty() }.mapNotNull { contact ->
            val dateStr = contact.dobString.trim()
            val parsedCal = parseDateStringToCalendar(dateStr) ?: return@mapNotNull null
            
            val dobMonth = parsedCal.get(Calendar.MONTH)
            val dobDay = parsedCal.get(Calendar.DAY_OF_MONTH)
            val hasYear = hasYearMentioned(dateStr)
            val birthYear = if (hasYear) parsedCal.get(Calendar.YEAR) else null

            val today = Calendar.getInstance()
            val birthdayCal = Calendar.getInstance().apply {
                set(Calendar.MONTH, dobMonth)
                set(Calendar.DAY_OF_MONTH, dobDay)
                set(Calendar.YEAR, today.get(Calendar.YEAR))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // Yearly Cycle checking: if birthday already happened this year (ignoring today), move to next year
            if (birthdayCal.timeInMillis < today.timeInMillis - 24 * 3600 * 1000L) {
                birthdayCal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
            }

            val upcomingCycleYear = birthdayCal.get(Calendar.YEAR)
            val ageStr = if (birthYear != null && birthYear > 0) " (${upcomingCycleYear - birthYear}th Birthday)" else ""

            CountdownItem(
                id = "contact_bday_${contact.id}",
                name = "${contact.firstName} ${contact.lastName}'s Birthday$ageStr",
                targetTimestamp = birthdayCal.timeInMillis,
                category = "Birthdays",
                contactId = contact.id,
                originalDateStr = dateStr
            )
        }
    }

    // Dynamic Anniversaries derived from Contacts Anniversary formatted as DD/MM/YYYY
    val derivedAnniversaryCountdowns = remember(contacts) {
        contacts.filter { it.anniversaryString.isNotEmpty() }.mapNotNull { contact ->
            val dateStr = contact.anniversaryString.trim()
            val parsedCal = parseDateStringToCalendar(dateStr) ?: return@mapNotNull null
            
            val month = parsedCal.get(Calendar.MONTH)
            val day = parsedCal.get(Calendar.DAY_OF_MONTH)
            val hasYear = hasYearMentioned(dateStr)
            val annivYear = if (hasYear) parsedCal.get(Calendar.YEAR) else null

            val today = Calendar.getInstance()
            val anniversaryCal = Calendar.getInstance().apply {
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.YEAR, today.get(Calendar.YEAR))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // Yearly Cycle checking
            if (anniversaryCal.timeInMillis < today.timeInMillis - 24 * 3600 * 1000L) {
                anniversaryCal.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
            }

            val upcomingCycleYear = anniversaryCal.get(Calendar.YEAR)
            val ageStr = if (annivYear != null && annivYear > 0) " (${upcomingCycleYear - annivYear}th Anniversary)" else ""

            CountdownItem(
                id = "contact_anniv_${contact.id}",
                name = "${contact.firstName} ${contact.lastName}'s Anniversary$ageStr",
                targetTimestamp = anniversaryCal.timeInMillis,
                category = "Anniversaries",
                contactId = contact.id,
                originalDateStr = dateStr
            )
        }
    }

    // Database Persistent Deadlines mapped as "Others" or user-created "Festivals" Countdowns
    val derivedDeadlineCountdowns = remember(deadlines) {
        deadlines.filter { !it.isCompleted }.map { d ->
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date(d.targetTimestamp))
            val isFestival = d.name.startsWith("[Festival] ") || d.name.startsWith("[Festivals] ")
            val cleanName = if (isFestival) d.name.substringAfter("] ") else d.name
            val category = if (isFestival) "Festivals" else "Others"
            CountdownItem(
                id = "db_deadline_${d.id}",
                name = cleanName,
                targetTimestamp = d.targetTimestamp,
                category = category,
                isDbBacked = true,
                dbId = d.id,
                originalDateStr = dateStr
            )
        }
    }

    // Combine all countdowns
    val allCountdowns = remember(derivedFestivalCountdowns, derivedBirthdayCountdowns, derivedAnniversaryCountdowns, derivedDeadlineCountdowns) {
        derivedFestivalCountdowns + derivedBirthdayCountdowns + derivedAnniversaryCountdowns + derivedDeadlineCountdowns
    }

    // Deep link selection handling from Global Search
    val extSelectedCountdownId by viewModel.selectedCountdownId.collectAsState()
    LaunchedEffect(extSelectedCountdownId, allCountdowns) {
        extSelectedCountdownId?.let { idVal ->
            val item = allCountdowns.find { it.id == idVal.toString() || (it.dbId != 0 && it.dbId == idVal) }
            if (item != null) {
                selectedItemForDetail = item
                detailEditMode = false
                detailNameEdit = item.name
                detailDateEdit = item.originalDateStr
                viewModel.clearSelectedCountdownId()
            }
        }
    }

    // Filtered and Sorted countdowns
    val filteredCountdowns = remember(allCountdowns, activeCategoryFilter, sortOption) {
        val list = allCountdowns.filter { item ->
            activeCategoryFilter == "All" || item.category.equals(activeCategoryFilter, ignoreCase = true)
        }
        when (sortOption) {
            "Soonest" -> list.sortedBy { it.targetTimestamp }
            "Furthest" -> list.sortedByDescending { it.targetTimestamp }
            "Festivals First" -> list.sortedWith(compareBy({ if (it.category == "Festivals") 0 else 1 }, { it.targetTimestamp }))
            "Birthdays First" -> list.sortedWith(compareBy({ if (it.category == "Birthdays") 0 else 1 }, { it.targetTimestamp }))
            "Name (A-Z)" -> list.sortedBy { it.name.lowercase(Locale.ROOT) }
            "Name (Z-A)" -> list.sortedByDescending { it.name.lowercase(Locale.ROOT) }
            else -> list.sortedBy { it.targetTimestamp }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // Top Header Row: Title & Count Badge + Sort Dropdown + Add Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "COUNTDOWNS",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 0.8.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E26))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${filteredCountdowns.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = WaterBlue
                    )
                }
            }

            // Right side: Dedicated Sort Box and Add Action Button
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dedicated Sort Box
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Charcoal)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .clickable { sortMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = when (sortOption) {
                                "Soonest" -> "⏳ Soonest"
                                "Furthest" -> "📅 Furthest"
                                "Festivals First" -> "🎉 Festivals"
                                "Birthdays First" -> "🎂 Birthdays"
                                "Name (A-Z)" -> "🔤 A → Z"
                                "Name (Z-A)" -> "🔠 Z → A"
                                else -> sortOption
                            },
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort Options", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        modifier = Modifier.background(SurfaceCard)
                    ) {
                        listOf(
                            "Soonest" to "⏳ Soonest First",
                            "Furthest" to "📅 Furthest First",
                            "Festivals First" to "🎉 Festivals First",
                            "Birthdays First" to "🎂 Birthdays First",
                            "Name (A-Z)" to "🔤 Name (A → Z)",
                            "Name (Z-A)" to "🔠 Name (Z → A)"
                        ).forEach { (opt, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (sortOption == opt) WaterBlue else Color.White,
                                        fontWeight = if (sortOption == opt) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp
                                    )
                                },
                                onClick = {
                                    sortOption = opt
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Add button
                IconButton(
                    onClick = {
                        eventName = ""
                        newEventCategory = if (activeCategoryFilter in listOf("Festivals", "Others", "Birthdays", "Anniversaries")) activeCategoryFilter else "Festivals"
                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        eventDateText = sdf.format(Date(System.currentTimeMillis() + 10 * 24 * 3600 * 1000L))
                        showAddDialog = true
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(WaterBlue)
                        .size(34.dp)
                        .testTag("add_countdown_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Countdown",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Full-Width Category Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val filterOptions = listOf(
                "All" to "All (${allCountdowns.size})",
                "Birthdays" to "🎂 Birthdays (${derivedBirthdayCountdowns.size})",
                "Anniversaries" to "💍 Anniversaries (${derivedAnniversaryCountdowns.size})",
                "Festivals" to "🎉 Festivals (${derivedFestivalCountdowns.size})",
                "Others" to "🎯 Others (${derivedDeadlineCountdowns.size})"
            )
            filterOptions.forEach { (catKey, label) ->
                val isSelected = activeCategoryFilter == catKey
                val catColor = when (catKey) {
                    "Birthdays" -> Color(0xFFF48FB1)
                    "Anniversaries" -> Color(0xFFCE93D8)
                    "Festivals" -> Color(0xFFFFB74D)
                    "Others" -> WaterBlue
                    else -> WaterBlue
                }
                val bg = if (isSelected) catColor else Color(0xFF131317)
                val txtColor = if (isSelected) Color.Black else Color(0xFFD6D6DE)
                val borderStroke = if (isSelected) BorderStroke(1.dp, catColor) else BorderStroke(1.dp, Color(0xFF24242E))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = bg,
                    border = borderStroke,
                    modifier = Modifier.clickable { activeCategoryFilter = catKey }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.5.sp,
                        color = txtColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Countdown Sleek Compact List
        if (filteredCountdowns.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No upcoming countdowns in this category.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 72.dp)
            ) {
                items(filteredCountdowns, key = { it.id }) { item ->
                    val diffMs = item.targetTimestamp - System.currentTimeMillis()
                    val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())

                    val catColor = when (item.category) {
                        "Festivals" -> Color(0xFFFFB74D)
                        "Birthdays" -> Color(0xFFF48FB1)
                        "Anniversaries" -> Color(0xFFCE93D8)
                        else -> WaterBlue
                    }
                    val iconBgGradient = when (item.category) {
                        "Festivals" -> Brush.linearGradient(listOf(Color(0xFF382309), Color(0xFF1F1405)))
                        "Birthdays" -> Brush.linearGradient(listOf(Color(0xFF3B1527), Color(0xFF220C17)))
                        "Anniversaries" -> Brush.linearGradient(listOf(Color(0xFF2E173E), Color(0xFF1A0D24)))
                        else -> Brush.linearGradient(listOf(Color(0xFF0F2633), Color(0xFF081720)))
                    }
                    val iconEmoji = when (item.category) {
                        "Festivals" -> "🎉"
                        "Birthdays" -> "🎂"
                        "Anniversaries" -> "💍"
                        else -> "⏳"
                    }

                    val displayTitle = when {
                        item.category == "Birthdays" && item.name.contains("'s Birthday") -> item.name.substringBefore("'s Birthday").trim()
                        item.category == "Anniversaries" && item.name.contains("'s Anniversary") -> item.name.substringBefore("'s Anniversary").trim()
                        item.name.contains(" (") -> item.name.substringBefore(" (").trim()
                        else -> item.name
                    }

                    val ageOrDetail = when {
                        item.category == "Birthdays" -> {
                            val age = Regex("\\((\\d+.*?)\\)").find(item.name)?.groupValues?.get(1)
                            listOfNotNull(age, item.originalDateStr.ifBlank { null }).joinToString(" · ")
                        }
                        item.category == "Anniversaries" -> {
                            val anniv = Regex("\\((\\d+.*?)\\)").find(item.name)?.groupValues?.get(1)
                            listOfNotNull(anniv, item.originalDateStr.ifBlank { null }).joinToString(" · ")
                        }
                        else -> {
                            listOfNotNull(item.category, item.originalDateStr.ifBlank { null }).joinToString(" · ")
                        }
                    }

                    val progressValue = remember(daysRemaining) {
                        if (item.category in listOf("Festivals", "Birthdays", "Anniversaries")) {
                            val percent = (365f - daysRemaining) / 365f
                            maxOf(0.05f, minOf(1.0f, percent))
                        } else {
                            val totalSampleDays = 30f
                            val percent = (totalSampleDays - daysRemaining) / totalSampleDays
                            maxOf(0.08f, minOf(1.0f, percent))
                        }
                    }

                    // Sleek, compact, high-visibility card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedItemForDetail = item
                                detailEditMode = false
                                detailNameEdit = item.name
                                detailDateEdit = item.originalDateStr
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (daysRemaining <= 3) catColor.copy(alpha = 0.5f)
                            else if (daysRemaining <= 7) catColor.copy(alpha = 0.25f)
                            else Color(0xFF1E1E26)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Icon Squircle
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(iconBgGradient)
                                    .border(1.dp, catColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = iconEmoji, fontSize = 20.sp)
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Middle: Title + Subtitle details + Slim accent progress
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = displayTitle,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = if (ageOrDetail.isNotBlank()) ageOrDetail else item.category,
                                    fontSize = 11.5.sp,
                                    color = if (daysRemaining <= 3) catColor else Color(0xFF9E9EA8),
                                    fontWeight = if (daysRemaining <= 3) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(5.dp))

                                LinearProgressIndicator(
                                    progress = progressValue,
                                    color = catColor,
                                    trackColor = Color.White.copy(alpha = 0.06f),
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(2.5.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Right: High-Visibility Countdown Capsule
                            if (daysRemaining == 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            Brush.horizontalGradient(listOf(Color(0xFFE91E63), Color(0xFFFF5722)))
                                        )
                                        .padding(horizontal = 11.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "TODAY! 🥳",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                }
                            } else if (daysRemaining == 1) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(catColor.copy(alpha = 0.15f))
                                        .border(1.dp, catColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "1 DAY",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = catColor
                                        )
                                        Text(
                                            text = "tomorrow",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.LightGray
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF09090D),
                                    border = BorderStroke(
                                        1.dp,
                                        if (daysRemaining <= 7) catColor.copy(alpha = 0.45f)
                                        else Color(0xFF22222B)
                                    ),
                                    modifier = Modifier.defaultMinSize(minWidth = 54.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "$daysRemaining",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (daysRemaining <= 7) catColor else Color.White
                                        )
                                        Text(
                                            text = "DAYS",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (daysRemaining <= 7) catColor.copy(alpha = 0.85f) else Color.Gray,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }

                            if (item.isDbBacked) {
                                IconButton(
                                    onClick = {
                                        viewModel.deleteDeadline(Deadline(id = item.dbId, name = item.name, targetTimestamp = item.targetTimestamp))
                                    },
                                    modifier = Modifier.size(26.dp).padding(start = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Modal dialogue popup to insert a brand new milestone
    if (showAddDialog) {
        val handleDismissAttempt = {
            if (eventName.isNotEmpty()) {
                showUnsavedDialog = true
            } else {
                showAddDialog = false
            }
        }

        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { Text("Unsaved Changes", color = Color.White) },
                text = { Text("You have unsaved changes. Do you want to save or discard them?", color = Color.LightGray) },
                containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
                confirmButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        if (eventName.isNotEmpty()) {
                            val parsedCal = parseDateStringToCalendar(eventDateText)
                            val targetTime = parsedCal?.timeInMillis ?: (System.currentTimeMillis() + 10 * 24 * 3600 * 1000L)
                            val finalName = if (newEventCategory == "Festivals") "[Festival] $eventName" else eventName
                            viewModel.createDeadline(finalName, (maxOf(0L, targetTime - System.currentTimeMillis()) / (24 * 3600 * 1000L)))
                        }
                        showAddDialog = false
                    }) {
                        Text("Save", color = WaterBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        showAddDialog = false
                    }) {
                        Text("Discard", color = Color(0xFFF9325D))
                    }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { handleDismissAttempt() },
            title = { Text("Add Countdown Event", fontWeight = FontWeight.Bold, color = Color.White) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Category Selection Chips
                    Text("Category", color = Color.Gray, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Festivals", "Others").forEach { cat ->
                            val isSel = newEventCategory == cat
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) (if (cat == "Festivals") Color(0xFFAB47BC) else WaterBlue) else Charcoal)
                                    .clickable { newEventCategory = cat }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (cat == "Festivals") "🎉 Festival" else "🎯 Other Milestone",
                                    color = if (isSel) Color.White else Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    TextField(
                        value = eventName,
                        onValueChange = { eventName = it },
                        label = { Text(if (newEventCategory == "Festivals") "Festival Name" else "Milestone Title") },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.LightGray,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("countdown_title_input")
                    )

                    val context = LocalContext.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val calendar = Calendar.getInstance()
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        eventDateText = String.format(java.util.Locale.US, "%02d/%02d/%04d", dayOfMonth, month + 1, year)
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                    ) {
                        TextField(
                            value = eventDateText,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("Target Date (DD/MM/YYYY)") },
                            placeholder = { Text("Click to select date...") },
                            colors = TextFieldDefaults.colors(
                                disabledTextColor = Color.White,
                                disabledLabelColor = Color.LightGray,
                                disabledContainerColor = SurfaceCard,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("countdown_date_input")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventName.isNotEmpty()) {
                            val parsedCal = parseDateStringToCalendar(eventDateText)
                            val targetTime = parsedCal?.timeInMillis ?: (System.currentTimeMillis() + 10 * 24 * 3600 * 1000L)
                            val finalName = if (newEventCategory == "Festivals") "[Festival] $eventName" else eventName
                            viewModel.createDeadline(finalName, (maxOf(0L, targetTime - System.currentTimeMillis()) / (24 * 3600 * 1000L)))
                        }
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Add", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    // Pop-out Details view for individual "Others" category items, allowing viewing & editing & deleting
    selectedItemForDetail?.let { item ->
        AlertDialog(
            onDismissRequest = { 
                selectedItemForDetail = null
                detailEditMode = false
            },
            title = {
                Text(
                    text = if (detailEditMode) "Edit Milestone" else "Milestone Details",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (detailEditMode) {
                        TextField(
                            value = detailNameEdit,
                            onValueChange = { detailNameEdit = it },
                            label = { Text("Milestone Title") },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = SurfaceCard
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val context = LocalContext.current
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val calendar = Calendar.getInstance()
                                    val parsed = parseDateStringToCalendar(detailDateEdit)
                                    if (parsed != null) {
                                        calendar.timeInMillis = parsed.timeInMillis
                                    }
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, year, month, dayOfMonth ->
                                            detailDateEdit = String.format(java.util.Locale.US, "%02d/%02d/%04d", dayOfMonth, month + 1, year)
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                        ) {
                            TextField(
                                value = detailDateEdit,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                label = { Text("Target Date (DD/MM/YYYY)") },
                                placeholder = { Text("Click to select date...") },
                                colors = TextFieldDefaults.colors(
                                    disabledTextColor = Color.White,
                                    disabledLabelColor = Color.LightGray,
                                    disabledContainerColor = SurfaceCard,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Column {
                            Text("Title:", color = Color.Gray, fontSize = 12.sp)
                            if (item.name.contains(" (")) {
                                val index = item.name.indexOf(" (")
                                val mainPart = item.name.substring(0, index)
                                val agePart = item.name.substring(index).trim()
                                Column {
                                    Text(mainPart, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(agePart, color = WaterBlue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            } else {
                                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }

                        Column {
                            Text("Target Date:", color = Color.Gray, fontSize = 12.sp)
                            Text(item.originalDateStr, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        val diffMs = item.targetTimestamp - System.currentTimeMillis()
                        val daysRemaining = maxOf(0, ((diffMs + 12 * 3600 * 1000L) / (24 * 3600 * 1000L)).toInt())
                        
                        Column {
                            Text("Time Remaining:", color = Color.Gray, fontSize = 12.sp)
                            Text("$daysRemaining Days Left", color = WaterBlue, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        }

                        // Display visual bars inside pop-out detailed view too!
                        val progressPercent = maxOf(0.1f, minOf(1.0f, (30f - daysRemaining) / 30f))
                        Column {
                            Text("Visual Timeline Tracker:", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                            LinearProgressIndicator(
                                progress = progressPercent,
                                color = WaterBlue,
                                trackColor = Color.LightGray.copy(alpha = 0.1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detailEditMode) {
                        Button(
                            onClick = {
                                if (detailDateEdit.isNotEmpty()) {
                                    if (item.category == "Birthdays" && item.contactId != null) {
                                        val c = contacts.firstOrNull { it.id == item.contactId }
                                        if (c != null) {
                                            viewModel.updateContact(c.copy(dobString = detailDateEdit))
                                        }
                                    } else if (item.category == "Anniversaries" && item.contactId != null) {
                                        val c = contacts.firstOrNull { it.id == item.contactId }
                                        if (c != null) {
                                            viewModel.updateContact(c.copy(anniversaryString = detailDateEdit))
                                        }
                                    } else if (item.isDbBacked && detailNameEdit.isNotEmpty()) {
                                        val parsedCal = parseDateStringToCalendar(detailDateEdit)
                                        val targetTime = parsedCal?.timeInMillis ?: item.targetTimestamp
                                        viewModel.updateDeadline(
                                            Deadline(
                                                id = item.dbId,
                                                name = detailNameEdit,
                                                targetTimestamp = targetTime,
                                                isCompleted = false
                                            )
                                        )
                                    }
                                }
                                selectedItemForDetail = null
                                detailEditMode = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                        ) {
                            Text("Save Changes", fontWeight = FontWeight.Bold)
                        }

                        TextButton(onClick = { detailEditMode = false }) {
                            Text("Cancel", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { detailEditMode = true },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit")
                        }

                        if (item.contactId != null) {
                            Button(
                                onClick = {
                                    viewModel.selectContact(item.contactId)
                                    selectedItemForDetail = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard, contentColor = WaterBlue)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Profile")
                            }
                        }

                        Button(
                            onClick = {
                                if (item.category == "Birthdays" && item.contactId != null) {
                                    val c = contacts.firstOrNull { it.id == item.contactId }
                                    if (c != null) {
                                        viewModel.updateContact(c.copy(dobString = ""))
                                    }
                                } else if (item.category == "Anniversaries" && item.contactId != null) {
                                    val c = contacts.firstOrNull { it.id == item.contactId }
                                    if (c != null) {
                                        viewModel.updateContact(c.copy(anniversaryString = ""))
                                    }
                                } else if (item.isDbBacked) {
                                    viewModel.deleteDeadline(
                                        Deadline(
                                            id = item.dbId,
                                            name = item.name,
                                            targetTimestamp = item.targetTimestamp
                                        )
                                    )
                                }
                                selectedItemForDetail = null
                                detailEditMode = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        )
    }
}
