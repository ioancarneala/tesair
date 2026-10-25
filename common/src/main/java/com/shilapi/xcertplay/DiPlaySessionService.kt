package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors
import java.util.UUID

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private val browserWorker = Executors.newSingleThreadExecutor()
    private val browserHandler = Handler(Looper.getMainLooper())
    private var browserStarted = false
    private var browserRequested = false
    private var browserStartToken: Any? = null
    private var browserRetry: Runnable? = null
    private var browserRetryAttempt = 0
    private var stopRequested = false
    private var receiverRetiring = false
    private var destroyed = false
    private var receiverAutomaticStart = true
    private var reliabilityMonitor: BrowserReliabilityMonitor? = null
    private var screenInteractive = true
    private var idleSince = -1L
    private var standbyParked = false
    private var standbyParking = false
    private var standbyProbe = false
    private var standbyGeneration = 0L
    private var standbyAlarmToken: String? = null
    private val standbyCheck = Runnable { updateBrowserPower() }
    private val browserMaintenance = object : Runnable {
        override fun run() {
            if (!browserRequested) return
            BrowserUpdates.check(this@DiPlaySessionService)
            if (browserStarted && currentBrowserOutput()?.running == true) {
                val address = BrowserAddressService.snapshot(this@DiPlaySessionService)
                if (address.enabled && !address.active && !address.busy) {
                    val requested = BrowserAddressService.ensureStartedIfEnabled(this@DiPlaySessionService)
                    BrowserReliabilityDiagnostics.record(this@DiPlaySessionService,
                        "browser_address_recovery requested=$requested")
                }
            }
            browserHandler.postDelayed(this, if (standbyParked) 15 * 60_000L else BROWSER_MAINTENANCE_MILLIS)
        }
    }
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockMode = -1
    @Volatile private var charging = true
    @Volatile private var browserClientConnected = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (browserCarPlayEnabled(this)) {
            BrowserReliabilityDiagnostics.record(this, "service_create")
            BrowserTelemetry.pump(this)
            BrowserUpdates.check(this)
            browserWorker.execute {
                ProcessExitDiagnostics.report(applicationContext).lineSequence().forEach {
                    BrowserReliabilityDiagnostics.record(applicationContext, it)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try {
        startCommand(intent, flags, startId)
    } finally {
        if (intent?.action == ACTION_STANDBY_PROBE) {
            BrowserStandbyAlarm.releaseHandoff(intent.getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN))
        }
    }

    private fun startCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val browserMode = browserCarPlayEnabled(this)
        // A queued foreground start still needs promotion when recovery is rejected or stopped.
        // stopSelf(startId) alone can leave Android's deadline active while another start is queued.
        if (browserMode) startSessionForeground(browserMode)
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            BrowserSessionRecovery.markStopped(this)
            if (browserMode) BrowserReliabilityDiagnostics.record(this, "service_stop_requested")
            cancelBrowserRecovery()
            if (browserMode) retireBrowserReceiver()
            else CarPlayBackgroundSession.stop()
            if (browserMode) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val automaticStart = intent == null || intent.action == ACTION_RECOVER || intent.action == ACTION_STANDBY_PROBE
        if (browserMode && automaticStart &&
            !BrowserSessionRecovery.canRecover(this)) {
            BrowserReliabilityDiagnostics.record(this, "service_restart_skipped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (browserMode && intent?.action == ACTION_STANDBY_PROBE && browserRequested) {
            val token = intent.getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN)
            if (standbyParked && token != null && token == standbyAlarmToken) wakeBrowserReceiver("probe", probe = true)
            return START_STICKY
        }
        if (intent != null && !automaticStart) BrowserSessionRecovery.markStarted(this)
        receiverAutomaticStart = intent?.action != ACTION_RECONNECT && intent?.getBooleanExtra(EXTRA_MANUAL_START, false) != true
        stopRequested = false
        if (browserMode) BrowserReliabilityDiagnostics.record(this,
            "service_start nullIntent=${intent == null} redelivered=${flags and START_FLAG_REDELIVERY != 0}")
        if (browserMode && (intent == null || intent.action == ACTION_RECOVER)) BrowserTelemetry.record(this,
            "receiver_start_requested", mapOf("automatic" to receiverAutomaticStart, "source" to "service_recovery"))
        if (!browserMode) startSessionForeground(browserMode)
        if (browserMode) {
            BrowserAddressService.ensureStartedIfEnabled(this)
        }
        if (browserMode) {
            browserRequested = true
            if (reliabilityMonitor == null) reliabilityMonitor = BrowserReliabilityMonitor(this)
            if (browserRetry == null) startBrowserOutput()
            browserHandler.removeCallbacks(browserMaintenance)
            browserHandler.postDelayed(browserMaintenance, BROWSER_MAINTENANCE_MILLIS)
        }
        if (browserMode && !receiverRetiring) {
            val receiver = currentBrowserRuntime()
            // Manual Start bypasses a pending backoff or corrected configuration gate once.
            // Ordinary recovery and a receiver already preparing/connected stay untouched.
            val manualRecovery = intent?.action == ACTION_RECOVER &&
                intent.getBooleanExtra(EXTRA_MANUAL_START, false) &&
                !CarPlayBackgroundSession.active && receiver?.manualRecoveryPending == true
            if (intent?.action == ACTION_RECONNECT || manualRecovery) {
                if (manualRecovery) BrowserReliabilityDiagnostics.record(this, "receiver_manual_recovery")
                receiver?.reconnect()
            }
        }
        if (browserMode && currentBrowserRuntime() == null &&
            currentBrowserOutput()?.running == true) startBrowserReceiver()
        if (browserMode && (standbyParked || standbyParking) && intent?.action != ACTION_STANDBY_PROBE) {
            wakeBrowserReceiver("service_start")
        }
        return if (BrowserSessionRecovery.canRecover(this)) START_STICKY else START_NOT_STICKY
    }

    private fun startSessionForeground(browserMode: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, if (browserMode) BrowserDashboardActivity::class.java else CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(if (browserMode) getString(R.string.browser_product_name) else "DiPlay")
            .setContentText(if (browserMode) "CarPlay browser stream running" else "CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && sessionMicrophoneRequired(this) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (!browserMode && AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
    }

    private fun startBrowserOutput() {
        if (!browserRequested || browserStarted) return
        browserStarted = true
        BrowserReliabilityDiagnostics.record(this, "browser_server_start")
        val token = Any().also { browserStartToken = it }
        acquireCpuLock()
        observePower()
        updateWifiLock()
        BrowserReliabilityDiagnostics.record(this,
            "power_locks cpu=${cpuLock?.isHeld == true} wifi=${wifiLock?.isHeld == true}")
        val output = browserOutput(this)
        output.onClientConnection = ::onBrowserClientConnection
        browserWorker.execute {
            val failure = runCatching { output.start() }.exceptionOrNull()
            browserHandler.post {
                // A late worker completion must not undo Disconnect or a newer service start.
                if (!browserRequested || browserStartToken !== token) return@post
                if (failure == null) {
                    browserRetryAttempt = 0
                    BrowserReliabilityDiagnostics.record(this, "browser_server_ready")
                    BrowserAddressService.ensureStartedIfEnabled(this)
                    startBrowserReceiver()
                } else {
                    android.util.Log.e("DiPlayBrowser", "Browser server could not start", failure)
                    browserStarted = false
                    releaseBrowserLocks()
                    BrowserAddressService.pause(this)
                    val delay = (2_000L shl browserRetryAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
                    val failureClass = failure.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")
                    BrowserReliabilityDiagnostics.record(this,
                        "browser_server_failure class=$failureClass retryMs=$delay")
                    browserRetryAttempt = (browserRetryAttempt + 1).coerceAtMost(4)
                    browserRetry = Runnable {
                        browserRetry = null
                        startBrowserOutput()
                    }.also { browserHandler.postDelayed(it, delay) }
                }
            }
        }
    }

    private fun startBrowserReceiver() {
        if (destroyed || receiverRetiring || !browserRequested || !BrowserSessionRecovery.canRecover(this)) return
        val output = currentBrowserOutput() ?: return
        val receiver = synchronized(Companion) {
            runtime ?: BrowserCarPlayRuntime(applicationContext, output).also { runtime = it }
        }
        receiver.onStandbyStateChanged = ::onReceiverStateChanged
        receiver.setTelemetryStartOrigin(automatic = receiverAutomaticStart)
        receiver.start()
        updateBrowserPower()
    }

    private fun retireBrowserReceiver() {
        if (receiverRetiring) return
        val receiver = currentBrowserRuntime()
        if (receiver == null) {
            BrowserTelemetry.sessionEnded(this, "user_stop")
            return
        }
        receiverRetiring = true
        receiver.close("user_stop") {
            synchronized(Companion) { if (runtime === receiver) runtime = null }
            receiverRetiring = false
            // A new Start can arrive before Android destroys this stopped Service. Wait for
            // the old controller's teardown, then restore the latest requested state.
            if (!destroyed && browserRequested && !stopRequested && currentBrowserOutput()?.running == true) {
                startBrowserReceiver()
            }
        }
    }

    private fun cancelBrowserRecovery() {
        browserRequested = false
        browserStarted = false
        browserStartToken = null
        browserRetryAttempt = 0
        browserRetry?.let(browserHandler::removeCallbacks)
        browserRetry = null
        browserHandler.removeCallbacks(browserMaintenance)
        ++standbyGeneration
        browserHandler.removeCallbacks(standbyCheck)
        BrowserStandbyAlarm.cancel(this)
        standbyAlarmToken = null
        standbyParked = false
        standbyParking = false
        standbyProbe = false
        idleSince = -1L
        currentBrowserRuntime()?.onStandbyStateChanged = null
        reliabilityMonitor?.close()
        reliabilityMonitor = null
        releaseBrowserLocks()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (browserCarPlayEnabled(this)) {
            BrowserReliabilityDiagnostics.record(this, "service_task_removed")
            return
        }
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    private fun releaseBrowserLocks() {
        if (cpuLock?.isHeld == true) cpuLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        cpuLock = null
        wifiLock = null
        wifiLockMode = -1
    }

    private fun acquireCpuLock() {
        if (cpuLock?.isHeld == true) return
        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:browser-stream").apply { acquire() }
    }

    private fun standbyDemand(): Boolean = charging || screenInteractive || browserClientConnected ||
        currentBrowserRuntime()?.sessionActive == true

    private fun onReceiverStateChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            browserHandler.post { onReceiverStateChanged() }
            return
        }
        reliabilityMonitor?.activityChanged()
        // Manual Connect clears the runtime's parked flag before it queues any connection work.
        if (standbyParked && currentBrowserRuntime()?.standbyParked == false) {
            wakeBrowserReceiver("receiver_action")
        } else updateBrowserPower()
    }

    private fun wakeBrowserReceiver(reason: String, probe: Boolean = false) {
        if (destroyed || !browserRequested || !browserStarted) return
        ++standbyGeneration
        browserHandler.removeCallbacks(standbyCheck)
        BrowserStandbyAlarm.cancel(this)
        standbyAlarmToken = null
        standbyParked = false
        standbyParking = false
        standbyProbe = probe
        idleSince = SystemClock.elapsedRealtime()
        // Acquire before resume can enqueue preparation or Bluetooth work.
        acquireCpuLock()
        updateWifiLock()
        browserHandler.removeCallbacks(browserMaintenance)
        browserHandler.postDelayed(browserMaintenance, BROWSER_MAINTENANCE_MILLIS)
        BrowserReliabilityDiagnostics.record(this, "standby_wake reason=$reason")
        currentBrowserRuntime()?.resumeFromStandby()
        reliabilityMonitor?.activityChanged()
        updateBrowserPower()
    }

    private fun updateBrowserPower() {
        if (destroyed || !browserRequested || !browserStarted) return
        browserHandler.removeCallbacks(standbyCheck)
        if (standbyDemand()) {
            if (standbyParked || standbyParking) {
                wakeBrowserReceiver("activity")
                return
            }
            idleSince = -1L
            standbyProbe = false
            acquireCpuLock()
            return
        }
        if (standbyParked || standbyParking) return
        val now = SystemClock.elapsedRealtime()
        if (idleSince < 0) idleSince = now
        val remaining = if (standbyProbe) 0L else STANDBY_GRACE_MILLIS - (now - idleSince)
        if (remaining > 0) {
            browserHandler.postDelayed(standbyCheck, remaining)
            return
        }
        val receiver = currentBrowserRuntime() ?: return
        // Never suspend a live bootstrap. Wait for its success or failure callback instead.
        if (!receiver.standbyReady) return
        standbyParking = true
        val generation = ++standbyGeneration
        val accepted = receiver.parkForStandby {
            if (destroyed || !browserRequested || generation != standbyGeneration) return@parkForStandby
            standbyParking = false
            if (standbyDemand() || !receiver.standbyParked) {
                wakeBrowserReceiver("activity_during_park")
                return@parkForStandby
            }
            val token = UUID.randomUUID().toString()
            // A sleeping Handler cannot provide the fallback. Arm the wake alarm first.
            if (!BrowserStandbyAlarm.schedule(this, token)) {
                wakeBrowserReceiver("alarm_unavailable")
                return@parkForStandby
            }
            standbyAlarmToken = token
            standbyParked = true
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            wifiLockMode = -1
            if (cpuLock?.isHeld == true) cpuLock?.release()
            cpuLock = null
            reliabilityMonitor?.activityChanged()
            BrowserReliabilityDiagnostics.record(this, "standby_enter cpu=false wifi=false")
        }
        if (!accepted) standbyParking = false
    }

    /** Tracks charging state so the radio can back off while parked on battery. */
    private fun observePower() {
        if (batteryReceiver != null) return
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> onChargingChanged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                    Intent.ACTION_SCREEN_ON -> {
                        screenInteractive = true
                        wakeBrowserReceiver("screen_on")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenInteractive = false
                        updateBrowserPower()
                    }
                }
            }
        }
        batteryReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // ACTION_BATTERY_CHANGED is a sticky, protected system broadcast, so it is exempt from the
        // export-flag rule. Still pass the flag where the platform supports it (API 33+).
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        onChargingChanged((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        val bluetooth = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val wake = when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    BluetoothDevice.ACTION_ACL_CONNECTED -> runCatching {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device != null && device.address == DiPlayPreferences.phoneAddress(this@DiPlaySessionService)
                    }.getOrDefault(false)
                    else -> false
                }
                if (wake) wakeBrowserReceiver("bluetooth")
            }
        }
        bluetoothReceiver = bluetooth
        val bluetoothFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        // These protected broadcasts may come from Bluetooth's privileged non-system UID.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetooth, bluetoothFilter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetooth, bluetoothFilter)
    }

    private fun onChargingChanged(nowCharging: Boolean) {
        if (nowCharging == charging) return
        charging = nowCharging
        browserHandler.post {
            if (nowCharging) wakeBrowserReceiver("power")
            updateWifiLock()
            updateBrowserPower()
        }
    }

    private fun onBrowserClientConnection(connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            browserHandler.post { onBrowserClientConnection(connected) }
            return
        }
        if (browserClientConnected == connected) return
        browserClientConnected = connected
        if (connected) wakeBrowserReceiver("browser")
        updateWifiLock()
        updateBrowserPower()
        reliabilityMonitor?.activityChanged()
    }

    /**
     * Holds the WiFi radio at high-performance only while a browser is streaming or the phone is
     * externally powered. These station-mode locks are not a hotspot reachability guarantee;
     * parked standby releases them entirely along with the CPU lock.
     */
    @Suppress("DEPRECATION")
    private fun updateWifiLock() {
        if (destroyed || !browserRequested || standbyParked) return
        val mode = browserWifiLockMode(charging, browserClientConnected)
        if (wifiLockMode == mode) return
        if (wifiLock?.isHeld == true) wifiLock?.release()
        val lock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(mode, "DiPlay:browser-stream")
        wifiLock = lock?.apply { acquire() }
        wifiLockMode = mode
        BrowserReliabilityDiagnostics.record(this,
            "wifi_lock mode=${if (mode == WifiManager.WIFI_MODE_FULL_HIGH_PERF) "high_perf" else "full"} held=${lock?.isHeld == true}")
    }

    override fun onDestroy() {
        destroyed = true
        if (browserCarPlayEnabled(this)) BrowserReliabilityDiagnostics.record(this,
            "service_destroy requestedStop=$stopRequested")
        cancelBrowserRecovery()
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        bluetoothReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothReceiver = null
        if (browserCarPlayEnabled(this)) {
            BrowserAddressService.pause(this)
            val output = synchronized(Companion) { browser.also { browser = null } }
            browserWorker.execute { output?.close() }
            val receiver = synchronized(Companion) { runtime.also { runtime = null } }
            if (receiver != null) receiver.close(if (stopRequested) "user_stop" else "service_stopped")
            else if (!stopRequested) BrowserTelemetry.sessionEnded(this, "service_stopped")
        }
        browserWorker.shutdown()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        const val ACTION_RECONNECT = "com.shihab.diplay.RECONNECT"
        const val ACTION_RECOVER = "com.shihab.diplay.RECOVER"
        const val ACTION_STANDBY_PROBE = "co.tesair.STANDBY_PROBE"
        const val EXTRA_MANUAL_START = "telemetry_manual_start"
        private var runtime: BrowserCarPlayRuntime? = null
        @Synchronized internal fun currentBrowserRuntime(): BrowserCarPlayRuntime? = runtime
        private const val BROWSER_MAINTENANCE_MILLIS = 30_000L
        private const val STANDBY_GRACE_MILLIS = 2 * 60_000L
        private const val CHANNEL = "diplay_connection"
        private var browser: BrowserStreamOutput? = null
        @Synchronized internal fun currentBrowserOutput(): BrowserStreamOutput? = browser
        @Synchronized internal fun browserOutput(context: Context): BrowserStreamOutput = browser
            ?: BrowserStreamOutput(context.applicationContext).also { output ->
                output.onTouch = { contacts -> CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(contacts) }
                output.onDiagnostic = { event -> BrowserReliabilityDiagnostics.record(context.applicationContext, event) }
                browser = output
            }
    }
}

