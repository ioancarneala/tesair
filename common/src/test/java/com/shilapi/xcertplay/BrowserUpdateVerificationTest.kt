package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserUpdateVerificationTest {
    private val manager = mock(PackageManager::class.java)
    private val app = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "co.tesair.app"
        override fun getPackageManager() = manager
    }

    @Test fun validSameKeyHigherVersionIsAccepted() {
        val fixture = fixture()
        BrowserUpdates.verify(app, fixture.first, fixture.second)
    }

    @Test fun modifiedDownloadCannotReachTheInstaller() {
        val fixture = fixture()
        fixture.second.appendText("modified")
        assertTrue(runCatching { BrowserUpdates.verify(app, fixture.first, fixture.second) }.isFailure)
    }

    @Test fun differentKeyDifferentPackageAndDowngradeAreRejected() {
        listOf(
            packageInfo("co.tesair.app", 67, "1.0.5", byteArrayOf(8)),
            packageInfo("other.application", 67, "1.0.5", byteArrayOf(1)),
            packageInfo("co.tesair.app", 66, "1.0.4", byteArrayOf(1)),
        ).forEach { archive ->
            val fixture = fixture()
            `when`(manager.getPackageArchiveInfo(fixture.second.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(archive)
            assertTrue(runCatching { BrowserUpdates.verify(app, fixture.first, fixture.second) }.isFailure)
        }
    }

    private fun fixture(): Pair<BrowserUpdateInfo, File> {
        val file = File.createTempFile("update-verification-", ".apk", app.cacheDir).apply {
            writeText("APK fixture parsed by the package-manager test double")
            deleteOnExit()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val info = BrowserUpdateInfo("1.0.5", 67,
            "https://github.com/ioancarneala/tesair/releases/download/v1.0.5/TesAir-v1.0.5.apk", hash)
        val installed = packageInfo("co.tesair.app", 66, "1.0.4", byteArrayOf(1))
        val archive = packageInfo("co.tesair.app", 67, "1.0.5", byteArrayOf(1))
        `when`(manager.getPackageInfo("co.tesair.app", PackageManager.GET_SIGNING_CERTIFICATES))
            .thenReturn(installed)
        `when`(manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES))
            .thenReturn(archive)
        return info to file
    }

    private fun packageInfo(name: String, code: Long, version: String, signature: ByteArray) = PackageInfo().apply {
        packageName = name
        longVersionCode = code
        versionName = version
        val signing = mock(SigningInfo::class.java)
        val signatures = arrayOf(Signature(signature))
        `when`(signing.apkContentsSigners).thenReturn(signatures)
        signingInfo = signing
    }
}
