package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (browserCarPlayEnabled(context)) {
            val enabled = BrowserSessionRecovery.canRecover(context) &&
                (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || AirPlayPersistence.loadAutoStartOnBoot(context))
            StartupDiagnosticSnapshot.received(context, enabled)
            BrowserReliabilityDiagnostics.record(context,
                "startup_received update=${intent.action == Intent.ACTION_MY_PACKAGE_REPLACED} enabled=$enabled")
            if (!enabled) return
            try {
                context.startForegroundService(Intent(context, DiPlaySessionService::class.java)
                    .setAction(DiPlaySessionService.ACTION_RECOVER))
                StartupDiagnosticSnapshot.launchResult(context, service = true)
            } catch (error: RuntimeException) {
                StartupDiagnosticSnapshot.launchResult(context, error, service = true)
                BrowserReliabilityDiagnostics.record(context,
                    "startup_service_failed class=${error.javaClass.simpleName}")
            }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
