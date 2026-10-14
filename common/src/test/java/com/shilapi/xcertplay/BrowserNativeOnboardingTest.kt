package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h740dp", shadows = [OnboardingHotspotShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserNativeOnboardingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<BrowserOnboardingActivity>? = null

    @Before fun setUp() {
        clearState()
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        grantConnectionPermissions()
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val paired = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(paired).setName("Example iPhone")
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setBondedDevices(setOf(paired))
        OnboardingHotspotShadow.apState = 13
        ShadowVpnService.setPrepareResult(null)
        while (shadowOf(context).nextStartedService != null) { /* Clear earlier test requests. */ }
    }

    @After fun tearDown() {
        lifecycle?.pause()?.stop()?.destroy()
        lifecycle = null
        clearState()
    }

    @Test fun readingTheGuideDoesNotRequestPermissionsOrStartTheReceiver() {
        shadowOf(context).denyPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        val screen = open(Step.BACKGROUND)
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_permissions_location_body)))
        assertNull(shadowOf(screen).lastRequestedPermission)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(texts(screen).contains(screen.getString(R.string.onboarding_continue)))
        button(screen, R.string.onboarding_permissions_action).performClick()
        assertNotNull(shadowOf(screen).lastRequestedPermission)
    }

    @Test fun deferredBatteryChoiceAndOpeningSettingsCannotBypassTheLiveGrant() {
        grantConnectionRequirements()
        setBatteryAllowed(false)
        context.getSharedPreferences("browser_onboarding", 0).edit().putBoolean("deferred_battery", true).commit()
        val screen = open(Step.BACKGROUND)
        assertEquals(listOf(screen.getString(R.string.onboarding_battery_action)), primaryActions(screen).map { it.text.toString() })
        assertFalse(texts(screen).contains(screen.getString(R.string.onboarding_continue)))

        button(screen, R.string.onboarding_battery_action).performClick()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            shadowOf(screen).nextStartedActivity?.action)
        lifecycle!!.pause().resume()
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertFalse(texts(screen).contains(screen.getString(R.string.onboarding_continue)))

        setBatteryAllowed(true)
        lifecycle!!.pause().resume()
        assertTrue(button(screen, R.string.onboarding_continue).isEnabled)
        button(screen, R.string.onboarding_continue).performClick()
        assertEquals(Step.IPHONE, BrowserOnboardingProgress.step(context))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun revokingBackgroundAccessBeforeContinueCannotAdvanceOrStopAnExistingSession() {
        grantConnectionRequirements()
        val screen = open(Step.BACKGROUND)
        val controller = retainSession(screen)
        val continueButton = button(screen, R.string.onboarding_continue)
        assertTrue(continueButton.isEnabled)
        setBatteryAllowed(false)

        continueButton.performClick()

        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_battery_action)))
        verifyNoInteractions(controller)
    }

    @Test fun resumingALaterStepRepairsMissingBackgroundAccessWithoutStoppingVideo() {
        setBatteryAllowed(false)
        val controller = retainSession(context)
        val screen = open(Step.TESLA_BROWSER)

        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_battery_title)))
        verifyNoInteractions(controller)
        assertFalse(BrowserOnboardingProgress.completed(context))
    }

    @Test fun startRechecksBackgroundAccessAfterTheScreenWasRendered() {
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Example iPhone")
        val screen = open(Step.IPHONE)
        shadowOf(context).grantPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        setBatteryAllowed(false)

        button(screen, R.string.onboarding_iphone_start).performClick()

        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun repairingBackgroundAccessDoesNotBlockRecoveryOfAnAlreadyRequestedSession() {
        grantConnectionRequirements()
        saveNetwork()
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Example iPhone")
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val browser = object : ContextWrapper(context) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        }
        BrowserSessionRecovery.markStarted(browser)
        assertTrue(BrowserSessionRecovery.canRecover(browser))
        setBatteryAllowed(false)

        BrowserOnboardingProgress.moveTo(context, Step.TESLA_BROWSER)
        val controller = Robolectric.buildActivity(BrowserOnboardingActivity::class.java).create()
        lifecycle = controller
        val screen = controller.get()
        ReflectionHelpers.setField(screen, "mBase", object : ContextWrapper(screen.baseContext) {
            override fun getPackageName() = browser.packageName
        })
        screen.certificateSyncFactory = { mock(SharedCertificateSync::class.java) }
        controller.start().resume().visible()

        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertEquals(DiPlaySessionService.ACTION_RECOVER, shadowOf(context).nextStartedService?.action)
        assertFalse(BrowserOnboardingProgress.completed(context))
    }

    @Test fun revokedBackgroundAccessInvalidatesCompletionAndCannotBeSkippedAgain() {
        BrowserOnboardingProgress.confirmAudio(context)
        assertTrue(BrowserOnboardingProgress.finish(context, true, true))
        setBatteryAllowed(false)

        assertFalse(BrowserOnboardingProgress.completed(context))
        assertTrue(BrowserOnboardingProgress.shouldResume(context))
        context.getSharedPreferences("browser_onboarding", 0).edit().putBoolean("deferred_battery", true).commit()
        assertFalse(BrowserOnboardingProgress.finish(context, true, true))
    }

    @Test fun everyManufacturerUsesTheSameReadyScreenWithoutAnExtraVerificationStep() {
        val output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        `when`(output.clientCount).thenReturn(1)
        `when`(output.hasRenderedVideo).thenReturn(true)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        CarPlayBackgroundSession.active = true
        BrowserOnboardingProgress.confirmAudio(context)
        var expected: List<String>? = null
        for (manufacturer in listOf("Xiaomi", "OnePlus", "Samsung")) {
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setBrand(manufacturer)
            val screen = open(Step.READY)
            assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_ready_title)))
            assertEquals(listOf(screen.getString(R.string.onboarding_open_status)), primaryActions(screen).map { it.text.toString() })
            assertTrue(BrowserOnboardingProgress.finish(context, true, true))
            assertTrue(BrowserOnboardingProgress.completed(context))
            if (expected == null) expected = texts(screen) else assertEquals(expected, texts(screen))
            lifecycle!!.pause().stop().destroy()
            lifecycle = null
        }
    }

    @Test @Config(sdk = [33])
    fun backgroundSetupShowsOneActionInTheSameOrderOnEveryManufacturer() {
        for (manufacturer in listOf("Xiaomi", "OnePlus", "Samsung")) {
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setBrand(manufacturer)
            setBatteryAllowed(false)
            shadowOf(context).denyPermissions(*WirelessPermissions.required(
                Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
            setRoutePhase(BrowserAddressService.Phase.OFF)
            AirPlayPersistence.saveAutoStartOnBoot(context, false)
            val screen = open(Step.BACKGROUND)
            fun onlyAction(id: Int, enabled: Boolean = true) {
                val primary = primaryActions(screen).single()
                assertEquals(screen.getString(id), primary.text.toString())
                assertEquals(enabled, primary.isEnabled)
                assertEquals(listOf(screen.getString(R.string.onboarding_back), screen.getString(id)),
                    descendants(screen.window.decorView).filterIsInstance<Button>().map { it.text.toString() }.toList())
                assertFalse(descendants(screen.window.decorView).any { it is android.widget.Switch })
            }
            onlyAction(R.string.onboarding_permissions_action)
            assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_permissions_body)))
            grantConnectionPermissions()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
            onlyAction(R.string.onboarding_battery_action)
            setBatteryAllowed(true)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
            onlyAction(R.string.onboarding_route_action)
            setRoutePhase(BrowserAddressService.Phase.STARTING)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
            onlyAction(R.string.onboarding_route_action, enabled = false)
            setRoutePhase(BrowserAddressService.Phase.ACTIVE)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
            onlyAction(R.string.onboarding_continue)
            assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_background_approved_title)))
            assertFalse(AirPlayPersistence.loadAutoStartOnBoot(context))
            button(screen, R.string.onboarding_continue).performClick()
            assertEquals(Step.IPHONE, BrowserOnboardingProgress.step(context))
            assertNull(shadowOf(context).nextStartedService)
            lifecycle!!.pause().stop().destroy()
            lifecycle = null
        }
    }

    @Test fun permanentlyDeniedConnectionPermissionReplacesTheActionWithSettings() {
        shadowOf(context).denyPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        val screen = open(Step.BACKGROUND)
        ReflectionHelpers.setField(screen, "permissionDenied", true)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertEquals(listOf(screen.getString(R.string.onboarding_app_settings)), primaryActions(screen).map { it.text.toString() })
        button(screen, R.string.onboarding_app_settings).performClick()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(screen).nextStartedActivity?.action)
        assertNull(shadowOf(screen).lastRequestedPermission)
        assertFalse(BrowserOnboardingProgress.completed(context))
    }

    @Test fun completedSetupChecksPermissionRevocationsButAllowsANormallyStoppedRoute() {
        BrowserOnboardingProgress.confirmAudio(context)
        assertTrue(BrowserOnboardingProgress.finish(context, true, true))
        BrowserAddressService.disable(context)
        assertTrue(BrowserOnboardingProgress.completed(context))
        shadowOf(context).denyPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        assertFalse(BrowserOnboardingProgress.completed(context))
        assertFalse(BrowserOnboardingProgress.finish(context, true, true))
        grantConnectionPermissions()
        ShadowVpnService.setPrepareResult(Intent("example.vpn.consent"))
        assertFalse(BrowserOnboardingProgress.completed(context))
        assertFalse(BrowserOnboardingProgress.finish(context, true, true))
        val screen = open(Step.READY)
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertEquals(listOf(screen.getString(R.string.onboarding_route_action)), primaryActions(screen).map { it.text.toString() })
    }

    @Test fun manualEntryValidatesInlineAndPreservesExactHotspotName() {
        val screen = open(Step.NETWORK)
        val fields = inputs(screen)
        fields[0].setText(" Example hotspot ")
        fields[1].setText("short")
        button(screen, R.string.onboarding_network_save).performClick()
        assertEquals(Step.NETWORK, BrowserOnboardingProgress.step(context))
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
        assertNotNull(fields[1].error)
        fields[1].setText("example-password")
        button(screen, R.string.onboarding_network_save).performClick()
        assertEquals(" Example hotspot ", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("example-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(context))
        assertNull(shadowOf(screen).lastRequestedPermission)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun changingNetworkDuringAnActiveSessionDoesNotReconnectIt() {
        saveNetwork()
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Example iPhone")
        val screen = open(Step.NETWORK)
        val controller = retainSession(screen)
        inputs(screen)[0].setText("Replacement hotspot")
        button(screen, R.string.onboarding_network_save).performClick()
        assertTrue(PendingReconnect.isPending(controller))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertNull(shadowOf(context).nextStartedService)
        verifyNoInteractions(controller)
        lifecycle!!.pause().stop().destroy()
        lifecycle = null
        CarPlayBackgroundSession.active = true
        val iphone = open(Step.IPHONE)
        assertFalse("An old session cannot verify edited settings", button(iphone, R.string.onboarding_continue).isEnabled)
    }

    @Test fun alternateConnectionModeIsPreservedUntilAnExplicitReplacementIsSaved() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Saved network", "example-password")
        val screen = open(Step.NETWORK)
        assertTrue(inputs(screen).isEmpty())
        button(screen, R.string.onboarding_network_existing).performClick()
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
        button(screen, R.string.onboarding_back).performClick()
        button(screen, R.string.onboarding_network_change).performClick()
        assertEquals(2, inputs(screen).size)
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun passwordIsMaskedAndUnsavedDraftIsNotRestored() {
        val screen = open(Step.NETWORK)
        inputs(screen)[1].setText("draft-password")
        assertTrue(inputs(screen)[1].transformationMethod is PasswordTransformationMethod)
        assertFalse(inputs(screen)[1].isSaveEnabled)
        button(screen, R.string.onboarding_password_show_short).performClick()
        assertNull(inputs(screen)[1].transformationMethod)
        assertTrue(screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val saved = Bundle()
        lifecycle!!.saveInstanceState(saved).pause()
        assertTrue(inputs(screen)[1].transformationMethod is PasswordTransformationMethod)
        lifecycle!!.stop().destroy()
        lifecycle = null
        val restored = open(saved = saved)
        assertEquals("", inputs(restored)[1].text.toString())
        assertEquals(Step.NETWORK, BrowserOnboardingProgress.step(context))
    }

    @Test fun reviewStartsAtNetworkAndOrdinaryLaunchResumesTheUnfinishedStep() {
        BrowserOnboardingProgress.moveTo(context, Step.TESLA_BROWSER)
        val screen = open(review = true)
        assertEquals(Step.NETWORK, BrowserOnboardingProgress.step(context))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_network_title)))
        inputs(screen)[0].setText("Example hotspot")
        inputs(screen)[1].setText("example-password")
        button(screen, R.string.onboarding_network_save).performClick()
        assertTrue(BrowserOnboardingProgress.shouldResume(context))
        lifecycle!!.pause().stop().destroy()
        lifecycle = null
        val resumed = open()
        assertTrue(texts(resumed).contains(resumed.getString(R.string.onboarding_route_title)))
    }

    @Test fun selectingAnIphoneDoesNotPretendItIsConnectedAndTheBrowserRequiresVideo() {
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Example iPhone")
        val screen = open(Step.IPHONE)
        assertFalse(button(screen, R.string.onboarding_continue).isEnabled)
        assertEquals(View.GONE, button(screen, R.string.onboarding_continue).visibility)
        assertEquals(View.VISIBLE, button(screen, R.string.onboarding_iphone_start).visibility)
        assertEquals(1, texts(screen).count { it == screen.getString(R.string.onboarding_iphone_accept) })
        CarPlayBackgroundSession.active = true
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertTrue(button(screen, R.string.onboarding_continue).isEnabled)
        assertEquals(View.VISIBLE, button(screen, R.string.onboarding_continue).visibility)
        assertEquals(View.GONE, button(screen, R.string.onboarding_iphone_start).visibility)
        CarPlayBackgroundSession.active = false
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals(View.VISIBLE, button(screen, R.string.onboarding_iphone_start).visibility)
        assertEquals(View.GONE, button(screen, R.string.onboarding_continue).visibility)
        lifecycle!!.pause().stop().destroy()
        lifecycle = null
        val browser = open(Step.TESLA_BROWSER)
        assertFalse(button(browser, R.string.onboarding_continue).isEnabled)
        assertFalse(texts(browser).contains(browser.getString(R.string.onboarding_browser_verified)))
        assertFalse(BrowserOnboardingProgress.completed(context))
    }

    @Test fun audioConfirmationAloneCannotFinishSetup() {
        val screen = open(Step.AUDIO)
        button(screen, R.string.onboarding_audio_confirm).performClick()
        assertTrue(BrowserOnboardingProgress.audioConfirmed(context))
        assertFalse(BrowserOnboardingProgress.completed(context))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_ready_waiting)))
        assertFalse(BrowserOnboardingProgress.finish(context, iphoneActive = true, videoRendered = false))
        assertFalse(BrowserOnboardingProgress.finish(context, iphoneActive = false, videoRendered = true))
        assertTrue(BrowserOnboardingProgress.finish(context, iphoneActive = true, videoRendered = true))
        assertTrue(BrowserOnboardingProgress.completed(context))
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
        BrowserOnboardingProgress.resetPhoneVerification(context)
        assertFalse(BrowserOnboardingProgress.audioConfirmed(context))
        assertFalse(BrowserOnboardingProgress.completed(context))
    }

    @Test fun anExistingInstallationIsNotForcedIntoTheGuideWithoutProgress() {
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
        BrowserOnboardingProgress.moveTo(context, Step.WELCOME)
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
        BrowserOnboardingProgress.moveTo(context, Step.IPHONE)
        assertTrue(BrowserOnboardingProgress.shouldResume(context))
    }

    @Test fun iphoneHelpOpensTheRelevantStepWithoutStartingAnUnfinishedGuide() {
        saveNetwork()
        val screen = open(contextualStep = Step.IPHONE)
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_iphone_select_title)))
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
        assertEquals(Step.WELCOME, BrowserOnboardingProgress.step(context))
        val saved = Bundle()
        lifecycle!!.saveInstanceState(saved).pause().stop().destroy()
        lifecycle = null
        val restored = open(saved = saved)
        assertTrue(texts(restored).contains(restored.getString(R.string.onboarding_iphone_select_title)))
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
        button(restored, R.string.onboarding_back).performClick()
        assertTrue(restored.isFinishing)
    }

    @Test fun contextualHelpPreservesCompletedSetup() {
        BrowserOnboardingProgress.confirmAudio(context)
        assertTrue(BrowserOnboardingProgress.finish(context, iphoneActive = true, videoRendered = true))
        open(contextualStep = Step.IPHONE)
        assertTrue(BrowserOnboardingProgress.completed(context))
        assertEquals(Step.READY, BrowserOnboardingProgress.step(context))
        assertFalse(BrowserOnboardingProgress.shouldResume(context))
    }

    private fun open(step: Step? = null, saved: Bundle? = null, review: Boolean = false,
        contextualStep: Step? = null): BrowserOnboardingActivity {
        step?.let { BrowserOnboardingProgress.moveTo(context, it) }
        val controller = Robolectric.buildActivity(BrowserOnboardingActivity::class.java,
            Intent(context, BrowserOnboardingActivity::class.java)
                .putExtra(BrowserOnboardingActivity.EXTRA_REVIEW, review)
                .apply { contextualStep?.let { putExtra(BrowserOnboardingActivity.EXTRA_STEP, it.name) } }).create(saved)
        lifecycle = controller
        controller.get().certificateSyncFactory = { mock(SharedCertificateSync::class.java) }
        controller.start().resume().visible()
        return controller.get()
    }

    private fun saveNetwork() {
        AirPlayPersistence.saveManualHotspotSsid(context, "Example hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "example-password")
    }

    private fun retainSession(screen: Context) = mock(CarPlayController::class.java).also {
        val display = CarPlaySessionDisplay(1920, 1440, Surface.ROTATION_0,
            hideTopBar = false, hideBottomBar = false, windowWidth = 1920, windowHeight = 1080)
        CarPlayBackgroundSession.store(it, mock(AndroidMediaSink::class.java), 1920, 1440, screen, display) {
            throw AssertionError("Reading or saving setup must not stop the receiver")
        }
    }

    private fun clearState() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(context)
        BrowserScreenOffCheck.stop()
        ShadowVpnService.setPrepareResult(null)
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding", "browser_session_recovery",
            "browser_screen_off_check").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    private fun setBatteryAllowed(allowed: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, allowed)
    }

    private fun grantConnectionPermissions() {
        shadowOf(context).grantPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
    }

    private fun grantConnectionRequirements() {
        grantConnectionPermissions()
        setRoutePhase(BrowserAddressService.Phase.ACTIVE)
    }

    private fun setRoutePhase(phase: BrowserAddressService.Phase) {
        ReflectionHelpers.setStaticField(BrowserAddressService::class.java, "state",
            BrowserAddressService.State(true, phase, "Test local address"))
    }

    private fun primaryActions(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().filter { it.tag == "onboarding_primary" }.toList()

    private fun button(screen: BrowserOnboardingActivity, id: Int) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == screen.getString(id) }
    private fun inputs(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView).filterIsInstance<EditText>().toList()
    private fun texts(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
