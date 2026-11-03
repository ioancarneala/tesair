package com.shilapi.xcertplay.network

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    enum class Band { TWO_GHZ, FIVE_GHZ, SIX_GHZ, UNKNOWN }

    /**
     * Current hotspot band when the firmware exposes it. A null/unknown result means the app
     * must not claim anything about the band, only show the 5 GHz recommendation.
     */
    fun band(context: Context): Band =
        if (Build.VERSION.SDK_INT < 30) Band.UNKNOWN else band30(context)

    @androidx.annotation.RequiresApi(30)
    private fun band30(context: Context): Band {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return Band.UNKNOWN
        return try {
            // The public SDK stubs do not expose getSoftApConfiguration/getBand; both exist on
            // Android 11+ and are readable when the OEM returns the current tethering config.
            val configuration = WifiManager::class.java
                .getMethod("getSoftApConfiguration").invoke(wifi) ?: return Band.UNKNOWN
            val band = configuration.javaClass.getMethod("getBand").invoke(configuration) as? Int
                ?: return Band.UNKNOWN
            bandFor(band)
        } catch (_: Exception) { Band.UNKNOWN }
    }

    internal fun bandFor(band: Int): Band = when (band) {
        1 -> Band.TWO_GHZ
        2 -> Band.FIVE_GHZ
        3 -> Band.SIX_GHZ
        else -> Band.UNKNOWN
    }

    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        return read(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            enabled = { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    internal fun stateEnabled(state: Int?): Boolean? = state?.takeIf { it in 10..14 }?.let { it == 13 }

    internal fun read(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        stateEnabled(runCatching(state).getOrNull())?.let { return it }
        runCatching(enabled).getOrNull()?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        // Local-only AP state says nothing about the car's tethered hotspot, including on multi-AP firmware.
        if (intent.hasExtra(EXTRA_WIFI_AP_MODE) && intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }
}
