package com.example.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AlarmScheduler
import com.example.util.SleepTimeHelper
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Bedtime
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReminderActivity : ComponentActivity() {

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var taskId: Int = -1
    private var rawTaskId: Int = -1
    private var taskTitle: String = ""
    private var taskTime: String = ""
    private var taskPriority: String = "MEDIUM"
    private var actionType: String = ""
    private var actionContactName: String = ""
    private var actionContactPhone: String = ""
    private var actionMessage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Parse extras
        taskId = intent.getIntExtra("TASK_ID", -1)
        rawTaskId = intent.getIntExtra("RAW_TASK_ID", -1)
        taskTitle = intent.getStringExtra("TASK_TITLE") ?: "Task Reminder"
        taskTime = intent.getStringExtra("TASK_TIME") ?: ""
        taskPriority = intent.getStringExtra("TASK_PRIORITY") ?: "MEDIUM"
        actionType = intent.getStringExtra("ACTION_TYPE") ?: ""
        actionContactName = intent.getStringExtra("ACTION_CONTACT_NAME") ?: ""
        actionContactPhone = intent.getStringExtra("ACTION_CONTACT_PHONE") ?: ""
        actionMessage = intent.getStringExtra("ACTION_MESSAGE") ?: ""

        Log.d("ReminderActivity", "Reminder activity launched for task $taskId, priority: $taskPriority")

        // Lockscreen / Keep Screen active parameters compatibility for modern Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        startAlert()

        setContent {
            MyApplicationTheme {
                // Auto turn off screen & stop alert if not responded within 15 minutes (15 * 60 * 1000L)
                LaunchedEffect(taskId) {
                    Log.d("ReminderActivity", "15-minute auto-turn-off timer active for reminder taskId: $taskId")
                    kotlinx.coroutines.delay(15 * 60 * 1000L) // 15 minutes
                    Log.d("ReminderActivity", "No response for 15 minutes. Turning off screen, stopping alert, and closing activity.")
                    try {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } catch (e: Exception) {
                        Log.e("ReminderActivity", "Error clearing keep screen on flag: ${e.message}")
                    }
                    stopAlert()
                    cancelNotification()
                    if (taskId == 20002) {
                        val prefs = applicationContext.getSharedPreferences("app_prefs", MODE_PRIVATE)
                        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        prefs.edit().putString("actual_wake_up_time_$todayStr", "LATE").apply()
                    }
                    finish()
                }

                if (taskId == 20001) {
                    BedtimeScreen(
                        onDismiss = {
                            stopAlert()
                            cancelNotification()
                            finish()
                        }
                    )
                } else if (taskId == 20002) {
                    WakeUpAlarmScreen(
                        onSnooze = { minutes ->
                            stopAlert()
                            cancelNotification()
                            AlarmScheduler.scheduleSnooze(applicationContext, taskId, taskTitle, taskTime, taskPriority, minutes)
                            finish()
                        },
                        onDismiss = {
                            stopAlert()
                            cancelNotification()
                            val prefs = applicationContext.getSharedPreferences("app_prefs", MODE_PRIVATE)
                            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            prefs.edit().putString("actual_wake_up_time_$todayStr", "LATE").apply()
                            finish()
                        },
                        onWokeUp = {
                            stopAlert()
                            cancelNotification()
                            val prefs = applicationContext.getSharedPreferences("app_prefs", MODE_PRIVATE)
                            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            val curTimeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
                            prefs.edit().putString("actual_wake_up_time_$todayStr", curTimeStr).apply()
                            finish()
                        }
                    )
                } else {
                    // Priority-specific auto-dismiss
                    if (taskPriority.uppercase() == "LOW" || taskPriority.uppercase() == "NONE") {
                        LaunchedEffect(Unit) {
                            Log.d("ReminderActivity", "Auto-dismissing in 5 minutes.")
                            kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 minutes (300,000 ms)
                            stopAlert()
                            finish()
                        }
                    }

                    var actTypeState by remember { mutableStateOf(actionType) }
                    var actNameState by remember { mutableStateOf(actionContactName) }
                    var actPhoneState by remember { mutableStateOf(actionContactPhone) }
                    var actMsgState by remember { mutableStateOf(actionMessage) }

                    LaunchedEffect(taskId) {
                        if (actTypeState.isEmpty() && taskId > 0) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val realTaskId = if (taskId >= 100) taskId / 100 else taskId
                                    val db = AppDatabase.getInstance(applicationContext)
                                    val t = db.taskDao().getTaskById(realTaskId)
                                    if (t != null) {
                                        val data = com.example.util.TaskActionHelper.parseActionData(t)
                                        withContext(Dispatchers.Main) {
                                            actTypeState = data.type
                                            actNameState = data.contactName
                                            actPhoneState = data.contactPhone
                                            actMsgState = data.message
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("ReminderActivity", "Error loading action state: ${e.message}")
                                }
                            }
                        }
                    }

                    ReminderScreen(
                        title = taskTitle.substringBefore(" (").trim(),
                        time = taskTime,
                        actionType = actTypeState,
                        actionContactName = actNameState,
                        actionContactPhone = actPhoneState,
                        actionMessage = actMsgState,
                        onDismiss = {
                            stopAlert()
                            cancelNotification()
                            finish()
                        },
                        onSnooze = { minutes ->
                            stopAlert()
                            cancelNotification()
                            val data = com.example.util.TaskActionData(actTypeState, actNameState, actPhoneState, actMsgState)
                            AlarmScheduler.scheduleSnooze(applicationContext, taskId, taskTitle, taskTime, taskPriority, minutes, data)
                            finish()
                        },
                        onComplete = {
                            stopAlert()
                            cancelNotification()
                            markTaskCompletedAndFinish()
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun BedtimeScreen(
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
        val userName = remember {
            prefs.getString("user_name", "")?.takeIf { it.isNotBlank() }
                ?: prefs.getString("user_nickname", "")?.takeIf { it.isNotBlank() }
        }
        val wakeUpTimeStr = remember { SleepTimeHelper.getWakeUpTime(context) ?: "07:00" }

        // Live calculation of remaining sleep seconds until next scheduled wake-up time
        var remainingSecondsToSleep by remember {
            mutableLongStateOf(calculateRemainingSleepSeconds(wakeUpTimeStr))
        }

        LaunchedEffect(wakeUpTimeStr) {
            while (true) {
                remainingSecondsToSleep = calculateRemainingSleepSeconds(wakeUpTimeStr)
                kotlinx.coroutines.delay(1000L)
            }
        }

        // If untouched for 10 seconds OR if touched: dismiss/close immediately
        var isDismissed by remember { mutableStateOf(false) }
        val handleDismiss = remember(onDismiss) {
            {
                if (!isDismissed) {
                    isDismissed = true
                    onDismiss()
                }
            }
        }

        var autoCloseCountdown by remember { mutableIntStateOf(10) }

        LaunchedEffect(Unit) {
            autoCloseCountdown = 10
            while (autoCloseCountdown > 0) {
                kotlinx.coroutines.delay(1000L)
                autoCloseCountdown--
            }
            handleDismiss()
        }

        // Pulsing glowing animation for the moon
        val infiniteTransition = rememberInfiniteTransition(label = "bedtime_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        val haloAlpha by infiniteTransition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        val sleepHours = remainingSecondsToSleep / 3600
        val sleepMins = (remainingSecondsToSleep % 3600) / 60
        val sleepSecs = remainingSecondsToSleep % 60

        val formattedWakeUp = remember(wakeUpTimeStr) {
            try {
                val parts = wakeUpTimeStr.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                }
                java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(cal.time)
            } catch (e: Exception) {
                wakeUpTimeStr
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF030510), Color(0xFF0A0F24), Color(0xFF040612))
                    )
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // If display is touched anywhere, go away immediately
                            if (event.changes.any { it.pressed }) {
                                handleDismiss()
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 40.dp)
            ) {
                // Top spacing / indicator
                Spacer(modifier = Modifier.height(16.dp))

                // Center section: Moon + Good Night wish + Remaining Sleep Time
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Glowing Moon Halo
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF818CF8).copy(alpha = haloAlpha), Color.Transparent)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🌙",
                            fontSize = 72.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Good Night greeting
                    Text(
                        text = if (!userName.isNullOrBlank()) "Good Night, $userName!" else "Good Night! 🌙",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Wishing you restful sleep and peaceful dreams.",
                        color = Color(0xFFC7D2FE),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Remaining time to sleep Card
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF11172E).copy(alpha = 0.85f)),
                        border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 22.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bedtime,
                                    contentDescription = null,
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "REMAINING TIME TO SLEEP",
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "${sleepHours}h ${sleepMins}m",
                                    color = Color.White,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = String.format(java.util.Locale.US, "%02ds", sleepSecs),
                                    color = Color(0xFF818CF8),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                color = Color(0xFF1E294B).copy(alpha = 0.65f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "⏰ Wake-up scheduled for $formattedWakeUp",
                                    color = Color(0xFFE0E7FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom auto-close indicator (No buttons, auto-closes if untouched for 10 sec)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { (autoCloseCountdown / 10f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF818CF8),
                        trackColor = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Auto-closing in ${autoCloseCountdown}s • Tap anywhere to dismiss",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    private fun calculateRemainingSleepSeconds(wakeUpTimeStr: String): Long {
        val parts = wakeUpTimeStr.split(":")
        val targetHour = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val targetMin = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        val diffMs = target.timeInMillis - now.timeInMillis
        return (diffMs / 1000L).coerceAtLeast(0L)
    }

    @Composable
    private fun WakeUpAlarmScreen(
        onSnooze: (Int) -> Unit,
        onDismiss: () -> Unit,
        onWokeUp: () -> Unit
    ) {
        var snoozeMinutes by remember { mutableStateOf(10) }
        val timeFormat = remember { java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()) }
        var currentTime by remember { mutableStateOf(timeFormat.format(java.util.Date())) }

        LaunchedEffect(Unit) {
            while (true) {
                currentTime = timeFormat.format(java.util.Date())
                kotlinx.coroutines.delay(1000L)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF090A10)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                ) {
                    Text(
                        text = "☀️",
                        fontSize = 64.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = "Good Morning!",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentTime,
                        color = Color(0xFF38B0F2),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Interactive Buttons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Woke Up button (Primary)
                    Button(
                        onClick = onWokeUp,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("alarm_woke_up_btn")
                    ) {
                        Text(
                            text = "I literally woke up!",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Dismiss button (Secondary)
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("alarm_dismiss_btn")
                    ) {
                        Text(
                            text = "I am gonna wake up late",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Snooze controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = { if (snoozeMinutes > 5) snoozeMinutes -= 5 },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(12.dp)
                                    .height(2.dp)
                                    .background(Color.White.copy(alpha = 0.7f), shape = RoundedCornerShape(1.dp))
                            )
                        }

                        Button(
                            onClick = { onSnooze(snoozeMinutes) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC7A168).copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .widthIn(min = 160.dp)
                                .height(44.dp)
                                .border(1.dp, Color(0xFFC7A168).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                        ) {
                            Text(
                                text = "Snooze $snoozeMinutes mins",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE5C088)
                            )
                        }

                        IconButton(
                            onClick = { if (snoozeMinutes < 60) snoozeMinutes += 5 },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Increase Snooze",
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReminderScreen(
        title: String,
        time: String,
        actionType: String = "",
        actionContactName: String = "",
        actionContactPhone: String = "",
        actionMessage: String = "",
        onDismiss: () -> Unit,
        onSnooze: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        var snoozeMinutes by remember { mutableStateOf(15) }
        val context = LocalContext.current

        val customBgBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, context) {
            value = withContext(Dispatchers.IO) {
                val file = java.io.File(context.filesDir, "reminder_bg.jpg")
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val useCustom = prefs.getBoolean("use_custom_reminder_bg", false)
                if (useCustom && file.exists()) {
                    try {
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        }

        val currentBg = customBgBitmap
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (currentBg != null) {
                Image(
                    bitmap = currentBg.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF070709))
                        .drawBehind {
                            // Top-right teal glow aura
                            drawCircle(
                                color = Color(0xFF0F766E).copy(alpha = 0.28f),
                                radius = size.width * 0.8f,
                                center = androidx.compose.ui.geometry.Offset(size.width * 0.95f, size.height * 0.25f)
                            )
                            // Left purple glow aura
                            drawCircle(
                                color = Color(0xFF6D28D9).copy(alpha = 0.22f),
                                radius = size.width * 0.7f,
                                center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.55f)
                            )
                            // Bottom sandy gold gold glow aura
                            drawCircle(
                                color = Color(0xFFD97706).copy(alpha = 0.24f),
                                radius = size.width * 0.9f,
                                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.95f)
                            )
                        }
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 48.dp)
            ) {
                // Header details matching clean aesthetics
                Text(
                    text = "L I F E   O S   R E M I N D E R",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )

                // Central Focus Text & Buttons (Take my vitamins, 2:00 pm, Dismiss, Complete)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = title,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("reminder_task_title")
                        )

                        if (time.isNotEmpty() && time != "None") {
                            Text(
                                text = time,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Prominent Action Button for CALL / SMS / WHATSAPP if configured
                    if (actionType.isNotEmpty() && actionContactPhone.isNotEmpty()) {
                        val cleanPhone = actionContactPhone.replace(Regex("[^0-9+]"), "")
                        val displayName = actionContactName.ifEmpty { actionContactPhone }

                        when (actionType.uppercase()) {
                            "CALL" -> {
                                Button(
                                    onClick = {
                                        stopAlert()
                                        cancelNotification()
                                        try {
                                            val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL, android.net.Uri.parse("tel:$cleanPhone")).apply {
                                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(callIntent)
                                        } catch (e: SecurityException) {
                                            val dialIntent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$cleanPhone")).apply {
                                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(dialIntent)
                                        } catch (e: Exception) {
                                            Log.e("ReminderActivity", "Error calling: ${e.message}")
                                        }
                                        finish()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier
                                        .width(220.dp)
                                        .height(52.dp)
                                        .testTag("reminder_action_call_btn")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = "Call",
                                            tint = Color.Black,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Call $displayName",
                                            color = Color.Black,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            "SMS" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(220.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            stopAlert()
                                            cancelNotification()
                                            try {
                                                val smsIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$cleanPhone")).apply {
                                                    putExtra("sms_body", actionMessage)
                                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(smsIntent)
                                            } catch (e: Exception) {
                                                Log.e("ReminderActivity", "Error sending SMS: ${e.message}")
                                            }
                                            finish()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6FF3)),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("reminder_action_msg_btn")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Message,
                                                contentDescription = "Message",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Message $displayName",
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (actionMessage.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "\"$actionMessage\"",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            "WHATSAPP" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(220.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            stopAlert()
                                            cancelNotification()
                                            try {
                                                val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${android.net.Uri.encode(actionMessage)}"
                                                val waIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(waIntent)
                                            } catch (e: Exception) {
                                                Log.e("ReminderActivity", "Error launching WhatsApp: ${e.message}")
                                            }
                                            finish()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("reminder_action_wa_btn")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "WhatsApp",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "WhatsApp $displayName",
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (actionMessage.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "\"$actionMessage\"",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Centered stack buttons: Dismiss and Complete (styled beautifully like pill shape buttons)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        // Dismiss button with dark translucent container
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A3D3C).copy(alpha = 0.8f)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("reminder_dismiss_btn")
                        ) {
                            Text(
                                text = "Dismiss",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        // Complete button with elegant cyan/teal container
                        Button(
                            onClick = onComplete,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F766E).copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("reminder_completed_btn")
                        ) {
                            Text(
                                text = "Complete",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Bottom Interactive Capsule Bar for Custom Snoozing
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("reminder_snooze_container")
                ) {
                    // Minus Button
                    IconButton(
                        onClick = { if (snoozeMinutes > 5) snoozeMinutes -= 5 },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(2.dp)
                                .background(Color.White.copy(alpha = 0.7f), shape = RoundedCornerShape(1.dp))
                        )
                    }

                    // Central Snooze Button
                    Button(
                        onClick = { onSnooze(snoozeMinutes) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC7A168).copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .widthIn(min = 160.dp)
                            .height(48.dp)
                            .border(1.dp, Color(0xFFC7A168).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .testTag("reminder_snooze_btn")
                    ) {
                        Text(
                            text = "Snooze $snoozeMinutes mins",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE5C088)
                        )
                    }

                    // Plus Button
                    IconButton(
                        onClick = { if (snoozeMinutes < 120) snoozeMinutes += 5 },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Increase Snooze",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    private fun startAlert() {
        val prefsForSilent = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        // Wakeup alarm (20002) always rings, other alarms check silent settings
        if (taskId != 20002) {
            if (prefsForSilent.getBoolean("master_silent_mode", false) || prefsForSilent.getBoolean("task_silent_mode", false)) {
                Log.d("ReminderActivity", "Silent mode or master silent mode is ON. Suppressing sound and vibration.")
                return
            }
        }

        val alarmSoundKey = when (taskPriority.uppercase()) {
            "HIGH" -> "task_high_alarm_sound"
            "MEDIUM" -> "task_medium_alarm_sound"
            else -> "task_low_alarm_sound"
        }
        var isAlarmSoundEnabled = prefsForSilent.getBoolean(alarmSoundKey, false)
        if (taskId == 20002) {
            isAlarmSoundEnabled = true // Force ring wake-up alarm!
        }
        if (taskId == 20001) {
            isAlarmSoundEnabled = false // Bedtime reminders do not require alarm sound!
        }

        val isBT = isBluetoothAudioConnected(applicationContext)

        if (isAlarmSoundEnabled) {
            Log.d("ReminderActivity", "Continuous Alarm Sound is enabled for priority: $taskPriority")
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(applicationContext, alarmUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(if (isBT) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                    }
                    if (isBT) {
                        setVolume(0.2f, 0.2f) // Safe & gentle volume over Bluetooth
                    }
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("ReminderActivity", "MediaPlayer alarm launch failed, trying ringtone: ${e.message}")
                try {
                    val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        play()
                    }
                } catch (ex: Exception) {
                    Log.e("ReminderActivity", "Ringtone backup failed: ${ex.message}")
                }
            }

            // Continuous vibration if vibration is enabled
            try {
                val taskVibrationEnabled = prefsForSilent.getBoolean("task_vibration_enabled", true)
                if (taskVibrationEnabled) {
                    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.let { v ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val pattern = longArrayOf(0, 800, 800)
                            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                        } else {
                            @Suppress("DEPRECATION")
                            val pattern = longArrayOf(0, 800, 800)
                            v.vibrate(pattern, 0)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ReminderActivity", "Vibrator waveform launch failed: ${e.message}")
            }
        } else {
            if (taskId == 20001) {
                Log.d("ReminderActivity", "Bedtime reminder: Alarm sound suppressed.")
            } else {
                // Audio playback configured using TYPE_NOTIFICATION (single shot alert)
                try {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(applicationContext, alertUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val attributes = android.media.AudioAttributes.Builder()
                                .setUsage(if (isBT) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            this.audioAttributes = attributes
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = false
                        }
                        play()
                    }
                } catch (e: Exception) {
                    Log.e("ReminderActivity", "Ringtone launch omitted: ${e.message}")
                }
            }

            // Single short vibration for all priorities
            try {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val taskVibrationEnabled = prefs.getBoolean("task_vibration_enabled", true)
                if (taskVibrationEnabled) {
                    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.let { v ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            v.vibrate(600)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ReminderActivity", "Vibrator launch failed: ${e.message}")
            }
        }
    }

    private fun stopAlert() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            ringtone?.stop()
            vibrator?.cancel()
            com.example.util.AudioVolumeMuteHelper.unmuteAllStreams(this)
        } catch (e: Exception) {
            Log.e("ReminderActivity", "Alert stop failed: ${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (taskId != -1) {
                notificationManager.cancel(taskId)
            }
            if (rawTaskId != -1) {
                notificationManager.cancel(rawTaskId)
            }
        } catch (e: Exception) {
            Log.e("ReminderActivity", "Notification cancellation failed: ${e.message}")
        }
    }

    private fun markTaskCompletedAndFinish() {
        if (taskId == -1) {
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val taskList = db.taskDao().getAllTasks().first()
                val targetTask = taskList.find { it.id == taskId }
                
                if (targetTask != null) {
                    db.taskDao().updateTask(targetTask.copy(isCompleted = true))
                    Log.d("ReminderActivity", "Task $taskId completed successfully in background.")
                    AlarmScheduler.cancelReminder(applicationContext, taskId)
                }
            } catch (e: Exception) {
                Log.e("ReminderActivity", "Error updates completion state in DB: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val isAppPlayingSound = (mediaPlayer?.isPlaying == true) ||
                    (ringtone?.isPlaying == true) ||
                    com.example.util.FocusTimerManager.isAlarmRinging() ||
                    com.example.util.BackgroundMediaManager.isPlaying.value

            if (isAppPlayingSound) {
                try {
                    com.example.util.AudioVolumeMuteHelper.muteAllStreams(this)
                    stopAlert()
                    com.example.util.FocusTimerManager.stopAlarm()
                    com.example.util.BackgroundMediaManager.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    com.example.util.AudioVolumeMuteHelper.unmuteAllStreams(this)
                }
                return true
            } else {
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isBluetoothAudioConnected(context: Context): Boolean {
        return try {
            val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    val type = device.type
                    if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        return true
                    }
                }
            }
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        stopAlert()
        super.onDestroy()
    }
}
