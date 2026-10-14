package com.shilapi.xcertplay

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Single telemetry worker owns this bounded private offline queue. */
internal class BrowserTelemetryStore(private val file: File) {
    private val events = mutableListOf<JSONObject>()
    private var loaded = false

    fun append(event: JSONObject, now: Long) {
        load()
        val bytes = event.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_EVENT_BYTES) return
        events.add(event)
        prune(now)
        persist()
    }

    fun batch(now: Long, limit: Int = MAX_BATCH_EVENTS): List<JSONObject> {
        load()
        if (prune(now)) persist()
        val batch = mutableListOf<JSONObject>()
        var bytes = 256
        for (event in events) {
            val size = event.toString().toByteArray(Charsets.UTF_8).size + 1
            if (batch.size >= limit.coerceIn(1, MAX_BATCH_EVENTS) || bytes + size > MAX_BATCH_BYTES) break
            batch.add(event); bytes += size
        }
        return batch
    }

    fun acknowledge(ids: Set<String>) {
        load()
        events.removeAll { it.optString("id") in ids }
        persist()
    }

    fun clear() {
        events.clear(); loaded = true
        AtomicFile(file).delete()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.length() > MAX_BYTES + MAX_EVENT_BYTES) { clear(); return }
            val stored = JSONArray(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            for (index in 0 until stored.length()) {
                val event = stored.optJSONObject(index) ?: continue
                if (event.optString("name") in BrowserTelemetrySchema.events && event.optLong("timeMs") > 0) events.add(event)
            }
        }
    }

    private fun prune(now: Long): Boolean {
        val before = events.size
        events.removeAll { it.optLong("timeMs") < now - MAX_AGE_MS || it.optLong("timeMs") > now + 24 * 60 * 60_000L }
        var bytes = events.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 } + 2
        while (bytes > MAX_BYTES && events.isNotEmpty()) bytes -= events.removeAt(0).toString().toByteArray(Charsets.UTF_8).size + 1
        return before != events.size
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(JSONArray(events).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (_: Exception) { if (output != null) atomic.failWrite(output) }
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        const val MAX_BATCH_EVENTS = 50
        const val MAX_BATCH_BYTES = 32 * 1024
        const val MAX_EVENT_BYTES = 4096
    }
}

internal object BrowserTelemetrySchema {
    val events = setOf("consent_granted", "app_opened", "app_backgrounded", "onboarding_step_viewed", "onboarding_step_completed",
        "onboarding_step_blocked", "permission_requested", "permission_result", "configuration_saved", "receiver_start_requested",
        "receiver_started", "receiver_start_failed", "connection_attempt_started", "iphone_connected", "iphone_disconnected",
        "video_received", "browser_page_loaded", "browser_capabilities", "browser_connected", "browser_disconnected",
        "browser_decoder_ready", "browser_first_frame", "browser_error", "performance_summary", "recovery_scheduled",
        "recovery_completed", "session_started", "session_ended", "update_check", "update_available", "update_download_started",
        "update_download_completed", "update_install_requested", "diagnostic_feedback", "reliability_sample")
    private val keys = setOf("appVersion", "appVersionCode", "androidApi", "manufacturer", "deviceModel", "browserFamily",
        "browserVersion", "browserEngine", "transport", "sessionOrigin", "step", "permission", "result", "reason", "errorCode",
        "automatic", "attempt", "durationMs", "retryDelayMs", "width", "height", "fpsRequested", "fpsReceived", "fpsDecoded",
        "droppedFrames", "decodeMeanMs", "decodeMaxMs", "queueDepth", "codec", "webCodecs", "secureContext", "configSupported",
        "sampleDecoded", "manualIntervention", "batteryPercent", "charging", "thermalStatus", "receiverRunning", "iphoneActive",
        "browserCount", "qualityPercent", "interfaceScale", "updateVersionCode", "onboardingRevision", "visibleDurationMs",
        "direction", "targetStep", "source", "browserSessionId", "viewportWidth", "viewportHeight", "declaredInfotainment",
        "displayPath", "acceptedPackets", "decodedFrames", "drawnFrames", "sampleDurationMs", "screenOn", "idle", "powerSave",
        "batteryExempt", "lowMemory", "uplink", "validated", "wifi", "cellular", "bluetooth", "videoRendered")
    private val booleans = setOf("automatic", "manualIntervention", "webCodecs", "secureContext", "configSupported", "sampleDecoded",
        "charging", "receiverRunning", "iphoneActive", "screenOn", "idle", "powerSave", "batteryExempt", "lowMemory", "uplink",
        "validated", "wifi", "cellular", "bluetooth", "videoRendered")
    private val strings = setOf("appVersion", "manufacturer", "deviceModel", "browserFamily", "browserVersion", "browserEngine",
        "transport", "sessionOrigin", "step", "permission", "result", "reason", "errorCode", "source", "direction", "targetStep",
        "displayPath", "declaredInfotainment", "codec", "browserSessionId")
    private val floating = setOf("fpsRequested", "fpsReceived", "fpsDecoded", "decodeMeanMs", "decodeMaxMs", "interfaceScale")
    private val maxima = mapOf("androidApi" to 100.0, "onboardingRevision" to 1000.0, "attempt" to 100_000.0,
        "durationMs" to BrowserTelemetryStore.MAX_AGE_MS.toDouble(), "visibleDurationMs" to 86_400_000.0, "retryDelayMs" to 86_400_000.0,
        "width" to 16_384.0, "height" to 16_384.0, "viewportWidth" to 16_384.0, "viewportHeight" to 16_384.0,
        "fpsRequested" to 240.0, "fpsReceived" to 240.0, "fpsDecoded" to 240.0, "sampleDurationMs" to 86_400_000.0,
        "decodeMeanMs" to 60_000.0, "decodeMaxMs" to 60_000.0, "queueDepth" to 100_000.0, "batteryPercent" to 100.0,
        "thermalStatus" to 10.0, "browserCount" to 100.0, "qualityPercent" to 200.0, "interfaceScale" to 10_000.0)
    private val technicalToken = Regex("[A-Za-z0-9_.:+/-]{1,80}")
    private val deviceLabel = Regex("[A-Za-z0-9_.()+ -]{1,80}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    fun attributes(input: Map<String, Any?>): Map<String, Any?> = input.entries.asSequence().filter { it.key in keys }
        .mapNotNull { (key, value) ->
            val clean = when (value) {
                is Boolean -> value.takeIf { key in booleans }
                is Number -> value.takeIf {
                    val number = it.toDouble()
                    key !in booleans && key !in strings && number.isFinite() && number >= 0 && number <= (maxima[key] ?: 1_000_000_000.0)
                        && (key in floating || number == kotlin.math.floor(number))
                }
                is String -> value.takeIf { key in strings && when (key) {
                    "manufacturer", "deviceModel" -> deviceLabel.matches(it)
                    "browserSessionId" -> uuid.matches(it)
                    else -> technicalToken.matches(it) && !it.contains("://")
                } }
                else -> null
            }
            clean?.let { key to it }
        }.take(48).toMap()
}
