package com.example

import android.app.Application
import androidx.work.Configuration
import java.util.concurrent.Executors

class MainApplication : Application(), Configuration.Provider {

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
