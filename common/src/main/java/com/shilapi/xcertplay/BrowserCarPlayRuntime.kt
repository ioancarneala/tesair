package com.shilapi.xcertplay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The foreground service owns the receiver; opening or destroying a screen never transfers it. */
internal class BrowserCarPlayRuntime(context: Context, private val output: BrowserStreamOutput) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DiPlayReceiverLifecycle") }
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "DiPlayReceiverCommands") }
    private val retryBudget = WirelessStartupRetryBudget()
    private val diagnosticLog = SessionLogFile(File(this.context.filesDir, "logs/diplay.log"))
    private var requested = false
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var preparing = false
    private var retiring = false
    private var retry: Runnable? = null
    private var resize: Runnable? = null
    private var viewport = BrowserCarPlayViewport.load(this.context)
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var layout: BrowserCarPlayLayout? = null
    private var activeSession: AirPlaySession? = null
    private var observerRegistered = false
    private val closeWaiters = mutableListOf<() -> Unit>()
    private var closeCompleted = false
    private var manualAttempt = false
    private var recoveryStartedAt = -1L
    var onStatusChanged: (() -> Unit)? = null
    var status: CarPlayStatus? = null
        private set
    var setupNeedsAttention = false
        private set
    val manualRecoveryPending: Boolean
        get() = requested && !closed && !preparing && !retiring && activeSession == null &&
            (setupNeedsAttention || retry != null)

    private val nightMode = CarPlayNightModeController(
        AndroidAmbientLight(this.context), MainThreadNightModeScheduler(), systemNight(),
        onNightChanged = { sendNightMode(it) },
    )
    private val configurationObserver = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            nightMode.systemChanged(newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        }
        override fun onLowMemory() = Unit
    }
    private val viewportListener: (Int, Int) -> Unit = { width, height ->
        if (!closed) {
            viewport = BrowserCarPlayViewport.save(this.context, width, height)
            resize?.let(main::removeCallbacks)
            resize = Runnable {
                resize = null
                applyViewArea()
            }.also { main.postDelayed(it, 500) }
        }
    }

    /** Metadata only: preserve the receiver's existing start/reconnect API and behaviour. */
    fun setTelemetryStartOrigin(automatic: Boolean) = onMain {
        if (!requested) manualAttempt = !automatic
    }

    fun start() = onMain {
        if (closed) return@onMain
        if (!requested) {
            requested = true
            diagnosticLog.beginSession("Receiver service session epochMs=${System.currentTimeMillis()}")
            record("receiver_requested")
            BrowserTelemetry.record(context, "receiver_start_requested", mapOf("automatic" to !manualAttempt))
            output.onViewport = viewportListener
            output.lastViewport?.let { viewport = BrowserCarPlayViewport.save(context, it.first, it.second) }
            context.registerComponentCallbacks(configurationObserver)
            observerRegistered = true
        }
        configureNightMode()
        maybeStart()
    }

    fun refreshAppearance() = onMain { if (!closed && requested) configureNightMode() }

    /** Called only by an explicit Connect/Apply action; saved settings do not interrupt a drive. */
    fun reconnect() = onMain {
        if (closed) return@onMain
        retryBudget.manualRetry()
        manualAttempt = true
        BrowserTelemetry.record(context, "receiver_start_requested", mapOf("manualIntervention" to true, "source" to "connect_action"))
        setupNeedsAttention = false
        configureNightMode()
        cancelRetry()
        restart()
    }

    fun close(reason: String = "receiver_closed", completion: () -> Unit = {}) = onMain {
        if (closeCompleted) { completion(); return@onMain }
        closeWaiters += completion
        if (closed) return@onMain
        closed = true
        requested = false
        ++generation
        cancelRetry()
        resize?.let(main::removeCallbacks)
        resize = null
        if (output.onViewport === viewportListener) output.onViewport = { _, _ -> }
        if (observerRegistered) context.unregisterComponentCallbacks(configurationObserver)
        observerRegistered = false
        nightMode.pause()
        commands.shutdown()
        record("receiver_close")
        BrowserTelemetry.sessionEnded(context, reason)
        retire { finishClose() }
    }

    private fun finishClose() {
        // A pending preparation owns no sockets. The worker fence also waits for its disk reads.
        worker.execute {
            AsyncDiagnosticLog.awaitIdle(1_000)
            diagnosticLog.close()
            main.post {
                worker.shutdown()
                closeCompleted = true
                closeWaiters.toList().also { closeWaiters.clear() }.forEach { it() }
            }
        }
    }

    private fun maybeStart() {
        if (!requested || closed || preparing || retiring || controller != null || retry != null || setupNeedsAttention) return
        val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
        if (WirelessPermissions.required(Build.VERSION.SDK_INT, mode).any {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }) {
            setStatus(CarPlayStatus.Failed("Open connection setup to allow the required permissions."))
            scheduleRetry(null, "permissions_pending")
            return
        }
        if (runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull() == false) {
            setStatus(CarPlayStatus.WaitingForPairedIphone)
            scheduleRetry(null, "bluetooth_off")
            return
        }
        preparing = true
        val attempt = ++generation
        val size = viewport
        val automatic = !manualAttempt
        manualAttempt = false
        record("receiver_preparing generation=$attempt width=${size.first} height=${size.second}")
        worker.execute {
            val result = runCatching {
                val identity = AirPlayPersistence.loadIdentity(context)
                val vehicleStatus = BrowserEvTestInput.open(context)
                val config = BrowserCarPlayConfiguration.runtime(context, identity, vehicleStatus)
                if (vehicleStatus != null) record("ev_battery_test_enabled simulated=true")
                DiPlayBootstrap.ensure(context, config.mfiTarget)
                val layout = BrowserCarPlayLayout.create(size.first, size.second,
                    AirPlayPersistence.loadBrowserResolutionPercent(context))
                val video = BrowserCarPlayConfiguration.video(context, identity, layout)
                val pairings = AirPlayPersistence.loadPairings(context) { id, key ->
                    AirPlayPersistence.savePairing(context, id, key)
                }
                Prepared(identity, config, video, layout, pairings, vehicleStatus)
            }
            main.post {
                if (closed || attempt != generation) return@post
                preparing = false
                result.fold(onSuccess = { begin(it, size, attempt, automatic) }, onFailure = {
                    record("receiver_prepare_failed generation=$attempt class=${it.javaClass.simpleName}")
                    BrowserTelemetry.record(context, "receiver_start_failed", mapOf("attempt" to attempt,
                        "reason" to "prepare_failed", "errorCode" to it.javaClass.simpleName))
                    setupNeedsAttention = true
                    setStatus(CarPlayStatus.Failed("Receiver setup needs attention. Open connection setup and reconnect."))
                })
            }
        }
    }

    private fun begin(prepared: Prepared, size: Pair<Int, Int>, attempt: Int, automatic: Boolean) {
        try {
            val renderer = AndroidMediaSink(context = context, surface = null,
                videoWidth = prepared.video.main.widthPixels, videoHeight = prepared.video.main.heightPixels,
                audioFocusEnabled = false, onAudioDiagnostic = { AsyncDiagnosticLog.append(diagnosticLog, it) })
            sink = renderer
            layout = prepared.layout
            val media = CarPlayMediaEngine(
                sink = BrowserCarPlayMediaSink(renderer, output) { !closed && generation == attempt },
                microphoneEnabled = false,
            )
            val next = CarPlayController(context, prepared.runtime, prepared.video, prepared.identity,
                prepared.pairings, listener(attempt), media,
                reportStatus = { incoming -> onMain {
                    if (!closed && generation == attempt) {
                        setStatus(incoming)
                        if (incoming is CarPlayStatus.Failed) {
                            BrowserTelemetry.record(context, "receiver_start_failed", mapOf("attempt" to attempt,
                                "reason" to "startup_failure", "errorCode" to (incoming.startupFailure?.name ?: "unknown")))
                            if (incoming.wifiResetRequired || incoming.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) {
                                setupNeedsAttention = true
                                onStatusChanged?.invoke()
                            } else scheduleRetry(incoming.startupFailure, "startup_failure",
                                incoming.message.contains("AirPlay iAP tunnel", ignoreCase = true))
                        }
                    }
                } },
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(context) },
                savePairRecord = { AirPlayPersistence.saveLockdownRecord(context, it) },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(context) },
                vehicleStatusProvider = prepared.vehicleStatus,
            )
            controller = next
            val display = CarPlaySessionDisplay(prepared.layout.canvasWidth, prepared.layout.canvasHeight,
                Surface.ROTATION_0, true, true, size.first, size.second,
                browserLayout = prepared.layout, microphoneRequired = false)
            CarPlayBackgroundSession.store(next, renderer, size.first, size.second, this, display) { completion ->
                onMain {
                    BrowserSessionRecovery.markStopped(context)
                    context.stopService(Intent(context, DiPlaySessionService::class.java))
                    close(completion = completion)
                }
            }
            output.setViewArea(prepared.layout.currentArea, prepared.layout.canvasWidth, prepared.layout.canvasHeight)
            output.setTelemetryAttempt(attempt)
            output.setTelemetryVideoProfile(prepared.video.main.fps)
            BrowserTelemetry.record(context, "connection_attempt_started", mapOf("attempt" to attempt,
                "automatic" to automatic, "width" to size.first, "height" to size.second))
            record("receiver_started generation=$attempt canvas=${display.width}x${display.height} fps=${prepared.video.main.fps}")
            BrowserTelemetry.record(context, "receiver_started", mapOf("attempt" to attempt, "width" to display.width,
                "height" to display.height, "fpsRequested" to prepared.video.main.fps, "codec" to "h264"))
            next.start()
        } catch (error: RuntimeException) {
            record("receiver_start_failed generation=$attempt class=${error.javaClass.simpleName}")
            BrowserTelemetry.record(context, "receiver_start_failed", mapOf("attempt" to attempt,
                "reason" to "start_failed", "errorCode" to error.javaClass.simpleName))
            setStatus(CarPlayStatus.Failed("Could not start the receiver. Retrying automatically."))
            scheduleRetry(null, "start_failed")
        }
    }

    private fun listener(attempt: Int) = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) = onMain {
            if (closed || generation != attempt) return@onMain
            activeSession = session
            CarPlayBackgroundSession.active = true
            record("iphone_session_active generation=$attempt")
            BrowserTelemetry.record(context, "iphone_connected", mapOf("attempt" to attempt, "transport" to "wireless"))
            BrowserUpdates.check(context)
            sendNightMode(nightMode.night)
            onStatusChanged?.invoke()
        }

        override fun onVideoFrameRendered(session: AirPlaySession) = onMain {
            if (closed || generation != attempt || activeSession !== session) return@onMain
            cancelRetry()
            applyViewArea(force = true)
            if (recoveryStartedAt >= 0) {
                BrowserTelemetry.record(context, "recovery_completed", mapOf("attempt" to attempt,
                    "durationMs" to (SystemClock.elapsedRealtime() - recoveryStartedAt).coerceAtLeast(0)))
                recoveryStartedAt = -1L
            }
            if (retryBudget.firstFrame(session, SystemClock.elapsedRealtime())) {
                main.postDelayed({
                    if (!closed && generation == attempt && activeSession === session &&
                        retryBudget.resetIfStable(session, SystemClock.elapsedRealtime())) {
                        record("receiver_retry_budget_reset generation=$attempt")
                    }
                }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
            }
        }

        override fun onSessionEnded(session: AirPlaySession) = onMain {
            if (closed || generation != attempt || (activeSession != null && activeSession !== session)) return@onMain
            activeSession = null
            CarPlayBackgroundSession.active = false
            retryBudget.disconnected()
            record("iphone_session_ended generation=$attempt")
            BrowserTelemetry.record(context, "iphone_disconnected", mapOf("attempt" to attempt, "reason" to "session_ended"))
            onStatusChanged?.invoke()
            scheduleRetry(null, "session_ended")
        }

        override fun onTransportError(message: String) = onMain {
            if (closed || generation != attempt) return@onMain
            retryBudget.disconnected()
            record("iphone_transport_error generation=$attempt")
            BrowserTelemetry.record(context, "iphone_disconnected", mapOf("attempt" to attempt,
                "reason" to "transport_error", "errorCode" to "transport_error"))
            // The message can contain a peer's address; the persistent log redacts it separately.
            AsyncDiagnosticLog.append(diagnosticLog, message)
            scheduleRetry(null, "transport_error", message.contains("AirPlay iAP tunnel", ignoreCase = true))
        }

        override fun onDebugLog(message: String) {
            // Keep old-controller teardown diagnostics too; metadata is redacted before writing.
            AsyncDiagnosticLog.append(diagnosticLog, message)
        }
    }

    private fun scheduleRetry(failure: WirelessStartupFailure?, reason: String, tunnel: Boolean = false) {
        if (closed || !requested || setupNeedsAttention || retry != null) return
        val delay = if (tunnel) 15_000L else retryBudget.nextDelayMillis(continuous = true) ?: return
        val attempt = generation
        record("iphone_retry_scheduled generation=$attempt delayMs=$delay reason=$reason startupFailure=${failure ?: "none"}")
        if (recoveryStartedAt < 0) recoveryStartedAt = SystemClock.elapsedRealtime()
        BrowserTelemetry.record(context, "recovery_scheduled", mapOf("attempt" to attempt, "retryDelayMs" to delay,
            "reason" to reason, "errorCode" to (failure?.name ?: "none"), "automatic" to true))
        retry = Runnable {
            retry = null
            if (!closed && requested && generation == attempt) restart()
        }.also { main.postDelayed(it, delay) }
    }

    private fun cancelRetry() { retry?.let(main::removeCallbacks); retry = null }

    private fun restart() {
        if (closed || !requested) return
        ++generation
        preparing = false
        retryBudget.disconnected()
        retire { if (!closed) maybeStart() }
    }

    private fun retire(completion: () -> Unit) {
        val oldController = controller
        val oldSink = sink
        controller = null
        sink = null
        layout = null
        activeSession = null
        if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear(oldController, keepOwner = !closed)
        if (oldController != null || oldSink != null) output.onScreenStreamActive(110, false)
        retiring = true
        worker.execute {
            oldController?.close()
            val complete = oldController?.awaitClosed(4_000) ?: true
            oldSink?.close()
            oldSink?.awaitVideoReleased(2_000)
            record("receiver_teardown complete=$complete")
            main.post {
                retiring = false
                completion()
            }
        }
    }

    private fun applyViewArea(force: Boolean = false) {
        val current = layout ?: return
        val target = current.indexFor(viewport.first, viewport.second)
        if ((!force && target == current.currentIndex) || controller?.showViewArea(target) == true) {
            current.use(target)
            output.setViewArea(current.currentArea, current.canvasWidth, current.canvasHeight)
        }
    }

    private fun configureNightMode() {
        nightMode.configure(AirPlayPersistence.loadCarPlayNightMode(context), systemNight(),
            AirPlayPersistence.loadAmbientLightThreshold(context), AirPlayPersistence.loadAmbientDelaySeconds(context),
            AirPlayPersistence.loadCarPlayNightSchedule(context))
        nightMode.resume(systemNight())
    }

    private fun systemNight() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun sendNightMode(night: Boolean) {
        val session = activeSession ?: return
        if (closed) return
        commands.execute {
            if (!closed) runCatching { session.setNightMode(night) }
                .onFailure { record("receiver_night_mode_failed class=${it.javaClass.simpleName}") }
        }
    }

    private fun setStatus(next: CarPlayStatus) { status = next; onStatusChanged?.invoke() }
    private fun record(event: String) = BrowserReliabilityDiagnostics.record(context, event)
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private data class Prepared(val identity: AirPlayIdentity, val runtime: CarPlayRuntimeConfig,
        val video: AirPlayConfig, val layout: BrowserCarPlayLayout, val pairings: PairingStore,
        val vehicleStatus: VehicleStatusProvider?)
}

