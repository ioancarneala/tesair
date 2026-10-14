package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Installation always follows an explicit tap, Android consent and a verified same-key APK. */
class BrowserUpdateActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "TesAir-update-download") }
    private val cancelled = AtomicBoolean(false)
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var retry: Button
    private lateinit var notifications: Button
    private var available: BrowserUpdateInfo? = null
    private var downloaded: File? = null
    private var busy = false
    private var notificationsAllowed = false
    internal var updateChecker: (Boolean, (BrowserUpdateState) -> Unit) -> Unit = { force, callback ->
        BrowserUpdates.check(this, force, callback)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The dialog result does not certify that app or channel notifications are enabled.
        refreshNotifications()
    }
    private val notificationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshNotifications()
    }

    private val sourcePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) install()
        else {
            detail.setText(R.string.browser_update_source_required)
            refreshAction()
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(32))
            setBackgroundColor(palette.background)
        }
        content.addView(BrowserBrand.header(this).apply {
            addView(BrowserDesign.setupButton(this@BrowserUpdateActivity,
                getString(R.string.browser_update_back)) { finish() })
        })
        title = BrowserDesign.setupText(this, 30, true)
        content.addView(title, margins(12))
        detail = BrowserDesign.setupText(this, 16)
        content.addView(detail, margins(12))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(palette.accent)
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            visibility = android.view.View.GONE
        }
        content.addView(progress, margins(24))
        action = BrowserDesign.setupButton(this, getString(R.string.browser_update_download), primary = true) {
            if (downloaded != null) install() else download()
        }
        content.addView(action, margins(28))
        retry = BrowserDesign.setupButton(this, getString(R.string.browser_update_check)) { check() }
        content.addView(retry, margins(8))
        content.addView(BrowserDesign.setupText(this, 14).apply {
            text = getString(R.string.browser_update_keeps_settings)
            gravity = Gravity.CENTER
        }, margins(24))
        notifications = BrowserDesign.setupButton(this, getString(R.string.browser_update_alerts_enable)) {
            requestNotifications()
        }.apply { tag = R.string.browser_update_alerts_enable }
        content.addView(notifications, margins(24))
        val frame = LinearLayout(this).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        frame.addView(content, LinearLayout.LayoutParams(
            if (resources.configuration.screenWidthDp > 600) dp(560) else -1, -2))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
            addView(frame)
        })
        check()
    }

    override fun onResume() {
        super.onResume()
        refreshNotifications()
    }

    private fun refreshNotifications() {
        if (!::notifications.isInitialized) return
        val allowed = BrowserUpdates.notificationsEnabled(this)
        notifications.isEnabled = !allowed
        val requestable = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("browser_updates", MODE_PRIVATE).getBoolean("notification_requested", false)
        notifications.setText(when {
            allowed -> R.string.browser_update_alerts_enabled
            requestable -> R.string.browser_update_alerts_enable
            else -> R.string.browser_update_alerts_settings
        })
        if (allowed && !notificationsAllowed) BrowserUpdates.notifyCached(this)
        notificationsAllowed = allowed
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("browser_updates", MODE_PRIVATE).getBoolean("notification_requested", false)) {
            getSharedPreferences("browser_updates", MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            try {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            } catch (_: RuntimeException) { /* Fall back to the public Android settings screen. */ }
        }
        val candidates = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in candidates) try {
            notificationSettings.launch(intent)
            return
        } catch (_: RuntimeException) { /* Try the next public settings entry point. */ }
        Toast.makeText(this, R.string.browser_update_alerts_unavailable, Toast.LENGTH_SHORT).show()
        refreshNotifications()
    }

    private fun check() {
        if (busy) return
        title.setText(R.string.browser_update_title)
        detail.setText(R.string.browser_update_checking)
        retry.isEnabled = false
        action.isEnabled = false
        updateChecker(true) checked@ { state ->
            if (isFinishing || isDestroyed) return@checked
            available = state.update
            retry.isEnabled = true
            if (state.update != null) {
                title.text = getString(R.string.browser_update_available, state.update.versionName)
                detail.setText(if (state.error) R.string.browser_update_offline_available else R.string.browser_update_ready)
            } else detail.setText(if (state.error) R.string.browser_update_offline else R.string.browser_update_current)
            refreshAction()
        }
    }

    private fun download() {
        val info = available ?: return
        if (busy) return
        busy = true
        action.isEnabled = false
        retry.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        detail.setText(R.string.browser_update_downloading)
        worker.execute {
            try {
                val file = BrowserUpdates.download(applicationContext, info, cancelled) { percent -> main.post {
                    if (!isDestroyed) {
                        progress.isIndeterminate = percent < 0
                        if (percent >= 0) progress.progress = percent
                    }
                } }
                main.post {
                    if (isDestroyed) return@post
                    busy = false
                    downloaded = file
                    progress.visibility = android.view.View.GONE
                    retry.isEnabled = true
                    detail.setText(if (CarPlayBackgroundSession.active) R.string.browser_update_carplay_active
                        else R.string.browser_update_downloaded)
                    refreshAction()
                }
            } catch (_: Exception) { main.post {
                if (isDestroyed) return@post
                busy = false
                progress.visibility = android.view.View.GONE
                retry.isEnabled = true
                detail.setText(R.string.browser_update_download_failed)
                refreshAction()
            } }
        }
    }

    private fun install() {
        val info = available ?: return
        val file = downloaded ?: return
        if (busy) return
        if (CarPlayBackgroundSession.active) {
            detail.setText(R.string.browser_update_carplay_active)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                sourcePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (_: RuntimeException) { detail.setText(R.string.browser_update_source_required) }
            return
        }
        busy = true
        action.isEnabled = false
        retry.isEnabled = false
        detail.setText(R.string.browser_update_verifying)
        worker.execute {
            try {
                BrowserUpdates.verify(applicationContext, info, file)
                main.post {
                    if (isDestroyed) return@post
                    busy = false
                    retry.isEnabled = true
                    refreshAction()
                    // The session may have started while the APK was being verified.
                    if (CarPlayBackgroundSession.active) {
                        detail.setText(R.string.browser_update_carplay_active)
                        return@post
                    }
                    try {
                        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                        detail.setText(R.string.browser_update_confirm_android)
                    } catch (_: RuntimeException) { detail.setText(R.string.browser_update_install_failed) }
                }
            } catch (_: Exception) { main.post {
                if (isDestroyed) return@post
                busy = false
                downloaded = null
                file.delete()
                retry.isEnabled = true
                detail.setText(R.string.browser_update_download_failed)
                refreshAction()
            } }
        }
    }

    private fun refreshAction() {
        action.visibility = if (available != null) android.view.View.VISIBLE else android.view.View.GONE
        action.isEnabled = !busy
        action.setText(if (downloaded != null) R.string.browser_update_install else R.string.browser_update_download)
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = BrowserDesign.dp(this, value)
    private fun margins(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

/** Uses a distinct authority and only the update cache; diagnostic report access stays unchanged. */
class BrowserUpdateProvider : FileProvider()
