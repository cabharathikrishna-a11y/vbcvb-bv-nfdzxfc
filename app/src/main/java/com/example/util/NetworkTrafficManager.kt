package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Dedicated Central Network & Traffic Controller
 *
 * Single code file responsible for:
 * 1. Routing all network traffic of the app (outbound requests, HTTP calls, OkHttp interceptor,
 *    HttpURLConnection wrapper, download/upload stream management, byte accounting).
 * 2. Receiving all network traffic of the app (inbound responses, byte tracking, real-time download
 *    and upload speed metering, per-category traffic statistics, telemetry logs).
 * 3. Authoritatively determining whether internet is ON or OFF (reactive StateFlows, active
 *    ConnectivityManager callbacks, and active lightweight reachability probing).
 * 4. Informing all other components of the app regarding their internet needs (metered data checks,
 *    bandwidth quality tier, data saver advice, deferred offline execution queue, and Jetpack Compose hooks).
 */
object NetworkTrafficManager {
    private const val TAG = "NetworkTrafficManager"

    // Custom Header to explicitly tag OkHttp requests with a category
    const val HEADER_TRAFFIC_CATEGORY = "X-Traffic-Category"

    // ---------------------------------------------------------------------------------------------
    // Enums & Models
    // ---------------------------------------------------------------------------------------------

    enum class ConnectionType(val displayName: String, val iconName: String) {
        WIFI("Wi-Fi", "wifi"),
        CELLULAR("Mobile Data", "signal_cellular_alt"),
        ETHERNET("Ethernet", "lan"),
        VPN("VPN Encrypted", "vpn_key"),
        BLUETOOTH("Bluetooth Tether", "bluetooth"),
        OFFLINE("Offline (No Internet)", "cloud_off")
    }

    enum class NetworkQuality(val label: String, val minDownlinkKbps: Int) {
        EXCELLENT("Excellent (>10 Mbps)", 10_000),
        GOOD("Good (2-10 Mbps)", 2_000),
        MODERATE("Moderate (500 Kbps - 2 Mbps)", 500),
        POOR("Poor (<500 Kbps)", 1),
        OFFLINE("Offline", 0)
    }

    enum class TrafficCategory(val displayName: String) {
        APP_UPDATE("App Updates & Patches"),
        DATABASE_SYNC("Database & Cloud Sync"),
        CLOUD_BACKUP("Cloud Backup & Drive"),
        PUSH_NOTIFICATION("Push & Urgent Notifications"),
        MEDIA_STREAMING("Media & Audio Streaming"),
        AUTHENTICATION("Authentication & Tokens"),
        OUTBOX_DRAIN("Offline Outbox Drain"),
        AI_MODELS("AI & Gemini Operations"),
        GENERAL_NETWORK("General Internet Traffic")
    }

    data class NetworkState(
        val isConnected: Boolean = false,
        val isInternetValidated: Boolean = false,
        val isActivelyReachable: Boolean = false,
        val connectionType: ConnectionType = ConnectionType.OFFLINE,
        val networkQuality: NetworkQuality = NetworkQuality.OFFLINE,
        val isMetered: Boolean = false,
        val downlinkSpeedKbps: Int = 0,
        val uplinkSpeedKbps: Int = 0,
        val carrierOrNetworkName: String = "Offline",
        val lastStateChangeTimestamp: Long = System.currentTimeMillis()
    ) {
        val isOnline: Boolean get() = isConnected
        val isOffline: Boolean get() = !isConnected

        fun getHumanReadableSummary(): String {
            if (!isConnected) return "Offline (No Connection)"
            val typeStr = connectionType.displayName
            val meteredStr = if (isMetered) "Metered" else "Unmetered"
            return "Online ($typeStr, $meteredStr, ${networkQuality.label})"
        }
    }

    data class TrafficCategoryStats(
        val category: TrafficCategory,
        var totalBytesSent: Long = 0L,
        var totalBytesReceived: Long = 0L,
        var totalOperations: Long = 0L,
        var successfulOperations: Long = 0L,
        var failedOperations: Long = 0L
    )

    data class PeriodUsage(
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L
    ) {
        val totalBytes: Long get() = rxBytes + txBytes
        val formattedRx: String get() = formatBytes(rxBytes)
        val formattedTx: String get() = formatBytes(txBytes)
        val formattedTotal: String get() = formatBytes(totalBytes)
    }

    data class DailyUsageEntry(
        val dateStr: String,
        val dayLabel: String,
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L
    ) {
        val totalBytes: Long get() = rxBytes + txBytes
        val formattedTotal: String get() = formatBytes(totalBytes)
        val formattedRx: String get() = formatBytes(rxBytes)
        val formattedTx: String get() = formatBytes(txBytes)
    }

    data class NetworkUsageReport(
        val today: PeriodUsage = PeriodUsage(),
        val past7Days: PeriodUsage = PeriodUsage(),
        val past30Days: PeriodUsage = PeriodUsage(),
        val allTime: PeriodUsage = PeriodUsage(),
        val installTimestamp: Long = System.currentTimeMillis(),
        val formattedInstallDate: String = "App Installation",
        val dailyBreakdown7Days: List<DailyUsageEntry> = emptyList(),
        val currentDownloadSpeedKbps: Double = 0.0,
        val currentUploadSpeedKbps: Double = 0.0,
        val connectionType: ConnectionType = ConnectionType.OFFLINE,
        val isMetered: Boolean = false,
        val networkQuality: NetworkQuality = NetworkQuality.OFFLINE,
        val categoryStats: List<TrafficCategoryStats> = emptyList()
    )

