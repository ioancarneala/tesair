package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentCallbacks
import android.content.pm.ApplicationInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.json.JSONArray
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
        shadowOf(requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter))
            .setState(BluetoothAdapter.STATE_ON)
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
        `when`(sink.awaitVideoReleased(2_000)).thenReturn(true)
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

    @Test fun manualRetryClearsTheConfigurationGateAndLoadsCorrectedCredentials() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Old test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        set("setupNeedsAttention", true)
        get<WirelessStartupRetryBudget>("retryBudget").nextDelayMillis()
        runtime.start()
        verify(controller, never()).close()

        AirPlayPersistence.saveExistingWifiCredentials(context, "Updated test hotspot", "test-only-5678")
        var prepared: CarPlayRuntimeConfig? = null
        mockConstruction(CarPlayController::class.java) { _, construction ->
            prepared = construction.arguments()[1] as CarPlayRuntimeConfig
        }.use { construction ->
            runtime.reconnect()
            assertFalse(runtime.setupNeedsAttention)
            assertEquals(0, get<WirelessStartupRetryBudget>("retryBudget").retries)
            drainWorker()

            verify(controller, times(1)).close()
            assertEquals(1, construction.constructed().size)
            verify(construction.constructed().single()).start()
            assertEquals("Updated test hotspot", prepared?.existingWifiSsid)
            assertEquals("test-only-5678", prepared?.existingWifiPassphrase)
            assertTrue(CarPlayBackgroundSession.isOwner(runtime))
            runtime.close()
            drainWorker()
        }
    }

    @Test fun manualRetryBypassesAQueuedBackoffWithoutLeavingTheOldTimerLive() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        repeat(4) { get<WirelessStartupRetryBudget>("retryBudget").nextDelayMillis() }
        listener().onTransportError("test interruption")
        assertTrue(runtime.manualRecoveryPending)

        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.reconnect()
            assertFalse(runtime.manualRecoveryPending)
            assertNull(get<Runnable?>("retry"))
            drainWorker()
            assertEquals(1, construction.constructed().size)
            verify(controller, times(1)).close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            worker.runAll()
            assertEquals(1, construction.constructed().size)
            verify(construction.constructed().single(), never()).close()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun manualRecoveryDoesNotInterruptPreparationRetirementOrAnActiveSession() {
        set("setupNeedsAttention", true)
        assertTrue(runtime.manualRecoveryPending)
        set("preparing", true)
        assertFalse(runtime.manualRecoveryPending)
        set("preparing", false)
        set("retiring", true)
        assertFalse(runtime.manualRecoveryPending)
        set("retiring", false)
        set("activeSession", mock(AirPlaySession::class.java))
        assertFalse(runtime.manualRecoveryPending)
        set("activeSession", null)
        set("requested", false)
        assertFalse(runtime.manualRecoveryPending)
    }

    @Test fun standbyIsOnlyAvailableBetweenAttemptsWithoutAnActiveIphone() {
        assertFalse(runtime.standbyReady)
        listener().onTransportError("test interruption")
        assertTrue(runtime.standbyReady)
        set("preparing", true)
        assertFalse(runtime.standbyReady)
        assertFalse(runtime.parkForStandby {})
        set("preparing", false)
        set("retiring", true)
        assertFalse(runtime.standbyReady)
        set("retiring", false)
        set("activeSession", mock(AirPlaySession::class.java))
        assertTrue(runtime.sessionActive)
        assertFalse(runtime.standbyReady)
        set("activeSession", null)
        set("requested", false)
        assertFalse(runtime.standbyReady)
    }

    @Test fun parkingCancelsRetryAndFencesOldCallbacksWithoutChangingSavedSetupOrViewer() {
        configureTestReceiver()
        val old = listener()
        val viewport = get<(Int, Int) -> Unit>("viewportListener")
        output.onViewport = viewport
        old.onTransportError("test interruption")
        var completed = 0
        assertTrue(runtime.parkForStandby { completed++ })
        assertTrue(runtime.standbyParked)
        assertFalse(runtime.standbyReady)
        assertNull(get<Runnable?>("retry"))
        assertEquals(0, completed)
        assertTrue(get<Boolean>("requested"))
        assertFalse(runtime.parkForStandby { completed++ })

        old.onTransportError("late old transport error")
        old.onSessionActive(mock(AirPlaySession::class.java))
        assertFalse(runtime.sessionActive)
        drainWorker()
        assertEquals(1, completed)
        assertEquals("Test hotspot", AirPlayPersistence.loadExistingWifiSsid(context))
        assertEquals("test-only-1234", AirPlayPersistence.loadExistingWifiPassphrase(context))
        assertEquals(1, mockingDetails(output).invocations.count { it.method.name == "setOnViewport" })
        verify(output, never()).close()
        verify(controller, times(1)).close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        drainWorker()
        assertNull(get<CarPlayController?>("controller"))
        assertNull(get<Runnable?>("retry"))
    }

    @Test fun normalStartCannotWakeAParkedReceiver() {
        listener().onTransportError("test interruption")
        assertTrue(runtime.parkForStandby {})
        drainWorker()
        runtime.start()
        assertTrue(runtime.standbyParked)
        assertFalse(get<Boolean>("preparing"))
        assertNull(get<Runnable?>("retry"))
    }

    @Test fun wakingDuringParkingWaitsForTheOldControllerAndStartsOnlyOnce() {
        configureTestReceiver()
        listener().onTransportError("test interruption")
        var parkedCompletions = 0
        assertTrue(runtime.parkForStandby { parkedCompletions++ })
        var awakeCallback = false
        runtime.onStandbyStateChanged = { if (!runtime.standbyParked) awakeCallback = true }
        mockConstruction(CarPlayController::class.java) { _, _ -> assertTrue(awakeCallback) }.use { construction ->
            runtime.resumeFromStandby()
            runtime.resumeFromStandby()
            assertTrue(awakeCallback)
            assertFalse(runtime.standbyParked)
            assertTrue(construction.constructed().isEmpty())
            drainWorker()
            assertEquals(1, parkedCompletions)
            assertEquals(1, construction.constructed().size)
            verify(controller, times(1)).close()
            verify(construction.constructed().single()).start()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun automaticWakePreservesTheConfigurationGateUntilManualReconnect() {
        configureTestReceiver()
        set("setupNeedsAttention", true)
        assertTrue(runtime.parkForStandby {})
        drainWorker()
        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.resumeFromStandby()
            drainWorker()
            assertFalse(runtime.standbyParked)
            assertTrue(runtime.setupNeedsAttention)
            assertTrue(runtime.standbyReady)
            assertTrue(construction.constructed().isEmpty())
            runtime.reconnect()
            drainWorker()
            assertFalse(runtime.setupNeedsAttention)
            assertEquals(1, construction.constructed().size)
            runtime.close()
            drainWorker()
        }
    }

    @Test fun manualReconnectFromParkingNotifiesTheServiceBeforePreparingAReplacement() {
        configureTestReceiver()
        listener().onTransportError("test interruption")
        assertTrue(runtime.parkForStandby {})
        drainWorker()
        assertTrue(runtime.manualRecoveryPending)
        var awakeCallback = false
        runtime.onStandbyStateChanged = { if (!runtime.standbyParked) awakeCallback = true }
        mockConstruction(CarPlayController::class.java) { _, _ -> assertTrue(awakeCallback) }.use { construction ->
            runtime.reconnect()
            assertTrue(awakeCallback)
            assertFalse(runtime.standbyParked)
            assertFalse(runtime.manualRecoveryPending)
            drainWorker()
            assertEquals(1, construction.constructed().size)
            runtime.close()
            drainWorker()
        }
    }

    @Test fun stopDuringParkingFinishesTeardownWithoutPublishingAWokenController() {
        configureTestReceiver()
        listener().onTransportError("test interruption")
        var parkedCompletions = 0
        var closeCompletions = 0
        assertTrue(runtime.parkForStandby { parkedCompletions++ })
        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.resumeFromStandby()
            runtime.close { closeCompletions++ }
            runtime.close { closeCompletions++ }
            runtime.resumeFromStandby()
            drainWorker()
            assertEquals(1, parkedCompletions)
            assertEquals(2, closeCompletions)
            assertFalse(runtime.standbyParked)
            assertFalse(runtime.standbyReady)
            assertTrue(construction.constructed().isEmpty())
            verify(controller, times(1)).close()
            verify(sink, times(1)).close()
        }
    }

    @Test fun incompleteControllerTeardownCannotAuthorizeCpuStandby() {
        `when`(controller.awaitClosed(4_000)).thenReturn(false)
        assertIncompleteTeardownDefersStandby()
    }

    @Test fun incompleteVideoReleaseCannotAuthorizeCpuStandby() {
        `when`(sink.awaitVideoReleased(2_000)).thenReturn(false)
        assertIncompleteTeardownDefersStandby()
    }

    @Test fun arrivalBypassesPendingBackoffButDoesNotInterruptPreparationOrAnActiveSession() {
        configureTestReceiver()
        listener().onTransportError("test interruption")
        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.resumeFromStandby()
            runtime.resumeFromStandby()
            drainWorker()
            assertEquals(1, construction.constructed().size)
            runtime.resumeFromStandby()
            verify(construction.constructed().single(), never()).close()
            listener().onSessionActive(mock(AirPlaySession::class.java))
            runtime.resumeFromStandby()
            assertTrue(runtime.sessionActive)
            verify(construction.constructed().single(), never()).close()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun standbyObserversDoNotReplaceTheUiObserver() {
        var standbyCalls = 0
        var uiCalls = 0
        runtime.onStandbyStateChanged = { standbyCalls++ }
        runtime.onStatusChanged = { uiCalls++ }
        val current = listener()
        val session = mock(AirPlaySession::class.java)
        current.onSessionActive(session)
        assertEquals(1, standbyCalls)
        assertEquals(1, uiCalls)
        current.onSessionEnded(session)
        assertEquals(2, standbyCalls)
        assertEquals(2, uiCalls)
        assertTrue(runtime.standbyReady)
    }

    @Test fun appearanceMonitoringOnlyRunsWhileAnIphoneSessionIsActive() {
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SCHEDULE)
        val appearance = get<CarPlayNightModeController>("nightMode")
        runtime.start()
        assertFalse(ReflectionHelpers.getField<Boolean>(appearance, "resumed"))
        val current = listener()
        val session = mock(AirPlaySession::class.java)
        current.onSessionActive(session)
        assertTrue(ReflectionHelpers.getField<Boolean>(appearance, "resumed"))
        current.onSessionEnded(session)
        assertFalse(ReflectionHelpers.getField<Boolean>(appearance, "resumed"))
        runtime.refreshAppearance()
        assertFalse(ReflectionHelpers.getField<Boolean>(appearance, "resumed"))
    }

    @Test fun expiredBootstrapSchedulesOneRetryAndBecomesEligibleForStandby() {
        configureTestReceiver()
        var reportStatus: ((CarPlayStatus) -> Unit)? = null
        mockConstruction(CarPlayController::class.java) { _, construction ->
            @Suppress("UNCHECKED_CAST")
            val callback = construction.arguments()[7] as (CarPlayStatus) -> Unit
            reportStatus = callback
        }.use { construction ->
            runtime.reconnect()
            drainWorker()
            assertEquals(1, construction.constructed().size)
            requireNotNull(reportStatus)(CarPlayStatus.ControlEnded)
            assertTrue(runtime.standbyReady)
            val first = get<Runnable>("retry")
            requireNotNull(reportStatus)(CarPlayStatus.ControlEnded)
            assertSame(first, get<Runnable>("retry"))
            assertEquals(1, get<WirelessStartupRetryBudget>("retryBudget").retries)
            verify(construction.constructed().single(), never()).close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            drainWorker()
            assertEquals(2, construction.constructed().size)
            verify(construction.constructed().first()).close()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun endedBootstrapControlDoesNotRestartAnActiveWifiSession() {
        configureTestReceiver()
        var reportStatus: ((CarPlayStatus) -> Unit)? = null
        mockConstruction(CarPlayController::class.java) { _, construction ->
            @Suppress("UNCHECKED_CAST")
            val callback = construction.arguments()[7] as (CarPlayStatus) -> Unit
            reportStatus = callback
        }.use { construction ->
            runtime.reconnect()
            drainWorker()
            listener().onSessionActive(mock(AirPlaySession::class.java))
            requireNotNull(reportStatus)(CarPlayStatus.ControlEnded)
            assertTrue(runtime.sessionActive)
            assertFalse(runtime.standbyReady)
            assertNull(get<Runnable?>("retry"))
            verify(construction.constructed().single(), never()).close()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun bluetoothOffWaitsWithoutPreparingControllersOrPausingRecovery() {
        CarPlayBackgroundSession.clear()
        set("controller", null)
        set("sink", null)
        set("layout", null)
        set("requested", false)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        val adapter = requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)

        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.start()
            assertEquals(com.shilapi.xcertplay.orchestration.CarPlayStatus.WaitingForPairedIphone, runtime.status)
            assertFalse(runtime.setupNeedsAttention)
            assertFalse(get<Boolean>("preparing"))
            assertNotNull(get<Runnable?>("retry"))
            drainWorker()
            assertTrue(construction.constructed().isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            drainWorker()
            assertTrue(construction.constructed().isEmpty())

            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            runtime.reconnect()
            drainWorker()
            assertEquals(1, construction.constructed().size)
            verify(construction.constructed().single()).start()
            runtime.close()
            drainWorker()
        }
    }

    @Test fun unknownBluetoothAdapterPreservesTheExistingControllerStartupPath() {
        CarPlayBackgroundSession.clear()
        set("controller", null)
        set("sink", null)
        set("layout", null)
        set("requested", false)
        val withoutBluetooth = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.BLUETOOTH_SERVICE) null else super.getSystemService(name)
            override fun getApplicationContext(): Context = this
            override fun registerComponentCallbacks(callback: ComponentCallbacks) = baseContext.registerComponentCallbacks(callback)
            override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = baseContext.unregisterComponentCallbacks(callback)
        }
        set("context", withoutBluetooth)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)

        mockConstruction(CarPlayController::class.java).use { construction ->
            runtime.start()
            drainWorker()
            assertEquals(1, construction.constructed().size)
            verify(construction.constructed().single()).start()
            runtime.close()
            drainWorker()
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

    @Test fun persistentVideoFormatFailureUsesTheExistingAutomaticRetry() {
        val failure: (Int, String) -> Unit = get("videoFailureListener")
        val attempt: Int = get("generation")
        failure(attempt, "unsupported_iphone_codec")
        val retry = get<Runnable?>("retry")
        assertNotNull(retry)
        failure(attempt, "unsupported_iphone_codec")
        assertSame(retry, get<Runnable?>("retry"))
        assertFalse(runtime.setupNeedsAttention)
        verify(controller, never()).close()
    }

    @Test fun videoFormatRecoveryIgnoresOldAttemptsAndUnknownReasons() {
        val failure: (Int, String) -> Unit = get("videoFailureListener")
        val attempt: Int = get("generation")
        failure(attempt - 1, "unsupported_iphone_codec")
        failure(attempt, "untrusted_external_reason")
        assertNull(get<Runnable?>("retry"))
        runtime.close()
        failure(attempt, "video_config_rejected")
        assertNull(get<Runnable?>("retry"))
    }

    @Test fun successfulVideoCancelsPendingFormatRecoveryWithoutRestartingTheSession() {
        val current = listener()
        val session = mock(AirPlaySession::class.java)
        current.onSessionActive(session)
        val failure: (Int, String) -> Unit = get("videoFailureListener")
        failure(get("generation"), "video_config_rejected")
        assertNotNull(get<Runnable?>("retry"))
        current.onVideoFrameRendered(session)
        assertNull(get<Runnable?>("retry"))
        assertTrue(runtime.sessionActive)
        verify(controller, never()).close()
    }

    @Test fun explicitStopReportsOneTerminalStateWithoutMisclassifyingLateTransportCallbacks() {
        val telemetry = BrowserTelemetryEngine(context, online = { false })
        BrowserTelemetry.replaceEngineForTest(context, telemetry)
        try {
            telemetry.setEnabled(true)
            val current = listener()
            current.onSessionActive(mock(AirPlaySession::class.java))
            runtime.close("user_stop")
            current.onTransportError("late teardown transport error")
            current.onSessionEnded(mock(AirPlaySession::class.java))
            runtime.close("service_stopped")
            assertTrue(telemetry.awaitIdle())
            val stored = JSONArray(File(context.noBackupFilesDir, "telemetry/events.json").readText())
            val events = (0 until stored.length()).map { stored.getJSONObject(it) }
            val terminal = events.single { it.getString("name") == "session_ended" }.getJSONObject("attributes")
            assertEquals("user_stop", terminal.getString("reason"))
            assertFalse(terminal.getBoolean("receiverRunning"))
            assertFalse(terminal.getBoolean("iphoneActive"))
            assertFalse(terminal.getBoolean("videoRendered"))
            assertEquals(0, terminal.getInt("browserCount"))
            assertTrue(events.none { it.getString("name") == "iphone_disconnected" })
        } finally { telemetry.shutdown() }
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

    private fun configureTestReceiver() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Test hotspot", "test-only-1234")
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
    }

    private fun assertIncompleteTeardownDefersStandby() {
        configureTestReceiver()
        listener().onTransportError("test interruption")
        var completed = false
        assertTrue(runtime.parkForStandby {
            assertFalse(runtime.standbyParked)
            completed = true
        })
        mockConstruction(CarPlayController::class.java).use { construction ->
            drainWorker()
            assertTrue(completed)
            assertFalse(runtime.standbyParked)
            assertEquals(1, construction.constructed().size)
            verify(controller, times(1)).close()
            verify(sink, times(1)).close()
            runtime.close()
            drainWorker()
        }
    }

    private fun listener(): AirPlaySessionListener = ReflectionHelpers.callInstanceMethod(runtime, "listener",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, get<Int>("generation")))
    private fun <T> get(name: String): T = ReflectionHelpers.getField(runtime, name)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(runtime, name, value)
}
