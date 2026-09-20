package com.example.ui.components

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.util.NetworkTrafficManager
import com.example.util.NetworkTrafficManager.DailyUsageEntry
import com.example.util.NetworkTrafficManager.PeriodUsage
import com.example.util.NetworkTrafficManager.TrafficCategory
import com.example.util.NetworkTrafficManager.TrafficCategoryStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Network Usage and Data Consumption Dialog
 *
 * Displays detailed data consumption metrics:
 * 1. Today's usage
 * 2. Past 7 days usage
 * 3. Past 30 days usage
 * 4. All over (Total since app installation)
 * Along with real-time live speed meters, daily trend breakdown, and category consumption.
 */
@Composable
fun NetworkUsageDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Real-time usage report state
    val reportState by NetworkTrafficManager.rememberNetworkUsageReport(context)
    val isOnline by NetworkTrafficManager.rememberIsInternetOn()

    var isRefreshing by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isRefreshing) 360f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "refresh_rotation"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 640.dp)
                .fillMaxHeight(0.92f)
                .testTag("network_usage_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = DeepSlate,
            border = BorderStroke(1.dp, Color(0xFF282832)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                Brush.linearGradient(listOf(WaterBlue.copy(alpha = 0.25f), WaterBlueAccent.copy(alpha = 0.15f))),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataUsage,
                            contentDescription = "Network Usage",
                            tint = WaterBlue,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Network Usage",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Data Consumption & Traffic Monitor",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    // Refresh Button
                    IconButton(
                        onClick = {
                            if (!isRefreshing) {
                                isRefreshing = true
                                NetworkTrafficManager.flushTrafficStats(context)
                                NetworkTrafficManager.getUsageReport(context)
                                coroutineScope.launch {
                                    delay(650)
                                    isRefreshing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("refresh_network_usage_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Metrics",
                            tint = WaterBlue,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotationAngle)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("close_network_usage_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF22222A), thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Live Connection Status Banner
                    LiveConnectionStatusBanner(
                        isOnline = isOnline,
                        report = reportState
                    )

                    // Section Title: Primary Consumption Periods
                    Text(
                        text = "DATA CONSUMPTION OVERVIEW",
                        color = WaterBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )

                    // 1. TODAY'S USAGE
                    DataPeriodCard(
                        title = "Today's Usage",
                        tag = "TODAY",
                        badgeColor = Color(0xFF00E5FF),
                        periodUsage = reportState.today,
                        subtitle = "Traffic recorded today",
                        testTag = "period_today_card"
                    )

                    // 2. PAST 7 DAYS USAGE
                    DataPeriodCard(
                        title = "Past 7 Days",
                        tag = "7 DAYS",
                        badgeColor = Color(0xFF00E676),
                        periodUsage = reportState.past7Days,
                        subtitle = "Cumulative rolling 7-day volume",
                        testTag = "period_7days_card"
                    )

                    // 3. PAST 30 DAYS USAGE
                    DataPeriodCard(
                        title = "Past 30 Days",
                        tag = "30 DAYS",
                        badgeColor = Color(0xFFFFB300),
                        periodUsage = reportState.past30Days,
                        subtitle = "Cumulative rolling 30-day volume",
                        testTag = "period_30days_card"
                    )

                    // 4. ALL OVER (TOTAL OF APP INSTALLATION)
                    DataPeriodCard(
                        title = "All Over (Total Installation)",
                        tag = "ALL TIME",
                        badgeColor = Color(0xFFB388FF),
                        periodUsage = reportState.allTime,
                        subtitle = "Total since app installation (${reportState.formattedInstallDate})",
                        testTag = "period_alltime_card",
                        highlight = true
                    )

                    // 7-Day History Trend Breakdown
                    if (reportState.dailyBreakdown7Days.isNotEmpty()) {
                        DailyBreakdownSection(entries = reportState.dailyBreakdown7Days)
                    }

                    // Category Traffic Breakdown
                    if (reportState.categoryStats.isNotEmpty()) {
                        CategoryBreakdownSection(categoryStats = reportState.categoryStats)
                    }

                    // Network Guidance & Policy Advice
                    val advice = remember(reportState) {
                        NetworkTrafficManager.getNetworkAdvice(context)
                    }
                    NetworkAdviceCard(advice = advice)

                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Done Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("done_network_usage_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Done",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Live Connection Status Banner displaying online status, connection type, and real-time speeds.
 */
@Composable
private fun LiveConnectionStatusBanner(
    isOnline: Boolean,
    report: NetworkTrafficManager.NetworkUsageReport
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF262630))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Online/Offline Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) SuccessGreen else AlertRed)
                    )
                    Text(
                        text = if (isOnline) "ONLINE" else "OFFLINE",
                        color = if (isOnline) SuccessGreen else AlertRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Connection Type Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceCard,
                    border = BorderStroke(1.dp, Color(0xFF333340))
                ) {
                    Text(
                        text = "${report.connectionType.name} ${if (report.isMetered) "• METERED" else "• UNMETERED"}",
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Real-time Upload / Download Speeds
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SpeedMetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Download Speed",
                    speedKbps = report.currentDownloadSpeedKbps,
                    icon = Icons.Default.ArrowDownward,
                    color = Color(0xFF00E5FF)
                )
                SpeedMetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Upload Speed",
                    speedKbps = report.currentUploadSpeedKbps,
                    icon = Icons.Default.ArrowUpward,
                    color = Color(0xFF00E676)
                )
            }
        }
    }
}

