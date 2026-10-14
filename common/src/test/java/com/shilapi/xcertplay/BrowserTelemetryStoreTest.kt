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
}
