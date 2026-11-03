package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamConfigTest {
    @Test fun unknownConfigKeepsTransportOpenAndAcceptsTheNextValidConfigWithoutInventingACodec() {
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val configured = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val codecCalls = AtomicInteger()
        val receivedConfig = AtomicReference<ByteArray>()
        val validConfig = "01640028ffe1000b67640028ace80780227e5401000468ce3c80"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val stream = ScreenStream(ByteArray(32)) { diagnostics.add(it) }
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onCodec(codec: VideoCodec) {
                assertEquals(VideoCodec.H264, codec)
                codecCalls.incrementAndGet()
            }
            override fun onConfig(codecData: ByteArray) {
                receivedConfig.set(codecData)
                configured.countDown()
            }
            override fun onConfigRejected() { rejected.countDown() }
            override fun onClosed(cause: Throwable?) {
                failure.set(cause)
                closed.countDown()
            }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val data = "unknown-private-config".toByteArray()
                socket.sendConfig(data)
                assertTrue("Unknown configuration must be reported", rejected.await(3, TimeUnit.SECONDS))
                assertEquals(1L, closed.count)
                assertEquals(0, codecCalls.get())
                socket.sendConfig(validConfig)
                assertTrue("The same transport must accept a later valid configuration", configured.await(3, TimeUnit.SECONDS))
                assertEquals(1L, closed.count)
                assertEquals(1, codecCalls.get())
                assertArrayEquals(validConfig, receivedConfig.get())
                val head = data.take(24).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertTrue(diagnostics.contains("Video: config codec=unknown configBytes=${data.size} head=$head result=rejected"))
                assertFalse(diagnostics.any { it.contains("unknown-private-config") || it.contains("body=") })
            }
            assertTrue("Only peer EOF should end the stream", closed.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            stream.close()
        }
    }

    private fun Socket.sendConfig(data: ByteArray) {
        val header = ByteArray(128)
        for (index in 0 until 4) header[index] = (data.size ushr (index * 8)).toByte()
        header[4] = 1
        getOutputStream().apply {
            write(header)
            write(data)
            flush()
        }
    }
}
