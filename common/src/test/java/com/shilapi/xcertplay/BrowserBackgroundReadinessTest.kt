package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class BrowserBackgroundReadinessTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun actualGrantAndRestrictionAreBothRequired() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val activity = shadowOf(context.getSystemService(ActivityManager::class.java))
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        activity.setBackgroundRestricted(false)
        assertFalse(BrowserBackgroundReadiness.snapshot(context).allowed)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            BrowserBackgroundReadiness.settingsIntent(context).action)

        power.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BrowserBackgroundReadiness.snapshot(context).allowed)
        activity.setBackgroundRestricted(true)
        assertFalse(BrowserBackgroundReadiness.snapshot(context).allowed)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            BrowserBackgroundReadiness.settingsIntent(context).action)

        activity.setBackgroundRestricted(false)
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse("Revocation must be seen immediately", BrowserBackgroundReadiness.snapshot(context).allowed)
    }

    @Test fun unknownRestrictionNeverClaimsReadiness() {
        assertFalse(BrowserBackgroundReadiness.State(true, null).allowed)
        assertFalse(BrowserBackgroundReadiness.State(false, false).allowed)
        assertFalse(BrowserBackgroundReadiness.State(true, true).allowed)
        assertTrue(BrowserBackgroundReadiness.State(true, false).allowed)
    }

    @Test fun xiaomiRequestTargetsAndroidConsentInsteadOfThePowerkeeperOverride() {
        ShadowBuild.setManufacturer("Xiaomi")
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        val request = BrowserBackgroundReadiness.settingsIntent(context)
        assertEquals("com.android.settings", request.`package`)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.action)
        assertEquals("package:${context.packageName}", request.dataString)
    }

    @Test fun standardRequestIsSharedAcrossManufacturers() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        for (manufacturer in listOf("Samsung", "OnePlus", "OPPO", "realme", "Huawei", "HONOR", "vivo", "Google", "motorola", "unknown")) {
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setBrand(manufacturer)
            val request = BrowserBackgroundReadiness.settingsIntent(context)
            assertEquals(manufacturer, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.action)
            assertNull(manufacturer, request.`package`)
        }
    }

    @Test fun unavailableOemConsentFallsBackWithoutInventingAGrant() {
        ShadowBuild.setManufacturer("Xiaomi")
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        val launched = mutableListOf<Intent>()
        val host = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                launched.add(intent)
                if (intent.`package` != null) throw ActivityNotFoundException()
            }
        }
        assertTrue(BrowserBackgroundReadiness.request(host))
        assertEquals(2, launched.size)
        assertEquals("com.android.settings", launched[0].`package`)
        assertNull(launched[1].`package`)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, launched[1].action)
        assertFalse(BrowserBackgroundReadiness.snapshot(host).allowed)
    }

    @Test fun missingOrProtectedHandlersReachPublicAppSettingsAndReportFailure() {
        ShadowBuild.setManufacturer("Samsung")
        ShadowBuild.setBrand("Samsung")
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        val launched = mutableListOf<Intent>()
        val host = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                launched.add(intent)
                if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) throw SecurityException()
                throw ActivityNotFoundException()
            }
        }
        assertFalse(BrowserBackgroundReadiness.request(host))
        assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            launched.map { it.action })
        assertTrue(launched.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        assertFalse(BrowserBackgroundReadiness.snapshot(host).allowed)
    }

    @Test fun verifiedApprovalDoesNotOpenAnUnnecessaryPrompt() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        val host = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { fail("Already approved") }
        }
        assertTrue(BrowserBackgroundReadiness.request(host))
    }
}
