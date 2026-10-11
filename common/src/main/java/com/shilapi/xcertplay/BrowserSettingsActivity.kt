// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.Locale

/** Browser-only preferences. Merely visiting a screen never restarts the receiver. */
class BrowserSettingsActivity : ComponentActivity() {
    private enum class Page { HOME, CONNECTION, PICTURE, HELP, ABOUT }
    private var page = Page.HOME
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var language = AppLocale.SYSTEM
    private var exportInProgress = false
    private var exportButton: Button? = null
    private var dialog: AlertDialog? = null
    private val motion = BrowserMotion()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Browser_Onboarding)
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("settings_page")?.let {
            runCatching { Page.valueOf(it) }.getOrNull()
        } ?: if (intent.getStringExtra("page") == "about") Page.ABOUT else Page.HOME
        language = AppLocale.preference(this)
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && language != AppLocale.preference(this)) recreate()
        else if (::content.isInitialized) render(preserveScroll = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(if (intent.getStringExtra("page") == "about") Page.ABOUT else Page.HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_page", page.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        motion.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun back() {
        if (page != Page.HOME) open(Page.HOME)
        else {
            BrowserAppNavigation.openHome(this, autoStart = false)
            finish()
        }
    }

    private fun open(destination: Page) {
        page = destination
        render()
    }

    private fun render(preserveScroll: Boolean = false) {
        val previousScroll = if (preserveScroll && ::scroll.isInitialized) scroll.scrollY else 0
        exportButton = null
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BrowserDesign.onboarding(this@BrowserSettingsActivity).background)
        }
        val frame = LinearLayout(this).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        content = column().apply { setPadding(dp(24), dp(16), dp(24), dp(32)) }
        frame.addView(content, LinearLayout.LayoutParams(
            if (resources.configuration.screenWidthDp > 600) dp(560) else -1, -2))
        scroll.addView(frame)
        setContentView(scroll)
        content.addView(BrowserBrand.header(this).apply {
            addView(BrowserDesign.setupButton(this@BrowserSettingsActivity,
                getString(R.string.back), click = ::back))
        })
        val title = when (page) {
            Page.HOME -> R.string.settings
            Page.CONNECTION -> R.string.connection
            Page.PICTURE -> R.string.settings_browser_preferences_picture
            Page.HELP -> R.string.settings_browser_preferences_help
            Page.ABOUT -> R.string.about
        }
        content.addView(text(getString(title), 30, true), match(top = 12, bottom = 20))
        when (page) {
            Page.HOME -> home()
            Page.CONNECTION -> connection()
            Page.PICTURE -> picture()
            Page.HELP -> help()
            Page.ABOUT -> about()
        }
        if (page != Page.ABOUT && PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) {
            val pending = card()
            pending.addView(text(getString(R.string.settings_reconnect_pending), 14))
            action(pending, R.string.apply_and_reconnect, primary = true, click = ::reconnect)
        }
        if (previousScroll > 0) scroll.post { scroll.scrollTo(0, previousScroll) }
        motion.update(content, page)
    }

    private fun home() {
        val options = card()
        row(options, R.string.connection, getString(R.string.settings_browser_preferences_connection_summary)) {
            open(Page.CONNECTION)
        }
        divider(options)
        row(options, R.string.settings_browser_preferences_picture,
            getString(R.string.settings_browser_preferences_picture_summary)) { open(Page.PICTURE) }
        divider(options)
        row(options, R.string.settings_browser_preferences_help,
            getString(R.string.settings_browser_preferences_help_summary)) { open(Page.HELP) }
        val app = card()
        row(app, R.string.language_app_language, AppLocale.displayName(this, AppLocale.preference(this))) {
            AppLocale.showPicker(this)
        }
        divider(app)
        row(app, R.string.about, getString(R.string.browser_product_name)) { open(Page.ABOUT) }
    }

    private fun connection() {
        val saved = card()
        summary(saved, getString(R.string.onboarding_chapter_iphone),
            if (DiPlayPreferences.phoneAddress(this) != null) DiPlayPreferences.phoneName(this)
            else getString(R.string.settings_browser_preferences_not_set))
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val network = when (mode) {
            WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotSsid(this)
            WirelessHotspotMode.EXISTING_WIFI -> AirPlayPersistence.loadExistingWifiSsid(this)
            else -> getString(R.string.browser_setup_automatic_network)
        }
        summary(saved, getString(R.string.browser_setup_network_label),
            network.ifBlank { getString(R.string.settings_browser_preferences_not_set) })
        if (mode == WirelessHotspotMode.MANUAL || mode == WirelessHotspotMode.EXISTING_WIFI) {
            val passwordSaved = when (mode) {
                WirelessHotspotMode.MANUAL -> AirPlayPersistence.loadManualHotspotPassphrase(this).isNotEmpty()
                else -> AirPlayPersistence.loadExistingWifiPassphrase(this).isNotEmpty()
            }
            summary(saved, getString(R.string.onboarding_network_password),
                if (passwordSaved) "••••••••" else getString(R.string.browser_setup_no_password))
        }
        action(saved, R.string.settings_browser_preferences_review_setup) { guide(review = true) }
        val automatic = card()
        toggle(automatic, R.string.settings_browser_preferences_auto_open,
            R.string.settings_browser_preferences_auto_open_hint, DiPlayPreferences.autoConnect(this)) {
            DiPlayPreferences.saveAutoConnect(this, it)
        }
        divider(automatic)
        toggle(automatic, R.string.settings_browser_preferences_after_restart,
            R.string.settings_browser_preferences_after_restart_hint, AirPlayPersistence.loadAutoStartOnBoot(this)) {
            AirPlayPersistence.saveAutoStartOnBoot(this, it)
        }
    }

    private fun picture() {
        val layout = card()
        val currentSize = AirPlayPersistence.loadWidthPhysicalMm(this)
        val sizes = BrowserPicturePresets.interfaceSizes
        row(layout, R.string.settings_browser_preferences_interface_size,
            getString(BrowserPicturePresets.title(sizes, currentSize, R.string.settings_browser_preferences_custom_size))) {
            presetDialog(R.string.settings_browser_preferences_interface_size, sizes, currentSize,
                R.string.settings_browser_preferences_keep_size, reconnectOnApply = true) {
                AirPlayPersistence.saveWidthPhysicalMm(this, it)
            }
        }
        layout.addView(text(getString(R.string.settings_browser_preferences_size_hint), 14))
        divider(layout)
        toggle(layout, R.string.right_hand_drive, R.string.settings_browser_preferences_driving_hint,
            AirPlayPersistence.loadRightHandDrive(this)) {
            AirPlayPersistence.saveRightHandDrive(this, it)
            markReconnectNeeded()
        }
        val video = card()
        val currentQuality = AirPlayPersistence.loadBrowserResolutionPercent(this)
        val qualities = BrowserPicturePresets.videoQualities
        row(video, R.string.settings_browser_preferences_quality,
            getString(BrowserPicturePresets.title(qualities, currentQuality, R.string.settings_browser_preferences_custom_quality))) {
            presetDialog(R.string.settings_browser_preferences_quality, qualities, currentQuality,
                R.string.settings_browser_preferences_keep_quality) {
                AirPlayPersistence.saveBrowserResolutionPercent(this, it)
            }
        }
        divider(video)
        val currentMotion = AirPlayPersistence.loadFps(this)
        val motions = BrowserPicturePresets.smoothness
        row(video, R.string.settings_browser_preferences_smoothness,
            getString(BrowserPicturePresets.title(motions, currentMotion, R.string.settings_browser_preferences_custom_motion))) {
            presetDialog(R.string.settings_browser_preferences_smoothness, motions, currentMotion,
                R.string.settings_browser_preferences_keep_motion) { AirPlayPersistence.saveFps(this, it) }
        }
        video.addView(text(getString(R.string.settings_browser_preferences_video_hint), 14))
        val appearance = card()
        row(appearance, R.string.settings_browser_preferences_appearance,
            getString(nightLabel(AirPlayPersistence.loadCarPlayNightMode(this)))) {
            val current = AirPlayPersistence.loadCarPlayNightMode(this)
            val choices = mutableListOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.DAY,
                CarPlayNightMode.NIGHT, CarPlayNightMode.SCHEDULE)
            // Keep an existing ambient setting visible without promoting a hidden phone sensor.
            if (current == CarPlayNightMode.AMBIENT) choices.add(current)
            choice(R.string.settings_browser_preferences_appearance,
                choices.map { getString(nightLabel(it)) }, choices.indexOf(current)) {
                AirPlayPersistence.saveCarPlayNightMode(this, choices[it])
                DiPlaySessionService.currentBrowserRuntime()?.refreshAppearance()
            }
        }
        if (AirPlayPersistence.loadCarPlayNightMode(this) == CarPlayNightMode.SCHEDULE) {
            scheduleRow(appearance, start = true)
            scheduleRow(appearance, start = false)
            appearance.addView(text(getString(R.string.settings_browser_preferences_clock_hint), 14))
        }
    }

    private fun presetDialog(
        title: Int,
        presets: List<BrowserPicturePresets.Preset>,
        current: Int,
        keepCurrentLabel: Int,
        reconnectOnApply: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val choices = BrowserPicturePresets.includingSaved(presets, current, keepCurrentLabel)
        var selected = choices.indexOfFirst { it.value == current }
        val running = reconnectOnApply && CarPlayBackgroundSession.hasSession()
        showDialog(AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(choices.map { getString(it.title) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(if (running) R.string.apply_and_reconnect else R.string.save) { _, _ ->
                val chosen = choices[selected].value
                if (chosen != current) {
                    save(chosen)
                    markReconnectNeeded()
                    if (running) reconnect()
                }
            }.setNegativeButton(R.string.cancel, null).create())
    }

    private fun scheduleRow(parent: LinearLayout, start: Boolean) {
        val schedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val selectedMinute = if (start) schedule.startMinute else schedule.endMinute
        val title = if (start) R.string.carplay_night_start else R.string.carplay_night_end
        row(parent, title, String.format(Locale.getDefault(), "%02d:%02d", selectedMinute / 60, selectedMinute % 60)) {
            val clock = TimePicker(this).apply {
                setIs24HourView(true)
                hour = selectedMinute / 60
                minute = selectedMinute % 60
            }
            val picker = AlertDialog.Builder(this).setTitle(title).setView(clock)
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
            picker.setOnShowListener {
                picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    clock.clearFocus()
                    val chosen = clock.hour * 60 + clock.minute
                    val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
                    if (chosen == if (start) current.endMinute else current.startMinute) {
                        picker.setTitle(R.string.carplay_night_schedule_same_time)
                    } else {
                        AirPlayPersistence.saveCarPlayNightSchedule(this,
                            if (start) current.copy(startMinute = chosen) else current.copy(endMinute = chosen))
                        DiPlaySessionService.currentBrowserRuntime()?.refreshAppearance()
                        picker.dismiss()
                        render(preserveScroll = true)
                    }
                }
            }
            showDialog(picker)
        }
    }

    private fun help() {
        val guideCard = card()
        guideCard.addView(text(getString(R.string.settings_browser_preferences_help_hint), 15))
        action(guideCard, R.string.settings_browser_preferences_setup_guide,
            primary = !PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) {
            guide(review = false)
        }
        val reports = card()
        reports.addView(text(getString(R.string.settings_browser_preferences_report_hint), 14))
        exportButton = action(reports, if (exportInProgress) R.string.saving_report else R.string.save_diagnostic_report,
            click = ::exportReport).apply { isEnabled = !exportInProgress }
    }

    private fun about() {
        val panel = card()
        @Suppress("DEPRECATION")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        panel.addView(text(getString(R.string.settings_browser_preferences_version, version), 16, true))
        panel.addView(text(getString(R.string.settings_browser_preferences_independent), 14), match(top = 12))
        val credits = card()
        credits.addView(text(getString(R.string.made_possible_by_open_source), 18, true))
        credits.addView(text(getString(R.string.settings_browser_preferences_credits), 14), match(top = 12))
    }

    private fun guide(review: Boolean) {
        startActivity(Intent(this, BrowserOnboardingActivity::class.java).apply {
            if (review) putExtra(BrowserOnboardingActivity.EXTRA_REVIEW, true)
            else putExtra(BrowserOnboardingActivity.EXTRA_STEP, BrowserOnboardingProgress.Step.IPHONE.name)
        })
    }

    private fun exportReport() {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; setText(R.string.saving_report) }
        val app = applicationContext
        Thread({
            val result = runCatching {
                check(BrowserAutomaticReport.save(app))
                val report = File(app.filesDir, "automatic-diagnostics/latest.txt").readText()
                val fileName = "CarPlay-report-${System.currentTimeMillis()}.txt"
                Triple(DiagnosticExportStore.saveWithoutPicker(app, fileName, report), fileName, report)
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; setText(R.string.save_diagnostic_report) }
                result.fold(onSuccess = { (saved, fileName, report) ->
                    showDialog(AlertDialog.Builder(this).setTitle(R.string.diagnostic_report_saved)
                        .setMessage(when {
                            saved.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            saved.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, saved.savedPath)
                            else -> "Downloads/DiPlay/$fileName"
                        }).setNegativeButton(R.string.done, null)
                        .setPositiveButton(R.string.view_diagnostic_report) { _, _ -> showReport(report) }
                        .setNeutralButton(R.string.share) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, saved.uri)
                                    clipData = ClipData.newRawUri(getString(R.string.report_clip_label), saved.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showReport(report) }
                        }.create())
                }, onFailure = { reportError() })
            }
        }, "browser-report-export").start()
    }

    private fun reportError() = showDialog(AlertDialog.Builder(this)
        .setTitle(R.string.could_not_save_the_report)
        .setMessage(R.string.check_that_storage_is_available_or_choose_another_save_loc)
        .setPositiveButton(R.string.close, null).create())

    private fun showReport(report: String) {
        val body = text(report, 13).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setTextIsSelectable(true)
        }
        showDialog(AlertDialog.Builder(this).setTitle(R.string.view_diagnostic_report)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.done, null).create())
    }

    private fun choice(title: Int, values: List<String>, initial: Int, save: (Int) -> Unit) {
        var selected = initial.coerceAtLeast(0)
        showDialog(AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(values.toTypedArray(), selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ -> save(selected); render(preserveScroll = true) }
            .setNegativeButton(R.string.cancel, null).create())
    }

    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        render(preserveScroll = true)
    }

    private fun reconnect() {
        startActivity(Intent(this, BrowserDashboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(BrowserDashboardActivity.EXTRA_RECONNECT, true))
        finish()
    }

    private fun showDialog(value: AlertDialog) {
        dialog?.dismiss()
        dialog = value
        value.show()
    }

    private fun row(parent: LinearLayout, title: Int, value: String, click: () -> Unit) {
        val palette = BrowserDesign.onboarding(this)
        val style = BrowserDesign.setupButton(this, getString(title), click = {})
        parent.addView(LinearLayout(this).apply {
            tag = title
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = style.background
            foreground = style.foreground
            isClickable = true
            isFocusable = true
            contentDescription = "${getString(title)} · $value"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            setOnClickListener { click() }
            addView(column().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(getString(title), 16, true))
                addView(text(value, 14), match(top = 4))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@BrowserSettingsActivity).apply {
                setImageResource(R.drawable.ic_dp_chevron)
                imageTintList = ColorStateList.valueOf(palette.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
        }, match())
    }

    private fun toggle(parent: LinearLayout, title: Int, hint: Int, value: Boolean, save: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            setText(title)
            textSize = 16f
            minHeight = dp(56)
            setTextColor(BrowserDesign.onboarding(context).ink)
            isChecked = value
            contentDescription = getString(title)
            val accent = BrowserDesign.onboarding(context).accent
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, BrowserDesign.onboarding(context).muted))
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }, match())
        parent.addView(text(getString(hint), 14), match(bottom = 8))
    }

    private fun summary(parent: LinearLayout, title: String, value: String) {
        parent.addView(text(title, 13), match(top = 8))
        parent.addView(text(value, 17, true).apply { setTextIsSelectable(true) }, match(bottom = 8))
    }

    private fun action(parent: LinearLayout, title: Int, primary: Boolean = false, click: () -> Unit) =
        BrowserDesign.setupButton(this, getString(title), primary, click).also { parent.addView(it, match(top = 12)) }

    private fun card() = BrowserDesign.setupCard(this).also { content.addView(it, match(bottom = 16)) }
    private fun divider(parent: LinearLayout) = parent.addView(View(this).apply {
        setBackgroundColor(BrowserDesign.onboarding(context).line)
    }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, bold: Boolean = false) =
        BrowserDesign.setupText(this, size, bold).apply { text = value }
    private fun dp(value: Int) = BrowserDesign.dp(this, value)
    private fun match(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun nightLabel(mode: CarPlayNightMode) = when (mode) {
        CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
        CarPlayNightMode.DAY -> R.string.carplay_night_day
        CarPlayNightMode.NIGHT -> R.string.carplay_night_night
        CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
        CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
    }
}
