package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserCarPlayConfigurationTest {
    private lateinit var context: Context
    private val identity = AirPlayIdentity.generate()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-only-1234")
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotSecurity.WPA2)
    }

    @Test fun headlessNegotiationKeepsTeslaQualitySizeAndDirectAudio() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 150)
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveBrowserResolutionPercent(context, 160)
        AirPlayPersistence.saveHevcEnabled(context, true)
        val layout = BrowserCarPlayLayout.create(1182, 920, AirPlayPersistence.loadBrowserResolutionPercent(context))
        val video = BrowserCarPlayConfiguration.video(context, identity, layout)

        assertEquals(2880, video.main.widthPixels)
        assertEquals(2160, video.main.heightPixels)
        assertEquals(150, video.main.widthPhysicalMm)
        assertEquals(113, video.main.heightPhysicalMm)
        assertEquals(60, video.main.fps)
        assertFalse(video.hevc)
        assertTrue(video.disableAudioOutput)
        assertFalse(video.microphone)
        assertNull(video.cluster)
        assertEquals(layout.areas, video.main.viewAreas)
        assertEquals(1182.0 / 920, layout.currentArea.width.toDouble() / layout.currentArea.height, 0.002)
    }

    @Test fun headlessReceiverReusesWirelessIdentityAndExcludesUnusedPhoneSensors() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        AirPlayPersistence.saveLocationReportingEnabled(context, true)
        DiPlayPreferences.savePhone(context, "02:00:00:00:00:01", "Test phone")
        val config = BrowserCarPlayConfiguration.runtime(context, identity)
        assertEquals(CarPlayTransport.WIRELESS, config.transport)
        assertEquals("02:00:00:00:00:01", config.wirelessBluetoothDeviceAddress)
        assertEquals(WirelessHotspotMode.MANUAL, config.wirelessHotspotMode)
        assertEquals("Test hotspot", config.manualHotspotSsid)
        assertEquals("DIPLAY-" + DiPlayBootstrap.deviceId(identity).replace(":", ""), config.identification.serialNumber)
        assertFalse(config.locationReportingEnabled)
        assertFalse(config.identification.locationInformationEnabled)
        assertFalse(config.identification.vehicleStatusEnabled)
        assertFalse(config.identification.vehicleSpeedEnabled)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun savedViewportDoesNotDependOnTheAndroidDisplayOrBrowserAlreadyBeingOpen() {
        BrowserCarPlayViewport.save(context, 1182, 920)
        val size = BrowserCarPlayViewport.load(context)
        val video = BrowserCarPlayConfiguration.video(context, identity,
            BrowserCarPlayLayout.create(size.first, size.second))
        assertEquals(1920, video.main.widthPixels)
        assertEquals(1440, video.main.heightPixels)
        val area = video.main.viewAreas!![video.main.initialViewArea]
        assertEquals(1182.0 / 920, area.width.toDouble() / area.height, 0.002)
    }

    @Test fun explicitFreshEvTestDeclaresVehicleDataWithoutEnablingOtherSensors() {
        var reading: VehicleStatusSnapshot? = VehicleStatusSnapshot(240, false, 60.0, 36_000, 60_000, 400)
        val provider = VehicleStatusProvider { reading }
        val config = BrowserCarPlayConfiguration.runtime(context, identity, provider)
        assertTrue(config.identification.vehicleStatusEnabled)
        assertEquals("DiPlay EV test", config.identification.manufacturer)
        assertFalse(config.identification.locationInformationEnabled)
        assertFalse(config.identification.vehicleSpeedEnabled)
        assertFalse(config.locationReportingEnabled)
        reading = null
        assertFalse(BrowserCarPlayConfiguration.runtime(context, identity, provider).identification.vehicleStatusEnabled)
    }
}
