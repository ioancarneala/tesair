package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BrowserAudioPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun existingInstallKeepsOnePlusAudioUntilExplicitOptInAndCanRestoreIt() {
        AirPlayPersistence.saveMainBufferedAudio(context, true)
        assertFalse(AirPlayPersistence.loadBrowserDirectAudio(context))

        AirPlayPersistence.saveBrowserDirectAudio(context, true)
        assertTrue(AirPlayPersistence.loadBrowserDirectAudio(context))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(context))

        AirPlayPersistence.saveBrowserDirectAudio(context, false)
        assertFalse(AirPlayPersistence.loadBrowserDirectAudio(context))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(context))
    }
}
