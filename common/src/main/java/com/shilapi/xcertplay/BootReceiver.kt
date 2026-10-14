package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts or resumes the CarPlay host after boot or an app update.
 *
 * Power-return recovery is best effort on firmware that delivers manifest power broadcasts.
 * The running foreground service observes charging dynamically and owns sticky session recovery.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> onPowerConnected(context)
            Intent.ACTION_POWER_DISCONNECTED -> {
                // No immediate teardown here: the session service observes the charging state and
                // backs off its radio locks on its own. This record only keeps the event visible.
                if (browserCarPlayEnabled(context)) {
                    BrowserReliabilityDiagnostics.record(context, "power_disconnected")
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                onBootOrUpdate(context, intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            else -> Unit
        }
    }

    private fun onPowerConnected(context: Context) {
        if (!browserCarPlayEnabled(context)) return
        val enabled = BrowserSessionRecovery.canRecover(context) &&
            AirPlayPersistence.loadAutoStartOnBoot(context)
        BrowserReliabilityDiagnostics.record(context, "power_connected enabled=$enabled")
        if (!enabled) return
        startBrowserSession(context)
    }

    private fun onBootOrUpdate(context: Context, update: Boolean) {
        if (browserCarPlayEnabled(context)) {
            val enabled = BrowserSessionRecovery.canRecover(context) &&
                (update || AirPlayPersistence.loadAutoStartOnBoot(context))
            StartupDiagnosticSnapshot.received(context, enabled)
            BrowserReliabilityDiagnostics.record(context,
                "startup_received update=$update enabled=$enabled")
            if (!enabled) return
            startBrowserSession(context)
            return
        }
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        BrowserReliabilityDiagnostics.record(context,
            "boot_received launchEnabled=$launchEnabled")
        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
            BrowserReliabilityDiagnostics.record(context, "boot_activity_requested")
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            BrowserReliabilityDiagnostics.record(context, "boot_activity_failed")
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private fun startBrowserSession(context: Context) {
        try {
            context.startForegroundService(Intent(context, DiPlaySessionService::class.java)
                .setAction(DiPlaySessionService.ACTION_RECOVER))
            StartupDiagnosticSnapshot.launchResult(context, service = true)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error, service = true)
            BrowserReliabilityDiagnostics.record(context,
                "startup_service_failed class=${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
