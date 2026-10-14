package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-w393dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserSettingsTelemetryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: BrowserTelemetryEngine
    private var lifecycle: ActivityController<BrowserSettingsActivity>? = null

    @Before fun prepare() {
        clearPreferences()
        File(app.noBackupFilesDir, "telemetry").deleteRecursively()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        BrowserSessionRecovery.markStopped(app)
        engine = BrowserTelemetryEngine(app, object : BrowserTelemetryTransport {
            override fun request(method: String, path: String, token: String?, body: String?): Int =
                throw AssertionError("Offline UI checks must not send any report")
        }, online = { false })
        BrowserTelemetry.replaceEngineForTest(app, engine)
        AirPlayPersistence.saveManualHotspotSsid(app, "test-only-private-network")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "test-only-private-password")
        AirPlayPersistence.saveBrowserResolutionPercent(app, 100)
        AirPlayPersistence.saveFps(app, 30)
        AirPlayPersistence.saveWidthPhysicalMm(app, 300)
    }

    @After fun finish() {
        lifecycle?.destroy()
        engine.setEnabled(false)
        assertTrue(engine.awaitIdle())
        engine.shutdown()
        clearPreferences()
        BrowserTelemetry.replaceEngineForTest(app, BrowserTelemetryEngine(app, online = { false }))
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    @Test fun changedPicturePreferencesAreRecordedAndUnchangedOrCancelledChoicesAreNot() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val screen = open()
        row(screen, R.string.settings_browser_preferences_picture).performClick()
        choose(screen, R.string.settings_browser_preferences_quality, 2)
        choose(screen, R.string.settings_browser_preferences_smoothness, 1)
        choose(screen, R.string.settings_browser_preferences_interface_size, 2)
        assertTrue(engine.awaitIdle())
        val changes = events().filter { it.getString("name") == "configuration_saved" &&
            it.getJSONObject("attributes").optString("source") == "preferences" }
        assertEquals(3, changes.size)
        val last = changes.last().getJSONObject("attributes")
        assertEquals(160, last.getInt("qualityPercent"))
        assertEquals(60, last.getInt("fpsRequested"))
        assertEquals(150, last.getInt("interfaceScale"))
        assertEquals("saved", last.getString("result"))
        assertFalse(events().toString().contains("test-only-private"))

        // Keeping the current choice and cancelling another choice must produce no new event.
        choose(screen, R.string.settings_browser_preferences_quality, 2)
        row(screen, R.string.settings_browser_preferences_smoothness).performClick()
        dialog().listView.performItemClick(null, 0, 0)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(engine.awaitIdle())
        assertEquals(3, events().count { it.getJSONObject("attributes").optString("source") == "preferences" })
        assertEquals(60, AirPlayPersistence.loadFps(app))
    }

    @Test fun privacyWithdrawalAndDeletionKeepCarPlayConfigurationAndProduceANewIdentityOnReconsent() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val originalId = engine.installationId()
        val screen = open()
        row(screen, R.string.browser_telemetry_settings).performClick()
        button(screen, R.string.browser_telemetry_change).performClick()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.awaitIdle())
        assertFalse(BrowserTelemetry.enabled(app))
        assertEquals(100, AirPlayPersistence.loadBrowserResolutionPercent(app))
        assertEquals("test-only-private-network", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("test-only-private-password", AirPlayPersistence.loadManualHotspotPassphrase(app))

        button(screen, R.string.browser_telemetry_delete).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.awaitIdle())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE).getString("installationId", null))
        assertEquals(30, AirPlayPersistence.loadFps(app))
        assertEquals(300, AirPlayPersistence.loadWidthPhysicalMm(app))
        assertEquals("test-only-private-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertNotEquals(originalId, engine.installationId())
    }

    private fun choose(screen: BrowserSettingsActivity, label: Int, position: Int) {
        row(screen, label).performClick()
        val picker = dialog()
        picker.listView.performItemClick(null, position, position.toLong())
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun open(): BrowserSettingsActivity = Robolectric.buildActivity(BrowserSettingsActivity::class.java)
        .setup().also { lifecycle = it }.get()
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun row(screen: BrowserSettingsActivity, title: Int) =
        descendants(screen.window.decorView).single { it.tag == title }
    private fun button(screen: BrowserSettingsActivity, title: Int) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.text == screen.getString(title) }
    private fun events(): List<JSONObject> {
        val array = JSONArray(File(app.noBackupFilesDir, "telemetry/events.json").readText())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun clearPreferences() {
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding", "browser_telemetry")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