@Composable
private fun SpeedMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    speedKbps: Double,
    icon: ImageVector,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, Color(0xFF2B2B36))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, color = TextSecondary, fontSize = 9.sp)
                val displaySpeed = if (speedKbps >= 1024.0) {
                    String.format("%.2f MB/s", speedKbps / 1024.0)
                } else {
                    String.format("%.1f KB/s", speedKbps)
                }
                Text(displaySpeed, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Visual card representing a distinct data consumption period:
 * - Today's usage
 * - Past 7 days
 * - Past 30 days
 * - All over (Total of app installation)
 */
@Composable
private fun DataPeriodCard(
    title: String,
    tag: String,
    badgeColor: Color,
    periodUsage: PeriodUsage,
    subtitle: String,
    testTag: String,
    highlight: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = if (highlight) Color(0xFF181820) else Charcoal),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (highlight) badgeColor.copy(alpha = 0.5f) else Color(0xFF262632))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = tag,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Big Headline Total
                Text(
                    text = periodUsage.formattedTotal,
                    color = if (highlight) badgeColor else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-metrics: Downloaded (Rx) & Uploaded (Tx)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Inbound (Download)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Inbound",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Downloaded (Rx)", color = TextSecondary, fontSize = 9.sp)
                        Text(periodUsage.formattedRx, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFF333340)))

                // Outbound (Upload)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "Outbound",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Uploaded (Tx)", color = TextSecondary, fontSize = 9.sp)
                        Text(periodUsage.formattedTx, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 7-Day Daily Breakdown displaying day-by-day consumption and proportions.
 */
@Composable
private fun DailyBreakdownSection(
    entries: List<DailyUsageEntry>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF262632))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "7-Day Daily Trend",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Day-by-day",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val maxBytes = remember(entries) {
                entries.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1024L) ?: 1024L
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    val fraction = (entry.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.04f, 1f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.dayLabel,
                            color = if (entry.dayLabel == "Today") WaterBlue else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (entry.dayLabel == "Today") FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(76.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceCard)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (entry.dayLabel == "Today") WaterBlue else Color(0xFF4A6572)
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = entry.formattedTotal,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(64.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Category Breakdown showing traffic volume across application features.
 */
@Composable
private fun CategoryBreakdownSection(
    categoryStats: List<TrafficCategoryStats>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF262632))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Traffic by Category",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryStats.forEach { cat ->
                    val total = cat.totalBytesSent + cat.totalBytesReceived
                    val (icon, color) = when (cat.category) {
                        TrafficCategory.CLOUD_BACKUP -> Icons.Default.CloudSync to Color(0xFF29B6F6)
                        TrafficCategory.DATABASE_SYNC -> Icons.Default.Sync to Color(0xFFAB47BC)
                        TrafficCategory.AI_MODELS -> Icons.Default.Psychology to Color(0xFF00E5FF)
                        TrafficCategory.MEDIA_STREAMING -> Icons.Default.PlayCircle to Color(0xFFFF7043)
                        TrafficCategory.APP_UPDATE -> Icons.Default.SystemUpdate to Color(0xFF66BB6A)
                        TrafficCategory.PUSH_NOTIFICATION -> Icons.Default.Notifications to Color(0xFFFFCA28)
                        else -> Icons.Default.Language to WaterBlue
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cat.category.name.replace("_", " "),
                                color = TextPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = NetworkTrafficManager.formatBytes(total),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Network Guidance & Recommendation Card.
 */
@Composable
private fun NetworkAdviceCard(
    advice: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, Color(0xFF2C2C38))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = WaterBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = advice,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
