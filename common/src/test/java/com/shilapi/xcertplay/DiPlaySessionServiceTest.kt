package com.shilapi.xcertplay

import android.os.Bundle
import android.os.Looper
import android.content.pm.PackageManager
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.Robolectric
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
class DiPlaySessionServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val services = mutableListOf<DiPlaySessionService>()
    private val workers = mutableListOf<PausedExecutorService>()
    private lateinit var receiver: BrowserCarPlayRuntime
    private lateinit var output: BrowserStreamOutput
    private val closed = mutableListOf<() -> Unit>()

    @Before fun prepare() {
        val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        requireNotNull(installed.applicationInfo).metaData = Bundle().apply { putBoolean(BROWSER_PRODUCT_METADATA, true) }
        shadowOf(app.packageManager).installPackage(installed)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Test hotspot", "test-only-1234")
        BrowserSessionRecovery.markStarted(app)
        assertTrue(BrowserSessionRecovery.canRecover(app))
        receiver = mock(BrowserCarPlayRuntime::class.java)
        `when`(receiver.isClosing).thenReturn(true)
        doAnswer { invocation -> closed += invocation.getArgument<() -> Unit>(1); null }
            .`when`(receiver).close(anyString(), any<() -> Unit>() ?: {})
        output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", receiver)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
    }

    @After fun cleanup() {
        services.forEach { service -> ReflectionHelpers.setField(service, "destroyed", true) }
        val states = ReflectionHelpers.getStaticField<BrowserConnectionStateStore>(DiPlaySessionService::class.java, "connectionStates")
        services.forEach(states::release)
        workers.forEach { it.runAll(); it.shutdownNow() }
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "runtime", null)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        listOf("diplay", "browser_session_recovery", "xcertplay_airplay").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @Test fun destroyedServiceRetainsTheReceiverUntilItsResourcesHaveClosed() {
        val service = service()

        service.onDestroy()

        assertSame(receiver, DiPlaySessionService.currentBrowserRuntime())
        assertNull(DiPlaySessionService.currentBrowserOutput())
        assertEquals(1, closed.size)
        closed.single().invoke()
        assertNull(DiPlaySessionService.currentBrowserRuntime())
    }

    @Test fun replacementServiceWaitsForActualCloseBeforeCreatingAReceiver() {
        val service = service()
        ReflectionHelpers.setField(service, "browserRequested", true)

        mockConstruction(BrowserCarPlayRuntime::class.java).use { construction ->
            ReflectionHelpers.callInstanceMethod<Void>(service, "startBrowserReceiver")
            ReflectionHelpers.callInstanceMethod<Void>(service, "startBrowserReceiver")

            assertTrue(construction.constructed().isEmpty())
            assertSame(receiver, DiPlaySessionService.currentBrowserRuntime())
            assertEquals(1, closed.size)
            verify(receiver, never()).start()

            closed.single().invoke()

            val replacement = construction.constructed().single()
            assertSame(replacement, DiPlaySessionService.currentBrowserRuntime())
            verify(replacement).start()
        }
    }

    @Test fun stoppedReplacementCannotRestartWhenTheOldReceiverFinallyCloses() {
        val service = service()
        ReflectionHelpers.setField(service, "browserRequested", true)
        ReflectionHelpers.callInstanceMethod<Void>(service, "startBrowserReceiver")
        ReflectionHelpers.setField(service, "browserRequested", false)

        mockConstruction(BrowserCarPlayRuntime::class.java).use { construction ->
            closed.single().invoke()

            assertTrue(construction.constructed().isEmpty())
            assertNull(DiPlaySessionService.currentBrowserRuntime())
        }
    }

    @Test fun servicePublishesOneRuntimeAndViewerObservationAndUpdatesItOnEvents() {
        val service = service()
        val receiverState = BrowserReceiverState(phase = BrowserReceiverState.Phase.CONNECTED,
            sessionRunning = true, iphoneActive = true)
        `when`(receiver.connectionState).thenReturn(receiverState)
        `when`(output.clientCount).thenReturn(1)
        `when`(output.hasRenderedVideo).thenReturn(false)
        ReflectionHelpers.callInstanceMethod<Void>(service, "claimConnectionState")
        val received = mutableListOf<BrowserConnectionState>()
        val observation = DiPlaySessionService.observeConnectionState(received::add)
        try {
            assertSame(receiverState, received.single().receiver)
            assertTrue(received.single().requested)
            assertTrue(received.single().serverRunning)
            assertTrue(received.single().streaming)
            assertFalse(received.single().videoRendered)

            `when`(output.hasRenderedVideo).thenReturn(true)
            ReflectionHelpers.callInstanceMethod<Void>(service, "publishConnectionState")
            assertTrue(received.last().videoRendered)
            assertSame(received.last(), DiPlaySessionService.currentConnectionState())
        } finally { observation.close() }
    }

    @Test fun workerCallbacksArePublishedOnMainAndAnOldServiceCannotReplaceTheNewState() {
        val old = service()
        ReflectionHelpers.callInstanceMethod<Void>(old, "claimConnectionState")
        val receivedOn = mutableListOf<Looper?>()
        val observation = DiPlaySessionService.observeConnectionState { receivedOn += Looper.myLooper() }
        try {
            `when`(output.clientCount).thenReturn(1)
            Thread { ReflectionHelpers.callInstanceMethod<Void>(old, "publishConnectionState") }.apply { start(); join() }
            assertEquals(0, DiPlaySessionService.currentConnectionState().browserCount)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, DiPlaySessionService.currentConnectionState().browserCount)
            assertTrue(receivedOn.all { it === Looper.getMainLooper() })

            val replacement = service()
            ReflectionHelpers.callInstanceMethod<Void>(replacement, "claimConnectionState")
            `when`(output.clientCount).thenReturn(0)
            ReflectionHelpers.callInstanceMethod<Void>(old, "publishConnectionState")
            ReflectionHelpers.callInstanceMethod<Void>(old, "releaseConnectionState")
            assertEquals(1, DiPlaySessionService.currentConnectionState().browserCount)
            ReflectionHelpers.callInstanceMethod<Void>(replacement, "publishConnectionState")
            assertEquals(0, DiPlaySessionService.currentConnectionState().browserCount)
        } finally { observation.close() }
    }

    private fun service(): DiPlaySessionService = Robolectric.buildService(DiPlaySessionService::class.java)
        .get().also { service ->
            ReflectionHelpers.getField<ExecutorService>(service, "browserWorker").shutdownNow()
            val worker = PausedExecutorService()
            ReflectionHelpers.setField(service, "browserWorker", worker)
            workers += worker
            services += service
        }
}
