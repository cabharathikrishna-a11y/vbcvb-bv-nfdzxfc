package com.example.util

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.receiver.TimerNotificationReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Dedicated Focus Display, Screen Status, Brightness & Idle Full-Screen Timer Manager.
 *
 * Capabilities:
 * 1. Screen Status (Keep Screen On / Display Awake) during active focus sessions.
 * 2. Auto Ultra-Low Screen Brightness in Full Screen:
 *    - In Full Screen Timer mode (both Home Screen Idle zoom and Timer tab full screen), display automatically dims to a comfortable low level (0.08f) keeping digits, clock time, and battery legible while conserving power.
 *    - Touching or interacting with the screen anywhere immediately restores brightness back to normal!
 * 3. 10-Second Home Screen Idle Detection:
 *    - Automatically monitors when the user is on the Home Screen (Launcher or App Home).
 *    - If 10 seconds elapse with NO touch/interaction during active focus, smoothly zooms into Full-Screen Timer display mode with centered digits, clock time, and battery level.
 *    - When the screen is touched or interacted with anywhere, immediately dismisses and returns back to the normal home screen.
 *    - Strictly checks that the device is on the Home Screen — if any other app is opened or active, full-screen mode is completely suppressed!
 * 4. Dedicated Full-Screen Active Notification:
 *    - Displays an ongoing status notification with a 1-tap "✕ Close Full Screen" button while full screen is active.
 */
object FocusDisplayManager {

    private const val TAG = "FocusDisplayManager"
    const val PREFS_NAME = "app_prefs"

    const val FULLSCREEN_NOTIF_CHANNEL_ID = "lifeos_fullscreen_overlay_channel"
    const val FULLSCREEN_NOTIF_ID = 10005

    // Preference keys
    const val KEY_FOCUS_KEEP_SCREEN_ON = "focus_keep_screen_on"
    const val KEY_AUTO_ULTRA_LOW_BRIGHTNESS = "auto_ultra_low_brightness_in_fullscreen"
    const val KEY_AUTO_FULLSCREEN_ON_HOME_IDLE = "auto_fullscreen_on_home_idle"
    const val KEY_AUTO_FULLSCREEN_IDLE_SECONDS = "auto_fullscreen_idle_seconds"
    const val KEY_AUTO_FULLSCREEN_ZOOM_ANIM = "auto_fullscreen_zoom_anim"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleMonitorJob: Job? = null

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    @Volatile
    private var lastInteractionTimestamp: Long = System.currentTimeMillis()

    @Volatile
    private var isFullScreenIdleActive = false

    private val _isFullScreenIdleVisible = MutableStateFlow(false)
    val isFullScreenIdleVisible: StateFlow<Boolean> = _isFullScreenIdleVisible.asStateFlow()

    private var fullScreenView: View? = null
    private var tvDigits: TextView? = null
    private var tvClockTime: TextView? = null
    private var tvBatteryLevel: TextView? = null
    private var tvPhaseBadge: TextView? = null
    private var tvTaskTitle: TextView? = null
    private var tvTapHint: TextView? = null

    private var cachedLauncherPackages: Set<String>? = null
    private var lastLauncherCacheTime: Long = 0

    /**
     * Initializes the display manager and starts idle monitoring.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        if (idleMonitorJob == null || idleMonitorJob?.isActive == false) {
            startIdleLoop(context.applicationContext)
        }
        Log.d(TAG, "FocusDisplayManager initialized.")
    }

    /**
     * Records a user interaction anywhere, resetting the idle countdown.
     * If full-screen idle overlay is visible, dismisses it immediately.
     */
    fun notifyUserInteracted(context: Context? = null) {
        lastInteractionTimestamp = System.currentTimeMillis()
        if (isFullScreenIdleActive || fullScreenView != null) {
            dismissFullScreenIdleTimer(context ?: appContext)
        }
    }

