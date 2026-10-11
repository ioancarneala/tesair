package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")
    private fun listener() = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(activity,
        "createSessionListener", from(Int::class.javaPrimitiveType, 0))

    private fun useBrowserMode() {
        ReflectionHelpers.setField(activity, "mBase", object : ContextWrapper(activity.baseContext) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
        })
        assertTrue(browserCarPlayEnabled(activity))
    }

    private fun prepareRetainedOwner() {
        useBrowserMode()
        ReflectionHelpers.setField(activity, "isActivityStarted", false)
        val size = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 1080)
        ReflectionHelpers.setField(activity, "activeDisplaySize", size)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.setField(activity, "teardownExecutor", PausedExecutorService())
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }

    @Test fun browserTransientFailuresKeepRetryingWhileTheHostIsNotVisible() {
        useBrowserMode()
        ReflectionHelpers.setField(activity, "isActivityStarted", false)
        repeat(12) { generation ->
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            val reason = if (generation % 2 == 0) WirelessStartupFailure.FIRST_TCP_TIMEOUT
                else WirelessStartupFailure.HOTSPOT_NOT_READY
            report(generation, CarPlayStatus.Failed("temporary absence", startupFailure = reason))
            assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
            assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            assertEquals(View.GONE, retry.visibility)
        }
        assertEquals(5, budget().retries)
        assertEquals(5, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun browserConfigurationFailureStillRequiresCorrection() {
        useBrowserMode()
        report(status = CarPlayStatus.Failed("Invalid hotspot details",
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun queuedBrowserRetryRunsForTheRetainedBackgroundOwner() {
        prepareRetainedOwner()
        report()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue(ReflectionHelpers.getField(activity, "handshakeResetInProgress"))
        assertTrue(CarPlayBackgroundSession.isOwner(activity))
    }

    @Test fun queuedBrowserRetryCannotRestartAfterStopMenuNewGenerationOrOwnerChange() {
        prepareRetainedOwner()
        repeat(4) { cancellation ->
            budget().manualRetry()
            ReflectionHelpers.setField(activity, "restartGeneration", 0)
            ReflectionHelpers.setField(activity, "startupFailureGeneration", -1)
            ReflectionHelpers.setField(activity, "menuOpen", false)
            ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
            ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
            report()
            assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            when (cancellation) {
                0 -> ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
                1 -> ReflectionHelpers.setField(activity, "menuOpen", true)
                2 -> ReflectionHelpers.setField(activity, "restartGeneration", 1)
                3 -> ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            assertEquals(if (cancellation == 2) 1 else 0,
                ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
            assertFalse(ReflectionHelpers.getField(activity, "handshakeResetInProgress"))
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        }
    }

    @Test fun browserFrameCancelsPendingRetryButHandshakeAloneDoesNot() {
        prepareRetainedOwner()
        val listener = listener()
        val session = mock(AirPlaySession::class.java)
        listener.onTransportError("connection lost")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        listener.onSessionActive(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        listener.onVideoFrameRendered(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "handshakeResetInProgress"))
    }

    @Test fun cancelledBrowserTimerCannotClearOrRunTheNextLossRetry() {
        prepareRetainedOwner()
        val listener = listener()
        val session = mock(AirPlaySession::class.java)
        listener.onTransportError("first loss")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        listener.onSessionActive(session)
        listener.onVideoFrameRendered(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))

        listener.onSessionEnded(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertTrue("The first timer must leave the newer retry scheduled",
            ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue(ReflectionHelpers.getField(activity, "handshakeResetInProgress"))
    }

    @Test fun manualBrowserRestartCannotStrandTheNewGenerationsRetry() {
        prepareRetainedOwner()
        report()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        budget().manualRetry()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "restartCarPlay",
            from(String::class.java, "Manual retry"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))

        // The teardown executor stays paused; model the next generation's startup failure
        // without opening a Bluetooth connection or constructing an authenticated controller.
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        report(1)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertTrue("The old generation's timer must not consume the new retry",
            ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(2, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue(ReflectionHelpers.getField(activity, "handshakeResetInProgress"))
    }
}
