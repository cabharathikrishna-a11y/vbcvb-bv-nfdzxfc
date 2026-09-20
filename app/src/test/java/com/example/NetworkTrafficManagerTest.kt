package com.example

import com.example.util.NetworkTrafficManager
import org.junit.Assert.*
import org.junit.Test

class NetworkTrafficManagerTest {

    @Test
    fun testFormatBytes() {
        assertEquals("500 B", NetworkTrafficManager.formatBytes(500L))
        assertEquals("1.0 KB", NetworkTrafficManager.formatBytes(1024L))
        assertEquals("1.5 KB", NetworkTrafficManager.formatBytes(1536L))
        assertEquals("1.00 MB", NetworkTrafficManager.formatBytes(1024L * 1024L))
        assertEquals("2.50 GB", NetworkTrafficManager.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testCategoryStatsRecording() {
        NetworkTrafficManager.clearTrafficLogs()
        val cat = NetworkTrafficManager.TrafficCategory.DATABASE_SYNC

        NetworkTrafficManager.recordTraffic(
            category = cat,
            operationName = "SyncTaskUnits",
            bytesSent = 256L,
            bytesReceived = 1024L,
            isSuccess = true
        )

        val stats = NetworkTrafficManager.getCategoryStats(cat)
        assertTrue(stats.totalBytesSent >= 256L)
        assertTrue(stats.totalBytesReceived >= 1024L)
        assertTrue(stats.successfulOperations >= 1L)

        val logs = NetworkTrafficManager.getRecentTrafficLogs()
        assertTrue(logs.isNotEmpty())
        val topLog = logs.first()
        assertEquals(cat, topLog.category)
        assertEquals("SyncTaskUnits", topLog.operationName)
        assertEquals(256L, topLog.bytesSent)
        assertEquals(1024L, topLog.bytesReceived)
        assertTrue(topLog.isSuccess)
    }

    @Test
    fun testNetworkQualityThresholds() {
        assertEquals("Offline", NetworkTrafficManager.NetworkQuality.OFFLINE.label)
        assertEquals(0, NetworkTrafficManager.NetworkQuality.OFFLINE.minDownlinkKbps)
        assertTrue(NetworkTrafficManager.NetworkQuality.EXCELLENT.minDownlinkKbps > NetworkTrafficManager.NetworkQuality.GOOD.minDownlinkKbps)
    }

    @Test
    fun testNetworkAdviceWhenOffline() {
        val offlineAdvice = NetworkTrafficManager.getNetworkAdvice(null)
        assertNotNull(offlineAdvice)
        assertTrue(offlineAdvice.isNotEmpty())
    }

    @Test
    fun testDefaultNetworkState() {
        val state = NetworkTrafficManager.networkState.value
        assertNotNull(state)
        assertNotNull(state.connectionType)
        assertNotNull(state.networkQuality)
        assertNotNull(state.getHumanReadableSummary())
    }

    @Test
    fun testOkHttpClientNotNull() {
        val client = NetworkTrafficManager.getOkHttpClient()
        assertNotNull(client)
        val builder = NetworkTrafficManager.createOkHttpClientBuilder(NetworkTrafficManager.TrafficCategory.APP_UPDATE)
        assertNotNull(builder)
    }

    @Test
    fun testPeriodUsageFormatting() {
        val usage = NetworkTrafficManager.PeriodUsage(
            rxBytes = 10 * 1024 * 1024L, // 10 MB
            txBytes = 2 * 1024 * 1024L   // 2 MB
        )
        assertEquals(12 * 1024 * 1024L, usage.totalBytes)
        assertEquals("10.00 MB", usage.formattedRx)
        assertEquals("2.00 MB", usage.formattedTx)
        assertEquals("12.00 MB", usage.formattedTotal)
    }

    @Test
    fun testNetworkUsageReportStructure() {
        val report = NetworkTrafficManager.getUsageReport(null)
        assertNotNull(report.today)
        assertNotNull(report.past7Days)
        assertNotNull(report.past30Days)
        assertNotNull(report.allTime)
        assertNotNull(report.formattedInstallDate)
        assertTrue(report.installTimestamp > 0L)
    }
}
