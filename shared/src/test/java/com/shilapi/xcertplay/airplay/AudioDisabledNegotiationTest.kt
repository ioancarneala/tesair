package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.CarPlayBonjourProtocol
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDisabledNegotiationTest {
    @Test
    fun disabledAudioOffersNoAudioResourceFormatsLatenciesOrVocoderEvenWithMicrophoneEnabled() {
        val info = AirPlayInfoPlist.build(config(disabled = true))
        assertEquals(listOf(1), resources(info).map { it["resourceID"] })
        assertFalse(info.containsKey("audioFormats"))
        assertFalse(info.containsKey("audioLatencies"))
        assertFalse(info.containsKey("mainBufferedInfo"))
        assertEquals(listOf("enhancedRequestCarUI"), info["extendedFeatures"])
        assertEquals(0x615203a4e2L, info["features"])
    }

    @Test
    fun disablingAudioPreservesVideoInputAndNeutralAppStates() {
        val normal = AirPlayInfoPlist.build(config(disabled = false))
        val disabled = AirPlayInfoPlist.build(config(disabled = true))
        assertEquals(normal["displays"], disabled["displays"])
        assertEquals((normal["modes"] as Map<*, *>)["appStates"], (disabled["modes"] as Map<*, *>)["appStates"])
        val normalHids = (normal["hidDevices"] as List<*>).map { (it as Map<*, *>)["uuid"] }
        val disabledHids = (disabled["hidDevices"] as List<*>).map { (it as Map<*, *>)["uuid"] }
        assertEquals(4, disabledHids.size)
        assertEquals(normalHids, disabledHids)
        assertEquals(listOf("iAPChannel", "viewAreas"),
            setupEnabledFeatures(config(disabled = true), listOf("mainBuffered")))
    }

    @Test
    fun ordinaryAudioNegotiationStillTakesAudioAndOffersItsCapabilities() {
        val info = AirPlayInfoPlist.build(config(disabled = false))
        assertEquals(listOf(1, 2), resources(info).map { it["resourceID"] })
        assertEquals(listOf("vocoderInfo", "enhancedRequestCarUI"), info["extendedFeatures"])
        assertEquals(0x615653aee2L, info["features"])
        assertTrue(info.containsKey("audioLatencies"))
        assertTrue(info.containsKey("mainBufferedInfo"))
        val formats = (info["audioFormats"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf(100, 101, 102, 103), formats.map { it["type"] }.toSet())
        assertTrue(formats.any { it.containsKey("audioInputFormats") })
        assertTrue(setupEnabledFeatures(config(disabled = false), listOf("mainBuffered")).contains("mainBuffered"))
    }

    @Test
    fun discoveryAndInfoAdvertiseTheSameCapabilitiesForBothAudioModes() {
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing")
        for (disabled in listOf(false, true)) {
            val configuration = config(disabled)
            val words = CarPlayBonjourProtocol.airPlayTxtRecords(configuration, identity).getValue("features")
                .split(',').map { it.removePrefix("0x").toLong(16) }
            val features = words[0] or (words.getOrElse(1) { 0L } shl 32)
            assertEquals(AirPlayInfoPlist.build(configuration)["features"], features)
            assertEquals(!disabled, features and (1L shl 9) != 0L)
            for (bit in listOf(7, 32, 37, 38)) assertTrue(features and (1L shl bit) != 0L)
        }
    }

    @Test
    fun disabledAudioRejectsEveryAudioStreamBeforeCallingMediaButAcceptsVideoAndData() {
        val media = RecordingMedia()
        val session = session(disabled = true, media)
        try {
            val response = setup(session, audioStreams() + listOf(mapOf("type" to 110), mapOf("type" to 130)))
            assertEquals(listOf(110L, 130L), response.map { (it as Map<*, *>)["type"] })
            assertEquals(emptyList<Int>(), media.audioTypes)
            assertEquals(0, media.bufferedSetups)
            assertEquals(1, media.screenSetups)
            assertEquals(1, media.dataSetups)
            assertEquals(setOf(110, 130), session.activeStreams)
        } finally { session.close() }
    }

    @Test
    fun anAudioOnlyRequestIsDeclinedWithoutOpeningAMicrophoneOrOutput() {
        val media = RecordingMedia()
        val session = session(disabled = true, media)
        try {
            assertEquals(emptyList<Any>(), setup(session, audioStreams()))
            assertTrue(session.activeStreams.isEmpty())
            assertTrue(media.audioTypes.isEmpty())
            assertEquals(0, media.bufferedSetups)
        } finally { session.close() }
    }

    @Test
    fun ordinaryModeContinuesToRouteAllSupportedAudioStreams() {
        val media = RecordingMedia()
        val session = session(disabled = false, media)
        try {
            val response = setup(session, audioStreams())
            assertEquals(listOf(100L, 100L, 100L, 100L, 101L, 102L, 103L),
                response.map { (it as Map<*, *>)["type"] })
            assertEquals(listOf(100, 100, 100, 100, 101, 102), media.audioTypes)
            assertEquals(1, media.bufferedSetups)
            assertEquals(setOf(100, 101, 102, 103), session.activeStreams)
        } finally { session.close() }
    }

    private fun resources(info: Map<String, Any?>): List<Map<*, *>> =
        ((info["modes"] as Map<*, *>)["resources"] as List<*>).map { it as Map<*, *> }

    private fun config(disabled: Boolean) = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
        disableAudioOutput = disabled, microphone = true, mainBufferedAudio = true,
    )

    private fun session(disabled: Boolean, media: AirPlayMediaHandler) = AirPlaySession(
        socket = Socket(), config = config(disabled), identity = AirPlayIdentity.generate(), pairings = PairingStore(),
        mfi = null, listener = object : AirPlaySessionListener {}, media = media,
    )

    private fun audioStreams(): List<Map<String, Any?>> =
        listOf("default", "media", "telephony", "speechRecognition").map {
            mapOf("type" to 100, "audioType" to it, "dataPort" to 1234, "audioFormat" to 0x4000)
        } + listOf(101, 102, 103).map { mapOf("type" to it) }

    private fun setup(session: AirPlaySession, streams: List<Map<String, Any?>>): List<*> {
        val request = RtspMessage.Request("SETUP", "rtsp://test", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to streams)))
        val response = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request) as RtspMessage.Response
        assertEquals(200, response.status ?: 200)
        return (BplistCodec.decode(response.body) as Map<*, *>)["streams"] as List<*>
    }

    private class RecordingMedia : AirPlayMediaHandler {
        val audioTypes = mutableListOf<Int>()
        var bufferedSetups = 0
        var screenSetups = 0
        var dataSetups = 0
        override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?> {
            audioTypes += type
            return mapOf("type" to type, "dataPort" to 1234, "controlPort" to 1235)
        }
        override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> {
            bufferedSetups++
            return mapOf("type" to 103, "dataPort" to 1236)
        }
        override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int {
            screenSetups++
            return 1237
        }
        override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> {
            dataSetups++
            return mapOf("type" to 130, "dataPort" to 1238)
        }
    }
}
