package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Persist user intent independently of any Activity or launcher. */
internal object BrowserSessionRecovery {
    private const val PREFERENCES = "browser_session_recovery"

    fun markStarted(context: Context) = setRequested(context, true)
    fun markStopped(context: Context) = setRequested(context, false)

    fun requested(context: Context): Boolean = browserCarPlayEnabled(context) &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("requested", false)

    fun stopped(context: Context): Boolean {
        if (!browserCarPlayEnabled(context)) return false
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.contains("requested") && !preferences.getBoolean("requested", false)
    }

    private fun setRequested(context: Context, requested: Boolean) {
        if (!browserCarPlayEnabled(context)) return
        // A following process kill must not restore the previous wanted state after Disconnect.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("requested", requested).commit()
    }

    fun canRecover(context: Context): Boolean = requested(context) && configured(context)

    fun configured(context: Context): Boolean {
        if (!browserCarPlayEnabled(context) || DiPlayPreferences.phoneAddress(context).isNullOrBlank() ||
            !AirPlayPersistence.loadWirelessEnabled(context)) return false
        val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
        val networkConfigured = when (mode) {
            WirelessHotspotMode.MANUAL -> ManualHotspotValidation.error(
                AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
            WirelessHotspotMode.EXISTING_WIFI -> ManualHotspotValidation.error(
                AirPlayPersistence.loadExistingWifiSsid(context),
                AirPlayPersistence.loadExistingWifiPassphrase(context)) == null
            else -> true
        }
        return networkConfigured && WirelessPermissions.required(Build.VERSION.SDK_INT, mode).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

}
