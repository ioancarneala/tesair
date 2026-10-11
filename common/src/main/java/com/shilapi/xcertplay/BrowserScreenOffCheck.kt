package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Passive diagnostics on every phone; never an onboarding permission or completion gate. */
internal object BrowserScreenOffCheck {
    data class State(val passed: Boolean, val remainingSeconds: Int)

    private const val PREFERENCES = "browser_screen_off_check"
    private const val PROOF = "verified_build"
    private val tracker = BrowserScreenOffTracker()
    private var trackedBuild: String? = null

    @Synchronized fun snapshot(context: Context): State {
        val identity = buildIdentity(context)
        if (identity != trackedBuild) {
            trackedBuild = identity
            tracker.reset()
        }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getString(PROOF, null)
        val allowed = BrowserBackgroundReadiness.snapshot(context).allowed
        if (saved != null && (!allowed || saved != identity)) {
            preferences.edit().remove(PROOF).apply()
            BrowserReliabilityDiagnostics.record(context, "screen_off_check_invalidated")
        }
        if (!allowed || identity == null) tracker.reset()
        val passed = allowed && identity != null && saved == identity
        return State(passed, if (passed) 0 else tracker.remainingSeconds(SystemClock.elapsedRealtime()))
    }

    /** Called on the foreground service's existing health worker, never on the video callback. */
    @Synchronized fun poll(context: Context) {
        val state = snapshot(context)
        if (state.passed || trackedBuild == null ||
            !BrowserBackgroundReadiness.snapshot(context).allowed) {
            tracker.reset()
            return
        }
        val power = context.getSystemService(PowerManager::class.java)
        val output = DiPlaySessionService.currentBrowserOutput()
        val now = SystemClock.elapsedRealtime()
        val observing = tracker.observing
        val complete = tracker.sample(now, power?.isInteractive == false,
            output?.running == true, CarPlayBackgroundSession.active,
            (output?.clientCount ?: 0) > 0,
            if (output?.hasRenderedVideo == true) output.lastRenderedAtElapsedMillis else -1L)
        if (!observing && tracker.observing) BrowserReliabilityDiagnostics.record(context, "screen_off_check_started")
        if (observing && !tracker.observing) BrowserReliabilityDiagnostics.record(context, "screen_off_check_interrupted")
        if (complete) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(PROOF, trackedBuild).apply()
            BrowserReliabilityDiagnostics.record(context, "screen_off_check_passed durationSeconds=30")
        }
    }

    @Synchronized fun stop() = tracker.reset()

    private fun buildIdentity(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$version:${info.versionName}:${Build.FINGERPRINT}"
    }.getOrNull()
}

/** Monotonic timing also rejects a suspended/delayed monitor and stale render acknowledgements. */
internal class BrowserScreenOffTracker {
    private var startedAt = -1L
    private var lastPollAt = -1L
    val observing: Boolean get() = startedAt >= 0

    fun sample(now: Long, screenOff: Boolean, serverRunning: Boolean, iphoneActive: Boolean,
        viewerConnected: Boolean, lastRenderedAt: Long): Boolean {
        val freshRender = lastRenderedAt >= 0 && now - lastRenderedAt in 0..MAX_GAP_MILLIS
        if (!screenOff || !serverRunning || !iphoneActive || !viewerConnected || !freshRender) {
            reset()
            return false
        }
        if (startedAt < 0 || lastPollAt < 0 || now - lastPollAt !in 0..MAX_GAP_MILLIS) startedAt = now
        lastPollAt = now
        return now - startedAt >= DURATION_SECONDS * 1_000L
    }

    fun remainingSeconds(now: Long): Int {
        if (startedAt < 0 || lastPollAt < 0 || now - lastPollAt !in 0..MAX_GAP_MILLIS) return DURATION_SECONDS
        return ((DURATION_SECONDS * 1_000L - (now - startedAt)).coerceAtLeast(0) + 999L).div(1_000L).toInt()
    }

    fun reset() { startedAt = -1L; lastPollAt = -1L }

    companion object {
        const val DURATION_SECONDS = 30
        private const val MAX_GAP_MILLIS = 5_000L
    }
}
