package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import com.shilapi.xcertplay.browser.BrowserAddressService

/** Stores completed actions, never form drafts or connection secrets. */
internal object BrowserOnboardingProgress {
    enum class Step { WELCOME, NETWORK, BACKGROUND, IPHONE, TESLA_WIFI, TESLA_BROWSER, AUDIO, READY }

    private fun preferences(context: Context) = context.getSharedPreferences("browser_onboarding", Context.MODE_PRIVATE)

    fun step(context: Context): Step = runCatching {
        Step.valueOf(preferences(context).getString("step", Step.WELCOME.name)!!)
    }.getOrDefault(Step.WELCOME)

    fun moveTo(context: Context, step: Step) {
        preferences(context).edit().putString("step", step.name).apply()
    }

    fun audioConfirmed(context: Context) = preferences(context).getBoolean("audio_confirmed", false)

    fun confirmAudio(context: Context) {
        preferences(context).edit().putBoolean("audio_confirmed", true).apply()
    }

    fun resetPhoneVerification(context: Context) {
        preferences(context).edit().putBoolean("audio_confirmed", false).putBoolean("completed", false).apply()
    }

    fun completed(context: Context) = preferences(context).getBoolean("completed", false) && approvalsAllowed(context)

    fun shouldResume(context: Context) = preferences(context).contains("step") &&
        step(context) != Step.WELCOME && !completed(context)

    fun canFinish(iphoneActive: Boolean, videoRendered: Boolean, audioConfirmed: Boolean) =
        iphoneActive && videoRendered && audioConfirmed

    fun finish(context: Context, iphoneActive: Boolean, videoRendered: Boolean): Boolean {
        if (!canFinish(iphoneActive, videoRendered, audioConfirmed(context)) || !approvalsAllowed(context)) return false
        preferences(context).edit().putBoolean("completed", true).putString("step", Step.READY.name).apply()
        return true
    }

    fun approvalsAllowed(context: Context): Boolean =
        WirelessPermissions.required(Build.VERSION.SDK_INT, AirPlayPersistence.loadWirelessHotspotMode(context)).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } && BrowserBackgroundReadiness.snapshot(context).allowed && localAddressApproved(context)

    // prepare may reclaim prior consent, so never poll it over another app's active VPN.
    // A normally stopped local service still has consent and does not reset completed setup.
    fun localAddressApproved(context: Context) = runCatching {
        !BrowserAddressService.hasOtherVpn(context) && VpnService.prepare(context) == null
    }.getOrDefault(false)
}
