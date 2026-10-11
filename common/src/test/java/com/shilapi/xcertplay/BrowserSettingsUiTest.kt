package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.ContextWrapper
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun clearSession() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("browser_display", 0).edit().clear().commit()
    }

    @Test fun browserDisplayKeepsApplicableControlsAndExplainsItsVideoProfile() {
        val screen = openSettings(SettingsCategory.DISPLAY)
        val visible = texts(screen)

        assertTrue(visible.contains(screen.getString(R.string.settings_browser_video_profile)))
        assertTrue(visible.any { it.startsWith(screen.getString(R.string.carplay_size) + " · ") })
        assertTrue(visible.any { it.startsWith(screen.getString(R.string.frame_rate) + " · ") })
        assertTrue(visible.any { it.startsWith(screen.getString(R.string.resolution) + " · ") })
        assertTrue(visible.contains(screen.getString(R.string.settings_browser_resolution_hint)))
        listOf(R.string.picture_adjustments, R.string.carplay_dock,
            R.string.hide_top_bar, R.string.hide_bottom_bar, R.string.adapt_pip_resolution).forEach { id ->
            assertFalse(screen.getString(id), visible.any { it == screen.getString(id) || it.startsWith(screen.getString(id) + " · ") })
        }
    }

    @Test fun browserAdvancedDestinationFallsBackToSupportedSettingsWithoutAudioControls() {
        val screen = openSettings(SettingsCategory.ADVANCED)
        val visible = texts(screen)

        listOf(R.string.main_buffered_audio, R.string.call_echo_cancellation,
            R.string.call_voice_filter, R.string.contrib_audio_home_toggle_audio_focus,
            R.string.settings_browser_audio_destination, R.string.efficient_video,
            R.string.smooth_video, R.string.side_panel, R.string.split_screen_areas,
            R.string.carplay_rotation, R.string.carplay_map_on_instrument_cluster_experimental).forEach { id ->
            assertFalse(screen.getString(id), visible.any { it == screen.getString(id) || it.startsWith(screen.getString(id) + " · ") })
        }
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
    }

    @Test fun browserOverviewDoesNotOfferPhoneSystemBarFullscreen() {
        val screen = openSettings()
        val switches = descendants(screen.window.decorView).filterIsInstance<Switch>().toList()

        assertFalse(switches.any { it.contentDescription == screen.getString(R.string.full_screen) })
        assertTrue(switches.any { it.contentDescription == screen.getString(R.string.connect_when_diplay_opens) })
    }

    @Test fun browserSearchOmitsInactiveVideoControlsAndKeepsUsefulSettings() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex")
        val titles = index.map { it.title }

        listOf(R.string.efficient_video, R.string.smooth_video,
            R.string.picture_adjustments, R.string.carplay_dock, R.string.settings_gesture_fingers_label,
            R.string.music_buffer, R.string.settings_browser_audio_destination,
            R.string.call_echo_cancellation, R.string.main_buffered_audio).forEach { id ->
            assertFalse(screen.getString(id), screen.getString(id) in titles)
        }
        listOf(R.string.frame_rate, R.string.carplay_size, R.string.right_hand_drive,
            R.string.resolution, R.string.connect_when_diplay_opens).forEach { id ->
            assertTrue(screen.getString(id), screen.getString(id) in titles)
        }
    }

    @Test fun browserResolutionSavesForNextConnectionAndShowsCappedPixels() {
        AirPlayPersistence.saveDisplayScalePercent(context, 75)
        BrowserCarPlayViewport.save(context, 2560, 1440)
        val screen = openSettings(SettingsCategory.DISPLAY)
        val controller = retainSession(screen)
        settingButton(screen, R.string.resolution).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()

        input.setText("160")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(160, AirPlayPersistence.loadBrowserResolutionPercent(context))
        assertEquals(75, AirPlayPersistence.loadDisplayScalePercent(context))
        assertTrue(texts(screen).contains(screen.getString(R.string.settings_browser_resolution_preview, 3840, 2160)))
        assertTrue(PendingReconnect.isPending(controller))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verifyNoInteractions(controller)
    }

    @Test fun browserResolutionRejectsOutOfRangeWithoutSavingOrMarkingReconnect() {
        val screen = openSettings(SettingsCategory.DISPLAY)
        val controller = retainSession(screen)
        settingButton(screen, R.string.resolution).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()

        for (invalid in listOf("29", "161")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing)
            assertNotNull(input.error)
            assertEquals(100, AirPlayPersistence.loadBrowserResolutionPercent(context))
            assertFalse(PendingReconnect.isPending(controller))
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        verifyNoInteractions(controller)
    }

    @Test fun browserSizeShowsSavedCustomValueAndCancellingDoesNotChangeTheSession() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 150)
        val screen = openSettings(SettingsCategory.DISPLAY)
        val controller = retainSession(screen)
        val button = settingButton(screen, R.string.carplay_size)
        assertTrue(button.text.toString().endsWith(screen.getString(R.string.settings_browser_carplay_size_value, 150)))

        for (cancelWithButton in listOf(true, false)) {
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val slider = descendants(dialog.window!!.decorView).filterIsInstance<SeekBar>().single()
            assertEquals(6, slider.max)
            assertEquals(1, slider.progress)
            assertEquals(screen.getString(R.string.apply_and_reconnect),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())

            slider.progress = 4
            assertEquals(150, AirPlayPersistence.loadWidthPhysicalMm(context))
            if (cancelWithButton) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            else dialog.cancel()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(150, AirPlayPersistence.loadWidthPhysicalMm(context))
            assertFalse(PendingReconnect.isPending(controller))
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            assertNull(shadowOf(screen).nextStartedActivity)
        }
        verifyNoInteractions(controller)
    }

    @Test fun browserSizeSavesTheChosenStepAndShowsItWhenReopened() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 150)
        val screen = openSettings(SettingsCategory.DISPLAY)
        val button = settingButton(screen, R.string.carplay_size)
        var previous = 150

        for (value in listOf(100, 200, 400)) {
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val slider = descendants(dialog.window!!.decorView).filterIsInstance<SeekBar>().single()
            assertEquals((previous - 100) / 50, slider.progress)
            assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            slider.progress = (value - 100) / 50
            assertEquals(previous, AirPlayPersistence.loadWidthPhysicalMm(context))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(dialog.isShowing)
            assertEquals(value, AirPlayPersistence.loadWidthPhysicalMm(context))
            assertTrue(button.text.toString().endsWith(screen.getString(R.string.settings_browser_carplay_size_value, value)))
            assertNull(shadowOf(screen).nextStartedActivity)
            previous = value
        }
    }

    @Test fun browserSizeReconnectsOnceOnlyAfterApplyingAChangedValue() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 150)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        AirPlayPersistence.saveBrowserResolutionPercent(context, 160)
        AirPlayPersistence.saveBrowserDirectAudio(context, true)
        val screen = openSettings(SettingsCategory.DISPLAY)
        // Model the already provisioned session without requiring private authentication assets.
        ReflectionHelpers.setField(screen, "setupError", null)
        retainSession(screen)
        val stoppedWithSizes = mutableListOf<Int>()
        val stop: ((() -> Unit) -> Unit) = { completion ->
            stoppedWithSizes += AirPlayPersistence.loadWidthPhysicalMm(context)
            CarPlayBackgroundSession.clear()
            completion()
        }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        val button = settingButton(screen, R.string.carplay_size)

        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val unchanged = ShadowAlertDialog.getLatestAlertDialog()
        unchanged.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(stoppedWithSizes.isEmpty())
        assertNull(shadowOf(screen).nextStartedActivity)

        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val changed = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(screen.getString(R.string.apply_and_reconnect),
            changed.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        descendants(changed.window!!.decorView).filterIsInstance<SeekBar>().single().progress = 0
        assertTrue(stoppedWithSizes.isEmpty())
        changed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(100, AirPlayPersistence.loadWidthPhysicalMm(context))
        assertTrue(stoppedWithSizes.isEmpty())
        val reconnect = shadowOf(screen).nextStartedActivity
        assertEquals(BrowserDashboardActivity::class.java.name, reconnect?.component?.className)
        assertTrue(reconnect?.getBooleanExtra(BrowserDashboardActivity.EXTRA_RECONNECT, false) == true)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertEquals(160, AirPlayPersistence.loadBrowserResolutionPercent(context))
        assertTrue(AirPlayPersistence.loadBrowserDirectAudio(context))
    }

    @Test fun nonBrowserSizeKeepsTheThreePresets() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 150)
        val screen = openSettings(SettingsCategory.DISPLAY, browser = false)
        settingButton(screen, R.string.carplay_size).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        assertEquals(3, dialog.listView.adapter.count)
        assertTrue(descendants(dialog.window!!.decorView).none { it is SeekBar })
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(150, AirPlayPersistence.loadWidthPhysicalMm(context))
    }

    @Test fun browserSettingsOnlyExposeCategoriesThatAffectTheBrowserExperience() {
        val screen = openSettings()
        val categories = ReflectionHelpers.callInstanceMethod<List<SettingsCategory>>(screen, "availableSettingsCategories")
        assertEquals(listOf(SettingsCategory.OVERVIEW, SettingsCategory.CONNECTION,
            SettingsCategory.DISPLAY, SettingsCategory.DIAGNOSTICS), categories)
        val controller = retainSession(screen)
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verifyNoInteractions(controller)
    }

    @Test fun nonBrowserAdvancedHasNoBrowserAudioDestination() {
        val screen = openSettings(SettingsCategory.ADVANCED, browser = false)
        val title = screen.getString(R.string.settings_browser_audio_destination)
        assertFalse(texts(screen).any { it == title || it.startsWith(title + " · ") })
    }

    private fun retainSession(screen: DiPlayActivity): CarPlayController = mock(CarPlayController::class.java).also {
        val display = CarPlaySessionDisplay(1920, 1440, Surface.ROTATION_0,
            hideTopBar = false, hideBottomBar = false, windowWidth = 1920, windowHeight = 1080)
        CarPlayBackgroundSession.store(it, mock(AndroidMediaSink::class.java), 1920, 1440, screen, display) {
            throw AssertionError("Saving settings must not stop the active session")
        }
    }

    private fun settingButton(screen: DiPlayActivity, title: Int): Button = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString().startsWith(screen.getString(title) + " · ") }

    private fun openSettings(category: SettingsCategory = SettingsCategory.OVERVIEW, browser: Boolean = true): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
            .setup().get().also { screen ->
                activity = screen
                ReflectionHelpers.setField(screen, "mBase", object : ContextWrapper(screen.baseContext) {
                    override fun getPackageName() = if (browser) "com.shihab.diplay.browsertest" else "com.shihab.diplay"
                })
                assertEquals("The test must use the requested app identity", browser, browserCarPlayEnabled(screen))
                ReflectionHelpers.setField(screen, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
