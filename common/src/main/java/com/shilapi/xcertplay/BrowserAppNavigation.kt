package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The browser product has one home, one guide and one settings screen. */
internal object BrowserAppNavigation {
    fun destination(context: Context, incoming: Intent, restored: Bundle? = null): Intent {
        val page = restored?.getString("page") ?: incoming.getStringExtra("page")
        return when (page) {
            "settings", "about" -> Intent(context, BrowserSettingsActivity::class.java).putExtra("page", page)
            "connection" -> Intent(context, BrowserOnboardingActivity::class.java)
                .putExtra(BrowserOnboardingActivity.EXTRA_REVIEW, true)
            else -> homeIntent(context, autoStart = restored == null && page == null)
        }.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    fun openHome(context: Context, autoStart: Boolean = false) {
        context.startActivity(homeIntent(context, autoStart).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun homeIntent(context: Context, autoStart: Boolean): Intent {
        if (!configured(context) || BrowserOnboardingProgress.shouldResume(context)) {
            return Intent(context, BrowserOnboardingActivity::class.java)
        }
        val connect = autoStart && DiPlayPreferences.autoConnect(context) && !CarPlayBackgroundSession.hasSession()
        if (connect) AirPlayPersistence.saveWirelessEnabled(context, true)
        return Intent(context, BrowserDashboardActivity::class.java)
            .putExtra(BrowserDashboardActivity.EXTRA_RECONNECT, connect)
    }

    private fun configured(context: Context): Boolean {
        if (DiPlayPreferences.phoneAddress(context) == null) return false
        return when (AirPlayPersistence.loadWirelessHotspotMode(context)) {
            WirelessHotspotMode.MANUAL -> ManualHotspotValidation.error(
                AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
            WirelessHotspotMode.EXISTING_WIFI -> ManualHotspotValidation.error(
                AirPlayPersistence.loadExistingWifiSsid(context),
                AirPlayPersistence.loadExistingWifiPassphrase(context)) == null
            else -> true // Preserve configured installations using another supported transport.
        }
    }
}
