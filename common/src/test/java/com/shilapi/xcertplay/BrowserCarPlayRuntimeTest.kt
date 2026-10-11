package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentCallbacks
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserCarPlayRuntimeTest {
    private lateinit var context: Context
    private lateinit var runtime: BrowserCarPlayRuntime
    private lateinit var output: BrowserStreamOutput
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var worker: PausedExecutorService
    private lateinit var commands: PausedExecutorService
    private val layout = BrowserCarPlayLayout.create(1182, 920, 160)

    @Before fun setUp() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
            override fun getApplicationContext(): Context = this
            override fun registerComponentCallbacks(callback: ComponentCallbacks) = baseContext.registerComponentCallbacks(callback)
            override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = baseContext.unregisterComponentCallbacks(callback)
        }
        CarPlayBackgroundSession.clear()
        output = mock(BrowserStreamOutput::class.java)
        runtime = BrowserCarPlayRuntime(context, output)
        get<ExecutorService>("worker").shutdownNow()
        get<ExecutorService>("commands").shutdownNow()
        worker = PausedExecutorService()
        commands = PausedExecutorService()
        set("worker", worker)
        set("commands", commands)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        `when`(controller.awaitClosed(4_000)).thenReturn(true)
        set("requested", true)
        set("controller", controller)
        set("sink", sink)
        set("layout", layout)
        CarPlayBackgroundSession.store(controller, sink, 1182, 920, runtime,
            CarPlaySessionDisplay(layout.canvasWidth, layout.canvasHeight, Surface.ROTATION_0,
                true, true, 1182, 920, browserLayout = layout, microphoneRequired = false)) {}
    }

    @After fun tearDown() {
        runtime.close()
        drainWorker()
        get<Handler>("main").removeCallbacksAndMessages(null)
        commands.shutdownNow()
        worker.shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun browserResizeKeepsTheControllerAndUsesTheNativeViewArea() {
        val viewport: (Int, Int) -> Unit = get("viewportListener")
        viewport(1600, 800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(layout.indexFor(1600, 800), layout.currentIndex)
        verify(controller).showViewArea(layout.currentIndex)
        verify(controller, never()).close()
        assertEquals(1600 to 800, BrowserCarPlayViewport.load(context))
    }

    @Test fun serviceStartsFromTheSavedViewportWithNoActivityOrBrowserClient() {
        CarPlayBackgroundSession.clear()
        set("controller", null)
        set("sink", null)
        set("layout", null)
        set("requested", false)
        BrowserCarPlayViewport.save(context, 1182, 920)
        set("viewport", BrowserCarPlayViewport.load(context))
        // This mode needs no runtime permission on API 29. No authentication assets or
        // transport sockets are used: only the controller constructor is replaced.
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.start()
            assertTrue(construction.constructed().isEmpty())
            worker.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, construction.constructed().size)
            val started = construction.constructed().single()
            verify(started).start()
            assertTrue(CarPlayBackgroundSession.isOwner(runtime))
            assertEquals(1182, CarPlayBackgroundSession.snapshot()?.width)
            assertEquals(920, CarPlayBackgroundSession.snapshot()?.height)
            assertNull(output.lastViewport)
            runtime.close()
            drainWorker()
        }
    }

    @Test fun closeDuringPreparationCannotPublishALateController() {
        CarPlayBackgroundSession.clear()
        set("controller", null)
        set("sink", null)
        set("layout", null)
        set("requested", false)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.start()
            runtime.close()
            drainWorker()
            assertTrue(construction.constructed().isEmpty())
            assertFalse(CarPlayBackgroundSession.hasSession())
        }
    }

    @Test fun adbEvSampleReachesTheRuntimeControllerAndChangesWithoutReconnect() {
        val flags = context.applicationInfo.flags
        val sample = File(context.cacheDir, "ev-battery-test.json")
        fun writeSample(percent: Int) = sample.writeText("""{"expiresAtMs":${System.currentTimeMillis() + 90_000},
            "batteryPercent":$percent,"rangeKm":${percent * 4},"maxRangeKm":400,"charging":false}""")
        try {
            context.applicationInfo.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            writeSample(60)
            CarPlayBackgroundSession.clear()
            set("controller", null)
            set("sink", null)
            set("layout", null)
            set("requested", false)
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
            AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
            AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
            var args: List<*> = emptyList<Any>()
            mockConstruction(CarPlayController::class.java) { _, construction -> args = construction.arguments() }.use {
                runtime.start()
                worker.runAll()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, it.constructed().size)
                val config = args[1] as CarPlayRuntimeConfig
                val provider = args[12] as VehicleStatusProvider
                assertTrue(config.identification.vehicleStatusEnabled)
                assertEquals("DiPlay EV test", config.identification.manufacturer)
                assertEquals(60.0, provider.snapshot()!!.batteryPercent, 0.0)
                writeSample(12)
                assertEquals(12.0, provider.snapshot()!!.batteryPercent, 0.0)
                assertTrue(provider.snapshot()!!.rangeWarning)
                assertTrue(sample.delete())
                assertNull(provider.snapshot())
                runtime.close()
                drainWorker()
            }
        } finally {
            context.applicationInfo.flags = flags
            sample.delete()
        }
    }

    @Test fun failedViewAreaCommandKeepsTheLastAcceptedLayout() {
        `when`(controller.showViewArea(anyInt())).thenReturn(false)
        val viewport: (Int, Int) -> Unit = get("viewportListener")
        viewport(1600, 800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, layout.currentIndex)
        verify(controller, never()).close()
    }

    @Test fun duplicateTransportFailuresScheduleOnlyOneBackoff() {
        val listener = listener()
        listener.onTransportError("test transport error")
        val first: Runnable = get("retry")
        listener.onTransportError("another test transport error")
        assertSame(first, get<Runnable>("retry"))
        assertEquals(1, get<WirelessStartupRetryBudget>("retryBudget").retries)
    }

    @Test fun aRetiredControllersCallbacksCannotScheduleRecovery() {
        val stale = listener()
        set("generation", 1)
        stale.onTransportError("late test error")
        stale.onSessionActive(mock(AirPlaySession::class.java))
        assertNull(get<Runnable?>("retry"))
        assertFalse(CarPlayBackgroundSession.active)
    }

    @Test fun aProbeEndingDoesNotDisconnectTheActiveIphone() {
        val listener = listener()
        val session = mock(AirPlaySession::class.java)
        listener.onSessionActive(session)
        listener.onSessionEnded(mock(AirPlaySession::class.java))
        assertTrue(CarPlayBackgroundSession.active)
        assertNull(get<Runnable?>("retry"))
    }

    @Test fun resumedVideoCancelsRecoveryAndReappliesTheLatestViewArea() {
        val listener = listener()
        val session = mock(AirPlaySession::class.java)
        listener.onSessionActive(session)
        listener.onTransportError("temporary test interruption")
        assertNotNull(get<Runnable?>("retry"))
        listener.onVideoFrameRendered(session)
        assertNull(get<Runnable?>("retry"))
        verify(controller).showViewArea(layout.currentIndex)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
    }

    @Test fun closeCancelsRecoveryAndCompletesEveryWaiterAfterTeardown() {
        listener().onTransportError("test interruption")
        var completed = 0
        runtime.close { completed++ }
        runtime.close { completed++ }
        assertEquals(0, completed)
        assertNull(get<Runnable?>("retry"))
        assertFalse(CarPlayBackgroundSession.hasSession())
        drainWorker()
        assertEquals(2, completed)
        verify(controller, times(1)).close()
        verify(sink, times(1)).close()
        listener().onTransportError("late interruption")
        assertNull(get<Runnable?>("retry"))
    }

    @Test fun oldRuntimeTeardownDoesNotClearTheNewOwnersSession() {
        val newer = mock(CarPlayController::class.java)
        val newOwner = Any()
        val display = CarPlayBackgroundSession.snapshot()!!.display
        CarPlayBackgroundSession.store(newer, sink, 1182, 920, newOwner, display) {}
        runtime.close()
        drainWorker()
        assertTrue(CarPlayBackgroundSession.isOwner(newOwner))
        assertSame(newer, CarPlayBackgroundSession.snapshot()?.controller)
        verify(newer, never()).close()
    }

    private fun drainWorker() {
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun listener(): AirPlaySessionListener = ReflectionHelpers.callInstanceMethod(runtime, "listener",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, get<Int>("generation")))
    private fun <T> get(name: String): T = ReflectionHelpers.getField(runtime, name)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(runtime, name, value)
}
