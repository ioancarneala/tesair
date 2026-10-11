package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w393dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserPreferencesActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<BrowserSettingsActivity>? = null

    @Before fun prepare() {
        clearPreferences()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Example hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "test-only-password")
        DiPlayPreferences.savePhone(app, "00:00:00:00:00:01", "Test iPhone")
    }

    @After fun finish() {
        lifecycle?.destroy()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        clearPreferences()
    }

    @Test fun homeKeepsBrowserBrandAndOnlyRelevantDestinations() {
        val screen = open()
        assertTrue(screen.getString(R.string.browser_product_name) in texts(screen))
        listOf(R.string.connection, R.string.settings_browser_preferences_picture,
            R.string.settings_browser_preferences_help, R.string.language_app_language,
            R.string.about).forEach { assertNotNull(row(screen, it)) }
        assertFalse(texts(screen).any { it.contains(screen.getString(R.string.settings_advanced)) })
        assertFalse(texts(screen).any { it.contains(screen.getString(R.string.settings_browser_audio_destination)) })
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    @Test fun connectionSummaryDoesNotExposePasswordAndUsesExistingPreferences() {
        val screen = open()
        val controller = retainSession(screen)
        row(screen, R.string.connection).performClick()
        val visible = texts(screen)
        assertTrue("Test iPhone" in visible)
        assertTrue("Example hotspot" in visible)
        assertTrue("••••••••" in visible)
        assertFalse(visible.any { "test-only-password" in it })
        switches(screen).single { it.text == screen.getString(R.string.settings_browser_preferences_auto_open) }
            .isChecked = true
        switches(screen).single { it.text == screen.getString(R.string.settings_browser_preferences_after_restart) }
            .isChecked = true
        assertTrue(DiPlayPreferences.autoConnect(app))
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun helpKeepsPendingReconnectAsTheOnlyPrimaryAction() {
        val screen = open()
        val controller = retainSession(screen)
        PendingReconnect.mark(controller)
        row(screen, R.string.settings_browser_preferences_help).performClick()
        val guide = buttons(screen).single { it.text == screen.getString(R.string.settings_browser_preferences_setup_guide) }
        val apply = buttons(screen).single { it.text == screen.getString(R.string.apply_and_reconnect) }
        assertEquals(BrowserDesign.onboarding(screen).accent, guide.currentTextColor)
        assertEquals(BrowserDesign.onboarding(screen).onAccent, apply.currentTextColor)
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun pictureUsesFriendlyChoicesAndQualityQueuesExplicitReconnectWithoutDroppingVideo() {
        AirPlayPersistence.saveDisplayScalePercent(app, 75)
        val screen = openPicture()
        val controller = retainSession(screen)
        val visible = texts(screen)
        assertTrue(screen.getString(R.string.settings_browser_preferences_balanced) in visible)
        assertFalse(visible.any { it.contains(" mm") || it.contains("%") || it.contains("fps") || it.contains(" × ") })
        row(screen, R.string.settings_browser_preferences_quality).performClick()
        val dialog = latestDialog()
        assertEquals(3, dialog.listView.adapter.count)
        assertEquals(0, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 2, 2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(160, AirPlayPersistence.loadBrowserResolutionPercent(app))
        assertEquals(75, AirPlayPersistence.loadDisplayScalePercent(app))
        assertTrue(PendingReconnect.isPending(controller))
        assertTrue(screen.getString(R.string.settings_browser_preferences_sharpest) in texts(screen))
        assertEquals(1, buttons(screen).count { it.text == screen.getString(R.string.apply_and_reconnect) })
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun oldCustomPictureValuesStayExactUntilAnotherPresetIsChosen() {
        AirPlayPersistence.saveWidthPhysicalMm(app, 250)
        AirPlayPersistence.saveBrowserResolutionPercent(app, 137)
        AirPlayPersistence.saveFps(app, 45)
        val screen = openPicture()
        val controller = retainSession(screen)
        val customChoices = listOf(
            R.string.settings_browser_preferences_interface_size to R.string.settings_browser_preferences_keep_size,
            R.string.settings_browser_preferences_quality to R.string.settings_browser_preferences_keep_quality,
            R.string.settings_browser_preferences_smoothness to R.string.settings_browser_preferences_keep_motion,
        )
        customChoices.forEach { (title, keep) ->
            row(screen, title).performClick()
            val dialog = latestDialog()
            val selected = dialog.listView.checkedItemPosition
            assertEquals(screen.getString(keep), dialog.listView.adapter.getItem(selected))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(250, AirPlayPersistence.loadWidthPhysicalMm(app))
        assertEquals(137, AirPlayPersistence.loadBrowserResolutionPercent(app))
        assertEquals(45, AirPlayPersistence.loadFps(app))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun legacyQualityAtTheCurrentCanvasCeilingIsNotSilentlyChangedToSharpest() {
        AirPlayPersistence.saveBrowserResolutionPercent(app, 150)
        val screen = openPicture()
        val controller = retainSession(screen)
        assertTrue(screen.getString(R.string.settings_browser_preferences_custom_quality) in texts(screen))
        row(screen, R.string.settings_browser_preferences_quality).performClick()
        val dialog = latestDialog()
        assertEquals(3, dialog.listView.checkedItemPosition)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(150, AirPlayPersistence.loadBrowserResolutionPercent(app))
        assertFalse(PendingReconnect.isPending(controller))
        verifyNoInteractions(controller)
    }

    @Test fun sizeDialogCancelsWithoutChangingAnythingAndConfirmsReconnectExplicitly() {
        AirPlayPersistence.saveWidthPhysicalMm(app, 150)
        AirPlayPersistence.saveBrowserResolutionPercent(app, 160)
        AirPlayPersistence.saveBrowserDirectAudio(app, true)
        val screen = openPicture()
        val controller = retainSession(screen)
        row(screen, R.string.settings_browser_preferences_interface_size).performClick()
        var dialog = latestDialog()
        assertEquals(4, dialog.listView.adapter.count)
        assertEquals(2, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(150, AirPlayPersistence.loadWidthPhysicalMm(app))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)

        row(screen, R.string.settings_browser_preferences_interface_size).performClick()
        dialog = latestDialog()
        assertEquals(screen.getString(R.string.apply_and_reconnect),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.listView.performItemClick(null, 3, 3)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(100, AirPlayPersistence.loadWidthPhysicalMm(app))
        assertEquals(160, AirPlayPersistence.loadBrowserResolutionPercent(app))
        assertTrue(AirPlayPersistence.loadBrowserDirectAudio(app))
        val target = shadowOf(screen).nextStartedActivity
        assertEquals(BrowserDashboardActivity::class.java.name, target?.component?.className)
        assertTrue(target!!.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false))
        verifyNoInteractions(controller)
    }

    @Test fun unchangedSizeAndCancelledFpsDoNotReconnect() {
        val screen = openPicture()
        val controller = retainSession(screen)
        row(screen, R.string.settings_browser_preferences_interface_size).performClick()
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(PendingReconnect.isPending(controller))
        row(screen, R.string.settings_browser_preferences_smoothness).performClick()
        val dialog = latestDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(30, AirPlayPersistence.loadFps(app))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun fpsAndDrivingSideWaitForApplyButAppearanceDoesNotNeedReconnect() {
        val screen = openPicture()
        val controller = retainSession(screen)
        row(screen, R.string.settings_browser_preferences_appearance).performClick()
        var dialog = latestDialog()
        dialog.listView.performItemClick(null, 2, 2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayNightMode.NIGHT, AirPlayPersistence.loadCarPlayNightMode(app))
        assertFalse(PendingReconnect.isPending(controller))
        row(screen, R.string.settings_browser_preferences_smoothness).performClick()
        dialog = latestDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(60, AirPlayPersistence.loadFps(app))
        assertTrue(PendingReconnect.isPending(controller))
        PendingReconnect.clear()
        switches(screen).single().isChecked = true
        assertTrue(AirPlayPersistence.loadRightHandDrive(app))
        assertTrue(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun guideOpensContextualHelpWithoutClearingSetupOrConnecting() {
        val screen = open()
        val controller = retainSession(screen)
        BrowserOnboardingProgress.moveTo(app, BrowserOnboardingProgress.Step.TESLA_BROWSER)
        row(screen, R.string.settings_browser_preferences_help).performClick()
        buttons(screen).single { it.text == screen.getString(R.string.settings_browser_preferences_setup_guide) }
            .performClick()
        val guide = shadowOf(screen).nextStartedActivity
        assertEquals(BrowserOnboardingActivity::class.java.name, guide?.component?.className)
        assertEquals(BrowserOnboardingProgress.Step.IPHONE.name,
            guide?.getStringExtra(BrowserOnboardingActivity.EXTRA_STEP))
        assertEquals(BrowserOnboardingProgress.Step.TESLA_BROWSER, BrowserOnboardingProgress.step(app))
        verifyNoInteractions(controller)
    }

    @Test fun schedulePickerUsesSavedTimeAndRejectsIdenticalStartAndEnd() {
        AirPlayPersistence.saveCarPlayNightMode(app, CarPlayNightMode.SCHEDULE)
        AirPlayPersistence.saveCarPlayNightSchedule(app, CarPlayNightSchedule(18 * 60 + 30, 6 * 60 + 15))
        val screen = openPicture()
        val controller = retainSession(screen)
        row(screen, R.string.carplay_night_start).performClick()
        val dialog = latestDialog()
        val clock = descendants(dialog.window!!.decorView).filterIsInstance<TimePicker>().single()
        assertEquals(18, clock.hour)
        assertEquals(30, clock.minute)
        fun chooseTime(hour: Int, minute: Int) {
            // performClick does not move focus as a real tap does. Commit any focused
            // numeric editor before each programmatic change, including after validation.
            shadowOf(Looper.getMainLooper()).idle()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            shadowOf(Looper.getMainLooper()).idle()
            clock.hour = hour
            clock.minute = minute
            assertEquals(hour, clock.hour)
            assertEquals(minute, clock.minute)
        }
        chooseTime(6, 15)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue("Equal start/end must keep the dialog open; saved=${AirPlayPersistence.loadCarPlayNightSchedule(app)}",
            dialog.isShowing)
        assertEquals(18 * 60 + 30, AirPlayPersistence.loadCarPlayNightSchedule(app).startMinute)
        chooseTime(19, 45)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse("A different start/end must save; saved=${AirPlayPersistence.loadCarPlayNightSchedule(app)}",
            dialog.isShowing)
        assertEquals(CarPlayNightSchedule(19 * 60 + 45, 6 * 60 + 15),
            AirPlayPersistence.loadCarPlayNightSchedule(app))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun navigatingBackFromPictureDoesNotApplyPendingChanges() {
        val screen = openPicture()
        val controller = retainSession(screen)
        PendingReconnect.mark(controller)
        screen.onBackPressedDispatcher.onBackPressed()
        assertNotNull(row(screen, R.string.connection))
        assertTrue(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    @Test fun reusingSettingsHonorsAnAboutDeepLinkWithoutReconnecting() {
        val screen = openPicture()
        val controller = retainSession(screen)
        lifecycle!!.newIntent(Intent().putExtra("page", "about"))
        assertTrue(screen.getString(R.string.made_possible_by_open_source) in texts(screen))
        assertFalse(PendingReconnect.isPending(controller))
        assertNull(shadowOf(screen).nextStartedActivity)
        verifyNoInteractions(controller)
    }

    private fun open(): BrowserSettingsActivity {
        lifecycle = Robolectric.buildActivity(BrowserSettingsActivity::class.java).setup()
        return lifecycle!!.get()
    }

    private fun openPicture() = open().also { row(it, R.string.settings_browser_preferences_picture).performClick() }

    private fun retainSession(context: Context): CarPlayController = mock(CarPlayController::class.java).also {
        val display = CarPlaySessionDisplay(1920, 1440, Surface.ROTATION_0,
            hideTopBar = false, hideBottomBar = false, windowWidth = 1920, windowHeight = 1080)
        CarPlayBackgroundSession.store(it, mock(AndroidMediaSink::class.java), 1920, 1440, context, display) {
            throw AssertionError("Settings must not stop the active session")
        }
    }

    private fun latestDialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun row(screen: BrowserSettingsActivity, title: Int) = descendants(screen.window.decorView)
        .single { it.tag == title }
    private fun buttons(screen: BrowserSettingsActivity) = descendants(screen.window.decorView).filterIsInstance<Button>()
    private fun switches(screen: BrowserSettingsActivity) = descendants(screen.window.decorView).filterIsInstance<Switch>()
    private fun texts(screen: BrowserSettingsActivity) = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun clearPreferences() {
        listOf("diplay", "xcertplay_airplay", "browser_display", "browser_onboarding")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
