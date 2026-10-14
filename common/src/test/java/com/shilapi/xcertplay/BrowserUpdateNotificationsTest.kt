package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w393dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserUpdateNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private var lifecycle: ActivityController<BrowserUpdateActivity>? = null
    private var checks = 0

    @Before fun prepare() {
        app.getSharedPreferences("browser_updates", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
        manager.cancelAll()
    }

    @After fun finish() {
        lifecycle?.pause()?.stop()?.destroy()
        app.getSharedPreferences("browser_updates", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
    }

    @Test @Config(sdk = [32]) fun olderAndroidUsesItsExistingNotificationSettingWithoutRuntimePrompt() {
        val screen = open()
        assertEquals(screen.getString(R.string.browser_update_alerts_enabled), alerts(screen).text)
        assertFalse(alerts(screen).isEnabled)
        assertNull(shadowOf(screen).lastRequestedPermission)
        assertEquals(1, checks)
    }

    @Test @Config(sdk = [32]) fun disabledNotificationsOnOlderAndroidOpenThePublicSettingsScreen() {
        shadowOf(manager).setNotificationsEnabled(false)
        val screen = open()
        assertEquals(screen.getString(R.string.browser_update_alerts_settings), alerts(screen).text)
        alerts(screen).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, request.intent.action)
        assertEquals(screen.packageName, request.intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(shadowOf(screen).lastRequestedPermission)
    }

    @Test fun freshAndroid13WaitsForATapAndDenialLeavesUpdateChecksAvailable() {
        val screen = open()
        assertNull(shadowOf(screen).lastRequestedPermission)
        assertEquals(screen.getString(R.string.browser_update_alerts_enable), alerts(screen).text)
        alerts(screen).performClick()
        permissionResult(screen, PackageManager.PERMISSION_DENIED)

        assertEquals(screen.getString(R.string.browser_update_alerts_settings), alerts(screen).text)
        assertTrue(alerts(screen).isEnabled)
        assertTrue(button(screen, R.string.browser_update_check).isEnabled)
        alerts(screen).performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            shadowOf(screen).nextStartedActivityForResult.intent.action)
        assertEquals(1, checks)
    }

    @Test fun successfulDialogResultCannotInventAMissingSystemGrant() {
        val screen = open()
        alerts(screen).performClick()
        permissionResult(screen, PackageManager.PERMISSION_GRANTED)
        assertEquals(screen.getString(R.string.browser_update_alerts_settings), alerts(screen).text)
        assertTrue(alerts(screen).isEnabled)
        assertFalse(BrowserUpdates.notificationsEnabled(screen))
    }

    @Test fun actualGrantIsConfirmedAndRevocationIsDetectedOnResumeWithoutAnotherNetworkCheck() {
        val screen = open()
        alerts(screen).performClick()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        permissionResult(screen, PackageManager.PERMISSION_GRANTED)
        assertEquals(screen.getString(R.string.browser_update_alerts_enabled), alerts(screen).text)
        assertFalse(alerts(screen).isEnabled)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        lifecycle!!.pause().resume()
        assertEquals(screen.getString(R.string.browser_update_alerts_settings), alerts(screen).text)
        assertTrue(alerts(screen).isEnabled)
        assertEquals(1, checks)
    }

    @Test fun returningFromSettingsOnlyConfirmsRealNotificationState() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        val screen = open()
        alerts(screen).performClick()
        val request = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        assertTrue(alerts(screen).isEnabled)
        assertFalse(BrowserUpdates.notificationsEnabled(screen))

        shadowOf(manager).setNotificationsEnabled(true)
        lifecycle!!.pause().resume()
        assertEquals(screen.getString(R.string.browser_update_alerts_enabled), alerts(screen).text)
        assertFalse(alerts(screen).isEnabled)
    }

    @Test fun blockedUpdateChannelIsNotPresentedAsEnabled() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel("tesair_updates", "Updates", NotificationManager.IMPORTANCE_NONE))
        val screen = open()
        assertEquals(screen.getString(R.string.browser_update_alerts_settings), alerts(screen).text)
        assertTrue(alerts(screen).isEnabled)
        assertFalse(BrowserUpdates.notificationsEnabled(screen))
    }

    @Test fun grantingNotificationsCanDeliverAnAlreadyCachedUpdateOnceWithoutInternet() {
        val packages = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getPackageName() = BrowserUpdatePolicy.PACKAGE
            override fun getPackageManager() = packages
        }
        `when`(packages.getPackageInfo(BrowserUpdatePolicy.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES))
            .thenReturn(PackageInfo().apply { longVersionCode = 66 })
        val update = BrowserUpdateInfo("1.0.5", 67,
            "https://github.com/ioancarneala/tesair/releases/download/v1.0.5/TesAir-v1.0.5.apk", "11".repeat(32))
        app.getSharedPreferences("browser_updates", Context.MODE_PRIVATE).edit()
            .putString("available", BrowserUpdatePolicy.json(update).toString()).commit()

        BrowserUpdates.notifyCached(context)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BrowserUpdates.notifyCached(context)
        BrowserUpdates.notifyCached(context)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    private fun open(): BrowserUpdateActivity {
        lifecycle = Robolectric.buildActivity(BrowserUpdateActivity::class.java)
        val screen = lifecycle!!.get()
        screen.updateChecker = { _, result -> checks++; result(BrowserUpdateState()) }
        lifecycle!!.create().start().resume().visible()
        return screen
    }

    private fun permissionResult(screen: BrowserUpdateActivity, result: Int) {
        val request = shadowOf(screen).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        screen.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun alerts(screen: BrowserUpdateActivity) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.tag == R.string.browser_update_alerts_enable }
    private fun button(screen: BrowserUpdateActivity, label: Int) = descendants(screen.window.decorView)
        .filterIsInstance<Button>().single { it.text == screen.getString(label) }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
