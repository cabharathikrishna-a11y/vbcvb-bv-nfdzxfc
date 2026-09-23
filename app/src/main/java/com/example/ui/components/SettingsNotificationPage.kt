package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.AppViewModel
import com.example.util.NotificationSettingsManager

@Composable
fun SettingsNotificationPage(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    SettingsPageScope {
        val context = LocalContext.current

        // Check Android 13+ runtime POST_NOTIFICATIONS permission
        var hasSystemPermission by remember {
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasSystemPermission = isGranted
            if (isGranted) {
                Toast.makeText(context, "Notification permission granted! 🔔", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "System permission denied. Notifications may not appear.", Toast.LENGTH_LONG).show()
            }
        }

        // Master switch state
        var masterEnabled by remember {
            mutableStateOf(NotificationSettingsManager.isMasterEnabled(context))
        }

        // Map of individual keys to state
        val stateMap = remember {
            mutableStateMapOf<String, Boolean>().apply {
                NotificationSettingsManager.ALL_CONFIGS.forEach { config ->
                    this[config.key] = NotificationSettingsManager.isEnabled(context, config.key, config.defaultEnabled)
                }
            }
        }

        // Category filter
        val categories = listOf("All", "Friends & Social", "Timers & Productivity", "Tasks & Planning", "Wellness & Life OS")
        var selectedCategory by remember { mutableStateOf("All") }

        val filteredConfigs = remember(selectedCategory) {
            if (selectedCategory == "All") {
                NotificationSettingsManager.ALL_CONFIGS
            } else {
                NotificationSettingsManager.ALL_CONFIGS.filter { it.category == selectedCategory }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("notification_settings_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NOTIFICATION SETTINGS",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Configure all automatic triggers, friend alerts & alarms",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1A1A1E), thickness = 1.dp)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // System Permission Warning Banner (if missing on Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasSystemPermission) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Permission Required",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "System Permission Required",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Android notification permission is disabled. Tap to grant so alerts can be posted.",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Master Toggle Banner
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (masterEnabled) Color(0xFF141E28) else Color(0xFF1E1E22)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (masterEnabled) Color(0xFF00E5FF).copy(alpha = 0.6f) else Color(0xFF33333A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(if (masterEnabled) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF2C2C34)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (masterEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                        contentDescription = "Master Notifications Switch",
                                        tint = if (masterEnabled) Color(0xFF00E5FF) else Color.Gray,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Allow App Notifications",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (masterEnabled) "All enabled automatic triggers will be delivered" else "All app notifications are currently muted",
                                        color = if (masterEnabled) Color(0xFF80D8FF) else Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = masterEnabled,
                                    onCheckedChange = { checked ->
                                        masterEnabled = checked
                                        NotificationSettingsManager.setMasterEnabled(context, checked)
                                        Toast.makeText(
                                            context,
                                            if (checked) "Notifications enabled" else "All app notifications muted",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.testTag("master_notification_switch"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF00E5FF),
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color(0xFF3A3A44)
                                    )
                                )
                            }
                        }
                    }
                }

                // Category Filter Chips
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "CATEGORIES",
                            color = Color(0xFF888899),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories) { cat ->
                                val isSelected = (cat == selectedCategory)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedCategory = cat },
                                    label = {
                                        Text(
                                            text = cat,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF2196F3).copy(alpha = 0.25f),
                                        selectedLabelColor = Color(0xFF64B5F6),
                                        containerColor = Color(0xFF141418),
                                        labelColor = Color.Gray
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = Color(0xFF2196F3),
                                        selectedBorderColor = Color(0xFF2196F3)
                                    )
                                )
                            }
                        }
                    }
                }

                // Individual Notification Trigger Cards
                itemsIndexed(filteredConfigs, key = { idx, config -> "${config.key}_$idx" }) { _, config ->
                    val isEnabled = stateMap[config.key] ?: config.defaultEnabled
                    val effectiveEnabled = masterEnabled && isEnabled

                    val icon = getCategoryIcon(config.key)
                    val iconColor = getCategoryColor(config.category)

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (effectiveEnabled) Color(0xFF16161C) else Color(0xFF111115)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (effectiveEnabled) Color(0xFF2A2A34) else Color(0xFF1E1E24)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (effectiveEnabled) iconColor.copy(alpha = 0.15f) else Color(0xFF222228)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = config.title,
                                        tint = if (effectiveEnabled) iconColor else Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = config.title,
                                        color = if (masterEnabled) Color.White else Color.Gray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = config.description,
                                        color = if (masterEnabled) Color(0xFFAAAAAA) else Color(0xFF555555),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Switch(
                                    checked = isEnabled,
                                    enabled = masterEnabled,
                                    onCheckedChange = { checked ->
                                        stateMap[config.key] = checked
                                        NotificationSettingsManager.setEnabled(context, config.key, checked)
                                        Toast.makeText(
                                            context,
                                            "${config.title}: ${if (checked) "ON" else "OFF"}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.testTag("switch_${config.key}"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = iconColor,
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color(0xFF33333E),
                                        disabledCheckedTrackColor = iconColor.copy(alpha = 0.3f),
                                        disabledUncheckedTrackColor = Color(0xFF222228)
                                    )
                                )
                            }

                            // Bottom actions inside card (Category pill & Preview test trigger)
                            Row(
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    color = Color(0xFF202028),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = config.category,
                                        color = iconColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        NotificationSettingsManager.sendTestNotification(context, config)
                                        Toast.makeText(context, "Sent test: ${config.title}", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = masterEnabled && hasSystemPermission,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("test_btn_${config.key}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Test Notification",
                                        tint = if (masterEnabled && hasSystemPermission) iconColor else Color.DarkGray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Test Alert",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (masterEnabled && hasSystemPermission) iconColor else Color.DarkGray
                                    )
                                }
                            }
                        }
                    }
                }

                // Batch Bulk Actions (Enable All / Disable All / App System Settings)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "QUICK ACTIONS",
                            color = Color(0xFF888899),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    NotificationSettingsManager.ALL_CONFIGS.forEach { config ->
                                        stateMap[config.key] = true
                                        NotificationSettingsManager.setEnabled(context, config.key, true)
                                    }
                                    Toast.makeText(context, "All notification triggers turned ON", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("enable_all_notifications_btn"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Enable All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    NotificationSettingsManager.ALL_CONFIGS.forEach { config ->
                                        stateMap[config.key] = false
                                        NotificationSettingsManager.setEnabled(context, config.key, false)
                                    }
                                    Toast.makeText(context, "All notification triggers turned OFF", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("disable_all_notifications_btn"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disable All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Open Android System Notification Settings
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("system_notification_settings_btn"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Android System Notification Channels", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

private fun getCategoryColor(category: String): Color {
    return when (category) {
        "Friends & Social" -> Color(0xFF00E5FF)
        "Timers & Productivity" -> Color(0xFFFF9800)
        "Tasks & Planning" -> Color(0xFF4CAF50)
        "Wellness & Life OS" -> Color(0xFF9C27B0)
        else -> Color(0xFF2196F3)
    }
}

private fun getCategoryIcon(key: String): ImageVector {
    return when (key) {
        NotificationSettingsManager.KEY_FRIEND_FOCUS -> Icons.Default.Person
        NotificationSettingsManager.KEY_PEER_NUDGE -> Icons.Default.NotificationsActive
        NotificationSettingsManager.KEY_CHAT_MESSAGES -> Icons.Default.Email
        NotificationSettingsManager.KEY_FCM_CLOUD -> Icons.Default.CloudSync
        NotificationSettingsManager.KEY_TIMER_END -> Icons.Default.Alarm
        NotificationSettingsManager.KEY_FOCUS_FOREGROUND -> Icons.Default.PlayArrow
        NotificationSettingsManager.KEY_TASK_REMINDERS -> Icons.Default.CheckCircle
        NotificationSettingsManager.KEY_ALL_DAY_TASKS -> Icons.Default.List
        NotificationSettingsManager.KEY_HABIT_REMINDERS -> Icons.Default.Refresh
        NotificationSettingsManager.KEY_COUNTDOWN_ALERTS -> Icons.Default.DateRange
        NotificationSettingsManager.KEY_STUDY_MOTIVATION -> Icons.Default.Star
        NotificationSettingsManager.KEY_WATER_REMINDERS -> Icons.Default.Opacity
        NotificationSettingsManager.KEY_BEDTIME_REMINDER -> Icons.Default.Bedtime
        NotificationSettingsManager.KEY_WAKEUP_ALARM -> Icons.Default.WbSunny
        NotificationSettingsManager.KEY_ON_THIS_DAY -> Icons.Default.History
        else -> Icons.Default.Notifications
    }
}
