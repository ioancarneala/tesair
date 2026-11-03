package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCodecHevcTest {
    private fun hvcC(): ByteArray {
        val data = ByteArray(32)
        data[0] = 1
        data[1] = 1
        data[2] = 0x60
        data[6] = 0xB0.toByte()
        data[12] = 93
        data[13] = 0xF0.toByte()
        data[15] = 0xFC.toByte()
        data[16] = 0xFD.toByte()
        data[17] = 0xF8.toByte()
        data[18] = 0xF8.toByte()
        data[21] = 0x0F
        data[22] = 1
        data[23] = 0xA1.toByte()
        data[24] = 0; data[25] = 1
        data[26] = 0; data[27] = 4
        data[28] = 0x42; data[29] = 0x01; data[30] = 0x01; data[31] = 0x60
        return data
    }

    private fun box(type: String, payload: ByteArray, prefix: Int = 0): ByteArray {
        val out = ByteArray(prefix + 8 + payload.size)
        val size = 8 + payload.size
        out[prefix] = (size ushr 24).toByte(); out[prefix + 1] = (size ushr 16).toByte()
        out[prefix + 2] = (size ushr 8).toByte(); out[prefix + 3] = size.toByte()
        type.forEachIndexed { index, char -> out[prefix + 4 + index] = char.code.toByte() }
        payload.copyInto(out, prefix + 8)
        return out
    }

    @Test fun rawHvcCIsDetected() {
        val config = hvcC()
        val (codec, data) = ScreenCodec.detectConfig(config)
        assertEquals(VideoCodec.H265, codec)
        assertArrayEquals(config, data)
    }

    @Test fun boxedAndNestedHvcCAreDetected() {
        val payload = hvcC()
        val (boxedCodec, boxed) = ScreenCodec.detectConfig(box("hvcC", payload))
        assertEquals(VideoCodec.H265, boxedCodec)
        assertArrayEquals(payload, boxed)

        // A hev1 sample entry wraps the hvcC box behind its own header.
        val entry = ByteArray(16 + 8 + payload.size)
        val size = entry.size
        entry[0] = (size ushr 24).toByte(); entry[1] = (size ushr 16).toByte()
        entry[2] = (size ushr 8).toByte(); entry[3] = size.toByte()
        "hev1".forEachIndexed { index, char -> entry[4 + index] = char.code.toByte() }
        box("hvcC", payload).copyInto(entry, 16)
        val (nestedCodec, nested) = ScreenCodec.detectConfig(entry)
        assertEquals(VideoCodec.H265, nestedCodec)
        assertArrayEquals(payload, nested)
    }

    @Test fun annexBParameterSetsAreDetected() {
        val config = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0c, 0x01)
        val (codec, data) = ScreenCodec.detectConfig(config)
        assertEquals(VideoCodec.H265, codec)
        assertArrayEquals(config, data)
    }
}
