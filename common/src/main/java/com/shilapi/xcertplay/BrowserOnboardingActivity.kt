package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shilapi.xcertplay.BrowserOnboardingProgress.Step
import com.shilapi.xcertplay.browser.BrowserAddressService
import com.shilapi.xcertplay.browser.SharedCertificateSync
import com.shilapi.xcertplay.browser.TlsIdentity
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** A resumable setup guide. Connection lifetime and retry remain owned by the service. */
class BrowserOnboardingActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val motion = BrowserMotion()
    private var step = Step.WELCOME
    private var contextualHelp = false
    private lateinit var content: LinearLayout
    private var status: TextView? = null
    private var address: TextView? = null
    private var next: Button? = null
    private var startCarPlay: Button? = null
    private var password: EditText? = null
    private var passwordToggle: Button? = null
    private var error: TextView? = null
    private var passwordDialog: AlertDialog? = null
    private var replaceNetwork = false
    private var permissionDenied = false
    private var awaitingRoute = false
    private var certificateSync: SharedCertificateSync? = null
    private var backgroundSignature = ""
    private var readyVerified = false
    private var startFailed = false
    private var viewedStep: Step? = null
    private var stepVisibleAt = 0L
    private var stepVisibleMs = 0L
    private var telemetryConsent: AlertDialog? = null
    private val draft by lazy { ViewModelProvider(this)[BrowserOnboardingDraft::class.java] }
    internal var certificateSyncFactory: (Context) -> SharedCertificateSync = {
        SharedCertificateSync(it, BuildConfig.BROWSER_CERTIFICATE_ENDPOINT,
            BuildConfig.BROWSER_CERTIFICATE_DOWNLOAD_TOKEN) { handler.post { updateLiveState() } }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !hasPermissions()
        BrowserTelemetry.record(this, "permission_result", mapOf("permission" to "wireless", "result" to if (permissionDenied) "denied" else "allowed"))
        render()
    }
    private val vpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingRoute = false
        if (it.resultCode == RESULT_OK) BrowserAddressService.enable(this)
        BrowserTelemetry.record(this, "permission_result", mapOf("permission" to "local_route", "result" to if (it.resultCode == RESULT_OK) "allowed" else "denied"))
        render()
    }
    private val refresh = object : Runnable {
        override fun run() {
            updateLiveState()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Browser_Onboarding)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val palette = BrowserDesign.onboarding(this)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        val requestedStep = (savedInstanceState?.getString(EXTRA_STEP) ?: intent.getStringExtra(EXTRA_STEP))
            ?.let { name -> Step.entries.firstOrNull { it.name == name } }
        contextualHelp = savedInstanceState?.getBoolean("contextual_help") ?: (requestedStep != null)
        permissionDenied = savedInstanceState?.getBoolean("connection_permission_denied") ?: false
        step = requestedStep ?: if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_REVIEW, false)) Step.NETWORK
            else BrowserOnboardingProgress.step(this)
        intent.removeExtra(EXTRA_REVIEW)
        intent.removeExtra(EXTRA_STEP)
        if (!contextualHelp) BrowserOnboardingProgress.moveTo(this, step)
        replaceNetwork = draft.replaceNetwork
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (contextualHelp || step == Step.WELCOME) finish() else move(Step.entries[step.ordinal - 1])
            }
        })
        render()
    }

    override fun onStart() {
        super.onStart()
        if (BrowserSessionRecovery.canRecover(this) && DiPlaySessionService.currentBrowserRuntime() == null) {
            try {
                startForegroundService(Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_RECOVER))
            } catch (_: RuntimeException) { startFailed = true }
        }
        if (DiPlaySessionService.currentBrowserOutput()?.running != true && certificateSync == null) {
            certificateSync = certificateSyncFactory(applicationContext).also { it.start() }
        }
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        stepVisibleAt = android.os.SystemClock.elapsedRealtime()
        BrowserTelemetry.record(this, "app_opened", mapOf("source" to "onboarding"))
        BrowserTelemetry.pump(this)
        recordReadiness()
        if (step.ordinal > Step.BACKGROUND.ordinal && !BrowserOnboardingProgress.approvalsAllowed(this)) {
            move(Step.BACKGROUND)
            return
        }
        if (::content.isInitialized && step == Step.BACKGROUND) render()
        updateLiveState()
    }

    override fun onPause() {
        stopVisibleTime()
        BrowserTelemetry.record(this, "app_backgrounded", mapOf("source" to "onboarding", "step" to step.name))
        concealPassword()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        motion.cancel()
        closeCertificateSync()
        super.onStop()
    }

    override fun onDestroy() {
        telemetryConsent?.dismiss()
        handler.removeCallbacks(refresh)
        closeCertificateSync()
        super.onDestroy()
    }

    private fun closeCertificateSync() {
        certificateSync?.close()
        certificateSync = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_STEP, step.name)
        outState.putBoolean("contextual_help", contextualHelp)
        outState.putBoolean("connection_permission_denied", permissionDenied)
    }

    private fun move(destination: Step) {
        stopVisibleTime()
        if (destination.ordinal > step.ordinal) BrowserTelemetry.record(this, "onboarding_step_completed",
            mapOf("step" to step.name, "targetStep" to destination.name,
                "direction" to "forward", "visibleDurationMs" to stepVisibleMs))
        stepVisibleMs = 0
        stepVisibleAt = android.os.SystemClock.elapsedRealtime()
        concealPassword()
        step = destination
        if (!contextualHelp) BrowserOnboardingProgress.moveTo(this, destination)
        render()
    }

    private fun render() {
        if (viewedStep != step) {
            viewedStep = step
            BrowserTelemetry.record(this, "onboarding_step_viewed", mapOf("step" to step.name, "onboardingRevision" to 1))
        }
        backgroundSignature = backgroundState()
        status = null
        address = null
        next = null
        startCarPlay = null
        password = null
        passwordToggle = null
        error = null
        val palette = BrowserDesign.onboarding(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
        }
        val frame = LinearLayout(this).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        frame.addView(content, LinearLayout.LayoutParams(
            if (resources.configuration.screenWidthDp > 600) dp(560) else -1, -2))
        scroll.addView(frame)
        setContentView(scroll)
        val top = BrowserBrand.header(this)
        if (step != Step.WELCOME) top.addView(BrowserDesign.setupButton(this, getString(R.string.onboarding_back)) {
            if (contextualHelp) finish() else move(Step.entries[step.ordinal - 1])
        })
        content.addView(top)
        if (step != Step.WELCOME) {
            val chapter = when (step) {
                Step.NETWORK, Step.BACKGROUND -> 1
                Step.IPHONE -> 2
                Step.TESLA_WIFI, Step.TESLA_BROWSER -> 3
                else -> 4
            }
            val name = listOf(R.string.onboarding_chapter_android, R.string.onboarding_chapter_iphone,
                R.string.onboarding_chapter_tesla, R.string.onboarding_chapter_finish)[chapter - 1]
            label(getString(R.string.onboarding_progress, chapter, 4, getString(name)), 13, top = 16)
            val track = LinearLayout(this)
            repeat(4) { index ->
                track.addView(View(this).apply { background = GradientDrawable().apply {
                    setColor(if (index < chapter) palette.accent else palette.line)
                    cornerRadius = dp(2).toFloat()
                } }, LinearLayout.LayoutParams(0, dp(3), 1f).apply { marginEnd = if (index < 3) dp(6) else 0 })
            }
            content.addView(track, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
        }
        when (step) {
            Step.WELCOME -> welcome()
            Step.NETWORK -> network()
            Step.BACKGROUND -> background()
            Step.IPHONE -> iphone()
            Step.TESLA_WIFI -> teslaWifi()
            Step.TESLA_BROWSER -> browser()
            Step.AUDIO -> audio()
            Step.READY -> ready()
        }
        updateLiveState()
        motion.update(content, step)
    }

    private fun stopVisibleTime() {
        if (stepVisibleAt > 0) stepVisibleMs += (android.os.SystemClock.elapsedRealtime() - stepVisibleAt).coerceAtLeast(0)
        stepVisibleAt = 0
    }

    private fun recordReadiness() {
        BrowserTelemetry.record(this, "permission_result", mapOf("permission" to "wireless", "source" to "snapshot", "result" to if (hasPermissions()) "allowed" else "not_granted"))
        BrowserTelemetry.record(this, "permission_result", mapOf("permission" to "background", "source" to "snapshot", "result" to if (batteryAllowed()) "allowed" else "not_granted"))
        BrowserTelemetry.record(this, "permission_result", mapOf("permission" to "local_route", "source" to "snapshot", "result" to if (BrowserOnboardingProgress.localAddressApproved(this)) "allowed" else "not_granted"))
    }

    private fun heading(title: Int, body: Int, scene: BrowserOnboardingArtView.Scene, device: Int? = null) {
        device?.let { label(getString(it), 12, true, top = 24) }
        label(getString(title), 32, true, top = if (device == null) 24 else 10).isAccessibilityHeading = true
        label(getString(body), 16, top = 10)
        content.addView(BrowserOnboardingArtView(this, scene).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(if (scene == BrowserOnboardingArtView.Scene.HOTSPOT) 104 else 188))
            .apply { topMargin = dp(16); bottomMargin = dp(8) })
    }

    private fun welcome() {
        heading(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body, BrowserOnboardingArtView.Scene.WELCOME)
        label(getString(R.string.onboarding_welcome_needs), 16)
        action(R.string.onboarding_begin, true) {
            if (BrowserTelemetry.choice(this) == "unset") {
                telemetryConsent = BrowserTelemetryConsent.show(this) {
                    BrowserTelemetry.record(this, "onboarding_step_viewed", mapOf("step" to Step.WELCOME.name, "onboardingRevision" to 1))
                    move(Step.NETWORK)
                }
            } else move(Step.NETWORK)
        }
    }

    private fun network() {
        heading(R.string.onboarding_network_title, R.string.onboarding_network_body,
            BrowserOnboardingArtView.Scene.HOTSPOT, R.string.onboarding_android_device)
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        if (mode != WirelessHotspotMode.MANUAL && !replaceNetwork && hasExistingNetwork()) {
            label(getString(R.string.onboarding_network_existing_body), 16)
            action(R.string.onboarding_network_existing, true) { move(Step.BACKGROUND) }
            action(R.string.onboarding_network_change) {
                replaceNetwork = true
                draft.replaceNetwork = true
                render()
            }
            return
        }
        action(R.string.onboarding_network_settings) { openHotspotSettings() }
        val name = input(R.string.onboarding_network_name, false).apply {
            setText(draft.name ?: AirPlayPersistence.loadManualHotspotSsid(this@BrowserOnboardingActivity))
            addTextChangedListener(onTextChanged { draft.name = it })
        }
        val secret = input(R.string.onboarding_network_password, true).apply {
            setText(draft.password ?: AirPlayPersistence.loadManualHotspotPassphrase(this@BrowserOnboardingActivity))
            addTextChangedListener(onTextChanged { draft.password = it })
        }
        password = secret
        content.removeView(secret)
        val passwordRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        passwordRow.addView(secret, LinearLayout.LayoutParams(0, -2, 1f))
        passwordToggle = BrowserDesign.setupButton(this, getString(R.string.onboarding_password_show_short)) {
            val hidden = secret.transformationMethod != null
            if (hidden) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            secret.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
            secret.setSelection(secret.text.length)
            passwordToggle?.setText(if (hidden) R.string.onboarding_password_hide_short else R.string.onboarding_password_show_short)
            passwordToggle?.contentDescription = getString(if (hidden) R.string.onboarding_hide_password else R.string.onboarding_show_password)
        }.apply {
            contentDescription = getString(R.string.onboarding_show_password)
            passwordRow.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        content.addView(passwordRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        label(getString(R.string.onboarding_network_exact_hint), 13)
        error = label("", 14).apply {
            setTextColor(BrowserDesign.onboarding(this@BrowserOnboardingActivity).error)
            visibility = View.GONE
        }
        action(R.string.onboarding_network_save, true) {
            val ssid = name.text.toString()
            val passphrase = secret.text.toString()
            val problem = ManualHotspotValidation.error(ssid, passphrase)
            if (problem != null) {
                BrowserTelemetry.record(this, "onboarding_step_blocked", mapOf("step" to step.name, "errorCode" to problem.name))
                val text = getString(problem.messageResource())
                error?.text = text
                error?.visibility = View.VISIBLE
                if (problem == ManualHotspotValidation.Error.PASSWORD_LENGTH) secret.error = text else name.error = text
            } else {
                saveNetwork(ssid, passphrase)
                draft.name = null
                draft.password = null
                move(Step.BACKGROUND)
            }
        }
        label(getString(R.string.onboarding_network_hint), 14)
    }

    private fun saveNetwork(ssid: String, secret: String) {
        BrowserTelemetry.record(this, "configuration_saved", mapOf("transport" to "manual_hotspot", "step" to step.name))
        val changed = AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL ||
            networkName() != ssid || networkPassword() != secret
        // Preserve imported WPA3 metadata if the user saves unchanged credentials.
        if (changed) {
            AirPlayPersistence.saveManualHotspotSsid(this, ssid)
            AirPlayPersistence.saveManualHotspotPassphrase(this, secret)
            AirPlayPersistence.saveManualHotspotSecurity(this, ManualHotspotValidation.securityFor(secret))
            AirPlayPersistence.saveManualHotspotBand(this, ManualHotspotBand.AUTO)
            AirPlayPersistence.saveManualHotspotChannel(this, 0)
            AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.MANUAL)
            DiPlayPreferences.saveDefaultConnectionMode(this, DefaultConnectionMode.WIRELESS)
            markPending()
        }
    }

    private fun background() {
        val route = BrowserAddressService.snapshot(this)
        fun introduction(title: Int, body: Int) = heading(title, body,
            BrowserOnboardingArtView.Scene.BACKGROUND, R.string.onboarding_android_device)
        when {
            !hasPermissions() -> {
                introduction(R.string.onboarding_permissions_title,
                    if (android.Manifest.permission.ACCESS_FINE_LOCATION in requiredPermissions()) R.string.onboarding_permissions_location_body
                    else R.string.onboarding_permissions_body)
                val needsSettings = permissionDenied && requiredPermissions().any {
                    checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it)
                }
                if (permissionDenied) label(getString(R.string.onboarding_permission_denied), 14)
                action(if (needsSettings) R.string.onboarding_app_settings else R.string.onboarding_permissions_action, true) {
                    if (needsSettings) openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else {
                        BrowserTelemetry.record(this, "permission_requested", mapOf("permission" to "wireless"))
                        permissions.launch(requiredPermissions().toTypedArray())
                    }
                }
            }
            !batteryAllowed() -> {
                introduction(R.string.onboarding_battery_title,
                    if (BrowserBackgroundReadiness.snapshot(this).restricted == true) R.string.browser_background_restricted
                    else R.string.onboarding_battery_body)
                action(R.string.onboarding_battery_action, true) {
                    BrowserTelemetry.record(this, "permission_requested", mapOf("permission" to "background"))
                    if (!BrowserBackgroundReadiness.request(this)) {
                        AlertDialog.Builder(this).setMessage(R.string.browser_battery_unavailable)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
            !route.active || !BrowserOnboardingProgress.localAddressApproved(this) -> {
                introduction(R.string.onboarding_route_title, R.string.onboarding_route_body)
                val message = when {
                    route.busy || awaitingRoute -> R.string.onboarding_wait_label
                    route.phase == BrowserAddressService.Phase.ERROR -> R.string.browser_address_error
                    route.phase == BrowserAddressService.Phase.OTHER_VPN -> R.string.browser_address_other_vpn
                    route.phase == BrowserAddressService.Phase.REVOKED || route.phase == BrowserAddressService.Phase.PERMISSION_REQUIRED -> R.string.browser_address_permission
                    else -> null
                }
                message?.let { label(getString(it), 14) }
                action(R.string.onboarding_route_action, true) { enableRoute() }.isEnabled = !route.busy && !awaitingRoute
            }
            else -> {
                introduction(R.string.onboarding_background_approved_title, R.string.onboarding_background_approved_body)
                next = action(R.string.onboarding_continue, true) {
                    if (BrowserOnboardingProgress.approvalsAllowed(this) && BrowserAddressService.snapshot(this).active) move(Step.IPHONE)
                    else render()
                }
            }
        }
    }

    private fun iphone() {
        heading(R.string.onboarding_iphone_title, R.string.onboarding_iphone_body,
            BrowserOnboardingArtView.Scene.IPHONE, R.string.onboarding_iphone_device)
        if (hasPermissions()) {
            val bluetoothName = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
            if (!bluetoothName.isNullOrBlank()) label(getString(R.string.onboarding_bluetooth_name, bluetoothName), 16, true)
        }
        if (DiPlayPreferences.phoneAddress(this) != null) label(getString(R.string.onboarding_iphone_selected,
            DiPlayPreferences.phoneName(this)), 16, true)
        action(R.string.onboarding_iphone_choose) { choosePhone() }
        label(getString(R.string.onboarding_iphone_accept), 16)
        status = label("", 15, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        startCarPlay = action(R.string.onboarding_iphone_start, true) { requestStart() }
        next = action(R.string.onboarding_continue, true) { move(Step.TESLA_WIFI) }
        action(R.string.onboarding_iphone_wifi_help) {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_iphone_wifi_body)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.onboarding_iphone_guide) { _, _ ->
                    openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://support.apple.com/en-us/111786")))
                }.show()
        }
        networkSummary()
        later()
    }

    private fun teslaWifi() {
        heading(R.string.onboarding_tesla_wifi_title, R.string.onboarding_tesla_wifi_body,
            BrowserOnboardingArtView.Scene.TESLA_WIFI, R.string.onboarding_tesla_device)
        networkSummary()
        label(getString(R.string.onboarding_tesla_wifi_hint), 15)
        action(R.string.onboarding_tesla_wifi_confirm, true) { move(Step.TESLA_BROWSER) }
        action(R.string.onboarding_tesla_guide) {
            openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tesla.com/support/connect-to-wifi")))
        }
        later()
    }

    private fun browser() {
        heading(R.string.onboarding_browser_title, R.string.onboarding_browser_body,
            BrowserOnboardingArtView.Scene.TESLA_BROWSER, R.string.onboarding_tesla_device)
        address = label("", 23, true).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
        }
        status = label("", 15).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        next = action(R.string.onboarding_continue, true) { move(Step.AUDIO) }
        action(R.string.onboarding_browser_help) {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_browser_help_body)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        action(R.string.onboarding_open_status) { openDashboard() }
        later()
    }

    private fun audio() {
        heading(R.string.onboarding_audio_title, R.string.onboarding_audio_body,
            BrowserOnboardingArtView.Scene.AUDIO, R.string.onboarding_tesla_device)
        label(getString(R.string.onboarding_audio_hint), 15)
        action(R.string.onboarding_audio_confirm, true) {
            BrowserOnboardingProgress.confirmAudio(this)
            move(Step.READY)
        }
        action(R.string.onboarding_audio_help) {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_audio_help_body)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        later()
    }

    private fun ready() {
        val verified = setupVerified()
        readyVerified = verified
        heading(if (verified) R.string.onboarding_ready_title else R.string.onboarding_ready_waiting,
            if (verified) R.string.onboarding_ready_body else R.string.onboarding_ready_waiting_body,
            if (verified) BrowserOnboardingArtView.Scene.READY else BrowserOnboardingArtView.Scene.TESLA_BROWSER)
        status = label("", 15, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (verified) label(getString(R.string.onboarding_ready_hint), 15)
        action(R.string.onboarding_open_status, true) {
            BrowserOnboardingProgress.finish(this, CarPlayBackgroundSession.active,
                pictureVerified())
            openDashboard()
        }
    }

    private fun updateLiveState() {
        if (!::content.isInitialized || isDestroyed) return
        if (step.ordinal > Step.BACKGROUND.ordinal && !BrowserOnboardingProgress.approvalsAllowed(this)) {
            move(Step.BACKGROUND)
            return
        }
        if (DiPlaySessionService.currentBrowserOutput()?.running == true) closeCertificateSync()
        when (step) {
            Step.BACKGROUND -> {
                if (backgroundSignature != backgroundState()) { render(); return }
                next?.isEnabled = BrowserOnboardingProgress.approvalsAllowed(this) && BrowserAddressService.snapshot(this).active
            }
            Step.IPHONE -> {
                val message = if (startFailed) R.string.onboarding_start_failed
                    else if (DiPlaySessionService.currentBrowserRuntime()?.setupNeedsAttention == true) R.string.browser_dashboard_setup_attention_hint
                    else if (hasPendingChanges()) R.string.onboarding_reconnect_title
                    else if (CarPlayBackgroundSession.active) R.string.onboarding_iphone_connected
                    else if (BrowserSessionRecovery.requested(this)) R.string.onboarding_iphone_connecting
                    else null
                status?.text = message?.let { getString(it) }.orEmpty()
                status?.visibility = if (message == null) View.GONE else View.VISIBLE
                val connected = CarPlayBackgroundSession.active && !hasPendingChanges()
                next?.isEnabled = connected
                next?.visibility = if (connected) View.VISIBLE else View.GONE
                startCarPlay?.visibility = if (connected) View.GONE else View.VISIBLE
            }
            Step.TESLA_BROWSER -> {
                val output = DiPlaySessionService.currentBrowserOutput()
                val url = output?.urls?.firstOrNull { it.startsWith("https://${TlsIdentity.TRUSTED_HOST}:") }
                address?.text = BrowserConnectionAddress.forInstructions(url) ?: getString(when (output?.certificateSyncState) {
                    SharedCertificateSync.State.RETRYING -> R.string.browser_https_retry
                    SharedCertificateSync.State.ERROR -> R.string.browser_https_unavailable
                    else -> R.string.onboarding_browser_preparing
                })
                status?.setText(when {
                    pictureVerified() -> R.string.onboarding_browser_verified
                    startFailed -> R.string.onboarding_start_failed
                    DiPlaySessionService.currentBrowserRuntime()?.setupNeedsAttention == true -> R.string.browser_dashboard_setup_attention_hint
                    else -> R.string.onboarding_browser_waiting
                })
                next?.isEnabled = pictureVerified()
            }
            Step.READY -> {
                val verified = setupVerified()
                if (verified != readyVerified) { render(); return }
                status?.setText(if (verified) R.string.onboarding_browser_verified else R.string.onboarding_browser_waiting)
                if (verified) BrowserOnboardingProgress.finish(this, true, true)
            }
            else -> Unit
        }
    }

    private fun requestStart() {
        if (!BrowserOnboardingProgress.approvalsAllowed(this)) {
            BrowserTelemetry.record(this, "onboarding_step_blocked", mapOf("step" to step.name, "reason" to "approvals_required"))
            move(Step.BACKGROUND); return
        }
        if (DiPlayPreferences.phoneAddress(this).isNullOrBlank()) {
            BrowserTelemetry.record(this, "onboarding_step_blocked", mapOf("step" to step.name, "reason" to "iphone_not_selected"))
            choosePhone(); return
        }
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        if (PendingReconnect.isPending(controller)) {
            AlertDialog.Builder(this).setTitle(R.string.onboarding_reconnect_title)
                .setMessage(R.string.onboarding_reconnect_body)
                .setPositiveButton(R.string.onboarding_reconnect_action) { _, _ -> startReceiver(true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else startReceiver(false)
    }

    private fun startReceiver(reconnect: Boolean) {
        BrowserTelemetry.record(this, "receiver_start_requested", mapOf("automatic" to false, "source" to "onboarding", "manualIntervention" to true))
        startFailed = false
        closeCertificateSync()
        AirPlayPersistence.saveWirelessEnabled(this, true)
        BrowserSessionRecovery.markStarted(this)
        try {
            startForegroundService(Intent(this, DiPlaySessionService::class.java).apply {
                action = if (reconnect) DiPlaySessionService.ACTION_RECONNECT else DiPlaySessionService.ACTION_RECOVER
                putExtra(DiPlaySessionService.EXTRA_MANUAL_START, true)
            })
            updateLiveState()
        } catch (_: RuntimeException) {
            startFailed = true
            status?.setText(R.string.onboarding_start_failed)
        }
    }

    @Suppress("MissingPermission")
    private fun choosePhone() {
        if (!hasPermissions()) { move(Step.BACKGROUND); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.takeIf { it.isEnabled }?.bondedDevices?.sortedBy { it.name ?: "" } }.getOrNull().orEmpty()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_iphone_pair_help)
                .setPositiveButton(R.string.onboarding_iphone_pair) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.onboarding_iphone_choose)
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                if (device.address != DiPlayPreferences.phoneAddress(this)) {
                    markPending()
                    BrowserOnboardingProgress.resetPhoneVerification(this)
                }
                DiPlayPreferences.savePhone(this, device.address, device.name ?: getString(R.string.paired_device))
                render()
            }.setNeutralButton(R.string.onboarding_iphone_pair) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun enableRoute() {
        if (awaitingRoute) return
        if (BrowserAddressService.hasOtherVpn(this)) {
            AlertDialog.Builder(this).setMessage(R.string.browser_address_other_vpn)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        try {
            val consent = VpnService.prepare(this)
            if (consent == null) BrowserAddressService.enable(this)
            else {
                BrowserTelemetry.record(this, "permission_requested", mapOf("permission" to "local_route"))
                awaitingRoute = true; vpn.launch(consent)
            }
        } catch (_: RuntimeException) {
            AlertDialog.Builder(this).setMessage(R.string.browser_address_error)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        updateLiveState()
    }

    private fun networkSummary() {
        val ssid = networkName()
        if (ssid.isEmpty()) { label(getString(R.string.onboarding_network_alternative), 15); return }
        val card = BrowserDesign.setupCard(this)
        card.addView(BrowserDesign.setupText(this, 13).apply { text = getString(R.string.onboarding_network_name) })
        card.addView(BrowserDesign.setupText(this, 22, true).apply {
            text = ssid
            textDirection = View.TEXT_DIRECTION_LTR
        })
        card.addView(BrowserDesign.setupButton(this, getString(R.string.onboarding_show_password)) {
            val value = BrowserDesign.setupText(this, 24, true).apply {
                text = networkPassword()
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }
            passwordDialog = AlertDialog.Builder(this).setTitle(R.string.onboarding_network_password)
                .setView(value).setPositiveButton(android.R.string.ok, null).create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.show()
                }
        })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
    }

    private fun input(title: Int, secret: Boolean): EditText {
        val label = label(getString(title), 14, true, top = 16)
        return EditText(this).apply {
            id = View.generateViewId()
            label.labelFor = id
            textSize = 18f
            setTextColor(BrowserDesign.onboarding(this@BrowserOnboardingActivity).ink)
            setHintTextColor(BrowserDesign.onboarding(this@BrowserOnboardingActivity).muted)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            // setSingleLine replaces the transformation method, so mask after applying it.
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            textDirection = View.TEXT_DIRECTION_LTR
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val palette = BrowserDesign.onboarding(this@BrowserOnboardingActivity)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(palette.field)
                    setStroke(dp(2), palette.accent)
                    cornerRadius = dp(12).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(palette.field)
                    setStroke(dp(1), palette.line)
                    cornerRadius = dp(12).toFloat()
                })
            }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun label(value: String, size: Int, bold: Boolean = false, top: Int = 10) =
        BrowserDesign.setupText(this, size, bold).apply {
            text = value
            gravity = Gravity.START
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        }

    private fun action(title: Int, primary: Boolean = false, click: () -> Unit) =
        BrowserDesign.setupButton(this, getString(title), primary, click).apply {
            if (primary) tag = "onboarding_primary"
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

    private fun later() { action(R.string.onboarding_later) { finish() } }
    private fun openDashboard() {
        startActivity(Intent(this, BrowserDashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
    private fun openSystem(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_settings_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }
    private fun openHotspotSettings() {
        for (action in listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)) {
            try { startActivity(Intent(action)); return } catch (_: RuntimeException) { /* Try the OEM fallback. */ }
        }
        openSystem(Intent(Settings.ACTION_SETTINGS))
    }
    private fun concealPassword() {
        password?.transformationMethod = PasswordTransformationMethod.getInstance()
        passwordToggle?.setText(R.string.onboarding_password_show_short)
        passwordToggle?.contentDescription = getString(R.string.onboarding_show_password)
        passwordDialog?.dismiss()
        passwordDialog = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun markPending() { CarPlayBackgroundSession.snapshot()?.controller?.let { PendingReconnect.mark(it) } }
    private fun requiredPermissions() = WirelessPermissions.required(Build.VERSION.SDK_INT, AirPlayPersistence.loadWirelessHotspotMode(this))
    private fun hasPermissions() = requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun batteryAllowed() = BrowserBackgroundReadiness.snapshot(this).allowed
    private fun backgroundState(): String {
        val route = BrowserAddressService.snapshot(this)
        return "${hasPermissions()}/${batteryAllowed()}/${BrowserOnboardingProgress.localAddressApproved(this)}/${route.active}/${route.busy}/${route.phase}/$awaitingRoute"
    }
    private fun pictureVerified(): Boolean {
        val output = DiPlaySessionService.currentBrowserOutput()
        return !hasPendingChanges() && CarPlayBackgroundSession.active && output?.running == true &&
            output.clientCount > 0 && output.hasRenderedVideo
    }
    private fun setupVerified() = pictureVerified() && BrowserOnboardingProgress.audioConfirmed(this) &&
        BrowserOnboardingProgress.approvalsAllowed(this)
    private fun hasPendingChanges() = PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)
    private fun onTextChanged(changed: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private fun hasExistingNetwork() = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.EXISTING_WIFI -> ManualHotspotValidation.error(networkName(), networkPassword()) == null
        WirelessHotspotMode.MANUAL -> false
        else -> DiPlayPreferences.phoneAddress(this) != null
    }
    private fun networkName() = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotSsid(this)
        WirelessHotspotMode.EXISTING_WIFI -> AirPlayPersistence.loadExistingWifiSsid(this)
        else -> ""
    }
    private fun networkPassword() = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotPassphrase(this)
        WirelessHotspotMode.EXISTING_WIFI -> AirPlayPersistence.loadExistingWifiPassphrase(this)
        else -> ""
    }
    private fun dp(value: Int) = BrowserDesign.dp(this, value)

    companion object {
        const val EXTRA_REVIEW = "browser_onboarding_review"
        const val EXTRA_STEP = "browser_onboarding_step"
    }
}

/** Draft secrets survive only a configuration change; never written to Bundle or preferences. */
internal class BrowserOnboardingDraft : ViewModel() {
    var name: String? = null
    var password: String? = null
    var replaceNetwork = false
}
