package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BrowserResolutionPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("xcertplay_airplay", 0)

    @After fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test fun upgradeIgnoresPreviouslyUnusedPhoneScaleAndPreservesBrowserBaseline() {
        AirPlayPersistence.saveDisplayScalePercent(context, 160)

        assertEquals(100, AirPlayPersistence.loadBrowserResolutionPercent(context))
        assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun browserQualityPersistsIndependentlyAndClampsBothBounds() {
        AirPlayPersistence.saveDisplayScalePercent(context, 75)
        for ((saved, expected) in listOf(30 to 30, 125 to 125, 160 to 160, 29 to 30, 161 to 160)) {
            AirPlayPersistence.saveBrowserResolutionPercent(context, saved)
            assertEquals(expected, AirPlayPersistence.loadBrowserResolutionPercent(context))
            assertEquals(75, AirPlayPersistence.loadDisplayScalePercent(context))
        }
    }

    @Test fun outOfRangeStoredValuesAreClampedWhenRead() {
        preferences.edit().putInt("browser_resolution_percent", -1).commit()
        assertEquals(30, AirPlayPersistence.loadBrowserResolutionPercent(context))
        preferences.edit().putInt("browser_resolution_percent", 999).commit()
        assertEquals(160, AirPlayPersistence.loadBrowserResolutionPercent(context))
    }
}
