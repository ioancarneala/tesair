package com.shilapi.xcertplay

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserUpdatePolicyTest {
    private fun update() = JSONObject().put("schemaVersion", 1).put("packageName", "co.tesair.app")
        .put("versionName", "1.0.5").put("versionCode", 67)
        .put("apkUrl", "https://github.com/ioancarneala/tesair/releases/download/v1.0.5/TesAir-v1.0.5.apk")
        .put("sha256", "a".repeat(64))

    @Test fun acceptsOnlyPublishedStableReleaseShape() {
        val response = JSONObject().put("schemaVersion", 1).put("update", update())
        val parsed = BrowserUpdatePolicy.parseResponse(response.toString())!!
        assertEquals(67L, parsed.versionCode)
        assertEquals("1.0.5", parsed.versionName)
        assertEquals(parsed, BrowserUpdatePolicy.parse(BrowserUpdatePolicy.json(parsed)))
    }

    @Test fun noPublishedUpdateIsValid() {
        assertNull(BrowserUpdatePolicy.parseResponse("{\"schemaVersion\":1,\"update\":null}"))
    }

    @Test fun rejectsUntrustedSourcesMalformedChecksumsAndDifferentPackages() {
        listOf(
            update().put("apkUrl", "https://example.com/tesair.apk"),
            update().put("apkUrl", "http://github.com/ioancarneala/tesair/releases/download/v1.0.5/TesAir-v1.0.5.apk"),
            update().put("sha256", "abc"), update().put("packageName", "co.tesair.app.debug"),
            update().put("versionName", "1.0.5-preview"), update().put("versionCode", -1),
            update().put("schemaVersion", 2),
        ).forEach { value -> assertTrue(value.toString(), runCatching { BrowserUpdatePolicy.parse(value) }.isFailure) }
    }

    @Test fun downloadRedirectsStayOnSecureGitHubAssetHosts() {
        assertTrue(BrowserUpdatePolicy.allowedDownloadUri(URI("https://release-assets.githubusercontent.com/a?signature=test")))
        listOf("http://github.com/a", "https://github.com.evil.test/a", "https://evil.test/a",
            "https://user@github.com/a", "https://github.com:8443/a", "https://github.com/a#x")
            .forEach { assertFalse(it, BrowserUpdatePolicy.allowedDownloadUri(URI(it))) }
    }

    @Test fun exactCurrentSigningIdentityRequiredAndUnsignedPackagesRejected() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertTrue(BrowserUpdatePolicy.signingMatches(listOf(a), listOf(a.copyOf())))
        assertFalse(BrowserUpdatePolicy.signingMatches(emptyList(), emptyList()))
        assertFalse(BrowserUpdatePolicy.signingMatches(listOf(a), listOf(b)))
        assertFalse(BrowserUpdatePolicy.signingMatches(listOf(a), listOf(a, b)))
    }

    @Test fun repeatedActiveConnectionsReuseTheSameDayCheckUntilItsCacheExpires() {
        val checkedAt = 1_800_000_000_000L
        listOf(0L, 1000L, 60_000L, 3_600_000L, BrowserUpdatePolicy.CHECK_INTERVAL_MS - 1)
            .forEach { delay -> assertFalse(BrowserUpdatePolicy.shouldCheck(checkedAt + delay, checkedAt, false)) }
        assertTrue(BrowserUpdatePolicy.shouldCheck(checkedAt + BrowserUpdatePolicy.CHECK_INTERVAL_MS, checkedAt, false))
        assertTrue(BrowserUpdatePolicy.shouldCheck(checkedAt, checkedAt, true))
    }

    @Test fun freshInstallAndClockCorrectionCannotHideUpdateChecks() {
        assertTrue(BrowserUpdatePolicy.shouldCheck(1_800_000_000_000L, 0, false))
        assertTrue(BrowserUpdatePolicy.shouldCheck(0, 0, false))
        assertTrue(BrowserUpdatePolicy.shouldCheck(1_800_000_000_000L, 1_800_000_000_001L, false))
    }
}
