package com.shilapi.xcertplay.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.BrowserTelemetry
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.host.BuildConfig
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Main CarPlay stream goes directly from DiPlay's media engine to the browser decoder. */
class BrowserStreamOutput(context: Context) : MediaSink, Closeable {
    private val app = context.applicationContext
    private val server = BridgeServer(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val recovery = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiPlayBrowserRecovery").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val tlsDiagnostics = BrowserTlsDiagnostics(app)
    private val certificates = SharedCertificateSync(context.applicationContext,
        BuildConfig.BROWSER_CERTIFICATE_ENDPOINT, BuildConfig.BROWSER_CERTIFICATE_DOWNLOAD_TOKEN) {
        recordTlsDiagnostics()
        main.post { if (!closed.get()) onStatusChanged() }
    }
    private val mediaLock = Any()
    private val touchLock = Any()
    private val clock = FrameClock()
    private var codec = VideoCodec.H264
    @Volatile private var configured = false
    private var mediaGeneration = 0L
    private var renderedGeneration = -1L
    private var receivedGeneration = -1L
    @Volatile private var telemetryAttempt = 0
    private var lastRecovery = 0L
    private var keyframeHandler: (() -> Unit)? = null
    private var diagnosticHandler: ((String) -> Unit)? = null
    private var gestureGeneration = 0L
    private var touching = false
    private var lastContact: AirPlayContact? = null
    private var pictureWidth = 0
    private var pictureHeight = 0
    private var canvasWidth = 0
    private var canvasHeight = 0
    @Volatile private var hevcRejected = false
    private var hevcCapabilityTried = false
    private var formatFailure: Runnable? = null

    /** HEVC is advertised only when the page decodes it and this session has not rejected it. */
    val allowHevc: Boolean get() = server.hevcSupported() && !hevcRejected

    @Volatile var running: Boolean = false
        private set
    @Volatile var lastRenderedAtElapsedMillis: Long = -1L
        private set
    @Volatile var status: String = "Browser server is off"
        private set
    var onViewport: (Int, Int) -> Unit = { _, _ -> }
    @Volatile var onTouch: (List<AirPlayContact>) -> Unit = {}
    var onStatusChanged: () -> Unit = {}
    var onFirstFrameRendered: () -> Unit = {}
    @Volatile var onVideoFailure: (Int, String) -> Unit = { _, _ -> }
    @Volatile var onVideoConfigured: (Int) -> Unit = {}
    @Volatile var onDiagnostic: (String) -> Unit = {}
    @Volatile var onClientConnection: (Boolean) -> Unit = {}
    @Volatile var onCongestion: () -> Unit = {}
    val clientCount: Int get() = server.clientCount()
    val certificateSyncState: SharedCertificateSync.State get() = certificates.state
    internal val certificateDiagnostics: SharedCertificateSync.Diagnostics get() = certificates.diagnostics
    val hasRenderedVideo: Boolean get() = synchronized(mediaLock) {
        !closed.get() && configured && renderedGeneration == mediaGeneration
    }
    val lastViewport: Pair<Int, Int>? get() = server.lastViewport()?.let { it[0] to it[1] }
    val urls: List<String> get() = if (!running) emptyList()
    else if (TlsIdentity.hasTrustedCertificate()) listOf("https://${TlsIdentity.TRUSTED_HOST}:${BridgeServer.PORT}")
    else runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { network ->
            if (!network.isUp || network.isLoopback) emptyList() else Collections.list(network.inetAddresses)
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { "https://${it.hostAddress}:${BridgeServer.PORT}" }
        }.distinct()
    }.getOrDefault(emptyList())

    init {
        server.setDiagnosticHandler(::record)
        server.setCongestionHandler { onCongestion() }
        server.setHevcRejectedHandler {
            synchronized(mediaLock) {
                hevcRejected = true
                onVideoFailure(telemetryAttempt, "browser_hevc_unsupported")
            }
        }
        server.setHevcCapabilityHandler {
            synchronized(mediaLock) {
                // The receiver may have started before the page reported HEVC support.
                if (server.hevcSupported() && !hevcRejected && !hevcCapabilityTried &&
                    codec == VideoCodec.H264 && configured) {
                    hevcCapabilityTried = true
                    onVideoFailure(telemetryAttempt, "hevc_capability")
                }
            }
        }
        server.setKeyframeHandler(::requestKeyframe)
        server.setDisplayHandler { width, height ->
            cancelTouch()
            main.post { if (!closed.get()) onViewport(width, height) }
        }
        server.setGestureHandler(::onControlMessage)
        server.setRenderedHandler(::frameRendered)
        server.setConnectionHandler {
            val connected = server.clientCount() > 0
            synchronized(mediaLock) { mediaGeneration++; renderedGeneration = -1 }
            if (!connected) cancelTouch()
            main.post {
                if (!closed.get()) {
                    onClientConnection(connected)
                    onStatusChanged()
                }
            }
        }
    }

