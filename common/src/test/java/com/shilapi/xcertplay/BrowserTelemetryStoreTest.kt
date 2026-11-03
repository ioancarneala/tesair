package com.shilapi.xcertplay

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserTelemetryStoreTest {
    private val now = 1_800_000_000_000L
    private fun event(time: Long = now, padding: String = "") = JSONObject()
        .put("id", UUID.randomUUID().toString()).put("name", "app_opened").put("timeMs", time)
        .put("uptimeMs", 123).put("sessionId", JSONObject.NULL).put("attributes", JSONObject().put("deviceModel", padding))

    @Test fun queueExpiresAfterSevenDaysAndBatchRespectsCountAndBytes() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-test/events.json")
        file.delete()
        val store = BrowserTelemetryStore(file)
        store.append(event(now - BrowserTelemetryStore.MAX_AGE_MS - 1), now)
        repeat(70) { store.append(event(), now) }
        assertEquals(50, store.batch(now).size)
        assertTrue(store.batch(now).all { it.getLong("timeMs") == now })
        val batch = store.batch(now)
        store.acknowledge(batch.map { it.getString("id") }.toSet())
        assertEquals(20, BrowserTelemetryStore(file).batch(now).size)
        store.clear()
        repeat(20) { store.append(event(padding = "x".repeat(3000)), now) }
        val largeBatch = store.batch(now)
        assertTrue(largeBatch.size < 20)
        assertTrue(org.json.JSONArray(largeBatch).toString().toByteArray().size + 256 <= BrowserTelemetryStore.MAX_BATCH_BYTES)
    }

    @Test fun storageDropsOldestEventsAtOneMiBWithoutLosingNewest() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-bound/events.json")
        file.delete()
        val store = BrowserTelemetryStore(file)
        repeat(350) { store.append(event(padding = "x".repeat(3000)), now) }
        val last = event(padding = "last")
        store.append(last, now)
        assertTrue(file.length() <= BrowserTelemetryStore.MAX_BYTES)
        val stored = org.json.JSONArray(file.readText())
        assertTrue(stored.length() < 351)
        assertEquals(last.getString("id"), stored.getJSONObject(stored.length() - 1).getString("id"))
    }

    @Test fun schemaRejectsUnknownKeysNestedObjectsOversizeStringsAndNonFiniteNumbers() {
        val values = BrowserTelemetrySchema.attributes(mapOf("password" to "secret", "fpsDecoded" to Double.NaN,
            "reason" to "x".repeat(121), "deviceModel" to JSONObject(), "charging" to true, "width" to 1920))
        assertEquals(mapOf("charging" to true, "width" to 1920), values)
    }

    @Test fun recentFailuresAndCurrentStateDoNotWaitBehindOfflineRoutineEvents() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-priority/events.json")
        file.delete()
        val store = BrowserTelemetryStore(file)
        repeat(80) { store.append(event().put("name", "performance_summary"), now) }
        val milestone = event().put("name", "onboarding_step_completed")
        val olderState = event().put("name", "reliability_sample")
        val latestState = event().put("name", "reliability_sample")
        val error = event().put("name", "browser_error")
        listOf(milestone, olderState, latestState, error).forEach { store.append(it, now) }
        val first = BrowserTelemetryStore(file).batch(now, 3)
        assertEquals(listOf(milestone, latestState, error).map { it.getString("id") }, first.map { it.getString("id") })
        store.acknowledge(first.map { it.getString("id") }.toSet())
        assertEquals(50, store.batch(now).size)
        assertTrue(store.batch(now).any { it.getString("id") == olderState.getString("id") })
    }

    @Test fun routineQueuePressurePreservesOnboardingAndFailureEvidence() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-retention/events.json")
        file.delete()
        val store = BrowserTelemetryStore(file)
        val milestone = event().put("name", "permission_result")
        val error = event().put("name", "browser_error")
        store.append(milestone, now); store.append(error, now)
        repeat(360) { store.append(event(padding = "x".repeat(3000)).put("name", "performance_summary"), now) }
        assertTrue(file.length() <= BrowserTelemetryStore.MAX_BYTES)
        val ids = store.batch(now).map { it.getString("id") }
        assertTrue(milestone.getString("id") in ids)
        assertTrue(error.getString("id") in ids)
        repeat(360) { store.append(event(padding = "x".repeat(3000)).put("name", "browser_error"), now) }
        val stored = org.json.JSONArray(file.readText())
        assertTrue((0 until stored.length()).any { stored.getJSONObject(it).getString("id") == milestone.getString("id") })
        store.clear()
        assertFalse(file.exists())
    }

    @Test fun aContinuingErrorBacklogCannotStarveOldestMilestonesOrCurrentRecovery() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-fairness/events.json")
        file.delete()
        val store = BrowserTelemetryStore(file)
        val milestones = (0 until 12).map { event().put("name", "onboarding_step_completed") }
        milestones.forEach { store.append(it, now) }
        val delivered = mutableSetOf<String>()
        repeat(3) { round ->
            repeat(90) { store.append(event().put("name", "browser_error"), now) }
            val current = event().put("name", "reliability_sample").put("attributes", JSONObject().put("iphoneActive", true))
            store.append(current, now)
            val batch = store.batch(now)
            assertEquals(50, batch.size)
            val ids = batch.map { it.getString("id") }.toSet()
            assertTrue(current.getString("id") in ids)
            assertTrue(milestones.subList(round * 4, round * 4 + 4).all { it.getString("id") in ids })
            assertTrue(org.json.JSONArray(batch).toString().toByteArray().size + 256 <= BrowserTelemetryStore.MAX_BATCH_BYTES)
            delivered += ids
            store.acknowledge(ids)
        }
        assertTrue(milestones.all { it.getString("id") in delivered })
    }

    @Test fun userRequestedHelpIsImportantButRoutineTechnicalFeedbackIsNot() {
        val manual = event().put("name", "diagnostic_feedback").put("attributes", JSONObject().put("result", "needs_help"))
        val routine = event().put("name", "diagnostic_feedback").put("attributes", JSONObject().put("source", "tls_sync").put("result", "ready"))
        assertEquals(3, BrowserTelemetryDeliveryPolicy.priority(manual))
        assertEquals(4, BrowserTelemetryDeliveryPolicy.retentionPriority(manual))
        assertTrue(BrowserTelemetryDeliveryPolicy.isMilestone(manual))
        assertEquals(0, BrowserTelemetryDeliveryPolicy.priority(routine))
    }
}
