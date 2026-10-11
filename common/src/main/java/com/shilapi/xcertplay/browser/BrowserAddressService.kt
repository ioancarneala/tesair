package com.shilapi.xcertplay.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.shilapi.xcertplay.BrowserDashboardActivity
import com.shilapi.xcertplay.host.R
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Adds one host-local address for the browser server. No packet proxy, DNS replacement or default
 * route is installed. Hotspot access to this address still depends on the phone's routing policy.
 */
class BrowserAddressService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var carrierTunnel: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { instance = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(lock) {
        when (intent?.action) {
            ACTION_DISABLE -> {
                setEnabled(this, false)
                generation++
                stopAddress(Phase.OFF, "Local browser address is off")
            }
            ACTION_PAUSE -> {
                generation++
                stopAddress(Phase.OFF, "Local browser address is paused")
            }
            ACTION_START -> {
                // A queued start must not undo Disable, Pause, or Android revoking permission.
                if (intent.getLongExtra(EXTRA_GENERATION, -1) != generation || !isEnabled(this)) {
                    if (tunnel == null) stopSelf(startId)
                    return@synchronized START_NOT_STICKY
                }
                if (tunnel != null) {
                    validateActiveAddress()
                    return@synchronized START_NOT_STICKY
                }
                val blocked = startBlocker(this)
                if (blocked != null) {
                    stopAddress(blocked.first, blocked.second)
                    return@synchronized START_NOT_STICKY
                }
                try {
                    showNotification()
                    tunnel = addressBuilder(ADDRESS).establish()
                    if (tunnel != null && Build.VERSION.SDK_INT >= 33) {
                        // Android 13+ filters hotspot ingress to an active VPN's address. Retain
                        // the first TUN while a separate, app-scoped carrier owns the VPN. This
                        // depends on firmware retaining the old local address after handover;
                        // only a browser on the hotspot can verify actual reachability.
                        carrierTunnel = addressBuilder(CARRIER_ADDRESS).establish()
                        if (carrierTunnel == null) {
                            closeTunnel()
                        }
                    }
                    if (tunnel == null) {
                        setEnabled(this, false)
                        generation++
                        stopAddress(Phase.PERMISSION_REQUIRED, "Android did not grant the local address")
                    } else if (validateActiveAddress()) {
                        publish(this, Phase.ACTIVE, "Local address enabled; hotspot access needs verification")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Local browser address could not start", error)
                    stopAddress(Phase.ERROR, "Local address could not start: ${error.javaClass.simpleName}")
                }
            }
            else -> if (tunnel == null) stopSelf(startId)
        }
        START_NOT_STICKY
    }

    private fun addressBuilder(address: String) = Builder()
        .setSession(getString(com.shilapi.xcertplay.host.R.string.browser_product_name))
        .addAddress(address, PREFIX_LENGTH)
        .addRoute(address, PREFIX_LENGTH)
        .addAllowedApplication(packageName)
        // Without this, an IPv4-only VPN also blocks this app's native IPv6 CarPlay.
        .allowFamily(OsConstants.AF_INET6)
        .allowBypass()
        .setMtu(1500)

    /** Validates ownership, not hotspot reachability. Caller holds [lock]. */
    private fun validateActiveAddress(): Boolean {
        val intact = try {
            val aliasOpen = tunnel?.fileDescriptor?.valid() == true
            if (Build.VERSION.SDK_INT < 33) {
                aliasOpen
            } else {
                val alias = NetworkInterface.getByInetAddress(InetAddress.getByName(ADDRESS))
                val carrier = NetworkInterface.getByInetAddress(InetAddress.getByName(CARRIER_ADDRESS))
                // Handover deliberately brings the alias interface down. Its host-local address
                // must survive on a different interface from the active carrier.
                aliasOpen && carrierTunnel?.fileDescriptor?.valid() == true &&
                    alias != null && carrier != null && alias.index > 0 && carrier.index > 0 &&
                    alias.index != carrier.index && carrier.isUp
            }
        } catch (error: Exception) {
            Log.w(TAG, "Local browser address verification failed: ${error.javaClass.simpleName}")
            false
        }
        if (!intact) {
            // Do not continually recreate unsupported interfaces from the background recovery loop.
            setEnabled(this, false)
            generation++
            stopAddress(Phase.ERROR, "Local browser address is unavailable; enable it again to retry")
        }
        return intact
    }

