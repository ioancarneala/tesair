package com.shilapi.xcertplay

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserTelemetrySnapshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: BrowserTelemetryEngine

    @Before fun prepare() {
        app.getSharedPreferences("browser_telemetry", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("browser_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.noBackupFilesDir, "telemetry").deleteRecursively()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "")
        AirPlayPersistence.saveExistingWifiCredentials(app, "", "")
        shadowOf(app).denyPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        ShadowVpnService.setPrepareResult(Intent("test.vpn.approval"))
        engine = newEngine()
    }

    @After fun finish() { engine.shutdown() }

    private fun newEngine() = BrowserTelemetryEngine(app, object : BrowserTelemetryTransport {
        override fun request(method: String, path: String, token: String?, body: String?): Int =
            throw AssertionError("An offline snapshot must never upload")
    }, online = { false })

    @Test fun consentCapturesActualMissingSetupAndPermissionStateWithoutNetworkDetails() {
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val all = events()
        val required = WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL)
        assertEquals(listOf("consent_granted", "app_opened", "configuration_saved") +
            List(3 + required.size) { "permission_result" }, all.map { it.getString("name") })
        assertTrue(all.all { it.getJSONObject("attributes").getString("androidVersion") == Build.VERSION.RELEASE })
        val configuration = all.single { it.getString("name") == "configuration_saved" }.getJSONObject("attributes")
        assertFalse(configuration.getBoolean("hotspotConfigured"))
        assertFalse(configuration.getBoolean("setupCompleted"))
        assertEquals(mapOf("wireless" to "not_granted", "background" to "not_granted", "local_route" to "not_granted"),
            permissionResults(all))
        assertEquals(required.associateWith { "not_granted" }, individualPermissionResults(all))
    }

    @Test fun configuredUpgradeReportsVerifiedGrantsAndCompletedSetupWithoutRepeatingOnboarding() {
        allowApprovals()
        AirPlayPersistence.saveManualHotspotSsid(app, "TesAir-Snapshot-Test")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "snapshot-pass-123")
        BrowserOnboardingProgress.confirmAudio(app)
        assertTrue(BrowserOnboardingProgress.finish(app, iphoneActive = true, videoRendered = true))
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val all = events()
        val configuration = all.single { it.getString("name") == "configuration_saved" }.getJSONObject("attributes")
        assertTrue(configuration.getBoolean("hotspotConfigured"))
        assertTrue(configuration.getBoolean("setupCompleted"))
        assertEquals(mapOf("wireless" to "allowed", "background" to "allowed", "local_route" to "allowed"),
            permissionResults(all))
        assertEquals(WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL)
            .associateWith { "allowed" }, individualPermissionResults(all))
        assertFalse(JSONArray(all).toString().contains("TesAir-Snapshot-Test"))
        assertFalse(JSONArray(all).toString().contains("snapshot-pass-123"))
        assertTrue(all.none { it.getString("name").startsWith("onboarding_step_") })
    }

    @Test fun configurationSnapshotValidatesCredentialsForTheActiveWirelessMode() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "TesAir-Existing-Test", "existing-pass-123")
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val configuration = events().single { it.getString("name") == "configuration_saved" }.getJSONObject("attributes")
        assertTrue(configuration.getBoolean("hotspotConfigured"))
        assertEquals(setOf(Manifest.permission.BLUETOOTH_CONNECT), individualPermissionResults(events()).keys)
        assertFalse(JSONArray(events()).toString().contains("TesAir-Existing-Test"))
        assertFalse(JSONArray(events()).toString().contains("existing-pass-123"))
    }

    @Test fun reopenedAppRechecksRevokedPermissionsInsteadOfReplayingTheCompletedFlag() {
        allowApprovals()
        BrowserOnboardingProgress.confirmAudio(app)
        assertTrue(BrowserOnboardingProgress.finish(app, iphoneActive = true, videoRendered = true))
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        engine.shutdown()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        engine = newEngine()
        engine.record("app_opened")
        assertTrue(engine.awaitIdle())
        val all = events()
        val latest = all.last { it.getString("name") == "configuration_saved" }.getJSONObject("attributes")
        assertFalse(latest.getBoolean("setupCompleted"))
        assertEquals("not_granted", permissionResults(all)["background"])
        assertEquals("allowed", permissionResults(all)["wireless"])
        assertEquals("allowed", permissionResults(all)["local_route"])
    }

    @Test fun managedHotspotSnapshotDistinguishesBluetoothAccessFromAMissingLocationGrant() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        shadowOf(app).denyPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT,
            WirelessHotspotMode.WIFI_P2P).toTypedArray())
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val all = events()
        assertEquals("not_granted", permissionResults(all)["wireless"])
        assertEquals(mapOf(Manifest.permission.BLUETOOTH_CONNECT to "allowed",
            Manifest.permission.ACCESS_COARSE_LOCATION to "allowed", Manifest.permission.ACCESS_FINE_LOCATION to "not_granted"),
            individualPermissionResults(all))
    }

    @Test @Config(sdk = [33]) fun android13WifiDirectSnapshotIdentifiesMissingNearbyWifiPermissionWithoutLocationEvents() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        shadowOf(app).denyPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT,
            WirelessHotspotMode.WIFI_P2P).toTypedArray())
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())
        val all = events()
        assertEquals("not_granted", permissionResults(all)["wireless"])
        assertEquals(mapOf(Manifest.permission.BLUETOOTH_CONNECT to "allowed",
            Manifest.permission.NEARBY_WIFI_DEVICES to "not_granted"), individualPermissionResults(all))
    }

    @Test @Config(sdk = [28, 29, 30]) fun legacyManualHotspotSnapshotDoesNotReportUnrequestedRadioGrants() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())

        assertEquals("allowed", permissionResults(events())["wireless"])
        assertTrue(individualPermissionResults(events()).isEmpty())
    }

    @Test @Config(sdk = [31, 33]) fun manualHotspotSnapshotRequiresBluetoothWithoutWifiDiscoveryGrants() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        engine.setEnabled(true)
        assertTrue(engine.awaitIdle())

        assertEquals("allowed", permissionResults(events())["wireless"])
        assertEquals(mapOf(Manifest.permission.BLUETOOTH_CONNECT to "allowed"), individualPermissionResults(events()))
    }

    private fun allowApprovals() {
        shadowOf(app).grantPermissions(*WirelessPermissions.required(Build.VERSION.SDK_INT, WirelessHotspotMode.MANUAL).toTypedArray())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(false)
        ShadowVpnService.setPrepareResult(null)
    }

    private fun events(): List<JSONObject> {
        val queued = JSONArray(File(app.noBackupFilesDir, "telemetry/events.json").readText())
        return (0 until queued.length()).map { queued.getJSONObject(it) }
    }

    private fun permissionResults(events: List<JSONObject>) = results(events).filterKeys { !it.startsWith("android.permission.") }
    private fun individualPermissionResults(events: List<JSONObject>) = results(events).filterKeys { it.startsWith("android.permission.") }
    private fun results(events: List<JSONObject>) = events.filter { it.getString("name") == "permission_result" }
        .associate { event ->
            val attributes = event.getJSONObject("attributes")
            assertEquals("snapshot", attributes.getString("source"))
            attributes.getString("permission") to attributes.getString("result")
        }
}
