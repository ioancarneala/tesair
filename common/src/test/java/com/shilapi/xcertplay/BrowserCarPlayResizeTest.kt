package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyInt
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserCarPlayResizeTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var construction: MockedConstruction<CarPlayController>
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
    private val layout = BrowserCarPlayLayout.create(1920, 1080)
    private val negotiated = AirPlayConfig(
        deviceName = "Browser resize test", deviceId = "02:00:00:00:00:01",
        btMac = "02:00:00:00:00:01", sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(layout.canvasWidth, layout.canvasHeight, 300, 225, fps = 30,
            viewAreas = layout.areas, initialViewArea = layout.initialIndex),
    )
    private val display = CarPlaySessionDisplay(layout.canvasWidth, layout.canvasHeight, Surface.ROTATION_0,
        hideTopBar = false, hideBottomBar = false, windowWidth = 1920, windowHeight = 1080,
        browserLayout = layout)

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        // Do not launch the Activity: these tests exercise a retained session without transports,
        // browser sockets, runtime permission prompts or authentication assets.
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        assertTrue(browserCarPlayEnabled(activity))
        controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(controller, "airPlayConfig", negotiated)
        construction = mockConstruction(CarPlayController::class.java)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("controller", controller)
        val sink = mock(AndroidMediaSink::class.java)
        setField("sink", sink)
        setField("sessionDisplay", display)
        setField("activeDisplaySize", size(1920, 1080))
        setField("hideTopBar", false)
        setField("hideBottomBar", false)
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, activity, display) {}
        assertTrue("Resize must exercise an owned live session", CarPlayBackgroundSession.isOwner(activity))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        construction.close()
    }

    @Test fun changedAspectSwitchesNativeLayoutWithoutReplacingTheSession() {
        applySize(1600, 800)

        assertEquals(size(1600, 800), getField("activeDisplaySize"))
        assertEquals(layout.indexFor(1600, 800), layout.currentIndex)
        verify(controller).showViewArea(layout.currentIndex)
        assertLiveSessionUnchanged()
    }

    @Test fun browserResizeDebouncesToOnlyTheLatestDimensions() {
        scheduleSize(1920, 1008)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(0, getField("restartGeneration"))
        scheduleSize(1600, 800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, getField("restartGeneration"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertEquals(size(1600, 800), getField("activeDisplaySize"))
        assertNull(getField("pendingDisplaySize"))
        verify(controller).showViewArea(layout.indexFor(1600, 800))
        assertLiveSessionUnchanged()
    }

    @Test fun unchangedEncodedDimensionsDoNotReconnect() {
        applySize(1920, 1080)
        assertLiveSessionUnchanged()
        verifyNoInteractions(controller)
    }

    @Test fun equivalentOddBrowserDimensionsDoNotCauseAReconnectLoop() {
        applySize(1921, 1081)
        assertEquals(size(1921, 1081), getField("activeDisplaySize"))
        assertLiveSessionUnchanged()
        verifyNoInteractions(controller)
    }

    @Test fun viewportReceivedBeforeStartupSetsDimensionsWithoutRestarting() {
        CarPlayBackgroundSession.clear()
        setField("controller", null)
        setField("sessionDisplay", null)
        setField("activeDisplaySize", null)

        applySize(1601, 901)

        assertEquals(size(1601, 901), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
        assertTrue(construction.constructed().isEmpty())
    }

    @Test fun unavailableViewAreaCommandKeepsLastAcceptedAreaAndDoesNotReconnect() {
        `when`(controller.showViewArea(anyInt())).thenReturn(false)
        applySize(1600, 800)
        assertEquals(layout.initialIndex, layout.currentIndex)
        assertLiveSessionUnchanged()
    }

    @Test fun firstFrameCanResendAreaSelectedBeforeTheEventChannelWasReady() {
        activity.javaClass.getDeclaredMethod("applyBrowserViewArea", sizeClass, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, size(1920, 1080), true)
        verify(controller).showViewArea(layout.initialIndex)
        assertLiveSessionUnchanged()
    }

    @Test fun explicitReconnectStillRebuildsTheOwnedSession() {
        activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Explicit browser reconnect")

        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("controller"))
        assertNull(getField("sessionDisplay"))
    }

    private fun assertLiveSessionUnchanged() {
        assertSame(controller, getField("controller"))
        assertTrue(CarPlayBackgroundSession.isOwner(activity))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(display, getField("sessionDisplay"))
        assertSame(negotiated, ReflectionHelpers.getField<AirPlayConfig>(controller, "airPlayConfig"))
        assertEquals(1920, (getField("sessionDisplay") as CarPlaySessionDisplay).width)
        assertEquals(1440, (getField("sessionDisplay") as CarPlaySessionDisplay).height)
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
        assertTrue("Resize must not create a replacement CarPlay controller", construction.constructed().isEmpty())
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun scheduleSize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun getField(name: String): Any? = ReflectionHelpers.getField(activity, name)
    private fun setField(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
}
