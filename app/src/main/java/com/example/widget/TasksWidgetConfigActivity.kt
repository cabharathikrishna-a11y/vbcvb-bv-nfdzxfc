package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TasksWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A)
                ) { innerPadding ->
                    TasksWidgetConfigScreen(
                        modifier = Modifier.padding(innerPadding),
                        initialConfig = TasksWidgetPreferences.loadConfig(this, appWidgetId),
                        onSave = { newConfig ->
                            TasksWidgetPreferences.saveConfig(this, appWidgetId, newConfig)
                            val appWidgetManager = AppWidgetManager.getInstance(this)
                            TasksWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)
                            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, com.example.R.id.tasks_widget_list)

                            val resultValue = Intent().apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                            setResult(Activity.RESULT_OK, resultValue)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TasksWidgetConfigScreen(
    modifier: Modifier = Modifier,
    initialConfig: TaskWidgetConfig,
    onSave: (TaskWidgetConfig) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(initialConfig.dateFilter) }
    var showCompleted by remember { mutableStateOf(initialConfig.showCompleted) }
    var selectedFolder by remember { mutableStateOf(initialConfig.folder) }
    var noDateTasksAtLast by remember { mutableStateOf(initialConfig.noDateTasksAtLast) }
    var selectedGlass by remember { mutableStateOf(initialConfig.glassStyle) }

    var availableFolders by remember { mutableStateOf<List<String>>(listOf("ALL")) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                val allTasks = db.taskDao().getAllTasksDirect()
                val folders = allTasks.mapNotNull { it.listCategory.trim().takeIf { c -> c.isNotEmpty() } }.distinct().sorted()
                availableFolders = listOf("ALL") + folders
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Configure Tasks Widget",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Customize date filters, folder sorting, and appearance",
                fontSize = 13.sp,
                color = Color(0xFF94A3B8)
            )
        }

        // Section 1: Date Range Filter
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "DATE RANGE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                letterSpacing = 1.sp
            )

            val filterOptions = listOf(
                TaskDateFilter.TODAY to "Today (Overdue + Due Today)",
                TaskDateFilter.NEXT_3_DAYS to "Next 3 Days",
                TaskDateFilter.NEXT_7_DAYS to "Next 7 Days",
                TaskDateFilter.NEXT_30_DAYS to "Next 30 Days",
                TaskDateFilter.ALL to "All Tasks (No Date Filter)"
            )

            filterOptions.forEach { (filter, label) ->
                val isSelected = selectedFilter == filter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0x3338BDF8) else Color(0x14FFFFFF))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0x1EFFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedFilter = filter }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF38BDF8),
                            unselectedColor = Color(0xFF64748B)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                    )
                }
            }
        }

        // Section 2: Show Completed Tasks Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Show Completed Tasks",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Display struck-through completed tasks in the widget",
                    fontSize = 11.5.sp,
                    color = Color(0xFF94A3B8)
                )
            }
            Switch(
                checked = showCompleted,
                onCheckedChange = { showCompleted = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF38BDF8)
                )
            )
        }

        // Section 3: Place No-Date Tasks at End
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Show No-Date Tasks At End",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Tasks without a due date appear after dated tasks",
                    fontSize = 11.5.sp,
                    color = Color(0xFF94A3B8)
                )
            }
            Switch(
                checked = noDateTasksAtLast,
                onCheckedChange = { noDateTasksAtLast = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF38BDF8)
                )
            )
        }

        // Section 4: Folder Filter
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "FILTER BY FOLDER / CATEGORY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                letterSpacing = 1.sp
            )

            availableFolders.forEach { folder ->
                val isSelected = selectedFolder.equals(folder, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0x3338BDF8) else Color(0x14FFFFFF))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0x1EFFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedFolder = folder }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { selectedFolder = folder },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF38BDF8),
                            unselectedColor = Color(0xFF64748B)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (folder == "ALL") "All Folders" else "📁 $folder",
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                    )
                }
            }
        }

        // Section 5: Glass Style Appearance
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "WIDGET GLASS THEME",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                letterSpacing = 1.sp
            )

            val glassOptions = listOf(
                "black_glass" to "Opaque Black Glass",
                "dark_glass" to "Translucent Dark Glass",
                "frost_glass" to "Frosted Silver Glass",
                "clear_glass" to "Minimal Clear Glass"
            )

            glassOptions.forEach { (style, name) ->
                val isSelected = selectedGlass.equals(style, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0x3338BDF8) else Color(0x14FFFFFF))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0x1EFFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedGlass = style }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { selectedGlass = style },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF38BDF8),
                            unselectedColor = Color(0xFF64748B)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                    )
                }
            }
        }

        // Save Button
        Button(
            onClick = {
                onSave(
                    TaskWidgetConfig(
                        dateFilter = selectedFilter,
                        showCompleted = showCompleted,
                        folder = selectedFolder,
                        noDateTasksAtLast = noDateTasksAtLast,
                        glassStyle = selectedGlass
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0284C7)
            )
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Apply Widget Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
