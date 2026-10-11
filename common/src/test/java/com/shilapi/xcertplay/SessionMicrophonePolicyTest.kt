package com.shilapi.xcertplay

import android.content.ContextWrapper
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SessionMicrophonePolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val browserContext get() = object : ContextWrapper(context) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
    }
    private val normalContext get() = object : ContextWrapper(context) {
        override fun getPackageName() = "com.shihab.diplay"
    }

    @Before @After fun clearState() {
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun coldBrowserSessionDoesNotRequireMicrophoneWithDefaultOrLegacyAudioPreference() {
        assertFalse(sessionMicrophoneRequired(browserContext))
        AirPlayPersistence.saveBrowserDirectAudio(context, false)
        assertFalse(sessionMicrophoneRequired(browserContext))
    }

    @Test fun coldBrowserSessionDoesNotRequireMicrophoneForDirectAudio() {
        AirPlayPersistence.saveBrowserDirectAudio(context, true)
        assertFalse(sessionMicrophoneRequired(browserContext))
    }

    @Test fun runningMicrophoneSessionIgnoresPendingDirectAudioChange() {
        val controller = retainSession(microphoneRequired = true)
        AirPlayPersistence.saveBrowserDirectAudio(context, true)

        assertTrue(sessionMicrophoneRequired(browserContext))
        verifyNoInteractions(controller)
    }

    @Test fun runningDirectAudioSessionIgnoresPendingAndroidAudioChange() {
        val controller = retainSession(microphoneRequired = false)
        AirPlayPersistence.saveBrowserDirectAudio(context, false)

        assertFalse(sessionMicrophoneRequired(browserContext))
        verifyNoInteractions(controller)
    }

    @Test fun sessionWithoutCapturedRequirementUsesDirectAudioRegardlessOfLegacyPreference() {
        retainSession(microphoneRequired = null)
        AirPlayPersistence.saveBrowserDirectAudio(context, true)
        assertFalse(sessionMicrophoneRequired(browserContext))

        AirPlayPersistence.saveBrowserDirectAudio(context, false)
        assertFalse(sessionMicrophoneRequired(browserContext))
    }

    @Test fun normalAppPreservesMicrophonePolicyRegardlessOfBrowserPreferenceOrSnapshot() {
        AirPlayPersistence.saveBrowserDirectAudio(context, true)
        assertTrue(sessionMicrophoneRequired(normalContext))

        retainSession(microphoneRequired = false)
        assertTrue(sessionMicrophoneRequired(normalContext))
    }

    private fun retainSession(microphoneRequired: Boolean?): CarPlayController =
        mock(CarPlayController::class.java).also { controller ->
            val display = CarPlaySessionDisplay(1920, 1080, 0,
                hideTopBar = false, hideBottomBar = false, windowWidth = 1920, windowHeight = 1080,
                microphoneRequired = microphoneRequired)
            CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java),
                1920, 1080, this, display) {
                throw AssertionError("Checking the microphone policy must not stop the session")
            }
        }
}
