package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h740dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserOnboardingEntryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<DiPlayActivity>? = null

    @Before fun setUp() = clearState()

    @After fun tearDown() {
        lifecycle?.destroy()
        lifecycle = null
        clearState()
    }

    @Test fun freshLauncherOpensTheGuideBeforeRequestingAReceiverOrPermission() {
        val activity = open(launcher())
        val guide = shadowOf(activity).nextStartedActivity
        assertEquals(BrowserOnboardingActivity::class.java.name, guide?.component?.className)
        assertFalse(guide!!.getBooleanExtra(BrowserOnboardingActivity.EXTRA_REVIEW, false))
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun savedCredentialsDoNotSkipAnUnfinishedGuideOrStopTheExistingSession() {
        configuredInstallation()
        BrowserOnboardingProgress.moveTo(app, Step.TESLA_BROWSER)
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)

        val activity = open(launcher())
        assertEquals(BrowserOnboardingActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(Step.TESLA_BROWSER, BrowserOnboardingProgress.step(app))
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun configuredUpgradeKeepsItsConnectionModeAndAutoconnectsWithoutTheGuide() {
        configuredInstallation()
        val activity = open(launcher())
        assertTrue(activity.isFinishing)
        assertFalse(BrowserOnboardingProgress.shouldResume(app))
        val dashboard = shadowOf(activity).nextStartedActivity
        assertEquals(BrowserDashboardActivity::class.java.name, dashboard?.component?.className)
        assertTrue(dashboard!!.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun launcherDeliveredToTheEntryShimResumesSetupWithoutASecondNavigation() {
        configuredInstallation()
        BrowserOnboardingProgress.moveTo(app, Step.TESLA_WIFI)
        val activity = open(Intent().putExtra("page", "settings"))
        assertEquals(BrowserSettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)

        lifecycle!!.newIntent(launcher())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BrowserOnboardingActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun connectionEntryReviewsSetupWithoutStartingTheReceiver() {
        configuredInstallation()
        val activity = open(Intent().putExtra("page", "connection"))
        val guide = shadowOf(activity).nextStartedActivity
        assertEquals(BrowserOnboardingActivity::class.java.name, guide?.component?.className)
        assertTrue(guide!!.getBooleanExtra(BrowserOnboardingActivity.EXTRA_REVIEW, false))

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun recreatingSettingsPreservesThePageDespiteUnfinishedSetup() {
        configuredInstallation()
        BrowserOnboardingProgress.moveTo(app, Step.BACKGROUND)
        val activity = open(launcher(), Bundle().apply { putString("page", "settings") })
        assertTrue(activity.isFinishing)
        assertEquals(BrowserSettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(app))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun open(intent: Intent, saved: Bundle? = null): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent)
        lifecycle = controller
        val activity = controller.get()
        // Model the browser APK before onCreate so these tests exercise its real entry branch.
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        activity.browserCertificateSyncFactory = { mock(SharedCertificateSync::class.java) }
        controller.create(saved)
        ReflectionHelpers.setField(activity, "setupError", null)
        return activity
    }

    private fun configuredInstallation() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Example iPhone")
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun clearState() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding",
            "browser_session_recovery").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        while (shadowOf(app).nextStartedService != null) { /* Discard recorded requests from prior tests. */ }
    }
}
