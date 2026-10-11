package com.shilapi.xcertplay.browser

import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.VideoCodec
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserVideoStatusTest {
    private lateinit var output: BrowserStreamOutput
    private lateinit var server: BridgeServer
    private val config = "01640028ffe1000b67640028ace80780227e5401000468ce3c80"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Before fun setUp() {
        output = BrowserStreamOutput(RuntimeEnvironment.getApplication())
        server = ReflectionHelpers.getField(output, "server")
    }

    @After fun tearDown() {
        output.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun connectedAndConfiguredAreNotReportedAsRenderedUntilCurrentFrameIsAcknowledged() {
        assertFalse(output.hasRenderedVideo)
        output.onScreenStreamActive(110, true)
        output.onVideoConfig(110, config)
        assertFalse(output.hasRenderedVideo)
        rendered(server.videoGeneration() - 1)
        assertFalse(output.hasRenderedVideo)
        rendered(server.videoGeneration())
        assertTrue(output.hasRenderedVideo)
    }

    @Test fun identicalConfigKeepsConfirmedVideoButAChangedConfigWaitsForANewFrame() {
        output.onVideoConfig(110, config)
        val first = server.videoGeneration()
        rendered(first)
        output.onVideoConfig(110, config)
        assertTrue(output.hasRenderedVideo)
        output.onVideoConfig(110, config.clone().also { it[it.lastIndex] = 0x81.toByte() })
        assertFalse(output.hasRenderedVideo)
        rendered(first)
        assertFalse(output.hasRenderedVideo)
        rendered(server.videoGeneration())
        assertTrue(output.hasRenderedVideo)
    }

    @Test fun everyCurrentAcknowledgementRefreshesLivenessButStaleEpochsDoNot() {
        assertEquals(-1L, output.lastRenderedAtElapsedMillis)
        output.onVideoConfig(110, config)
        val epoch = server.videoGeneration()
        rendered(epoch)
        val first = output.lastRenderedAtElapsedMillis
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        rendered(epoch)
        assertEquals(SystemClock.elapsedRealtime(), output.lastRenderedAtElapsedMillis)
        assertTrue(output.lastRenderedAtElapsedMillis > first)
        val latest = output.lastRenderedAtElapsedMillis
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        rendered(epoch - 1)
        assertEquals(latest, output.lastRenderedAtElapsedMillis)
        output.onScreenStreamActive(110, false)
        rendered(server.videoGeneration())
        assertEquals(latest, output.lastRenderedAtElapsedMillis)
    }

    @Test fun replacementBrowserMustAcknowledgeItsOwnFrameBeforeItIsReportedReady() {
        output.onVideoConfig(110, config)
        rendered(server.videoGeneration())
        assertTrue(output.hasRenderedVideo)

        ReflectionHelpers.getField<Runnable>(server, "connectionChanged").run()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(output.hasRenderedVideo)
        rendered(server.videoGeneration())
        assertTrue(output.hasRenderedVideo)
    }

    @Test fun streamLossMalformedConfigUnsupportedCodecAndCloseClearConfirmedVideo() {
        listOf<() -> Unit>(
            { output.onScreenStreamActive(110, false) },
            { output.onVideoConfig(110, byteArrayOf()) },
            { output.onVideoCodec(110, VideoCodec.H265) },
            { output.close() },
        ).forEach { invalidate ->
            output.onVideoCodec(110, VideoCodec.H264)
            output.onVideoConfig(110, config)
            rendered(server.videoGeneration())
            assertTrue(output.hasRenderedVideo)
            invalidate()
            assertFalse(output.hasRenderedVideo)
        }
    }

    private fun rendered(epoch: Long) {
        ReflectionHelpers.callInstanceMethod<Unit>(output, "frameRendered", from(Long::class.javaPrimitiveType, epoch))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
