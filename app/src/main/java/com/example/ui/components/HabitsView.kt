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

    // Dialog state controllers
    var showCreateEditDialog by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var editingHabitTarget by remember { mutableStateOf<Habit?>(null) }

    val showHistoryDialog by viewModel.showHabitsHistoryDialog.collectAsState()
    var countLogTarget by remember { mutableStateOf<Habit?>(null) }
    var showLongPressOptionsForHabit by remember { mutableStateOf<Habit?>(null) }
    var showOrderMenuForHabitId by remember { mutableStateOf<Int?>(null) }

    // Local form states
    var curHabitName by remember { mutableStateOf("") }
    var curTimeOfDay by remember { mutableStateOf("Morning") }
    var curTargetCount by remember { mutableStateOf("1") }
    var curFrequency by remember { mutableStateOf("DAILY") } // DAILY, WEEKLY, MONTHLY, MONTHLY_ONCE
    var curWeeklyDay by remember { mutableStateOf(2) } // Calendar.MONDAY = 2
    var curMonthlyStartDate by remember { mutableStateOf("1") }
    var curMonthlyEndDate by remember { mutableStateOf("30") }
    var curScheduledTime by remember { mutableStateOf("08:00") }
    var curIsReminderEnabled by remember { mutableStateOf(false) }
    var curHabitActionData by remember { mutableStateOf(TaskActionData()) }
    var showActionConfigDialog by remember { mutableStateOf(false) }
    val contactsList by viewModel.contacts.collectAsState()

    val externalSelectedId by viewModel.selectedHabitId.collectAsState()
    LaunchedEffect(externalSelectedId, allDbHabits) {
        externalSelectedId?.let { idVal ->
            val habit = allDbHabits.find { it.id == idVal }
            if (habit != null) {
                isEditMode = true
                editingHabitTarget = habit
                curHabitName = habit.name
                curTimeOfDay = habit.timeOfDay
                curTargetCount = habit.targetCount.toString()
                curFrequency = habit.frequency
                curWeeklyDay = habit.weeklyDay
                curMonthlyStartDate = habit.monthlyStartDate.toString()
                curMonthlyEndDate = habit.monthlyEndDate.toString()
                curScheduledTime = habit.scheduledTime
                curIsReminderEnabled = habit.isReminderEnabled
                curHabitActionData = TaskActionHelper.parseActionData(habit)
                showCreateEditDialog = true
                viewModel.clearSelectedHabitId()
            }
        }
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
                                isEditMode = false
                                curHabitName = ""
                                curTimeOfDay = "Morning"
                                curTargetCount = "1"
                                curFrequency = "DAILY"
                                curWeeklyDay = 2
                                curMonthlyStartDate = "1"
                                curMonthlyEndDate = "30"
                                curScheduledTime = "08:00"
                                curIsReminderEnabled = false
                                curHabitActionData = TaskActionData()
                                showCreateEditDialog = true
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

    // CREATE OR EDIT DIALOG with "Add or Save" button control
    var showUnsavedDialog by remember { mutableStateOf(false) }

    if (showCreateEditDialog) {
        val handleDismissAttempt = {
            if (curHabitName.trim().isNotEmpty()) {
                showUnsavedDialog = true
            } else {
                showCreateEditDialog = false
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
                        if (curHabitName.trim().isNotEmpty()) {
                            val targetInt = curTargetCount.toIntOrNull() ?: 1
                            val mStart = curMonthlyStartDate.toIntOrNull() ?: 1
                            val mEnd = curMonthlyEndDate.toIntOrNull() ?: 30
                            
                            val freshHabit = Habit(
                                id = if (isEditMode) (editingHabitTarget?.id ?: 0) else 0,
                                name = curHabitName.trim(),
                                listCategory = if (selectedListName.equals("all", ignoreCase = true)) {
                                    habitLists.firstOrNull { !it.equals("all", ignoreCase = true) } ?: "Health & Vigor"
                                } else {
                                    selectedListName
                                },
                                timeOfDay = curTimeOfDay,
                                targetCount = targetInt,
                                frequency = curFrequency,
                                weeklyDay = curWeeklyDay,
                                monthlyStartDate = mStart,
                                monthlyEndDate = mEnd,
                                streakCount = if (isEditMode) (editingHabitTarget?.streakCount ?: 0) else 0,
                                lastCompletedTimestamp = if (isEditMode) (editingHabitTarget?.lastCompletedTimestamp) else null,
                                scheduledTime = curScheduledTime,
                                isReminderEnabled = curIsReminderEnabled,
                                actionType = curHabitActionData.type,
                                actionContactName = curHabitActionData.contactName,
                                actionContactPhone = curHabitActionData.contactPhone,
                                actionMessage = curHabitActionData.message
                            )

                            if (isEditMode) {
                                viewModel.updateHabit(freshHabit)
                            } else {
                                viewModel.createHabit(
                                    name = freshHabit.name,
                                    listCategory = freshHabit.listCategory,
                                    timeOfDay = freshHabit.timeOfDay,
                                    targetCount = freshHabit.targetCount,
                                    frequency = freshHabit.frequency,
                                    weeklyDay = freshHabit.weeklyDay,
                                    monthlyStartDate = freshHabit.monthlyStartDate,
                                    monthlyEndDate = freshHabit.monthlyEndDate,
                                    scheduledTime = freshHabit.scheduledTime,
                                    isReminderEnabled = freshHabit.isReminderEnabled,
                                    actionType = freshHabit.actionType,
                                    actionContactName = freshHabit.actionContactName,
                                    actionContactPhone = freshHabit.actionContactPhone,
                                    actionMessage = freshHabit.actionMessage
                                )
                            }
                        }
                        showCreateEditDialog = false
                    }) {
                        Text("Save", color = WaterBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        showCreateEditDialog = false
                    }) {
                        Text("Discard", color = Color(0xFFF9325D))
                    }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { handleDismissAttempt() },
            title = { Text(if (isEditMode) "Edit Habit Plan" else "Define New Habit Plan", fontWeight = FontWeight.Bold, color = Color.White) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        TextField(
                            value = curHabitName,
                            onValueChange = { curHabitName = it },
                            label = { Text("Habit name (e.g. Meditate)") },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = SurfaceCard
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("add_habit_name_field")
                        )
                    }

                    item {
                        TextField(
                            value = curTargetCount,
                            onValueChange = { curTargetCount = it },
                            label = { Text("Daily Target count") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = SurfaceCard
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text("Day Segment", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("Morning", "Afternoon", "Evening", "Night").forEach { label ->
                                val isSelected = curTimeOfDay == label
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) WaterBlue else SurfaceCard)
                                        .clickable { curTimeOfDay = label }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (isSelected) Color.Black else Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val timeParts = curScheduledTime.split(":")
                                        val initialHour = timeParts.getOrNull(0)?.toIntOrNull() ?: 8
                                        val initialMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
                                        android.app.TimePickerDialog(
                                            dialogContext,
                                            { _, hourOfDay, minute ->
                                                curScheduledTime = String.format("%02d:%02d", hourOfDay, minute)
                                            },
                                            initialHour,
                                            initialMinute,
                                            true
                                        ).show()
                                    }
                            ) {
                                TextField(
                                    value = curScheduledTime,
                                    onValueChange = { },
                                    readOnly = true,
                                    enabled = false,
                                    label = { Text("Scheduled Time") },
                                    placeholder = { Text("e.g. 08:00") },
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.LightGray,
                                        disabledTextColor = Color.White,
                                        focusedContainerColor = SurfaceCard,
                                        unfocusedContainerColor = SurfaceCard,
                                        disabledContainerColor = SurfaceCard,
                                        disabledLabelColor = Color.Gray,
                                        disabledPlaceholderColor = Color.Gray
                                    ),
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = "Select Time",
                                            tint = WaterBlue
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("habit_time_field")
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reminder", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = curIsReminderEnabled,
                                    onCheckedChange = { curIsReminderEnabled = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = WaterBlue, checkedTrackColor = WaterBlue.copy(alpha = 0.5f))
                                )
                            }
                        }
                    }

                    item {
                        Text("Scheduling Profile", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("DAILY", "WEEKLY", "MONTHLY", "MONTHLY_ONCE").forEach { freq ->
                                val isSelected = curFrequency == freq
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) WaterBlue else SurfaceCard)
                                        .clickable { curFrequency = freq }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = when(freq) {
                                            "DAILY" -> "Daily"
                                            "WEEKLY" -> "Weekly"
                                            "MONTHLY" -> "Month Span"
                                            else -> "Month Day"
                                        },
                                        color = if (isSelected) Color.Black else Color.LightGray,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Schedule option parameters based on Selected Frequency profile
                    if (curFrequency == "WEEKLY") {
                        item {
                            Text("Repeat Weekly on:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Maps 2=Monday, 3=Tuesday, 4=Wednesday, 5=Thursday, 6=Friday, 7=Saturday, 1=Sunday
                                listOf(
                                    2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S", 1 to "S"
                                ).forEach { (calIdx, shortLabel) ->
                                    val isSelected = curWeeklyDay == calIdx
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(CircleShape)
                                            .background(if (isSelected) WaterBlue else SurfaceCard)
                                            .clickable { curWeeklyDay = calIdx }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(shortLabel, color = if (isSelected) Color.Black else Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (curFrequency == "MONTHLY") {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Monthly Span Dates (e.g. from 1st to 15th of the month)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextField(
                                        value = curMonthlyStartDate,
                                        onValueChange = { curMonthlyStartDate = it },
                                        label = { Text("From Date (1-31)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = curMonthlyEndDate,
                                        onValueChange = { curMonthlyEndDate = it },
                                        label = { Text("To Date (1-31)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (curFrequency == "MONTHLY_ONCE") {
                        item {
                            TextField(
                                value = curMonthlyStartDate,
                                onValueChange = { curMonthlyStartDate = it },
                                label = { Text("Repeat monthly on Day of Month (1-31)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Text("HABIT ACTION", color = WaterBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceCard)
                                .clickable { showActionConfigDialog = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = "Habit Action",
                                    tint = WaterBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Action",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (curHabitActionData.type.isNotEmpty()) {
                                        val summary = when (curHabitActionData.type.uppercase()) {
                                            "CALL" -> "📞 Call ${curHabitActionData.contactName.ifEmpty { curHabitActionData.contactPhone }}"
                                            "SMS" -> "💬 SMS ${curHabitActionData.contactName.ifEmpty { curHabitActionData.contactPhone }}"
                                            "WHATSAPP" -> "🟢 WhatsApp ${curHabitActionData.contactName.ifEmpty { curHabitActionData.contactPhone }}"
                                            else -> curHabitActionData.type
                                        }
                                        Text(
                                            text = summary,
                                            color = WaterBlue,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            text = "Call, SMS, or WhatsApp reminder",
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
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (curHabitName.trim().isNotEmpty()) {
                            val targetInt = curTargetCount.toIntOrNull() ?: 1
                            val mStart = curMonthlyStartDate.toIntOrNull() ?: 1
                            val mEnd = curMonthlyEndDate.toIntOrNull() ?: 30
                            
                            val freshHabit = Habit(
                                id = if (isEditMode) (editingHabitTarget?.id ?: 0) else 0,
                                name = curHabitName.trim(),
                                listCategory = if (selectedListName.equals("all", ignoreCase = true)) {
                                    habitLists.firstOrNull { !it.equals("all", ignoreCase = true) } ?: "Health & Vigor"
                                } else {
                                    selectedListName
                                },
                                timeOfDay = curTimeOfDay,
                                targetCount = targetInt,
                                frequency = curFrequency,
                                weeklyDay = curWeeklyDay,
                                monthlyStartDate = mStart,
                                monthlyEndDate = mEnd,
                                streakCount = if (isEditMode) (editingHabitTarget?.streakCount ?: 0) else 0,
                                lastCompletedTimestamp = if (isEditMode) (editingHabitTarget?.lastCompletedTimestamp) else null,
                                scheduledTime = curScheduledTime,
                                isReminderEnabled = curIsReminderEnabled,
                                actionType = curHabitActionData.type,
                                actionContactName = curHabitActionData.contactName,
                                actionContactPhone = curHabitActionData.contactPhone,
                                actionMessage = curHabitActionData.message
                            )

                            if (isEditMode) {
                                viewModel.updateHabit(freshHabit)
                            } else {
                                viewModel.createHabit(
                                    name = freshHabit.name,
                                    listCategory = freshHabit.listCategory,
                                    timeOfDay = freshHabit.timeOfDay,
                                    targetCount = freshHabit.targetCount,
                                    frequency = freshHabit.frequency,
                                    weeklyDay = freshHabit.weeklyDay,
                                    monthlyStartDate = freshHabit.monthlyStartDate,
                                    monthlyEndDate = freshHabit.monthlyEndDate,
                                    scheduledTime = freshHabit.scheduledTime,
                                    isReminderEnabled = freshHabit.isReminderEnabled,
                                    actionType = freshHabit.actionType,
                                    actionContactName = freshHabit.actionContactName,
                                    actionContactPhone = freshHabit.actionContactPhone,
                                    actionMessage = freshHabit.actionMessage
                                )
                            }
                        }
                        showCreateEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Add or Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateEditDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    if (showActionConfigDialog) {
        TaskActionConfigDialog(
            initialAction = curHabitActionData,
            contacts = contactsList,
            onSave = { updatedAction ->
                curHabitActionData = updatedAction
                showActionConfigDialog = false
            },
            onDismiss = {
                showActionConfigDialog = false
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
                            isEditMode = true
                            editingHabitTarget = targetHabit
                            curHabitName = targetHabit.name
                            curTimeOfDay = targetHabit.timeOfDay
                            curTargetCount = targetHabit.targetCount.toString()
                            curFrequency = targetHabit.frequency
                            curWeeklyDay = targetHabit.weeklyDay
                            curMonthlyStartDate = targetHabit.monthlyStartDate.toString()
                            curMonthlyEndDate = targetHabit.monthlyEndDate.toString()
                            curScheduledTime = targetHabit.scheduledTime
                            curIsReminderEnabled = targetHabit.isReminderEnabled
                            curHabitActionData = TaskActionHelper.parseActionData(targetHabit)
                            showCreateEditDialog = true
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


