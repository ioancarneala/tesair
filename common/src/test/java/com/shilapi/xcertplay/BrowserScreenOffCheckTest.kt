package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import android.os.SystemClock
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class BrowserScreenOffCheckTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var context: Context
    private lateinit var power: PowerManager
    private lateinit var activities: ActivityManager
    private lateinit var output: BrowserStreamOutput

    @Before fun setUp() {
        app.getSharedPreferences("browser_screen_off_check", 0).edit().clear().commit()
        BrowserScreenOffCheck.stop()
        ShadowBuild.setManufacturer("Xiaomi")
        ShadowBuild.setBrand("Xiaomi")
        ShadowBuild.setFingerprint("synthetic-firmware-1")
        power = mock(PowerManager::class.java)
        activities = mock(ActivityManager::class.java)
        `when`(power.isIgnoringBatteryOptimizations(app.packageName)).thenReturn(true)
        `when`(power.isInteractive).thenReturn(false)
        `when`(activities.isBackgroundRestricted).thenReturn(false)
        context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = when (name) {
                POWER_SERVICE -> power
                ACTIVITY_SERVICE -> activities
                else -> super.getSystemService(name)
            }
        }
        output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        `when`(output.clientCount).thenReturn(1)
        `when`(output.hasRenderedVideo).thenReturn(true)
        `when`(output.lastRenderedAtElapsedMillis).thenAnswer { SystemClock.elapsedRealtime() }
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        CarPlayBackgroundSession.active = true
    }

    @After fun tearDown() {
        BrowserScreenOffCheck.stop()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        CarPlayBackgroundSession.clear()
    }

    @Test fun passiveObservationWorksOnEveryManufacturer() {
        for (manufacturer in listOf("Xiaomi", "Redmi", "POCO", "Samsung", "OnePlus", "Google", "Huawei", "vivo", "unknown")) {
            app.getSharedPreferences("browser_screen_off_check", 0).edit().clear().commit()
            BrowserScreenOffCheck.stop()
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setBrand(manufacturer)
            assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
            observeThirtySeconds()
            assertTrue(manufacturer, BrowserScreenOffCheck.snapshot(context).passed)
        }
    }

    @Test fun completedProofSurvivesMonitorRestartAndDoesNotNeedPhoneScreenToStayOff() {
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        observeThirtySeconds()
        assertTrue(BrowserScreenOffCheck.snapshot(context).passed)
        BrowserScreenOffCheck.stop()
        `when`(power.isInteractive).thenReturn(true)
        assertTrue(BrowserScreenOffCheck.snapshot(context).passed)
        assertEquals(0, BrowserScreenOffCheck.snapshot(context).remainingSeconds)
    }

    @Test fun batteryGrantRevocationInvalidatesSavedProofAndCannotBeUndoneByOpeningSettings() {
        observeThirtySeconds()
        `when`(power.isIgnoringBatteryOptimizations(app.packageName)).thenReturn(false)
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        `when`(power.isIgnoringBatteryOptimizations(app.packageName)).thenReturn(true)
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
    }

    @Test fun backgroundRestrictionAlsoInvalidatesSavedProof() {
        observeThirtySeconds()
        `when`(activities.isBackgroundRestricted).thenReturn(true)
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        `when`(activities.isBackgroundRestricted).thenReturn(false)
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
    }

    @Test fun updatedFirmwareRequiresANewObservation() {
        observeThirtySeconds()
        ShadowBuild.setFingerprint("synthetic-firmware-2")
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        assertEquals(30, BrowserScreenOffCheck.snapshot(context).remainingSeconds)
    }

    @Test fun updatedAppRequiresANewObservation() {
        observeThirtySeconds()
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.setLongVersionCode(info.longVersionCode + 1)
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
    }

    @Test fun screenOnOrAHistoricalRenderedBooleanCannotCreateProof() {
        `when`(power.isInteractive).thenReturn(true)
        observeThirtySeconds()
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        `when`(power.isInteractive).thenReturn(false)
        `when`(output.lastRenderedAtElapsedMillis).thenReturn(0L)
        observeThirtySeconds()
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
    }

    @Test fun ackFromPreviousMediaConfigurationCannotCreateProof() {
        `when`(output.hasRenderedVideo).thenReturn(false)
        observeThirtySeconds()
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
    }

    @Test fun missingGrantsCannotAccumulateProgress() {
        `when`(power.isIgnoringBatteryOptimizations(app.packageName)).thenReturn(false)
        observeThirtySeconds()
        assertFalse(BrowserScreenOffCheck.snapshot(context).passed)
        assertEquals(30, BrowserScreenOffCheck.snapshot(context).remainingSeconds)
        `when`(power.isIgnoringBatteryOptimizations(app.packageName)).thenReturn(true)
        BrowserScreenOffCheck.poll(context)
        assertEquals(30, BrowserScreenOffCheck.snapshot(context).remainingSeconds)
    }

    private fun observeThirtySeconds() {
        for (sample in 0..15) {
            if (sample > 0) ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            BrowserScreenOffCheck.poll(context)
        }
    }
}
