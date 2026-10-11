package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserAutoConnectTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val browser get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
    }

    @Before @After fun clear() {
        listOf("diplay", "xcertplay_airplay", "browser_onboarding", "browser_session_recovery")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        CarPlayBackgroundSession.clear()
    }

    @Test fun browserUpgradeEnablesAutoConnectOnceAndPreservesLaterOptOut() {
        DiPlayPreferences.saveAutoConnect(browser, false)
        DiPlayPreferences.prepareBrowserAutoConnect(browser)
        assertTrue(DiPlayPreferences.autoConnect(browser))
        DiPlayPreferences.saveAutoConnect(browser, false)
        DiPlayPreferences.prepareBrowserAutoConnect(browser)
        assertFalse(DiPlayPreferences.autoConnect(browser))
    }

    @Test fun otherPackagesKeepTheirExistingStartupBehavior() {
        DiPlayPreferences.prepareBrowserAutoConnect(app)
        assertFalse(DiPlayPreferences.autoConnect(app))
    }

    @Test fun freshLauncherConnectsWirelesslyDespiteAnOldUsbPreference() {
        configuredInstallation()
        DiPlayPreferences.saveDefaultConnectionMode(browser, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(browser, false)

        val destination = BrowserAppNavigation.destination(browser, launcher())

        assertDashboard(destination, reconnect = true)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(browser))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(browser))
    }

    @Test fun autoConnectOptOutStillOpensTheDashboardWithoutStarting() {
        configuredInstallation()
        DiPlayPreferences.saveAutoConnect(browser, false)
        AirPlayPersistence.saveWirelessEnabled(browser, false)

        assertDashboard(BrowserAppNavigation.destination(browser, launcher()), reconnect = false)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(browser))
    }

    @Test fun restoringTheLauncherDoesNotUndoStopButAFreshLauncherIntentCanConnect() {
        configuredInstallation()
        BrowserSessionRecovery.markStopped(browser)

        assertDashboard(BrowserAppNavigation.destination(browser, launcher(), Bundle()), reconnect = false)
        assertFalse(BrowserSessionRecovery.requested(browser))

        assertDashboard(BrowserAppNavigation.destination(browser, launcher()), reconnect = true)
    }

    @Test fun explicitHomeAndRestoredHomeNeverRequestAReconnect() {
        configuredInstallation()

        assertDashboard(BrowserAppNavigation.destination(browser, Intent().putExtra("page", "home")), reconnect = false)
        assertDashboard(BrowserAppNavigation.destination(browser, launcher(), Bundle().apply {
            putString("page", "home")
        }), reconnect = false)
    }

    @Test fun openingTheLauncherPreservesAnExistingReceiver() {
        configuredInstallation()
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)

        assertDashboard(BrowserAppNavigation.destination(browser, launcher()), reconnect = false)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    private fun configuredInstallation() {
        DiPlayPreferences.prepareBrowserAutoConnect(browser)
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Example iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.WIFI_P2P)
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun assertDashboard(intent: Intent, reconnect: Boolean) {
        assertEquals(BrowserDashboardActivity::class.java.name, intent.component?.className)
        assertEquals(reconnect, intent.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
    }
}
