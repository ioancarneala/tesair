package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31, 33], manifest = Config.NONE)
class ManualHotspotMetadataTest {
    @Test fun restrictedRadioMetadataStillAttachesTheSavedSystemHotspot() {
        val context = mock(Context::class.java)
        val wifi = mock(WifiManager::class.java)
        val connectivity = mock(ConnectivityManager::class.java)
        val network = mock(NetworkInterface::class.java)
        val address = InetAddress.getByName("192.168.50.1")
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(connectivity)
        `when`(connectivity.allNetworks).thenReturn(emptyArray())
        `when`(WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int).thenReturn(13)
        `when`(wifi.connectionInfo).thenThrow(SecurityException("Wi-Fi metadata restricted"))
        `when`(wifi.scanResults).thenThrow(SecurityException("Wi-Fi metadata restricted"))
        `when`(network.name).thenReturn("wlan_test")
        `when`(network.index).thenReturn(7)
        `when`(network.isUp).thenReturn(true)
        `when`(network.inetAddresses).thenAnswer { Collections.enumeration(listOf(address)) }
        `when`(network.hardwareAddress).thenReturn(byteArrayOf(2, 18, 52, 86, 120, 90))

        val manager = ManualHotspotManager(context, "Test hotspot", "test-password",
            ManualHotspotBand.AUTO, 0, ManualHotspotSecurity.WPA2)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<WirelessHotspotInfo> {
                // Static mocks belong to this worker, where the real readiness/start logic runs.
                mockStatic(NetworkInterface::class.java).use { interfaces ->
                    interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                        .thenAnswer { Collections.enumeration(listOf(network)) }
                    interfaces.`when`<NetworkInterface> { NetworkInterface.getByName("wlan_test") }.thenReturn(network)
                    manager.start(3_000).also { manager.validateReady() }
                }
            }.get(5, TimeUnit.SECONDS)

            assertEquals(WirelessHotspotBackend.MANUAL_HOTSPOT, result.backend)
            assertEquals("Test hotspot", result.ssid)
            assertEquals("test-password", result.passphrase)
            assertEquals(Iap2WirelessSecurity.WPA_WPA2, result.security)
            assertEquals(address, result.hostAddress)
            assertEquals(0, result.channel)
            assertNull(result.frequencyMHz)
            verify(wifi).connectionInfo
            verify(wifi).scanResults
        } finally {
            manager.close()
            worker.shutdownNow()
        }
    }
}
