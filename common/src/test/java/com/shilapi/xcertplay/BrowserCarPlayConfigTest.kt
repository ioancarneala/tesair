package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserCarPlayConfigTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(activity)
        AirPlayPersistence.saveManualHotspotSsid(activity, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "test-only-1234")
        ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayIdentity.generate())
        ReflectionHelpers.setField(activity, "microphoneAvailable", true)
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(activity)
    }

    @Test fun defaultKeepsTheWorkingCanvasAndRoutesAudioDirectlyFromIphone() {
        val config = config(100)
        assertEquals(1920, config.main.widthPixels)
        assertEquals(1440, config.main.heightPixels)
        assertTrue(config.disableAudioOutput)
        assertFalse(config.microphone)
        assertFalse(config.hevc)
    }

    @Test fun browserHostAdvertisesTeslaForFreshAndLegacyCarButtons() {
        val expected = activity.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        for (label in listOf(null, "BYD")) {
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().putString("oem_label", label).commit()
            val info = AirPlayInfoPlist.build(config(100))
            assertEquals("Tesla", info["oemIconLabel"])
            val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
            assertArrayEquals(expected, icon["imageData"] as ByteArray)
            assertEquals(192, icon["widthPixels"])
            assertEquals(192, icon["heightPixels"])
        }
    }

    @Test fun retainedBrowserHostReadsCustomCarButtonForItsNextHandshake() {
        val previous = config(100)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val custom = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        AirPlayPersistence.saveOemLabel(activity, "My car")
        AirPlayPersistence.saveCustomAirPlayIcon(activity, custom)

        val updated = config(100)
        val info = AirPlayInfoPlist.build(updated)
        assertEquals("My car", info["oemIconLabel"])
        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertArrayEquals(custom, icon["imageData"] as ByteArray)
        assertEquals(48, icon["widthPixels"])
        assertEquals(48, icon["heightPixels"])
        assertEquals(previous.main, updated.main)
        assertEquals(previous.deviceId, updated.deviceId)
        assertEquals(previous.disableAudioOutput, updated.disableAudioOutput)
    }

    @Test fun browserQualityChangesTheAdvertisedSourceAndRetainsNativeAreas() {
        val normal = config(100)
        val sharper = config(160)
        assertEquals(2880, sharper.main.widthPixels)
        assertEquals(2160, sharper.main.heightPixels)
        assertEquals(normal.main.widthPhysicalMm, sharper.main.widthPhysicalMm)
        assertEquals(normal.main.heightPhysicalMm, sharper.main.heightPhysicalMm)
        assertEquals(normal.main.viewAreas!!.size, sharper.main.viewAreas!!.size)
        assertTrue(sharper.main.viewAreas!!.all {
            it.width <= sharper.main.widthPixels && it.height <= sharper.main.heightPixels
        })
    }

    @Test fun legacyAudioPreferenceCannotEnableAndroidPlaybackOrMicrophoneForBrowserSessions() {
        val running = config(100)
        AirPlayPersistence.saveBrowserDirectAudio(activity, true)
        val direct = config(100)
        assertTrue(running.disableAudioOutput)
        assertFalse(running.microphone)
        assertTrue(direct.disableAudioOutput)
        assertFalse(direct.microphone)
        assertEquals(running.main, direct.main)

        AirPlayPersistence.saveBrowserDirectAudio(activity, false)
        val fallback = config(100)
        assertTrue(fallback.disableAudioOutput)
        assertFalse(fallback.microphone)
    }

    @Test fun reusedBrowserHostReadsSavedCarPlaySizeForTheNextHandshake() {
        // A host created before the setting changed can survive until the next handshake.
        ReflectionHelpers.setField(activity, "widthPhysicalMm", 300)
        AirPlayPersistence.saveWidthPhysicalMm(activity, 300)
        AirPlayPersistence.saveBrowserDirectAudio(activity, true)
        val running = config(160)

        AirPlayPersistence.saveWidthPhysicalMm(activity, 250)
        val larger = config(160)

        assertEquals(300, running.main.widthPhysicalMm)
        assertEquals(225, running.main.heightPhysicalMm)
        assertEquals(250, larger.main.widthPhysicalMm)
        assertEquals(188, larger.main.heightPhysicalMm)
        assertEquals(running.main.widthPixels, larger.main.widthPixels)
        assertEquals(running.main.heightPixels, larger.main.heightPixels)
        assertEquals(running.main.viewAreas, larger.main.viewAreas)
        assertEquals(running.main.initialViewArea, larger.main.initialViewArea)
        assertTrue(larger.disableAudioOutput)
        assertFalse(larger.microphone)
    }

    @Test fun directAudioDoesNotRequireAndroidMicrophonePermission() {
        ReflectionHelpers.setField(activity, "microphoneAvailable", false)
        ReflectionHelpers.setField(activity, "microphonePermissionResolved", false)
        AirPlayPersistence.saveBrowserDirectAudio(activity, false)
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "resolveMicrophonePermission"))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "awaitingMicrophonePermission"))
        assertFalse(config(100).microphone)
    }

    @Test fun browserIgnoresSavedPhoneGpsAndVehicleOutputSettingsWithoutErasingThem() {
        AirPlayPersistence.saveLocationReportingEnabled(activity, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadConnectionSettings")
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "locationReportingEnabled"))
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(activity))

        // Even a retained host with an older in-memory value cannot advertise Android GPS.
        ReflectionHelpers.setField(activity, "locationReportingEnabled", true)
        val runtime = ReflectionHelpers.callInstanceMethod<CarPlayRuntimeConfig>(activity, "createRuntimeConfig")
        assertFalse(runtime.locationReportingEnabled)
        assertFalse(runtime.identification.locationInformationEnabled)
        assertFalse(runtime.identification.vehicleStatusEnabled)
        assertFalse(runtime.identification.vehicleSpeedEnabled)
    }

    @Test fun browserDoesNotRequestAnUnusedClusterStreamAfterAnOldSettingWasEnabled() {
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        assertNull(ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig?>(activity, "clusterDisplayConfig"))
        assertNull(config(100).cluster)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(activity))
    }

    @Test fun nativeAppRetainsItsSavedLocationReportingBehavior() {
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay"
        })
        AirPlayPersistence.saveLocationReportingEnabled(activity, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadConnectionSettings")
        val runtime = ReflectionHelpers.callInstanceMethod<CarPlayRuntimeConfig>(activity, "createRuntimeConfig")
        assertTrue(runtime.locationReportingEnabled)
        assertTrue(runtime.identification.locationInformationEnabled)
    }

    @Test fun browserUsesWirelessEvenWhenAnEarlierVersionSavedUsbMode() {
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "loadConnectionSettings")
        val runtime = ReflectionHelpers.callInstanceMethod<CarPlayRuntimeConfig>(activity, "createRuntimeConfig")
        assertEquals(CarPlayTransport.WIRELESS, runtime.transport)
        assertFalse("Legacy preferences remain intact for the native application", AirPlayPersistence.loadWirelessEnabled(activity))
    }

    private fun config(percent: Int): AirPlayConfig {
        ReflectionHelpers.setField(activity, "displayScalePercent", percent)
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 1080)
        return activity.javaClass.getDeclaredMethod("createBrowserAirPlayConfig", type)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
    }
}
