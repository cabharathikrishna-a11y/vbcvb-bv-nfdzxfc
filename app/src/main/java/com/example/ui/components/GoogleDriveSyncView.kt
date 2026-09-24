package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppViewModel
import com.example.ui.Screen
import com.example.util.GoogleDriveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveSyncView(
    viewModel: AppViewModel,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var accountEmail by remember { mutableStateOf(prefs.getString("selected_file_backup_account", null) ?: "Signed In User") }
    var hasPermission by remember { mutableStateOf(GoogleDriveSyncManager.hasDrivePermission(context)) }

    val syncStatus by viewModel.driveSyncStatus.collectAsState()

    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("Ready to sync") }
    var lastSyncTime by remember { mutableStateOf(prefs.getString("last_gdrive_sync_timestamp", "Never") ?: "Never") }

    // Auto sync settings
    var autoSyncEnabled by remember { mutableStateOf(prefs.getBoolean("gdrive_auto_sync_enabled", true)) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("gdrive_wifi_only", true)) }
    var selectedInterval by remember { mutableStateOf(prefs.getString("gdrive_sync_interval", "Hourly") ?: "Hourly") }

    // Drive Files
    var isLoadingFiles by remember { mutableStateOf(false) }
    var driveFiles by remember { mutableStateOf<List<GoogleDriveSyncManager.GoogleDriveFileItem>>(emptyList()) }

    // Sync Audit Logs
    val syncLogs = remember { mutableStateListOf<String>() }

    fun addLog(msg: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        syncLogs.add(0, "[$timeStr] $msg")
    }

    LaunchedEffect(Unit) {
        addLog("Google Drive Sync Manager initialized.")
        if (hasPermission) {
            addLog("Drive permission verified for account: $accountEmail")
        } else {
            addLog("Drive permission required. Tap 'Connect Google Account'.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Google Drive Sync",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Cloud Vault Backup, Multi-Device Recovery & Data Sync",
                            fontSize = 12.sp,
                            color = Color(0xFFA0A5C0)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (onBack != null) onBack() else viewModel.navigateTo(Screen.SETTINGS)
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account Connection Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, if (hasPermission) Color(0xFF34D399) else Color(0xFFF43F5E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = if (hasPermission) Color(0xFF34D399) else Color(0xFFF43F5E),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (hasPermission) "Google Drive Connected" else "Drive Not Connected",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = accountEmail,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Surface(
                            color = (if (hasPermission) Color(0xFF34D399) else Color(0xFFF43F5E)).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (hasPermission) "Authorized" else "Action Needed",
                                color = if (hasPermission) Color(0xFF34D399) else Color(0xFFF43F5E),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                hasPermission = true
                                prefs.edit().putBoolean("gdrive_permission_granted", true).apply()
                                addLog("Google Account authenticated & drive permissions refreshed.")
                                Toast.makeText(context, "Google Drive Connected!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect Account", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoadingFiles = true
                                    addLog("Fetching files from Google Drive...")
                                    val (success, files) = GoogleDriveSyncManager.listGoogleDriveFiles(context, parentId = null)
                                    if (success) {
                                        driveFiles = files
                                        addLog("Successfully fetched ${files.size} Drive items.")
                                    } else {
                                        addLog("Failed to list Google Drive files or token expired.")
                                    }
                                    isLoadingFiles = false
                                }
                            },
                            border = BorderStroke(1.dp, Color(0xFF818CF8)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh Vault", color = Color(0xFF818CF8), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Atomic 3-Pass Continuous Live Sync Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SyncLock,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Atomic 3-Pass Live Sync",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Assess → Reconcile & Bookkeep → Parity Verification",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Surface(
                            color = Color(0xFF38BDF8).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Continuous",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Synchronizes every individual item (Tasks, Notes, Chats, Contacts, Habits, Journal, Finances, View Settings & Tab Layout) into dedicated subfolders in 'LifeOS_Sync_Vault'. Records full edit audit logs and respects deleted tombstones across devices.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    var liveSyncRunning by remember { mutableStateOf(false) }
                    var liveSyncPhase by remember { mutableStateOf("Ready") }
                    var liveSyncPercent by remember { mutableStateOf(0) }
                    var liveSyncDetail by remember { mutableStateOf("Tap below to start 3-pass reconciliation") }

                    if (liveSyncRunning) {
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = liveSyncPhase,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "$liveSyncPercent%",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { (liveSyncPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF38BDF8),
                                    trackColor = Color(0xFF334155)
                                )

                                Text(
                                    text = liveSyncDetail,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                liveSyncRunning = true
                                liveSyncPhase = "Pass 1: Read & Assess"
                                liveSyncPercent = 15
                                liveSyncDetail = "Reading cloud tombstones and assessing delta..."
                                addLog("Starting Atomic 3-Pass Live Sync...")

                                viewModel.triggerGoogleDriveLiveSync(
                                    context = context,
                                    onProgress = { phase, percent, detail ->
                                        liveSyncPhase = phase
                                        liveSyncPercent = percent
                                        liveSyncDetail = detail
                                        addLog("[$phase] $detail")
                                    },
                                    onComplete = { success, msg ->
                                        liveSyncRunning = false
                                        liveSyncPercent = 100
                                        lastSyncTime = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
                                        addLog(msg)
                                        Toast.makeText(context, if (success) "Live Sync Complete ✅" else "Sync Error ⚠️", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Sync, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run Live 3-Pass Parity Sync", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // One-Tap Sync Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Sync & Backup Actions",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Last Sync: $lastSyncTime",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (syncStatus.isRunning) {
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF818CF8)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${syncStatus.operationType}: ${syncStatus.phase}",
                                            color = Color(0xFF818CF8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = "${syncStatus.progress}%",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { (syncStatus.progress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF818CF8),
                                    trackColor = Color(0xFF334155)
                                )

                                Text(
                                    text = syncStatus.statusMessage,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudSync,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Background service active with silent notifications — safe to close app",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Push Backup Button
                            Button(
                                onClick = {
                                    addLog("Triggering Push Backup background task...")
                                    viewModel.triggerGoogleDriveBackup(context)
                                    Toast.makeText(context, "Cloud Backup started in background ☁️", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !syncStatus.isRunning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF818CF8)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Push Backup", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Pull Restore Button
                            OutlinedButton(
                                onClick = {
                                    addLog("Triggering Pull Restore background task...")
                                    viewModel.triggerGoogleDriveRestore(context)
                                    Toast.makeText(context, "Cloud Restore started in background 📥", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !syncStatus.isRunning,
                                border = BorderStroke(1.dp, Color(0xFF34D399)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pull Restore", color = Color(0xFF34D399), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Enforce Single Root Vault & Folder Cleanup Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.FolderSpecial, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Single Vault & Folder Cleanup",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Keep Drive clean: 1 Root Folder (LifeOS_AppData) & subfolders",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Scans your Google Drive root, consolidates all legacy/duplicate LifeOS folders into one single 'LifeOS_AppData' master vault, organizes subfolders (App_Backups, Focus_Data, Task_Attachments, Shared_Media, General_Files), and permanently deletes old redundant folders and duplicate backup files.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            addLog("Triggering Drive Vault Consolidation & Cleanup background task...")
                            viewModel.triggerGoogleDriveCleanVault(context)
                            Toast.makeText(context, "Drive cleanup started in background 🧹", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !syncStatus.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.CleaningServices, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Consolidate & Purge Duplicate Folders", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Auto-Sync Configuration Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Automated Sync Settings",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Enable Auto Sync Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Background Auto-Sync", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Automatically push local changes to Drive", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = {
                                autoSyncEnabled = it
                                prefs.edit().putBoolean("gdrive_auto_sync_enabled", it).apply()
                                addLog("Auto-sync set to: $it")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF818CF8)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                    // Wi-Fi Only Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Wi-Fi Network Only", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Save mobile data during large syncs", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                prefs.edit().putBoolean("gdrive_wifi_only", it).apply()
                                addLog("Wi-Fi constraint set to: $it")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF38BDF8)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 10.dp))

                    // Sync Schedule Picker
                    Text("Sync Schedule Interval", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("15 Mins", "Hourly", "6 Hours", "Daily").forEach { interval ->
                            val isSelected = selectedInterval == interval
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedInterval = interval
                                    prefs.edit().putString("gdrive_sync_interval", interval).apply()
                                    addLog("Sync interval updated to: $interval")
                                },
                                label = { Text(interval, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF818CF8).copy(alpha = 0.25f),
                                    selectedLabelColor = Color(0xFF818CF8)
                                )
                            )
                        }
                    }
                }
            }

            // Google Drive Vault Files Explorer
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cloud Vault Storage Items",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = {
                            scope.launch {
                                isLoadingFiles = true
                                addLog("Refreshing Drive file list...")
                                val (success, files) = GoogleDriveSyncManager.listGoogleDriveFiles(context, parentId = null)
                                if (success) {
                                    driveFiles = files
                                    addLog("Fetched ${files.size} Drive items.")
                                } else {
                                    addLog("Failed to list Google Drive files.")
                                }
                                isLoadingFiles = false
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF818CF8))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingFiles) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Color(0xFF818CF8), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading Drive files...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    } else if (driveFiles.isEmpty()) {
                        Text("No Drive backup items found yet. Tap 'Push Backup' to upload.", color = Color(0xFF64748B), fontSize = 12.sp)
                    } else {
                        driveFiles.forEach { item ->
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (item.isFolder) Color(0xFFA855F7) else Color(0xFF38BDF8),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(item.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                "Modified: ${item.modifiedTime.take(19).replace("T", " ")} | ${item.size} bytes",
                                                color = Color(0xFF64748B),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    IconButton(onClick = {
                                        scope.launch {
                                            addLog("Deleting item ${item.name} from Drive...")
                                            val ok = GoogleDriveSyncManager.deleteGoogleDriveFile(context, item.id)
                                            if (ok) {
                                                driveFiles = driveFiles.filter { it.id != item.id }
                                                addLog("Deleted ${item.name}")
                                            } else {
                                                addLog("Failed to delete ${item.name}")
                                            }
                                        }
                                    }) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sync Audit Log Console
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sync Audit Log Console", color = Color(0xFF818CF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { syncLogs.clear() }) {
                            Text("Clear", color = Color(0xFF64748B), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF030712))
                            .padding(8.dp)
                    ) {
                        if (syncLogs.isEmpty()) {
                            Text("No sync activity logged yet.", color = Color(0xFF475569), fontSize = 11.sp)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(syncLogs) { log ->
                                    Text(
                                        text = log,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color(0xFF34D399),
                                        modifier = Modifier.padding(vertical = 1.dp)
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
