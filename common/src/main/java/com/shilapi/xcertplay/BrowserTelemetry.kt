package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Optional technical telemetry. Media callbacks only enqueue bounded, allowlisted metadata. */
object BrowserTelemetry {
    private val engines = mutableMapOf<String, BrowserTelemetryEngine>()

    @Synchronized private fun engine(context: Context): BrowserTelemetryEngine {
        val app = context.applicationContext
        return engines.getOrPut(app.filesDir.absolutePath + app.packageName) { BrowserTelemetryEngine(app) }
    }

    @JvmStatic @JvmOverloads fun record(context: Context, name: String, attributes: Map<String, Any?> = emptyMap()) {
        runCatching { engine(context).record(name, attributes) }
    }

    @JvmStatic fun enabled(context: Context): Boolean = engine(context).enabled.get()
    @JvmStatic fun choice(context: Context): String = engine(context).choice()
    @JvmStatic fun installationId(context: Context): String? = engine(context).installationId()
    @JvmStatic fun setEnabled(context: Context, enabled: Boolean) = engine(context).setEnabled(enabled)
    @JvmStatic fun pump(context: Context) = engine(context).pump()

    /** False means deletion is retained privately for retry when the device is online. */
    @JvmStatic fun deleteData(context: Context, callback: (Boolean) -> Unit) = engine(context).deleteData(callback)

    internal fun replaceEngineForTest(context: Context, replacement: BrowserTelemetryEngine) {
        synchronized(this) {
            val key = context.applicationContext.filesDir.absolutePath + context.packageName
            engines.put(key, replacement)?.shutdown()
        }
    }
}

