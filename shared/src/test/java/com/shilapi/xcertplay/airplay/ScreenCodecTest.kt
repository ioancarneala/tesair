package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class ScreenCodecTest {
    private val avc = "01640028ffe1000b67640028ace80780227e5401000468ce3c80"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rawConfigurationRecordsKeepTheirCodecAndData() {
        assertConfig(VideoCodec.H264, avc, avc)
        val hevc = hevcRecord()
        assertConfig(VideoCodec.H265, hevc, hevc)
    }

    @Test fun boxedRecordsUseDeclaredBoundsAndSupportSiblingAndExtendedSizeBoxes() {
        val hevc = hevcRecord()
        assertConfig(VideoCodec.H264, avc, box("free", byteArrayOf(7, 8)) + box("avcC", avc))
        assertConfig(VideoCodec.H265, hevc, box("hvcC", hevc) + box("free", byteArrayOf()))
        assertConfig(VideoCodec.H264, avc, ByteBuffer.allocate(16 + avc.size)
            .putInt(1).put("avcC".toByteArray()).putLong((16 + avc.size).toLong()).put(avc).array())
        assertConfig(VideoCodec.H265, hevc, box("hvcC", hevc).also { ByteBuffer.wrap(it).putInt(0) })
    }

    @Test fun codecNamesInsideNalUnitsOrUnknownDataAreNotConfigurationBoxes() {
        val sps = byteArrayOf(0x67, 100, 0, 40) + "hvcC".toByteArray()
        val misleadingAvc = byteArrayOf(1, 100, 0, 40, -1, -31, 0, sps.size.toByte()) + sps +
            byteArrayOf(1, 0, 1, 0x68)
        assertConfig(VideoCodec.H264, misleadingAvc, misleadingAvc)
        assertConfig(VideoCodec.H265, hevcRecord(), hevcRecord())
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCodec.detectConfig(byteArrayOf(9, 8, 7, 6) + "hvcC".toByteArray() + hevcRecord())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCodec.detectConfig(box("free", "avcC".toByteArray() + avc))
        }
    }

    @Test fun everyTruncatedRecordIsRejectedInsteadOfBeingMisclassifiedAsHevc() {
        for (record in listOf(avc, hevcRecord(), box("avcC", avc), box("hvcC", hevcRecord()))) {
            for (length in 0 until record.size) {
                assertThrows("Truncated record of $length bytes", IllegalArgumentException::class.java) {
                    ScreenCodec.detectConfig(record.copyOf(length))
                }
            }
        }
        for (record in listOf(byteArrayOf(0), ByteArray(23), avc.clone().also { it[0] = 2 })) {
            assertThrows(IllegalArgumentException::class.java) { ScreenCodec.detectConfig(record) }
        }
    }

    @Test fun malformedParameterSetsAndBoxLengthsAreRejected() {
        val invalidRecords = listOf(
            avc.clone().also { it[8] = 0x68 },
            avc.clone().also { it[5] = 0xe0.toByte() },
            avc.clone().also { it[22] = 0x67 },
            hevcRecord().also { it[28] = 0x42 },
            hevcRecord().also { it[29] = 0 },
            box("avcC", avc).also { ByteBuffer.wrap(it).putInt(it.size + 1) },
            box("avcC", avc).also { ByteBuffer.wrap(it).putInt(7) },
            box("avcC", avc) + byteArrayOf(0),
            box("avcC", avc) + box("hvcC", hevcRecord()),
        )
        invalidRecords.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) { ScreenCodec.detectConfig(record) }
        }
    }

    @Test fun highProfileAvcExtensionsAndNonParameterHevcArraysRemainSupported() {
        val extendedAvc = avc + byteArrayOf(-3, -8, -8, 1, 0, 2, 0x6d, 1)
        assertConfig(VideoCodec.H264, extendedAvc, extendedAvc)
        assertConfig(VideoCodec.H264, extendedAvc, box("avcC", extendedAvc))
        val hevc = hevcRecord(39, 40)
        assertConfig(VideoCodec.H265, hevc, hevc)
        val inBandHevc = hevcRecord(*intArrayOf())
        assertConfig(VideoCodec.H265, inBandHevc, inBandHevc)
        assertConfig(VideoCodec.H265, inBandHevc, box("hvcC", inBandHevc))
        assertThrows(IllegalArgumentException::class.java) { ScreenCodec.detectConfig(extendedAvc.copyOf(extendedAvc.size - 1)) }
    }

    private fun assertConfig(codec: VideoCodec, record: ByteArray, input: ByteArray) {
        val detected = ScreenCodec.detectConfig(input)
        assertEquals(codec, detected.first)
        assertArrayEquals(record, detected.second)
    }

    private fun box(type: String, data: ByteArray): ByteArray = ByteBuffer.allocate(8 + data.size)
        .putInt(8 + data.size).put(type.toByteArray()).put(data).array()

    private fun hevcRecord(vararg types: Int = intArrayOf(32, 33, 34)): ByteArray {
        val record = ByteArray(23 + 7 * types.size)
        record[0] = 1
        record[13] = 0xf0.toByte()
        record[15] = 0xfc.toByte()
        record[16] = 0xfc.toByte()
        record[17] = 0xf8.toByte()
        record[18] = 0xf8.toByte()
        record[21] = 3
        record[22] = types.size.toByte()
        var cursor = 23
        types.forEach { type ->
            record[cursor++] = type.toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = 0
            record[cursor++] = 2
            record[cursor++] = (type shl 1).toByte()
            record[cursor++] = 1
        }
        return record
    }

    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test fun readsTheFrameTimeFromTheScreenHeader() {
        val header = ByteArray(128)
        // NTP 32.32 little-endian at byte 8: 3 s + 0x40000000/2^32 s = 3.25 s.
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(3_250_000_000L, ScreenCodec.senderNanos(header))
        // One 1/60 s step, as the iPhone stamps consecutive main-screen frames.
        val next = raw + 0x0444_4444L
        for (i in 0 until 8) header[8 + i] = (next ushr (8 * i)).toByte()
        assertEquals(16_666_666L, ScreenCodec.senderNanos(header) - 3_250_000_000L)
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(12)))
    }
}
