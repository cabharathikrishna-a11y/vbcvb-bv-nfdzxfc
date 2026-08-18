package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dedicated Central Network & Traffic Controller
 * 
 * Monitors all inbound & outbound internet traffic, detects real-time connection status
 * (WiFi, Mobile Cellular Data, Ethernet, VPN, Offline), tracks per-category bandwidth consumption,
 * and acts as the single central gatekeeper for all network-requiring features (Updates, Sync,
 * Backup, Media, Notifications, etc.).
 */
object NetworkTrafficManager {
    private const val TAG = "NetworkTrafficManager"

    enum class ConnectionType(val displayName: String, val icon: String) {
        WIFI("Wi-Fi", "wifi"),
        CELLULAR("Mobile Data", "signal_cellular_alt"),
        ETHERNET("Ethernet", "lan"),
        VPN("VPN Encrypted", "vpn_key"),
        BLUETOOTH("Bluetooth Tether", "bluetooth"),
        OFFLINE("Offline (No Connection)", "cloud_off")
    }

    enum class TrafficCategory(val displayName: String) {
        APP_UPDATE("App Updates & Patches"),
        DATABASE_SYNC("Database & Cloud Sync"),
        CLOUD_BACKUP("Cloud Backup & Drive"),
        PUSH_NOTIFICATION("Push & Urgent Notifications"),
        MEDIA_STREAMING("Media & Spotify Audio"),
        AUTHENTICATION("Authentication & Tokens"),
        OUTBOX_DRAIN("Offline Outbox Drain"),
        GENERAL_NETWORK("General Internet Traffic")
    }

    data class NetworkState(
        val isConnected: Boolean = false,
        val isInternetValidated: Boolean = false,
        val connectionType: ConnectionType = ConnectionType.OFFLINE,
        val isMetered: Boolean = false,
        val downlinkSpeedKbps: Int = 0,
        val uplinkSpeedKbps: Int = 0,
        val carrierOrNetworkName: String = "Unknown",
        val lastStateChangeTimestamp: Long = System.currentTimeMillis()
    )

    data class TrafficCategoryStats(
        val category: TrafficCategory,
        var totalBytesSent: Long = 0L,
        var totalBytesReceived: Long = 0L,
        var totalOperations: Long = 0L,
        var successfulOperations: Long = 0L,
        var failedOperations: Long = 0L
    )

    data class TrafficLogEntry(
        val id: Long = System.currentTimeMillis(),
        val timestamp: Long = System.currentTimeMillis(),
        val category: TrafficCategory,
        val operationName: String,
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

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var speedMonitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isInitialized = false

    // Reactive StateFlows for UI & Architecture
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _connectionType = MutableStateFlow(ConnectionType.OFFLINE)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val _currentDownloadSpeedKbps = MutableStateFlow(0.0)
    val currentDownloadSpeedKbps: StateFlow<Double> = _currentDownloadSpeedKbps.asStateFlow()

    private val _currentUploadSpeedKbps = MutableStateFlow(0.0)
    val currentUploadSpeedKbps: StateFlow<Double> = _currentUploadSpeedKbps.asStateFlow()

    private val _totalAppRxBytes = MutableStateFlow(0L)
    val totalAppRxBytes: StateFlow<Long> = _totalAppRxBytes.asStateFlow()

    private val _totalAppTxBytes = MutableStateFlow(0L)
    val totalAppTxBytes: StateFlow<Long> = _totalAppTxBytes.asStateFlow()

    // Per Category Stats & Activity Logs
    private val categoryStatsMap = ConcurrentHashMap<TrafficCategory, TrafficCategoryStats>()
    private val recentTrafficLogs = CopyOnWriteArrayList<TrafficLogEntry>()
    private const val MAX_LOGS = 150

    // Baseline Bytes for App Session
    private var initialSessionRxBytes: Long = 0L
    private var initialSessionTxBytes: Long = 0L
    private var lastMeasuredRxBytes: Long = 0L
    private var lastMeasuredTxBytes: Long = 0L
    private var lastSpeedCheckTimestamp: Long = 0L

    init {
        TrafficCategory.values().forEach { cat ->
            categoryStatsMap[cat] = TrafficCategoryStats(cat)
        }
    }

    /**
     * Initializes the Central Network Traffic Monitor and registers the Connectivity Callback.
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext
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
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading initial TrafficStats: ${e.message}")
        }

        // 1. Evaluate Initial Connection
        updateCurrentNetworkState(appContext)

        // 2. Register Active System Network Callback
        try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Network Available: $network")
                        updateCurrentNetworkState(appContext)
                    }

                    override fun onLost(network: Network) {
                        Log.d(TAG, "Network Lost: $network")
                        updateCurrentNetworkState(appContext)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        updateCurrentNetworkState(appContext)
                    }

                    override fun onUnavailable() {
                        Log.d(TAG, "Network Unavailable")
                        updateCurrentNetworkState(appContext)
                    }
                }
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering ConnectivityManager NetworkCallback", e)
        }

        // 3. Launch Continuous Traffic Speed & Byte Poller
        startTrafficSpeedMonitor()
    }

    /**
     * Re-evaluates network capabilities and updates connection state.
     */
    fun updateCurrentNetworkState(context: Context): NetworkState {
        val appContext = context.applicationContext
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> ConnectionType.BLUETOOTH
                hasInternet -> ConnectionType.WIFI
                else -> ConnectionType.OFFLINE
            }

            val state = NetworkState(
                isConnected = hasInternet,
                isInternetValidated = isValidated,
                connectionType = connType,
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
            val fallback = NetworkState(isConnected = true, connectionType = ConnectionType.WIFI)
            publishNetworkState(fallback)
            return fallback
        }
    }

