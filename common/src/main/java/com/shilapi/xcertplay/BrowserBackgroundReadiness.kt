package com.shilapi.xcertplay

import android.app.ActivityManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Live Android grants, not a record that the user opened a settings page. */
internal object BrowserBackgroundReadiness {
    data class State(val batteryExempt: Boolean, val restricted: Boolean?) {
        // An unreadable restriction is not evidence that background use is permitted.
        val allowed get() = batteryExempt && restricted == false
    }

    fun snapshot(context: Context): State = State(
        batteryExempt = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false),
        restricted = if (Build.VERSION.SDK_INT < 28) false else runCatching {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        }.getOrNull(),
    )

    fun settingsIntent(context: Context): Intent {
        val needsExemption = !snapshot(context).batteryExempt
        return Intent(
            if (needsExemption) Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** OEM settings can be missing or disabled; none of these launches counts as a grant. */
    fun request(context: Context): Boolean {
        val state = snapshot(context)
        if (state.allowed) return true
        val primary = settingsIntent(context)
        val candidates = buildList {
            // Prefer Android's actual Doze consent when present. Vendor battery dashboards
            // can handle the same implicit action without granting the Android exemption.
            if (!state.batteryExempt) add(Intent(primary).setPackage("com.android.settings"))
            add(primary)
            if (!state.batteryExempt) {
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
        for (intent in candidates) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next public settings entry point on this firmware.
            } catch (_: SecurityException) {
                // Some manufacturers expose a settings handler that apps cannot open.
            }
        }
        return false
    }
}
