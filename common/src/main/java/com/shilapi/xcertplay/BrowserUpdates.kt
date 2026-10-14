package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal data class BrowserUpdateState(
    val update: BrowserUpdateInfo? = null,
    val error: Boolean = false,
)

/** Checks while the app or receiver is already active. No jobs, alarms or installation IDs. */
internal object BrowserUpdates {
    private const val PREFS = "browser_updates"
    private const val CHANNEL = "tesair_updates"
    private const val NOTIFICATION = 7404
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TesAir-updates").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val checkLock = Any()
    private var checking = false
    private val callbacks = ArrayList<(BrowserUpdateState) -> Unit>()

    fun show(activity: Activity) {
        activity.startActivity(Intent(activity, BrowserUpdateActivity::class.java))
    }

    fun cached(context: Context): BrowserUpdateInfo? {
        if (context.packageName != BrowserUpdatePolicy.PACKAGE) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("available", null)
        return stored?.let { runCatching { BrowserUpdatePolicy.parse(org.json.JSONObject(it)) }.getOrNull() }
            ?.takeIf { it.versionCode > installedVersion(context) }
    }

    internal fun notificationsEnabled(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance !=
            NotificationManager.IMPORTANCE_NONE

    internal fun notifyCached(context: Context) {
        cached(context)?.let { info -> runCatching { notifyOnce(context, info) } }
    }

    fun check(context: Context, force: Boolean = false, callback: (BrowserUpdateState) -> Unit = {}) {
        val app = context.applicationContext
        if (app.packageName != BrowserUpdatePolicy.PACKAGE) {
            main.post { callback(BrowserUpdateState()) }
            return
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong("last_check", 0)
        if (!BrowserUpdatePolicy.shouldCheck(now, previous, force, prefs.getBoolean("last_error", false))) {
            notifyCached(app)
            main.post { callback(BrowserUpdateState(cached(app), prefs.getBoolean("last_error", false))) }
            return
        }
        synchronized(checkLock) {
            callbacks.add(callback)
            if (checking) return
            checking = true
        }
        worker.execute {
            val state = try {
                val connection = openConnection(URI(BrowserUpdatePolicy.ENDPOINT))
                val available = try {
                    when (connection.responseCode) {
                        204 -> null
                        200 -> BrowserUpdatePolicy.parseResponse(readMetadata(connection))
                        else -> throw IOException("Update check failed")
                    }
                } finally { connection.disconnect() }
                val newer = available?.takeIf { it.versionCode > installedVersion(app) }
                prefs.edit().putLong("last_check", now).putBoolean("last_error", false).apply {
                    if (newer == null) remove("available")
                    else putString("available", BrowserUpdatePolicy.json(newer).toString())
                }.apply()
                if (newer != null) runCatching { notifyOnce(app, newer) }
                BrowserUpdateState(newer)
            } catch (_: Exception) {
                // Retry offline checks sooner than successful checks, without polling the network on every connection.
                prefs.edit().putLong("last_check", now).putBoolean("last_error", true).apply()
                BrowserUpdateState(cached(app), error = true)
            }
            val listeners = synchronized(checkLock) {
                checking = false
                callbacks.toList().also { callbacks.clear() }
            }
            main.post { listeners.forEach { it(state) } }
        }
    }

    internal fun installedVersion(context: Context): Long = installedPackage(context).longVersionCode

    @Suppress("DEPRECATION")
    private fun installedPackage(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    internal fun download(context: Context, info: BrowserUpdateInfo, cancelled: AtomicBoolean,
        progress: (Int) -> Unit): File {
        val directory = File(context.cacheDir, "tesair-updates").apply {
            if (!isDirectory && !mkdirs()) throw IOException("Update storage unavailable")
        }
        val staleBefore = System.currentTimeMillis() - BrowserUpdatePolicy.CHECK_INTERVAL_MS
        directory.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < staleBefore }
            ?.forEach(File::delete)
        val target = File(directory, "TesAir-v${info.versionName}.apk")
        if (target.isFile) {
            try { verify(context, info, target); return target }
            catch (_: Exception) { target.delete() }
        }
        val partial = File.createTempFile("download-", ".part", directory)
        var uri = URI(info.apkUrl)
        var connection: HttpsURLConnection? = null
        try {
            for (hop in 0..5) {
                if (cancelled.get()) throw IOException("Cancelled")
                require(BrowserUpdatePolicy.allowedDownloadUri(uri))
                connection = openConnection(uri)
                val response = connection.responseCode
                if (response in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    uri = uri.resolve(location)
                    connection.disconnect()
                    connection = null
                    continue
                }
                if (response != 200) throw IOException("Download unavailable")
                val length = connection.contentLengthLong
                if (length > BrowserUpdatePolicy.MAX_APK_BYTES) throw IOException("Download too large")
                var total = 0L
                var previousProgress = -1
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > BrowserUpdatePolicy.MAX_APK_BYTES) throw IOException("Download too large")
                        output.write(buffer, 0, count)
                        val percent = if (length > 0) (total * 100 / length).coerceIn(0, 99).toInt() else -1
                        if (percent != previousProgress) { previousProgress = percent; progress(percent) }
                    }
                } }
                if (total == 0L || (length > 0 && total != length)) throw IOException("Incomplete download")
                verify(context, info, partial)
                if (!partial.renameTo(target)) throw IOException("Update storage unavailable")
                directory.listFiles()?.filter { it != target && it.name.endsWith(".apk") }?.forEach(File::delete)
                progress(100)
                return target
            }
            throw IOException("Too many redirects")
        } finally {
            connection?.disconnect()
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    internal fun verify(context: Context, info: BrowserUpdateInfo, apk: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == info.sha256) { "Update integrity check failed" }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES) ?: throw IOException("Invalid package")
        val installed = installedPackage(context)
        require(context.packageName == BrowserUpdatePolicy.PACKAGE && archive.packageName == context.packageName)
        require(archive.longVersionCode == info.versionCode && archive.versionName == info.versionName &&
            archive.longVersionCode > installed.longVersionCode)
        val trusted = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        val candidate = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        require(BrowserUpdatePolicy.signingMatches(trusted, candidate)) { "Update signing key differs" }
    }

    private fun openConnection(uri: URI) = (uri.toURL().openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json, application/octet-stream")
        setRequestProperty("User-Agent", "TesAir-Updater/1")
    }

    private fun readMetadata(connection: HttpsURLConnection): String = connection.inputStream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 16_384) throw IOException("Update metadata too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun notifyOnce(context: Context, info: BrowserUpdateInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("notified_version", 0) >= info.versionCode ||
            !notificationsEnabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.browser_update_channel), NotificationManager.IMPORTANCE_LOW))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val intent = PendingIntent.getActivity(context, NOTIFICATION,
            Intent(context, BrowserUpdateActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_browser_brand)
            .setContentTitle(context.getString(R.string.browser_update_available, info.versionName))
            .setContentText(context.getString(R.string.browser_update_notification))
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build())
        prefs.edit().putLong("notified_version", info.versionCode).apply()
    }
}
