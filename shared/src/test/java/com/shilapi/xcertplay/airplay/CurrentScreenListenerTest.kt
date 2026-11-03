package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A replaced screen stream's thread may still be delivering when its successor starts. Its codec, config and
 * frames must not reach the sink, which by then belongs to the successor's decoder.
 */
@RunWith(RobolectricTestRunner::class) // CarPlayMediaEngine.onScreen logs through android.util.Log.
@Config(sdk = [29], manifest = Config.NONE)
class CurrentScreenListenerTest {
    private sealed class Event {
        data class Codec(val type: Int, val codec: VideoCodec) : Event()
        data class CodecData(val type: Int, val data: List<Byte>) : Event()
        data class ConfigRejected(val type: Int) : Event()
        data class Frame(val type: Int, val data: List<Byte>) : Event()
        data class TimedFrame(val type: Int, val data: List<Byte>, val senderNanos: Long, val arrivalNanos: Long) : Event()
    }

    /** Records the four video deliveries; the 4-argument frame is recorded on its own, not via the 3-argument default. */
    private class RecordingSink : MediaSink {
        val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
        val screenStates: MutableList<Pair<Int, Boolean>> = Collections.synchronizedList(mutableListOf())
        override fun onScreenStreamActive(type: Int, active: Boolean) { screenStates += type to active }
        override fun onVideoCodec(type: Int, codec: VideoCodec) { events += Event.Codec(type, codec) }
        override fun onVideoConfig(type: Int, codecData: ByteArray) { events += Event.CodecData(type, codecData.toList()) }
        override fun onVideoConfigRejected(type: Int) { events += Event.ConfigRejected(type) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events += Event.Frame(type, naluBytes.toList()) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            events += Event.TimedFrame(type, naluBytes.toList(), senderNanos, arrivalNanos)
        }
        fun take(): List<Event> = synchronized(events) { events.toList().also { events.clear() } }
    }

    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11)
    private val timedFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x22)

    private fun ScreenStream.Listener.deliverAll(codec: VideoCodec = VideoCodec.H265) {
        onCodec(codec)
        onConfig(config)
        onFrame(frame)
        onFrame(timedFrame, 1_234_567_890L, 9_876_543_210L)
    }

    private fun expected(type: Int, codec: VideoCodec = VideoCodec.H265) = listOf(
        Event.Codec(type, codec),
        Event.CodecData(type, config.toList()),
        Event.Frame(type, frame.toList()),
        Event.TimedFrame(type, timedFrame.toList(), 1_234_567_890L, 9_876_543_210L),
    )

    @Test
    fun currentStreamDeliversEveryCallbackWithItsTypeAndTimes() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(111, sink) { current.get() }

        listener.deliverAll()

        assertEquals(expected(111), sink.take())
    }

    @Test
    fun replacedStreamDeliversNothing() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(110, sink) { current.get() }
        listener.deliverAll(VideoCodec.H264)
        assertEquals(expected(110, VideoCodec.H264), sink.take())

        current.set(false) // A replacing stream for the same key has been registered.
        listener.deliverAll(VideoCodec.H264)

        assertEquals(emptyList<Event>(), sink.take())
    }

    @Test
    fun currencyIsCheckedAtEachDeliveryNotOnce() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        var checks = 0
        val listener = currentScreenListener(110, sink) { checks++; current.get() }

        listener.onCodec(VideoCodec.H264)
        listener.onConfig(config)
        current.set(false)
        listener.onFrame(frame)
        listener.onFrame(timedFrame, 1L, 2L)

        assertEquals(4, checks)
        assertEquals(
            listOf(Event.Codec(110, VideoCodec.H264), Event.CodecData(110, config.toList())),
            sink.take(),
        )
    }

    @Test fun configRejectionIsDeliveredOnlyWhileItsStreamIsCurrent() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(110, sink) { current.get() }
        listener.onConfigRejected()
        assertEquals(listOf(Event.ConfigRejected(110)), sink.take())
        current.set(false)
        listener.onConfigRejected()
        assertEquals(emptyList<Event>(), sink.take())
    }

    @Test fun replacedStreamsLateClosureCannotEndItsSuccessorsSession() {
        val engine = CarPlayMediaEngine(RecordingSink())
        val session = testSession()
        try {
            withSharedSecret(session)
            val streams = streamsOf(engine)
            val key = CarPlayMediaEngine.StreamKey(session, 110)
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
            val previousListener = listenerOf(streams.getValue(key) as ScreenStream)
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
            val current = streams.getValue(key) as ScreenStream

            previousListener.onClosed(IllegalArgumentException("Unknown video configuration"))
            assertFalse(sessionClosed(session))
            assertSame(current, streams[key])

            listenerOf(current).onClosed(null)
            assertTrue("The current stream still owns session closure", sessionClosed(session))
            assertFalse(streams.containsKey(key))
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun aNewSessionOwnsTheSameScreenAndAnOldSessionCannotDeliverItsVideo() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = testSession()
        val current = testSession()
        try {
            withSharedSecret(old)
            withSharedSecret(current)
            val streams = streamsOf(engine)
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            val previous = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(old, 110)) as ScreenStream)
            engine.onScreen(current, 110, mapOf("streamConnectionID" to 2L))
            val latest = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(current, 110)) as ScreenStream)

            previous.deliverAll()
            assertEquals("The sink belongs to the newer session", emptyList<Event>(), sink.take())
            latest.deliverAll(VideoCodec.H264)
            assertEquals(expected(110, VideoCodec.H264), sink.take())
        } finally {
            engine.onSessionClosed(old)
            engine.onSessionClosed(current)
            old.close()
            current.close()
        }
    }

    @Test fun oldSessionTeardownClosureAndLateStreamClosureCannotDeactivateTheNewScreen() {
        for (finish in listOf("teardown", "session", "stream")) {
            val sink = RecordingSink()
            val engine = CarPlayMediaEngine(sink)
            val old = testSession()
            val current = testSession()
            try {
                withSharedSecret(old)
                withSharedSecret(current)
                val streams = streamsOf(engine)
                engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
                val previous = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(old, 110)) as ScreenStream)
                engine.onScreen(current, 110, mapOf("streamConnectionID" to 2L))
                val latest = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(current, 110)) as ScreenStream)
                sink.screenStates.clear()

                when (finish) {
                    "teardown" -> engine.onTeardown(old, 110)
                    "session" -> engine.onSessionClosed(old)
                    else -> previous.onClosed(null)
                }

                assertEquals("$finish must not deactivate the newer screen", emptyList<Pair<Int, Boolean>>(),
                    sink.screenStates.toList())
                assertFalse(sessionClosed(current))
                latest.deliverAll(VideoCodec.H264)
                assertEquals(expected(110, VideoCodec.H264), sink.take())

                latest.onClosed(null)
                assertEquals(listOf(110 to false), sink.screenStates.toList())
                assertTrue("The current owner still closes its own session", sessionClosed(current))
            } finally {
                engine.onSessionClosed(old)
                engine.onSessionClosed(current)
                old.close()
                current.close()
            }
        }
    }

    @Test fun ownershipHandoverWaitsForAnInFlightSinkDeliveryBeforeInstallingTheNewScreen() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handoverStarted = CountDownLatch(1)
        val handoverDone = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val sink = object : MediaSink {
            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                if (codecData[0] == 1.toByte()) {
                    events += "old-started"
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    events += "old-finished"
                } else events += "new-config"
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (active) events += "installed"
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val old = testSession()
        val current = testSession()
        var delivery: Thread? = null
        var handover: Thread? = null
        try {
            withSharedSecret(old)
            withSharedSecret(current)
            val streams = streamsOf(engine)
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            val previous = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(old, 110)) as ScreenStream)
            events.clear()
            delivery = Thread {
                try { previous.onConfig(byteArrayOf(1)) } catch (error: Throwable) { errors += error }
            }.apply { start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            handover = Thread {
                handoverStarted.countDown()
                try { engine.onScreen(current, 110, mapOf("streamConnectionID" to 2L)) }
                catch (error: Throwable) { errors += error }
                finally { handoverDone.countDown() }
            }.apply { start() }
            assertTrue(handoverStarted.await(2, TimeUnit.SECONDS))
            assertFalse("Handover must wait for the sink delivery", handoverDone.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            delivery.join(2_000)
            handover.join(2_000)
            assertFalse(delivery.isAlive)
            assertFalse(handover.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
            val latest = listenerOf(streams.getValue(CarPlayMediaEngine.StreamKey(current, 110)) as ScreenStream)
            latest.onConfig(byteArrayOf(2))
            previous.onConfig(byteArrayOf(1))
            assertEquals(listOf("old-started", "old-finished", "installed", "new-config"), events.toList())
        } finally {
            release.countDown()
            delivery?.join(2_000)
            handover?.join(2_000)
            engine.onSessionClosed(old)
            engine.onSessionClosed(current)
            old.close()
            current.close()
        }
    }

    @Test
    fun onScreenStopsTheReplacedStreamsListenerForTheSameKey() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        try {
            withSharedSecret(session)
            val streams = streamsOf(engine)
            val mainKey = CarPlayMediaEngine.StreamKey(session, 110)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
            val first = streams[mainKey] as ScreenStream
            val firstListener = listenerOf(first)
            firstListener.deliverAll()
            assertEquals(expected(110), sink.take())

            // The cluster stream of the same session has a different key and is not affected by the replacement.
            assertNotNull(engine.onScreen(session, 111, mapOf("streamConnectionID" to 3L)))
            val clusterListener = listenerOf(streams[CarPlayMediaEngine.StreamKey(session, 111)] as ScreenStream)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
            val second = streams[mainKey] as ScreenStream
            assertNotSame(first, second)
            val secondListener = listenerOf(second)

            // The replaced stream's thread was already past its read when it was closed and still delivers.
            firstListener.deliverAll()
            assertEquals(emptyList<Event>(), sink.take())

            secondListener.deliverAll(VideoCodec.H264)
            assertEquals(expected(110, VideoCodec.H264), sink.take())
            clusterListener.deliverAll()
            assertEquals(expected(111), sink.take())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun withSharedSecret(session: AirPlaySession) {
        // onScreen derives the stream key from the pair-verify secret; any 32 bytes will do for routing.
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32) { it.toByte() })
        assertTrue(session.sharedSecret != null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamsOf(engine: CarPlayMediaEngine): Map<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>

    private fun listenerOf(screen: ScreenStream): ScreenStream.Listener =
        ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(screen) as ScreenStream.Listener

    private fun sessionClosed(session: AirPlaySession): Boolean =
        (AirPlaySession::class.java.getDeclaredField("closed").apply { isAccessible = true }
            .get(session) as AtomicBoolean).get()

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
