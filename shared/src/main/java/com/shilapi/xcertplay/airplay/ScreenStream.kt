package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, stats, arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats, arrivalNanos: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val payload = if (body.size >= ScreenCodec.TAG_SIZE) {
                    val start = System.nanoTime()
                    ScreenCodec.decryptFrame(key, frameCounter.get(), header, body)
                        .also {
                            stats.decrypted(System.nanoTime() - start, body.size)
                            frameCounter.incrementAndGet()
                        }
                } else {
                    body
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                        "head=${payload.hexPrefix(16)} chacha=${AirPlayCrypto.chachaImplementation}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload), ScreenCodec.senderNanos(header), arrivalNanos)
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = try {
                    ScreenCodec.detectConfig(body)
                } catch (error: IllegalArgumentException) {
                    runCatching { onDiagnostic("Video: config codec=unknown configBytes=${body.size} result=rejected") }
                    throw error
                }
                val diagnostic = "Video: config codec=${codec.name.lowercase()} " +
                    "configBytes=${body.size} dataBytes=${codecData.size} result=accepted"
                Log.i(TAG, diagnostic)
                runCatching { onDiagnostic(diagnostic) }
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     * On a Tang with iOS 27 consecutive main-screen frames are exactly 1/60 s apart (1/30 s on the
     * cluster stream), and skipped frames leave whole multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        if (validAvcConfig(payload)) return VideoCodec.H264 to payload
        if (validHevcConfig(payload)) return VideoCodec.H265 to payload

        // A fourcc inside an SPS or unknown data is not a configuration box.
        var cursor = 0
        var selected: Pair<VideoCodec, ByteArray>? = null
        while (cursor + 8 <= payload.size) {
            val size = readU32Be(payload, cursor).toLong() and 0xffff_ffffL
            val headerSize = if (size == 1L) 16 else 8
            if (cursor + headerSize > payload.size) break
            val boxSize = when (size) {
                0L -> (payload.size - cursor).toLong()
                1L -> {
                    if (readU32Be(payload, cursor + 8) != 0) break
                    readU32Be(payload, cursor + 12).toLong() and 0xffff_ffffL
                }
                else -> size
            }
            if (boxSize < headerSize || boxSize > payload.size - cursor) break
            val end = cursor + boxSize.toInt()
            val codec = when (String(payload, cursor + 4, 4, Charsets.US_ASCII)) {
                "avcC" -> VideoCodec.H264
                "hvcC" -> VideoCodec.H265
                else -> null
            }
            if (codec != null) {
                require(selected == null) { "Ambiguous video configuration" }
                val data = payload.copyOfRange(cursor + headerSize, end)
                require(if (codec == VideoCodec.H264) validAvcConfig(data) else validHevcConfig(data)) {
                    "Invalid video configuration"
                }
                selected = codec to data
            }
            cursor = end
        }
        require(cursor == payload.size && selected != null) { "Unknown video configuration" }
        return selected
    }

    private fun validAvcConfig(data: ByteArray): Boolean {
        if (data.size < 7 || data[0] != 1.toByte()) return false
        var cursor = 6
        fun parameterSets(count: Int, type: Int, minimumLength: Int): Boolean {
            repeat(count) {
                if (cursor + 2 > data.size) return false
                val length = readU16Be(data, cursor)
                cursor += 2
                if (length < minimumLength || length > data.size - cursor || (data[cursor].toInt() and 31) != type) return false
                cursor += length
            }
            return true
        }
        val spsCount = data[5].toInt() and 31
        if (spsCount == 0 || !parameterSets(spsCount, 7, 4) || cursor >= data.size) return false
        val ppsCount = data[cursor++].toInt() and 255
        if (ppsCount == 0 || !parameterSets(ppsCount, 8, 1)) return false
        if (cursor == data.size) return true
        // High-profile records may append chroma/bit-depth fields and SPS extension NALs.
        if ((data[1].toInt() and 255) !in AVC_EXTENDED_PROFILES || cursor + 4 > data.size) return false
        cursor += 3
        val extensionCount = data[cursor++].toInt() and 255
        return parameterSets(extensionCount, 13, 1) && cursor == data.size
    }

    private fun validHevcConfig(data: ByteArray): Boolean {
        if (data.size < 23 || data[0] != 1.toByte()) return false
        val arrayCount = data[22].toInt() and 255
        var cursor = 23
        var hasNal = false
        repeat(arrayCount) {
            if (cursor + 3 > data.size) return false
            val type = data[cursor++].toInt() and 63
            val count = readU16Be(data, cursor)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > data.size) return false
                val length = readU16Be(data, cursor)
                cursor += 2
                if (length < 2 || length > data.size - cursor ||
                    ((data[cursor].toInt() ushr 1) and 63) != type || (data[cursor + 1].toInt() and 7) == 0) return false
                cursor += length
                hasNal = true
            }
        }
        // Parameter sets may be supplied in-band; then the fixed HEVC header must identify the record.
        val fixedHeader = (data[13].toInt() and 0xf0) == 0xf0 &&
            (data[15].toInt() and 0xfc) == 0xfc && (data[16].toInt() and 0xfc) == 0xfc &&
            (data[17].toInt() and 0xf8) == 0xf8 && (data[18].toInt() and 0xf8) == 0xf8
        return (hasNal || fixedHeader) && cursor == data.size
    }

    private val AVC_EXTENDED_PROFILES = setOf(100, 110, 122, 144, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