    private fun showNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.browser_address_notification_channel),
                NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, BrowserDashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disable = PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, BrowserAddressService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.browser_address_notification_title))
            .setContentText(getString(R.string.browser_address_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.browser_address_disable), disable).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else if (Build.VERSION.SDK_INT >= 29) {
            // The manifest's VPN type was introduced in Android 14; older systems reject it.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Caller holds [lock], including when Android invokes onRevoke on a Binder thread. */
    private fun stopAddress(phase: Phase, message: String) {
        closeTunnel()
        publish(this, phase, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunnel() {
        val old = tunnel
        val oldCarrier = carrierTunnel
        tunnel = null
        carrierTunnel = null
        for (descriptor in listOfNotNull(oldCarrier, old)) {
            try {
                descriptor.close()
            } catch (error: Exception) {
                Log.w(TAG, "Local browser address cleanup failed: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun onRevoke() {
        synchronized(lock) {
            setEnabled(this, false)
            generation++
            stopAddress(Phase.REVOKED, "Android revoked the local browser address; enable it again to retry")
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            closeTunnel()
            if (instance === this) {
                instance = null
                if (state.active || state.busy) publish(this, Phase.OFF, "Local browser address is stopped")
            }
        }
        super.onDestroy()
    }

    enum class Phase { OFF, STARTING, ACTIVE, STOPPING, PERMISSION_REQUIRED, OTHER_VPN, ERROR, REVOKED }

    data class State(val enabled: Boolean, val phase: Phase, val message: String) {
        val active: Boolean get() = phase == Phase.ACTIVE
        val busy: Boolean get() = phase == Phase.STARTING || phase == Phase.STOPPING
    }

    companion object {
        // OxygenOS rejects a 240/4 address in VpnService.establish(). Use a shared-space alias.
        const val ADDRESS = "100.99.9.9"
        internal const val CARRIER_ADDRESS = "169.254.99.1"
        const val PREFIX_LENGTH = 32
        const val ACTION_START = "com.shilapi.xcertplay.browser.ADDRESS_START"
        const val ACTION_DISABLE = "com.shilapi.xcertplay.browser.ADDRESS_DISABLE"
        const val ACTION_PAUSE = "com.shilapi.xcertplay.browser.ADDRESS_PAUSE"
        private const val EXTRA_GENERATION = "generation"
        private const val PREFERENCES = "browser_address"
        private const val CHANNEL = "diplay_browser_address"
        private const val NOTIFICATION_ID = 31
        private const val TAG = "DiPlayBrowserAddress"
        private val lock = Any()
        private var generation = 0L
        private var instance: BrowserAddressService? = null
        private var state = State(false, Phase.OFF, "Local browser address is off")

        @JvmStatic fun snapshot(context: Context): State = synchronized(lock) {
            state.copy(enabled = isEnabled(context))
        }

        /** The UI must obtain Android's VPN consent before calling this explicit opt-in. */
        @JvmStatic fun enable(context: Context): Boolean = synchronized(lock) {
            instance?.takeIf { it.tunnel != null }?.let { return@synchronized it.validateActiveAddress() }
            val blocked = startBlocker(context)
            if (blocked != null) {
                publish(context, blocked.first, blocked.second)
                return@synchronized false
            }
            setEnabled(context, true)
            requestStart(context)
        }

        /** Startup hook: never displays consent or replaces another active VPN. */
        @JvmStatic fun ensureStartedIfEnabled(context: Context): Boolean = synchronized(lock) {
            if (!isEnabled(context)) return@synchronized false
            instance?.takeIf { it.tunnel != null }?.let { return@synchronized it.validateActiveAddress() }
            if (state.phase == Phase.STARTING) return@synchronized true
            val blocked = startBlocker(context)
            if (blocked != null) {
                publish(context, blocked.first, blocked.second)
                return@synchronized false
            }
            requestStart(context)
        }

        @JvmStatic fun disable(context: Context) = stop(context, clearPreference = true)

        /** Stop with the browser session, keeping the user's opt-in for its next explicit start. */
        @JvmStatic fun pause(context: Context) = stop(context, clearPreference = false)

        @JvmStatic fun hasOtherVpn(context: Context): Boolean = synchronized(lock) {
            if (instance?.tunnel != null) return@synchronized false
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return@synchronized true
            @Suppress("DEPRECATION")
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        private fun startBlocker(context: Context): Pair<Phase, String>? = try {
            when {
                hasOtherVpn(context) -> Phase.OTHER_VPN to "Another VPN is active; turn it off before enabling this address"
                prepare(context) != null -> Phase.PERMISSION_REQUIRED to "Android VPN permission is required"
                else -> null
            }
        } catch (error: Exception) {
            Phase.ERROR to "Could not check local address permission: ${error.javaClass.simpleName}"
        }

        private fun requestStart(context: Context): Boolean {
            if (state.phase == Phase.STARTING) return true
            generation++
            publish(context, Phase.STARTING, "Enabling local browser address")
            return try {
                context.startForegroundService(Intent(context, BrowserAddressService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_GENERATION, generation))
                true
            } catch (error: Exception) {
                Log.e(TAG, "Could not request local browser address", error)
                publish(context, Phase.ERROR, "Local address could not start: ${error.javaClass.simpleName}")
                false
            }
        }

        private fun stop(context: Context, clearPreference: Boolean) = synchronized(lock) {
            if (clearPreference) setEnabled(context, false)
            generation++
            val message = if (clearPreference) "Local browser address is off" else "Local browser address is paused"
            val service = instance
            if (service != null) {
                publish(context, Phase.STOPPING, message)
                // A VPN remains system-bound, so stopService alone does not close its interface.
                service.stopAddress(Phase.OFF, message)
            } else {
                context.stopService(Intent(context, BrowserAddressService::class.java))
                publish(context, Phase.OFF, message)
            }
        }

        private fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", false)

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }

        private fun publish(context: Context, phase: Phase, message: String) {
            state = State(isEnabled(context), phase, message)
        }
    }
}
