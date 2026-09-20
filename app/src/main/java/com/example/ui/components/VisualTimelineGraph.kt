package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.AnalyticsVaultEngine
import com.example.api.AnalyticsVaultEngine.SegmentType
import com.example.api.AnalyticsVaultEngine.TimelineSegment
import com.example.data.LocalHistoryVault
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun VisualTimelineGraph(
    dateString: String,
    dayRecords: List<LocalHistoryVault>,
    onDateChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoomToActive by remember { mutableStateOf(false) }
    var selectedSegment by remember { mutableStateOf<TimelineSegment?>(null) }

    // Reconstruct the timeline segments for the current day
    val segments = remember(dateString, dayRecords, zoomToActive) {
        AnalyticsVaultEngine.reconstructDayBlocks(dateString, dayRecords, zoomToActive)
    }

    // Helper to format date header nicely: "Friday, July 17, 2026"
    val formattedDateHeader = remember(dateString) {
        try {
            val sdfInput = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val sdfOutput = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
            val parsed = sdfInput.parse(dateString)
            if (parsed != null) sdfOutput.format(parsed) else dateString
        } catch (e: Exception) {
            dateString
        }
    }

    // Day navigation helpers
    val navigateDay: (Int) -> Unit = { offset ->
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val parsed = sdf.parse(dateString)
            if (parsed != null) {
                val cal = Calendar.getInstance().apply {
                    time = parsed
                    add(Calendar.DAY_OF_YEAR, offset)
                }
                onDateChange(sdf.format(cal.time))
                selectedSegment = null // Clear tooltips when date moves
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with Day Navigators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navigateDay(-1) }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Previous Day",
                        tint = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Visual Timeline Tracker",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF44D7B6),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = formattedDateHeader,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                IconButton(onClick = { navigateDay(1) }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Next Day",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display Zoom controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daily Flow Chronology",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (zoomToActive) "Focus Hours Only" else "Full 24-Hours",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = zoomToActive,
                        onCheckedChange = { 
                            zoomToActive = it 
                            selectedSegment = null
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00C853),
                            checkedTrackColor = Color(0xFF00C853).copy(alpha = 0.4f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.DarkGray
                        ),
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // The Visual Timeline Bar
            if (segments.isEmpty() || segments.all { it.type == SegmentType.IDLE }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.03f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "No studies",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No study flow recorded on this day.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                ) {
                    val containerWidth = maxWidth

                    segments.forEach { segment ->
                        val startPercent = segment.startOffsetPercent
                        val endPercent = segment.endOffsetPercent
                        val widthPercent = (endPercent - startPercent).coerceIn(0.005f, 1f)

                        // Choose color based on type
                        val color = when (segment.type) {
                            SegmentType.FOCUS -> Color(0xFF00C853) // Emerald Green
                            SegmentType.BREAK -> Color(0xFFFFB300) // Amber Yellow
                            SegmentType.IDLE -> Color(0xFF2C2C2E)  // Neutral Slate
                        }

                        // Only interact with active focus/break blocks
                        val isInteractive = segment.type != SegmentType.IDLE

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(widthPercent)
                                .offset(x = containerWidth * startPercent)
                                .background(color)
                                .then(
                                    if (isInteractive) {
                                        Modifier.clickable {
                                            selectedSegment = if (selectedSegment == segment) null else segment
                                        }
                                    } else Modifier
                                )
                        )
                    }
                }

                // Add nice time labels underneath the bar if 24-hours is active
                if (!zoomToActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("12 AM", fontSize = 9.sp, color = Color.Gray)
                        Text("6 AM", fontSize = 9.sp, color = Color.Gray)
                        Text("12 PM", fontSize = 9.sp, color = Color.Gray)
                        Text("6 PM", fontSize = 9.sp, color = Color.Gray)
                        Text("11:59 PM", fontSize = 9.sp, color = Color.Gray)
                    }
                } else {
                    // Zoom active labels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val sdf = SimpleDateFormat("h:mm a", Locale.US)
                        val startLabel = sdf.format(Date(segments.first().startTimeMs))
                        val endLabel = sdf.format(Date(segments.last().endTimeMs))
                        Text(startLabel, fontSize = 9.sp, color = Color.Gray)
                        Text("Study Window", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        Text(endLabel, fontSize = 9.sp, color = Color.Gray)
                    }
                }

                // Interactive Dynamic Micro-Popup/Tooltip Area
                AnimatedVisibility(
                    visible = selectedSegment != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    selectedSegment?.let { segment ->
                        val sdfTime = SimpleDateFormat("h:mm a", Locale.US)
                        val timeString = "${sdfTime.format(Date(segment.startTimeMs))} - ${sdfTime.format(Date(segment.endTimeMs))}"
                        val blockDuration = segment.endTimeMs - segment.startTimeMs
                        val durationString = AnalyticsVaultEngine.formatMsToReadable(blockDuration)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (segment.type == SegmentType.FOCUS) {
                                    Color(0xFF00C853).copy(alpha = 0.15f)
                                } else {
                                    Color(0xFFFFB300).copy(alpha = 0.15f)
                                }
                            ),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (segment.type == SegmentType.FOCUS) Color(0xFF00C853) else Color(0xFFFFB300)
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (segment.type == SegmentType.FOCUS) "FOCUS SESSION" else "BREAK / PAUSE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (segment.type == SegmentType.FOCUS) Color(0xFF00C853) else Color(0xFFFFB300),
                                        letterSpacing = 0.5.sp
                                    )

                                    Text(
                                        text = durationString,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = segment.taskTitle ?: "General Study Activity",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )

                                if (!segment.tag.isNullOrBlank()) {
                                    Text(
                                        text = "Tag: #${segment.tag}",
                                        fontSize = 11.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (segment.type == SegmentType.FOCUS) Color(0xFF00C853) else Color(0xFFFFB300))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = timeString,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Legend markers bar
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF00C853)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Focusing", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFB300)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Breaks", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C2E)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Idle Gaps", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }

            // Subject-Wise Focus Details section
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF27272A), thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))

            val totalDayFocusMs = remember(dayRecords) {
                dayRecords.sumOf { it.total_focus_ms }
            }

            val subjectStatsList = remember(dayRecords) {
                val grouped = dayRecords.groupBy { it.subject.ifBlank { "General Study" } }
                val totalMs = totalDayFocusMs.coerceAtLeast(1L)
                grouped.map { (subject, records) ->
                    val subjTotalMs = records.sumOf { it.total_focus_ms }
                    val pct = (subjTotalMs.toFloat() / totalMs.toFloat()) * 100f
                    val colorInt = com.example.widget.WidgetManager.getSubjectColor(subject)
                    object {
                        val subjectName = subject
                        val totalMs = subjTotalMs
                        val count = records.size
                        val color = Color(colorInt)
                        val percentage = pct
                    }
                }.sortedByDescending { it.totalMs }
            }

            val formattedTotalTime = remember(totalDayFocusMs) {
                val totalSecs = (totalDayFocusMs / 1000L).toInt()
                val hrs = totalSecs / 3600
                val mins = (totalSecs % 3600) / 60
                val secs = totalSecs % 60
                when {
                    hrs > 0 -> "${hrs}h ${mins}m"
                    mins > 0 -> "${mins}m ${secs}s"
                    else -> "${secs}s"
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Subject-Wise Details",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        if (subjectStatsList.isEmpty()) "0 Subjects Tracked"
                        else "${subjectStatsList.size} ${if (subjectStatsList.size == 1) "Subject" else "Subjects"} • ${dayRecords.size} ${if (dayRecords.size == 1) "Session" else "Sessions"}",
                        fontSize = 11.sp,
                        color = Color(0xFFA1A1AA)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B)
                ) {
                    Text(
                        text = "Total: $formattedTotalTime",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (subjectStatsList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                // Proportional Subject Distribution Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF27272A))
                ) {
                    subjectStatsList.forEach { stat ->
                        val weight = (stat.percentage / 100f).coerceIn(0.001f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight)
                                .background(stat.color)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subjectStatsList.forEach { stat ->
                        val durationText = run {
                            val secs = (stat.totalMs / 1000L).toInt()
                            val h = secs / 3600
                            val m = (secs % 3600) / 60
                            when {
                                h > 0 && m > 0 -> "${h}h ${m}m"
                                h > 0 -> "${h}h"
                                m > 0 -> "${m}m"
                                else -> "${secs}s"
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF141416), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(stat.color)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stat.subjectName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${stat.count} ${if (stat.count == 1) "sess" else "sessions"} (${String.format(Locale.US, "%.0f%%", stat.percentage)})",
                                fontSize = 11.sp,
                                color = Color(0xFFA1A1AA),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Text(
                                text = durationText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141416), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No focused sessions recorded for this day yet.",
                        fontSize = 12.sp,
                        color = Color(0xFF71717A)
                    )
                }
            }
        }
    }
}
