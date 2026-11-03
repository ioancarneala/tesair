package com.shilapi.xcertplay

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BrowserTelemetryDeliveryPolicyTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()

    @Test fun retryAfterAcceptsSecondsAndBoundsUnreasonablyLongDelays() {
        assertEquals(3_600_000L, BrowserTelemetryDeliveryPolicy.retryAfterMillis("3600", now))
        assertEquals(0L, BrowserTelemetryDeliveryPolicy.retryAfterMillis("0", now))
        assertEquals(BrowserTelemetryDeliveryPolicy.MAX_SERVER_DELAY_MS,
            BrowserTelemetryDeliveryPolicy.retryAfterMillis("999999999999999999999999999", now))
        assertEquals(BrowserTelemetryDeliveryPolicy.MAX_SERVER_DELAY_MS,
            BrowserTelemetryDeliveryPolicy.retryAfterMillis("172800", now))
        assertNull(BrowserTelemetryDeliveryPolicy.retryAfterMillis("-1", now))
        assertNull(BrowserTelemetryDeliveryPolicy.retryAfterMillis("later", now))
    }

    @Test fun retryAfterDateUsesTheResponseClockWhenThePhoneClockIsWrong() {
        assertEquals(3_600_000L, BrowserTelemetryDeliveryPolicy.retryAfterMillis("Wed, 28 Oct 2026 13:00:00 GMT", now))
        assertEquals(3_600_000L, BrowserTelemetryDeliveryPolicy.retryAfterMillis("Wed, 28 Oct 2026 13:00:00 GMT",
            now + 10 * 60 * 60_000L, now))
        assertEquals(0L, BrowserTelemetryDeliveryPolicy.retryAfterMillis("Wed, 28 Oct 2026 11:00:00 GMT", now))
        assertNull(BrowserTelemetryDeliveryPolicy.retryAfterMillis(null, now))
    }

    @Test fun repetitiveSignaturesIgnoreAttemptNumbersButKeepDifferentReasonsAndManualActions() {
        val first = BrowserTelemetryDeliveryPolicy.repeatedAttemptKey("recovery_scheduled", mapOf("attempt" to 1, "reason" to "transport_error"))
        assertEquals(first, BrowserTelemetryDeliveryPolicy.repeatedAttemptKey("recovery_scheduled",
            mapOf("attempt" to 99, "reason" to "transport_error")))
        assertNotEquals(first, BrowserTelemetryDeliveryPolicy.repeatedAttemptKey("recovery_scheduled", mapOf("reason" to "bluetooth_off")))
        assertNull(BrowserTelemetryDeliveryPolicy.repeatedAttemptKey("connection_attempt_started", mapOf("automatic" to false)))
        assertNull(BrowserTelemetryDeliveryPolicy.repeatedAttemptKey("onboarding_step_completed", emptyMap()))
    }
}