internal fun sessionMicrophoneRequired(context: Context): Boolean {
    if (!browserCarPlayEnabled(context)) return true
    // Keep an older running session's permission type until it ends. New browser sessions use
    // the iPhone's Bluetooth audio route and never need Android's microphone.
    return CarPlayBackgroundSession.snapshot()?.display?.microphoneRequired
        ?: false
}

/** High-performance WiFi is only warranted while streaming or externally powered. */
@Suppress("DEPRECATION")
internal fun browserWifiLockMode(charging: Boolean, browserClientConnected: Boolean): Int =
    if (charging || browserClientConnected) WifiManager.WIFI_MODE_FULL_HIGH_PERF
    else WifiManager.WIFI_MODE_FULL

/** Browser pixels are persisted independently of Android window size, orientation, or lock state. */
internal object BrowserCarPlayViewport {
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 2560.0 / maxOf(width, height))
        return ((width * scale).toInt() / 2 * 2).coerceAtLeast(2) to
            ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }
    fun load(context: Context): Pair<Int, Int> {
        val saved = context.getSharedPreferences("browser_display", Context.MODE_PRIVATE)
        val width = saved.getInt("width", 1920)
        val height = saved.getInt("height", 1080)
        return if (width > 0 && height > 0) fit(width, height) else 1920 to 1080
    }
    fun save(context: Context, width: Int, height: Int): Pair<Int, Int> = fit(width, height).also { (w, h) ->
        context.getSharedPreferences("browser_display", Context.MODE_PRIVATE).edit()
            .putInt("width", w).putInt("height", h).apply()
    }
}

/** Main video remains compressed; Android retains the existing audio and microphone path. */
internal class BrowserCarPlayMediaSink(
    private val android: MediaSink,
    private val browser: MediaSink,
    private val current: () -> Boolean,
) : MediaSink by android {
    private fun video(type: Int, deliver: (MediaSink) -> Unit) {
        if (type != 110) deliver(android) else if (current()) deliver(browser)
    }
    override fun onVideoCodec(type: Int, codec: VideoCodec) = video(type) { it.onVideoCodec(type, codec) }
    override fun onVideoConfig(type: Int, codecData: ByteArray) = video(type) { it.onVideoConfig(type, codecData) }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) = video(type) { it.onVideoFrame(type, naluBytes) }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        video(type) { it.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos) }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) = video(type) { target ->
        target.setVideoRecoveryHandler(type) { if (type != 110 || current()) handler() }
    }
    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) = video(type) { target ->
        target.setVideoDiagnosticHandler(type) { if (type != 110 || current()) handler(it) }
    }
    override fun onScreenStreamActive(type: Int, active: Boolean) {
        android.onScreenStreamActive(type, active)
        if (type == 110 && current()) browser.onScreenStreamActive(type, active)
    }
}
