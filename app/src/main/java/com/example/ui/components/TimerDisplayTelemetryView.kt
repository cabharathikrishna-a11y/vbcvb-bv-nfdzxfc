package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.WaterBlue
import com.example.util.FocusTimerManager
import com.example.util.LiveTimerDisplayRelay
import com.example.util.TimerDisplayTelemetryEntry
import com.example.util.TimerDisplayTelemetryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Timer Display Telemetry Subpage & Viewer.
 *
 * Requirements:
 * - Date-wise sorted and grouped records view (latest dates first).
 * - Strictly past 77 days local records stored on device only (no backup needed).
 * - Minute-by-minute independent background/foreground recorder of displayed values and statuses.
 * - Single-tap copy for all 77-day logs or date-specific records.
 */
@Composable
fun TimerDisplayTelemetryPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by TimerDisplayTelemetryManager.telemetryLogs.collectAsState()
    val isMonitoringEnabled by TimerDisplayTelemetryManager.isMonitoringEnabled.collectAsState()
    val liveDisplaySnapshot by LiveTimerDisplayRelay.liveDisplaySnapshot.collectAsState()

    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    var selectedDateFilter by remember { mutableStateOf("ALL") }
    var selectedStatusFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirmation by remember { mutableStateOf(false) }

    // Map to track collapsed date sections (by default all are expanded)
    val collapsedDateMap = remember { mutableStateMapOf<String, Boolean>() }

    // Resolve current live display values
    val rawTimerSecs = FocusTimerManager.timerSecondsLeft.collectAsState().value
    val fallbackDisplayTime = String.format(Locale.US, "%02d:%02d", rawTimerSecs / 60, rawTimerSecs % 60)
    val currentDisplayTime = liveDisplaySnapshot?.formattedTimeText ?: fallbackDisplayTime
    val isTimerOn = FocusTimerManager.isTimerRunning.collectAsState().value
    val isStopwatchOn = FocusTimerManager.isStopwatchActive.collectAsState().value
    val isPausedState = FocusTimerManager.isPaused.collectAsState().value
    val isFocusPhase = FocusTimerManager.isFocusPhase.collectAsState().value
    val isMinusActive = FocusTimerManager.isMinusTimerActive.collectAsState().value
    val isTabFocusTimer = FocusTimerManager.isTabFocusTimerSelected.collectAsState().value

    val liveStatus = when {
        isMinusActive -> "OVERTIME"
        !isFocusPhase -> if (isTimerOn || isStopwatchOn) "BREAK" else "PAUSED"
        isTimerOn || isStopwatchOn -> "FOCUSING"
        isPausedState -> "PAUSED"
        else -> "IDLE"
    }

    val liveMode = when {
        isMinusActive -> "Minus Timer"
        !isFocusPhase -> "Break"
        isTabFocusTimer -> "Pomodoro"
        else -> "Stopwatch"
    }

    // Extract unique dates present in logs (sorted descending)
    val distinctDates = remember(logs) {
        logs.map { it.dateString }.distinct().sortedDescending()
    }

    // Filter logs based on date, status filter chips and search query
    val filteredLogs = remember(logs, selectedDateFilter, selectedStatusFilter, searchQuery) {
        logs.filter { entry ->
            val matchesDate = if (selectedDateFilter == "ALL") true else entry.dateString == selectedDateFilter

            val matchesStatus = when (selectedStatusFilter) {
                "FOCUSING" -> entry.status.equals("FOCUSING", ignoreCase = true)
                "IDLE" -> entry.status.equals("IDLE", ignoreCase = true)
                "PAUSED" -> entry.status.contains("PAUSED", ignoreCase = true)
                "BREAK" -> entry.status.equals("BREAK", ignoreCase = true)
                else -> true
            }

            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                entry.clockTimeString.contains(searchQuery, ignoreCase = true) ||
                        entry.dateString.contains(searchQuery, ignoreCase = true) ||
                        entry.displayedValue.contains(searchQuery, ignoreCase = true) ||
                        entry.mode.contains(searchQuery, ignoreCase = true) ||
                        entry.status.contains(searchQuery, ignoreCase = true) ||
                        entry.taskTitle.contains(searchQuery, ignoreCase = true) ||
                        entry.tag.contains(searchQuery, ignoreCase = true)
            }

            matchesDate && matchesStatus && matchesSearch
        }
    }

    // Group filtered logs date-wise (descending order by date)
    val dateGroupedRecords = remember(filteredLogs) {
        filteredLogs.groupBy { it.dateString }
            .toList()
            .sortedByDescending { it.first } // Newest date first
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear Local Telemetry Logs?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete all recorded minute-by-minute timer display logs from this device? This cannot be undone.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        TimerDisplayTelemetryManager.clearLogs(context)
                        showClearConfirmation = false
                        Toast.makeText(context, "All local telemetry logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Clear Device Data", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E22),
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // TOP APP BAR WITH PROMINENT COPY BUTTON
        Surface(
            color = Color(0xFF0C0C0E),
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(0.5.dp, Color(0xFF222226))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp).testTag("telemetry_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Settings",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Timer Telemetry Records",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Date-wise records • Past 77 days on-device",
                            color = Color.Gray,
                            fontSize = 10.5.sp
                        )
                    }
                }

                // PROMINENT COPY BUTTON AT TOP
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            TimerDisplayTelemetryManager.copyToClipboard(
                                context = context,
                                filterStatus = if (selectedStatusFilter == "ALL") null else selectedStatusFilter,
                                targetDate = if (selectedDateFilter == "ALL") null else selectedDateFilter
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WaterBlue,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("copy_telemetry_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = Color.Black,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedDateFilter != "ALL") "Copy Date" else "Copy All Logs",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { showClearConfirmation = true },
                        modifier = Modifier.size(36.dp).testTag("clear_telemetry_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear All Logs",
                            tint = Color(0xFFFF5252).copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp)
        ) {
            // RETENTION & LOCAL DEVICE POLICY BANNER
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, WaterBlue.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isMonitoringEnabled) Color(0xFF10B981) else Color(0xFFFF9800))
                                )
                                Text(
                                    text = if (isMonitoringEnabled) "77-DAY LOCAL RECORDER ACTIVE" else "RECORDER PAUSED",
                                    color = if (isMonitoringEnabled) Color(0xFF10B981) else Color(0xFFFF9800),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Auto-Record",
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                                Switch(
                                    checked = isMonitoringEnabled,
                                    onCheckedChange = { enabled ->
                                        TimerDisplayTelemetryManager.setMonitoringEnabled(context, enabled)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = WaterBlue,
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    ),
                                    modifier = Modifier.testTag("telemetry_monitor_switch")
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.8.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "CURRENT DISPLAYED VALUE",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentDisplayTime,
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                val statusBgColor = when (liveStatus) {
                                    "FOCUSING" -> Color(0xFF10B981)
                                    "PAUSED" -> Color(0xFFFFB300)
                                    "BREAK" -> Color(0xFF00E5FF)
                                    "OVERTIME" -> Color(0xFFFF5252)
                                    else -> Color(0xFF64748B)
                                }
                                Surface(
                                    color = statusBgColor.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, statusBgColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "STATUS: $liveStatus",
                                        color = statusBgColor,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "77 Days Rolling • On-Device Only",
                                        color = Color.LightGray,
                                        fontSize = 9.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // DATE SELECTOR & FILTER CHIPS ROW
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search text field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("telemetry_search_input"),
                        placeholder = {
                            Text(
                                "Search date (e.g. 2026-08-17), time, or display value...",
                                color = Color.Gray,
                                fontSize = 11.5.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WaterBlue,
                            unfocusedBorderColor = Color(0xFF222226),
                            focusedContainerColor = Color(0xFF0C0C0E),
                            unfocusedContainerColor = Color(0xFF0C0C0E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // DATE-WISE HORIZONTAL TABS SELECTOR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isAllSelected = selectedDateFilter == "ALL"
                        Surface(
                            onClick = { selectedDateFilter = "ALL" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isAllSelected) WaterBlue.copy(alpha = 0.22f) else Color(0xFF141416),
                            border = BorderStroke(1.dp, if (isAllSelected) WaterBlue else Color(0xFF222226))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = if (isAllSelected) WaterBlue else Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "All Dates (${distinctDates.size} Days)",
                                    color = if (isAllSelected) WaterBlue else Color.Gray,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        distinctDates.forEach { dateStr ->
                            val isSelected = selectedDateFilter == dateStr
                            val label = if (dateStr == todayDateStr) "Today ($dateStr)" else dateStr
                            val dayCount = logs.count { it.dateString == dateStr }

                            Surface(
                                onClick = { selectedDateFilter = dateStr },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) WaterBlue.copy(alpha = 0.22f) else Color(0xFF141416),
                                border = BorderStroke(1.dp, if (isSelected) WaterBlue else Color(0xFF222226))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = null,
                                        tint = if (isSelected) WaterBlue else Color.Gray,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "$label • $dayCount",
                                        color = if (isSelected) WaterBlue else Color.Gray,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // STATUS FILTER CHIPS ROW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val statusOptions = listOf(
                            "ALL" to "All Status",
                            "FOCUSING" to "Focusing",
                            "IDLE" to "Idle",
                            "PAUSED" to "Paused",
                            "BREAK" to "Break"
                        )

                        statusOptions.forEach { (key, label) ->
                            val isSelected = selectedStatusFilter == key
                            Surface(
                                onClick = { selectedStatusFilter = key },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) WaterBlue.copy(alpha = 0.18f) else Color(0xFF141416),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) WaterBlue else Color(0xFF222226)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) WaterBlue else Color.Gray,
                                        fontSize = 9.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // DATE-WISE GROUPED RECORDS
            if (dateGroupedRecords.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0E)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E1E22))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (logs.isEmpty()) "No Telemetry Logs Yet" else "No matching date records found",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (logs.isEmpty())
                                    "The monitor records displayed values every minute automatically on device for the past 77 days. Check back in a minute!"
                                else
                                    "Try clearing the date filter or search query.",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(dateGroupedRecords, key = { it.first }) { (dateString, entriesForDate) ->
                    val isCollapsed = collapsedDateMap[dateString] ?: false
                    val isToday = dateString == todayDateStr
                    val focusingCount = entriesForDate.count { it.status.equals("FOCUSING", ignoreCase = true) }
                    val breakCount = entriesForDate.count { it.status.equals("BREAK", ignoreCase = true) }
                    val idleCount = entriesForDate.count { it.status.equals("IDLE", ignoreCase = true) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0C)),
                        border = BorderStroke(1.dp, if (isToday) WaterBlue.copy(alpha = 0.5f) else Color(0xFF1E1E24)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // DATE HEADER ROW (Interactive Accordion Header)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        collapsedDateMap[dateString] = !isCollapsed
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (isToday) WaterBlue.copy(alpha = 0.2f) else Color(0xFF1A1A22)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = if (isToday) WaterBlue else Color.LightGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = if (isToday) "Today ($dateString)" else dateString,
                                                color = Color.White,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                color = Color(0xFF1A1A22),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "${entriesForDate.size} logs",
                                                    color = Color.Gray,
                                                    fontSize = 9.5.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = "Focus: $focusingCount min • Break: $breakCount min • Idle: $idleCount min",
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Dedicated single-date copy button
                                    IconButton(
                                        onClick = {
                                            TimerDisplayTelemetryManager.copyToClipboard(
                                                context = context,
                                                filterStatus = if (selectedStatusFilter == "ALL") null else selectedStatusFilter,
                                                targetDate = dateString
                                            )
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy this date's logs",
                                            tint = WaterBlue,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    Icon(
                                        imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // EXPANDABLE LIST OF MINUTE RECORDS FOR THIS DATE
                            AnimatedVisibility(
                                visible = !isCollapsed,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    HorizontalDivider(color = Color(0xFF1E1E24), thickness = 0.7.dp)

                                    entriesForDate.forEach { record ->
                                        TelemetryRecordItemCard(record = record)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Telemetry Record Card within a Date Group.
 */
@Composable
fun TelemetryRecordItemCard(
    record: TimerDisplayTelemetryEntry,
    modifier: Modifier = Modifier
) {
    val statusColor = when (record.status.uppercase()) {
        "FOCUSING" -> Color(0xFF10B981) // Emerald Green
        "PAUSED", "PAUSED (BREAK)" -> Color(0xFFFFB300) // Amber
        "BREAK" -> Color(0xFF00E5FF) // Cyan
        "OVERTIME" -> Color(0xFFFF5252) // Coral Red
        else -> Color(0xFF64748B) // Slate Gray for IDLE
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121216)),
        border = BorderStroke(0.5.dp, Color(0xFF222228)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.clockTimeString,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Center: Status Badge & Mode
            Column(
                modifier = Modifier.weight(1.4f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = statusColor.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.7.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = record.status,
                        color = statusColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = record.mode + if (record.taskTitle.isNotEmpty()) " • ${record.taskTitle}" else "",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            // Right: Displayed Value in bold digital typography
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Display",
                    color = Color.DarkGray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = record.displayedValue,
                    color = if (record.status == "FOCUSING") Color(0xFF38BDF8) else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