    data class TrafficLogEntry(
        val id: Long = System.currentTimeMillis(),
        val timestamp: Long = System.currentTimeMillis(),
        val category: TrafficCategory,
        val operationName: String,
        val url: String? = null,
        val httpMethod: String? = null,
        val statusCode: Int? = null,
        val connectionType: ConnectionType,
        val bytesSent: Long,
        val bytesReceived: Long,
        val durationMs: Long,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )

    sealed class NetworkResult<out T> {
        data class Success<T>(val data: T, val bytesTransferred: Long, val durationMs: Long) : NetworkResult<T>()
        data class SkippedOffline(val reason: String = "Device is offline") : NetworkResult<Nothing>()
        data class Failed(val error: Throwable, val durationMs: Long) : NetworkResult<Nothing>()
    }

    interface ConnectivityListener {
        fun onInternetAvailable(networkState: NetworkState)
        fun onInternetLost()
    }

    class NoConnectivityException(message: String = "Device has no active internet connection.") : IOException(message)

    // ---------------------------------------------------------------------------------------------
    // Core Engine State
    // ---------------------------------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var speedMonitorJob: Job? = null
    private var reachabilityJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null
    private var isInitialized = false

    // Reactive StateFlows for UI & Architecture
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isOffline = MutableStateFlow(true)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _connectionType = MutableStateFlow(ConnectionType.OFFLINE)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val _networkQuality = MutableStateFlow(NetworkQuality.OFFLINE)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()

    private val _currentDownloadSpeedKbps = MutableStateFlow(0.0)
    val currentDownloadSpeedKbps: StateFlow<Double> = _currentDownloadSpeedKbps.asStateFlow()

    private val _currentUploadSpeedKbps = MutableStateFlow(0.0)
    val currentUploadSpeedKbps: StateFlow<Double> = _currentUploadSpeedKbps.asStateFlow()

    private val _totalAppRxBytes = MutableStateFlow(0L)
    val totalAppRxBytes: StateFlow<Long> = _totalAppRxBytes.asStateFlow()

    private val _totalAppTxBytes = MutableStateFlow(0L)
    val totalAppTxBytes: StateFlow<Long> = _totalAppTxBytes.asStateFlow()

    // Persistent Data Consumption Storage
    private const val PREFS_NAME = "lifeos_network_traffic_history"
    private const val KEY_INSTALL_TIMESTAMP = "app_install_timestamp"
    private const val KEY_ALL_TIME_RX = "all_time_rx"
    private const val KEY_ALL_TIME_TX = "all_time_tx"
    private const val PREFIX_DAILY_RX = "daily_rx_"
    private const val PREFIX_DAILY_TX = "daily_tx_"

    private val _networkUsageReport = MutableStateFlow(NetworkUsageReport())
    val networkUsageReport: StateFlow<NetworkUsageReport> = _networkUsageReport.asStateFlow()

    // Per Category Stats & Activity Logs
    private val categoryStatsMap = ConcurrentHashMap<TrafficCategory, TrafficCategoryStats>()
    private val recentTrafficLogs = CopyOnWriteArrayList<TrafficLogEntry>()
    private const val MAX_LOGS = 200

    // Registered Listeners and Deferred Offline Action Queue
    private val connectivityListeners = CopyOnWriteArrayList<ConnectivityListener>()
    private val deferredOnlineActions = ConcurrentHashMap<String, suspend () -> Unit>()

    // Baseline Bytes for App Session
    private var initialSessionRxBytes: Long = 0L
    private var initialSessionTxBytes: Long = 0L
    private var lastMeasuredRxBytes: Long = 0L
    private var lastMeasuredTxBytes: Long = 0L
    private var lastSpeedCheckTimestamp: Long = 0L

    // ---------------------------------------------------------------------------------------------
    // OkHttp Central Routing Client & Interceptor
    // ---------------------------------------------------------------------------------------------

    /**
     * Interceptor that routes, measures, inspects, and logs every HTTP request and response
     * flowing through the OkHttpClient.
     */
    class TrafficRoutingInterceptor(
        private val defaultCategory: TrafficCategory = TrafficCategory.GENERAL_NETWORK
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()

            // Determine category from header or fallback
            val categoryHeader = originalRequest.header(HEADER_TRAFFIC_CATEGORY)
            val category = try {
                if (categoryHeader != null) TrafficCategory.valueOf(categoryHeader) else defaultCategory
            } catch (_: Exception) {
                defaultCategory
            }

            val requestBuilder = originalRequest.newBuilder()
                .removeHeader(HEADER_TRAFFIC_CATEGORY)
            val request = requestBuilder.build()

            // 1. Connectivity Check
            val currentContext = appContext
            val online = if (currentContext != null) isConnected(currentContext) else _isOnline.value
            if (!online) {
                val errorMsg = "Blocked by NetworkTrafficManager: Device is offline"
                Log.w(TAG, "--> [OUTBOUND BLOCKED] ${request.method} ${request.url} - $errorMsg")
                recordLogEntry(
                    TrafficLogEntry(
                        category = category,
                        operationName = "HTTP ${request.method}",
                        url = request.url.toString(),
                        httpMethod = request.method,
                        statusCode = null,
                        connectionType = ConnectionType.OFFLINE,
                        bytesSent = 0L,
                        bytesReceived = 0L,
                        durationMs = 0L,
                        isSuccess = false,
                        errorMessage = errorMsg
                    )
                )
                updateCategoryStats(category, bytesSent = 0L, bytesReceived = 0L, isSuccess = false)
                throw NoConnectivityException("No internet connection available for ${request.url}")
            }

            // 2. Measure Outbound Request Size
            var bytesSent = 0L
            try {
                val reqBody = request.body
                if (reqBody != null) {
                    val buffer = Buffer()
                    reqBody.writeTo(buffer)
                    bytesSent = buffer.size
                }
            } catch (e: Throwable) {
                // If body could not be buffered (e.g. one-shot streaming), fallback to contentLength
                bytesSent = request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L
            }

            val startTime = System.currentTimeMillis()
            val connType = _connectionType.value
            Log.d(TAG, "--> [ROUTED OUTBOUND] ${request.method} ${request.url} ($category, Sent: ${bytesSent}B, $connType)")

            // 3. Execute Request
            val response: Response
            try {
                response = chain.proceed(request)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "<-- [ROUTED FAILED] ${request.method} ${request.url} (${duration}ms) - ${e.message}")
                recordLogEntry(
                    TrafficLogEntry(
                        category = category,
                        operationName = "HTTP ${request.method}",
                        url = request.url.toString(),
                        httpMethod = request.method,
                        statusCode = null,
                        connectionType = connType,
                        bytesSent = bytesSent,
                        bytesReceived = 0L,
                        durationMs = duration,
                        isSuccess = false,
                        errorMessage = e.localizedMessage ?: e.javaClass.simpleName
                    )
                )
                updateCategoryStats(category, bytesSent = bytesSent, bytesReceived = 0L, isSuccess = false)
                throw e
            }

