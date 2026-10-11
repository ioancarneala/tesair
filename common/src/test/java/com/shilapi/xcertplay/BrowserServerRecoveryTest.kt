package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.Manifest
import android.app.Service
import android.os.Looper
import android.os.PowerManager
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserServerRecoveryTest {
    private lateinit var lifecycle: ServiceController<DiPlaySessionService>
    private lateinit var service: DiPlaySessionService
    private lateinit var worker: PausedExecutorService
    private lateinit var output: BrowserStreamOutput
    private lateinit var runtime: BrowserCarPlayRuntime
    private var destroyed = false

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = lifecycle.get()
        ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(service.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        ReflectionHelpers.getField<ExecutorService>(service, "browserWorker").shutdownNow()
        worker = PausedExecutorService()
        ReflectionHelpers.setField(service, "browserWorker", worker)
        output = mock(BrowserStreamOutput::class.java)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        runtime = mock(BrowserCarPlayRuntime::class.java)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", runtime)
        BrowserAddressService.disable(service)
        ShadowVpnService.setPrepareResult(null)
        BrowserSessionRecovery.markStopped(service)
        AirPlayPersistence.saveWirelessEnabled(service, false)
    }

    @After fun tearDown() {
        if (!destroyed) lifecycle.destroy()
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayBackgroundSession.clear()
        ShadowVpnService.setPrepareResult(null)
        BrowserSessionRecovery.markStopped(service)
    }

    @Test fun transientServerFailureRecoversWithoutAnotherActivityStart() {
        doAnswer { throw IOException("temporary bind failure") }.doNothing().`when`(output).start()
        start()
        val firstLock = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "cpuLock")
        completeAttempt()
        assertFalse("Backoff must not hold the failed attempt's CPU lock", firstLock.isHeld)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        worker.runAll()
        verify(output, times(1)).start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        completeAttempt()
        verify(output, times(2)).start()
        assertTrue(ReflectionHelpers.getField<PowerManager.WakeLock>(service, "cpuLock").isHeld)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        worker.runAll()
        verify(output, times(2)).start()
    }

    @Test fun repeatedStartDoesNotDuplicateInFlightWorkOrTheRetryTimer() {
        doAnswer { throw IOException("temporary bind failure") }.doNothing().`when`(output).start()
        repeat(3) { start() }
        completeAttempt()
        repeat(3) { start() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        completeAttempt()
        verify(output, times(2)).start()
        assertNull(ReflectionHelpers.getField<Runnable?>(service, "browserRetry"))
    }

    @Test fun continuingFailuresBackOffToThirtySecondsWithoutHoldingLocks() {
        doAnswer { throw IOException("temporary bind failure") }.`when`(output).start()
        start()
        completeAttempt()
        val delays = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        delays.forEachIndexed { index, delay ->
            assertNull(ReflectionHelpers.getField<PowerManager.WakeLock?>(service, "cpuLock"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delay - 1))
            worker.runAll()
            verify(output, times(index + 1)).start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            completeAttempt()
            verify(output, times(index + 2)).start()
        }
    }

    @Test fun explicitDisconnectCancelsQueuedRetryBeforeServiceDestruction() {
        doAnswer { throw IOException("temporary bind failure") }.`when`(output).start()
        start()
        completeAttempt()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        worker.runAll()
        verify(output, times(1)).start()
        assertNull(ReflectionHelpers.getField<Runnable?>(service, "browserRetry"))
    }

    @Test fun lateFailureCannotRestartAfterDestruction() {
        doAnswer { throw IOException("temporary bind failure") }.`when`(output).start()
        start()
        worker.runAll()
        lifecycle.destroy()
        destroyed = true
        completeAttempt()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))

        verify(output, times(1)).start()
        assertNull(ReflectionHelpers.getField<Runnable?>(service, "browserRetry"))
        assertNull(ReflectionHelpers.getField<PowerManager.WakeLock?>(service, "cpuLock"))
    }

    @Test fun stoppedAttemptsLateFailureCannotReleaseANewerStartOrScheduleItsRetry() {
        doAnswer { throw IOException("temporary bind failure") }.doNothing().`when`(output).start()
        start()
        worker.runAll()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)
        start()
        completeAttempt()

        verify(output, times(2)).start()
        assertTrue(ReflectionHelpers.getField<PowerManager.WakeLock>(service, "cpuLock").isHeld)
        assertNull(ReflectionHelpers.getField<Runnable?>(service, "browserRetry"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        worker.runAll()
        verify(output, times(2)).start()
    }

    @Test fun enabledAddressRecoversAfterItsServiceStopsWithoutRestartingCarPlay() {
        `when`(output.running).thenReturn(true)
        start()
        completeAttempt()
        allowAddressThenPause()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        assertEquals(BrowserAddressService.ACTION_START, shadowOf(service.application).nextStartedService?.action)
        assertNull("Maintenance must queue at most one address start", shadowOf(service.application).nextStartedService)
        verify(output, times(1)).start()
    }

    @Test fun disabledOrPermissionRevokedAddressIsNotRestartedByMaintenance() {
        `when`(output.running).thenReturn(true)
        start()
        completeAttempt()
        clearStartedServices()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertNull(shadowOf(service.application).nextStartedService)

        allowAddressThenPause()
        ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertNull(shadowOf(service.application).nextStartedService)
        assertEquals(BrowserAddressService.Phase.PERMISSION_REQUIRED, BrowserAddressService.snapshot(service).phase)
    }

    @Test fun disconnectCancelsAddressMaintenanceEvenBeforeServiceDestruction() {
        `when`(output.running).thenReturn(true)
        start()
        completeAttempt()
        allowAddressThenPause()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))

        assertNull(shadowOf(service.application).nextStartedService)
        assertFalse(BrowserAddressService.snapshot(service).active)
    }

    @Test fun failedServerDoesNotRestartAnAddressBeforeItsServerRecovers() {
        doAnswer { throw IOException("temporary bind failure") }.`when`(output).start()
        start()
        completeAttempt()
        allowAddressThenPause()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        assertNull(shadowOf(service.application).nextStartedService)
    }

    @Test fun systemRestartRestoresTheReceiverWithoutAnActivityOrLauncher() {
        configureReceiver()
        BrowserSessionRecovery.markStarted(service)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        completeAttempt()

        verify(output, times(1)).start()
        verify(runtime, times(1)).start()
        assertNull(shadowOf(service.application).nextStartedActivity)
        assertTrue(BrowserSessionRecovery.requested(service))
    }

    @Test fun repeatedStartAndRecoveryDoNotDuplicateTheLiveReceiver() {
        configureReceiver()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        completeAttempt()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertEquals(Service.START_STICKY,
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECOVER), 0, 3))
        completeAttempt()

        verify(output, times(1)).start()
        verify(runtime, times(1)).start()
        assertNull(shadowOf(service.application).nextStartedActivity)
    }

    @Test fun staleSystemRestartAfterDisconnectDoesNotResurrectTheServerOrReceiver() {
        configureReceiver()
        service.onStartCommand(Intent(), 0, 1)
        completeAttempt()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECOVER), 0, 4))
        completeAttempt()
        assertFalse(BrowserSessionRecovery.requested(service))
        assertNull(shadowOf(service.application).nextStartedActivity)
        verify(output, times(1)).start()
        verify(runtime, times(1)).start()
    }

    @Test fun unconfiguredBrowserDoesNotRestartAReceiverOrOpenSetupFromTheBackground() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
        completeAttempt()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        assertNull(shadowOf(service.application).nextStartedActivity)
        verify(output, times(1)).start()
        verify(runtime, never()).start()
    }

    @Test fun recoveryCannotStartAnUnrequestedServer() {
        configureReceiver()
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECOVER), 0, 1))
        completeAttempt()
        verify(output, never()).start()
        verify(runtime, never()).start()
        assertFalse(BrowserSessionRecovery.requested(service))
        assertNull(shadowOf(service.application).nextStartedActivity)
    }

    @Test
    @Config(sdk = [31])
    fun coldRecoveryWithWirelessDisabledPromotesBeforeStoppingWithoutStartingTheReceiver() {
        configureReceiver()
        BrowserSessionRecovery.markStarted(service)
        AirPlayPersistence.saveWirelessEnabled(service, false)

        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECOVER), 0, 1))

        assertForegroundStartWasSatisfiedAndRemoved()
        completeAttempt()
        verify(output, never()).start()
        verify(runtime, never()).start()
        assertTrue("Rejected recovery must retain the user's earlier intent", BrowserSessionRecovery.requested(service))
        assertNull(shadowOf(service.application).nextStartedActivity)
    }

    @Test
    @Config(sdk = [31])
    fun coldUnrequestedRestartPromotesBeforeStoppingWithoutStartingWork() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        assertForegroundStartWasSatisfiedAndRemoved()
        completeAttempt()
        verify(output, never()).start()
        verify(runtime, never()).start()
        assertFalse(BrowserSessionRecovery.requested(service))
    }

    @Test
    @Config(sdk = [31])
    fun coldStopSatisfiesTheForegroundDeadlineAndKeepsRecoveryStopped() {
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 1))

        assertForegroundStartWasSatisfiedAndRemoved()
        completeAttempt()
        verify(output, never()).start()
        verify(runtime, never()).start()
        assertFalse(BrowserSessionRecovery.requested(service))
    }

    @Test
    @Config(sdk = [31])
    fun aValidStartIsForegroundBeforeAnyServerWorkRuns() {
        start()

        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)
        verify(output, never()).start()
        completeAttempt()
        verify(output).start()
    }

    @Test fun removingTheBrowserTaskKeepsTheServiceOwnedReceiverRunning() {
        configureReceiver()
        start()
        completeAttempt()
        service.onTaskRemoved(Intent())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertTrue(BrowserSessionRecovery.requested(service))
        verify(runtime, never()).close()
        verify(output, never()).close()
        assertNull(shadowOf(service.application).nextStartedActivity)
    }

    @Test fun serviceDestructionReleasesTheReceiverButRetainsRequestedRecovery() {
        configureReceiver()
        start()
        completeAttempt()
        lifecycle.destroy()
        destroyed = true
        completeAttempt()

        verify(runtime, times(1)).close()
        assertNull(DiPlaySessionService.currentBrowserRuntime())
        assertTrue(BrowserSessionRecovery.canRecover(service))
    }

    @Test fun startDuringDisconnectWaitsForTeardownThenCreatesAFreshReceiver() {
        configureReceiver()
        `when`(output.running).thenReturn(true)
        var finishClose: (() -> Unit)? = null
        doAnswer { invocation ->
            finishClose = invocation.getArgument(0)
            null
        }.`when`(runtime).close(any<() -> Unit>() ?: {})
        start()
        completeAttempt()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)
        assertNotNull(finishClose)

        mockConstruction(BrowserCarPlayRuntime::class.java).use { construction ->
            assertEquals(Service.START_STICKY,
                service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECONNECT), 0, 3))
            completeAttempt()
            assertTrue(construction.constructed().isEmpty())
            verify(runtime, times(1)).start()
            verify(runtime, never()).reconnect()

            finishClose!!.invoke()
            assertEquals(1, construction.constructed().size)
            val replacement = construction.constructed().single()
            assertSame(replacement, DiPlaySessionService.currentBrowserRuntime())
            verify(replacement, times(1)).start()
            assertTrue(BrowserSessionRecovery.requested(service))
            lifecycle.destroy()
            destroyed = true
            completeAttempt()
        }
    }

    @Test fun lateDisconnectCompletionCannotRestartADestroyedService() {
        configureReceiver()
        `when`(output.running).thenReturn(true)
        var finishClose: (() -> Unit)? = null
        doAnswer { invocation ->
            if (finishClose == null) finishClose = invocation.getArgument(0)
            null
        }.`when`(runtime).close(any<() -> Unit>() ?: {})
        start()
        completeAttempt()
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 2)
        service.onStartCommand(Intent(DiPlaySessionService.ACTION_RECONNECT), 0, 3)
        completeAttempt()
        lifecycle.destroy()
        destroyed = true
        completeAttempt()

        mockConstruction(BrowserCarPlayRuntime::class.java).use { construction ->
            finishClose!!.invoke()
            assertTrue(construction.constructed().isEmpty())
            assertNull(DiPlaySessionService.currentBrowserRuntime())
            verify(runtime, times(1)).start()
        }
    }

    private fun configureReceiver() {
        DiPlayPreferences.saveAutoConnect(service, false)
        DiPlayPreferences.savePhone(service, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(service, true)
        AirPlayPersistence.saveWirelessHotspotMode(service, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(service, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(service, "test-passphrase")
        shadowOf(service.application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun assertForegroundStartWasSatisfiedAndRemoved() {
        val state = shadowOf(service)
        assertEquals("Even a no-op must acknowledge startForegroundService", 1, state.lastForegroundNotificationId)
        assertTrue(state.isForegroundStopped)
        assertTrue(state.isStoppedBySelf)
        assertNull("A rejected start must not leave a running notification", state.lastForegroundNotification)
    }

    private fun allowAddressThenPause() {
        assertTrue(BrowserAddressService.enable(service))
        BrowserAddressService.pause(service)
        clearStartedServices()
    }

    private fun clearStartedServices() {
        while (shadowOf(service.application).nextStartedService != null) { /* Discard setup requests. */ }
    }

    private fun start() { service.onStartCommand(Intent(), 0, 1) }
    private fun completeAttempt() {
        worker.runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
