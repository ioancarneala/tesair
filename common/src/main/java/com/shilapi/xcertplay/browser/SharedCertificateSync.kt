package com.shilapi.xcertplay.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads the operator's dedicated public-host identity; never issues certificates on a phone. */
class SharedCertificateSync internal constructor(
    context: Context,
    private val endpoint: String,
    private val token: String,
    private val onStateChanged: () -> Unit,
    private val transport: Transport,
    private val identity: Identity,
    private val now: () -> Long,
) : AutoCloseable {
    constructor(context: Context, endpoint: String, token: String, onStateChanged: () -> Unit) : this(
        context, endpoint, token, onStateChanged, HttpsTransport(), AndroidIdentity(context), System::currentTimeMillis)

    enum class State { DISABLED, CHECKING, CURRENT, RETRYING, ERROR }
    @Volatile var state: State = State.DISABLED
        private set
    val hostname: String? get() = identity.hostname()

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val validatedNetworks = mutableSetOf<Network>()
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivity?.getNetworkCapabilities(network)?.let { onCapabilitiesChanged(network, it) }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val valid = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val restored = synchronized(validatedNetworks) {
                if (valid) validatedNetworks.add(network) else { validatedNetworks.remove(network); false }
            }
            if (restored) retryAfterNetworkRestored()
        }
        override fun onLost(network: Network) { synchronized(validatedNetworks) { validatedNetworks.remove(network) } }
    }
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "browser-certificate-sync") }
    private val closed = AtomicBoolean()
    private var started = false
    private var scheduled: ScheduledFuture<*>? = null

    @Synchronized fun start() {
        if (closed.get() || started) return
        started = true
        if (endpoint.isBlank() || token.isBlank()) { publish(State.DISABLED); return }
        if (!validEndpoint(endpoint) || token.any { it == '\r' || it == '\n' }) { publish(State.ERROR); return }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest((endpoint + "\n" + token).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (preferences.getString("endpoint", "") != fingerprint) preferences.edit()
            .putString("endpoint", fingerprint).remove("next_attempt").remove("failures")
            .remove("server_retry_until").remove("last_attempt").commit()
        val remaining = (preferences.getLong("next_attempt", 0) - now()).coerceIn(0, MAX_RETRY_SECONDS * 1000)
        val delay = maxOf(if (identity.trusted()) remaining else remaining.coerceAtMost(60_000),
            (preferences.getLong("server_retry_until", 0) - now()).coerceIn(0, MAX_RETRY_SECONDS * 1000))
        if (delay > 0) publish(if (identity.trusted()) State.CURRENT else State.RETRYING)
        schedule(delay)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback); networkRegistered = connectivity != null }
    }

    @Synchronized private fun schedule(delay: Long) {
        if (closed.get()) return
        scheduled?.cancel(false)
        scheduled = worker.schedule({
            val next = syncOnce()
            if (!closed.get()) schedule(next)
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Separated from scheduling so failure/retry semantics can be tested without network or keys. */
    internal fun syncOnce(): Long {
        if (closed.get() || endpoint.isBlank() || token.isBlank()) return PERIOD_MILLIS
        if (!validEndpoint(endpoint)) { publish(State.ERROR); return PERIOD_MILLIS }
        publish(State.CHECKING)
        preferences.edit().putLong("last_attempt", now()).commit()
        var retryAfter = 0L
        var authenticationRejected = false
        try {
            val response = transport.fetch(endpoint, token)
            if (closed.get()) return PERIOD_MILLIS
            retryAfter = response.retryAfterSeconds.coerceIn(0, MAX_RETRY_SECONDS) * 1000
            preferences.edit().putLong("server_retry_until", if (retryAfter > 0) now() + retryAfter else 0).commit()
            authenticationRejected = response.code == 401 || response.code == 403
            if (response.code != 200) throw IllegalStateException("Certificate distribution unavailable")
            val bundle = JSONObject(response.body)
            val versionValue = bundle.opt("version")
            val version = if (versionValue is Int || versionValue is Long) (versionValue as Number).toLong() else -1
            if (bundle.opt("schemaVersion") != 1 || version !in 1..9_007_199_254_740_991L ||
                bundle.optString("hostname") != TlsIdentity.TRUSTED_HOST) throw IllegalArgumentException("Invalid certificate bundle")
            val chain = bundle.getString("certificateChain")
            val key = bundle.getString("privateKey")
            synchronized(this) {
                if (closed.get()) return PERIOD_MILLIS
                identity.install(version, bundle.getString("hostname"), chain, key)
            }
            if (!identity.trusted()) throw IllegalStateException("Trusted certificate unavailable")
            preferences.edit().putInt("failures", 0).remove("server_retry_until")
                .putLong("next_attempt", now() + PERIOD_MILLIS).commit()
            publish(State.CURRENT)
            return PERIOD_MILLIS
        } catch (_: Exception) {
            if (closed.get()) return PERIOD_MILLIS
            val failures = preferences.getInt("failures", 0).coerceIn(0, 8)
            val backoff = if (identity.trusted()) (60_000L shl failures).coerceAtMost(PERIOD_MILLIS) else 60_000L
            val delay = maxOf(backoff, retryAfter)
            preferences.edit().putInt("failures", (failures + 1).coerceAtMost(8))
                .putLong("next_attempt", now() + delay).commit()
            // Never replace or clear an existing certificate on a network or validation failure.
            publish(if (authenticationRejected) State.ERROR else State.RETRYING)
            return delay
        }
    }

    @Synchronized private fun retryAfterNetworkRestored() {
        if (closed.get() || !started || state == State.CHECKING || state == State.DISABLED) return
        if (identity.trusted() && state != State.RETRYING && state != State.ERROR) return
        val delay = networkRetryDelay(now(), preferences.getLong("last_attempt", 0),
            preferences.getLong("server_retry_until", 0))
        preferences.edit().putLong("next_attempt", now() + delay).commit()
        schedule(delay)
    }

    private fun publish(next: State) {
        if (closed.get() || state == next) return
        state = next
        runCatching(onStateChanged)
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduled?.cancel(true)
        if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        networkRegistered = false
        transport.close()
        worker.shutdownNow()
    }

    internal interface Identity {
        fun install(version: Long, hostname: String, certificateChain: String, privateKey: String)
        fun trusted(): Boolean
        fun hostname(): String?
    }

    private class AndroidIdentity(context: Context) : Identity {
        init { TlsIdentity.initializeSharedStorage(context.applicationContext) }
        override fun install(version: Long, hostname: String, certificateChain: String, privateKey: String) {
            TlsIdentity.installSharedBundle(version, hostname, certificateChain, privateKey)
        }
        override fun trusted() = TlsIdentity.hasTrustedCertificate()
        override fun hostname() = TlsIdentity.trustedHostname()
    }

    // Not data classes: default debug strings must not contain downloaded key material.
    internal class Response(val code: Int, val body: String, val retryAfterSeconds: Long = 0)
    internal interface Transport : AutoCloseable {
        fun fetch(endpoint: String, token: String): Response
        override fun close() = Unit
    }

    private class HttpsTransport : Transport {
        @Volatile private var connection: HttpURLConnection? = null
        private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "browser-certificate-deadline") }
        override fun fetch(endpoint: String, token: String): Response {
            check(validEndpoint(endpoint))
            val http = URL(endpoint).openConnection() as javax.net.ssl.HttpsURLConnection
            connection = http
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            val timeout = timer.schedule({ http.disconnect() }, 30, TimeUnit.SECONDS)
            try {
                http.instanceFollowRedirects = false
                http.connectTimeout = 10_000
                http.readTimeout = 10_000
                http.useCaches = false
                http.requestMethod = "GET"
                http.setRequestProperty("Authorization", "Bearer $token")
                http.setRequestProperty("Accept", "application/json")
                http.setRequestProperty("Cache-Control", "no-store")
                val code = http.responseCode
                val retry = http.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, MAX_RETRY_SECONDS) ?: 0
                if (code != 200) return Response(code, "", retry)
                if (http.contentLengthLong > MAX_RESPONSE_BYTES) throw java.io.IOException("Certificate bundle too large")
                val bytes = http.inputStream.use { readBounded(it, deadline, System::nanoTime) }
                return Response(code, bytes.toString(Charsets.UTF_8), retry)
            } finally {
                timeout.cancel(false)
                connection = null
                http.disconnect()
            }
        }
        override fun close() { connection?.disconnect(); timer.shutdownNow() }
    }

    companion object {
        internal const val PREFERENCES = "browser_certificate_sync_" + TlsIdentity.TRUSTED_HOST
        private const val DISTRIBUTION_HOST = "certificates.tesair.co"
        internal const val PERIOD_MILLIS = 6 * 60 * 60 * 1000L
        internal const val MAX_RETRY_SECONDS = 24 * 60 * 60L
        internal const val MAX_RESPONSE_BYTES = 256 * 1024
        internal fun networkRetryDelay(now: Long, lastAttempt: Long, serverRetryUntil: Long): Long = maxOf(
            (60_000L - (now - lastAttempt)).coerceIn(0, 60_000),
            (serverRetryUntil - now).coerceIn(0, MAX_RETRY_SECONDS * 1000))
        internal fun validEndpoint(endpoint: String): Boolean = runCatching {
            val url = URL(endpoint)
            url.protocol == "https" && url.host.equals(DISTRIBUTION_HOST, ignoreCase = true) &&
            (url.port == -1 || url.port == 443) && url.userInfo == null &&
            url.ref == null && url.query == null && url.path == "/v1/bundle" &&
                endpoint == endpoint.trim()
        }.getOrDefault(false)

        internal fun readBounded(input: java.io.InputStream, deadlineNanos: Long, nanoTime: () -> Long): ByteArray {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                if (Thread.currentThread().isInterrupted || nanoTime() >= deadlineNanos)
                    throw java.net.SocketTimeoutException("Certificate download deadline reached")
                val count = input.read(buffer)
                if (nanoTime() >= deadlineNanos) throw java.net.SocketTimeoutException("Certificate download deadline reached")
                if (count < 0) return bytes.toByteArray()
                if (bytes.size() + count > MAX_RESPONSE_BYTES) throw java.io.IOException("Certificate bundle too large")
                bytes.write(buffer, 0, count)
            }
        }
    }
}
