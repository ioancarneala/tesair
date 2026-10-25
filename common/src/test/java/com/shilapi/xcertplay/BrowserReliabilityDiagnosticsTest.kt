package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserReliabilityDiagnosticsTest {
    private fun browserContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
        override fun getApplicationContext(): Context = this
    }

    @Test fun eventsSurviveARecreatedLoggerAndRedactPrivateValuesBeforeStorage() {
        val app = browserContext()
        val file = File(app.filesDir, "logs/reliability.log")
        file.delete()
        BrowserReliabilityDiagnostics.record(app, "viewer_close reason=read_io code=0")
        BrowserReliabilityDiagnostics.record(app, "password=synthetic-secret")
        BrowserReliabilityDiagnostics.record(app, "peer=10.11.12.13")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val stored = file.readText()
        assertTrue(stored.contains("viewer_close reason=read_io"))
        assertTrue(stored.contains("epochMs="))
        assertTrue(stored.contains("uptimeMs="))
        assertFalse(stored.contains("synthetic-secret"))
        assertFalse(stored.contains("10.11.12.13"))
        SessionLogFile(file).use { it.beginSession("synthetic process restarted") }
        val report = BrowserReliabilityDiagnostics.report(app)
        assertTrue(report.contains("viewer_close reason=read_io"))
        assertTrue(report.contains("synthetic process restarted"))
    }

    @Test fun healthSnapshotContainsStateWithoutCollectingNetworkOrPhoneNames() {
        val app = browserContext()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Synthetic Private Phone")
        AirPlayPersistence.saveManualHotspotSsid(app, "Synthetic Private Network")
        val snapshot = BrowserReliabilityDiagnostics.snapshot(app)
        assertTrue(snapshot, snapshot.startsWith("system screenOn="))
        assertTrue(snapshot.contains("batteryExempt="))
        assertTrue(snapshot.contains("thermal="))
        assertTrue(snapshot.contains("route="))
        assertTrue(snapshot.contains("bluetooth="))
        assertTrue(snapshot.contains("receiverRequested="))
        assertFalse(snapshot.contains("Synthetic Private"))
        assertFalse(snapshot.contains("00:11:22:33:44:55"))
        assertEquals(snapshot, DiagnosticRedactor.redact(snapshot))
    }

    @Test fun aliasPresenceIncludesAnAssignedAddressOnADownInterface() {
        val network = mock(NetworkInterface::class.java)
        `when`(network.isUp).thenReturn(false)
        `when`(network.inetAddresses).thenAnswer {
            Collections.enumeration(listOf(InetAddress.getByName(BrowserAddressService.ADDRESS)))
        }
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(network)) }

            val snapshot = BrowserReliabilityDiagnostics.snapshot(browserContext())

            assertTrue(snapshot, snapshot.contains("aliasPresent=true"))
        }
    }

    @Test fun unrelatedInterfaceAddressDoesNotCountAsTheAlias() {
        val network = mock(NetworkInterface::class.java)
        `when`(network.isUp).thenReturn(true)
        `when`(network.inetAddresses).thenAnswer {
            Collections.enumeration(listOf(InetAddress.getByName("192.0.2.1")))
        }
        mockStatic(NetworkInterface::class.java).use { interfaces ->
            interfaces.`when`<java.util.Enumeration<NetworkInterface>> { NetworkInterface.getNetworkInterfaces() }
                .thenAnswer { Collections.enumeration(listOf(network)) }

            val snapshot = BrowserReliabilityDiagnostics.snapshot(browserContext())

            assertTrue(snapshot, snapshot.contains("aliasPresent=false"))
        }
    }

    @Test fun standbyChecksOncePerMinuteWithoutPollingScreenOffAndReportsEveryFifteenMinutes() {
        MonitorFixture().use { fixture ->
            fixture.clock.advanceTo(0)
            assertEquals(listOf(0L), fixture.reports)
            assertEquals(60_000L, fixture.clock.nextDelay())

            fixture.clock.advanceTo(14 * 60_000L)
            assertEquals(15, fixture.clock.executed)
            assertEquals(listOf(0L), fixture.reports)
            assertEquals(0, fixture.proofPolls)

            fixture.clock.advanceTo(15 * 60_000L)
            assertEquals(listOf(0L, 900_000L), fixture.reports)
        }
    }

    @Test fun connectionPromotesThePendingStandbyTickImmediatelyAndReportsOncePerMinute() {
        MonitorFixture().use { fixture ->
            fixture.clock.advanceTo(0)
            fixture.clock.advanceTo(10_000)
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = true, browser = true)
            fixture.monitor.activityChanged()
            assertEquals(0L, fixture.clock.nextDelay())

            fixture.clock.advanceTo(10_000)
            assertEquals(listOf(0L, 10_000L), fixture.reports)
            assertEquals(1, fixture.proofPolls)
            assertEquals(2_000L, fixture.clock.nextDelay())

            fixture.clock.advanceTo(69_999)
            assertEquals(2, fixture.reports.size)
            fixture.clock.advanceTo(70_000)
            assertEquals(listOf(0L, 10_000L, 70_000L), fixture.reports)
            assertEquals(31, fixture.proofPolls)
        }
    }

    @Test fun iphoneOrBrowserAloneKeepsMinuteReportsWithoutTheTwoSecondProofLoop() {
        MonitorFixture().use { fixture ->
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = true, browser = false)
            fixture.clock.advanceTo(60_000)
            assertEquals(listOf(0L, 60_000L), fixture.reports)
            assertEquals(0, fixture.proofPolls)
            assertEquals(60_000L, fixture.clock.nextDelay())

            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = false, browser = true)
            fixture.monitor.activityChanged()
            fixture.clock.advanceTo(60_000)
            assertEquals(listOf(0L, 60_000L, 60_000L), fixture.reports)
            assertEquals(0, fixture.proofPolls)
        }
    }

    @Test fun endingTheStreamStopsFastPollingAndStartsTheStandbyReportInterval() {
        MonitorFixture().use { fixture ->
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = true, browser = true)
            fixture.clock.advanceTo(0)
            fixture.clock.advanceTo(1_000)
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = false, browser = false)
            fixture.monitor.activityChanged()
            fixture.clock.advanceTo(1_000)
            assertEquals(listOf(0L, 1_000L), fixture.reports)
            assertEquals(60_000L, fixture.clock.nextDelay())
            assertEquals(1, fixture.proofPolls)
            assertEquals(1, fixture.proofStops)

            fixture.clock.advanceTo(900_999)
            assertEquals(2, fixture.reports.size)
            assertEquals(1, fixture.proofPolls)
            fixture.clock.advanceTo(901_000)
            assertEquals(listOf(0L, 1_000L, 901_000L), fixture.reports)
        }
    }

    @Test fun repeatedActivityCallbacksCoalesceAndDoNotPostponeADueReport() {
        MonitorFixture().use { fixture ->
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = true, browser = false)
            fixture.clock.advanceTo(30_000)
            repeat(3) { fixture.monitor.activityChanged() }
            assertEquals(1, fixture.clock.pendingCount())
            fixture.clock.advanceTo(30_000)
            assertEquals(listOf(0L), fixture.reports)
            assertEquals(30_000L, fixture.clock.nextDelay())
            fixture.clock.advanceTo(60_000)
            assertEquals(listOf(0L, 60_000L), fixture.reports)
        }
    }

    @Test fun closedMonitorCancelsPendingTicksAndIgnoresConnectionCallbacks() {
        MonitorFixture().use { fixture ->
            fixture.clock.advanceTo(0)
            fixture.monitor.close()
            repeat(3) { fixture.monitor.activityChanged() }
            fixture.clock.advanceTo(30 * 60_000L)
            assertEquals(listOf(0L), fixture.reports)
            assertEquals(0, fixture.clock.pendingCount())
            assertTrue(fixture.clock.shutdown)
        }
    }

    @Test fun teardownDuringAnInFlightReportCannotScheduleWorkAfterExecutorShutdown() {
        MonitorFixture().use { fixture ->
            fixture.activity = BrowserReliabilityMonitor.Activity(iphone = true, browser = true)
            fixture.onReport = { fixture.monitor.close() }
            fixture.clock.advanceTo(0)
            assertEquals(listOf(0L), fixture.reports)
            assertTrue(fixture.clock.shutdown)
            assertEquals(0, fixture.proofPolls)
            assertEquals(0, fixture.clock.pendingCount())
            fixture.monitor.activityChanged()
        }
    }

    @Test fun closedMonitorCannotEnqueueAnActiveSampleAfterTheTerminalState() {
        val app = browserContext()
        val telemetry = BrowserTelemetryEngine(app, online = { false })
        BrowserTelemetry.replaceEngineForTest(app, telemetry)
        val output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        `when`(output.clientCount).thenReturn(1)
        `when`(output.hasRenderedVideo).thenReturn(true)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        telemetry.setEnabled(true)
        val monitor = BrowserReliabilityMonitor(app)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(monitor, "sampleTelemetry")
            monitor.close()
            BrowserTelemetry.sessionEnded(app, "user_stop")
            // A tick that reached sampling after close must not replace the terminal observation.
            ReflectionHelpers.callInstanceMethod<Unit>(monitor, "sampleTelemetry")
            assertTrue(telemetry.awaitIdle())
            val stored = JSONArray(File(app.noBackupFilesDir, "telemetry/events.json").readText())
            val events = (0 until stored.length()).map { stored.getJSONObject(it) }
            assertTrue(events.any { it.getString("name") == "reliability_sample" &&
                it.getJSONObject("attributes").getInt("browserCount") == 1 })
            assertEquals("session_ended", events.last().getString("name"))
            assertFalse(events.last().getJSONObject("attributes").getBoolean("receiverRunning"))
        } finally {
            monitor.close()
            telemetry.shutdown()
            ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        }
    }

    private inner class MonitorFixture : AutoCloseable {
        val clock = MonitorClock()
        var activity = BrowserReliabilityMonitor.Activity(iphone = false, browser = false)
        val reports = mutableListOf<Long>()
        var proofPolls = 0
        var proofStops = 0
        var onReport: () -> Unit = {}
        private val app = browserContext()
        private val telemetry = BrowserTelemetryEngine(app, online = { false })
        val monitor: BrowserReliabilityMonitor

        init {
            telemetry.enabled.set(false)
            BrowserTelemetry.replaceEngineForTest(app, telemetry)
            monitor = BrowserReliabilityMonitor(app, worker = clock.worker, elapsed = { clock.now },
                activity = { activity }, report = { reports += clock.now; onReport() },
                pollScreenOff = { proofPolls++ }, stopScreenOff = { proofStops++ })
        }

        override fun close() {
            monitor.close()
            telemetry.shutdown()
        }
    }

    /** Executes the real monitor callbacks against monotonic virtual time, without sleeping. */
    private class MonitorClock {
        private class Task(val due: Long, val runnable: Runnable, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        val worker = mock(ScheduledExecutorService::class.java)
        var now = 0L
            private set
        var executed = 0
            private set
        var shutdown = false
            private set

        init {
            doAnswer { call ->
                if (shutdown) throw RejectedExecutionException("Test executor is closed")
                val task = Task(now + call.getArgument<Long>(1), call.getArgument(0))
                tasks += task
                val future = mock(ScheduledFuture::class.java)
                doAnswer { task.cancelled = true; true }.`when`(future).cancel(false)
                future
            }.`when`(worker).schedule(any(Runnable::class.java), anyLong(), eq(TimeUnit.MILLISECONDS))
            doAnswer { call -> call.getArgument<Runnable>(0).run(); null }
                .`when`(worker).execute(any(Runnable::class.java))
            doAnswer { shutdown = true; null }.`when`(worker).shutdown()
        }

        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val next = tasks.filterNot { it.cancelled }.minByOrNull { it.due } ?: break
                if (next.due > target) break
                tasks.remove(next)
                now = next.due
                executed++
                next.runnable.run()
            }
            now = target
        }

        fun pendingCount(): Int = tasks.count { !it.cancelled }
        fun nextDelay(): Long = tasks.filterNot { it.cancelled }.minOf { it.due } - now
    }
}
