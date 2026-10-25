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
    @Test fun unknownConfigClosesTransportForRecoveryWithoutInventingACodecOrLoggingItsData() {
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val codecCalls = AtomicInteger()
        val stream = ScreenStream(ByteArray(32)) { diagnostics.add(it) }
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onCodec(codec: VideoCodec) { codecCalls.incrementAndGet() }
            override fun onClosed(cause: Throwable?) {
                failure.set(cause)
                closed.countDown()
            }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val data = "unknown-private-config".toByteArray()
                val header = ByteArray(128)
                header[0] = data.size.toByte()
                header[4] = 1
                socket.getOutputStream().apply {
                    write(header)
                    write(data)
                    flush()
                }
                assertTrue("Invalid configuration must end the stream", closed.await(3, TimeUnit.SECONDS))
                assertTrue(failure.get() is IllegalArgumentException)
                assertEquals(0, codecCalls.get())
                assertTrue(diagnostics.contains("Video: config codec=unknown configBytes=${data.size} result=rejected"))
                assertFalse(diagnostics.any { it.contains("unknown-private-config") || it.contains("body=") })
            }
        } finally {
            stream.close()
        }
    }
}