    /** Correlate the first compressed frame with its actual receiver connection attempt. */
    fun setTelemetryAttempt(attempt: Int) = synchronized(mediaLock) {
        clearFormatFailureLocked()
        telemetryAttempt = attempt
    }

    fun setTelemetryVideoProfile(fps: Int) { server.setRequestedFps(fps) }

    fun telemetryConsentChanged() {
        if (!closed.get()) server.telemetryConsentChanged()
    }

    /** Called by certificate/health workers; no trust-store work runs on media callbacks. */
    internal fun recordTlsDiagnostics() {
        if (!closed.get()) runCatching {
            tlsDiagnostics.record(TlsIdentity.diagnostics(), certificates.diagnostics)
        }
    }

    /** A native iPhone layout change leaves the encoded canvas and decoder running. */
    fun setViewArea(area: AirPlayViewArea?, canvasWidth: Int, canvasHeight: Int) {
        if (closed.get()) return
        this.canvasWidth = canvasWidth
        this.canvasHeight = canvasHeight
        cancelTouch()
        server.setViewArea(area?.originX ?: 0, area?.originY ?: 0,
            area?.width ?: canvasWidth, area?.height ?: canvasHeight, canvasWidth, canvasHeight)
    }

    /** Call from the service worker: AndroidKeyStore and server startup may block. */
    @Synchronized fun start() {
        check(!closed.get()) { "Browser output has been closed" }
        if (running) return
        try {
            server.makeSecure(TlsIdentity.create(), null)
            server.start(5000, false)
            running = true
            record("server_started")
            certificates.start()
            recordTlsDiagnostics()
            updateStatus("Browser ready · connect an iPhone in TesAir")
        } catch (error: Exception) {
            record("server_start_failed")
            BrowserTelemetry.record(app, "browser_error", mapOf("reason" to "server_start_failed", "errorCode" to error.javaClass.simpleName))
            updateStatus("Browser server could not start: ${error.javaClass.simpleName}", true)
            throw error
        }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        if (type == MAIN_SCREEN) synchronized(mediaLock) { keyframeHandler = handler }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        if (type == MAIN_SCREEN) synchronized(mediaLock) {
            diagnosticHandler = handler; renderedGeneration = -1; mediaGeneration++
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (type != MAIN_SCREEN || closed.get()) return
        synchronized(mediaLock) {
            this.codec = codec
            if (codec != VideoCodec.H264 && !(codec == VideoCodec.H265 && allowHevc)) {
                awaitVideoFormatLocked("unsupported_iphone_codec", codec.name.lowercase())
            }
        }
        if (codec != VideoCodec.H264) cancelTouch()
    }

    override fun onVideoConfigRejected(type: Int) {
        if (type != MAIN_SCREEN || closed.get()) return
        synchronized(mediaLock) {
            // Never advertise a codec this relay cannot carry for the rest of the session.
            if (codec == VideoCodec.H265) hevcRejected = true
            awaitVideoFormatLocked("video_config_unknown", codec.name.lowercase())
        }
        cancelTouch()
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (type != MAIN_SCREEN || closed.get()) return
        var changed = false
        synchronized(mediaLock) {
            try {
                val width: Int
                val height: Int
                when (codec) {
                    VideoCodec.H264 -> {
                        val format = H264Format.fromAvcC(codecData)
                        width = format.width; height = format.height
                        val previousEpoch = server.videoGeneration()
                        server.videoConfig(format.annexB, width, height, VideoFormat.codec(format.annexB))
                        if (server.videoGeneration() != previousEpoch) {
                            changed = true
                            mediaGeneration++; renderedGeneration = -1
                        }
                    }
                    VideoCodec.H265 -> {
                        if (!allowHevc) {
                            changed = true
                            awaitVideoFormatLocked("unsupported_iphone_codec", "h265")
                            return@synchronized
                        }
                        // HEVC has no browser description: Annex B parameter sets are prepended
                        // to each key frame, and the canvas size comes from our own display config.
                        val sets = HevcFormat.parameterSets(codecData)
                        width = if (canvasWidth > 0) canvasWidth else 1920
                        height = if (canvasHeight > 0) canvasHeight else 1080
                        val previousEpoch = server.videoGeneration()
                        server.videoConfig(sets, width, height, HevcFormat.codec(codecData))
                        if (server.videoGeneration() != previousEpoch) {
                            changed = true
                            mediaGeneration++; renderedGeneration = -1
                        }
                    }
                    else -> return@synchronized
                }
                pictureWidth = width; pictureHeight = height
                record("video_format width=$width height=$height codec=${codec.name.lowercase()}")
                configured = true
                clearFormatFailureLocked()
                val attempt = telemetryAttempt
                main.post {
                    if (!closed.get() && configured && telemetryAttempt == attempt) onVideoConfigured(attempt)
                }
                updateStatus("Direct CarPlay video · ${width}×${height}")
            } catch (_: Exception) {
                changed = true
                if (codec == VideoCodec.H265) hevcRejected = true
                awaitVideoFormatLocked("video_config_rejected", codec.name.lowercase())
            }
        }
        if (changed) cancelTouch()
    }

    /** Startup can briefly send a different configuration before the negotiated H.264 stream. */
    private fun awaitVideoFormatLocked(reason: String, codecName: String) {
        configured = false
        if (formatFailure != null) return
        server.resetVideo(); mediaGeneration++; renderedGeneration = -1
        val attempt = telemetryAttempt
        record("video_format_pending reason=$reason codec=$codecName")
        BrowserTelemetry.record(app, "diagnostic_feedback", mapOf("attempt" to attempt,
            "source" to "video_format", "result" to "waiting", "reason" to reason, "codec" to codecName))
        updateStatus("Preparing CarPlay video")
        val task = object : Runnable {
            override fun run() = synchronized(mediaLock) {
                if (closed.get() || formatFailure !== this || configured || telemetryAttempt != attempt) return
                record("video_format_recovery reason=$reason codec=$codecName")
                BrowserTelemetry.record(app, "browser_error", mapOf("attempt" to attempt,
                    "reason" to reason, "codec" to codecName))
                updateStatus("Reconnecting to CarPlay")
                onVideoFailure(attempt, reason)
            }
        }
        formatFailure = task
        main.postDelayed(task, VIDEO_FORMAT_GRACE_MILLIS)
    }

    private fun clearFormatFailureLocked() {
        formatFailure?.let(main::removeCallbacks)
        formatFailure = null
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) =
        onVideoFrame(type, naluBytes, 0, System.nanoTime())

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type != MAIN_SCREEN || closed.get()) return
        synchronized(mediaLock) {
            if (!configured) return
            if (naluBytes.isEmpty() || naluBytes.size > MAX_FRAME_BYTES) { requestKeyframe(); return }
            if (receivedGeneration != mediaGeneration) {
                receivedGeneration = mediaGeneration
                BrowserTelemetry.record(app, "video_received", mapOf("attempt" to telemetryAttempt,
                    "width" to pictureWidth, "height" to pictureHeight, "codec" to codec.name.lowercase()))
            }
            val key = if (codec == VideoCodec.H265) HevcFormat.isKeyframe(naluBytes) else H264Format.isKeyframe(naluBytes)
            server.frame(naluBytes, key, clock.timestamp(senderNanos, arrivalNanos))
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (type != MAIN_SCREEN || closed.get()) return
        record(if (active) "iphone_video_active" else "iphone_video_inactive")
        synchronized(mediaLock) {
            if (!active) {
                clearFormatFailureLocked()
                configured = false; mediaGeneration++; renderedGeneration = -1
                keyframeHandler = null; diagnosticHandler = null
                server.resetVideo(); clock.reset()
                updateStatus("CarPlay disconnected · waiting for the iPhone")
            } else updateStatus("CarPlay connected · waiting for compressed video")
        }
        if (!active) cancelTouch()
    }

