package com.shilapi.xcertplay

import android.Manifest
import android.content.ContextWrapper
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserSessionRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val browser get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
    }

    @Before @After fun reset() {
        listOf("diplay", "browser_session_recovery", "xcertplay_airplay").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        CarPlayBackgroundSession.clear()
        while (shadowOf(app).nextStartedActivity != null) { /* Clear prior launch assertions. */ }
    }

    @Test fun configuredReceiverRecoversOnlyAfterAnExplicitSessionStart() {
        configure()
        assertTrue(BrowserSessionRecovery.configured(browser))
        assertFalse(BrowserSessionRecovery.requested(browser))
        assertFalse(BrowserSessionRecovery.stopped(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))
        BrowserSessionRecovery.markStarted(browser)

        // A fresh context reads the persisted intent without an Activity or Home role.
        assertTrue(BrowserSessionRecovery.requested(browser))
        assertFalse(BrowserSessionRecovery.stopped(browser))
        assertTrue(BrowserSessionRecovery.canRecover(browser))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun connectOnOpenPreferenceDoesNotDisableAnAlreadyRequestedServer() {
        configure()
        DiPlayPreferences.saveAutoConnect(browser, false)
        BrowserSessionRecovery.markStarted(browser)

        assertTrue(BrowserSessionRecovery.canRecover(browser))
        assertFalse(DiPlayPreferences.autoConnect(browser))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun explicitStopPersistsUntilTheUserStartsAgain() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        BrowserSessionRecovery.markStopped(browser)

        assertFalse(BrowserSessionRecovery.requested(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))
        assertTrue(BrowserSessionRecovery.stopped(browser))
        assertTrue(BrowserSessionRecovery.configured(browser))
        BrowserSessionRecovery.markStarted(browser)
        assertTrue(BrowserSessionRecovery.canRecover(browser))
        assertFalse(BrowserSessionRecovery.stopped(browser))
    }

    @Test fun incompleteHotspotSetupBlocksRecoveryWithoutDiscardingRequestedState() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        AirPlayPersistence.saveManualHotspotSsid(browser, "")
        assertFalse(BrowserSessionRecovery.configured(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))
        assertTrue(BrowserSessionRecovery.requested(browser))

        AirPlayPersistence.saveManualHotspotSsid(browser, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(browser, "short")
        assertFalse(BrowserSessionRecovery.canRecover(browser))
    }

    @Test fun missingPhoneOrDisabledWirelessBlocksRecovery() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        DiPlayPreferences.savePhone(browser, "", "")
        assertFalse(BrowserSessionRecovery.configured(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))

        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(browser, false)
        assertFalse(BrowserSessionRecovery.canRecover(browser))
    }

    @Test fun revokedPermissionBlocksRecoveryWithoutOpeningPermissionUi() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(BrowserSessionRecovery.configured(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))
        assertTrue(BrowserSessionRecovery.requested(browser))
        assertNull(shadowOf(app).nextStartedActivity)

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(BrowserSessionRecovery.canRecover(browser))
    }

    @Test fun existingWifiUsesItsOwnCredentialsAndPermissions() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(browser, "", "")
        assertFalse(BrowserSessionRecovery.canRecover(browser))

        AirPlayPersistence.saveExistingWifiCredentials(browser, "Existing network", "test-passphrase")
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(BrowserSessionRecovery.canRecover(browser))
    }

    @Test fun otherBuildsDoNotAcquireBrowserRecoveryState() {
        configure()
        BrowserSessionRecovery.markStarted(app)
        assertFalse(BrowserSessionRecovery.requested(app))
        assertFalse(app.getSharedPreferences("browser_session_recovery", 0).getBoolean("requested", false))
        assertFalse(BrowserSessionRecovery.configured(app))
        assertFalse(BrowserSessionRecovery.canRecover(app))
        assertFalse(BrowserSessionRecovery.stopped(app))
    }

    private fun configure() {
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(browser, true)
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(browser, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(browser, "test-passphrase")
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
