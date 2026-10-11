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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserAppNavigationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val browser get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
    }

    @Before @After fun clear() {
        listOf("diplay", "xcertplay_airplay", "browser_onboarding", "browser_session_recovery")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        CarPlayBackgroundSession.clear()
    }

    @Test fun settingsAndAboutUseTheDedicatedSettingsScreenEvenBeforeSetup() {
        listOf("settings", "about").forEach { page ->
            val destination = BrowserAppNavigation.destination(browser, Intent().putExtra("page", page))
            assertEquals(BrowserSettingsActivity::class.java.name, destination.component?.className)
            assertFalse(destination.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
            assertTrue(destination.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        }
    }

    @Test fun restoredSettingsTakePriorityOverTheLauncherIntent() {
        val destination = BrowserAppNavigation.destination(browser,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), Bundle().apply {
                putString("page", "settings")
            })

        assertEquals(BrowserSettingsActivity::class.java.name, destination.component?.className)
    }

    @Test fun incompleteNetworkOrMissingIphoneReturnsToTheGuide() {
        assertGuide(BrowserAppNavigation.destination(browser, Intent()))
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Example iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(browser, "")

        assertGuide(BrowserAppNavigation.destination(browser, Intent()))
    }

    @Test fun configuredSameLanUpgradeRetainsItsModeAndUsesTheDashboard() {
        DiPlayPreferences.prepareBrowserAutoConnect(browser)
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Example iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(browser, "Example network", "example-password")

        val destination = BrowserAppNavigation.destination(browser, Intent())

        assertEquals(BrowserDashboardActivity::class.java.name, destination.component?.className)
        assertTrue(destination.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(browser))
    }

    private fun assertGuide(destination: Intent) {
        assertEquals(BrowserOnboardingActivity::class.java.name, destination.component?.className)
        assertFalse(destination.getBooleanExtra(BrowserOnboardingActivity.EXTRA_REVIEW, false))
    }
}