    private fun requestKeyframe() {
        val handler = synchronized(mediaLock) {
            val now = System.nanoTime()
            if (closed.get() || now - lastRecovery < 500_000_000L) return
            val current = keyframeHandler ?: return
            lastRecovery = now; current
        }
        runCatching { recovery.execute { if (!closed.get()) runCatching { handler() } } }
    }

    private fun frameRendered(videoEpoch: Long) {
        val generation: Long
        val diagnostic: ((String) -> Unit)?
        synchronized(mediaLock) {
            if (!configured || closed.get() || videoEpoch != server.videoGeneration()) return
            lastRenderedAtElapsedMillis = SystemClock.elapsedRealtime()
            if (renderedGeneration == mediaGeneration) return
            generation = mediaGeneration; renderedGeneration = generation; diagnostic = diagnosticHandler
        }
        main.post {
            val current = synchronized(mediaLock) {
                !closed.get() && configured && generation == mediaGeneration && videoEpoch == server.videoGeneration()
            }
            if (current) {
                record("first_frame_rendered")
                diagnostic?.invoke("first frame rendered")
                onFirstFrameRendered()
                updateStatus("Browser connected · ${pictureWidth}×${pictureHeight} · direct H.264")
            }
        }
    }

    internal fun onControlMessage(message: JSONObject) {
        if (message.optString("type") == "touch") liveTouch(message) else gesture(message)
    }