    /**
     * Applies screen status (Keep Screen On) to an Activity window during focus.
     */
    fun applyScreenSettingsForFocus(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val context = activity.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val isKeepOn = prefs.getBoolean(KEY_FOCUS_KEEP_SCREEN_ON, true)
        val isSessionActive = FocusTimerManager.isTimerRunning.value || 
                              FocusTimerManager.isStopwatchActive.value || 
                              FocusTimerManager.isPaused.value

        try {
            val window = activity.window ?: return
            if (isSessionActive && isKeepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply screen settings for focus: ${e.message}")
        }
    }

    /**
     * Restores default screen settings (clears keep awake & screen brightness override).
     */
    fun restoreScreenSettings(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        try {
            val window = activity.window ?: return
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore screen settings: ${e.message}")
        }
    }

    /**
     * Core 10-second idle loop that verifies device Home Screen state.
     */
    private fun startIdleLoop(context: Context) {
        idleMonitorJob?.cancel()
        idleMonitorJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(400)
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val isFeatureEnabled = prefs.getSafeBoolean(KEY_AUTO_FULLSCREEN_ON_HOME_IDLE, true)
                    val idleSeconds = prefs.getSafeInt(KEY_AUTO_FULLSCREEN_IDLE_SECONDS, 10).coerceAtLeast(3)
                    val idleThresholdMs = idleSeconds * 1000L

                    val isTimerActive = FocusTimerManager.isTimerRunning.value
                    val isStopwatchActive = FocusTimerManager.isStopwatchActive.value
                    val isPaused = FocusTimerManager.isPaused.value
                    // When a session is paused or not actively running, full-screen is paused/suppressed
                    val isSessionRunning = (isTimerActive || isStopwatchActive) && !isPaused

                    if (!isFeatureEnabled || !isSessionRunning) {
                        if (isFullScreenIdleActive || fullScreenView != null) {
                            dismissFullScreenIdleTimer(context)
                        }
                        lastInteractionTimestamp = System.currentTimeMillis()
                        continue
                    }

                    // Check if current view is strictly Home Screen (App Home or Device Launcher)
                    val isHome = isDeviceHomeScreenOrAppHome(context)
                    if (!isHome) {
                        // If user is inside another app (e.g. Chrome, WhatsApp, Notes, Instagram), immediately dismiss full screen
                        if (isFullScreenIdleActive || fullScreenView != null) {
                            dismissFullScreenIdleTimer(context)
                        }
                        lastInteractionTimestamp = System.currentTimeMillis()
                        continue
                    }

                    val elapsedSinceInteraction = System.currentTimeMillis() - lastInteractionTimestamp

                    if (elapsedSinceInteraction >= idleThresholdMs) {
                        if (!isFullScreenIdleActive && fullScreenView == null) {
                            showFullScreenIdleTimer(context)
                        } else {
                            updateDigitsText()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in idle monitor loop: ${e.message}")
                }
            }
        }
    }

