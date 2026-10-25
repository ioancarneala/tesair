package com.shilapi.xcertplay

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserStandbyReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val browser get() = object : ContextWrapper(app) {
        override fun getPackageName() = "com.shihab.diplay.browsertest"
    }
    private val manager get() = browser.getSystemService(AlarmManager::class.java)
    private val receiver = BrowserStandbyReceiver()

    @Before fun prepare() {
        cleanup()
        DiPlayPreferences.savePhone(browser, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessEnabled(browser, true)
        AirPlayPersistence.saveWirelessHotspotMode(browser, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(browser, "Test hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(browser, "test-passphrase")
        shadowOf(app).grantPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT,
            WirelessHotspotMode.MANUAL).toTypedArray())
        assertTrue(BrowserSessionRecovery.configured(browser))
        assertFalse(BrowserSessionRecovery.canRecover(browser))
    }

    @After fun cleanup() {
        BrowserStandbyAlarm.cancel(browser)
        val token = ReflectionHelpers.getField<String?>(BrowserStandbyAlarm, "handoffToken")
        BrowserStandbyAlarm.releaseHandoff(token)
        assertFalse(ReflectionHelpers.getField<PowerManager.WakeLock?>(BrowserStandbyAlarm, "handoff")?.isHeld == true)
        ReflectionHelpers.setField(BrowserStandbyAlarm, "handoff", null)
        listOf("diplay", "browser_session_recovery", "xcertplay_airplay").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        shadowOf(app).clearStartedServices()
    }

    @Test fun configuredButNeverStartedReceiverDoesNotStartFromAnAlarm() {
        receiver.onReceive(browser, probe("never-started"))

        assertNull(shadowOf(app).nextStartedService)
        assertNull(ReflectionHelpers.getField<PowerManager.WakeLock?>(BrowserStandbyAlarm, "handoff"))
        assertFalse(BrowserSessionRecovery.requested(browser))
    }

    @Test fun stoppedReceiverRejectsAnAlreadyDeliveredAlarm() {
        BrowserSessionRecovery.markStarted(browser)
        val delivered = probe("before-stop")
        BrowserSessionRecovery.markStopped(browser)

        receiver.onReceive(browser, delivered)

        assertNull(shadowOf(app).nextStartedService)
        assertFalse(BrowserSessionRecovery.requested(browser))
        assertTrue(BrowserSessionRecovery.stopped(browser))
        assertNull(ReflectionHelpers.getField<PowerManager.WakeLock?>(BrowserStandbyAlarm, "handoff"))
    }

    @Test fun unrelatedActionOrMissingTokenCannotStartARequestedReceiver() {
        BrowserSessionRecovery.markStarted(browser)

        receiver.onReceive(browser, probe("wrong-action").setAction("co.tesair.UNRELATED"))
        receiver.onReceive(browser, Intent(BrowserStandbyAlarm.ACTION))

        assertNull(shadowOf(app).nextStartedService)
        assertNull(ReflectionHelpers.getField<PowerManager.WakeLock?>(BrowserStandbyAlarm, "handoff"))
    }

    @Test fun validAlarmHoldsCpuUntilTheServiceAcceptsTheMatchingToken() {
        BrowserSessionRecovery.markStarted(browser)

        receiver.onReceive(browser, probe("current-standby"))

        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(DiPlaySessionService::class.java.name, started.component?.className)
        assertEquals(DiPlaySessionService.ACTION_STANDBY_PROBE, started.action)
        assertEquals("current-standby", started.getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN))
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertFalse(shadowOf(lock).isReferenceCounted)

        BrowserStandbyAlarm.releaseHandoff("current-standby")

        assertFalse(lock.isHeld)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun fallbackUsesOneInexactWakeupAlarmWithoutExactAlarmAccessAndCanBeCancelled() {
        shadowOf(app).denyPermissions(Manifest.permission.SCHEDULE_EXACT_ALARM)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(manager.canScheduleExactAlarms())
        val before = SystemClock.elapsedRealtime()

        assertTrue(BrowserStandbyAlarm.schedule(browser, "first"))
        assertTrue(BrowserStandbyAlarm.schedule(browser, "latest"))

        val alarms = shadowOf(manager).scheduledAlarms
        assertEquals(1, alarms.size)
        val alarm = alarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue(alarm.isAllowWhileIdle)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertEquals(0L, alarm.intervalMs)
        assertTrue(alarm.triggerAtMs >= before + 15 * 60_000L)
        val pending = shadowOf(alarm.operation)
        assertTrue(pending.isBroadcast)
        assertTrue(pending.isImmutable)
        assertEquals(BrowserStandbyReceiver::class.java.name, pending.savedIntent.component?.className)
        assertEquals(BrowserStandbyAlarm.ACTION, pending.savedIntent.action)
        assertEquals("latest", pending.savedIntent.getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN))

        BrowserStandbyAlarm.cancel(browser)

        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertTrue(pending.isCanceled)
    }

    @Test fun rejectedForegroundStartReleasesHandoffAndSchedulesAnotherBoundedProbe() {
        BrowserSessionRecovery.markStarted(browser)
        val rejecting = object : ContextWrapper(browser) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("Background service start rejected")
            }
        }

        receiver.onReceive(rejecting, probe("retry-after-rejection"))

        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertNull(shadowOf(app).nextStartedService)
        val alarm = shadowOf(manager).scheduledAlarms.single()
        assertEquals("retry-after-rejection", shadowOf(alarm.operation).savedIntent
            .getStringExtra(BrowserStandbyAlarm.EXTRA_TOKEN))
        assertTrue(BrowserSessionRecovery.canRecover(browser))
    }

    @Test fun staleOrMissingHandoffTokenCannotReleaseANewerWakeLease() {
        BrowserStandbyAlarm.acquireHandoff(browser, "old")
        val lock = ShadowPowerManager.getLatestWakeLock()
        BrowserStandbyAlarm.acquireHandoff(browser, "new")

        BrowserStandbyAlarm.releaseHandoff("old")
        BrowserStandbyAlarm.releaseHandoff(null)

        assertTrue(lock.isHeld)
        BrowserStandbyAlarm.releaseHandoff("new")
        assertFalse(lock.isHeld)
    }

    private fun probe(token: String) = Intent(browser, BrowserStandbyReceiver::class.java)
        .setAction(BrowserStandbyAlarm.ACTION)
        .putExtra(BrowserStandbyAlarm.EXTRA_TOKEN, token)
}
