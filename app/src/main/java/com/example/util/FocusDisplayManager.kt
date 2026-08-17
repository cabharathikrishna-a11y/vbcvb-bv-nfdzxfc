package com.example.util

import android.app.Activity
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
 *    - In Full Screen Timer mode (both Home Screen Idle zoom and Timer tab full screen), display automatically dims to lowest possible level (0.01f) keeping digits, clock time, and battery legible.
 *    - Touching or interacting with the screen immediately restores brightness back to normal!
 * 3. 10-Second Home Screen Idle Detection:
 *    - Automatically monitors when the user is on the Home Screen (Launcher or App Home).
 *    - If 10 seconds elapse with NO touch/interaction during active focus, smoothly zooms into Full-Screen Timer display mode with centered digits, clock time, and battery level.
 *    - When the screen is touched or interacted with, smoothly shrinks back to normal home screen and OSD, restoring normal brightness and resetting the 10s idle counter.
 *    - Strictly checks that the device is on the Home Screen — if any other app is opened or active, full-screen mode is completely suppressed!
 */
object FocusDisplayManager {

    private const val TAG = "FocusDisplayManager"
    const val PREFS_NAME = "app_prefs"

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
     * Records a user interaction, resetting the idle countdown.
     * If full-screen idle overlay is visible, dismisses it and returns to normal home screen with normal brightness.
     */
    fun notifyUserInteracted(context: Context? = null) {
        lastInteractionTimestamp = System.currentTimeMillis()
        if (isFullScreenIdleActive) {
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
                delay(500) // check twice a second
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val isFeatureEnabled = prefs.getSafeBoolean(KEY_AUTO_FULLSCREEN_ON_HOME_IDLE, true)
                    val idleSeconds = prefs.getSafeInt(KEY_AUTO_FULLSCREEN_IDLE_SECONDS, 10).coerceAtLeast(3)
                    val idleThresholdMs = idleSeconds * 1000L

                    val isTimerActive = FocusTimerManager.isTimerRunning.value
                    val isStopwatchActive = FocusTimerManager.isStopwatchActive.value
                    val isPaused = FocusTimerManager.isPaused.value
                    val hasActiveSession = isTimerActive || isStopwatchActive || isPaused

                    if (!isFeatureEnabled || !hasActiveSession) {
                        if (isFullScreenIdleActive) {
                            dismissFullScreenIdleTimer(context)
                        }
                        continue
                    }

                    // Check if current view is Home Screen (App Home or Device Launcher)
                    val isHome = isDeviceHomeScreenOrAppHome(context)
                    if (!isHome) {
                        // If user is inside another app (e.g. Chrome, WhatsApp, Notes), immediately suppress/dismiss full screen
                        if (isFullScreenIdleActive) {
                            dismissFullScreenIdleTimer(context)
                        }
                        lastInteractionTimestamp = System.currentTimeMillis() // reset idle countdown while in other apps
                        continue
                    }

                    val elapsedSinceInteraction = System.currentTimeMillis() - lastInteractionTimestamp

                    if (elapsedSinceInteraction >= idleThresholdMs) {
                        if (!isFullScreenIdleActive) {
                            showFullScreenIdleTimer(context)
                        } else {
                            // Update active text and numbers
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
     * Checks if the device is currently showing the Home Screen (Launcher) or the LifeOS App Home Screen.
     */
    private fun isDeviceHomeScreenOrAppHome(context: Context): Boolean {
        // If our app is in foreground and on Home/Timer view
        if (!FocusTimerManager.appIsBackgrounded) {
            return true
        }

        // If app is backgrounded, inspect foreground package
        return try {
            val launchers = getLauncherPackages(context)
            val topPackage = getTopForegroundPackage(context)

            if (topPackage != null) {
                launchers.contains(topPackage) || topPackage == context.packageName
            } else {
                // If usage stats not accessible or null, permit when overlay is actively displayed on home
                true
            }
        } catch (e: Exception) {
            true
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
        } catch (e: Exception) {
            Log.w(TAG, "Error querying launcher packages: ${e.message}")
        }

        // Common OEM launcher fallbacks
        packages.addAll(listOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",      // Samsung One UI
            "com.miui.home",                    // Xiaomi MIUI/HyperOS
            "com.oppo.launcher",                // Oppo ColorOS
            "com.oneplus.launcher",             // OnePlus OxygenOS
            "com.huawei.android.launcher",      // Huawei EMUI/HarmonyOS
            "com.transsion.hilauncher",         // Tecno/Infinix
            "com.motorola.launcher3",           // Motorola
            "com.vivo.launcher",                // Vivo OriginOS/Funtouch
            "com.realme.launcher"               // Realme UI
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
            val startTime = endTime - 12000 // 12 second window

            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            var lastTopPackage: String? = null
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
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
        if (isFullScreenIdleActive) return
        if (!OverlayPermissionHelper.hasOverlayPermission(context)) {
            Log.d(TAG, "Overlay permission not granted for full screen idle timer.")
            return
        }

        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return

        try {
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#E6000000")) // Deep 90% AMOLED Black with translucent backdrop
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

            // Bottom Tap-to-Exit hint
            val tapHint = TextView(context).apply {
                text = "👆 Tap anywhere to restore full brightness & return to Home Screen"
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(context, 36f)
                }
            }
            tvTapHint = tapHint
            contentContainer.addView(tapHint)

            val frameParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            root.addView(contentContainer, frameParams)

            // Touch Listener: Touching anywhere smoothly returns to normal home screen and normal brightness
            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                    notifyUserInteracted(context)
                    return@setOnTouchListener true
                }
                true
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
                    screenBrightness = 0.01f // lowest possible brightness, keeps numbers, clock, and battery legible
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

            // Execute Zoom & Centering Entrance Animation
            val useZoomAnim = prefs.getBoolean(KEY_AUTO_FULLSCREEN_ZOOM_ANIM, true)
            if (useZoomAnim) {
                val scaleAnim = ScaleAnimation(
                    0.25f, 1.0f,
                    0.25f, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 380
                    interpolator = DecelerateInterpolator(1.8f)
                }

                val alphaAnim = AlphaAnimation(0.0f, 1.0f).apply {
                    duration = 300
                }

                val animSet = AnimationSet(true).apply {
                    addAnimation(scaleAnim)
                    addAnimation(alphaAnim)
                }
                contentContainer.startAnimation(animSet)
            }

            Log.d(TAG, "Full-screen idle timer overlay successfully displayed with zoom animation.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display full-screen idle timer overlay: ${e.message}", e)
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
     * Dismisses the full screen overlay with a smooth exit animation, returning to the normal home screen.
     */
    fun dismissFullScreenIdleTimer(context: Context? = null) {
        if (!isFullScreenIdleActive && fullScreenView == null) return

        val view = fullScreenView
        val wm = windowManager ?: (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)

        isFullScreenIdleActive = false
        _isFullScreenIdleVisible.value = false
        lastInteractionTimestamp = System.currentTimeMillis()

        if (view != null && wm != null) {
            try {
                val scaleAnim = ScaleAnimation(
                    1.0f, 0.4f,
                    1.0f, 0.4f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 200
                    interpolator = AccelerateDecelerateInterpolator()
                }
                val alphaAnim = AlphaAnimation(1.0f, 0.0f).apply {
                    duration = 180
                }
                val animSet = AnimationSet(true).apply {
                    addAnimation(scaleAnim)
                    addAnimation(alphaAnim)
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(p0: Animation?) {}
                        override fun onAnimationRepeat(p0: Animation?) {}
                        override fun onAnimationEnd(p0: Animation?) {
                            try {
                                wm.removeView(view)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    })
                }
                view.startAnimation(animSet)
            } catch (e: Exception) {
                try {
                    wm.removeView(view)
                } catch (ex: Exception) {
                    // Ignore
                }
            } finally {
                fullScreenView = null
                tvDigits = null
                tvPhaseBadge = null
                tvTaskTitle = null
                tvTapHint = null
            }
        }
        Log.d(TAG, "Full-screen idle timer overlay dismissed; returned to normal Home Screen.")
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
