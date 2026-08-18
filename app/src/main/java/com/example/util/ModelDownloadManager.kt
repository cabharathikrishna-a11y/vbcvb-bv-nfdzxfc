package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.LocalAiModel
import com.example.data.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class DownloadSpeedMode(
    val label: String,
    val description: String,
    val icon: String,
    val bufferSizeBytes: Int,
    val chunkDelayMs: Long
) {
    FAST(
        label = "Fast (Turbo)",
        description = "Maximum bandwidth & priority throughput for fastest download",
        icon = "🚀",
        bufferSizeBytes = 512 * 1024,
        chunkDelayMs = 0L
    ),
    MEDIUM(
        label = "Medium (Balanced)",
        description = "Balanced network usage & steady background download",
        icon = "⚡",
        bufferSizeBytes = 128 * 1024,
        chunkDelayMs = 4L
    ),
    SLOW(
        label = "Slow (Eco Saver)",
        description = "Conserves battery & data bandwidth for other apps",
        icon = "🔋",
        bufferSizeBytes = 32 * 1024,
        chunkDelayMs = 25L
    )
}

data class StorageCheckResult(
    val hasEnoughSpace: Boolean,
    val freeStorageGb: Double,
    val requiredStorageGb: Double = 1.3,
    val deficitGb: Double = 0.0
)

data class DownloadProgressState(
    val modelId: String? = null,
    val progress: Float = 0f, // 0.0 to 1.0
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedMBps: Double = 0.0,
    val etaSeconds: Long = 0L,
    val speedMode: DownloadSpeedMode = DownloadSpeedMode.FAST,
    val statusText: String = "",
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null
)

object ModelDownloadManager {

    private const val NOTIF_CHANNEL_ID = "lifeos_ai_model_download"
    private const val NOTIF_CHANNEL_NAME = "Life OS AI Model Downloader"
    private const val NOTIFICATION_ID = 10099

    private val _downloadState = MutableStateFlow(DownloadProgressState())
    val downloadState: StateFlow<DownloadProgressState> = _downloadState.asStateFlow()

