package com.shilapi.xcertplay

import android.app.ActivityManager
import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.PowerManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.host.R
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w393dp-h800dp", shadows = [OnboardingHotspotShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserIphonePairingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private var lifecycle: ActivityController<BrowserOnboardingActivity>? = null

    @Before fun prepare() {
        clearState()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        shadowOf(app).grantPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        adapter.setName("Test Android")
        shadowOf(adapter).setBondedDevices(emptySet())
        hotspot(true)
        ShadowVpnService.setPrepareResult(null)
        while (shadowOf(app).nextStartedService != null) { /* Discard earlier test requests. */ }
    }

    @After fun finish() { lifecycle?.pause()?.stop()?.destroy(); clearState() }

    @Test fun bluetoothOffShowsOneSystemEnableActionAndKeepsTheSavedIphone() {
        bondIphone()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        val screen = open()
        assertEquals(listOf(screen.getString(R.string.onboarding_bluetooth_enable_action)), primaries(screen))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_bluetooth_enable_title)))
        button(screen, R.string.onboarding_bluetooth_enable_action).performClick()
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, shadowOf(screen).nextStartedActivityForResult.intent.action)
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun pairingShowsActualAndroidNameAndDoesNotAskForTheHotspotPassword() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Synthetic Hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "synthetic-pass-123")
        val screen = open()
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_visible)), primaries(screen))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_bluetooth_name, "Test Android")))
        assertFalse(texts(screen).contains("Synthetic Hotspot"))
        assertFalse(texts(screen).contains("synthetic-pass-123"))
        assertTrue(buttons(screen).none { it.text == screen.getString(R.string.onboarding_iphone_start) })
        button(screen, R.string.onboarding_iphone_visible).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult.intent
        assertEquals(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE, request.action)
        assertEquals(300, request.getIntExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0))
    }

    @Test fun hotspotOffBlocksStartAndLiveRecheckShowsTheNextActionWhenEnabled() {
        bondIphone()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        hotspot(false)
        val screen = open()
        assertEquals(listOf(screen.getString(R.string.onboarding_network_settings)), primaries(screen))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_hotspot_off_body)))
        assertTrue(buttons(screen).none { it.text == screen.getString(R.string.onboarding_iphone_start) })
        assertNull(shadowOf(app).nextStartedService)
        hotspot(true)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_start)), primaries(screen))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
    }

    @Test fun returningFromBluetoothSettingsRechecksTheAdapterInsteadOfAssumingItWasEnabled() {
        bondIphone()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        val screen = open()
        lifecycle!!.pause()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        lifecycle!!.resume()
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_start)), primaries(screen))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun autoSelectionRequiresExactlyOneNewRecognizedIphoneAndPreservesAnExistingSelection() {
        val known = setOf("old")
        assertEquals("new", BrowserOnboardingProgress.newlyPairedIphone(known, listOf("old" to true, "new" to true), null))
        assertNull(BrowserOnboardingProgress.newlyPairedIphone(known, listOf("old" to true), null))
        assertNull(BrowserOnboardingProgress.newlyPairedIphone(known, listOf("new" to false), null))
        assertNull(BrowserOnboardingProgress.newlyPairedIphone(known, listOf("new" to true, "another" to true), null))
        assertNull(BrowserOnboardingProgress.newlyPairedIphone(known, listOf("new" to true, "keyboard" to false), null))
        assertNull(BrowserOnboardingProgress.newlyPairedIphone(known, listOf("new" to true), "saved"))
    }

    @Test fun missingSavedBondPreservesTheSelectionAndOffersPairingInsteadOfStart() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val screen = open()
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_visible)), primaries(screen))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun aNewIphoneReplacesAnOlderWaitingReceiverConfiguration() {
        val waiting = mock(BrowserCarPlayRuntime::class.java)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", waiting)
        assertFalse(waiting.manualRecoveryPending)
        val screen = open()
        button(screen, R.string.onboarding_iphone_visible).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, 300, null)
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        bondIphone()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        val start = shadowOf(app).nextStartedService
        assertEquals(DiPlaySessionService.ACTION_RECONNECT, start?.action)
        assertTrue(start!!.getBooleanExtra(DiPlaySessionService.EXTRA_MANUAL_START, false))
    }

    @Test fun theAcceptedDurationIsBoundedAndExpiresWithoutPollingScanMode() {
        val screen = open()
        button(screen, R.string.onboarding_iphone_visible).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, 3_600, null)
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(301))
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_visible)), primaries(screen))
        assertNull(DiPlayPreferences.phoneAddress(app))
    }

    @Test fun aDeclinedRequestOffersANewPairedIphoneForExplicitSelectionWithoutClaimingVisibility() {
        val screen = open()
        button(screen, R.string.onboarding_iphone_visible).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(screen).nextStartedActivity.action)
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_visible)), primaries(screen))
        assertFalse(texts(screen).contains(screen.getString(R.string.onboarding_iphone_pair_confirm)))
        bondIphone()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        assertNull(DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun anExistingBondOffersSelectionAfterSettingsFallbackWithoutAutoSelectingIt() {
        bondIphone()
        val screen = open()
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        button(screen, R.string.onboarding_iphone_pair_another).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        assertNull(DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test @Config(sdk = [31]) fun acceptedSystemResponseShowsPairingWithoutBluetoothScanPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val screen = open()
        button(screen, R.string.onboarding_iphone_visible).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, 300, null)
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN))
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_choose)), primaries(screen))
        assertTrue(texts(screen).contains(screen.getString(R.string.onboarding_iphone_pair_confirm)))
    }

    @Test fun pendingSystemDialogDoesNotClaimVisibilityOrAutoSelectABond() {
        val screen = open()
        button(screen, R.string.onboarding_iphone_visible).performClick()
        assertEquals(listOf(screen.getString(R.string.onboarding_iphone_visible)), primaries(screen))
        assertFalse(texts(screen).contains(screen.getString(R.string.onboarding_iphone_pair_confirm)))
        bondIphone()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "updateLiveState")
        assertNull(DiPlayPreferences.phoneAddress(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun open(): BrowserOnboardingActivity {
        BrowserOnboardingProgress.moveTo(app, Step.IPHONE)
        lifecycle = Robolectric.buildActivity(BrowserOnboardingActivity::class.java).create()
        lifecycle!!.get().certificateSyncFactory = { mock(SharedCertificateSync::class.java) }
        return lifecycle!!.start().resume().visible().get()
    }

    private fun clearState() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(app)
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding", "browser_session_recovery")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    private fun hotspot(enabled: Boolean) { OnboardingHotspotShadow.apState = if (enabled) 13 else 11 }
    private fun bondIphone() {
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(device).setName("Test iPhone")
        shadowOf(adapter).setBondedDevices(setOf(device))
    }
    private fun primaries(screen: BrowserOnboardingActivity) = buttons(screen)
        .filter { it.tag == "onboarding_primary" && it.visibility == View.VISIBLE }.map { it.text.toString() }
    private fun button(screen: BrowserOnboardingActivity, id: Int) = buttons(screen).single { it.text == screen.getString(id) }
    private fun buttons(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView).filterIsInstance<Button>().toList()
    private fun texts(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

@Implements(WifiManager::class)
class OnboardingHotspotShadow : ShadowWifiManager() {
    @Implementation fun getWifiApState(): Int = apState
    companion object { var apState = 13 }
}