    private fun publishNetworkState(state: NetworkState) {
        _networkState.value = state
        _isOnline.value = state.isConnected
        _connectionType.value = state.connectionType
    }

    /**
     * Starts the lightweight periodic speed & byte counter coroutine.
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
                            val rxDelta = (currentRx - lastMeasuredRxBytes).coerceAtLeast(0L)
                            val txDelta = (currentTx - lastMeasuredTxBytes).coerceAtLeast(0L)

                            val downKbps = (rxDelta * 8.0) / (timeDeltaSec * 1024.0)
                            val upKbps = (txDelta * 8.0) / (timeDeltaSec * 1024.0)

                            _currentDownloadSpeedKbps.value = (Math.round(downKbps * 10.0) / 10.0)
                            _currentUploadSpeedKbps.value = (Math.round(upKbps * 10.0) / 10.0)

                            _totalAppRxBytes.value = currentRx
                            _totalAppTxBytes.value = currentTx

                            lastMeasuredRxBytes = currentRx
                            lastMeasuredTxBytes = currentTx
                            lastSpeedCheckTimestamp = now
                        }
                    }
                } catch (e: Throwable) {
                    // Ignore transient traffic stat reading errors
                }
            }
        }
    }

    /**
     * Checks if the device has an active internet connection.
     */
    fun isConnected(context: Context): Boolean {
        if (!isInitialized) init(context)
        return _isOnline.value || updateCurrentNetworkState(context).isConnected
    }

    /**
     * Synchronously returns current ConnectionType.
     */
    fun getConnectionType(context: Context): ConnectionType {
        if (!isInitialized) init(context)
        return _connectionType.value
    }

    /**
     * Synchronously returns whether the current connection is metered (Cellular / Metered Wi-Fi).
     */
    fun isMetered(context: Context): Boolean {
        if (!isInitialized) init(context)
        return _networkState.value.isMetered
    }

    /**
     * Determines whether a specific network operation can/should execute.
     */
    fun canPerformNetworkTask(context: Context, category: TrafficCategory): Boolean {
        val online = isConnected(context)
        if (!online) {
            Log.d(TAG, "Blocking [${category.displayName}]: System is currently offline.")
            return false
        }
        return true
    }

    /**
     * Central Routing Gateway for all internet operations (Updates, Sync, Backup, Media, Notifications).
     * Automatically measures bytes in/out, records timings, intercepts offline conditions, and updates telemetry.
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
        if (!isConnected(context)) {
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
     * Records explicit traffic bytes manually (e.g. from known download chunk sizes or media streams).
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