    private var activeJob: Job? = null

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "ai_models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isFlagshipModelDownloaded(context: Context): Boolean {
        return isModelDownloaded(context, ModelRepository.FLAGSHIP_QWEN_CODER)
    }

    fun isModelDownloaded(context: Context, model: LocalAiModel): Boolean {
        val file = File(getModelsDir(context), model.fileName)
        return file.exists() && file.length() > 50 * 1024 * 1024 // at least 50MB
    }

    fun getDownloadedModelFile(context: Context, model: LocalAiModel): File? {
        val file = File(getModelsDir(context), model.fileName)
        return if (file.exists() && file.length() > 50 * 1024 * 1024) file else null
    }

    fun checkStorageSpace(context: Context, minRequiredGb: Double = 1.3): StorageCheckResult {
        val specs = DeviceSpecsManager.getDeviceSpecs(context)
        val freeGb = specs.freeStorageGb
        val hasEnough = freeGb >= minRequiredGb
        val deficit = if (hasEnough) 0.0 else ((minRequiredGb - freeGb) * 10).let { Math.round(it) / 10.0 }
        return StorageCheckResult(
            hasEnoughSpace = hasEnough,
            freeStorageGb = freeGb,
            requiredStorageGb = minRequiredGb,
            deficitGb = deficit
        )
    }

    fun deleteModel(context: Context, model: LocalAiModel): Boolean {
        val file = File(getModelsDir(context), model.fileName)
        val tempFile = File(getModelsDir(context), "${model.fileName}.download")
        val d1 = if (file.exists()) file.delete() else false
        val d2 = if (tempFile.exists()) tempFile.delete() else false
        return d1 || d2
    }

    fun cancelDownload(context: Context? = null) {
        activeJob?.cancel()
        activeJob = null
        _downloadState.value = DownloadProgressState(
            isDownloading = false,
            statusText = "Download paused."
        )
        if (context != null) {
            cancelNotification(context)
        }
    }

    fun startDownload(
        context: Context,
        model: LocalAiModel = ModelRepository.FLAGSHIP_QWEN_CODER,
        speedMode: DownloadSpeedMode = DownloadSpeedMode.FAST,
        coroutineScope: CoroutineScope,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (_downloadState.value.isDownloading) {
            onComplete(false, "Download is already in progress.")
            return
        }

        val destFile = File(getModelsDir(context), model.fileName)
        val tempFile = File(getModelsDir(context), "${model.fileName}.download")

        if (destFile.exists() && destFile.length() > 50 * 1024 * 1024) {
            _downloadState.value = DownloadProgressState(
                modelId = model.id,
                progress = 1.0f,
                speedMode = speedMode,
                bytesDownloaded = destFile.length(),
                totalBytes = destFile.length(),
                statusText = "${model.name} is ready on device.",
                isDownloading = false,
                isCompleted = true
            )
            onComplete(true, null)
            return
        }

        // Storage Check
        val storageCheck = checkStorageSpace(context, minRequiredGb = model.minStorageGb)
        if (!storageCheck.hasEnoughSpace) {
            val err = "Insufficient storage: ${storageCheck.freeStorageGb} GB available, need at least ${storageCheck.requiredStorageGb} GB (deficit ${storageCheck.deficitGb} GB)."
            _downloadState.value = DownloadProgressState(
                modelId = model.id,
                error = err,
                statusText = err,
                isDownloading = false
            )
            onComplete(false, err)
            return
        }

        val initialExistingBytes = if (tempFile.exists()) tempFile.length() else 0L

        _downloadState.value = DownloadProgressState(
            modelId = model.id,
            progress = if (initialExistingBytes > 0) (initialExistingBytes.toFloat() / (model.sizeGb * 1024 * 1024 * 1024).toFloat()).coerceIn(0f, 0.99f) else 0f,
            bytesDownloaded = initialExistingBytes,
            totalBytes = (model.sizeGb * 1024 * 1024 * 1024).toLong(),
            speedMode = speedMode,
            statusText = if (initialExistingBytes > 0) "Resuming download (${initialExistingBytes / (1024 * 1024)} MB already saved)..." else "Connecting to repository (${speedMode.label})...",
            isDownloading = true
        )

        createNotificationChannel(context)
        updateNotification(context, model.name, 0, "Connecting...")

        activeJob = coroutineScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var totalExpectedBytes = (model.sizeGb * 1024 * 1024 * 1024).toLong()

            try {
                var currentUrl = model.downloadUrl
                var redirects = 0
                var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                var isResumed = false

                while (redirects < 8) {
                    val url = URL(currentUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 25000
                        readTimeout = 45000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LifeOS/2.0")
                        if (existingBytes > 0) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }

                    val code = try {
                        conn.responseCode
                    } catch (e: Exception) {
                        conn.disconnect()
                        throw e
                    }

                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308 || code == 303) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (!location.isNullOrBlank()) {
                            currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                location
                            } else {
                                URL(URL(currentUrl), location).toString()
                            }
                            redirects++
                            continue
                        }
                    }

                    connection = conn
                    break
                }

                var responseCode = connection?.responseCode ?: 0

                // If 416 Range Not Satisfiable, temp file might be corrupted or full, reset and reconnect from 0
                if (responseCode == 416) {
                    connection?.disconnect()
                    tempFile.delete()
                    existingBytes = 0L
                    val freshConn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 25000
                        readTimeout = 45000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LifeOS/2.0")
                    }
                    connection = freshConn
                    responseCode = freshConn.responseCode
                }

                if (responseCode == 206) {
                    isResumed = true
                } else if (responseCode in 200..299) {
                    isResumed = false
                    existingBytes = 0L
                } else {
                    throw Exception("Server returned HTTP $responseCode")
                }

                val streamLength = connection?.contentLengthLong?.takeIf { it > 0 } ?: -1L
                totalExpectedBytes = if (isResumed) {
                    if (streamLength > 0) existingBytes + streamLength else (model.sizeGb * 1024 * 1024 * 1024).toLong()
                } else {
                    if (streamLength > 0) streamLength else (model.sizeGb * 1024 * 1024 * 1024).toLong()
                }

                val startOffset = if (isResumed) existingBytes else 0L
                var totalBytesOnDisk = startOffset

                inputStream = connection!!.inputStream
                outputStream = FileOutputStream(tempFile, isResumed)

                val buffer = ByteArray(speedMode.bufferSizeBytes)
                var bytesRead: Int
                var bytesReadThisSession = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytesRead = 0L
                var lastNotifUpdate = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (activeJob?.isActive != true) {
                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()
                        cancelNotification(context)
                        return@launch
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    bytesReadThisSession += bytesRead
                    totalBytesOnDisk = startOffset + bytesReadThisSession

                    if (speedMode.chunkDelayMs > 0) {
                        delay(speedMode.chunkDelayMs)
                    }

                    val now = System.currentTimeMillis()
                    val timeDiffSec = (now - lastTime) / 1000.0

                    var speedMBps = _downloadState.value.speedMBps
                    var etaSec = _downloadState.value.etaSeconds

                    if (timeDiffSec >= 0.5) {
                        val bytesSinceLast = bytesReadThisSession - lastBytesRead
                        speedMBps = ((bytesSinceLast / (1024.0 * 1024.0)) / timeDiffSec).let { Math.round(it * 10) / 10.0 }
                        val remainingBytes = maxOf(0L, totalExpectedBytes - totalBytesOnDisk)
                        etaSec = if (speedMBps > 0.05) (remainingBytes / (speedMBps * 1024.0 * 1024.0)).toLong() else 0L
                        lastTime = now
                        lastBytesRead = bytesReadThisSession
                    }

                    val progress = if (totalExpectedBytes > 0) {
                        (totalBytesOnDisk.toDouble() / totalExpectedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else 0.5f

                    val mbRead = (totalBytesOnDisk / (1024.0 * 1024.0)).toInt()
                    val totalMb = (totalExpectedBytes / (1024.0 * 1024.0)).toInt()

                    val statusStr = "Downloading $mbRead MB / $totalMb MB ($speedMBps MB/s)"

                    _downloadState.value = DownloadProgressState(
                        modelId = model.id,
                        progress = progress,
                        bytesDownloaded = totalBytesOnDisk,
                        totalBytes = totalExpectedBytes,
                        speedMBps = speedMBps,
                        etaSeconds = etaSec,
                        speedMode = speedMode,
                        statusText = statusStr,
                        isDownloading = true
                    )

                    if (now - lastNotifUpdate > 1000) {
                        lastNotifUpdate = now
                        val pct = (progress * 100).toInt()
                        val etaText = if (etaSec > 0) " • ETA: ${formatEta(etaSec)}" else ""
                        updateNotification(context, model.name, pct, "$pct% • $speedMBps MB/s$etaText")
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                val finalFileSize = tempFile.length()
                val minAcceptableBytes = if (totalExpectedBytes > 0) {
                    (totalExpectedBytes * 0.95).toLong()
                } else {
                    (model.sizeGb * 1024 * 1024 * 1024 * 0.85).toLong()
                }

                if (finalFileSize < minAcceptableBytes || finalFileSize < 30 * 1024 * 1024) {
                    val downloadedMb = finalFileSize / (1024 * 1024)
                    val expectedMb = totalExpectedBytes / (1024 * 1024)
                    throw Exception("Download incomplete: received $downloadedMb MB of $expectedMb MB. Tap to resume download.")
                }

                if (destFile.exists()) destFile.delete()
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                if (!destFile.exists() || destFile.length() < 30 * 1024 * 1024) {
                    throw Exception("Failed to write model file to storage.")
                }

                _downloadState.value = DownloadProgressState(
                    modelId = model.id,
                    progress = 1.0f,
                    speedMode = speedMode,
                    bytesDownloaded = destFile.length(),
                    totalBytes = destFile.length(),
                    statusText = "${model.name} ready to run offline!",
                    isDownloading = false,
                    isCompleted = true
                )

                updateNotification(context, model.name, 100, "Download complete! AI is ready offline.", isComplete = true)

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                try {
                    outputStream?.flush()
                    outputStream?.close()
                    inputStream?.close()
                } catch (_: Exception) {}

                val currentSavedBytes = if (tempFile.exists()) tempFile.length() else 0L
                val savedMb = currentSavedBytes / (1024 * 1024)
                val baseMsg = e.localizedMessage ?: "Network interrupted"
                val errMsg = if (savedMb > 0) {
                    "Interrupted at $savedMb MB ($baseMsg). Tap Download to resume."
                } else {
                    baseMsg
                }

                _downloadState.value = DownloadProgressState(
                    modelId = model.id,
                    progress = if (totalExpectedBytes > 0) (currentSavedBytes.toFloat() / totalExpectedBytes.toFloat()).coerceIn(0f, 0.99f) else 0f,
                    bytesDownloaded = currentSavedBytes,
                    totalBytes = totalExpectedBytes,
                    speedMode = speedMode,
                    statusText = errMsg,
                    isDownloading = false,
                    error = errMsg
                )
                cancelNotification(context)
                withContext(Dispatchers.Main) {
                    onComplete(false, errMsg)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun formatEta(seconds: Long): String {
        return if (seconds >= 60) {
            val mins = seconds / 60
            val secs = seconds % 60
            "${mins}m ${secs}s"
        } else {
            "${seconds}s"
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress for offline AI model weights."
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        context: Context,
        modelName: String,
        progressPercent: Int,
        subText: String,
        isComplete: Boolean = false
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (isComplete) "AI Model Ready" else "Downloading $modelName")
                .setContentText(subText)
                .setContentIntent(pendingIntent)
                .setOngoing(!isComplete)
                .setOnlyAlertOnce(true)

            if (!isComplete) {
                builder.setProgress(100, progressPercent, false)
            } else {
                builder.setProgress(0, 0, false)
                builder.setAutoCancel(true)
            }

            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    private fun cancelNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
