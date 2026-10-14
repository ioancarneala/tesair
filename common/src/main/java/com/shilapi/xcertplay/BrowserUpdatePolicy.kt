package com.shilapi.xcertplay

import java.net.URI
import org.json.JSONObject

internal data class BrowserUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
)

/** Accept only the stable release package. Redirects are checked separately on every hop. */
internal object BrowserUpdatePolicy {
    const val PACKAGE = "co.tesair.app"
    const val ENDPOINT = "https://telemetry.tesair.co/v1/update"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val hashPattern = Regex("[a-fA-F0-9]{64}")

    fun shouldCheck(now: Long, previous: Long, force: Boolean): Boolean =
        force || previous <= 0 || now < previous || now - previous >= CHECK_INTERVAL_MS

    fun parseResponse(source: String): BrowserUpdateInfo? {
        val root = JSONObject(source)
        require(root.getInt("schemaVersion") == 1)
        if (root.isNull("update")) return null
        return parse(root.getJSONObject("update"))
    }

    fun parse(value: JSONObject): BrowserUpdateInfo {
        require(value.getInt("schemaVersion") == 1 && value.getString("packageName") == PACKAGE)
        val name = value.getString("versionName")
        val code = value.getLong("versionCode")
        val url = value.getString("apkUrl")
        val sha = value.getString("sha256")
        require(versionPattern.matches(name) && name.length <= 30 && code in 1..Int.MAX_VALUE)
        require(hashPattern.matches(sha))
        val expected = "https://github.com/ioancarneala/tesair/releases/download/v$name/TesAir-v$name.apk"
        require(url == expected)
        return BrowserUpdateInfo(name, code, url, sha.lowercase())
    }

    fun json(info: BrowserUpdateInfo) = JSONObject().put("schemaVersion", 1)
        .put("packageName", PACKAGE).put("versionName", info.versionName)
        .put("versionCode", info.versionCode).put("apkUrl", info.apkUrl).put("sha256", info.sha256)

    fun allowedDownloadUri(uri: URI): Boolean = uri.scheme == "https" &&
        uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") &&
        uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null

    fun signingMatches(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean =
        installed.isNotEmpty() && installed.size == candidate.size &&
            installed.all { trusted -> candidate.any { it.contentEquals(trusted) } }
}
