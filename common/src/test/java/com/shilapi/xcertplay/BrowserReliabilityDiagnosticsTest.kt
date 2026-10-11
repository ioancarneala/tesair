package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.browser.BrowserAddressService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserReliabilityDiagnosticsTest {
    private fun browserContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
        override fun getApplicationContext(): Context = this
    }

    @Test fun eventsSurviveARecreatedLoggerAndRedactPrivateValuesBeforeStorage() {
        val app = browserContext()
        val file = File(app.filesDir, "logs/reliability.log")
        file.delete()
        BrowserReliabilityDiagnostics.record(app, "viewer_close reason=read_io code=0")
        BrowserReliabilityDiagnostics.record(app, "password=synthetic-secret")
        BrowserReliabilityDiagnostics.record(app, "peer=10.11.12.13")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val stored = file.readText()
        assertTrue(stored.contains("viewer_close reason=read_io"))
        assertTrue(stored.contains("epochMs="))
        assertTrue(stored.contains("uptimeMs="))
        assertFalse(stored.contains("synthetic-secret"))
        assertFalse(stored.contains("10.11.12.13"))
        SessionLogFile(file).use { it.beginSession("synthetic process restarted") }
        val report = BrowserReliabilityDiagnostics.report(app)
        assertTrue(report.contains("viewer_close reason=read_io"))
        assertTrue(report.contains("synthetic process restarted"))
    }

    @Test fun healthSnapshotContainsStateWithoutCollectingNetworkOrPhoneNames() {
        val app = browserContext()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Synthetic Private Phone")
        AirPlayPersistence.saveManualHotspotSsid(app, "Synthetic Private Network")
        val snapshot = BrowserReliabilityDiagnostics.snapshot(app)
        assertTrue(snapshot, snapshot.startsWith("system screenOn="))
        assertTrue(snapshot.contains("batteryExempt="))
        assertTrue(snapshot.contains("thermal="))
        assertTrue(snapshot.contains("route="))
        assertTrue(snapshot.contains("bluetooth="))
        assertTrue(snapshot.contains("receiverRequested="))
        assertFalse(snapshot.contains("Synthetic Private"))
        assertFalse(snapshot.contains("00:11:22:33:44:55"))
        assertEquals(snapshot, DiagnosticRedactor.redact(snapshot))
    }

    @Test fun aliasPresenceIncludesAnAssignedAddressOnADownInterface() {
        val network = mock(NetworkInterface::class.java)
        `when`(network.isUp).thenReturn(false)
        `when`(network.inetAddresses).thenAnswer {
            Collections.enumeration(listOf(InetAddress.getByName(BrowserAddressService.ADDRESS)))
        }
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(network)) }

            val snapshot = BrowserReliabilityDiagnostics.snapshot(browserContext())

            assertTrue(snapshot, snapshot.contains("aliasPresent=true"))
        }
    }

    @Test fun unrelatedInterfaceAddressDoesNotCountAsTheAlias() {
        val network = mock(NetworkInterface::class.java)
        `when`(network.isUp).thenReturn(true)
        `when`(network.inetAddresses).thenAnswer {
            Collections.enumeration(listOf(InetAddress.getByName("192.0.2.1")))
        }
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(network)) }

            val snapshot = BrowserReliabilityDiagnostics.snapshot(browserContext())

            assertTrue(snapshot, snapshot.contains("aliasPresent=false"))
        }
    }
}
