package com.shilapi.xcertplay.browser

import android.content.Context
import com.shilapi.xcertplay.BrowserReliabilityDiagnostics
import com.shilapi.xcertplay.BrowserTelemetry
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Bounded public identity facts; refresh failures never masquerade as a browser video failure. */
internal class BrowserTlsDiagnostics(
    private val local: (String) -> Unit,
    private val enabled: () -> Boolean,
    private val telemetry: (String, Map<String, Any?>) -> Unit,
) {
    constructor(context: Context) : this(
        { BrowserReliabilityDiagnostics.record(context.applicationContext, it) },
        { BrowserTelemetry.enabled(context.applicationContext) },
        { name, attributes -> BrowserTelemetry.record(context.applicationContext, name, attributes) },
    )

    private var lastLocal: String? = null
    private var lastIdentity: String? = null
    private var lastSync: SharedCertificateSync.Diagnostics? = null
    private var reporting = false

    @Synchronized fun record(identity: TlsIdentity.Diagnostics, sync: SharedCertificateSync.Diagnostics) {
        val line = format(identity, sync)
        if (line != lastLocal) runCatching { local(line) }.onSuccess { lastLocal = line }
        if (!runCatching(enabled).getOrDefault(false)) { reporting = false; return }
        val identityState = format(identity, null)
        if (!reporting || identityState != lastIdentity) {
            val expiry = if (identity.notAfterMillis > 0) runCatching {
                Instant.ofEpochMilli(identity.notAfterMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            }.getOrDefault("unknown") else "unknown"
            runCatching {
                telemetry("diagnostic_feedback", mapOf("source" to "tls_identity",
                    "result" to if (identity.trusted) "trusted" else "untrusted",
                    "reason" to "${identity.validity.name.lowercase(Locale.ROOT)}_until_$expiry"))
            }.onSuccess { lastIdentity = identityState }
        }
        if (!reporting || sync != lastSync) {
            runCatching {
                telemetry("diagnostic_feedback", mapOf("source" to "tls_sync",
                    "result" to sync.state.name.lowercase(Locale.ROOT),
                    "reason" to sync.reason.name.lowercase(Locale.ROOT)))
            }.onSuccess { lastSync = sync }
        }
        reporting = true
    }

    companion object {
        fun format(identity: TlsIdentity.Diagnostics, sync: SharedCertificateSync.Diagnostics?): String =
            "tls trusted=${identity.trusted} hostnameMatch=${identity.hostnameMatch} " +
                "notBeforeMs=${identity.notBeforeMillis} notAfterMs=${identity.notAfterMillis} " +
                "chainLength=${identity.chainLength} validity=${identity.validity.name.lowercase(Locale.ROOT)} " +
                "sync=${sync?.state?.name?.lowercase(Locale.ROOT) ?: "unknown"} " +
                "syncReason=${sync?.reason?.name?.lowercase(Locale.ROOT) ?: "unknown"}"
    }
}
