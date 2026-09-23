package com.example.util

import android.content.Context
import android.util.Log

/**
 * Global Gatekeeper Mechanism.
 * Ensures that if no user is authenticated/logged in, all app functions,
 * background services, workers, notifications, timers, alarms, cloud sync,
 * and data listeners are completely disabled and frozen.
 */
object AuthGatekeeper {

    private const val TAG = "AuthGatekeeper"

    fun isUserLoggedIn(context: Context): Boolean {
        return try {
            val prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
            val currentUsername = prefs.getString("current_username", null)
            val userName = prefs.getString("user_name", null)
            val userEmail = prefs.getString("user_email", null)

            val hasValidIdentity = !currentUsername.isNullOrBlank() || !userName.isNullOrBlank() || !userEmail.isNullOrBlank()
            isLoggedIn && hasValidIdentity
        } catch (e: Exception) {
            Log.e(TAG, "Error checking authentication status: ${e.message}", e)
            false
        }
    }

    /**
     * Enforces global freeze when unauthenticated:
     * - Cancels all alarms & reminders
     * - Stops background daemon services
     * Returns true if frozen (not logged in), false if user is authenticated.
     */
    fun enforceGlobalFreezeIfUnauthenticated(context: Context): Boolean {
        if (!isUserLoggedIn(context)) {
            Log.w(TAG, "Global Auth Gatekeeper: No user logged in. Freezing all app functions, services, and alarms.")
            try {
                AlarmScheduler.cancelAllAlarms(context)
                com.example.service.KeepAliveService.stop(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error during unauthenticated freeze enforcement: ${e.message}")
            }
            return true
        }
        return false
    }
}
