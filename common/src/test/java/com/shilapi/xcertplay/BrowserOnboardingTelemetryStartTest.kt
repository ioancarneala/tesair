package com.shilapi.xcertplay

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w393dp-h800dp", shadows = [OnboardingHotspotShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserOnboardingTelemetryStartTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: BrowserTelemetryEngine
    private var lifecycle: ActivityController<BrowserOnboardingActivity>? = null

    @Before fun prepare() {
        clearState()
        engine = BrowserTelemetryEngine(app, object : BrowserTelemetryTransport {
            override fun request(method: String, path: String, token: String?, body: String?): Int =
                throw AssertionError("An offline onboarding check must never upload")
        }, online = { false })
        BrowserTelemetry.replaceEngineForTest(app, engine)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        shadowOf(app).grantPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val paired = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(paired).setName("Example iPhone")
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setBondedDevices(setOf(paired))
        OnboardingHotspotShadow.apState = 13
        ShadowVpnService.setPrepareResult(null)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        while (shadowOf(app).nextStartedService != null) { /* Discard prior test requests. */ }
    }

    @After fun finish() {
        lifecycle?.pause()?.stop()?.destroy()
        engine.setEnabled(false)
        assertTrue(engine.awaitIdle())
        engine.shutdown()
        clearState()
        BrowserTelemetry.replaceEngineForTest(app, BrowserTelemetryEngine(app, online = { false }))
    }

    @Test fun startingWithoutASelectedIphoneReportsABlockedStepInsteadOfAFictitiousReceiverSession() {
        assertTrue(BrowserOnboardingProgress.approvalsAllowed(app))
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Example iPhone")
        val screen = open()
        // A selection cleared after rendering must be rechecked before starting the receiver.
        app.getSharedPreferences("diplay", 0).edit().remove("phone_address").commit()
        startButton(screen).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.awaitIdle())

        val events = events()
        assertTrue(events.any { it.getString("name") == "onboarding_step_blocked" &&
            it.getJSONObject("attributes").optString("reason") == "iphone_not_selected" })
        assertFalse(events.any { it.getString("name") in setOf("receiver_start_requested", "session_started", "connection_attempt_started") })
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun revokedApprovalsAfterRenderingReportABlockWithoutCreatingAReceiverSession() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Example iPhone")
        val screen = open()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
        startButton(screen).performClick()
        lifecycle!!.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.awaitIdle())

        val events = events()
        assertTrue(events.any { it.getString("name") == "onboarding_step_blocked" &&
            it.getJSONObject("attributes").optString("reason") == "approvals_required" })
        val snapshots = events.filter { it.getString("name") == "permission_result" &&
            it.getJSONObject("attributes").optString("source") == "snapshot" }
        assertTrue(snapshots.any { it.getJSONObject("attributes").optString("permission") == "background" &&
            it.getJSONObject("attributes").optString("result") == "not_granted" })
        assertFalse(snapshots.any { it.getJSONObject("attributes").optString("result") == "denied" })
        assertFalse(events.any { it.getString("name") in setOf("receiver_start_requested", "session_started", "connection_attempt_started") })
        assertEquals(Step.BACKGROUND, BrowserOnboardingProgress.step(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun open(): BrowserOnboardingActivity {
        BrowserOnboardingProgress.moveTo(app, Step.IPHONE)
        lifecycle = Robolectric.buildActivity(BrowserOnboardingActivity::class.java).create()
        return lifecycle!!.get().also { screen ->
            screen.certificateSyncFactory = { mock(SharedCertificateSync::class.java) }
            lifecycle!!.start().resume().visible()
        }
    }

    private fun startButton(screen: BrowserOnboardingActivity) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.text == screen.getString(R.string.onboarding_iphone_start) }

    private fun events(): List<JSONObject> {
        val events = JSONArray(File(app.noBackupFilesDir, "telemetry/events.json").readText())
        return (0 until events.length()).map { events.getJSONObject(it) }
    }

    private fun clearState() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        BrowserAddressService.disable(app)
        listOf("diplay", "xcertplay_airplay", "browser_onboarding", "browser_session_recovery", "browser_telemetry")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        File(app.noBackupFilesDir, "telemetry").deleteRecursively()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
