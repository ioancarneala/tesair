package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.shilapi.xcertplay.browser.BrowserAddressService
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Connection metadata only. No screen contents, network names, peers or protocol payloads. */
internal object BrowserReliabilityDiagnostics {
    private var target: SessionLogFile? = null

    @Synchronized private fun log(context: Context): SessionLogFile {
        val file = File(context.applicationContext.filesDir, "logs/reliability.log")
        return target?.takeIf { it.file == file }
            ?: SessionLogFile(file).also { target = it }
    }

    fun record(context: Context, event: String) {
        if (!browserCarPlayEnabled(context)) return
        runCatching {
            AsyncDiagnosticLog.append(log(context),
                "RELIABILITY epochMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.elapsedRealtime()} " +
                    "pid=${android.os.Process.myPid()} $event")
        }
    }

    fun snapshot(context: Context): String = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val aliasPresent = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                // Presence is not reachability: handover retains the local alias on a down TUN.
                Collections.list(iface.inetAddresses).any { it.hostAddress == BrowserAddressService.ADDRESS }
            }
        }.getOrDefault(false)
        val route = BrowserAddressService.snapshot(context)
        val bluetooth = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
        val output = DiPlaySessionService.currentBrowserOutput()
        "system screenOn=${power?.isInteractive} idle=${power?.isDeviceIdleMode} " +
            "powerSave=${power?.isPowerSaveMode} batteryExempt=${power?.isIgnoringBatteryOptimizations(context.packageName)} " +
            "thermal=${if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else -1} " +
            "batteryPercent=${if (level >= 0 && scale > 0) level * 100 / scale else -1} " +
            "batteryTenthsC=${battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1} " +
            "plugged=${battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0} " +
            "lowMemory=${memory.lowMemory} availableMiB=${memory.availMem / 1048576} " +
            "uplink=${network != null} validated=${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true} " +
            "wifi=${network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true} " +
            "cellular=${network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true} " +
            "bluetooth=$bluetooth receiverRequested=${BrowserSessionRecovery.requested(context)} " +
            "route=${route.phase} aliasPresent=$aliasPresent server=${output?.running == true} " +
            "viewers=${output?.clientCount ?: 0} session=${CarPlayBackgroundSession.hasSession()} " +
            "iphoneActive=${CarPlayBackgroundSession.active} videoRendered=${output?.hasRenderedVideo == true}"
    }.getOrElse { "system unavailable=${it.javaClass.simpleName}" }

    fun report(context: Context): String {
        // Export runs on its own worker; transport callbacks never wait for disk.
        AsyncDiagnosticLog.awaitIdle(1_000)
        return snapshot(context) + "\n" + SessionLogFile.report(File(context.filesDir, "logs/reliability.log"))
    }

    fun telemetrySample(context: Context) {
        if (!BrowserTelemetry.enabled(context)) return
        val output = DiPlaySessionService.currentBrowserOutput()
        val active = CarPlayBackgroundSession.active || (output?.clientCount ?: 0) > 0
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            BrowserTelemetry.record(context, "reliability_sample", mapOf(
                "screenOn" to power?.isInteractive, "idle" to power?.isDeviceIdleMode,
                "powerSave" to power?.isPowerSaveMode, "batteryExempt" to power?.isIgnoringBatteryOptimizations(context.packageName),
                "thermalStatus" to if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
                "batteryPercent" to if (level >= 0 && scale > 0) level * 100 / scale else null,
                "charging" to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
                "lowMemory" to memory.lowMemory, "uplink" to (network != null),
                "validated" to (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "wifi" to (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
                "cellular" to (network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true),
                "receiverRunning" to (output?.running == true), "browserCount" to (output?.clientCount ?: 0),
                // This confirms a frame in the current media generation, not continuous live playback.
                "iphoneActive" to CarPlayBackgroundSession.active, "videoRendered" to (output?.hasRenderedVideo == true),
            ))
            // Operational changes flush in the collector; use the existing tick for other pending reports during use.
            if (active) BrowserTelemetry.pump(context)
        }
    }
}

/** Foreground-session diagnostics once/minute, plus lightweight pending screen-off verification. */
internal class BrowserReliabilityMonitor(context: Context) : Closeable {
    private val app = context.applicationContext
    private val closed = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { job ->
        Thread(job, "diplay-health").apply { isDaemon = true }
    }
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = event("uplink_available")
        override fun onLost(network: Network) = event("uplink_lost")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) BrowserTelemetry.pump(app)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "idle_changed"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power_save_changed"
                android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION -> "wifi_state_changed"
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> "bluetooth_state_changed"
                else -> return
            }
            event(event)
        }
    }
    private var callbackRegistered = false
    private var receiverRegistered = false

    init {
        callbackRegistered = runCatching { connectivity?.registerDefaultNetworkCallback(callback); connectivity != null }.getOrDefault(false)
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        receiverRegistered = runCatching {
            // Bluetooth's protected system broadcasts can originate from a privileged non-system UID.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            true
        }.getOrDefault(false)
        worker.scheduleWithFixedDelay({
            if (!closed.get()) {
                BrowserReliabilityDiagnostics.record(app, BrowserReliabilityDiagnostics.snapshot(app))
                BrowserAutomaticReport.save(app)
                sampleTelemetry()
            }
        }, 0, 60, TimeUnit.SECONDS)
        worker.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { BrowserScreenOffCheck.poll(app) }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun event(event: String) {
        if (closed.get()) return
        BrowserReliabilityDiagnostics.record(app, event)
    }

    private fun sampleTelemetry() = synchronized(closed) {
        // Finish an in-flight enqueue before service teardown records its terminal state.
        if (!closed.get()) BrowserReliabilityDiagnostics.telemetrySample(app)
    }

    override fun close() {
        synchronized(closed) { if (!closed.compareAndSet(false, true)) return }
        BrowserScreenOffCheck.stop()
        if (callbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
        // Finish one private snapshot without making service teardown wait for disk.
        worker.execute { BrowserAutomaticReport.save(app) }
        worker.shutdown()
    }
}
