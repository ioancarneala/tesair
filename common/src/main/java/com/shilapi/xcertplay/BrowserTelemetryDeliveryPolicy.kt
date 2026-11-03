package com.shilapi.xcertplay

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** Delivery policy only; these decisions do not add fields to the reporting schema. */
internal object BrowserTelemetryDeliveryPolicy {
    const val MAX_SERVER_DELAY_MS = 24 * 60 * 60_000L
    const val REPEATED_ATTEMPT_INTERVAL_MS = 30 * 60_000L
    private val repeatedAttempts = setOf("connection_attempt_started", "recovery_scheduled", "receiver_started", "receiver_start_failed")
    private val milestones = setOf("consent_granted", "onboarding_step_viewed", "onboarding_step_completed",
        "onboarding_step_blocked", "permission_requested", "permission_result", "configuration_saved",
        "update_available", "update_download_started", "update_download_completed", "update_install_requested")
    private val critical = setOf("browser_error", "receiver_start_failed")
    private val state = setOf("reliability_sample", "session_started", "session_ended", "iphone_connected",
        "iphone_disconnected", "browser_connected", "browser_disconnected", "browser_first_frame", "recovery_completed")

    fun repeatedAttemptKey(name: String, attributes: Map<String, Any?>): String? {
        if (name !in repeatedAttempts || attributes["automatic"] == false || attributes["manualIntervention"] == true) return null
        return listOf(name, attributes["reason"], attributes["errorCode"], attributes["source"], attributes["automatic"])
            .joinToString("|")
    }

    fun priority(event: JSONObject): Int = priority(event.optString("name"), event.optJSONObject("attributes")?.optString("result"))

    fun priority(name: String, result: String? = null): Int = when (name) {
        "diagnostic_feedback" -> if (result == "needs_help") 3 else 0
        in critical -> 3
        in milestones -> 2
        in state -> 1
        else -> 0
    }

    fun isMilestone(event: JSONObject): Boolean = event.optString("name") in milestones ||
        event.optString("name") == "diagnostic_feedback" && event.optJSONObject("attributes")?.optString("result") == "needs_help"

    fun retentionPriority(event: JSONObject): Int = if (isMilestone(event)) 4 else priority(event)

    fun retryAfterMillis(value: String?, receivedAtMs: Long, serverTimeMs: Long? = null): Long? {
        val text = value?.trim()?.takeIf { it.length in 1..128 } ?: return null
        if (text.all { it in '0'..'9' }) {
            val seconds = text.toLongOrNull() ?: return MAX_SERVER_DELAY_MS
            return seconds.coerceAtMost(MAX_SERVER_DELAY_MS / 1_000) * 1_000
        }
        val deadline = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        return (deadline - (serverTimeMs ?: receivedAtMs)).coerceIn(0, MAX_SERVER_DELAY_MS)
    }
}
