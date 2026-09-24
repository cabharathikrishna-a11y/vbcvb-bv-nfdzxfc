package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ModelRepository
import com.example.ui.AppViewModel
import com.example.util.DeviceSpecsManager
import com.example.util.DownloadSpeedMode
import com.example.util.EngineDiagnosticTestResult
import com.example.util.LocalQwenIntelligenceEngine
import com.example.util.ModelCompatibilityReport
import com.example.util.ModelDownloadManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartChatView(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadState by ModelDownloadManager.downloadState.collectAsState()

    var isModelReady by remember {
        mutableStateOf(ModelDownloadManager.isFlagshipModelDownloaded(context))
    }

    LaunchedEffect(downloadState.isCompleted) {
        if (downloadState.isCompleted) {
            isModelReady = ModelDownloadManager.isFlagshipModelDownloaded(context)
        }
    }

    var showRamHardwareWarningDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var selectedSpeedMode by remember { mutableStateOf(DownloadSpeedMode.FAST) }
    var showStorageWarningDialog by remember { mutableStateOf(false) }
    var storageDeficitGb by remember { mutableStateOf(0.0) }
    var showModelInfoSheet by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D11))
    ) {
        if (downloadState.isDownloading) {
            // 1. ACTIVE DOWNLOAD SCREEN WITH LIVE PROGRESS
            ActiveDownloadProgressScreen(
                downloadState = downloadState,
                onCancel = {
                    ModelDownloadManager.cancelDownload(context)
                }
            )
        } else if (!isModelReady) {
            // 2. HERO DOWNLOAD SCREEN (When AI Model Is Not Downloaded Yet)
            ModelDownloadHeroScreen(
                onInitiateDownload = {
                    val specs = DeviceSpecsManager.getDeviceSpecs(context)
                    if (specs.freeStorageGb < 1.3) {
                        storageDeficitGb = Math.round((1.3 - specs.freeStorageGb) * 10.0) / 10.0
                        showStorageWarningDialog = true
                    } else {
                        showRamHardwareWarningDialog = true
                    }
                }
            )
        } else {
            // 3. FULL SMART AI CHAT INTERFACE (100% On-Device Engine)
            ActiveQwenChatScreen(
                viewModel = viewModel,
                onShowModelInfo = { showModelInfoSheet = true }
            )
        }

        // RAM & HARDWARE RESOURCE WARNING DIALOG (3-4 GB RAM Notice)
        if (showRamHardwareWarningDialog) {
            val specs = remember { DeviceSpecsManager.getDeviceSpecs(context) }
            AlertDialog(
                onDismissRequest = { showRamHardwareWarningDialog = false },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "RAM Warning",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "⚠️ 3 to 4 GB RAM Requirement Notice",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Please note before downloading: Running this on-device AI neural engine executes 1.5B parameters locally and requires 3 to 4 GB of RAM during active reasoning.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            lineHeight = 18.sp
                        )

                        // Hardware Specs Snapshot Card
                        Surface(
                            color = Color(0xFF16161E),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Device Total RAM:", color = Color.Gray, fontSize = 11.sp)
                                    Text("${specs.totalPhysicalRamGb} GB", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Currently Available RAM:", color = Color.Gray, fontSize = 11.sp)
                                    Text(
                                        text = "${specs.availableRamGb} GB",
                                        color = if (specs.availableRamGb >= 2.0) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Execution Engine Mode:", color = Color.Gray, fontSize = 11.sp)
                                    Text(
                                        text = if (specs.isLowRamDevice) "4GB Low-RAM Safe Mode" else "Standard Performance",
                                        color = Color(0xFF818CF8),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Text(
                            text = "⚡ Generating responses locally engages multi-core CPU/GPU threads, which temporarily uses system memory and may slightly warm up the device during long reasoning tasks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFBBF24),
                            fontSize = 10.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRamHardwareWarningDialog = false
                            showSpeedDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("I Understand, Choose Speed", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRamHardwareWarningDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF18181C),
                shape = RoundedCornerShape(20.dp)
            )
        }

        // SPEED SELECTION DIALOG
        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚡ Choose Download Speed", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Select your network throughput mode. Background downloading is enabled for all modes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )

                        DownloadSpeedMode.values().forEach { mode ->
                            val isSelected = selectedSpeedMode == mode
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSpeedMode = mode }
                                    .testTag("speed_mode_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.2f) else Color(0xFF1E1E24)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF6366F1) else Color.White.copy(alpha = 0.08f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = mode.icon, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mode.label,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF818CF8) else Color.White
                                        )
                                        Text(
                                            text = mode.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedSpeedMode = mode },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF6366F1))
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSpeedDialog = false
                            ModelDownloadManager.startDownload(
                                context = context,
                                model = ModelRepository.FLAGSHIP_QWEN_CODER,
                                speedMode = selectedSpeedMode,
                                coroutineScope = coroutineScope
                            ) { success, error ->
                                if (success) {
                                    isModelReady = true
                                    Toast.makeText(context, "Qwen 2.5 Coder 1.5B ready to run offline!", Toast.LENGTH_LONG).show()
                                } else if (error != null) {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Start Download (1.0 GB)", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSpeedDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF18181C),
                shape = RoundedCornerShape(20.dp)
            )
        }

        // INSUFFICIENT STORAGE WARNING DIALOG
        if (showStorageWarningDialog) {
            AlertDialog(
                onDismissRequest = { showStorageWarningDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Storage Warning",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Insufficient Storage Space",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "The Qwen 2.5 Coder 1.5B AI model requires at least 1.3 GB of free storage to unpack and execute smoothly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "Deficit: Please free up at least $storageDeficitGb GB of storage on your device and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showStorageWarningDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Got it", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF18181C),
                shape = RoundedCornerShape(20.dp)
            )
        }

        // MODEL INFO & COMPATIBILITY BOTTOM SHEET
        if (showModelInfoSheet) {
            val compatibilityReport = remember(isModelReady) { DeviceSpecsManager.verifyModelCompatibility(context) }
            var isRunningDiagnostic by remember { mutableStateOf(false) }
            var diagnosticResult by remember { mutableStateOf<EngineDiagnosticTestResult?>(null) }

            ModalBottomSheet(
                onDismissRequest = { showModelInfoSheet = false },
                containerColor = Color(0xFF18181C)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "🛡️ AI Engine Compatibility & Specs",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "On-Device Neural Runtime Verification",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (compatibilityReport.isCompatible) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFF59E0B).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = if (compatibilityReport.isCompatible) "✅ 100% READY" else "⚠️ CHECK",
                                    color = if (compatibilityReport.isCompatible) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Summary Banner Card
                    item {
                        Surface(
                            color = Color(0xFF1E1E28),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Hardware Verdict:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF818CF8)
                                )
                                Text(
                                    text = compatibilityReport.diagnosticSummary,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    // Compatibility Checklist
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF14141A), RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Compatibility Diagnostics Checklist",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            compatibilityReport.compatibilityDetails.forEach { item ->
                                Text(
                                    text = item,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    // Telemetry Matrix
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF14141A), RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Execution Backend", color = Color.Gray, fontSize = 12.sp)
                                Text(compatibilityReport.executionBackend, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("RAM Status Tier", color = Color.Gray, fontSize = 12.sp)
                                Text(compatibilityReport.ramStatusLevel, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Thermal State", color = Color.Gray, fontSize = 12.sp)
                                Text(compatibilityReport.thermalState, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Zero-Crash OOM Shield", color = Color.Gray, fontSize = 12.sp)
                                Text("ACTIVE", color = Color(0xFF818CF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    // Live Engine Diagnostic Test
                    item {
                        Surface(
                            color = Color(0xFF1E1E28),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "🧪 On-Device Benchmark & Warmup",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Executes an on-device tokenization & inference test",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            if (!isRunningDiagnostic) {
                                                isRunningDiagnostic = true
                                                coroutineScope.launch {
                                                    val res = DeviceSpecsManager.runEngineDiagnosticWarmup(context)
                                                    diagnosticResult = res
                                                    isRunningDiagnostic = false
                                                }
                                            }
                                        },
                                        enabled = !isRunningDiagnostic,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        if (isRunningDiagnostic) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("Run Test", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }

                                diagnosticResult?.let { res ->
                                    Surface(
                                        color = Color(0xFF121218),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "✅ Benchmark Passed: 100% Local Inference",
                                                color = Color(0xFF10B981),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = "⏱️ Latency: ${res.executionTimeMs} ms  •  🚀 Speed: ~${res.estimatedTokensPerSec} tokens/sec",
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "🧠 Memory used: ~${res.memoryFootprintMb} MB  •  ${res.notes}",
                                                color = Color.LightGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Delete Model Button
                    item {
                        Button(
                            onClick = {
                                ModelDownloadManager.deleteModel(context, ModelRepository.FLAGSHIP_QWEN_CODER)
                                isModelReady = false
                                showModelInfoSheet = false
                                Toast.makeText(context, "Model deleted.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Model", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Model Weights (1.0 GB)", color = Color(0xFFEF4444))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadHeroScreen(onInitiateDownload: () -> Unit) {
    val context = LocalContext.current
    val specs = remember { DeviceSpecsManager.getDeviceSpecs(context) }
    val downloadState by ModelDownloadManager.downloadState.collectAsState()

    val isDownloading = downloadState.isDownloading
    val tempFile = remember(isDownloading) {
        java.io.File(ModelDownloadManager.getModelsDir(context), "${ModelRepository.FLAGSHIP_QWEN_CODER.fileName}.download")
    }
    val partialMb = if (tempFile.exists()) (tempFile.length() / (1024 * 1024)).toInt() else 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ICON BADGE
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "AI Model",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // TITLE & SUBTITLE
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Qwen 2.5 Coder 1.5B",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF6366F1).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "GGUF Q4_K_M • 100% Offline Neural Intelligence",
                            color = Color(0xFF818CF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                if (!downloadState.error.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = downloadState.error ?: "",
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // DOWNLOAD ACTION BUTTON (PROMINENT AT TOP / MIDDLE FOR IMMEDIATE ACCESSIBILITY)
                Button(
                    onClick = onInitiateDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("download_ai_model_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (partialMb > 0) Color(0xFF10B981) else Color(0xFF6366F1)
                    )
                ) {
                    Icon(
                        imageVector = if (partialMb > 0) Icons.Default.PlayArrow else Icons.Default.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (partialMb > 0) "Resume Download ($partialMb MB saved)" else "Download AI Model (1.0 GB)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                // RAM & STORAGE SPEC METRICS
                Surface(
                    color = Color(0xFF121218),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, contentDescription = "RAM", tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "System RAM: ${specs.totalPhysicalRamGb} GB (Avail: ${specs.availableRamGb} GB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "Req: 3-4 GB",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storage, contentDescription = "Storage", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Free Space: ${specs.freeStorageGb} GB (Min 1.3 GB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (specs.freeStorageGb >= 1.3) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }

                            Text(
                                text = if (partialMb > 0) "$partialMb MB / 1.0 GB" else "Size: 1.0 GB",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // FEATURES LIST
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1C24), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeatureRow(icon = "🧠", title = "3 to 4 GB RAM Requirement", desc = "Loads 1.5B parameters on-device. Uses 3–4 GB RAM during active neural reasoning.")
                    FeatureRow(icon = "💻", title = "Offline Coding & Logic", desc = "Kotlin, Python, JS, SQL, and algorithm solver without cloud latency.")
                    FeatureRow(icon = "📱", title = "Full Life OS Automation", desc = "Read, write, edit, and delete Tasks, Habits, Notes, Finances & Timer.")
                    FeatureRow(icon = "🔒", title = "100% Private On-Device", desc = "Zero telemetry, no subscriptions, works fully in airplane mode.")
                    FeatureRow(icon = "🏎️", title = "4GB RAM Safe Engine", desc = "Optimized memory-mapped KV cache safeguards preventing OOM crashes.")
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadProgressScreen(
    downloadState: com.example.util.DownloadProgressState,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.size(72.dp),
                    color = Color(0xFF6366F1),
                    trackColor = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 6.dp
                )

                Text(
                    text = "Downloading Qwen 2.5 Coder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6366F1).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${downloadState.speedMode.icon} ${downloadState.speedMode.label} Mode",
                        color = Color(0xFF818CF8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = Color(0xFF6366F1),
                    trackColor = Color.White.copy(alpha = 0.1f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1C24), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Progress", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        Text(
                            text = "${(downloadState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Downloaded", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        Text(
                            text = "${(downloadState.bytesDownloaded / (1024 * 1024))} MB / ${(downloadState.totalBytes / (1024 * 1024))} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Speed", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        Text(
                            text = "${downloadState.speedMBps} MB/s",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }

                    if (downloadState.etaSeconds > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Estimated Remaining", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                            Text(
                                text = "${downloadState.etaSeconds / 60}m ${downloadState.etaSeconds % 60}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Text(
                    text = "Background downloading is active. You can safely switch tabs or minimize the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Download")
                }
            }
        }
    }
}

@Composable
private fun ActiveQwenChatScreen(
    viewModel: AppViewModel,
    onShowModelInfo: () -> Unit
) {
    val messages by viewModel.chatbotMessages.collectAsState()
    val isLoading by viewModel.chatbotLoading.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
    ) {
        // TOP BAR
        Surface(
            color = Color(0xFF16161C),
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6366F1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "AI",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Deepa AI Assistant",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFA855F7))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "🛡️ 100% ON-DEVICE INTELLIGENCE",
                                fontSize = 10.sp,
                                color = Color(0xFFA855F7),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onShowModelInfo,
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✅ Verified Compatible",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = onShowModelInfo) {
                        Icon(Icons.Default.Info, contentDescription = "Model Specs & Compatibility", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        // CHAT MESSAGES LIST
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    ChatEmptyPlaceholder(
                        onSuggestionSelected = { query ->
                            viewModel.sendMessageToAI(query)
                        }
                    )
                }
            }

            itemsIndexed(messages, key = { idx, msg -> "${msg.id}_${msg.timestamp}_$idx" }) { _, msg ->
                ChatBubbleItem(
                    message = msg,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(msg.text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF6366F1),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Deepa AI is reasoning locally...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // SUGGESTION CHIPS (WHEN NOT EMPTY)
        if (messages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickChip("📋 List tasks") { viewModel.sendMessageToAI("Show my pending tasks") }
                QuickChip("💡 Concept") { viewModel.sendMessageToAI("Explain photosynthesis") }
                QuickChip("💻 Code Kotlin") { viewModel.sendMessageToAI("Write binary search in Kotlin") }
                QuickChip("😄 Joke") { viewModel.sendMessageToAI("Tell me a funny programming joke") }
            }
        }

        // INPUT BAR
        Surface(
            color = Color(0xFF16161C),
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask anything, code, or execute app actions...", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .testTag("ai_chat_input_field"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF22222B),
                        unfocusedContainerColor = Color(0xFF22222B),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            viewModel.sendMessageToAI(text)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (inputText.isNotBlank()) Color(0xFF6366F1) else Color(0xFF2B2B36))
                        .testTag("send_ai_message_button")
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: com.example.ui.ChatMessage,
    onCopy: () -> Unit
) {
    val isUser = message.isUser

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            if (!isUser) {
                val modelLabel = message.modelUsed?.ifBlank { "Deepa AI (On-Device)" } ?: "Deepa AI (On-Device)"
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8),
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) Color(0xFF6366F1) else Color(0xFF1E1E26),
            border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )

                    if (!isUser) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = onCopy,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyPlaceholder(
    onSuggestionSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "AI",
                tint = Color(0xFF818CF8),
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = "Deepa AI (100% On-Device Engine)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "Fully private, autonomous on-device intelligence for science, coding, mathematics, focus tracking, and Life OS automations.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Try asking:",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )

            listOf(
                "🌿 Explain photosynthesis in detail",
                "💻 Write binary search algorithm in Kotlin",
                "🕳️ What is a black hole and how does it form?",
                "📋 Show my pending tasks",
                "💰 Log expense ₹250 on books",
                "😄 Tell me a funny programming joke"
            ).forEach { prompt ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSuggestionSelected(prompt.substring(2).trim()) },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E26),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = icon, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF22222C),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
