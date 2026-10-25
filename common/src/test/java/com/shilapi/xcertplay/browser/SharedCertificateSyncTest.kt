package com.shilapi.xcertplay.browser

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SharedCertificateSyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clients = mutableListOf<SharedCertificateSync>()
    private val endpoint = "https://certificates.tesair.co/v1/bundle"
    private val now = 1_000_000L

    @After fun cleanup() {
        clients.forEach { it.close() }
        context.getSharedPreferences(SharedCertificateSync.PREFERENCES, 0).edit().clear().commit()
        context.getSharedPreferences(TlsIdentity.SHARED_PREFERENCES, 0).edit().clear().commit()
        context.getSharedPreferences("browser_shared_identity", 0).edit().clear().commit()
        context.getSharedPreferences("browser_certificate_sync", 0).edit().clear().commit()
        context.getSharedPreferences("shared-metadata-test", 0).edit().clear().commit()
    }

    @Test fun noEndpointDoesNotFetchOrChangeTheExistingIdentity() {
        val identity = FakeIdentity(true)
        val sync = client(identity, endpoint = "") { throw AssertionError("No network without configuration") }
        sync.start()
        assertEquals(SharedCertificateSync.State.DISABLED, sync.state)
        assertEquals(0, identity.installed)
        assertTrue(identity.trusted())
    }

    @Test fun acceptsOnlyExactHttpsDistributionEndpoint() {
        assertTrue(SharedCertificateSync.validEndpoint(endpoint))
        assertTrue(SharedCertificateSync.validEndpoint("https://certificates.tesair.co:443/v1/bundle"))
        for (invalid in listOf("http://certificates.tesair.co/v1/bundle", "https://user:pass@certificates.tesair.co/v1/bundle",
            "https://certificates.tesair.co/other/v1/bundle", "$endpoint?token=x", "$endpoint#fragment",
            "https://certificates.tesair.co:8443/v1/bundle", "https://certificates.tesair.co.evil.test/v1/bundle",
            "https://certificates.example.invalid/v1/bundle", "https://carplay.tesair.co/v1/bundle",
            "https://example.test/v1/bundle", " $endpoint", "$endpoint ")) {
            assertFalse(invalid, SharedCertificateSync.validEndpoint(invalid))
        }
    }

    @Test fun legacyEndpointCannotFetchOrReplaceTheCurrentIdentity() {
        val identity = FakeIdentity(true)
        val sync = client(identity, "https://certificates.example.invalid/v1/bundle") {
            throw AssertionError("Legacy distribution must never receive a request")
        }
        sync.start()
        assertEquals(SharedCertificateSync.State.ERROR, sync.state)
        assertEquals(SharedCertificateSync.PERIOD_MILLIS, sync.syncOnce())
        assertEquals(SharedCertificateSync.State.ERROR, sync.state)
        assertEquals(0, identity.installed)
        assertTrue(identity.trusted())
    }

    @Test fun installSuccessIsPeriodicAndFailuresPreserveTheCurrentIdentity() {
        val identity = FakeIdentity(true)
        val sync = client(identity) { SharedCertificateSync.Response(200, bundle(2)) }
        assertEquals(SharedCertificateSync.PERIOD_MILLIS, sync.syncOnce())
        assertEquals(1, identity.installed)
        assertEquals(SharedCertificateSync.State.CURRENT, sync.state)
        identity.reject = true
        assertEquals(60_000, sync.syncOnce())
        assertTrue(identity.trusted())
        assertEquals(SharedCertificateSync.State.RETRYING, sync.state)
    }

    @Test fun malformedSchemaVersionHostnameAndNonIntegerVersionNeverReachTheKeystore() {
        for (body in listOf(bundle("2"), bundle(2.5), bundle(9_007_199_254_740_992L),
            JSONObject(bundle(2)).put("schemaVersion", "1").toString(),
            JSONObject(bundle(2)).put("hostname", "carplay.example.invalid").toString(),
            JSONObject(bundle(2)).put("hostname", "carplay.tesair.co.evil.test").toString(),
            JSONObject(bundle(2)).put("hostname", "other.example").toString())) {
            val identity = FakeIdentity(true)
            val sync = client(identity) { SharedCertificateSync.Response(200, body) }
            sync.syncOnce()
            assertEquals(0, identity.installed)
            assertTrue(identity.trusted())
        }
    }

    @Test fun respectsRetryAfterAndExposesPersistedWaitImmediately() {
        val identity = FakeIdentity(false)
        val sync = client(identity) { SharedCertificateSync.Response(429, "", 900) }
        assertEquals(900_000, sync.syncOnce())
        val prefs = context.getSharedPreferences(SharedCertificateSync.PREFERENCES, 0)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest((endpoint + "\nexample-download-token").toByteArray())
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString("endpoint", fingerprint).commit()
        val resumed = client(identity) { throw AssertionError("Backoff must survive restart") }
        resumed.start()
        assertEquals(SharedCertificateSync.State.RETRYING, resumed.state)
        val trusted = client(FakeIdentity(true)) { throw AssertionError("Not due yet") }
        trusted.start()
        assertEquals(SharedCertificateSync.State.CURRENT, trusted.state)
    }

    @Test fun rejectedDownloadTokenShowsAnErrorWithoutRemovingTheWorkingCertificate() {
        val identity = FakeIdentity(true)
        val sync = client(identity) { SharedCertificateSync.Response(401, "") }
        assertEquals(60_000, sync.syncOnce())
        assertEquals(SharedCertificateSync.State.ERROR, sync.state)
        assertEquals(SharedCertificateSync.Reason.HTTP_UNAUTHORIZED, sync.diagnostics.reason)
        assertTrue(identity.trusted())
        assertEquals(0, identity.installed)
    }

    @Test fun diagnosticsClassifyHttpFailuresWithoutRemovingAWorkingIdentity() {
        for ((code, reason) in mapOf(401 to SharedCertificateSync.Reason.HTTP_UNAUTHORIZED,
            403 to SharedCertificateSync.Reason.HTTP_FORBIDDEN, 429 to SharedCertificateSync.Reason.HTTP_RATE_LIMITED,
            503 to SharedCertificateSync.Reason.HTTP_SERVER_ERROR, 302 to SharedCertificateSync.Reason.HTTP_UNEXPECTED)) {
            val identity = FakeIdentity(true)
            val sync = client(identity) { SharedCertificateSync.Response(code, "private-response-must-not-be-reported") }
            sync.syncOnce()
            assertEquals(reason, sync.diagnostics.reason)
            assertTrue(identity.trusted())
            assertFalse(sync.diagnostics.toString().contains("private-response"))
        }
    }

    @Test fun diagnosticsDistinguishDownloadBundleAndKeystoreFailuresWithoutExceptionMessages() {
        val cases = listOf(
            Triple(SocketTimeoutException("private-network-details"), SharedCertificateSync.Stage.DOWNLOAD,
                SharedCertificateSync.Reason.NETWORK_TIMEOUT),
            Triple(java.net.UnknownHostException("private-network-details"), SharedCertificateSync.Stage.DOWNLOAD,
                SharedCertificateSync.Reason.NETWORK_UNAVAILABLE),
            Triple(javax.net.ssl.SSLHandshakeException("private-certificate-details"), SharedCertificateSync.Stage.DOWNLOAD,
                SharedCertificateSync.Reason.DOWNLOAD_TLS_FAILED),
            Triple(IOException("private-response-details"), SharedCertificateSync.Stage.DOWNLOAD,
                SharedCertificateSync.Reason.DOWNLOAD_FAILED),
            Triple(IllegalArgumentException("private-bundle-details"), SharedCertificateSync.Stage.BUNDLE,
                SharedCertificateSync.Reason.INVALID_BUNDLE),
            Triple(java.security.KeyStoreException("private-key-details"), SharedCertificateSync.Stage.INSTALL,
                SharedCertificateSync.Reason.KEYSTORE_FAILED),
            Triple(java.security.cert.CertificateExpiredException("private-certificate-details"), SharedCertificateSync.Stage.INSTALL,
                SharedCertificateSync.Reason.IDENTITY_EXPIRED),
            Triple(java.security.cert.CertificateNotYetValidException("private-certificate-details"), SharedCertificateSync.Stage.INSTALL,
                SharedCertificateSync.Reason.CLOCK_BEFORE_VALIDITY),
            Triple(CertificateException("private-chain-details"), SharedCertificateSync.Stage.INSTALL,
                SharedCertificateSync.Reason.IDENTITY_INVALID),
            Triple(IOException("private-storage-details"), SharedCertificateSync.Stage.INSTALL,
                SharedCertificateSync.Reason.INSTALL_FAILED),
            Triple(IllegalStateException("private-identity-details"), SharedCertificateSync.Stage.VERIFY,
                SharedCertificateSync.Reason.IDENTITY_UNAVAILABLE),
        )
        for ((error, stage, expected) in cases) {
            assertEquals(expected, SharedCertificateSync.failureReason(error, stage))
        }
        val wrapped = IllegalStateException("private-wrapper", java.security.cert.CertificateNotYetValidException("private-time"))
        assertEquals(SharedCertificateSync.Reason.CLOCK_BEFORE_VALIDITY,
            SharedCertificateSync.failureReason(wrapped, SharedCertificateSync.Stage.INSTALL))
    }

    @Test fun rejectedBundleAndInstallationFailurePublishTheCorrectFinalOutcome() {
        val malformed = client(FakeIdentity(false)) { SharedCertificateSync.Response(200, "private-invalid-json") }
        malformed.syncOnce()
        assertEquals(SharedCertificateSync.Reason.INVALID_BUNDLE, malformed.diagnostics.reason)
        val identity = FakeIdentity(true).apply { failure = java.security.KeyStoreException("private-keystore-details") }
        val sync = client(identity) { SharedCertificateSync.Response(200, bundle(2)) }
        sync.syncOnce()
        assertEquals(SharedCertificateSync.Diagnostics(SharedCertificateSync.State.RETRYING,
            SharedCertificateSync.Reason.KEYSTORE_FAILED), sync.diagnostics)
        assertTrue(identity.trusted())
        identity.failure = null
        sync.syncOnce()
        assertEquals(SharedCertificateSync.Diagnostics(SharedCertificateSync.State.CURRENT,
            SharedCertificateSync.Reason.CURRENT), sync.diagnostics)
    }

    @Test fun diagnosticSnapshotsAreAtomicAndUnchangedPublicationsAreDeduplicated() {
        val observed = mutableListOf<SharedCertificateSync.Diagnostics>()
        lateinit var sync: SharedCertificateSync
        sync = client(FakeIdentity(true), "https://unapproved.example/v1/bundle",
            changed = { observed += sync.diagnostics }) { throw AssertionError("Invalid configuration must not fetch") }
        sync.start()
        sync.syncOnce()
        assertEquals(listOf(SharedCertificateSync.Diagnostics(SharedCertificateSync.State.ERROR,
            SharedCertificateSync.Reason.INVALID_CONFIGURATION)), observed)
        val snapshot = sync.diagnostics
        sync.close()
        sync.syncOnce()
        assertSame(snapshot, sync.diagnostics)
        assertEquals(1, observed.size)
    }

    @Test fun firstSetupRetriesWithinAMinuteWhileExistingCertificatesUseBoundedBackoff() {
        val fresh = client(FakeIdentity(false)) { throw IOException("Synthetic offline network") }
        repeat(10) { assertEquals(60_000L, fresh.syncOnce()) }
        val prefs = context.getSharedPreferences(SharedCertificateSync.PREFERENCES, 0)
        prefs.edit().remove("failures").commit()
        val working = client(FakeIdentity(true)) { throw IOException("Synthetic offline network") }
        assertEquals(60_000L, working.syncOnce())
        assertEquals(120_000L, working.syncOnce())
        repeat(10) { assertTrue(working.syncOnce() <= SharedCertificateSync.PERIOD_MILLIS) }
    }

    @Test fun restoredInternetRetriesPromptlyWithoutIgnoringRateLimits() {
        assertEquals(0L, SharedCertificateSync.networkRetryDelay(now, now - 300_000, 0))
        assertEquals(50_000L, SharedCertificateSync.networkRetryDelay(now, now - 10_000, 0))
        assertEquals(900_000L, SharedCertificateSync.networkRetryDelay(now, now - 300_000, now + 900_000))
        assertEquals(60_000L, SharedCertificateSync.networkRetryDelay(now, now + 300_000, 0))
        assertEquals(SharedCertificateSync.MAX_RETRY_SECONDS * 1000,
            SharedCertificateSync.networkRetryDelay(now, 0, now + 2 * SharedCertificateSync.MAX_RETRY_SECONDS * 1000))
    }

    @Test fun downloadsAreBoundedByBytesAndTime() {
        assertArrayEquals(byteArrayOf(1, 2), SharedCertificateSync.readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 100) { 0 })
        assertThrows(IOException::class.java) {
            SharedCertificateSync.readBounded(ByteArrayInputStream(ByteArray(SharedCertificateSync.MAX_RESPONSE_BYTES + 1)), 100) { 0 }
        }
        var tick = 0L
        val trickle = object : java.io.InputStream() {
            override fun read() = 1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { tick += 2; buffer[offset] = 1; return 1 }
        }
        assertThrows(SocketTimeoutException::class.java) { SharedCertificateSync.readBounded(trickle, 5) { tick } }
        assertEquals(6, tick)
    }

    @Test fun failedMetadataCommitRestoresThePreviousPointerAndRollbackFloor() {
        val actual = context.getSharedPreferences("shared-metadata-test", 0)
        actual.edit().putString("alias", "previous").putLong("version", 5).putLong("not_after", 100).commit()
        var fail = true
        val failing = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val delegate = actual.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { delegate.putLong(key, value); return this }
                    override fun commit(): Boolean {
                        delegate.commit()
                        return if (fail) { fail = false; false } else true
                    }
                }
            }
        }
        assertThrows(IOException::class.java) { TlsIdentity.persistSharedMetadata(failing, "replacement", 6, 200) }
        assertEquals("previous", actual.getString("alias", ""))
        assertEquals(5, actual.getLong("version", 0))
        assertEquals(100, actual.getLong("not_after", 0))
    }

    @Test fun hostnameMigrationPreservesLegacyMetadataAndPersistsAnIndependentRollbackFloor() {
        val legacy = context.getSharedPreferences("browser_shared_identity", 0)
        legacy.edit().putString("alias", "browser-shared-tls-5000-synthetic")
            .putLong("version", 5000).putLong("not_after", now + 900_000).commit()
        val before = legacy.all.toMap()
        TlsIdentity.initializeSharedStorage(context)
        assertEquals("carplay.tesair.co", TlsIdentity.TRUSTED_HOST)
        assertEquals(0L, TlsIdentity.sharedVersion())
        assertEquals(0L, TlsIdentity.sharedNotAfter())
        assertTrue(TlsIdentity.sharedUpdateRequired(2, TlsIdentity.sharedVersion(), false))
        val current = context.getSharedPreferences(TlsIdentity.SHARED_PREFERENCES, 0)
        TlsIdentity.persistSharedMetadata(current, TlsIdentity.SHARED_PREFIX + "2-synthetic", 2, now + 100_000)
        TlsIdentity.initializeSharedStorage(context)
        assertEquals(2L, TlsIdentity.sharedVersion())
        assertEquals(now + 100_000, TlsIdentity.sharedNotAfter())
        assertEquals(before, legacy.all)
        assertThrows(CertificateException::class.java) {
            TlsIdentity.sharedUpdateRequired(1, TlsIdentity.sharedVersion(), false)
        }
    }

    @Test fun rejectedMigrationLeavesBothIdentityNamespacesUntouched() {
        val legacy = context.getSharedPreferences("browser_shared_identity", 0)
        legacy.edit().putString("alias", "browser-shared-tls-5000-synthetic")
            .putLong("version", 5000).putLong("not_after", now + 900_000).commit()
        val before = legacy.all.toMap()
        TlsIdentity.initializeSharedStorage(context)
        assertThrows(CertificateException::class.java) {
            TlsIdentity.installSharedBundle(1, "carplay.example.invalid", "synthetic", "synthetic")
        }
        assertThrows(CertificateException::class.java) {
            TlsIdentity.installSharedBundle(1, TlsIdentity.TRUSTED_HOST, "synthetic", "synthetic")
        }
        assertEquals(before, legacy.all)
        assertTrue(context.getSharedPreferences(TlsIdentity.SHARED_PREFERENCES, 0).all.isEmpty())
    }

    @Test fun legacyDownloadBackoffDoesNotDelayNewDomainEnrollment() {
        val legacy = context.getSharedPreferences("browser_certificate_sync", 0)
        legacy.edit().putInt("failures", 8).putLong("next_attempt", now + 86_400_000)
            .putLong("server_retry_until", now + 86_400_000).commit()
        val before = legacy.all.toMap()
        val current = context.getSharedPreferences(SharedCertificateSync.PREFERENCES, 0)
        assertTrue(current.all.isEmpty())
        val identity = FakeIdentity(false)
        val sync = client(identity) { SharedCertificateSync.Response(200, bundle(1)) }
        assertEquals(SharedCertificateSync.PERIOD_MILLIS, sync.syncOnce())
        assertEquals(1, identity.installed)
        assertEquals(SharedCertificateSync.State.CURRENT, sync.state)
        assertEquals(before, legacy.all)
        assertEquals(now + SharedCertificateSync.PERIOD_MILLIS, current.getLong("next_attempt", 0))
    }

    private fun client(identity: FakeIdentity, endpoint: String = this.endpoint, changed: () -> Unit = {},
        fetch: () -> SharedCertificateSync.Response) = SharedCertificateSync(context, endpoint,
        "example-download-token", changed, object : SharedCertificateSync.Transport {
            override fun fetch(endpoint: String, token: String) = fetch()
        }, identity) { now }.also(clients::add)

    private fun bundle(version: Any) = JSONObject().put("schemaVersion", 1).put("version", version)
        .put("hostname", TlsIdentity.TRUSTED_HOST).put("certificateChain", "synthetic-public-certificate")
        .put("privateKey", "synthetic-test-key").toString()

    private class FakeIdentity(private var valid: Boolean) : SharedCertificateSync.Identity {
        var installed = 0
        var reject = false
        var failure: Exception? = null
        override fun install(version: Long, hostname: String, certificateChain: String, privateKey: String) {
            failure?.let { throw it }
            if (reject) throw java.security.cert.CertificateException("Synthetic invalid chain")
            installed++; valid = true
        }
        override fun trusted() = valid
        override fun hostname() = if (valid) TlsIdentity.TRUSTED_HOST else null
    }
}
