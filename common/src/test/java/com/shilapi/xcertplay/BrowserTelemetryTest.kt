package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserTelemetryTest {
    private lateinit var app: Context
    private lateinit var transport: FakeTransport
    private lateinit var engine: BrowserTelemetryEngine
    private var online = false
    private var time = 1_800_000_000_000L
    private var elapsed = 100_000L

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.noBackupFilesDir, "telemetry").deleteRecursively()
        transport = FakeTransport()
        BrowserSessionRecovery.markStopped(app)
        engine = newEngine()
    }

    @After fun finish() { engine.shutdown() }

    private fun newEngine() = BrowserTelemetryEngine(app, transport, { online }, { time }, { elapsed })
    private fun queue() = JSONArray(File(app.noBackupFilesDir, "telemetry/events.json").readText())

    @Test fun unsetConsentDoesNotCreateIdentityOrQueueOrSendRequests() {
        engine.record("app_opened")
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals("unset", engine.choice())
        assertNull(engine.installationId())
        assertFalse(File(app.noBackupFilesDir, "telemetry/events.json").exists())
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun identitySurvivesProcessRecreationAndOrdinaryUpdates() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val id = engine.installationId()
        assertEquals(id, UUID.fromString(id).toString())
        assertTrue(app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE)
            .getString("token", "")!!.matches(Regex("[a-f0-9]{64}")))
        engine.shutdown()
        engine = newEngine()
        engine.record("app_opened")
        assertTrue(engine.awaitIdle())
        assertEquals(id, engine.installationId())
        assertTrue(engine.enabled.get())
    }

    @Test fun withdrawalClearsOfflineQueueAndStopsFurtherEventsButPreservesIdentity() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val id = engine.installationId()
        engine.record("iphone_connected")
        engine.setEnabled(false)
        engine.record("browser_connected")
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals("disabled", engine.choice())
        assertFalse(File(app.noBackupFilesDir, "telemetry/events.json").exists())
        assertTrue(transport.calls.isEmpty())
        assertTrue(transport.cancelled)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(id, engine.installationId())
    }

    @Test fun offlineEventsAreUploadedOnceWithEnrollmentAndSuccessfulAcknowledgement() {
        engine.setEnabled(true)
        engine.record("iphone_connected", mapOf("transport" to "wireless", "password" to "never-store-this"))
        assertTrue(engine.awaitIdle())
        assertFalse(queue().toString().contains("never-store-this"))
        val queuedIds = (0 until queue().length()).map { queue().getJSONObject(it).getString("id") }
        online = true
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(listOf("/v1/installations", "/v1/events"), transport.calls.map { it.path })
        val upload = JSONObject(transport.calls.last().body!!).getJSONArray("events")
        assertEquals(queuedIds, (0 until upload.length()).map { upload.getJSONObject(it).getString("id") })
        assertEquals(0, queue().length())
        time += 300_001
        elapsed += 300_001
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(2, transport.calls.size)
    }

    @Test fun deletionQueuesAuthenticatedTombstoneOfflineAndDoesNotUploadItAsAnEvent() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val oldId = engine.installationId()
        var deleted: Boolean? = null
        engine.deleteData { deleted = it }
        assertTrue(engine.awaitIdle())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, deleted)
        assertNull(engine.installationId())
        assertFalse(File(app.noBackupFilesDir, "telemetry/events.json").exists())
        online = true
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        assertEquals("DELETE", transport.calls.single().method)
        assertEquals("/v1/installations/$oldId", transport.calls.single().path)
        assertNull(app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE).getString("pendingDeletion", null))
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertNotEquals(oldId, engine.installationId())
    }

    @Test fun retryUsesBackoffAndKeepsStableEventIdsUntilAcknowledged() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val ids = queue().toString()
        online = true
        transport.status = 503
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        assertEquals(ids, queue().toString())
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        time += 60_001
        elapsed += 60_001
        transport.status = 200
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(3, transport.calls.size)
        assertEquals(0, queue().length())
    }

    @Test fun receiverSessionsGroupAttemptsAndEndBeforeTheNextReceiverLifecycle() {
        engine.setEnabled(true)
        engine.record("receiver_start_requested")
        engine.record("connection_attempt_started", mapOf("attempt" to 1, "automatic" to true))
        engine.record("recovery_scheduled", mapOf("attempt" to 1))
        engine.record("connection_attempt_started", mapOf("attempt" to 2, "automatic" to false))
        engine.record("session_ended", mapOf("reason" to "receiver_closed"))
        engine.record("receiver_start_requested")
        assertTrue(engine.awaitIdle())
        val all = queue()
        val sessions = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("name") == "session_started" }
        assertEquals(2, sessions.size)
        val firstId = sessions.first().getString("sessionId")
        assertNotEquals(firstId, sessions.last().getString("sessionId"))
        val attempts = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("name") == "connection_attempt_started" }
        assertTrue(attempts.all { it.getString("sessionId") == firstId })
        assertEquals(false, attempts.last().getJSONObject("attributes").getBoolean("automatic"))
    }

    @Test fun frequentPerformanceAndHealthSamplesAreBounded() {
        engine.setEnabled(true)
        repeat(30) {
            engine.record("performance_summary", mapOf("fpsDecoded" to 30))
            engine.record("reliability_sample", mapOf("charging" to false))
        }
        assertTrue(engine.awaitIdle())
        val all = queue()
        assertEquals(1, (0 until all.length()).count { all.getJSONObject(it).getString("name") == "performance_summary" })
        assertEquals(1, (0 until all.length()).count { all.getJSONObject(it).getString("name") == "reliability_sample" })
    }

    @Test fun deletionDuringEnrollmentCannotUploadOldIdentityAfterImmediateReconsent() {
        online = true
        transport.holdEnrollment = true
        engine.setEnabled(true)
        assertTrue(transport.enrollmentStarted.await(5, TimeUnit.SECONDS))
        val oldId = engine.installationId()
        engine.deleteData {}
        engine.setEnabled(true)
        transport.enrollmentRelease.countDown()
        assertTrue(engine.awaitIdle())
        val uploads = transport.calls.filter { it.path == "/v1/events" }
        assertEquals(1, uploads.size)
        assertNotEquals(oldId, JSONObject(uploads.single().body!!).getString("installationId"))
        assertTrue(transport.calls.any { it.method == "DELETE" && it.path == "/v1/installations/$oldId" })
    }

    @Test fun deletionIntentSurvivesProcessDeathBeforeTheBlockedWorkerCanCreateItsTombstone() {
        online = true
        transport.holdEnrollment = true
        engine.setEnabled(true)
        assertTrue(transport.enrollmentStarted.await(5, TimeUnit.SECONDS))
        val oldId = engine.installationId()
        engine.deleteData {}
        val preferences = app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE)
        assertTrue(preferences.getBoolean("deletionRequested", false))
        assertNull(preferences.getString("pendingDeletion", null))
        // shutdownNow discards the queued deletion task, as a killed process would.
        engine.shutdown()
        online = false
        engine = newEngine()
        assertTrue(engine.awaitIdle())
        assertEquals("disabled", engine.choice())
        assertNull(preferences.getString("installationId", null))
        assertFalse(preferences.getBoolean("deletionRequested", false))
        assertEquals(oldId, JSONArray(preferences.getString("pendingDeletion", null)).getJSONObject(0).getString("installationId"))
        assertFalse(File(app.noBackupFilesDir, "telemetry/events.json").exists())
        online = true
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertTrue(transport.calls.any { it.method == "DELETE" && it.path == "/v1/installations/$oldId" })
        assertNull(preferences.getString("pendingDeletion", null))
    }

    @Test fun withdrawalThenProcessDeathCannotReplayRevokedQueueAfterReconsent() {
        engine.setEnabled(true)
        engine.record("onboarding_step_completed", mapOf("step" to "HOTSPOT"))
        assertTrue(engine.awaitIdle())
        val originalId = engine.installationId()
        val original = queue()
        val oldIds = (0 until original.length()).map { original.getJSONObject(it).getString("id") }.toSet()
        online = true
        transport.holdEnrollment = true
        engine.pump()
        assertTrue(transport.enrollmentStarted.await(5, TimeUnit.SECONDS))
        engine.setEnabled(false)
        engine.shutdown()
        online = false
        engine = newEngine()
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(originalId, engine.installationId())
        val fresh = queue()
        assertTrue((0 until fresh.length()).none { fresh.getJSONObject(it).getString("id") in oldIds })
        assertTrue((0 until fresh.length()).none { fresh.getJSONObject(it).getString("name") == "onboarding_step_completed" })
    }

    @Test fun backgroundingFlushesSetupEventsDuringSuccessfulUploadCooldown() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.count { it.path == "/v1/events" })
        engine.record("permission_result", mapOf("permission" to "background", "result" to "denied"))
        engine.record("onboarding_step_blocked", mapOf("step" to "BACKGROUND"))
        engine.record("app_backgrounded", mapOf("source" to "onboarding"))
        assertTrue(engine.awaitIdle())
        val uploads = transport.calls.filter { it.path == "/v1/events" }
        assertEquals(2, uploads.size)
        val sent = JSONObject(uploads.last().body!!).getJSONArray("events")
        assertEquals(listOf("permission_result", "onboarding_step_blocked", "app_backgrounded"),
            (0 until sent.length()).map { sent.getJSONObject(it).getString("name") })
        assertEquals(0, queue().length())
    }

    @Test fun backgroundingDoesNotBypassFailedRequestBackoff() {
        online = true
        transport.status = 503
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        val initialCount = queue().length()
        engine.record("onboarding_step_blocked", mapOf("step" to "BACKGROUND"))
        engine.record("app_backgrounded", mapOf("source" to "onboarding"))
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        assertEquals(initialCount + 2, queue().length())
        elapsed += 60_001; time += 60_001
        transport.status = 200
        engine.record("app_backgrounded", mapOf("source" to "onboarding"))
        assertTrue(engine.awaitIdle())
        assertEquals(3, transport.calls.size)
        assertEquals(0, queue().length())
    }

    @Test fun unsupportedBrowserFailureFlushesEvenWhenThereIsNoActiveViewerToPumpHealth() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.count { it.path == "/v1/events" })
        engine.record("browser_capabilities", mapOf("webCodecs" to false, "secureContext" to true))
        engine.record("browser_error", mapOf("reason" to "webcodecs_unavailable"))
        assertTrue(engine.awaitIdle())
        val uploads = transport.calls.filter { it.path == "/v1/events" }
        assertEquals(2, uploads.size)
        val sent = JSONObject(uploads.last().body!!).getJSONArray("events")
        assertEquals(listOf("browser_capabilities", "browser_error"),
            (0 until sent.length()).map { sent.getJSONObject(it).getString("name") })
        assertFalse(sent.getJSONObject(0).getJSONObject("attributes").getBoolean("webCodecs"))
        assertEquals(0, queue().length())
    }

    @Test fun unchangedHealthSamplesStaySparseButBothActivityTransitionsAreRecorded() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        engine.record("reliability_sample", mapOf("iphoneActive" to false, "browserCount" to 0))
        assertTrue(engine.awaitIdle())
        elapsed += 5 * 60_000L + 1
        engine.record("reliability_sample", mapOf("iphoneActive" to false, "browserCount" to 0))
        assertTrue(engine.awaitIdle())
        elapsed += 25 * 60_000L
        engine.record("reliability_sample", mapOf("iphoneActive" to false, "browserCount" to 0))
        engine.record("reliability_sample", mapOf("iphoneActive" to true, "browserCount" to 1))
        assertTrue(engine.awaitIdle())
        elapsed += 5 * 60_000L + 1
        engine.record("reliability_sample", mapOf("iphoneActive" to true, "browserCount" to 1))
        engine.record("reliability_sample", mapOf("iphoneActive" to false, "browserCount" to 0))
        assertTrue(engine.awaitIdle())
        val all = queue()
        val health = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("name") == "reliability_sample" }
        assertEquals(2, health.count { it.getJSONObject("attributes").getBoolean("iphoneActive") })
        assertEquals(3, health.count { !it.getJSONObject("attributes").getBoolean("iphoneActive") })
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun connectionAndStopTransitionsUploadDuringSuccessfulCooldownWithoutAHealthTimer() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val transitions = listOf("receiver_start_requested", "receiver_started", "iphone_connected",
            "browser_connected", "browser_first_frame", "browser_disconnected", "iphone_disconnected", "session_ended")
        transitions.forEach { name ->
            val before = transport.calls.count { it.path == "/v1/events" }
            engine.record(name, if (name == "session_ended") mapOf("reason" to "user_stop",
                "receiverRunning" to false, "iphoneActive" to false, "browserCount" to 0, "videoRendered" to false) else emptyMap())
            assertTrue(engine.awaitIdle())
            assertEquals(name, before + 1, transport.calls.count { it.path == "/v1/events" })
            val sent = JSONObject(transport.calls.last().body!!).getJSONArray("events")
            assertEquals(name, sent.getJSONObject(sent.length() - 1).getString("name"))
        }
        assertEquals(0, queue().length())
    }

    @Test fun changedOperationalStateUploadsImmediatelyIncludingReturningToIdle() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val idle = mapOf("receiverRunning" to true, "iphoneActive" to false, "browserCount" to 0,
            "videoRendered" to false, "charging" to false, "batteryPercent" to 70)
        engine.record("reliability_sample", idle)
        assertTrue(engine.awaitIdle())
        val initialUploads = transport.calls.count { it.path == "/v1/events" }
        repeat(20) { engine.record("reliability_sample", idle + ("batteryPercent" to 69)) }
        assertTrue(engine.awaitIdle())
        assertEquals(initialUploads, transport.calls.count { it.path == "/v1/events" })
        engine.record("reliability_sample", idle + mapOf("iphoneActive" to true, "browserCount" to 1))
        engine.record("reliability_sample", idle)
        assertTrue(engine.awaitIdle())
        assertEquals(initialUploads + 2, transport.calls.count { it.path == "/v1/events" })
        val finalState = JSONObject(transport.calls.last().body!!).getJSONArray("events").getJSONObject(0).getJSONObject("attributes")
        assertFalse(finalState.getBoolean("iphoneActive"))
        assertEquals(0, finalState.getInt("browserCount"))
    }

    @Test fun operationalTransitionsAndStopDoNotBypassFailedNetworkBackoff() {
        online = true
        transport.status = 503
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val initialCalls = transport.calls.size
        engine.record("iphone_connected")
        engine.record("browser_first_frame")
        engine.record("reliability_sample", mapOf("iphoneActive" to true, "browserCount" to 1))
        engine.record("reliability_sample", mapOf("iphoneActive" to false, "browserCount" to 0))
        engine.record("session_ended", mapOf("reason" to "user_stop"))
        assertTrue(engine.awaitIdle())
        assertEquals(initialCalls, transport.calls.size)
        assertEquals("session_ended", queue().getJSONObject(queue().length() - 1).getString("name"))
    }

    @Test fun enrollmentServerDateCorrectsSkewedEventTimesWithoutChangingEventIds() {
        engine.setEnabled(true)
        engine.record("iphone_connected")
        assertTrue(engine.awaitIdle())
        val stored = queue()
        val ids = (0 until stored.length()).map { stored.getJSONObject(it).getString("id") }
        transport.serverDate = time - 20 * 60_000L
        online = true
        engine.pump()
        assertTrue(engine.awaitIdle())
        val sent = JSONObject(transport.calls.last().body!!).getJSONArray("events")
        assertEquals(ids, (0 until sent.length()).map { sent.getJSONObject(it).getString("id") })
        assertTrue((0 until sent.length()).all { sent.getJSONObject(it).getLong("timeMs") == transport.serverDate })
    }

    @Test fun malformedBatchDoesNotDiscardValidEventsAroundOneRejectedEvent() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val initialCount = queue().length()
        online = true
        transport.rejectLargeEventBatches = true
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(initialCount, queue().length())
        repeat(8) {
            elapsed += 6 * 60 * 60_000L
            time += 6 * 60 * 60_000L
            engine.pump()
            assertTrue(engine.awaitIdle())
        }
        assertTrue(queue().length() < initialCount)
        assertTrue(transport.calls.filter { it.path == "/v1/events" }.any {
            JSONObject(it.body!!).getJSONArray("events").length() == 1
        })
    }

    @Test fun rateLimitDeadlineSurvivesRestartAndCannotBeBypassedByImmediateEvents() {
        online = true
        transport.status = 429
        transport.retryDelay = 86_400_000L
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        val id = engine.installationId()
        engine.shutdown()
        elapsed = 10L // A reboot resets monotonic time; the persisted wall deadline still applies.
        time += 60 * 60_000L
        engine = newEngine()
        engine.record("browser_error", mapOf("reason" to "decoder_failed"))
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(1, transport.calls.size)
        assertEquals(id, engine.installationId())
        assertTrue((0 until queue().length()).any { queue().getJSONObject(it).getString("name") == "browser_error" })
        time += 23 * 60 * 60_000L + 1
        elapsed += 23 * 60 * 60_000L + 1
        transport.status = 200
        transport.retryDelay = null
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(3, transport.calls.size)
        assertEquals(0, queue().length())
    }

    @Test fun unavailableEventEndpointHonorsServerDelayAndRetainsStableQueuedIds() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        transport.status = 503
        transport.retryDelay = 3_600_000L
        engine.record("browser_error", mapOf("reason" to "video_config_rejected"))
        assertTrue(engine.awaitIdle())
        val ids = queue().toString()
        val calls = transport.calls.size
        elapsed += 60_001; time += 60_001
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(calls, transport.calls.size)
        assertEquals(ids, queue().toString())
        elapsed += 3_600_000; time += 3_600_000
        transport.status = 200
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertEquals(calls + 1, transport.calls.size)
        assertEquals(0, queue().length())
    }

    @Test fun corruptOrClockShiftedStoredDeadlinesCannotRenewAFullDayHoldAcrossRestarts() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val preferences = app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE)
        val invalid: List<Any> = listOf(Long.MAX_VALUE, Long.MIN_VALUE, time + BrowserTelemetryDeliveryPolicy.MAX_SERVER_DELAY_MS + 1,
            time - 1, "not_a_timestamp")
        for (value in invalid) {
            engine.shutdown()
            preferences.edit().apply {
                if (value is Long) putLong("serverRetryUntilMs", value) else putString("serverRetryUntilMs", value.toString())
            }.commit()
            val before = transport.calls.size
            online = true
            engine = newEngine()
            engine.record("browser_error", mapOf("reason" to "decoder_failed"))
            assertTrue(engine.awaitIdle())
            assertTrue("invalid deadline=$value", transport.calls.size > before)
            assertFalse(preferences.contains("serverRetryUntilMs"))
            engine.shutdown()
            engine = newEngine()
            engine.record("browser_error", mapOf("reason" to "decoder_failed"))
            assertTrue(engine.awaitIdle())
            assertTrue(transport.calls.size > before + 1)
        }
    }

    @Test fun repeatedAutomaticRetriesAreSparseWithoutSamplingOnboardingOrManualAttempts() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        repeat(40) { attempt ->
            engine.record("connection_attempt_started", mapOf("attempt" to attempt, "automatic" to true))
            engine.record("recovery_scheduled", mapOf("attempt" to attempt, "reason" to "transport_error"))
            engine.record("receiver_start_failed", mapOf("attempt" to attempt, "reason" to "startup_failed"))
            engine.record("onboarding_step_completed", mapOf("step" to "BACKGROUND"))
        }
        assertTrue(engine.awaitIdle())
        fun count(name: String) = (0 until queue().length()).count { queue().getJSONObject(it).getString("name") == name }
        assertEquals(1, count("connection_attempt_started"))
        assertEquals(1, count("recovery_scheduled"))
        assertEquals(1, count("receiver_start_failed"))
        assertEquals(40, count("onboarding_step_completed"))
        engine.record("recovery_scheduled", mapOf("reason" to "bluetooth_off"))
        engine.record("connection_attempt_started", mapOf("automatic" to false))
        elapsed += BrowserTelemetryDeliveryPolicy.REPEATED_ATTEMPT_INTERVAL_MS
        time += BrowserTelemetryDeliveryPolicy.REPEATED_ATTEMPT_INTERVAL_MS
        engine.record("connection_attempt_started", mapOf("attempt" to 100, "automatic" to true))
        assertTrue(engine.awaitIdle())
        assertEquals(2, count("recovery_scheduled"))
        assertEquals(3, count("connection_attempt_started"))
    }

    @Test fun performanceSummariesCannotAloneExceedTheDailyIngestionQuota() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        repeat(12) {
            engine.record("performance_summary", mapOf("fpsDecoded" to 30))
            assertTrue(engine.awaitIdle())
            elapsed += 60_000; time += 60_000
        }
        assertEquals(3, (0 until queue().length()).count { queue().getJSONObject(it).getString("name") == "performance_summary" })
    }

    @Test fun aNewReceiverSessionAlwaysKeepsItsFirstAutomaticAttempt() {
        engine.setEnabled(true)
        repeat(2) {
            engine.record("receiver_start_requested")
            engine.record("connection_attempt_started", mapOf("automatic" to true, "attempt" to 1))
            engine.record("receiver_start_failed", mapOf("reason" to "startup_failed"))
            engine.record("session_ended", mapOf("reason" to "receiver_closed"))
        }
        assertTrue(engine.awaitIdle())
        val all = queue()
        val attempts = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("name") == "connection_attempt_started" }
        assertEquals(2, attempts.size)
        assertNotEquals(attempts.first().getString("sessionId"), attempts.last().getString("sessionId"))
        assertEquals(2, (0 until all.length()).count { all.getJSONObject(it).getString("name") == "receiver_start_failed" })
    }

    @Test fun manualHelpFeedbackUploadsPromptlyWithoutInventingAnOperationalFailure() {
        online = true
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val before = transport.calls.count { it.path == "/v1/events" }
        engine.record("diagnostic_feedback", mapOf("step" to "browser", "result" to "needs_help"))
        assertTrue(engine.awaitIdle())
        assertEquals(before + 1, transport.calls.count { it.path == "/v1/events" })
        val sent = JSONObject(transport.calls.last().body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("diagnostic_feedback", sent.getString("name"))
        assertEquals("needs_help", sent.getJSONObject("attributes").getString("result"))
    }

    @Test fun withdrawalAndDeletionDuringServerCooldownNeverUploadRevokedEvents() {
        online = true
        transport.status = 429
        transport.retryDelay = 3_600_000L
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val oldId = engine.installationId()
        val old = queue()
        val oldIds = (0 until old.length()).map { old.getJSONObject(it).getString("id") }.toSet()
        engine.setEnabled(false)
        engine.record("iphone_connected")
        assertTrue(engine.awaitIdle())
        assertFalse(File(app.noBackupFilesDir, "telemetry/events.json").exists())
        engine.deleteData {}
        assertTrue(engine.awaitIdle())
        assertNull(engine.installationId())
        assertEquals(1, transport.calls.size)
        engine.shutdown()
        engine = newEngine()
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        assertNotEquals(oldId, engine.installationId())
        assertTrue((0 until queue().length()).none { queue().getJSONObject(it).getString("id") in oldIds })
        assertEquals(1, transport.calls.size)
        elapsed += 3_600_001; time += 3_600_001
        transport.status = 200
        transport.retryDelay = null
        engine.pump()
        assertTrue(engine.awaitIdle())
        assertTrue(transport.calls.any { it.method == "DELETE" && it.path == "/v1/installations/$oldId" })
        assertEquals(0, queue().length())
        assertNull(app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE).getString("pendingDeletion", null))
    }

    private data class Call(val method: String, val path: String, val token: String?, val body: String?)
    private class FakeTransport : BrowserTelemetryTransport {
        val calls = mutableListOf<Call>()
        var status = 200
        var cancelled = false
        var serverDate: Long? = null
        var retryDelay: Long? = null
        var holdEnrollment = false
        var rejectLargeEventBatches = false
        val enrollmentStarted = CountDownLatch(1)
        val enrollmentRelease = CountDownLatch(1)
        override fun request(method: String, path: String, token: String?, body: String?): Int {
            calls.add(Call(method, path, token, body))
            if (path == "/v1/installations" && holdEnrollment) {
                holdEnrollment = false
                enrollmentStarted.countDown()
                check(enrollmentRelease.await(5, TimeUnit.SECONDS))
            }
            if (path == "/v1/events" && rejectLargeEventBatches && JSONObject(body!!).getJSONArray("events").length() > 1) return 400
            return status
        }
        override fun cancel() { cancelled = true }
        override fun serverTimeMillis(): Long? = serverDate
        override fun retryAfterMillis(): Long? = retryDelay
    }
}