internal class BrowserTelemetryEngine(
    private val app: Context,
    private val transport: BrowserTelemetryTransport = BrowserTelemetryHttpTransport(),
    private val online: () -> Boolean = {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val uptime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val preferences = app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE)
    val enabled = AtomicBoolean(preferences.getString("choice", "unset") == "enabled")
    private val pendingRecords = AtomicInteger()
    private val consentGeneration = AtomicInteger()
    private val pumpPending = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "TesAirTelemetry").apply { isDaemon = true } }
    private val store = BrowserTelemetryStore(File(app.noBackupFilesDir, "telemetry/events.json"))
    private var sessionId: String? = null
    private var nextAttemptElapsedMs = 0L
    private var failures = 0
    private var registered = false
    private var opened = false
    private val samples = mutableMapOf<String, Long>()
    private val baseMetadata by lazy(::metadata)
    private var batchLimit = BrowserTelemetryStore.MAX_BATCH_EVENTS
    private var serverTimeAnchor: Long? = null
    private var serverUptimeAnchor = 0L

    init {
        if (preferences.getBoolean("deletionRequested", false)) worker.execute { prepareRequestedDeletion() }
    }

    fun choice(): String = preferences.getString("choice", "unset") ?: "unset"
    fun installationId(): String? = if (enabled.get()) preferences.getString("installationId", null) else null

    fun record(name: String, attributes: Map<String, Any?> = emptyMap()) {
        if (!enabled.get() || name !in BrowserTelemetrySchema.events) return
        if (pendingRecords.incrementAndGet() > MAX_PENDING_RECORDS) { pendingRecords.decrementAndGet(); return }
        // Copy a small scalar map here; callers can reuse their maps after this method returns.
        val clean = BrowserTelemetrySchema.attributes(attributes)
        val time = now()
        val elapsed = uptime()
        val generation = consentGeneration.get()
        worker.execute {
            try {
                if (!enabled.get() || generation != consentGeneration.get()) return@execute
                if (name == "performance_summary" || name == "reliability_sample") {
                    val active = clean["iphoneActive"] == true || (clean["browserCount"] as? Number)?.toInt()?.let { it > 0 } == true
                    val minimum = if (name == "reliability_sample") {
                        if (active) 5 * 60_000L else 30 * 60_000L
                    } else 60_000L
                    val key = if (name == "reliability_sample") "$name:${if (active) "active" else "idle"}" else name
                    val previous = samples[key]
                    if (previous != null && elapsed - previous in 0 until minimum) return@execute
                    samples[key] = elapsed
                }
                ensureIdentity()
                if (name == "receiver_start_requested" && sessionId == null) {
                    sessionId = UUID.randomUUID().toString()
                    append("session_started", time, elapsed, mapOf("reason" to "receiver_requested"))
                }
                if (name == "app_opened") {
                    append(name, time, elapsed, metadata() + clean)
                    if (!opened) { opened = true; configurationSnapshot(time, elapsed) }
                } else append(name, time, elapsed, clean)
                if (name == "session_ended") sessionId = null
                if (name in FINAL_UPLOAD_EVENTS) upload(force = true)
            } catch (_: Exception) {
                // A reporting or storage failure must never escape into the receiver process.
            } finally { pendingRecords.decrementAndGet() }
        }
    }

    fun setEnabled(value: Boolean) {
        val previous = enabled.getAndSet(value)
        if (previous != value) consentGeneration.incrementAndGet()
        val generation = consentGeneration.get()
        preferences.edit().putString("choice", if (value) "enabled" else "disabled").commit()
        runCatching { DiPlaySessionService.currentBrowserOutput()?.telemetryConsentChanged() }
        if (!value) transport.cancel()
        worker.execute {
            if (value && enabled.get() && generation == consentGeneration.get()) {
                if (!previous) {
                    // Withdrawal may have been followed by process death before its worker cleared disk.
                    store.clear(); sessionId = null; opened = false; samples.clear()
                }
                ensureIdentity()
                if (!previous) {
                    val time = now(); val elapsed = uptime()
                    append("consent_granted", time, elapsed, metadata())
                    append("app_opened", time, elapsed, metadata())
                    configurationSnapshot(time, elapsed)
                    if (sessionId == null && BrowserSessionRecovery.requested(app)) {
                        sessionId = UUID.randomUUID().toString()
                        append("session_started", time, elapsed, mapOf("reason" to "consent_in_requested_receiver"))
                    }
                    opened = true
                }
                upload()
            } else if (!value) {
                store.clear()
                sessionId = null
                opened = false
                samples.clear()
            }
        }
    }

    fun deleteData(callback: (Boolean) -> Unit) {
        enabled.set(false)
        consentGeneration.incrementAndGet()
        // Record intent before cancellation: Android can kill this process while an upload exits.
        preferences.edit().putString("choice", "disabled").putBoolean("deletionRequested", true).commit()
        runCatching { DiPlaySessionService.currentBrowserOutput()?.telemetryConsentChanged() }
        transport.cancel()
        worker.execute {
            prepareRequestedDeletion()
            val completed = runCatching { if (online()) deletePending() else tombstones().length() == 0 }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { callback(completed) }
        }
    }

    fun pump() {
        if (!enabled.get() && preferences.getString("pendingDeletion", null) == null &&
            !preferences.getBoolean("deletionRequested", false)) return
        if (!pumpPending.compareAndSet(false, true)) return
        worker.execute {
            try { upload() } finally { pumpPending.set(false) }
        }
    }

    private fun ensureIdentity() {
        if (!enabled.get()) return
        if (preferences.getString("installationId", null) == null) {
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            preferences.edit().putString("installationId", UUID.randomUUID().toString()).putString("token", token).commit()
            registered = false
        }
    }

    private fun append(name: String, time: Long, elapsed: Long, attributes: Map<String, Any?>) {
        if (!enabled.get()) return
        val event = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
            .put("timeMs", time).put("uptimeMs", elapsed).put("sessionId", sessionId ?: JSONObject.NULL)
            .put("attributes", JSONObject(BrowserTelemetrySchema.attributes(baseMetadata + attributes)))
        store.append(event, time)
    }

    private fun metadata(): Map<String, Any?> {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return mapOf("appVersion" to (info?.versionName ?: "unknown"), "appVersionCode" to (info?.longVersionCode ?: 0L),
            "androidApi" to Build.VERSION.SDK_INT, "manufacturer" to Build.MANUFACTURER, "deviceModel" to Build.MODEL,
            "onboardingRevision" to 1)
    }

    private fun configurationSnapshot(time: Long, elapsed: Long) {
        append("configuration_saved", time, elapsed, mapOf("qualityPercent" to AirPlayPersistence.loadBrowserResolutionPercent(app),
            "fpsRequested" to AirPlayPersistence.loadFps(app), "interfaceScale" to AirPlayPersistence.loadWidthPhysicalMm(app),
            "codec" to "h264", "transport" to "wireless", "source" to "snapshot",
            "declaredInfotainment" to app.getSharedPreferences("browser_telemetry_context", Context.MODE_PRIVATE)
                .getString("declaredInfotainment", null)))
    }

    private fun upload(force: Boolean = false) {
        prepareRequestedDeletion()
        if ((uptime() < nextAttemptElapsedMs && !(force && failures == 0)) || !runCatching(online).getOrDefault(false)) return
        val generation = consentGeneration.get()
        try {
            if (!deletePending()) { backoff(); return }
            if (!enabled.get()) return
            ensureIdentity()
            val id = preferences.getString("installationId", null) ?: return
            val token = preferences.getString("token", null) ?: return
            if (!registered) {
                val status = transport.request("POST", "/v1/installations", null,
                    JSONObject().put("schemaVersion", 1).put("installationId", id).put("token", token).toString())
                captureServerTime()
                if (!enabled.get() || generation != consentGeneration.get()) return
                if (status !in 200..299) { backoff(); return }
                registered = true
            }
            val events = store.batch(now(), batchLimit)
            if (events.isEmpty() || !enabled.get() || generation != consentGeneration.get()) return
            val corrected = events.map { original ->
                JSONObject(original.toString()).apply { put("timeMs", original.getLong("timeMs") + clockCorrection()) }
            }
            val body = JSONObject().put("schemaVersion", 1).put("installationId", id).put("events", JSONArray(corrected)).toString()
            val status = transport.request("POST", "/v1/events", token, body)
            captureServerTime()
            if (!enabled.get() || generation != consentGeneration.get()) return
            when {
                status in 200..299 -> {
                    store.acknowledge(events.map { it.getString("id") }.toSet())
                    failures = 0; nextAttemptElapsedMs = uptime() + MIN_UPLOAD_INTERVAL_MS
                    batchLimit = BrowserTelemetryStore.MAX_BATCH_EVENTS
                }
                status == 401 || status == 404 -> { registered = false; backoff() }
                status == 400 || status == 413 || status == 422 -> {
                    // Isolate malformed events rather than discarding their valid neighbours.
                    if (events.size == 1) {
                        store.acknowledge(setOf(events.single().getString("id")))
                        batchLimit = BrowserTelemetryStore.MAX_BATCH_EVENTS
                    } else batchLimit = (events.size / 2).coerceAtLeast(1)
                    backoff()
                }
                else -> backoff()
            }
        } catch (_: Exception) { backoff() }
    }

    private fun captureServerTime() {
        transport.serverTimeMillis()?.let { serverTimeAnchor = it; serverUptimeAnchor = uptime() }
    }

    private fun clockCorrection(): Long {
        val anchor = serverTimeAnchor ?: return 0L
        val difference = anchor + (uptime() - serverUptimeAnchor).coerceAtLeast(0) - now()
        return if (kotlin.math.abs(difference) > 2 * 60_000L) difference else 0L
    }

    private fun tombstones(): JSONArray = runCatching { JSONArray(preferences.getString("pendingDeletion", "[]")) }.getOrElse { JSONArray() }

    private fun prepareRequestedDeletion() {
        if (!preferences.getBoolean("deletionRequested", false)) return
        val pending = tombstones()
        val id = preferences.getString("installationId", null)
        val token = preferences.getString("token", null)
        if (id != null && token != null && (0 until pending.length()).none {
                pending.getJSONObject(it).optString("installationId") == id
            }) pending.put(JSONObject().put("installationId", id).put("token", token))
        // Keep the deletion credential durable in the same write that removes its active identity.
        preferences.edit().apply {
            if (pending.length() > 0) putString("pendingDeletion", pending.toString()) else remove("pendingDeletion")
        }.remove("installationId").remove("token").remove("deletionRequested").commit()
        registered = false; sessionId = null; opened = false; samples.clear()
        nextAttemptElapsedMs = 0L; failures = 0; batchLimit = BrowserTelemetryStore.MAX_BATCH_EVENTS
        store.clear()
    }

    private fun deletePending(): Boolean {
        val remaining = JSONArray()
        val pending = tombstones()
        for (index in 0 until pending.length()) {
            val entry = pending.getJSONObject(index)
            val status = runCatching { transport.request("DELETE", "/v1/installations/${entry.getString("installationId")}",
                entry.getString("token"), null) }.getOrDefault(0)
            if (status !in 200..299 && status != 404) remaining.put(entry)
        }
        preferences.edit().apply {
            if (remaining.length() == 0) remove("pendingDeletion") else putString("pendingDeletion", remaining.toString())
        }.commit()
        return remaining.length() == 0
    }

    private fun backoff() {
        failures = (failures + 1).coerceAtMost(9)
        nextAttemptElapsedMs = uptime() + (60_000L shl (failures - 1)).coerceAtMost(6 * 60 * 60_000L)
    }

    internal fun awaitIdle(): Boolean = runCatching { worker.submit {}.get(5, TimeUnit.SECONDS); true }.getOrDefault(false)
    internal fun shutdown() { transport.cancel(); worker.shutdownNow() }

    companion object {
        private const val MAX_PENDING_RECORDS = 256
        private const val MIN_UPLOAD_INTERVAL_MS = 5 * 60_000L
        private val FINAL_UPLOAD_EVENTS = setOf("app_backgrounded", "browser_error", "receiver_start_failed")
    }
}

internal interface BrowserTelemetryTransport {
    fun request(method: String, path: String, token: String?, body: String?): Int
    fun cancel() {}
    fun serverTimeMillis(): Long? = null
}

internal class BrowserTelemetryHttpTransport : BrowserTelemetryTransport {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var serverTime: Long? = null
    override fun request(method: String, path: String, token: String?, body: String?): Int {
        val connection = URL("https://telemetry.tesair.co$path").openConnection() as HttpURLConnection
        active = connection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            serverTime = connection.getHeaderFieldDate("Date", 0).takeIf { it > 0 }
            return status
        } finally { connection.disconnect(); if (active === connection) active = null }
    }
    override fun cancel() { active?.disconnect() }
    override fun serverTimeMillis(): Long? = serverTime
}
