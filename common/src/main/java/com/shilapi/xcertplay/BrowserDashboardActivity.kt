package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.browser.TlsIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Setup and status only. The foreground service owns the receiver and survives this screen. */
class BrowserDashboardActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dashboard: BrowserDashboardView
    private var awaitingRouteConsent = false
    private var awaitingPermissions = false
    private var permissionDenied = false
    private var reconnectAfterPermissions = false
    private var serviceFailure: String? = null
    private var languagePreference = AppLocale.SYSTEM
    private var settingsRepair: SettingsRepair? = null
    private var telemetryConsent: AlertDialog? = null

    private enum class SettingsRepair { HOTSPOT, BLUETOOTH, VPN }

    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingPermissions = false
            permissionDenied = !hasWirelessPermissions()
            if (!permissionDenied) startReceiver(reconnectAfterPermissions && !CarPlayBackgroundSession.active)
            updateStatus()
        }
    private val routeConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingRouteConsent = false
            if (result.resultCode == RESULT_OK) BrowserAddressService.enable(this)
            updateStatus()
        }
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Browser_Onboarding)
        super.onCreate(savedInstanceState)
        awaitingPermissions = savedInstanceState?.getBoolean("awaiting_wireless_permissions") == true
        permissionDenied = savedInstanceState?.getBoolean("wireless_permission_denied") == true
        reconnectAfterPermissions = savedInstanceState?.getBoolean("reconnect_after_permissions") == true
        settingsRepair = savedInstanceState?.getString("settings_repair")?.let {
            runCatching { SettingsRepair.valueOf(it) }.getOrNull()
        }
        languagePreference = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val colors = BrowserDesign.onboarding(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        dashboard = BrowserDashboardView(this,
            updateApp = { BrowserUpdates.show(this) },
            allowBackground = ::allowBackground,
            enableRoute = ::requestBrowserAddress,
            toggleRoute = {
                val route = BrowserAddressService.snapshot(this)
                if (route.enabled || route.active) BrowserAddressService.disable(this)
                else requestBrowserAddress()
                updateStatus()
            },
            retry = { requestReceiver(reconnect = true) },
            settings = { startActivity(Intent(this, BrowserSettingsActivity::class.java)) },
            reviewSetup = {
                if (!hasWirelessPermissions()) requestReceiver(reconnect = true)
                else openGuide()
            },
            allowConnection = { requestReceiver(reconnect = !CarPlayBackgroundSession.active) },
            openHotspot = ::openHotspotSettings,
            openBluetooth = ::openBluetoothSettings,
            openVpn = ::openVpnSettings,
            openInternet = ::openInternetSettings,
            connectIphone = ::openIphoneGuide,
        )
        setContentView(dashboard)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        requestReceiver(savedInstanceState == null && intent.getBooleanExtra(EXTRA_RECONNECT, false))
        intent.removeExtra(EXTRA_RECONNECT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestReceiver(intent.getBooleanExtra(EXTRA_RECONNECT, false))
        intent.removeExtra(EXTRA_RECONNECT)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        BrowserUpdates.check(this) {
            if (!isFinishing && !isDestroyed) dashboard.showUpdate(BrowserUpdates.cached(this) != null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting_wireless_permissions", awaitingPermissions)
        outState.putBoolean("wireless_permission_denied", permissionDenied)
        outState.putBoolean("reconnect_after_permissions", reconnectAfterPermissions)
        outState.putString("settings_repair", settingsRepair?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        BrowserTelemetry.pump(this)
        if (BrowserTelemetry.choice(this) == "unset" && telemetryConsent?.isShowing != true) {
            telemetryConsent = BrowserTelemetryConsent.show(this) {
                BrowserTelemetry.record(this, "app_opened", mapOf("source" to "dashboard"))
            }
        } else BrowserTelemetry.record(this, "app_opened", mapOf("source" to "dashboard"))
        if (Build.VERSION.SDK_INT < 33 && languagePreference != AppLocale.preference(this)) {
            recreate()
            return
        }
        if (permissionDenied && hasWirelessPermissions()) {
            permissionDenied = false
            startReceiver(reconnectAfterPermissions && !CarPlayBackgroundSession.active)
        }
        resumeAfterSettings()
        DiPlaySessionService.currentBrowserRuntime()?.refreshAppearance()
        updateStatus()
    }

    override fun onPause() {
        BrowserTelemetry.record(this, "app_backgrounded", mapOf("source" to "dashboard"))
        dashboard.concealPassword()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        telemetryConsent?.dismiss()
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun requiredPermissions() = WirelessPermissions.required(
        Build.VERSION.SDK_INT, AirPlayPersistence.loadWirelessHotspotMode(this))

    private fun hasWirelessPermissions() = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestReceiver(reconnect: Boolean = false) {
        if (awaitingPermissions) {
            reconnectAfterPermissions = reconnectAfterPermissions || reconnect
            return
        }
        if (hasWirelessPermissions()) {
            permissionDenied = false
            startReceiver(reconnect)
        } else {
            reconnectAfterPermissions = reconnect
            awaitingPermissions = true
            wirelessPermissions.launch(requiredPermissions().toTypedArray())
        }
        updateStatus()
    }

    private fun startReceiver(reconnect: Boolean) {
        if (reconnect) BrowserTelemetry.record(this, "receiver_start_requested", mapOf("automatic" to false, "manualIntervention" to true, "source" to "dashboard"))
        serviceFailure = null
        reconnectAfterPermissions = false
        try {
            startForegroundService(Intent(this, DiPlaySessionService::class.java).apply {
                action = if (reconnect) DiPlaySessionService.ACTION_RECONNECT else DiPlaySessionService.ACTION_RECOVER
            })
        } catch (error: RuntimeException) {
            serviceFailure = error.javaClass.simpleName
            BrowserReliabilityDiagnostics.record(this, "dashboard_start_failed class=${error.javaClass.simpleName}")
        }
    }

    private fun allowBackground() {
        BrowserTelemetry.record(this, "permission_requested", mapOf("permission" to "background"))
        if (!BrowserBackgroundReadiness.request(this)) {
            AlertDialog.Builder(this).setMessage(R.string.browser_battery_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun requestBrowserAddress() {
        if (awaitingRouteConsent) return
        if (BrowserAddressService.hasOtherVpn(this)) {
            AlertDialog.Builder(this).setMessage(R.string.browser_address_other_vpn)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent == null) BrowserAddressService.enable(this)
        else {
            awaitingRouteConsent = true
            BrowserTelemetry.record(this, "permission_requested", mapOf("permission" to "local_route"))
            routeConsent.launch(consent)
        }
        updateStatus()
    }

    private fun openGuide() {
        startActivity(Intent(this, BrowserOnboardingActivity::class.java)
            .putExtra(BrowserOnboardingActivity.EXTRA_REVIEW, true))
    }

    private fun openIphoneGuide() {
        startActivity(Intent(this, BrowserOnboardingActivity::class.java)
            .putExtra(BrowserOnboardingActivity.EXTRA_STEP, BrowserOnboardingProgress.Step.IPHONE.name))
    }

    private fun openHotspotSettings() = openSystemSettings(SettingsRepair.HOTSPOT,
        "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)

    private fun openBluetoothSettings() = openSystemSettings(SettingsRepair.BLUETOOTH,
        Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun openVpnSettings() = openSystemSettings(SettingsRepair.VPN, Settings.ACTION_VPN_SETTINGS)

    private fun openInternetSettings() {
        if (Build.VERSION.SDK_INT >= 29) openSystemSettings(null,
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
        else openSystemSettings(null, Settings.ACTION_WIRELESS_SETTINGS)
    }

    private fun openSystemSettings(repair: SettingsRepair?, vararg actions: String) {
        for (action in actions.toList() + Settings.ACTION_SETTINGS) {
            settingsRepair = repair
            try {
                startActivity(Intent(action))
                return
            } catch (_: RuntimeException) {
                settingsRepair = null
            }
        }
    }

    private fun resumeAfterSettings() {
        val repair = settingsRepair ?: return
        settingsRepair = null
        if (repair == SettingsRepair.VPN) {
            if (!BrowserAddressService.snapshot(this).active && !BrowserAddressService.hasOtherVpn(this)) {
                requestBrowserAddress()
            }
            return
        }
        // Ordinary Bluetooth recovery already retries automatically. Only a terminal hotspot
        // failure needs an explicit restart after the user has corrected its system settings.
        if (repair == SettingsRepair.HOTSPOT && !CarPlayBackgroundSession.active &&
            !BrowserSessionRecovery.stopped(this) &&
            DiPlaySessionService.currentBrowserRuntime()?.setupNeedsAttention == true &&
            hotspotNeedsAttention() && CarHotspotStatus.isEnabled(this) != false) {
            requestReceiver(reconnect = true)
        }
    }

    private fun hotspotNeedsAttention(): Boolean {
        if (CarPlayBackgroundSession.active ||
            AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return false
        if (CarHotspotStatus.isEnabled(this) == false) return true
        val failure = DiPlaySessionService.currentBrowserRuntime()?.status as? CarPlayStatus.Failed
        return failure?.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION ||
            failure?.startupFailure == WirelessStartupFailure.HOTSPOT_NOT_READY
    }

    private fun bluetoothRequired(): Boolean = !CarPlayBackgroundSession.active && hasWirelessPermissions() &&
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull() == false

    private fun updateStatus() {
        if (!::dashboard.isInitialized || isDestroyed) return
        val route = BrowserAddressService.snapshot(this)
        val output = DiPlaySessionService.currentBrowserOutput()
        val runtime = DiPlaySessionService.currentBrowserRuntime()
        val urls = output?.urls.orEmpty()
        val viewport = BrowserCarPlayViewport.load(this)
        val hotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val needsAttention = permissionDenied || runtime?.setupNeedsAttention == true
        dashboard.render(BrowserDashboardView.State(
            serverRunning = output?.running == true,
            sessionRunning = CarPlayBackgroundSession.hasSession(),
            iphoneActive = CarPlayBackgroundSession.active,
            browserConnected = (output?.clientCount ?: 0) > 0,
            videoRendered = output?.hasRenderedVideo == true,
            batteryAllowed = BrowserBackgroundReadiness.snapshot(this).allowed,
            routeActive = route.active,
            routeEnabled = route.enabled,
            routeBusy = route.busy || awaitingRouteConsent,
            wireless = true,
            routeMessage = getString(when {
                route.active -> R.string.browser_dashboard_route_ready
                route.busy || awaitingRouteConsent -> R.string.browser_address_starting
                route.phase == BrowserAddressService.Phase.OTHER_VPN -> R.string.browser_address_other_vpn
                route.phase == BrowserAddressService.Phase.ERROR -> R.string.browser_address_error
                route.phase == BrowserAddressService.Phase.REVOKED ||
                    route.phase == BrowserAddressService.Phase.PERMISSION_REQUIRED -> R.string.browser_address_permission
                else -> R.string.browser_address_off
            }),
            networkName = when (hotspotMode) {
                WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotSsid(this)
                WirelessHotspotMode.EXISTING_WIFI -> AirPlayPersistence.loadExistingWifiSsid(this)
                else -> ""
            },
            password = when (hotspotMode) {
                WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotPassphrase(this)
                WirelessHotspotMode.EXISTING_WIFI -> AirPlayPersistence.loadExistingWifiPassphrase(this)
                else -> ""
            },
            trustedUrl = urls.firstOrNull { it.startsWith("https://${TlsIdentity.TRUSTED_HOST}:") },
            secureAddressMessage = when (output?.certificateSyncState) {
                SharedCertificateSync.State.CHECKING -> getString(R.string.browser_https_preparing)
                SharedCertificateSync.State.RETRYING -> getString(R.string.browser_https_retry)
                SharedCertificateSync.State.ERROR -> getString(R.string.browser_https_unavailable)
                else -> null
            },
            diagnostic = listOfNotNull(runtime?.status?.javaClass?.simpleName, serviceFailure, output?.status,
                urls.joinToString("\n"),
                getString(R.string.browser_dashboard_viewport, viewport.first, viewport.second),
                getString(R.string.browser_dashboard_setup_attention_hint).takeIf { needsAttention })
                .joinToString("\n"),
            setupNeedsAttention = needsAttention,
            permissionsRequired = !hasWirelessPermissions(),
            hotspotNeedsAttention = hotspotNeedsAttention(),
            bluetoothRequired = bluetoothRequired(),
            otherVpn = !route.active && BrowserAddressService.hasOtherVpn(this),
            receiverFailed = serviceFailure != null || (BrowserSessionRecovery.stopped(this) &&
                output?.running != true && runtime == null),
            addressNeedsHelp = output?.certificateSyncState == SharedCertificateSync.State.RETRYING ||
                output?.certificateSyncState == SharedCertificateSync.State.ERROR,
            stopped = !BrowserSessionRecovery.requested(this) && !CarPlayBackgroundSession.hasSession(),
        ))
    }

    companion object {
        const val EXTRA_RECONNECT = "browser_reconnect"
    }
}
