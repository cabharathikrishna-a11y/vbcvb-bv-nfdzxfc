package com.example

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.util.concurrent.Executors
import okhttp3.OkHttpClient

class MainApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors()))))
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE || level >= TRIM_MEMORY_BACKGROUND) {
            try {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            } catch (_: Throwable) {}
            try {
                System.gc()
            } catch (_: Throwable) {}
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        } catch (_: Throwable) {}
        try {
            System.gc()
        } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.util.AppCrashRollbackManager.initialize(this)
        com.example.util.NetworkTrafficManager.init(this)
        
        // Execute background non-critical initializations asynchronously to minimize app startup time
        Executors.newSingleThreadExecutor().execute {
            try {
                com.example.api.Firebase.ensureFirebaseInitialized(this)
            } catch (e: Exception) {
                android.util.Log.e("MainApp", "Async Firebase init failed: ${e.message}", e)
            }
            try {
                com.example.util.UrgentNotificationHelper.initChannels(this)
            } catch (e: Exception) {
                android.util.Log.e("MainApp", "Async Notification Channel init failed: ${e.message}", e)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                try {
                    packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(this, "com.example.provider.LifeOsCloudMediaProvider"),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                    android.util.Log.e("MainApp", "Could not enable LifeOsCloudMediaProvider", e)
                }
            }
        }
    }

    companion object {
        lateinit var instance: MainApplication
            private set
    }
}
