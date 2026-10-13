package com.shilapi.xcertplay.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val server = BridgeServer(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val recovery = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiPlayBrowserRecovery").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val certificates = SharedCertificateSync(context.applicationContext,
        BuildConfig.BROWSER_CERTIFICATE_ENDPOINT, BuildConfig.BROWSER_CERTIFICATE_DOWNLOAD_TOKEN) {
        main.post { if (!closed.get()) onStatusChanged() }
    }
    private val mediaLock = Any()
    private val touchLock = Any()
    private val clock = FrameClock()
    private var codec = VideoCodec.H264
    @Volatile private var configured = false
    private var mediaGeneration = 0L
    private var renderedGeneration = -1L
    private var lastRecovery = 0L
    private var keyframeHandler: (() -> Unit)? = null
    private var diagnosticHandler: ((String) -> Unit)? = null
    private var gestureGeneration = 0L
    private var touching = false
    private var lastContact: AirPlayContact? = null
    private var pictureWidth = 0
    private var pictureHeight = 0

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
    @Volatile var onDiagnostic: (String) -> Unit = {}
    @Volatile var onClientConnection: (Boolean) -> Unit = {}
    val clientCount: Int get() = server.clientCount()
    val certificateSyncState: SharedCertificateSync.State get() = certificates.state
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

    /** A native iPhone layout change leaves the encoded canvas and decoder running. */
    fun setViewArea(area: AirPlayViewArea?, canvasWidth: Int, canvasHeight: Int) {
        if (closed.get()) return
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
            updateStatus("Browser ready · connect an iPhone in TesAir")
        } catch (error: Exception) {
            record("server_start_failed")
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
            if (codec != VideoCodec.H264) {
                configured = false; server.resetVideo(); mediaGeneration++
                updateStatus("Unsupported video format. Reconnect your iPhone in TesAir.", true)
            }
        }
        if (codec != VideoCodec.H264) cancelTouch()
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (type != MAIN_SCREEN || closed.get()) return
        var changed = false
        synchronized(mediaLock) {
            if (codec != VideoCodec.H264) return
            try {
                val format = H264Format.fromAvcC(codecData)
                pictureWidth = format.width; pictureHeight = format.height
                val previousEpoch = server.videoGeneration()
                server.videoConfig(format.annexB, format.width, format.height)
                if (server.videoGeneration() != previousEpoch) {
                    changed = true
                    mediaGeneration++; renderedGeneration = -1
                    record("video_format width=${format.width} height=${format.height}")
                }
                configured = true
                updateStatus("Direct CarPlay video · ${format.width}×${format.height}")
            } catch (_: Exception) {
                changed = true
                configured = false; server.resetVideo(); mediaGeneration++
                record("video_config_rejected")
                updateStatus("CarPlay supplied an unsupported H.264 configuration.", true)
            }
        }
        if (changed) cancelTouch()
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) =
        onVideoFrame(type, naluBytes, 0, System.nanoTime())

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type != MAIN_SCREEN || closed.get()) return
        synchronized(mediaLock) {
            if (!configured) return
            if (naluBytes.isEmpty() || naluBytes.size > MAX_FRAME_BYTES) { requestKeyframe(); return }
            server.frame(naluBytes, H264Format.isKeyframe(naluBytes), clock.timestamp(senderNanos, arrivalNanos))
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (type != MAIN_SCREEN || closed.get()) return
        record(if (active) "iphone_video_active" else "iphone_video_inactive")
        synchronized(mediaLock) {
            if (!active) {
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
        synchronized(mediaLock) { configured = false; diagnosticHandler = null; keyframeHandler = null; mediaGeneration++ }
        main.post { onStatusChanged() }
        status = "Browser output stopped"
    }

    companion object {
        private const val MAIN_SCREEN = 110
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
