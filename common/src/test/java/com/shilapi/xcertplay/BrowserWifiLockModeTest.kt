package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserWifiLockModeTest {
    @Test fun usesHighPerfWhileStreaming() {
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, browserWifiLockMode(charging = false, browserClientConnected = true))
    }

    @Test fun usesHighPerfWhileCharging() {
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, browserWifiLockMode(charging = true, browserClientConnected = false))
    }

    @Test fun fallsBackToFullLockOnlyWhenIdleAndOnBattery() {
        assertEquals(WifiManager.WIFI_MODE_FULL, browserWifiLockMode(charging = false, browserClientConnected = false))
    }
}
