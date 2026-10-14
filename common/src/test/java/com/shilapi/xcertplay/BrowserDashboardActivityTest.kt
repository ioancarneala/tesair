package com.shilapi.xcertplay

import android.content.Intent
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.ActivityManager
import android.Manifest
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowBluetoothAdapter
import com.shilapi.xcertplay.browser.BrowserStreamOutput

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserDashboardActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<BrowserDashboardActivity>? = null

    @Before fun setUp() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("browser_session_recovery", 0).edit().clear().commit()
        app.getSharedPreferences("browser_onboarding", 0).edit().clear().commit()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(app)
        ShadowVpnService.setPrepareResult(null)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        CarPlayBackgroundSession.clear()
        shadowOf(app).grantPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        while (shadowOf(app).nextStartedService != null) { /* Discard earlier requests. */ }
    }

    @After fun tearDown() {
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
        lifecycle?.destroy()
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(app)
        ShadowVpnService.setPrepareResult(null)
    }

    @Test @Config(sdk = [28, 31]) fun missingBluetoothHardwareShowsNoFalseRepairAndDoesNotLaunchAReceiver() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create().start().resume()
        val screen = lifecycle!!.get()
        assertTrue(descendants(screen.window.decorView).filterIsInstance<TextView>()
            .any { it.text == screen.getString(R.string.browser_bluetooth_unavailable_title) })
        val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(screen, "dashboard")
        val state = ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state")
        assertEquals(BrowserDashboardView.Step.BLUETOOTH_UNAVAILABLE, state.step)
        assertEquals(BrowserDashboardView.Action.NONE, state.action)
        assertNull(shadowOf(app).nextStartedService)
        descendants(screen.window.decorView).filterIsInstance<Button>()
            .single { it.text == screen.getString(R.string.browser_dashboard_show_details) }.performClick()
        assertTrue(descendants(screen.window.decorView).filterIsInstance<Button>()
            .none { it.visibility == View.VISIBLE && it.text == screen.getString(R.string.retry_carplay_connection) })
    }

    @Test @Config(sdk = [31]) fun missingRuntimeGrantStillRequestsAccessInsteadOfClaimingBluetoothHardwareIsMissing() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create().start().resume()
        val screen = lifecycle!!.get()
        val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(screen, "dashboard")
        val state = ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state")
        assertEquals(BrowserDashboardView.Step.PERMISSIONS, state.step)
        assertFalse(state.bluetoothUnavailable)
    }

    @Test @Config(sdk = [28, 31]) fun anActivePictureIsPreservedIfTheBluetoothAdapterBecomesUnavailable() {
        val output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        `when`(output.clientCount).thenReturn(1)
        `when`(output.hasRenderedVideo).thenReturn(true)
        `when`(output.urls).thenReturn(listOf("https://carplay.tesair.co:8443"))
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        val stop: ((() -> Unit) -> Unit) = { throw AssertionError("A hardware read must not stop an active session") }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        CarPlayBackgroundSession.active = true
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        val screen = openDashboard()
        val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(screen, "dashboard")
        val state = ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state")
        assertFalse(state.bluetoothUnavailable)
        assertEquals(BrowserDashboardView.Step.READY, state.step)
        assertTrue(CarPlayBackgroundSession.active)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun openingTheDashboardStartsOnlyTheServiceAndClosingItDoesNotStopTheReceiver() {
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).setup()
        val start = shadowOf(app).nextStartedService
        assertEquals(DiPlaySessionService::class.java.name, start?.component?.className)
        assertEquals(DiPlaySessionService.ACTION_RECOVER, start?.action)
        assertNull(shadowOf(lifecycle!!.get()).nextStartedActivity)
        lifecycle!!.pause().stop().destroy()
        lifecycle = null
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun explicitReconnectIsDeliveredToTheServiceWithoutStoppingIt() {
        val intent = Intent().putExtra(BrowserDashboardActivity.EXTRA_RECONNECT, true)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java, intent).create()
        val start = shadowOf(app).nextStartedService
        assertEquals(DiPlaySessionService::class.java.name, start?.component?.className)
        assertEquals(DiPlaySessionService.ACTION_RECONNECT, start?.action)
        assertFalse(lifecycle!!.get().intent.hasExtra(BrowserDashboardActivity.EXTRA_RECONNECT))
        assertNull(shadowOf(lifecycle!!.get()).nextStartedActivity)
    }

    @Test fun backMinimizesTheDashboardWithoutOpeningAnOldHomeOrStoppingTheReceiver() {
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        CarPlayBackgroundSession.active = true
        val activity = openDashboard()

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.active)
    }

    @Test fun settingsOpensTheDedicatedProductScreenWithoutRequestingAReconnect() {
        val activity = openDashboard()
        assertEquals(1, descendants(activity.window.decorView).filterIsInstance<TextView>()
            .count { it.text.toString() == activity.getString(R.string.browser_product_name) })
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == activity.getString(R.string.settings) }.performClick()

        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(BrowserSettingsActivity::class.java.name, settings?.component?.className)
        assertFalse(settings!!.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
        lifecycle!!.pause().resume()
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test @Config(sdk = [31, 33]) fun missingWirelessPermissionDoesNotStartTheForegroundService() {
        shadowOf(app).denyPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun returningToTheDashboardDoesNotRequestAReconnect() {
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create()
        shadowOf(app).nextStartedService
        ReflectionHelpers.callInstanceMethod<Unit>(lifecycle!!.get(), "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, Intent()))
        val start = shadowOf(app).nextStartedService
        assertEquals(DiPlaySessionService::class.java.name, start?.component?.className)
        assertEquals(DiPlaySessionService.ACTION_RECOVER, start?.action)
        assertNull(shadowOf(lifecycle!!.get()).nextStartedActivity)
    }

    @Test fun restoringTheDashboardDoesNotReplayAnExplicitConnectAfterStop() {
        val intent = Intent().putExtra(BrowserDashboardActivity.EXTRA_RECONNECT, true)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java, intent)
            .create(Bundle())
        val start = shadowOf(app).nextStartedService
        assertEquals(DiPlaySessionService.ACTION_RECOVER, start?.action)
        assertFalse(lifecycle!!.get().intent.hasExtra(BrowserDashboardActivity.EXTRA_RECONNECT))
    }

    @Test @Config(sdk = [31, 33]) fun recreationPreservesTheConnectWaitingForPermissionWithoutStartingEarly() {
        shadowOf(app).denyPermissions(*WirelessPermissions.required(
            Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        val intent = Intent().putExtra(BrowserDashboardActivity.EXTRA_RECONNECT, true)
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java, intent).create()
        val saved = Bundle()
        lifecycle!!.saveInstanceState(saved).destroy()
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create(saved)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(ReflectionHelpers.getField(lifecycle!!.get(), "awaitingPermissions"))
        assertTrue(ReflectionHelpers.getField(lifecycle!!.get(), "reconnectAfterPermissions"))
    }

    @Test fun repairActionsOpenTheirSystemSettingsWithoutReconnectingCarplay() {
        val activity = openDashboard()
        val actions = listOf(
            "openHotspotSettings" to "android.settings.TETHER_SETTINGS",
            "openBluetoothSettings" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "openVpnSettings" to Settings.ACTION_VPN_SETTINGS,
            "openInternetSettings" to if (Build.VERSION.SDK_INT >= 29)
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS,
        )
        actions.forEach { (method, action) ->
            ReflectionHelpers.callInstanceMethod<Unit>(activity, method)
            assertEquals(action, shadowOf(activity).nextStartedActivity?.action)
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun iphoneHelpTargetsTheIphoneStepWithoutResettingSavedProgress() {
        val activity = openDashboard()
        BrowserOnboardingProgress.moveTo(app, BrowserOnboardingProgress.Step.TESLA_BROWSER)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openIphoneGuide")
        val guide = shadowOf(activity).nextStartedActivity
        assertEquals(BrowserOnboardingActivity::class.java.name, guide?.component?.className)
        assertEquals(BrowserOnboardingProgress.Step.IPHONE.name,
            guide?.getStringExtra(BrowserOnboardingActivity.EXTRA_STEP))
        assertFalse(guide!!.getBooleanExtra(BrowserOnboardingActivity.EXTRA_REVIEW, false))
        assertEquals(BrowserOnboardingProgress.Step.TESLA_BROWSER, BrowserOnboardingProgress.step(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun backgroundActionUsesCurrentAndroidStateAndOpeningSettingsDoesNotGrantIt() {
        val activity = openDashboard()
        val power = shadowOf(activity.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(activity.packageName, false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allowBackground")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            shadowOf(activity).nextStartedActivity?.action)
        lifecycle!!.pause().resume()
        assertFalse(BrowserBackgroundReadiness.snapshot(activity).allowed)

        power.setIgnoringBatteryOptimizations(activity.packageName, true)
        shadowOf(activity.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allowBackground")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action)
        assertFalse(BrowserBackgroundReadiness.snapshot(activity).allowed)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun returningFromInternetOrBluetoothSettingsLeavesAutomaticRecoveryAlone() {
        val activity = openDashboard()
        listOf("openInternetSettings", "openBluetoothSettings").forEach { method ->
            ReflectionHelpers.callInstanceMethod<Unit>(activity, method)
            shadowOf(activity).nextStartedActivity
            lifecycle!!.pause().resume()
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test @Config(shadows = [EnabledHotspotShadow::class])
    fun terminalHotspotRepairSurvivesRecreationAndRetriesOnlyOnceAfterSettings() {
        val activity = openDashboard()
        setHotspotFailure(terminal = true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openHotspotSettings")
        shadowOf(activity).nextStartedActivity
        val saved = Bundle()
        lifecycle!!.saveInstanceState(saved).pause().stop().destroy()
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create(saved)
        assertEquals(DiPlaySessionService.ACTION_RECOVER, shadowOf(app).nextStartedService?.action)
        lifecycle!!.start().resume()
        assertEquals(DiPlaySessionService.ACTION_RECONNECT, shadowOf(app).nextStartedService?.action)
        lifecycle!!.pause().resume()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test @Config(shadows = [EnabledHotspotShadow::class])
    fun hotspotReturnNeverInterruptsAnActiveIphoneOrDuplicatesAutomaticRetry() {
        val activity = openDashboard()
        setHotspotFailure(terminal = true)
        CarPlayBackgroundSession.active = true
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openHotspotSettings")
        lifecycle!!.pause().resume()
        assertNull(shadowOf(app).nextStartedService)

        CarPlayBackgroundSession.active = false
        setHotspotFailure(terminal = false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openHotspotSettings")
        lifecycle!!.pause().resume()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test @Config(shadows = [DisabledHotspotShadow::class])
    fun inactiveSystemHotspotIsShownBeforeAnyStartupFailure() {
        val activity = openDashboard()
        val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(activity, "dashboard")
        assertTrue(ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state").hotspotNeedsAttention)

        CarPlayBackgroundSession.active = true
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateStatus")
        assertFalse(ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state").hotspotNeedsAttention)

        CarPlayBackgroundSession.active = false
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateStatus")
        assertFalse(ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state").hotspotNeedsAttention)
    }

    @Test fun returningFromVpnSettingsRestartsOnlyTheBrowserRoute() {
        val activity = openDashboard()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openVpnSettings")
        assertEquals(Settings.ACTION_VPN_SETTINGS, shadowOf(activity).nextStartedActivity?.action)
        lifecycle!!.pause().resume()
        val route = shadowOf(app).nextStartedService
        assertEquals(BrowserAddressService::class.java.name, route?.component?.className)
        assertEquals(BrowserAddressService.ACTION_START, route?.action)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun vpnTakeoverUsesLiveEvidenceEvenWhenTheRouteWasRevoked() {
        val route = Robolectric.buildService(BrowserAddressService::class.java).create()
        try {
            route.get().onRevoke()
            assertEquals(BrowserAddressService.Phase.REVOKED, BrowserAddressService.snapshot(app).phase)
            val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
            val network = ShadowNetwork.newInstance(123)
            @Suppress("DEPRECATION")
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN, 0, true, true)
            manager.addNetwork(network, info)
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            manager.setNetworkCapabilities(network, capabilities)
            val activity = openDashboard()
            val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(activity, "dashboard")
            assertTrue(ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state").otherVpn)
            manager.clearAllNetworks()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateStatus")
            assertFalse(ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state").otherVpn)
        } finally {
            route.destroy()
        }
    }

    @Test @Config(shadows = [EnabledHotspotShadow::class])
    fun explicitStopOffersAStartActionWithoutSilentlyStartingOrClearingStop() {
        val controller = Robolectric.buildActivity(BrowserDashboardActivity::class.java)
        lifecycle = controller
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        BrowserSessionRecovery.markStopped(activity)
        controller.create().start().resume()
        assertEquals(DiPlaySessionService.ACTION_RECOVER, shadowOf(app).nextStartedService?.action)
        assertTrue(BrowserSessionRecovery.stopped(activity))
        val dashboard = ReflectionHelpers.getField<BrowserDashboardView>(activity, "dashboard")
        val state = ReflectionHelpers.getField<BrowserDashboardView.State>(dashboard, "state")
        assertTrue(state.receiverFailed)
        assertNull(shadowOf(app).nextStartedService)

        setHotspotFailure(terminal = true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openHotspotSettings")
        lifecycle!!.pause().resume()
        assertTrue(BrowserSessionRecovery.stopped(activity))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun openDashboard(): BrowserDashboardActivity {
        lifecycle = Robolectric.buildActivity(BrowserDashboardActivity::class.java).create().start().resume()
        assertEquals(DiPlaySessionService.ACTION_RECOVER, shadowOf(app).nextStartedService?.action)
        return lifecycle!!.get()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun setHotspotFailure(terminal: Boolean) {
        val runtime = mock(BrowserCarPlayRuntime::class.java)
        `when`(runtime.setupNeedsAttention).thenReturn(terminal)
        `when`(runtime.status).thenReturn(CarPlayStatus.Failed("Hotspot test",
            startupFailure = if (terminal) WirelessStartupFailure.HOTSPOT_CONFIGURATION
                else WirelessStartupFailure.HOTSPOT_NOT_READY))
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", runtime)
    }

    @Implements(WifiManager::class)
    class EnabledHotspotShadow : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = 13
    }

    @Implements(WifiManager::class)
    class DisabledHotspotShadow : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = 11
    }
}
