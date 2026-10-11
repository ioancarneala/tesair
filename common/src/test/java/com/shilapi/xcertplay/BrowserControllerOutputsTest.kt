package com.shilapi.xcertplay

import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserControllerOutputsTest {
    @Test fun browserControllerDoesNotClaimClusterControlOrPublishAndroidWidgetState() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        }
        val clusterControl = Class.forName("com.shilapi.xcertplay.hud.BydClusterMapPause")
            .getDeclaredField("streamControl").apply { isAccessible = true }
        val previousControl = clusterControl.get(null)
        val sentinel: (Boolean) -> Unit = {}
        clusterControl.set(null, sentinel)
        CarPlayGlance.setConnected(false)
        var controller: CarPlayController? = null
        try {
            controller = CarPlayController(
                context,
                CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                    identification = Iap2IdentificationConfig("DiPlay", "Browser", "DiPlay", "test",
                        "1", "1", 3)),
                AirPlayConfig("DiPlay", "02:00:00:00:00:01", "02:00:00:00:00:01", "950.7.1",
                    AirPlayDisplayConfig(1920, 1440), disableAudioOutput = true),
                AirPlayIdentity.generate(), PairingStore(), mock(AirPlaySessionListener::class.java),
                mock(AirPlayMediaHandler::class.java), {},
            )
            assertSame(sentinel, clusterControl.get(null))
            val listener = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
            val session = mock(AirPlaySession::class.java)
            listener.onSessionActive(session)
            assertFalse(CarPlayGlance.snapshot().connected)
            assertSame(sentinel, clusterControl.get(null))
            listener.onSessionEnded(session)
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            assertSame(sentinel, clusterControl.get(null))
        } finally {
            controller?.close()
            controller?.awaitClosed(2_000)
            clusterControl.set(null, previousControl)
            CarPlayGlance.setConnected(false)
        }
    }
}
