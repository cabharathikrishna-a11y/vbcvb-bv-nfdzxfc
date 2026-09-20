package com.example.util

import android.content.Context

/**
 * Backward-compatible network check facade.
 * Delegates all connectivity, status, and network advice evaluations
 * directly to the dedicated Central NetworkTrafficManager.
 */
object NetworkChecker {

    fun isInternetAvailable(context: Context): Boolean {
        return NetworkTrafficManager.isInternetOn(context)
    }

    fun isOnline(context: Context): Boolean {
        return NetworkTrafficManager.isInternetOn(context)
    }

    fun isInternetOn(context: Context): Boolean {
        return NetworkTrafficManager.isInternetOn(context)
    }

    fun isInternetOff(context: Context): Boolean {
        return NetworkTrafficManager.isInternetOff(context)
    }

    fun isOffline(context: Context): Boolean {
        return NetworkTrafficManager.isInternetOff(context)
    }

    fun getConnectionType(context: Context): NetworkTrafficManager.ConnectionType {
        return NetworkTrafficManager.getConnectionType(context)
    }

    fun isMetered(context: Context): Boolean {
        return NetworkTrafficManager.isMetered(context)
    }

    fun getNetworkQuality(): NetworkTrafficManager.NetworkQuality {
        return NetworkTrafficManager.getNetworkQuality()
    }

    fun getNetworkAdvice(context: Context): String {
        return NetworkTrafficManager.getNetworkAdvice(context)
    }

    fun getNetworkStatusSummary(context: Context): String {
        return NetworkTrafficManager.getNetworkStatusSummary(context)
    }

    fun getUsageReport(context: Context): NetworkTrafficManager.NetworkUsageReport {
        return NetworkTrafficManager.getUsageReport(context)
    }
}
