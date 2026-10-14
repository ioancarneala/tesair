package com.shilapi.xcertplay

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h740dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserOnboardingUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<BrowserOnboardingActivity>? = null

    @Before fun setUp() {
        clearState()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        shadowOf(context).grantPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        while (shadowOf(context).nextStartedService != null) { /* Discard earlier requests. */ }
    }

    @After fun tearDown() {
        lifecycle?.pause()?.stop()?.destroy()
        lifecycle = null
        clearState()
    }

    @Test fun welcomeAndSetupUseOneProductIdentityAndStayInsideTheGuide() {
        val screen = open(Step.WELCOME)
        assertProductIdentity(screen)
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_welcome_title)))
        assertEquals(listOf(screen.getString(R.string.onboarding_begin)),
            buttons(screen).map { it.text.toString() })

        button(screen, R.string.onboarding_begin).performClick()
        val consent = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(consent.isShowing)
        assertEquals(Step.WELCOME, BrowserOnboardingProgress.step(context))
        consent.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("disabled", BrowserTelemetry.choice(context))
        assertEquals(Step.NETWORK, BrowserOnboardingProgress.step(context))
        assertProductIdentity(screen)
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_network_title)))
        button(screen, R.string.onboarding_back).performClick()
        assertEquals(Step.WELCOME, BrowserOnboardingProgress.step(context))
        assertProductIdentity(screen)
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_welcome_title)))
        assertNull(shadowOf(screen).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun restoredGuideBackReturnsToThePreviousSetupStepInsteadOfAnOldHome() {
        open(Step.BACKGROUND)
        val saved = Bundle()
        lifecycle!!.saveInstanceState(saved).pause().stop().destroy()
        lifecycle = null
        val restored = open(saved = saved)
        assertProductIdentity(restored)
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))

        restored.onBackPressedDispatcher.onBackPressed()
        assertFalse(restored.isFinishing)
        assertEquals(Step.NETWORK, BrowserOnboardingProgress.step(context))
        assertTrue(texts(restored).contains(restored.getString(R.string.onboarding_network_title)))
        assertProductIdentity(restored)
        assertNull(shadowOf(restored).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun backFromWelcomeClosesTheGuideWithoutOpeningOrConnectingAnything() {
        val screen = open(Step.WELCOME)
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(screen.isFinishing)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun statusFromBrowserSetupOpensOnlyTheDashboardAndPreservesTheReceiver() {
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        CarPlayBackgroundSession.active = true
        val screen = open(Step.TESLA_BROWSER)
        assertProductIdentity(screen)

        button(screen, R.string.onboarding_open_status).performClick()

        val target = shadowOf(screen).nextStartedActivity
        assertEquals(BrowserDashboardActivity::class.java.name, target?.component?.className)
        assertFalse(target!!.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
        assertTrue(screen.isFinishing)
        assertEquals(Step.TESLA_BROWSER, BrowserOnboardingProgress.step(context))
        assertTrue(CarPlayBackgroundSession.active)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun browserSetupShowsTheShortAddressOnlyForAnAvailableTrustedEndpoint() {
        val direct = "https://carplay.tesair.co:8443"
        val output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        `when`(output.urls).thenReturn(listOf(direct))
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        val screen = open(Step.TESLA_BROWSER)
        assertTrue(texts(screen).contains(BrowserConnectionAddress.ENTRY_URL))
        assertFalse(texts(screen).contains(direct))
        assertEquals(listOf(direct), output.urls)

        `when`(output.urls).thenReturn(listOf("https://99.1.1.1:8443"))
        `when`(output.certificateSyncState).thenReturn(SharedCertificateSync.State.ERROR)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertFalse(texts(screen).contains(BrowserConnectionAddress.ENTRY_URL))
        assertTrue(texts(screen).contains(screen.getString(R.string.browser_https_unavailable)))

        `when`(output.urls).thenReturn(emptyList())
        `when`(output.certificateSyncState).thenReturn(SharedCertificateSync.State.CHECKING)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertFalse(texts(screen).contains(BrowserConnectionAddress.ENTRY_URL))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_browser_preparing)))
        assertNull(shadowOf(context).nextStartedService)
    }

    private fun open(step: Step? = null, saved: Bundle? = null): BrowserOnboardingActivity {
        step?.let { BrowserOnboardingProgress.moveTo(context, it) }
        lifecycle = Robolectric.buildActivity(BrowserOnboardingActivity::class.java).create(saved)
        val screen = lifecycle!!.get()
        screen.certificateSyncFactory = { mock(SharedCertificateSync::class.java) }
        lifecycle!!.start().resume().visible()
        return screen
    }

    private fun assertProductIdentity(screen: BrowserOnboardingActivity) {
        assertEquals(1, texts(screen).count { it == screen.getString(R.string.browser_product_name) })
    }

    private fun clearState() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(context)
        ShadowVpnService.setPrepareResult(null)
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding", "browser_session_recovery")
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    private fun button(screen: BrowserOnboardingActivity, id: Int) = buttons(screen)
        .single { it.text.toString() == screen.getString(id) }
    private fun buttons(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().toList()
    private fun texts(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
