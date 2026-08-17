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
        return if (file.exists()) {
            file.delete()
        } else false
    }

    fun cancelDownload(context: Context? = null) {
        activeJob?.cancel()
        activeJob = null
        _downloadState.value = DownloadProgressState(
            isDownloading = false,
            statusText = "Download cancelled."
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

        val destFile = File(getModelsDir(context), model.fileName)
        val tempFile = File(getModelsDir(context), "${model.fileName}.download")

        _downloadState.value = DownloadProgressState(
            modelId = model.id,
            progress = 0f,
            speedMode = speedMode,
            statusText = "Connecting to repository (${speedMode.label})...",
            isDownloading = true
        )

        createNotificationChannel(context)
        updateNotification(context, model.name, 0, "Starting download (${speedMode.label})...")

        activeJob = coroutineScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                var currentUrl = model.downloadUrl
                var redirects = 0
                while (redirects < 5) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 40000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "LifeOS-ModelDownloader/2.0")
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val newLocation = connection.getHeaderField("Location")
                        if (!newLocation.isNullOrBlank()) {
                            currentUrl = newLocation
                            redirects++
                            continue
                        }
                    }
                    break
                }

                val responseCode = connection?.responseCode ?: 0
                if (responseCode !in 200..299) {
                    throw Exception("Server returned HTTP $responseCode")
                }

                val contentLength = connection?.contentLengthLong?.takeIf { it > 0 }
                    ?: (model.sizeGb * 1024 * 1024 * 1024).toLong()

                inputStream = connection!!.inputStream
                outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(speedMode.bufferSizeBytes)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytesRead = 0L
                var lastNotifUpdate = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!activeJob!!.isActive) {
                        outputStream.close()
                        inputStream.close()
                        if (tempFile.exists()) tempFile.delete()
                        cancelNotification(context)
                        return@launch
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (speedMode.chunkDelayMs > 0) {
                        delay(speedMode.chunkDelayMs)
                    }

                    val now = System.currentTimeMillis()
                    val timeDiffSec = (now - lastTime) / 1000.0

                    var speedMBps = _downloadState.value.speedMBps
                    var etaSec = _downloadState.value.etaSeconds

                    if (timeDiffSec >= 0.5) {
                        val bytesSinceLast = totalBytesRead - lastBytesRead
                        speedMBps = ((bytesSinceLast / (1024.0 * 1024.0)) / timeDiffSec).let { Math.round(it * 10) / 10.0 }
                        val remainingBytes = maxOf(0L, contentLength - totalBytesRead)
                        etaSec = if (speedMBps > 0.05) (remainingBytes / (speedMBps * 1024.0 * 1024.0)).toLong() else 0L
                        lastTime = now
                        lastBytesRead = totalBytesRead
                    }

                    val progress = if (contentLength > 0) {
                        (totalBytesRead.toDouble() / contentLength.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else 0.5f

                    val mbRead = (totalBytesRead / (1024.0 * 1024.0)).toInt()
                    val totalMb = (contentLength / (1024.0 * 1024.0)).toInt()

                    val statusStr = "Downloading $mbRead MB / $totalMb MB ($speedMBps MB/s)"

                    _downloadState.value = DownloadProgressState(
                        modelId = model.id,
                        progress = progress,
                        bytesDownloaded = totalBytesRead,
                        totalBytes = contentLength,
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

                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)

                _downloadState.value = DownloadProgressState(
                    modelId = model.id,
                    progress = 1.0f,
                    speedMode = speedMode,
                    statusText = "Qwen 2.5 Coder 1.5B ready to run offline!",
                    isDownloading = false,
                    isCompleted = true
                )

                updateNotification(context, model.name, 100, "Download complete! AI is ready offline.", isComplete = true)

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                try {
                    outputStream?.close()
                    inputStream?.close()
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Exception) {}

                val errMsg = e.localizedMessage ?: "Download encountered a network interruption."
                _downloadState.value = DownloadProgressState(
                    modelId = model.id,
                    progress = 0f,
                    speedMode = speedMode,
                    statusText = "Download error: $errMsg",
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