            val duration = System.currentTimeMillis() - startTime
            val responseCode = response.code
            val isSuccessCode = response.isSuccessful

            // 4. Wrap ResponseBody with CountingSource to accurately measure inbound received bytes
            val responseBody = response.body
            if (responseBody == null) {
                recordLogEntry(
                    TrafficLogEntry(
                        category = category,
                        operationName = "HTTP ${request.method}",
                        url = request.url.toString(),
                        httpMethod = request.method,
                        statusCode = responseCode,
                        connectionType = connType,
                        bytesSent = bytesSent,
                        bytesReceived = 0L,
                        durationMs = duration,
                        isSuccess = isSuccessCode
                    )
                )
                updateCategoryStats(category, bytesSent, bytesReceived = 0L, isSuccess = isSuccessCode)
                return response
            }

            var recordedBytesReceived = 0L
            val countingSource = object : ForwardingSource(responseBody.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) {
                        recordedBytesReceived += read
                    }
                    if (read == -1L) {
                        // EOF reached: log completion
                        val finalDuration = System.currentTimeMillis() - startTime
                        Log.i(
                            TAG,
                            "<-- [ROUTED INBOUND COMPLETE] $responseCode ${request.url} " +
                                "($category, Rx: ${formatBytes(recordedBytesReceived)}, Tx: ${formatBytes(bytesSent)}, ${finalDuration}ms)"
                        )
                        recordLogEntry(
                            TrafficLogEntry(
                                category = category,
                                operationName = "HTTP ${request.method}",
                                url = request.url.toString(),
                                httpMethod = request.method,
                                statusCode = responseCode,
                                connectionType = connType,
                                bytesSent = bytesSent,
                                bytesReceived = recordedBytesReceived,
                                durationMs = finalDuration,
                                isSuccess = isSuccessCode
                            )
                        )
                        updateCategoryStats(category, bytesSent, recordedBytesReceived, isSuccessCode)
                    }
                    return read
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (recordedBytesReceived > 0 && responseBody.contentLength() != recordedBytesReceived) {
                            // If closed early without reading till EOF
                            recordLogEntry(
                                TrafficLogEntry(
                                    category = category,
                                    operationName = "HTTP ${request.method} (Early Close)",
                                    url = request.url.toString(),
                                    httpMethod = request.method,
                                    statusCode = responseCode,
                                    connectionType = connType,
                                    bytesSent = bytesSent,
                                    bytesReceived = recordedBytesReceived,
                                    durationMs = System.currentTimeMillis() - startTime,
                                    isSuccess = isSuccessCode
                                )
                            )
                            updateCategoryStats(category, bytesSent, recordedBytesReceived, isSuccessCode)
                        }
                    }
                }
            }

            val wrappedBody = object : okhttp3.ResponseBody() {
                override fun contentType() = responseBody.contentType()
                override fun contentLength() = responseBody.contentLength()
                override fun source(): okio.BufferedSource = countingSource.buffer()
            }

            return response.newBuilder()
                .body(wrappedBody)
                .build()
        }
    }

    // Shared Central Routed OkHttpClient
    @Volatile
    private var sharedOkHttpClient: OkHttpClient? = null

    /**
     * Gets the shared, centrally routed OkHttpClient. All requests sent through this client
     * are automatically measured, routed, logged, and guarded against offline failures.
     */
    fun getOkHttpClient(): OkHttpClient {
        return sharedOkHttpClient ?: synchronized(this) {
            sharedOkHttpClient ?: createOkHttpClientBuilder().build().also {
                sharedOkHttpClient = it
            }
        }
    }

    /**
     * Creates an OkHttpClient.Builder pre-configured with NetworkTrafficManager's routing interceptor,
     * sensible connection timeouts, and connection pooling.
     */
    fun createOkHttpClientBuilder(category: TrafficCategory = TrafficCategory.GENERAL_NETWORK): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .addInterceptor(TrafficRoutingInterceptor(category))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }

    init {
        TrafficCategory.values().forEach { cat ->
            categoryStatsMap[cat] = TrafficCategoryStats(cat)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Initialization & Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Initializes the Central Network Traffic Monitor and registers the Connectivity Callback.
     * Safe to call multiple times; idempotently retains application context.
     */
    @Synchronized
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx

        if (isInitialized) {
            updateCurrentNetworkState(appCtx)
            return
        }
        isInitialized = true

        try {
            val myUid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(myUid)
            val tx = TrafficStats.getUidTxBytes(myUid)
            initialSessionRxBytes = if (rx >= 0) rx else 0L
            initialSessionTxBytes = if (tx >= 0) tx else 0L
            lastMeasuredRxBytes = initialSessionRxBytes
            lastMeasuredTxBytes = initialSessionTxBytes
            lastSpeedCheckTimestamp = System.currentTimeMillis()
            _totalAppRxBytes.value = initialSessionRxBytes
            _totalAppTxBytes.value = initialSessionTxBytes

            // Initialize persistent data usage baseline & install timestamp
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_INSTALL_TIMESTAMP)) {
                val installTs = try {
                    appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).firstInstallTime
                } catch (_: Throwable) {
                    System.currentTimeMillis()
                }
                prefs.edit().putLong(KEY_INSTALL_TIMESTAMP, installTs).apply()
            }

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (!prefs.contains(KEY_ALL_TIME_RX) && (initialSessionRxBytes > 0 || initialSessionTxBytes > 0)) {
                prefs.edit()
                    .putLong(KEY_ALL_TIME_RX, initialSessionRxBytes)
                    .putLong(KEY_ALL_TIME_TX, initialSessionTxBytes)
                    .putLong(PREFIX_DAILY_RX + todayStr, initialSessionRxBytes)
                    .putLong(PREFIX_DAILY_TX + todayStr, initialSessionTxBytes)
                    .apply()
            }
            _networkUsageReport.value = buildUsageReport(appCtx)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading initial TrafficStats: ${e.message}")
        }

        // 1. Evaluate Initial Connection
        updateCurrentNetworkState(appCtx)

        // 2. Register Active System Network Callback
        try {
            val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Network Callback onAvailable: $network")
                        updateCurrentNetworkState(appCtx)
                        verifyReachabilityAndNotify(appCtx)
                    }

                    override fun onLost(network: Network) {
                        Log.d(TAG, "Network Callback onLost: $network")
                        updateCurrentNetworkState(appCtx)
                        notifyInternetLost()
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        updateCurrentNetworkState(appCtx)
                    }

                    override fun onUnavailable() {
                        Log.d(TAG, "Network Callback onUnavailable")
                        updateCurrentNetworkState(appCtx)
                        notifyInternetLost()
                    }
                }
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering ConnectivityManager NetworkCallback", e)
        }

        // 3. Launch Continuous Traffic Speed & Byte Poller
        startTrafficSpeedMonitor()

        // 4. Initial Reachability Probe
        verifyReachabilityAndNotify(appCtx)
    }

    // ---------------------------------------------------------------------------------------------
    // Connectivity & Internet State Evaluation
    // ---------------------------------------------------------------------------------------------

    /**
     * Authoritatively re-evaluates network capabilities and updates connection state.
     */
    fun updateCurrentNetworkState(context: Context): NetworkState {
        val appCtx = context.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                val state = NetworkState(isConnected = false, connectionType = ConnectionType.OFFLINE)
                publishNetworkState(state)
                return state
            }

            val activeNetwork = cm.activeNetwork
            if (activeNetwork == null) {
                val state = NetworkState(isConnected = false, connectionType = ConnectionType.OFFLINE)
                publishNetworkState(state)
                return state
            }

            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps == null) {
                val state = NetworkState(isConnected = false, connectionType = ConnectionType.OFFLINE)
                publishNetworkState(state)
                return state
            }

            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val downSpeed = caps.linkDownstreamBandwidthKbps
            val upSpeed = caps.linkUpstreamBandwidthKbps

            val connType = when {
                !hasInternet -> ConnectionType.OFFLINE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> ConnectionType.BLUETOOTH
                else -> ConnectionType.WIFI
            }

            val quality = when {
                !hasInternet -> NetworkQuality.OFFLINE
                downSpeed >= NetworkQuality.EXCELLENT.minDownlinkKbps -> NetworkQuality.EXCELLENT
                downSpeed >= NetworkQuality.GOOD.minDownlinkKbps -> NetworkQuality.GOOD
                downSpeed >= NetworkQuality.MODERATE.minDownlinkKbps -> NetworkQuality.MODERATE
                downSpeed > 0 -> NetworkQuality.POOR
                else -> if (hasInternet) NetworkQuality.GOOD else NetworkQuality.OFFLINE
            }

            val state = NetworkState(
                isConnected = hasInternet,
                isInternetValidated = isValidated,
                isActivelyReachable = hasInternet && isValidated,
                connectionType = connType,
                networkQuality = quality,
                isMetered = isMetered,
                downlinkSpeedKbps = downSpeed,
                uplinkSpeedKbps = upSpeed,
                carrierOrNetworkName = connType.displayName,
                lastStateChangeTimestamp = System.currentTimeMillis()
            )

            publishNetworkState(state)
            return state
        } catch (e: Throwable) {
            Log.e(TAG, "Error calculating network state", e)
            val fallback = NetworkState(isConnected = true, connectionType = ConnectionType.WIFI, networkQuality = NetworkQuality.GOOD)
            publishNetworkState(fallback)
            return fallback
        }
    }

    private fun publishNetworkState(state: NetworkState) {
        val wasOffline = !_isOnline.value
        _networkState.value = state
        _isOnline.value = state.isConnected
        _isOffline.value = !state.isConnected
        _connectionType.value = state.connectionType
        _networkQuality.value = state.networkQuality

        if (state.isConnected && wasOffline) {
            notifyInternetAvailable(state)
            drainDeferredActions()
        }
    }

    /**
     * Asynchronously verifies active internet reachability with a fast socket probe (to 8.8.8.8:53).
     * This catches captive portals, hotel Wi-Fi login screens, and router disconnects.
     */
    fun verifyReachabilityAndNotify(context: Context) {
        reachabilityJob?.cancel()
        reachabilityJob = scope.launch {
            val reachable = checkActiveInternetReachability()
            val current = _networkState.value
            if (current.isConnected) {
                val updated = current.copy(isActivelyReachable = reachable)
                _networkState.value = updated
                if (reachable) {
                    notifyInternetAvailable(updated)
                    drainDeferredActions()
                }
            }
        }
    }

    /**
     * Direct socket check to verify true external connectivity.
     */
    suspend fun checkActiveInternetReachability(timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), timeoutMs)
                true
            }
        } catch (_: Exception) {
            try {
                // Secondary fallback via Cloudflare DNS
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("1.1.1.1", 53), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public Status Inquiries (Telling whether Internet is ON or OFF)
    // ---------------------------------------------------------------------------------------------

    /**
     * Authoritatively tells whether internet is currently ON.
     */
    fun isInternetOn(context: Context? = null): Boolean {
        val ctx = context ?: appContext
        if (ctx != null && !isInitialized) init(ctx)
        return _isOnline.value || (ctx?.let { updateCurrentNetworkState(it).isConnected } ?: false)
    }

    /**
     * Backward-compatible alias for isInternetOn.
     */
    fun isConnected(context: Context? = null): Boolean = isInternetOn(context)

    /**
     * Backward-compatible alias for isInternetOn.
     */
    fun isOnline(context: Context? = null): Boolean = isInternetOn(context)

    /**
     * Authoritatively tells whether internet is currently OFF.
     */
    fun isInternetOff(context: Context? = null): Boolean = !isInternetOn(context)

    /**
     * Synchronously returns the current ConnectionType.
     */
    fun getConnectionType(context: Context? = null): ConnectionType {
        val ctx = context ?: appContext
        if (ctx != null && !isInitialized) init(ctx)
        return _connectionType.value
    }

    /**
     * Synchronously returns whether the current connection is metered.
     */
    fun isMetered(context: Context? = null): Boolean {
        val ctx = context ?: appContext
        if (ctx != null && !isInitialized) init(ctx)
        return _networkState.value.isMetered
    }

    /**
     * Returns the current Network Quality classification.
     */
    fun getNetworkQuality(): NetworkQuality = _networkQuality.value

    /**
     * Returns a human-readable network status summary.
     */
    fun getNetworkStatusSummary(context: Context? = null): String {
        val ctx = context ?: appContext
        if (ctx != null && !isInitialized) init(ctx)
        return _networkState.value.getHumanReadableSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // Informing Other Needs of the App Regarding Internet
    // ---------------------------------------------------------------------------------------------

    /**
     * Gatekeeper check: determines if a specific network operation category can/should execute.
     */
    fun canPerformNetworkTask(context: Context, category: TrafficCategory): Boolean {
        val online = isInternetOn(context)
        if (!online) {
            Log.d(TAG, "Blocking [${category.displayName}]: Device is currently offline.")
            return false
        }
        return true
    }

    /**
     * Informs whether large downloads (e.g. app APK updates, ML weights, large database dumps)
     * are recommended based on metered status and network quality.
     */
    fun canPerformHeavyDownload(context: Context? = null): Boolean {
        val online = isInternetOn(context)
        if (!online) return false
        val metered = isMetered(context)
        // If unmetered Wi-Fi/Ethernet, always allow; if metered, allow only if quality is GOOD/EXCELLENT
        return !metered || (_networkQuality.value == NetworkQuality.EXCELLENT || _networkQuality.value == NetworkQuality.GOOD)
    }

    /**
     * Informs whether background cloud sync should trigger.
     */
    fun canAutoSync(context: Context? = null): Boolean {
        return isInternetOn(context)
    }

    /**
     * Informs whether media players should stream in high fidelity or economize bandwidth.
     */
    fun shouldSaveData(context: Context? = null): Boolean {
        return isMetered(context) || _networkQuality.value == NetworkQuality.POOR
    }

    /**
     * Generates practical user or component guidance regarding current network conditions.
     */
    fun getNetworkAdvice(context: Context? = null): String {
        if (isInternetOff(context)) {
            return "No internet connection. Offline cache and local operations active."
        }
        val type = getConnectionType(context)
        val metered = isMetered(context)
        return when {
            type == ConnectionType.WIFI && !metered -> "High-speed unmetered Wi-Fi. All sync, streaming, and update features unrestricted."
            type == ConnectionType.CELLULAR && metered -> "Connected to mobile data (metered). Background backups and large downloads deferred."
            type == ConnectionType.VPN -> "Secure VPN active. Traffic encrypted."
            else -> "Connected to ${type.displayName}. Network ready."
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Deferred Offline Action Queue
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers an action to automatically execute as soon as the internet turns back ON.
     */
    fun runWhenInternetRestored(tag: String, action: suspend () -> Unit) {
        if (_isOnline.value) {
            scope.launch { action() }
        } else {
            deferredOnlineActions[tag] = action
            Log.d(TAG, "Queued offline action with tag '$tag'. Will run when internet is restored.")
        }
    }

    private fun drainDeferredActions() {
        if (deferredOnlineActions.isEmpty()) return
        Log.i(TAG, "Internet restored! Draining ${deferredOnlineActions.size} deferred offline actions...")
        val actionsToRun = HashMap(deferredOnlineActions)
        deferredOnlineActions.clear()

        scope.launch {
            for ((tag, action) in actionsToRun) {
                try {
                    Log.d(TAG, "Executing deferred action: $tag")
                    action()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error running deferred action '$tag': ${e.message}", e)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Central Network Routing Helpers (GET, POST, Download, URLConnection)
    // ---------------------------------------------------------------------------------------------

    /**
     * Central Routing Gateway for arbitrary suspended network operations (Firebase, Retrofit, Ktor, etc.).
     */
    suspend fun <T> routeOperation(
        context: Context,
        category: TrafficCategory,
        operationName: String,
        block: suspend () -> T
    ): NetworkResult<T> {
        val startTime = System.currentTimeMillis()
        val connType = getConnectionType(context)

        // 1. Offline Interception & Guarding
        if (!isInternetOn(context)) {
            val entry = TrafficLogEntry(
                category = category,
                operationName = operationName,
                connectionType = ConnectionType.OFFLINE,
                bytesSent = 0L,
                bytesReceived = 0L,
                durationMs = 0L,
                isSuccess = false,
                errorMessage = "Blocked: System is OFFLINE"
            )
            recordLogEntry(entry)
            updateCategoryStats(category, bytesSent = 0L, bytesReceived = 0L, isSuccess = false)
            Log.w(TAG, "[$operationName] skipped - Device is offline.")
            return NetworkResult.SkippedOffline("Device is currently offline")
        }

        // 2. Measure Baseline Traffic
        val myUid = Process.myUid()
        val startRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
        val startTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)

        return try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            val endRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
            val endTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)

            val bytesReceived = (endRx - startRx).coerceAtLeast(0L)
            val bytesSent = (endTx - startTx).coerceAtLeast(0L)
            val totalTransferred = bytesReceived + bytesSent

            val entry = TrafficLogEntry(
                category = category,
                operationName = operationName,
                connectionType = connType,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                durationMs = duration,
                isSuccess = true
            )
            recordLogEntry(entry)
            updateCategoryStats(category, bytesSent, bytesReceived, isSuccess = true)

            Log.i(TAG, "[$operationName] SUCCESS ($connType, ${duration}ms, Sent: ${bytesSent}B, Recv: ${bytesReceived}B)")
            NetworkResult.Success(result, totalTransferred, duration)
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            val entry = TrafficLogEntry(
                category = category,
                operationName = operationName,
                connectionType = connType,
                bytesSent = 0L,
                bytesReceived = 0L,
                durationMs = duration,
                isSuccess = false,
                errorMessage = e.localizedMessage ?: e.javaClass.simpleName
            )
            recordLogEntry(entry)
            updateCategoryStats(category, 0L, 0L, isSuccess = false)

            Log.e(TAG, "[$operationName] FAILED ($connType, ${duration}ms): ${e.message}", e)
            NetworkResult.Failed(e, duration)
        }
    }

    /**
     * Opens a standard HttpURLConnection routed through this manager with traffic counting hooks.
     */
    fun openRoutedConnection(
        url: URL,
        category: TrafficCategory = TrafficCategory.GENERAL_NETWORK
    ): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty(HEADER_TRAFFIC_CATEGORY, category.name)
        return connection
    }

    /**
     * Executes a routed HTTP GET request using the central routed OkHttpClient.
     */
    suspend fun executeGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        category: TrafficCategory = TrafficCategory.GENERAL_NETWORK
    ): String = withContext(Dispatchers.IO) {
        val client = getOkHttpClient()
        val requestBuilder = Request.Builder().url(url)
        requestBuilder.header(HEADER_TRAFFIC_CATEGORY, category.name)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code ${response.code} for $url")
            }
            response.body?.string() ?: ""
        }
    }

    /**
     * Executes a routed HTTP POST request using the central routed OkHttpClient.
     */
    suspend fun executePost(
        url: String,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        headers: Map<String, String> = emptyMap(),
        category: TrafficCategory = TrafficCategory.GENERAL_NETWORK
    ): String = withContext(Dispatchers.IO) {
        val client = getOkHttpClient()
        val mediaType = contentType.toMediaTypeOrNull()
        val requestBody = body.toRequestBody(mediaType)
        val requestBuilder = Request.Builder().url(url).post(requestBody)
        requestBuilder.header(HEADER_TRAFFIC_CATEGORY, category.name)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code ${response.code} for POST $url")
            }
            response.body?.string() ?: ""
        }
    }

    /**
     * Direct file downloader that routes through the central manager and provides progress updates.
     */
    suspend fun downloadUrlToFile(
        url: String,
        destinationFile: File,
        category: TrafficCategory = TrafficCategory.APP_UPDATE,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val client = getOkHttpClient()
        val request = Request.Builder()
            .url(url)
            .header(HEADER_TRAFFIC_CATEGORY, category.name)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            onProgress?.invoke(totalRead, totalBytes)
                        }
                        output.flush()
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadUrlToFile failed for $url: ${e.message}", e)
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Traffic Recording & Speed Monitoring
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts the continuous background speed & byte poller.
     */
    private fun startTrafficSpeedMonitor() {
        speedMonitorJob?.cancel()
        speedMonitorJob = scope.launch {
            val myUid = Process.myUid()
            while (isActive) {
                delay(2000L)
                try {
                    val currentRx = TrafficStats.getUidRxBytes(myUid)
                    val currentTx = TrafficStats.getUidTxBytes(myUid)
                    val now = System.currentTimeMillis()

                    if (currentRx >= 0 && currentTx >= 0 && lastSpeedCheckTimestamp > 0) {
                        val timeDeltaSec = (now - lastSpeedCheckTimestamp) / 1000.0
                        if (timeDeltaSec > 0.5) {
                            val rxDelta = if (currentRx >= lastMeasuredRxBytes) (currentRx - lastMeasuredRxBytes) else currentRx
                            val txDelta = if (currentTx >= lastMeasuredTxBytes) (currentTx - lastMeasuredTxBytes) else currentTx

                            val downKbps = (rxDelta * 8.0) / (timeDeltaSec * 1024.0)
                            val upKbps = (txDelta * 8.0) / (timeDeltaSec * 1024.0)

                            _currentDownloadSpeedKbps.value = (Math.round(downKbps * 10.0) / 10.0)
                            _currentUploadSpeedKbps.value = (Math.round(upKbps * 10.0) / 10.0)

                            _totalAppRxBytes.value = currentRx
                            _totalAppTxBytes.value = currentTx

                            lastMeasuredRxBytes = currentRx
                            lastMeasuredTxBytes = currentTx
                            lastSpeedCheckTimestamp = now

                            if (rxDelta > 0L || txDelta > 0L) {
                                appContext?.let { ctx ->
                                    recordBytesConsumed(ctx, rxDelta, txDelta)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // Ignore transient stat reading errors
                }
            }
        }
    }

    /**
     * Records explicit traffic bytes manually (e.g. from external chunks or media streams).
     */
    fun recordTraffic(
        category: TrafficCategory,
        operationName: String,
        bytesSent: Long,
        bytesReceived: Long,
        isSuccess: Boolean = true,
        errorMessage: String? = null
    ) {
        val entry = TrafficLogEntry(
            category = category,
            operationName = operationName,
            connectionType = _connectionType.value,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            durationMs = 0L,
            isSuccess = isSuccess,
            errorMessage = errorMessage
        )
        recordLogEntry(entry)
        updateCategoryStats(category, bytesSent, bytesReceived, isSuccess)
    }

    private fun updateCategoryStats(category: TrafficCategory, bytesSent: Long, bytesReceived: Long, isSuccess: Boolean) {
        val stats = categoryStatsMap.getOrPut(category) { TrafficCategoryStats(category) }
        synchronized(stats) {
            stats.totalBytesSent += bytesSent
            stats.totalBytesReceived += bytesReceived
            stats.totalOperations += 1
            if (isSuccess) {
                stats.successfulOperations += 1
            } else {
                stats.failedOperations += 1
            }
        }
    }

    private fun recordLogEntry(entry: TrafficLogEntry) {
        recentTrafficLogs.add(0, entry)
        while (recentTrafficLogs.size > MAX_LOGS) {
            recentTrafficLogs.removeAt(recentTrafficLogs.size - 1)
        }
    }

    fun getCategoryStats(category: TrafficCategory): TrafficCategoryStats {
        return categoryStatsMap.getOrPut(category) { TrafficCategoryStats(category) }
    }

    fun getAllCategoryStats(): List<TrafficCategoryStats> {
        return categoryStatsMap.values.toList()
    }

    fun getRecentTrafficLogs(): List<TrafficLogEntry> {
        return recentTrafficLogs.toList()
    }

    fun clearTrafficLogs() {
        recentTrafficLogs.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Connectivity Listeners
    // ---------------------------------------------------------------------------------------------

    fun addConnectivityListener(listener: ConnectivityListener) {
        if (!connectivityListeners.contains(listener)) {
            connectivityListeners.add(listener)
            // Immediately notify with current state
            if (_isOnline.value) {
                listener.onInternetAvailable(_networkState.value)
            } else {
                listener.onInternetLost()
            }
        }
    }

    fun removeConnectivityListener(listener: ConnectivityListener) {
        connectivityListeners.remove(listener)
    }

    private fun notifyInternetAvailable(state: NetworkState) {
        for (listener in connectivityListeners) {
            try {
                listener.onInternetAvailable(state)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in ConnectivityListener.onInternetAvailable: ${e.message}")
            }
        }
    }

    private fun notifyInternetLost() {
        for (listener in connectivityListeners) {
            try {
                listener.onInternetLost()
            } catch (e: Throwable) {
                Log.e(TAG, "Error in ConnectivityListener.onInternetLost: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Jetpack Compose Integration Hooks
    // ---------------------------------------------------------------------------------------------

    @Composable
    fun rememberIsInternetOn(): State<Boolean> {
        return isOnline.collectAsState()
    }

    @Composable
    fun rememberNetworkState(): State<NetworkState> {
        return networkState.collectAsState()
    }

    @Composable
    fun rememberNetworkUsageReport(context: Context): State<NetworkUsageReport> {
        // Trigger initial refresh
        getUsageReport(context)
        return networkUsageReport.collectAsState()
    }

    // ---------------------------------------------------------------------------------------------
    // Data Consumption & Persistent Usage Accounting
    // ---------------------------------------------------------------------------------------------

    /**
     * Records bytes consumed to persistent SharedPreferences storage.
     * Updates daily buckets and all-time cumulative counters.
     */
    @Synchronized
    fun recordBytesConsumed(context: Context, deltaRx: Long, deltaTx: Long) {
        if (deltaRx <= 0L && deltaTx <= 0L) return
        val appCtx = context.applicationContext
        try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val curTodayRx = prefs.getLong(PREFIX_DAILY_RX + todayStr, 0L)
            val curTodayTx = prefs.getLong(PREFIX_DAILY_TX + todayStr, 0L)
            val curAllRx = prefs.getLong(KEY_ALL_TIME_RX, 0L)
            val curAllTx = prefs.getLong(KEY_ALL_TIME_TX, 0L)

            prefs.edit()
                .putLong(PREFIX_DAILY_RX + todayStr, curTodayRx + deltaRx)
                .putLong(PREFIX_DAILY_TX + todayStr, curTodayTx + deltaTx)
                .putLong(KEY_ALL_TIME_RX, curAllRx + deltaRx)
                .putLong(KEY_ALL_TIME_TX, curAllTx + deltaTx)
                .apply()

            _networkUsageReport.value = buildUsageReport(appCtx)
        } catch (e: Throwable) {
            Log.w(TAG, "Error persisting consumed bytes: ${e.message}")
        }
    }

    /**
     * Flushes real-time kernel TrafficStats deltas immediately into persistent storage.
     * Ensures reported usage numbers are strictly up to the millisecond.
     */
    @Synchronized
    fun flushTrafficStats(context: Context? = null) {
        val appCtx = context?.applicationContext ?: appContext ?: return
        try {
            val myUid = Process.myUid()
            val currentRx = TrafficStats.getUidRxBytes(myUid)
            val currentTx = TrafficStats.getUidTxBytes(myUid)

            if (currentRx >= 0 && currentTx >= 0) {
                if (lastMeasuredRxBytes == 0L && lastMeasuredTxBytes == 0L) {
                    lastMeasuredRxBytes = currentRx
                    lastMeasuredTxBytes = currentTx
                    return
                }

                val deltaRx = if (currentRx >= lastMeasuredRxBytes) {
                    currentRx - lastMeasuredRxBytes
                } else {
                    currentRx // Device rebooted, start fresh delta
                }

                val deltaTx = if (currentTx >= lastMeasuredTxBytes) {
                    currentTx - lastMeasuredTxBytes
                } else {
                    currentTx // Device rebooted, start fresh delta
                }

                if (deltaRx > 0L || deltaTx > 0L) {
                    lastMeasuredRxBytes = currentRx
                    lastMeasuredTxBytes = currentTx
                    recordBytesConsumed(appCtx, deltaRx, deltaTx)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error flushing traffic stats: ${e.message}")
        }
    }

    /**
     * Builds the comprehensive Network Usage Report:
     * - Today's usage
     * - Past 7 days usage
     * - Past 30 days usage
     * - All over (Total since app installation)
     */
    fun buildUsageReport(context: Context): NetworkUsageReport {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Ensure install timestamp exists
        val installTs = if (prefs.contains(KEY_INSTALL_TIMESTAMP)) {
            prefs.getLong(KEY_INSTALL_TIMESTAMP, System.currentTimeMillis())
        } else {
            val ts = try {
                appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).firstInstallTime
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
            prefs.edit().putLong(KEY_INSTALL_TIMESTAMP, ts).apply()
            ts
        }

        val installFormatted = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(installTs))
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        // 1. Today's usage
        val todayStr = sdf.format(Date())
        val todayRx = prefs.getLong(PREFIX_DAILY_RX + todayStr, 0L)
        val todayTx = prefs.getLong(PREFIX_DAILY_TX + todayStr, 0L)
        val todayUsage = PeriodUsage(rxBytes = todayRx, txBytes = todayTx)

        // 2. Past 7 days usage + Daily breakdown
        var past7Rx = 0L
        var past7Tx = 0L
        val breakdown7 = mutableListOf<DailyUsageEntry>()

        for (i in 0 until 7) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dStr = sdf.format(cal.time)
            val dRx = prefs.getLong(PREFIX_DAILY_RX + dStr, 0L)
            val dTx = prefs.getLong(PREFIX_DAILY_TX + dStr, 0L)
            past7Rx += dRx
            past7Tx += dTx

            val label = when (i) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(cal.time)
            }
            breakdown7.add(DailyUsageEntry(dateStr = dStr, dayLabel = label, rxBytes = dRx, txBytes = dTx))
        }

        // 3. Past 30 days usage
        var past30Rx = 0L
        var past30Tx = 0L
        for (i in 0 until 30) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dStr = sdf.format(cal.time)
            past30Rx += prefs.getLong(PREFIX_DAILY_RX + dStr, 0L)
            past30Tx += prefs.getLong(PREFIX_DAILY_TX + dStr, 0L)
        }

        // 4. All over (in total of app installation)
        val storedAllRx = prefs.getLong(KEY_ALL_TIME_RX, 0L)
        val storedAllTx = prefs.getLong(KEY_ALL_TIME_TX, 0L)
        // All time must always be >= 30-day sum, 7-day sum, and today
        val allTimeRx = maxOf(storedAllRx, past30Rx, past7Rx, todayRx)
        val allTimeTx = maxOf(storedAllTx, past30Tx, past7Tx, todayTx)

        val state = _networkState.value

        return NetworkUsageReport(
            today = todayUsage,
            past7Days = PeriodUsage(rxBytes = past7Rx, txBytes = past7Tx),
            past30Days = PeriodUsage(rxBytes = past30Rx, txBytes = past30Tx),
            allTime = PeriodUsage(rxBytes = allTimeRx, txBytes = allTimeTx),
            installTimestamp = installTs,
            formattedInstallDate = installFormatted,
            dailyBreakdown7Days = breakdown7,
            currentDownloadSpeedKbps = _currentDownloadSpeedKbps.value,
            currentUploadSpeedKbps = _currentUploadSpeedKbps.value,
            connectionType = state.connectionType,
            isMetered = state.isMetered,
            networkQuality = state.networkQuality,
            categoryStats = getAllCategoryStats()
        )
    }

    /**
     * Synchronously returns an updated NetworkUsageReport after flushing kernel traffic deltas.
     */
    fun getUsageReport(context: Context? = null): NetworkUsageReport {
        val appCtx = context?.applicationContext ?: appContext
        if (appCtx != null) {
            flushTrafficStats(appCtx)
            val report = buildUsageReport(appCtx)
            _networkUsageReport.value = report
            return report
        }
        return _networkUsageReport.value
    }

    /**
     * Resets data usage counters if user explicitly requests it.
     */
    fun resetUsageStats(context: Context) {
        val appCtx = context.applicationContext
        try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().putLong(KEY_INSTALL_TIMESTAMP, System.currentTimeMillis()).apply()
            val myUid = Process.myUid()
            lastMeasuredRxBytes = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0L)
            lastMeasuredTxBytes = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0L)
            _networkUsageReport.value = buildUsageReport(appCtx)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed resetting usage stats: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    /**
     * Formats bytes into a human-readable string (e.g. "1.4 MB", "520 KB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
}