/** Browser negotiation deliberately excludes Android video decoding, microphone, GPS and car APIs. */
internal object BrowserCarPlayConfiguration {
    fun runtime(context: Context, identity: AirPlayIdentity,
        testVehicleStatus: VehicleStatusProvider? = null): CarPlayRuntimeConfig {
        val target = AirPlayPersistence.loadMfiTarget(context)
        val id = DiPlayBootstrap.deviceId(identity)
        return CarPlayRuntimeConfig(
            mfiTarget = target,
            ch341Devices = if (target == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x5512)) else emptyList(),
            ch341MfiResetGpio = null,
            linuxI2cPath = if (target == MfiTarget.I2C) AirPlayPersistence.loadMfiI2cPath(context).trim() else null,
            remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context).trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context).takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(name = "DiPlay",
                modelIdentifier = model(context),
                manufacturer = if (testVehicleStatus != null) "DiPlay EV test" else manufacturer(context),
                serialNumber = "DIPLAY-" + id.replace(":", ""), firmwareVersion = "0.1.0", hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3, locationInformationEnabled = false,
                vehicleStatusEnabled = testVehicleStatus?.snapshot() != null,
                vehicleSpeedEnabled = false),
            label = "DiPlay", hostName = "diplay-" + id.replace(":", "").lowercase(),
            hostMac = id.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(context),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context),
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
            manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(context),
            manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
            manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context),
            manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context),
            manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
            existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(context),
            existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(context),
            locationReportingEnabled = false,
        )
    }

    fun video(context: Context, identity: AirPlayIdentity, layout: BrowserCarPlayLayout): AirPlayConfig {
        val physicalWidth = AirPlayPersistence.loadWidthPhysicalMm(context)
        return AirPlayConfig(deviceName = "DiPlay", deviceId = DiPlayBootstrap.deviceId(identity),
            btMac = DiPlayBluetooth.localAddress(context) ?: DiPlayBootstrap.deviceId(identity),
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(layout.canvasWidth, layout.canvasHeight, physicalWidth,
                (physicalWidth.toDouble() * layout.canvasHeight / layout.canvasWidth).roundToInt().coerceAtLeast(1),
                fps = AirPlayPersistence.loadFps(context), viewAreas = layout.areas, initialViewArea = layout.initialIndex),
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(context), hevc = false,
            disableAudioOutput = true, microphone = false,
            manufacturer = manufacturer(context), model = model(context), oemLabel = AirPlayPersistence.loadOemLabel(context),
            icons = listOf(icon(context)), mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(context))
    }

    private fun manufacturer(context: Context) = AirPlayPersistence.loadManufacturer(context).trim()
        .ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
    private fun model(context: Context) = AirPlayPersistence.loadModel(context).trim()
        .ifBlank { AirPlayPersistence.DEFAULT_MODEL }
    private fun icon(context: Context): AirPlayIcon {
        fun decode(bytes: ByteArray): AirPlayIcon? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return if (bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight)
                AirPlayIcon(bounds.outWidth, bounds.outHeight, bytes) else null
        }
        runCatching { AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes() }.getOrNull()
            ?.let { decode(it) }?.let { return it }
        return context.resources.openRawResource(R.raw.ic_car_home).use { decode(it.readBytes()) }
            ?: error("Packaged CarPlay icon is invalid")
    }
}
