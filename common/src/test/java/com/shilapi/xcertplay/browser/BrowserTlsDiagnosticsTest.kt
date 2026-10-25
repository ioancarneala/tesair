package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.BrowserTelemetrySchema
import com.shilapi.xcertplay.DiagnosticRedactor
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BrowserTlsDiagnosticsTest {
    private val expiry = Instant.parse("2027-01-09T00:00:00Z").toEpochMilli()
    private val current = SharedCertificateSync.Diagnostics(SharedCertificateSync.State.CURRENT,
        SharedCertificateSync.Reason.CURRENT)

    @Test fun publicFactsHaveAnExactBoundedLocalFormatWithoutSensitiveFields() {
        val identity = identity()
        assertEquals("tls trusted=true hostnameMatch=true notBeforeMs=1000 notAfterMs=$expiry " +
            "chainLength=4 validity=current sync=current syncReason=current", BrowserTlsDiagnostics.format(identity, current))
        val line = BrowserTlsDiagnostics.format(identity, null)
        assertTrue(line.endsWith("sync=unknown syncReason=unknown"))
        assertFalse(line.contains("certificate")); assertFalse(line.contains("PRIVATE KEY"))
        assertFalse(line.contains("://")); assertFalse(line.contains("token"))
    }

    @Test fun expiryIsUtcAndEventsUseOnlyTheExistingTelemetrySchema() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = BrowserTlsDiagnostics({}, { true }, { name, attributes -> events += name to attributes })
        diagnostics.record(identity(), current)
        assertEquals(2, events.size)
        assertEquals("current_until_2027-01-09", events.first().second["reason"])
        assertEquals("trusted", events.first().second["result"])
        for ((name, attributes) in events) {
            assertTrue(name in BrowserTelemetrySchema.events)
            assertEquals(attributes, BrowserTelemetrySchema.attributes(attributes))
        }
    }

    @Test fun missingIdentityIsExplicitAndDoesNotInventAnExpiry() {
        val events = mutableListOf<Map<String, Any?>>()
        val diagnostics = BrowserTlsDiagnostics({}, { true }, { _, attributes -> events += attributes })
        val missing = TlsIdentity.Diagnostics(false, false, false, 0, 0, 0, TlsIdentity.Validity.MISSING)
        diagnostics.record(missing, current)
        assertEquals("missing_until_unknown", events.first()["reason"])
        assertEquals("untrusted", events.first()["result"])
        assertTrue(BrowserTlsDiagnostics.format(missing, current).contains("chainLength=0 validity=missing"))
    }

    @Test fun everyFixedSyncOutcomeSurvivesTheExistingDiagnosticRedactor() {
        for (reason in SharedCertificateSync.Reason.values()) {
            val sync = SharedCertificateSync.Diagnostics(SharedCertificateSync.State.RETRYING, reason)
            val line = BrowserTlsDiagnostics.format(identity(), sync)
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }

    @Test fun identityAndSyncTransitionsAreDeduplicatedSeparatelyAndResumeAfterOptIn() {
        var optedIn = true
        val local = mutableListOf<String>()
        val events = mutableListOf<Map<String, Any?>>()
        val diagnostics = BrowserTlsDiagnostics({ local += it }, { optedIn }, { _, attributes -> events += attributes })
        val identity = identity()
        diagnostics.record(identity, current)
        diagnostics.record(identity, current)
        assertEquals(1, local.size); assertEquals(2, events.size)
        val retrying = SharedCertificateSync.Diagnostics(SharedCertificateSync.State.RETRYING,
            SharedCertificateSync.Reason.NETWORK_TIMEOUT)
        diagnostics.record(identity, retrying)
        assertEquals(2, local.size); assertEquals(3, events.size)
        assertEquals("tls_sync", events.last()["source"])
        assertEquals("network_timeout", events.last()["reason"])
        diagnostics.record(identity(false), retrying)
        assertEquals(3, local.size); assertEquals(4, events.size)
        assertEquals("tls_identity", events.last()["source"])
        optedIn = false
        diagnostics.record(identity(false), retrying)
        assertEquals(4, events.size)
        optedIn = true
        diagnostics.record(identity(false), retrying)
        diagnostics.record(identity(false), retrying)
        assertEquals(6, events.size); assertEquals(3, local.size)
        assertTrue(events.all { it["source"] == "tls_identity" || it["source"] == "tls_sync" })
    }

    @Test fun diagnosticSinkFailuresCannotEscapeIntoTheReceiverAndCanBeRetried() {
        var localAttempts = 0
        var telemetryAttempts = 0
        val diagnostics = BrowserTlsDiagnostics({
            if (++localAttempts == 1) throw IllegalStateException("Synthetic local storage failure")
        }, { true }, { _, _ ->
            if (++telemetryAttempts <= 2) throw IllegalStateException("Synthetic telemetry failure")
        })
        diagnostics.record(identity(), current)
        diagnostics.record(identity(), current)
        diagnostics.record(identity(), current)
        assertEquals(2, localAttempts)
        assertEquals(4, telemetryAttempts)
        BrowserTlsDiagnostics({}, { throw IllegalStateException("Synthetic consent read failure") },
            { _, _ -> fail("Unknown consent must not report") }).record(identity(), current)
    }

    private fun identity(trusted: Boolean = true) = TlsIdentity.Diagnostics(true, trusted, true, 1000,
        expiry, 4, if (trusted) TlsIdentity.Validity.CURRENT else TlsIdentity.Validity.UNTRUSTED)
}
