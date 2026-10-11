package com.shilapi.xcertplay

import android.Manifest
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
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
class BrowserBootReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val starts = mutableListOf<Intent>()
    private val activities = mutableListOf<Intent>()
    private var startFailure: RuntimeException? = null
    private val browser get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
        override fun startForegroundService(intent: Intent): ComponentName? {
            starts.add(intent)
            startFailure?.let { throw it }
            return intent.component
        }
        override fun startActivity(intent: Intent) { activities.add(intent) }
    }

    @Before @After fun reset() {
        listOf("diplay", "browser_session_recovery", "xcertplay_airplay").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        starts.clear()
        activities.clear()
        startFailure = null
    }

    @Test fun bootResumesAnOptedInRequestedReceiverAsAServiceOnly() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        BrowserSessionRecovery.markStarted(browser)
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertRecoveryServiceRequested()
        assertTrue(BrowserSessionRecovery.requested(browser))
    }

    @Test fun browserDefaultResumesOnlyAPreviouslyStartedReceiverAndRespectsExplicitOptOut() {
        configure()
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(browser))
        receive(Intent.ACTION_BOOT_COMPLETED)
        assertNoLaunch()
        BrowserSessionRecovery.markStarted(browser)
        receive(Intent.ACTION_BOOT_COMPLETED)
        assertRecoveryServiceRequested()
        starts.clear()
        AirPlayPersistence.saveAutoStartOnBoot(browser, false)
        receive(Intent.ACTION_BOOT_COMPLETED)
        assertNoLaunch()
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(browser))
    }

    @Test fun ordinaryDiPlayKeepsItsExistingBootDefault() {
        assertFalse(browserCarPlayEnabled(app))
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(app))
    }

    @Test fun bootWithoutOptInDoesNotStartAnythingOrDiscardTheRequestedSession() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, false)
        BrowserSessionRecovery.markStarted(browser)
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertNoLaunch()
        assertTrue(BrowserSessionRecovery.requested(browser))
    }

    @Test fun bootCannotStartAConfiguredReceiverThatWasNeverRequested() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertNoLaunch()
        assertFalse(BrowserSessionRecovery.requested(browser))
    }

    @Test fun packageReplacementResumesARunningReceiverWithoutTheBootOptIn() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, false)
        BrowserSessionRecovery.markStarted(browser)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertRecoveryServiceRequested()
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(browser))
    }

    @Test fun explicitStopSurvivesBothBootAndPackageReplacement() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        BrowserSessionRecovery.markStarted(browser)
        BrowserSessionRecovery.markStopped(browser)
        receive(Intent.ACTION_BOOT_COMPLETED)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNoLaunch()
        assertFalse(BrowserSessionRecovery.requested(browser))
    }

    @Test fun revokedPermissionPreventsBackgroundStartupWithoutOpeningPermissionUi() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        BrowserSessionRecovery.markStarted(browser)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        receive(Intent.ACTION_BOOT_COMPLETED)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNoLaunch()
        assertTrue(BrowserSessionRecovery.requested(browser))
    }

    @Test fun missingHotspotConfigurationPreventsBothBackgroundStarts() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        BrowserSessionRecovery.markStarted(browser)
        AirPlayPersistence.saveManualHotspotSsid(browser, "")
        receive(Intent.ACTION_BOOT_COMPLETED)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNoLaunch()
    }

    @Test fun rejectedServiceStartDoesNotFallBackToOpeningAnActivity() {
        configure()
        BrowserSessionRecovery.markStarted(browser)
        startFailure = SecurityException("test failure")
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertRecoveryServiceRequested()
        assertTrue(BrowserSessionRecovery.requested(browser))
    }

    @Test fun unrelatedBroadcastCannotStartTheReceiver() {
        configure()
        AirPlayPersistence.saveAutoStartOnBoot(browser, true)
        BrowserSessionRecovery.markStarted(browser)
        receive(Intent.ACTION_TIME_CHANGED)

        assertNoLaunch()
    }

    private fun receive(action: String) = BootReceiver().onReceive(browser, Intent(action))

    private fun assertRecoveryServiceRequested() {
        assertEquals(1, starts.size)
        assertEquals(DiPlaySessionService::class.java.name, starts.single().component?.className)
        assertEquals(DiPlaySessionService.ACTION_RECOVER, starts.single().action)
        assertTrue("Recovery must never launch an Activity", activities.isEmpty())
    }

    private fun assertNoLaunch() {
        assertTrue(starts.isEmpty())
        assertTrue(activities.isEmpty())
    }

    private fun configure() {
        DiPlayPreferences.saveAutoConnect(browser, false)
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(browser, true)
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(browser, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(browser, "test-passphrase")
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