    /**
     * Strictly checks if the device is currently showing the Home Screen (Launcher) or the LifeOS App Home Screen.
     * If another app is open, returns false to prevent interrupting the user.
     */
    private fun isDeviceHomeScreenOrAppHome(context: Context): Boolean {
        // If our app is in foreground
        if (!FocusTimerManager.appIsBackgrounded) {
            return true
        }

        // App is in background: verify the active top foreground package is strictly a launcher
        return try {
            val topPackage = getTopForegroundPackage(context)
            if (topPackage == null) {
                // If top package cannot be identified, suppress full screen to avoid appearing over other apps
                false
            } else {
                val launchers = getLauncherPackages(context)
                launchers.contains(topPackage) || topPackage == context.packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves all installed Home Screen launcher packages on the device.
     */
    private fun getLauncherPackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (cachedLauncherPackages != null && (now - lastLauncherCacheTime < 60000)) {
            return cachedLauncherPackages!!
        }

        val packages = mutableSetOf<String>()
        try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveList = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolve in resolveList) {
                resolve.activityInfo?.packageName?.let { packages.add(it) }
            }
            val defaultHome = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            defaultHome?.activityInfo?.packageName?.let { packages.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying launcher packages: ${e.message}")
        }

        // Common OEM & Third-Party launcher packages
        packages.addAll(listOf(
            "com.google.android.apps.nexuslauncher",
            "com.google.android.launcher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",      // Samsung One UI
            "com.miui.home",                    // Xiaomi HyperOS/MIUI
            "com.oppo.launcher",                // Oppo ColorOS
            "com.oneplus.launcher",             // OnePlus OxygenOS
            "com.huawei.android.launcher",      // Huawei EMUI/HarmonyOS
            "com.transsion.hilauncher",         // Tecno/Infinix
            "com.motorola.launcher3",           // Motorola
            "com.vivo.launcher",                // Vivo OriginOS/Funtouch
            "com.realme.launcher",              // Realme UI
            "com.nothing.launcher",             // Nothing OS
            "com.teslacoilsw.launcher",         // Nova Launcher
            "com.microsoft.launcher",           // Microsoft Launcher
            "com.actionlauncher.playstore",     // Action Launcher
            "app.lawnchair",                    // Lawnchair
            "app.lawnchair.lawnchair3",
            "com.smartlauncher.smartlauncher",  // Smart Launcher
            "com.niagara.launcher"              // Niagara Launcher
        ))

        cachedLauncherPackages = packages
        lastLauncherCacheTime = now
        return packages
    }

    /**
     * Determines current top foreground application package.
     */
    private fun getTopForegroundPackage(context: Context): String? {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 10000 // 10 second window

            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            var lastTopPackage: String? = null
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastTopPackage = event.packageName
                    }
                }
            }
            lastTopPackage
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Displays the full-screen timer mode with a smooth zoom transition moving numbers to the center.
     */
    fun showFullScreenIdleTimer(context: Context) {
        if (isFullScreenIdleActive || fullScreenView != null) return
        if (!OverlayPermissionHelper.hasOverlayPermission(context)) {
            Log.d(TAG, "Overlay permission not granted for full screen idle timer.")
            return
        }

        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return

        try {
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#F5000000")) // AMOLED Black
                isClickable = true
                isFocusable = true
            }

            // Top status bar for Clock Time (top-left) and Battery Level (top-right)
            val topBarLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(context, 20f), dpToPx(context, 36f), dpToPx(context, 20f), dpToPx(context, 10f))
            }

            val clockView = TextView(context).apply {
                text = getFormattedCurrentTime()
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvClockTime = clockView
            topBarLayout.addView(clockView)

            val batteryView = TextView(context).apply {
                text = getBatteryString(context)
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            tvBatteryLevel = batteryView
            topBarLayout.addView(batteryView)

            val topBarParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
            root.addView(topBarLayout, topBarParams)

            val contentContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpToPx(context, 24f), dpToPx(context, 40f), dpToPx(context, 24f), dpToPx(context, 40f))
            }

            // Phase indicator badge (e.g. 🎯 POMODORO FOCUS)
            val phaseBadge = TextView(context).apply {
                text = getPhaseBadgeText()
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#2600E5FF"))
                    cornerRadius = dpToPx(context, 20f).toFloat()
                    setStroke(dpToPx(context, 1f), Color.parseColor("#4D00E5FF"))
                }
                setPadding(dpToPx(context, 16f), dpToPx(context, 6f), dpToPx(context, 16f), dpToPx(context, 6f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(context, 24f)
                }
            }
            tvPhaseBadge = phaseBadge
            contentContainer.addView(phaseBadge)

            // Giant Centered Timer Digits
            val digitsView = TextView(context).apply {
                text = LiveTimerDisplayRelay.liveDisplayTimeText.value
                setTextColor(Color.WHITE)
                textSize = 68f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                letterSpacing = 0.05f
                setShadowLayer(25f, 0f, 0f, Color.parseColor("#6600E5FF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            tvDigits = digitsView
            contentContainer.addView(digitsView)

            // Attached task title if present
            val taskTitleView = TextView(context).apply {
                val task = FocusTimerManager.attachedTask.value
                text = if (task != null && task.title.isNotBlank()) "📌 ${task.title}" else "Life OS Focus Session"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 15f
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(context, 16f)
                }
            }
            tvTaskTitle = taskTitleView
            contentContainer.addView(taskTitleView)

            // Interactive Exit Pill Button & Touch Hint
            val exitButton = TextView(context).apply {
                text = "✕  Tap anywhere to close Full Screen"
                setTextColor(Color.parseColor("#DDDDDD"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#28FFFFFF"))
                    cornerRadius = dpToPx(context, 20f).toFloat()
                    setStroke(dpToPx(context, 1f), Color.parseColor("#44FFFFFF"))
                }
                setPadding(dpToPx(context, 18f), dpToPx(context, 8f), dpToPx(context, 18f), dpToPx(context, 8f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(context, 32f)
                }
            }
            tvTapHint = exitButton
            contentContainer.addView(exitButton)

            val frameParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            root.addView(contentContainer, frameParams)

            // Direct Touch Listener: Any touch anywhere immediately dismisses the full screen overlay
            root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        notifyUserInteracted(context)
                        true
                    }
                    else -> true
                }
            }
            root.setOnClickListener {
                notifyUserInteracted(context)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isUltraLowBrightness = prefs.getBoolean(KEY_AUTO_ULTRA_LOW_BRIGHTNESS, true)

            val wmLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (isUltraLowBrightness) {
                    screenBrightness = 0.08f // Increased slightly to 8% for comfortable legibility
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            wm.addView(root, wmLayoutParams)
            fullScreenView = root
            isFullScreenIdleActive = true
            _isFullScreenIdleVisible.value = true

            // Post the dedicated Full Screen active notification
            postFullScreenActiveNotification(context)

            // Execute Zoom & Centering Entrance Animation
            val useZoomAnim = prefs.getBoolean(KEY_AUTO_FULLSCREEN_ZOOM_ANIM, true)
            if (useZoomAnim) {
                val scaleAnim = ScaleAnimation(
                    0.25f, 1.0f,
                    0.25f, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 320
                    interpolator = DecelerateInterpolator(1.8f)
                }

                val alphaAnim = AlphaAnimation(0.0f, 1.0f).apply {
                    duration = 240
                }

                val animSet = AnimationSet(true).apply {
                    addAnimation(scaleAnim)
                    addAnimation(alphaAnim)
                }
                contentContainer.startAnimation(animSet)
            }

            Log.d(TAG, "Full-screen idle timer overlay successfully displayed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display full-screen idle timer overlay: ${e.message}", e)
        }
    }

    /**
     * Posts a high-visibility notification allowing the user to close full screen from the notification drawer.
     */
    private fun postFullScreenActiveNotification(context: Context) {
        try {
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    FULLSCREEN_NOTIF_CHANNEL_ID,
                    "Full-Screen Focus Mode",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notification to exit Full-Screen Focus Display"
                    setShowBadge(false)
                    setSound(null, null)
                }
                notifManager.createNotificationChannel(channel)
            }

            val closeIntent = Intent(context, TimerNotificationReceiver::class.java).apply {
                action = LiveTimerNotificationManager.ACTION_CLOSE_FULLSCREEN_OVERLAY
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pClose = PendingIntent.getBroadcast(context, 8881, closeIntent, flags)

            val notif = NotificationCompat.Builder(context, FULLSCREEN_NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("📺 Full-Screen Focus Mode Active")
                .setContentText("Tap here to exit Full Screen & return to Home Screen")
                .setContentIntent(pClose)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "✕ Close Full Screen", pClose)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notifManager.notify(FULLSCREEN_NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post full screen active notification: ${e.message}")
        }
    }

    /**
     * Updates the numbers and badges in real-time.
     */
    private fun updateDigitsText() {
        val snapshot = LiveTimerDisplayRelay.computeCurrentDisplaySnapshot()
        val text = snapshot.formattedTimeText
        val isMinus = snapshot.isMinusTimer

        tvDigits?.let { tv ->
            if (tv.text != text) {
                tv.text = text
            }
            if (isMinus) {
                tv.setTextColor(Color.parseColor("#EF5350"))
                tv.setShadowLayer(25f, 0f, 0f, Color.parseColor("#66EF5350"))
            } else {
                tv.setTextColor(Color.WHITE)
                tv.setShadowLayer(25f, 0f, 0f, Color.parseColor("#6600E5FF"))
            }
        }

        tvPhaseBadge?.let { badge ->
            badge.text = getPhaseBadgeText()
        }

        tvClockTime?.let { tv ->
            val currTime = getFormattedCurrentTime()
            if (tv.text != currTime) {
                tv.text = currTime
            }
        }

        tvBatteryLevel?.let { tv ->
            appContext?.let { ctx ->
                val bat = getBatteryString(ctx)
                if (tv.text != bat) {
                    tv.text = bat
                }
            }
        }

        val task = FocusTimerManager.attachedTask.value
        tvTaskTitle?.let { titleView ->
            titleView.text = if (task != null && task.title.isNotBlank()) "📌 ${task.title}" else "Life OS Focus Session"
        }
    }

    private fun getFormattedCurrentTime(): String {
        return try {
            val sdf = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
            sdf.format(java.util.Date())
        } catch (e: Exception) {
            ""
        }
    }

    private fun getBatteryString(context: Context): String {
        return try {
            val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = context.registerReceiver(null, filter)
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                "🔋 $pct%"
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun getPhaseBadgeText(): String {
        val isMinus = FocusTimerManager.isMinusTimerActive.value
        val isFocusPhase = FocusTimerManager.isFocusPhase.value
        val isStopwatch = FocusTimerManager.isStopwatchActive.value
        val isPaused = FocusTimerManager.isPaused.value

        return when {
            isMinus -> "⏳ OVERTIME FOCUS"
            !isFocusPhase -> if (isPaused) "☕ REST BREAK (PAUSED)" else "☕ REST BREAK"
            isStopwatch -> if (isPaused) "⏱ STOPWATCH (PAUSED)" else "⏱ STOPWATCH FOCUS"
            isPaused -> "🎯 POMODORO (PAUSED)"
            else -> "🎯 POMODORO FOCUS"
        }
    }

    /**
     * Dismisses the full screen overlay immediately and smoothly, returning to the normal home screen.
     */
    fun dismissFullScreenIdleTimer(context: Context? = null) {
        if (!isFullScreenIdleActive && fullScreenView == null) return

        val view = fullScreenView
        val ctx = context ?: appContext
        val wm = windowManager ?: (ctx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)

        isFullScreenIdleActive = false
        _isFullScreenIdleVisible.value = false
        lastInteractionTimestamp = System.currentTimeMillis()

        // Cancel the Full Screen notification immediately
        try {
            val notifManager = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notifManager?.cancel(FULLSCREEN_NOTIF_ID)
        } catch (_: Exception) {}

        if (view != null && wm != null) {
            try {
                // Immediately disable touch handling on view so underlying apps and home screen receive touches instantly
                view.isClickable = false
                view.isFocusable = false
                view.setOnTouchListener(null)
                view.setOnClickListener(null)

                val removeAction = Runnable {
                    try {
                        wm.removeView(view)
                    } catch (_: Exception) {}
                }

                // Smooth fade and scale out animation
                view.animate()
                    .alpha(0f)
                    .scaleX(0.75f)
                    .scaleY(0.75f)
                    .setDuration(120)
                    .withEndAction(removeAction)
                    .start()

                // Fallback guarantee: ensures view is removed from WindowManager even if animator fails
                view.postDelayed(removeAction, 160)
            } catch (e: Exception) {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {}
            } finally {
                fullScreenView = null
                tvDigits = null
                tvPhaseBadge = null
                tvTaskTitle = null
                tvTapHint = null
                tvClockTime = null
                tvBatteryLevel = null
            }
        }
        Log.d(TAG, "Full-screen idle timer overlay dismissed; returned to normal Home Screen.")
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}

