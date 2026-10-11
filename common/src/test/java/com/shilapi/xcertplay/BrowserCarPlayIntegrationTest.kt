package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class BrowserCarPlayIntegrationTest {
    private class RecordingSink : MediaSink {
        val events = mutableListOf<String>()
        var frame: ByteArray? = null
        var recovery: (() -> Unit)? = null
        var diagnostic: ((String) -> Unit)? = null
        override fun onVideoCodec(type: Int, codec: VideoCodec) { events += "codec:$type:$codec" }
        override fun onVideoConfig(type: Int, codecData: ByteArray) { events += "config:$type" }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            events += "frame:$type:$senderNanos:$arrivalNanos"; frame = naluBytes
        }
        override fun onScreenStreamActive(type: Int, active: Boolean) { events += "active:$type:$active" }
        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery = handler }
        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostic = handler }
        override fun onAudioStopped(id: AudioStreamId) { events += "audio:${id.type}" }
        override fun onMicrophoneStopped(id: AudioStreamId) { events += "microphone:${id.type}" }
    }

    @Test fun mainVideoBypassesAndroidDecoderWithoutCopyingOrDroppingTimestamps() {
        val device = RecordingSink(); val browser = RecordingSink()
        val sink = BrowserCarPlayMediaSink(device, browser) { true }
        val compressed = byteArrayOf(0, 0, 0, 1, 0x65)
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, byteArrayOf(1, 2, 3))
        sink.onVideoFrame(110, compressed, 10, 20)
        assertTrue(device.events.isEmpty())
        assertEquals(listOf("codec:110:H264", "config:110", "frame:110:10:20"), browser.events)
        assertSame(compressed, browser.frame)
    }

    @Test fun audioMicrophoneAndOtherVideoStreamsKeepTheExistingSink() {
        val device = RecordingSink(); val browser = RecordingSink()
        val sink = BrowserCarPlayMediaSink(device, browser) { true }
        sink.onAudioStopped(AudioStreamId(100, "media"))
        sink.onMicrophoneStopped(AudioStreamId(102, "speech"))
        sink.onVideoCodec(111, VideoCodec.H264)
        sink.onVideoFrame(111, byteArrayOf(1), 30, 40)
        assertEquals(listOf("audio:100", "microphone:102", "codec:111:H264", "frame:111:30:40"), device.events)
        assertTrue(browser.events.isEmpty())
    }

    @Test fun streamActivityReachesAudioPolicyAndBrowserWithoutStartingAndroidVideo() {
        val device = RecordingSink(); val browser = RecordingSink()
        val sink = BrowserCarPlayMediaSink(device, browser) { true }
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(110, false)
        assertEquals(listOf("active:110:true", "active:110:false"), device.events)
        assertEquals(device.events, browser.events)
    }

    @Test fun browserAckAndKeyframeRequestsReachOnlyTheCurrentCarPlaySession() {
        val device = RecordingSink(); val browser = RecordingSink()
        var current = true; var recoveries = 0
        val diagnostics = mutableListOf<String>()
        val sink = BrowserCarPlayMediaSink(device, browser) { current }
        sink.setVideoRecoveryHandler(110) { recoveries++ }
        sink.setVideoDiagnosticHandler(110) { diagnostics += it }
        browser.recovery!!.invoke(); browser.diagnostic!!.invoke("first frame rendered")
        assertEquals(1, recoveries); assertEquals(listOf("first frame rendered"), diagnostics)
        assertNull(device.recovery); assertNull(device.diagnostic)
        current = false
        browser.recovery!!.invoke(); browser.diagnostic!!.invoke("first frame rendered")
        sink.onVideoFrame(110, byteArrayOf(1), 1, 2)
        sink.onScreenStreamActive(110, false)
        assertEquals(1, recoveries); assertEquals(1, diagnostics.size)
        assertTrue(browser.events.isEmpty())
        assertEquals(listOf("active:110:false"), device.events)
    }

    @Test fun browserViewportPreservesShapeAndBoundsWithoutPhoneMetrics() {
        assertEquals(1920 to 1080, BrowserCarPlayViewport.fit(1921, 1081))
        assertEquals(2560 to 1440, BrowserCarPlayViewport.fit(3840, 2160))
        assertEquals(1080 to 1920, BrowserCarPlayViewport.fit(1080, 1920))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBrowserViewportIsRejected() { BrowserCarPlayViewport.fit(0, 1080) }
}
