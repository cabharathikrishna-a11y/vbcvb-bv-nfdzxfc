package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.Habit
import com.example.data.Task
import com.example.ui.AppViewModel
import com.example.ui.theme.Charcoal
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue
import com.example.util.TaskActionHelper
import com.example.util.TaskActionData
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HabitsView(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    // Collect database-backed state flows
    val allDbHabits by viewModel.habits.collectAsState()
    val allCompletions by viewModel.habitCompletions.collectAsState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    // View filter configurations
    var isSidebarExpanded by remember { mutableStateOf(false) }
    var selectedListName by remember { mutableStateOf("all") }
    var selectedTimeOfDayFilter by remember { mutableStateOf("All") } // "All", "Morning", "Afternoon", "Evening", "Night"

    val habitLists = remember {
        mutableStateListOf("all", "Health & Vigor", "Intellect & Learning", "Mindfulness", "Daily Routine")
    }
    var showCreateListDialog by remember { mutableStateOf(false) }

    // Dialog and full-screen state controllers
    var showHabitEditorScreen by remember { mutableStateOf(false) }
    var editingHabitTarget by remember { mutableStateOf<Habit?>(null) }

    val showHistoryDialog by viewModel.showHabitsHistoryDialog.collectAsState()
    var countLogTarget by remember { mutableStateOf<Habit?>(null) }
    var showLongPressOptionsForHabit by remember { mutableStateOf<Habit?>(null) }
    var showOrderMenuForHabitId by remember { mutableStateOf<Int?>(null) }

    val contactsList by viewModel.contacts.collectAsState()

    val externalSelectedId by viewModel.selectedHabitId.collectAsState()
    LaunchedEffect(externalSelectedId, allDbHabits) {
        externalSelectedId?.let { idVal ->
            val habit = allDbHabits.find { it.id == idVal }
            if (habit != null) {
                editingHabitTarget = habit
                showHabitEditorScreen = true
                viewModel.clearSelectedHabitId()
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = isSidebarExpanded) {
        isSidebarExpanded = false
    }

    // Today format helper
    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val dialogContext = remember(context) {
        var cur = context
        while (cur is android.content.ContextWrapper) {
            if (cur is android.app.Activity) {
                return@remember cur
            }
            cur = cur.baseContext
        }
        context
    }

    // Active Tab State ("Today" is open by default)
    var activeTab by remember { mutableStateOf("Today") }

    val allTasks: List<Task> by viewModel.tasks.collectAsState(initial = emptyList())
    val upcomingTasksWithDueDates = remember(allTasks, todayDateStr) {
        allTasks.filter { task: Task ->
            !task.isCompleted && task.dueDateString.isNotBlank() && task.dueDateString >= todayDateStr
        }.sortedBy { it.dueDateString }
    }

    // Filter habits scheduled for *today* based on their frequency profile
    val calendar = Calendar.getInstance()
    val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val currentDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)

    val displayedHabitsToday = allDbHabits.filter { habit ->
        val listMatch = selectedListName.equals("all", ignoreCase = true) || habit.listCategory == selectedListName
        val timeSegMatch = selectedTimeOfDayFilter == "All" || habit.timeOfDay == selectedTimeOfDayFilter
        
        // Schedule eligibility filter
        val scheduledToday = when (habit.frequency.uppercase()) {
            "WEEKLY" -> habit.weeklyDay == currentDayOfWeek
            "MONTHLY" -> currentDayOfMonth >= habit.monthlyStartDate && currentDayOfMonth <= habit.monthlyEndDate
            "MONTHLY_ONCE" -> habit.monthlyStartDate == currentDayOfMonth
            else -> true // "DAILY" always matches
        }

        listMatch && timeSegMatch && scheduledToday
    }

    val displayedHabitsAll = allDbHabits.filter { habit ->
        val listMatch = selectedListName.equals("all", ignoreCase = true) || habit.listCategory == selectedListName
        val timeSegMatch = selectedTimeOfDayFilter == "All" || habit.timeOfDay == selectedTimeOfDayFilter
        listMatch && timeSegMatch
    }

    val displayedHabits = if (activeTab == "Today") displayedHabitsToday else displayedHabitsAll

    Column(modifier = modifier.fillMaxSize().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp)) {
        // Sub-Header panel replacing secondary titles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isSidebarExpanded = !isSidebarExpanded },
                modifier = Modifier.testTag("habits_sidebar_toggle")
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Toggle Sidebar Manager",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedListName.uppercase(),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        // Premium Custom Tab Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF161618))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (activeTab == "Today") WaterBlue else Color.Transparent)
                    .clickable { activeTab = "Today" }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "TODAY'S HABITS",
                    color = if (activeTab == "Today") Color.Black else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (activeTab == "All") WaterBlue else Color.Transparent)
                    .clickable { activeTab = "All" }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ALL HABITS & UPCOMING",
                    color = if (activeTab == "All") Color.Black else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            // Checklist grid card
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = if (isTablet) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f) else Color.Transparent),
                border = if (isTablet) BorderStroke(1.dp, Color(0xFF222222)) else null,
                shape = if (isTablet) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp)
            ) {
                Column(modifier = Modifier.padding(if (isTablet) 16.dp else 0.dp)) {
                    // Time filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (activeTab == "Today") "ACTIVE TODAY" else "ALL HABITS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA1A1AA)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            listOf("All", "Morning", "Afternoon", "Evening", "Night").forEach { timeFilter ->
                                val isSelected = selectedTimeOfDayFilter == timeFilter
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) WaterBlue.copy(alpha = 0.18f) else Color(0xFF1E1E22))
                                        .border(0.5.dp, if (isSelected) WaterBlue.copy(alpha = 0.5f) else Color(0xFF2E2E34), RoundedCornerShape(8.dp))
                                        .clickable { selectedTimeOfDayFilter = timeFilter }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = timeFilter,
                                        color = if (isSelected) WaterBlue else Color(0xFFA1A1AA),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (displayedHabits.isEmpty()) {
                                item {
                                    CenteredEmptyStateView(
                                        icon = Icons.Default.Loop,
                                        title = if (activeTab == "Today") "No Habits For Today" else "No Habits Found",
                                        subtitle = if (activeTab == "Today") "No active habits scheduled for today. Build positive routines by tapping + below!" else "Your habit list is empty. Tap + below to create a new habit.",
                                        accentColor = Color(0xFF818CF8),
                                        orbSize = 72.dp,
                                        iconSize = 34.dp
                                    )
                                }
                            } else {
                                items(displayedHabits) { habit ->
                                    val progressCount = allCompletions.count { it.habitId == habit.id && it.dateString == todayDateStr }
                                    val isCompleted = progressCount >= habit.targetCount

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceCard)
                                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (habit.frequency.uppercase() == "WEEKLY" && habit.weeklyDay != currentDayOfWeek) {
                                                        android.widget.Toast.makeText(context, "Weekly habits can only be updated on their designated day!", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        countLogTarget = habit
                                                    }
                                                },
                                                onLongClick = { showLongPressOptionsForHabit = habit }
                                            )
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 6-dot drag toggle on left
                                        Box(
                                            modifier = Modifier
                                                .padding(end = 10.dp)
                                                .clickable { showOrderMenuForHabitId = habit.id }
                                                .padding(vertical = 4.dp, horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(2.5.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF71717A)))
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = showOrderMenuForHabitId == habit.id,
                                                onDismissRequest = { showOrderMenuForHabitId = null },
                                                modifier = Modifier.background(Charcoal)
                                            ) {
                                                val listToReorder = displayedHabits
                                                val indexInFiltered = listToReorder.indexOf(habit)

                                                DropdownMenuItem(
                                                    text = { Text("Move Up", color = Color.White, fontSize = 13.sp) },
                                                    leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp)) },
                                                    enabled = indexInFiltered > 0,
                                                    onClick = {
                                                        showOrderMenuForHabitId = null
                                                        val mutableFiltered = listToReorder.toMutableList()
                                                        val temp = mutableFiltered[indexInFiltered]
                                                        mutableFiltered[indexInFiltered] = mutableFiltered[indexInFiltered - 1]
                                                        mutableFiltered[indexInFiltered - 1] = temp

                                                        val updatedList = mutableFiltered.mapIndexed { idx, h -> h.copy(orderIndex = idx) }
                                                        viewModel.updateHabitsOrder(updatedList)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Move Down", color = Color.White, fontSize = 13.sp) },
                                                    leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp)) },
                                                    enabled = indexInFiltered < listToReorder.size - 1,
                                                    onClick = {
                                                        showOrderMenuForHabitId = null
                                                        val mutableFiltered = listToReorder.toMutableList()
                                                        val temp = mutableFiltered[indexInFiltered]
                                                        mutableFiltered[indexInFiltered] = mutableFiltered[indexInFiltered + 1]
                                                        mutableFiltered[indexInFiltered + 1] = temp

                                                        val updatedList = mutableFiltered.mapIndexed { idx, h -> h.copy(orderIndex = idx) }
                                                        viewModel.updateHabitsOrder(updatedList)
                                                    }
                                                )
                                            }
                                        }

                                        // Habit Info Column
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = habit.name,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = when (habit.frequency.uppercase()) {
                                                        "WEEKLY" -> "Weekly (${getWeeklyDayName(habit.weeklyDay)})"
                                                        "MONTHLY" -> "Monthly (${habit.monthlyStartDate}-${habit.monthlyEndDate})"
                                                        "MONTHLY_ONCE" -> "Monthly once (${habit.monthlyStartDate})"
                                                        else -> "Daily"
                                                    },
                                                    color = Color(0xFFA1A1AA),
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )

                                                Text(
                                                    text = "•",
                                                    color = Color(0xFF52525B),
                                                    fontSize = 10.sp
                                                )

                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFF27272A))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = habit.timeOfDay,
                                                        color = Color(0xFFD4D4D8),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                }

                                                if (selectedListName.equals("all", ignoreCase = true) && habit.listCategory.isNotBlank()) {
                                                    Text(
                                                        text = "•",
                                                        color = Color(0xFF52525B),
                                                        fontSize = 10.sp
                                                    )

                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(WaterBlue.copy(alpha = 0.15f))
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = habit.listCategory,
                                                            color = WaterBlue,
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }

                                            if (habit.actionType.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(5.dp))
                                                val parsedAction = remember(habit.actionType, habit.actionContactName, habit.actionContactPhone, habit.actionMessage) {
                                                    TaskActionHelper.parseActionData(habit)
                                                }
                                                val (actIcon, actColor, actLabel) = when (parsedAction.type.uppercase()) {
                                                    "CALL" -> Triple("📞", Color(0xFF00E676), "Call ${parsedAction.contactName.ifEmpty { parsedAction.contactPhone }}")
                                                    "SMS" -> Triple("💬", Color(0xFF2E6FF3), "SMS ${parsedAction.contactName.ifEmpty { parsedAction.contactPhone }}")
                                                    "WHATSAPP" -> Triple("🟢", Color(0xFF25D366), "WA ${parsedAction.contactName.ifEmpty { parsedAction.contactPhone }}")
                                                    else -> Triple("⚡", WaterBlue, parsedAction.type)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(actColor.copy(alpha = 0.15f))
                                                        .border(0.5.dp, actColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            TaskActionHelper.executeAction(context, parsedAction)
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "$actIcon $actLabel",
                                                        color = actColor,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        // Right Controls
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Progress Counter clickable pill
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isCompleted) WaterBlue.copy(alpha = 0.18f) else Color(0xFF222226))
                                                    .border(
                                                        1.dp,
                                                        if (isCompleted) WaterBlue.copy(alpha = 0.5f) else Color(0xFF33333C),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        if (habit.frequency.uppercase() == "WEEKLY" && habit.weeklyDay != currentDayOfWeek) {
                                                            android.widget.Toast.makeText(context, "Weekly habits can only be updated on their designated day!", android.widget.Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            countLogTarget = habit
                                                        }
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "$progressCount/${habit.targetCount}",
                                                    color = if (isCompleted) WaterBlue else Color(0xFFE4E4E7),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }

                                            // Direct circular checkmark toggle
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isCompleted) WaterBlue else Color.Transparent)
                                                    .border(
                                                        1.5.dp,
                                                        if (isCompleted) WaterBlue else Color(0xFF52525B),
                                                        CircleShape
                                                    )
                                                    .clickable {
                                                        if (habit.frequency.uppercase() == "WEEKLY" && habit.weeklyDay != currentDayOfWeek) {
                                                            android.widget.Toast.makeText(context, "Weekly habits can only be completed on their designated day!", android.widget.Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            if (isCompleted) {
                                                                allCompletions.filter { it.habitId == habit.id && it.dateString == todayDateStr }
                                                                    .forEach { viewModel.toggleHabit(habit, todayDateStr) }
                                                            } else {
                                                                viewModel.toggleHabit(habit, todayDateStr)
                                                            }
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Complete Toggle",
                                                    tint = if (isCompleted) Color.Black else Color(0xFF71717A),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // If All tab, append Upcoming Tasks
                            if (activeTab == "All") {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Event,
                                            contentDescription = null,
                                            tint = WaterBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "UPCOMING TASKS WITH DUE DATES",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.Gray,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                if (upcomingTasksWithDueDates.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No upcoming tasks with due dates found.", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    items(upcomingTasksWithDueDates) { task ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(SurfaceCard)
                                                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = task.title,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White,
                                                    fontSize = 15.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (task.description.isNotBlank()) {
                                                    Text(
                                                        text = task.description,
                                                        color = Color(0xFFA1A1AA),
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                            // Display Due Date Badge
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFFFFB300).copy(alpha = 0.15f))
                                                    .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(11.dp))
                                                    Text(
                                                        text = task.dueDateString,
                                                        color = Color(0xFFFFB300),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                editingHabitTarget = null
                                showHabitEditorScreen = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("add_habit_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add New Habit", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Categories Sidebar Scrim (Dismiss on click outside)
            androidx.compose.animation.AnimatedVisibility(
                visible = isSidebarExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            isSidebarExpanded = false
                        }
                )
            }

            // Categories Sidebar (Renders on top with solid black background)
            androidx.compose.animation.AnimatedVisibility(
                visible = isSidebarExpanded,
                enter = slideInHorizontally() + fadeIn(),
                exit = slideOutHorizontally() + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Card(
                    modifier = Modifier
                        .width(if (isTablet) 240.dp else 218.dp)
                        .fillMaxHeight()
                        .shadow(16.dp)
                        .clickable(enabled = true, onClick = {}),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = if (isTablet) 12.dp else 16.dp, bottomEnd = if (isTablet) 12.dp else 16.dp),
                    border = BorderStroke(1.dp, Color(0xFF2E2E30))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "HABIT LISTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            IconButton(
                                onClick = { showCreateListDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Create Habit Category",
                                    tint = WaterBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(habitLists) { listName ->
                                val isSelected = selectedListName == listName
                                val textColor = if (isSelected) Color.Black else Color.White
                                val bgContainer = if (isSelected) WaterBlue else Color.Transparent

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bgContainer)
                                        .clickable {
                                            selectedListName = listName
                                            isSidebarExpanded = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderSpecial,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.Black else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = listName,
                                        color = textColor,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )

                                    val count = if (listName.equals("all", ignoreCase = true)) {
                                        allDbHabits.size
                                    } else {
                                        allDbHabits.count { it.listCategory == listName }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(if (isSelected) Color.Black.copy(alpha = 0.15f) else Color(0xFF2E2E30))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = count.toString(),
                                            color = if (isSelected) Color.Black else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Progress Logger target Count control
    countLogTarget?.let { targetHabit ->
        val runningProgress = allCompletions.count { it.habitId == targetHabit.id && it.dateString == todayDateStr }
        AlertDialog(
            onDismissRequest = { countLogTarget = null },
            title = { Text("Increment Habit Count", fontWeight = FontWeight.Bold) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(targetHabit.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Daily Progress count", color = Color.Gray, fontSize = 12.sp)
                    Text("$runningProgress / ${targetHabit.targetCount}", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = WaterBlue)

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(
                            onClick = {
                                if (runningProgress > 0) {
                                    viewModel.toggleHabit(targetHabit, todayDateStr)
                                }
                            },
                            modifier = Modifier.clip(CircleShape).background(SurfaceCard)
                        ) {
                            Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }

                        IconButton(
                            onClick = {
                                viewModel.toggleHabit(targetHabit, todayDateStr)
                            },
                            modifier = Modifier.clip(CircleShape).background(WaterBlue)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increment", tint = Color.Black)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { countLogTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Done")
                }
            }
        )
    }

    // Full-screen Habit Creator and Editor Page (matching Task creation page)
    if (showHabitEditorScreen) {
        HabitEditorFullScreen(
            habit = editingHabitTarget,
            initialListName = selectedListName,
            allLists = habitLists.filter { !it.equals("all", ignoreCase = true) },
            contactsList = contactsList,
            onDismiss = {
                showHabitEditorScreen = false
                editingHabitTarget = null
            },
            onSave = { resultHabit ->
                if (editingHabitTarget != null) {
                    viewModel.updateHabit(resultHabit)
                } else {
                    viewModel.createHabit(
                        name = resultHabit.name,
                        listCategory = resultHabit.listCategory,
                        timeOfDay = resultHabit.timeOfDay,
                        targetCount = resultHabit.targetCount,
                        frequency = resultHabit.frequency,
                        weeklyDay = resultHabit.weeklyDay,
                        monthlyStartDate = resultHabit.monthlyStartDate,
                        monthlyEndDate = resultHabit.monthlyEndDate,
                        scheduledTime = resultHabit.scheduledTime,
                        isReminderEnabled = resultHabit.isReminderEnabled,
                        actionType = resultHabit.actionType,
                        actionContactName = resultHabit.actionContactName,
                        actionContactPhone = resultHabit.actionContactPhone,
                        actionMessage = resultHabit.actionMessage
                    )
                }
                showHabitEditorScreen = false
                editingHabitTarget = null
            },
            onDelete = {
                editingHabitTarget?.let { viewModel.deleteHabit(it) }
                showHabitEditorScreen = false
                editingHabitTarget = null
            },
            onAddList = { newCat ->
                if (newCat.isNotBlank() && !habitLists.contains(newCat)) {
                    habitLists.add(newCat)
                }
            }
        )
    }

    // HISTORY DIALOG showing past list of scheduled habits vs completed with date selection
    if (showHistoryDialog) {
        var selectedHistoryDate by remember { mutableStateOf(Date()) }
        val context = androidx.compose.ui.platform.LocalContext.current
     val dialogContext = remember(context) {
         var cur = context
         while (cur is android.content.ContextWrapper) {
             if (cur is android.app.Activity) {
                 return@remember cur
             }
             cur = cur.baseContext
         }
         context
     }
        val calendarForSelection = remember { Calendar.getInstance().apply { time = selectedHistoryDate } }

        val datePickerDialog = remember(selectedHistoryDate) {
            android.app.DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val newCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    }
                    selectedHistoryDate = newCal.time
                },
                calendarForSelection.get(Calendar.YEAR),
                calendarForSelection.get(Calendar.MONTH),
                calendarForSelection.get(Calendar.DAY_OF_MONTH)
            )
        }

        AlertDialog(
            onDismissRequest = { viewModel.setShowHabitsHistoryDialog(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = WaterBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Habits History Logs", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    // Date Selector Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF151517))
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    time = selectedHistoryDate
                                    add(Calendar.DAY_OF_YEAR, -1)
                                }
                                selectedHistoryDate = cal.time
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day", tint = Color.White)
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { datePickerDialog.show() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Today, contentDescription = "Choose Date", tint = WaterBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault()).format(selectedHistoryDate),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    time = selectedHistoryDate
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }
                                selectedHistoryDate = cal.time
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next Day", tint = Color.White)
                        }
                    }

                    val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(selectedHistoryDate)
                    val pastCal = Calendar.getInstance().apply { time = selectedHistoryDate }
                    val pDayOfWeek = pastCal.get(Calendar.DAY_OF_WEEK)
                    val pDayOfMonth = pastCal.get(Calendar.DAY_OF_MONTH)

                    val historicalScheduledHabits = allDbHabits.filter { habit ->
                        val listMatch = habit.listCategory == selectedListName
                        val scheduledOnPastDate = when (habit.frequency.uppercase()) {
                            "WEEKLY" -> habit.weeklyDay == pDayOfWeek
                            "MONTHLY" -> pDayOfMonth >= habit.monthlyStartDate && pDayOfMonth <= habit.monthlyEndDate
                            "MONTHLY_ONCE" -> habit.monthlyStartDate == pDayOfMonth
                            else -> true
                        }
                        listMatch && scheduledOnPastDate
                    }

                    val totalCountOnDay = historicalScheduledHabits.size
                    val completedCountOnDay = historicalScheduledHabits.count { habit ->
                        val logCount = allCompletions.count { it.habitId == habit.id && it.dateString == dateKey }
                        logCount >= habit.targetCount
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HABITS REPORT FOR THIS DAY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(WaterBlue.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$completedCountOnDay / $totalCountOnDay Completed",
                                fontSize = 10.sp,
                                color = WaterBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (totalCountOnDay > 0) {
                        val progressPct = completedCountOnDay.toFloat() / totalCountOnDay.toFloat()
                        LinearProgressIndicator(
                            progress = { progressPct },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = WaterBlue,
                            trackColor = Color.DarkGray
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (historicalScheduledHabits.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No habits scheduled on this date.",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            items(historicalScheduledHabits) { habit ->
                                val logCount = allCompletions.count { it.habitId == habit.id && it.dateString == dateKey }
                                val isPastCompleted = logCount >= habit.targetCount

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1B1B1D))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = habit.name,
                                            color = if (isPastCompleted) Color.White else Color.Gray,
                                            fontSize = 13.sp,
                                            fontWeight = if (isPastCompleted) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = habit.timeOfDay,
                                            color = Color.DarkGray,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "$logCount/${habit.targetCount}",
                                            color = if (isPastCompleted) WaterBlue else Color.Gray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                        Icon(
                                            imageVector = if (isPastCompleted) Icons.Default.CheckCircle else Icons.Default.Close,
                                            contentDescription = null,
                                            tint = if (isPastCompleted) WaterBlue else Color(0xFFC62828),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.setShowHabitsHistoryDialog(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showCreateListDialog) {
        var newListName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateListDialog = false },
            title = { Text("Create Habit List", fontWeight = FontWeight.Bold, color = Color.White) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                TextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    placeholder = { Text("e.g. Work Routines") },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.LightGray,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = SurfaceCard
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newListName.trim().isNotEmpty() && !habitLists.contains(newListName.trim())) {
                            habitLists.add(newListName.trim())
                            selectedListName = newListName.trim()
                        }
                        showCreateListDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateListDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    showLongPressOptionsForHabit?.let { targetHabit ->
        AlertDialog(
            onDismissRequest = { showLongPressOptionsForHabit = null },
            title = { Text("Manage Habit: ${targetHabit.name}", fontWeight = FontWeight.Bold, color = Color.White) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column {
                    Text("Select an action to modify or delete this habit plan.", color = Color.LightGray, fontSize = 14.sp)
                    val actData = remember(targetHabit) { TaskActionHelper.parseActionData(targetHabit) }
                    if (actData.type.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                TaskActionHelper.executeAction(context, actData)
                                showLongPressOptionsForHabit = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val actLabel = when (actData.type.uppercase()) {
                                "CALL" -> "📞 Call ${actData.contactName.ifEmpty { actData.contactPhone }}"
                                "SMS" -> "💬 Send SMS to ${actData.contactName.ifEmpty { actData.contactPhone }}"
                                "WHATSAPP" -> "🟢 WhatsApp ${actData.contactName.ifEmpty { actData.contactPhone }}"
                                else -> "⚡ Action: ${actData.type}"
                            }
                            Text(actLabel, color = WaterBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            editingHabitTarget = targetHabit
                            showHabitEditorScreen = true
                            showLongPressOptionsForHabit = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            viewModel.deleteHabit(targetHabit)
                            showLongPressOptionsForHabit = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLongPressOptionsForHabit = null }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

private fun getWeeklyDayName(day: Int): String {
    return when (day) {
        Calendar.SUNDAY -> "Sunday"
        Calendar.MONDAY -> "Monday"
        Calendar.TUESDAY -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY -> "Thursday"
        Calendar.FRIDAY -> "Friday"
        Calendar.SATURDAY -> "Saturday"
        else -> "Monday"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorFullScreen(
    habit: Habit?, // null if creating a new habit, non-null if editing
    initialListName: String,
    allLists: List<String>,
    contactsList: List<com.example.data.Contact>,
    onDismiss: () -> Unit,
    onSave: (Habit) -> Unit,
    onDelete: (() -> Unit)? = null,
    onAddList: ((String) -> Unit)? = null
) {
    var habitName by remember(habit) { mutableStateOf(habit?.name ?: "") }
    var selectedList by remember(habit, initialListName) {
        mutableStateOf(
            habit?.listCategory ?: (if (initialListName.equals("all", ignoreCase = true)) "Health & Vigor" else initialListName)
        )
    }
    var timeOfDay by remember(habit) { mutableStateOf(habit?.timeOfDay ?: "Morning") }
    var targetCount by remember(habit) { mutableStateOf(habit?.targetCount ?: 1) }
    var frequency by remember(habit) { mutableStateOf(habit?.frequency ?: "DAILY") }
    var weeklyDay by remember(habit) { mutableStateOf(habit?.weeklyDay ?: 2) }
    var monthlyStartDateStr by remember(habit) { mutableStateOf((habit?.monthlyStartDate ?: 1).toString()) }
    var monthlyEndDateStr by remember(habit) { mutableStateOf((habit?.monthlyEndDate ?: 30).toString()) }
    var scheduledTime by remember(habit) { mutableStateOf(habit?.scheduledTime ?: "08:00") }
    var isReminderEnabled by remember(habit) { mutableStateOf(habit?.isReminderEnabled ?: false) }

    var habitActionData by remember(habit) {
        mutableStateOf(habit?.let { TaskActionHelper.parseActionData(it) } ?: TaskActionData())
    }
    var showActionConfigDialog by remember { mutableStateOf(false) }
    var showNewListDialog by remember { mutableStateOf(false) }
    var newListNameInput by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val initialName = remember(habit) { habit?.name ?: "" }
    val initialTimeOfDay = remember(habit) { habit?.timeOfDay ?: "Morning" }
    val initialFreq = remember(habit) { habit?.frequency ?: "DAILY" }
    val initialTime = remember(habit) { habit?.scheduledTime ?: "08:00" }
    val initialReminder = remember(habit) { habit?.isReminderEnabled ?: false }

    val isModified = remember(habitName, timeOfDay, frequency, scheduledTime, isReminderEnabled, targetCount) {
        habitName != initialName ||
        timeOfDay != initialTimeOfDay ||
        frequency != initialFreq ||
        scheduledTime != initialTime ||
        isReminderEnabled != initialReminder ||
        targetCount != (habit?.targetCount ?: 1)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val dialogContext = remember(context) {
        var cur = context
        while (cur is android.content.ContextWrapper) {
            if (cur is android.app.Activity) return@remember cur
            cur = cur.baseContext
        }
        context
    }

    val handleDismissAttempt = {
        if (isModified && habitName.trim().isNotEmpty()) {
            showUnsavedDialog = true
        } else {
            onDismiss()
        }
    }

    val saveCurrentHabit = {
        if (habitName.trim().isNotEmpty()) {
            val mStart = monthlyStartDateStr.toIntOrNull()?.coerceIn(1, 31) ?: 1
            val mEnd = monthlyEndDateStr.toIntOrNull()?.coerceIn(1, 31) ?: 30

            val resultHabit = Habit(
                id = habit?.id ?: 0,
                name = habitName.trim(),
                listCategory = selectedList,
                timeOfDay = timeOfDay,
                targetCount = targetCount.coerceAtLeast(1),
                frequency = frequency,
                weeklyDay = weeklyDay,
                monthlyStartDate = mStart,
                monthlyEndDate = mEnd,
                streakCount = habit?.streakCount ?: 0,
                lastCompletedTimestamp = habit?.lastCompletedTimestamp,
                scheduledTime = scheduledTime,
                isReminderEnabled = isReminderEnabled,
                orderIndex = habit?.orderIndex ?: 0,
                actionType = habitActionData.type,
                actionContactName = habitActionData.contactName,
                actionContactPhone = habitActionData.contactPhone,
                actionMessage = habitActionData.message
            )
            onSave(resultHabit)
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        handleDismissAttempt()
    }

    Dialog(
        onDismissRequest = { handleDismissAttempt() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Unsaved Changes Confirmation Dialog
                if (showUnsavedDialog) {
                    AlertDialog(
                        onDismissRequest = { showUnsavedDialog = false },
                        title = { Text("Unsaved Changes", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { Text("You have unsaved changes in this habit. Do you want to save them or discard?", color = Color.LightGray) },
                        containerColor = SurfaceCard,
                        confirmButton = {
                            TextButton(onClick = {
                                showUnsavedDialog = false
                                saveCurrentHabit()
                            }) {
                                Text("Save", color = WaterBlue, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showUnsavedDialog = false
                                onDismiss()
                            }) {
                                Text("Discard", color = Color(0xFFF9325D))
                            }
                        }
                    )
                }

                // Delete Confirmation Dialog
                if (showDeleteConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        title = { Text("Delete Habit?", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { Text("Are you sure you want to delete \"${habit?.name}\"? This action cannot be undone.", color = Color.LightGray) },
                        containerColor = SurfaceCard,
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirmDialog = false
                                onDelete?.invoke()
                            }) {
                                Text("Delete", color = Color(0xFFF9325D), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text("Cancel", color = Color.White)
                            }
                        }
                    )
                }

                // Create New Category / List Dialog
                if (showNewListDialog) {
                    AlertDialog(
                        onDismissRequest = { showNewListDialog = false },
                        title = { Text("New Habit Category", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            OutlinedTextField(
                                value = newListNameInput,
                                onValueChange = { newListNameInput = it },
                                label = { Text("Category Name") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = WaterBlue,
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        containerColor = SurfaceCard,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val trimmed = newListNameInput.trim()
                                    if (trimmed.isNotEmpty()) {
                                        onAddList?.invoke(trimmed)
                                        selectedList = trimmed
                                        newListNameInput = ""
                                        showNewListDialog = false
                                    }
                                },
                                enabled = newListNameInput.trim().isNotEmpty()
                            ) {
                                Text("Add", color = WaterBlue, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNewListDialog = false }) {
                                Text("Cancel", color = Color.White)
                            }
                        }
                    )
                }

                // Action Configuration Dialog
                if (showActionConfigDialog) {
                    TaskActionConfigDialog(
                        initialAction = habitActionData,
                        contacts = contactsList,
                        onSave = { updatedAction ->
                            habitActionData = updatedAction
                            showActionConfigDialog = false
                        },
                        onDismiss = { showActionConfigDialog = false }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Top App Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { handleDismissAttempt() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (habit == null) "Create Habit" else "Edit Habit",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (habit == null) "Build a new daily routine" else "Update habit parameters",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (habit != null) {
                                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Habit",
                                        tint = Color(0xFFF9325D)
                                    )
                                }
                            }

                            Button(
                                onClick = { saveCurrentHabit() },
                                enabled = habitName.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WaterBlue,
                                    contentColor = Color.Black,
                                    disabledContainerColor = WaterBlue.copy(alpha = 0.3f),
                                    disabledContentColor = Color.DarkGray
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (habit == null) "Create" else "Save",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Main Scrollable Page Content
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section 1: Habit Information (Hero Card)
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    OutlinedTextField(
                                        value = habitName,
                                        onValueChange = { habitName = it },
                                        label = { Text("Habit Name") },
                                        placeholder = { Text("e.g., Morning Run, Read 20 mins, Meditate", color = Color.Gray) },
                                        leadingIcon = {
                                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = WaterBlue)
                                        },
                                        trailingIcon = {
                                            if (habitName.isNotEmpty()) {
                                                IconButton(onClick = { habitName = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = WaterBlue,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                            focusedLabelColor = WaterBlue,
                                            unfocusedLabelColor = Color.LightGray
                                        ),
                                        modifier = Modifier.fillMaxWidth().testTag("add_habit_name_field")
                                    )

                                    // Category / List Selector Chips
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "CATEGORY / LIST",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )

                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            items(allLists) { listName ->
                                                val isSelected = selectedList.equals(listName, ignoreCase = true)
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(if (isSelected) WaterBlue else Color.White.copy(alpha = 0.08f))
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) WaterBlue else Color.White.copy(alpha = 0.15f),
                                                            RoundedCornerShape(20.dp)
                                                        )
                                                        .clickable { selectedList = listName }
                                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = listName,
                                                        color = if (isSelected) Color.Black else Color.White,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }

                                            item {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(Color.White.copy(alpha = 0.05f))
                                                        .border(
                                                            1.dp,
                                                            BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)).brush,
                                                            RoundedCornerShape(20.dp)
                                                        )
                                                        .clickable { showNewListDialog = true }
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Add, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("New List", color = WaterBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 2: Time of Day Segment Card
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.WbSunny, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "TIME OF DAY SEGMENT",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        listOf(
                                            "Morning" to "🌅 Morning",
                                            "Afternoon" to "☀️ Noon",
                                            "Evening" to "🌆 Evening",
                                            "Night" to "🌙 Night",
                                            "Anytime" to "⚡ Any"
                                        ).forEach { (segKey, label) ->
                                            val isSelected = timeOfDay.equals(segKey, ignoreCase = true)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) WaterBlue else Color.White.copy(alpha = 0.06f))
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) WaterBlue else Color.Transparent,
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { timeOfDay = segKey }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color.Black else Color.LightGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 3: Frequency & Repetition Schedule Card
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Repeat, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "FREQUENCY & TARGET GOAL",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    // Frequency Selector Pills
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        listOf(
                                            "DAILY" to "Daily",
                                            "WEEKLY" to "Weekly",
                                            "MONTHLY" to "Month Span",
                                            "MONTHLY_ONCE" to "Month Day"
                                        ).forEach { (freqKey, label) ->
                                            val isSelected = frequency == freqKey
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) WaterBlue else Color.White.copy(alpha = 0.06f))
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) WaterBlue else Color.Transparent,
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { frequency = freqKey }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color.Black else Color.LightGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                )
                                            }
                                        }
                                    }

                                    // Dynamic Sub-options based on Frequency
                                    if (frequency == "WEEKLY") {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "Select Day of Week:",
                                                color = Color.LightGray,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                // Calendar mappings: 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat, 1=Sun
                                                listOf(
                                                    2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S", 1 to "S"
                                                ).forEach { (calIdx, shortLabel) ->
                                                    val isSelected = weeklyDay == calIdx
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .aspectRatio(1f)
                                                            .clip(CircleShape)
                                                            .background(if (isSelected) WaterBlue else Color.White.copy(alpha = 0.08f))
                                                            .border(
                                                                1.dp,
                                                                if (isSelected) WaterBlue else Color.Transparent,
                                                                CircleShape
                                                            )
                                                            .clickable { weeklyDay = calIdx },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = shortLabel,
                                                            color = if (isSelected) Color.Black else Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (frequency == "MONTHLY") {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "Active Monthly Span (Dates 1 - 31):",
                                                color = Color.LightGray,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                OutlinedTextField(
                                                    value = monthlyStartDateStr,
                                                    onValueChange = { monthlyStartDateStr = it },
                                                    label = { Text("From Day (1-31)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White,
                                                        focusedBorderColor = WaterBlue,
                                                        unfocusedBorderColor = Color.Gray
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = monthlyEndDateStr,
                                                    onValueChange = { monthlyEndDateStr = it },
                                                    label = { Text("To Day (1-31)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White,
                                                        focusedBorderColor = WaterBlue,
                                                        unfocusedBorderColor = Color.Gray
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }

                                    if (frequency == "MONTHLY_ONCE") {
                                        OutlinedTextField(
                                            value = monthlyStartDateStr,
                                            onValueChange = { monthlyStartDateStr = it },
                                            label = { Text("Day of Month to Repeat (1-31)") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = WaterBlue,
                                                unfocusedBorderColor = Color.Gray
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

                                    // Target completions stepper
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = "Target Completions",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "How many times per interval",
                                                color = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { if (targetCount > 1) targetCount-- },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.08f))
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }

                                            Text(
                                                text = "$targetCount time${if (targetCount > 1) "s" else ""}",
                                                color = WaterBlue,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )

                                            IconButton(
                                                onClick = { if (targetCount < 50) targetCount++ },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.08f))
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 4: Scheduled Time & Notification Reminder Card
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Alarm, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "NOTIFICATION & SCHEDULE",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    // Reminder Switch Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Daily Reminder Alert",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Send alert notification when it's time",
                                                color = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Switch(
                                            checked = isReminderEnabled,
                                            onCheckedChange = { isReminderEnabled = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.Black,
                                                checkedTrackColor = WaterBlue,
                                                uncheckedThumbColor = Color.LightGray,
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                        )
                                    }

                                    Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

                                    // Scheduled Time Picker
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.05f))
                                            .clickable {
                                                val timeParts = scheduledTime.split(":")
                                                val initialHour = timeParts.getOrNull(0)?.toIntOrNull() ?: 8
                                                val initialMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
                                                android.app.TimePickerDialog(
                                                    dialogContext,
                                                    { _, hourOfDay, minute ->
                                                        scheduledTime = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                                                    },
                                                    initialHour,
                                                    initialMinute,
                                                    true
                                                ).show()
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = WaterBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("Scheduled Time", color = Color.Gray, fontSize = 11.sp)
                                                Text(scheduledTime, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Text("Change", color = WaterBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Section 5: Habit Smart Action Automation Card
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "SMART ACTION AUTOMATION",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.05f))
                                            .clickable { showActionConfigDialog = true }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Automated Trigger Action",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (habitActionData.type.isNotEmpty()) {
                                                    val summary = when (habitActionData.type.uppercase()) {
                                                        "CALL" -> "📞 Call ${habitActionData.contactName.ifEmpty { habitActionData.contactPhone }}"
                                                        "SMS" -> "💬 SMS ${habitActionData.contactName.ifEmpty { habitActionData.contactPhone }}"
                                                        "WHATSAPP" -> "🟢 WhatsApp ${habitActionData.contactName.ifEmpty { habitActionData.contactPhone }}"
                                                        else -> habitActionData.type
                                                    }
                                                    Text(
                                                        text = summary,
                                                        color = WaterBlue,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                } else {
                                                    Text(
                                                        text = "Call, SMS, or WhatsApp reminder (Optional)",
                                                        color = Color.Gray,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }

                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom Action CTA Button
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { saveCurrentHabit() },
                                enabled = habitName.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WaterBlue,
                                    contentColor = Color.Black,
                                    disabledContainerColor = WaterBlue.copy(alpha = 0.3f),
                                    disabledContentColor = Color.DarkGray
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (habit == null) "Create Habit Plan" else "Save Changes",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}


