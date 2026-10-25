package com.shilapi.xcertplay.browser

import android.os.Looper
import com.shilapi.xcertplay.airplay.VideoCodec
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserStreamOutputTest {
    private lateinit var output: BrowserStreamOutput
    private lateinit var pendingStatus: AtomicReference<String>
    private val failures = mutableListOf<Pair<Int, String>>()
    private val diagnostics = mutableListOf<String>()
    private val avcc = "01640028ffe1000b67640028ace80780227e5401000468ce3c80"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Before fun setUp() {
        output = BrowserStreamOutput(RuntimeEnvironment.getApplication())
        output.setTelemetryAttempt(7)
        output.onVideoFailure = { attempt, reason -> failures.add(attempt to reason) }
        output.onDiagnostic = { diagnostics.add(it) }

        // Observe the actual browser message without starting TLS, sockets or a sender thread.
        val server = ReflectionHelpers.getField<BridgeServer>(output, "server")
        val session = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)) {
                _, method, _ ->
            when (method.name) {
                "getInputStream" -> ByteArrayInputStream(byteArrayOf())
                "getHeaders" -> emptyMap<String, String>()
                else -> null
            }
        } as NanoHTTPD.IHTTPSession
        val client = server.openWebSocket(session)
        ReflectionHelpers.getField<MutableList<Any>>(server, "clients").add(client)
        pendingStatus = ReflectionHelpers.getField(client, "pendingStatus")
    }

    @After fun tearDown() {
        output.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun transientHevcConfigurationWaitsWithoutSendingAManualReconnectError() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        assertTrue(failures.isEmpty())
        assertBrowserStatus("Preparing CarPlay video")

        output.onVideoCodec(110, VideoCodec.H264)
        output.onVideoConfig(110, avcc)
        advance(10_000)

        assertTrue(failures.isEmpty())
        assertTrue(output.status.startsWith("Direct CarPlay video"))
        assertEquals("status", JSONObject(pendingStatus.get()).getString("type"))
        assertEquals(1, diagnostics.count { it.startsWith("video_format_pending ") })
        assertFalse(diagnostics.any { it.startsWith("video_format_recovery ") })
    }

    @Test fun repeatedUnsupportedCodecDoesNotExtendTheDeadlineOrRepeatRecovery() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.onVideoCodec(110, VideoCodec.H265)
        advance(BrowserStreamOutput.VIDEO_FORMAT_GRACE_MILLIS - 2_001)
        assertTrue(failures.isEmpty())
        assertBrowserStatus("Preparing CarPlay video")

        advance(1)
        assertEquals(listOf(7 to "unsupported_iphone_codec"), failures)
        assertBrowserStatus("Reconnecting to CarPlay")

        output.onVideoCodec(110, VideoCodec.H265)
        advance(30_000)
        assertEquals(listOf(7 to "unsupported_iphone_codec"), failures)
        assertEquals(1, diagnostics.count { it.startsWith("video_format_recovery ") })
    }

    @Test fun invalidAvcConfigurationHasTheSameBoundedAutomaticRecovery() {
        output.onVideoConfig(110, byteArrayOf())
        advance(1_000)
        output.onVideoConfig(110, avcc.copyOf(8))
        advance(BrowserStreamOutput.VIDEO_FORMAT_GRACE_MILLIS - 1_001)
        assertTrue(failures.isEmpty())
        assertBrowserStatus("Preparing CarPlay video")

        advance(1)
        assertEquals(listOf(7 to "video_config_rejected"), failures)
        assertBrowserStatus("Reconnecting to CarPlay")
        output.onVideoConfig(110, byteArrayOf())
        advance(10_000)
        assertEquals(1, failures.size)
    }

    @Test fun validAvcConfigurationCancelsMalformedConfigurationRecovery() {
        output.onVideoConfig(110, avcc.copyOf(8))
        advance(2_999)
        output.onVideoConfig(110, avcc)
        advance(10_000)

        assertTrue(failures.isEmpty())
        assertTrue(output.status.startsWith("Direct CarPlay video"))
    }

    @Test fun codecAnnouncementAloneDoesNotCancelRecoveryBeforeAValidConfiguration() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.onVideoCodec(110, VideoCodec.H264)
        advance(1_000)

        assertEquals(listOf(7 to "unsupported_iphone_codec"), failures)
    }

    @Test fun aNewAttemptCancelsOldRecoveryAndGetsItsOwnDeadline() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.setTelemetryAttempt(8)
        output.onVideoCodec(110, VideoCodec.H264)
        output.onVideoConfig(110, avcc)
        advance(2_000)
        assertTrue(failures.isEmpty())

        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_999)
        assertTrue(failures.isEmpty())
        advance(1)
        assertEquals(listOf(8 to "unsupported_iphone_codec"), failures)
    }

    @Test fun stoppingTheStreamCancelsItsPendingRecovery() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.onScreenStreamActive(110, false)
        advance(10_000)

        assertTrue(failures.isEmpty())
        assertBrowserStatus("CarPlay disconnected · waiting for the iPhone")

        output.onScreenStreamActive(110, true)
        output.onVideoCodec(110, VideoCodec.H264)
        output.onVideoConfig(110, avcc)
        advance(10_000)
        assertTrue(failures.isEmpty())
        assertTrue(output.status.startsWith("Direct CarPlay video"))
    }

    @Test fun repeatedActiveNotificationCannotCancelPendingFormatRecovery() {
        output.onScreenStreamActive(110, true)
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.onScreenStreamActive(110, true)
        advance(999)
        assertTrue(failures.isEmpty())

        advance(1)
        assertEquals(listOf(7 to "unsupported_iphone_codec"), failures)
        assertBrowserStatus("Reconnecting to CarPlay")
    }

    @Test fun closingCancelsRecoveryAndRejectsLaterMediaCallbacks() {
        output.onVideoCodec(110, VideoCodec.H265)
        advance(2_000)
        output.close()
        output.onVideoCodec(110, VideoCodec.H265)
        output.onVideoConfig(110, byteArrayOf())
        advance(10_000)

        assertTrue(failures.isEmpty())
        assertEquals("Browser output stopped", output.status)
        assertFalse(diagnostics.any { it.startsWith("video_format_recovery ") })
    }

    @Test fun otherScreenTypesCannotTriggerMainScreenRecovery() {
        output.onVideoCodec(111, VideoCodec.H265)
        output.onVideoConfig(111, byteArrayOf())
        advance(10_000)

        assertTrue(failures.isEmpty())
        assertEquals("Browser server is off", output.status)
        assertTrue(diagnostics.isEmpty())
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun assertBrowserStatus(expected: String) {
        assertEquals(expected, output.status)
        val message = JSONObject(pendingStatus.get())
        assertEquals("status", message.getString("type"))
        assertEquals(expected, message.getString("message"))
    }
}