    private fun gesture(message: JSONObject) {
        val points = message.optJSONArray("points") ?: return
        if (points.length() !in 1..64) return
        val contacts = ArrayList<AirPlayContact>(points.length())
        for (index in 0 until points.length()) {
            val point = points.optJSONArray(index) ?: return
            val x = point.optDouble(0, Double.NaN); val y = point.optDouble(1, Double.NaN)
            if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return
            contacts.add(AirPlayContact(0, x, y, true))
        }
        val duration = message.optLong("duration", 50).coerceIn(40, 1500)
        val generation = synchronized(touchLock) {
            if (closed.get() || !configured) return
            cancelTouchLocked()
            gestureGeneration
        }
        main.post {
            synchronized(touchLock) {
                if (closed.get() || !configured || generation != gestureGeneration) return@post
                touching = true; sendContactLocked(contacts.first())
                for (index in 1 until contacts.size) {
                    main.postDelayed({
                        synchronized(touchLock) {
                            if (!closed.get() && touching && generation == gestureGeneration) sendContactLocked(contacts[index])
                        }
                    }, duration * index / (contacts.size - 1))
                }
                main.postDelayed({
                    synchronized(touchLock) {
                        if (generation == gestureGeneration) cancelTouchLocked()
                    }
                }, duration)
            }
        }
    }

    private fun liveTouch(message: JSONObject) {
        val action = message.optString("action")
        if (action == "up" || action == "cancel") { cancelTouch(); return }
        if (action != "down" && action != "move") return
        val x = message.optDouble("x", Double.NaN); val y = message.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return
        synchronized(touchLock) {
            if (closed.get() || !configured) return
            if (action == "down") { cancelTouchLocked(); touching = true }
            if (touching) sendContactLocked(AirPlayContact(0, x, y, true))
        }
    }

    // The callback queues HID work. Keep input ordered without involving the Android UI thread.
    private fun sendContactLocked(contact: AirPlayContact) {
        lastContact = contact
        onTouch(listOf(contact))
    }

    private fun cancelTouch() = synchronized(touchLock) { cancelTouchLocked() }

    private fun cancelTouchLocked() {
        gestureGeneration++
        if (touching) {
            touching = false
            // CarPlay uses the release position to complete a tap; an empty list sends (0, 0).
            lastContact?.let { onTouch(listOf(it.copy(down = false))) }
            lastContact = null
        }
    }

    private fun updateStatus(message: String, error: Boolean = false) {
        status = message; server.browserStatus(message, error)
        main.post { if (!closed.get()) onStatusChanged() }
    }

    private fun record(event: String) { runCatching { onDiagnostic(event) } }

    /** The service calls this on its worker, ordered after start(). */
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelTouch()
        running = false; certificates.close(); recovery.shutdownNow(); server.stop()
        synchronized(mediaLock) {
            clearFormatFailureLocked()
            configured = false; diagnosticHandler = null; keyframeHandler = null; mediaGeneration++
        }
        main.post { onStatusChanged() }
        status = "Browser output stopped"
    }

    companion object {
        private const val MAIN_SCREEN = 110
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        internal const val VIDEO_FORMAT_GRACE_MILLIS = 3_000L
    }
}
