package com.shilapi.xcertplay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.PowerManager

/** A fallback probe; power and real connection events remain the prompt wake paths. */
class BrowserStandbyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BrowserStandbyAlarm.ACTION || !BrowserSessionRecovery.canRecover(context)) return
        val token = intent.getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN) ?: return
        BrowserStandbyAlarm.acquireHandoff(context, token)
        runCatching {
            context.startForegroundService(Intent(context, DiPlaySessionService::class.java)
                .setAction(DiPlaySessionService.ACTION_STANDBY_PROBE)
                .putExtra(BrowserStandbyAlarm.EXTRA_TOKEN, token))
        }.onFailure {
            BrowserStandbyAlarm.releaseHandoff(token)
            BrowserReliabilityDiagnostics.record(context, "standby_probe_start_failed class=${it.javaClass.simpleName}")
            if (BrowserSessionRecovery.canRecover(context)) BrowserStandbyAlarm.schedule(context, token)
        }
    }
}

internal object BrowserStandbyAlarm {
    const val ACTION = "co.tesair.STANDBY_PROBE"
    const val EXTRA_TOKEN = "standby_token"
    private const val REQUEST_CODE = 52
    private const val PROBE_INTERVAL_MILLIS = 15 * 60_000L
    private var handoff: PowerManager.WakeLock? = null
    private var handoffToken: String? = null

    @Synchronized fun acquireHandoff(context: Context, token: String) {
        val lock = handoff ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TesAir:standby-handoff")
            .apply { setReferenceCounted(false) }.also { handoff = it }
        handoffToken = token
        // Cover delivery to onStartCommand; an OS launch failure must not leave a permanent lock.
        lock.acquire(30_000L)
    }

    @Synchronized fun releaseHandoff(token: String?) {
        if (token == null || token != handoffToken) return
        if (handoff?.isHeld == true) handoff?.release()
        handoffToken = null
    }

    fun schedule(context: Context, token: String): Boolean = runCatching {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, BrowserStandbyReceiver::class.java).setAction(ACTION).putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact by design: no exact-alarm permission, and Android may defer this fallback.
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + PROBE_INTERVAL_MILLIS, pending)
        true
    }.getOrDefault(false)

    fun cancel(context: Context) {
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, BrowserStandbyReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}
