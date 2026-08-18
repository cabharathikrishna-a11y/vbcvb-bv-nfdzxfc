package com.example.util

import android.content.Context

/**
 * Backward-compatible network check facade.
 * Delegates all connectivity and state evaluations to the dedicated Central NetworkTrafficManager.
 */
object NetworkChecker {

    fun isInternetAvailable(context: Context): Boolean {
        return NetworkTrafficManager.isConnected(context)
    }

    fun isOnline(context: Context): Boolean {
        return NetworkTrafficManager.isConnected(context)
    }

    fun getConnectionType(context: Context): NetworkTrafficManager.ConnectionType {
        return NetworkTrafficManager.getConnectionType(context)
    }
}
