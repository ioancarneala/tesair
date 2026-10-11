package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class QueuedTouchSessionTest {
    private class EventSocket : Socket() {
        val bytes = ByteArrayOutputStream()
        override fun getOutputStream() = bytes
    }

    private fun session(): Pair<AirPlaySession, EventSocket> {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01", sourceVersion = "1",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            },
        )
        val event = EventSocket()
        for ((name, value) in listOf("eventSocket" to event,
            "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        setup(session)
        return session to event
    }

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 110L)))
    }

    private fun teardown(session: AirPlaySession) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 110L)))))
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request)
    }

    @Test fun streamReplacedWhileTouchWaitsForEventWriterRejectsOldToken() =
        rejectsWaitingTouch(::setup)

    @Test fun streamTornDownWhileTouchWaitsForEventWriterRejectsOldToken() =
        rejectsWaitingTouch(::teardown)

    private fun rejectsWaitingTouch(invalidate: (AirPlaySession) -> Unit) {
        val (session, event) = session()
        val token = requireNotNull(session.mainScreenSessionToken())
        val lock = session.javaClass.getDeclaredField("eventWriteLock").apply { isAccessible = true }.get(session)
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val writer = Thread {
            try {
                result.set(session.sendTouch(listOf(AirPlayContact(0, 0.2, 0.3, true)), token))
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            synchronized(lock) {
                writer.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (writer.state != Thread.State.BLOCKED && writer.isAlive && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals("Touch must be waiting on the event writer before changing the stream",
                    Thread.State.BLOCKED, writer.state)
                invalidate(session)
                assertNotSame(token, session.mainScreenSessionToken())
            }
            writer.join(5_000)
            assertFalse("Touch writer did not finish", writer.isAlive)
            assertNull(failure.get())
            assertEquals(false, result.get())
            assertEquals("An old touch must not write to the replacement stream", 0, event.bytes.size())
        } finally {
            session.close()
            writer.join(5_000)
        }
    }

    @Test fun currentTokenAndExistingPublicTouchSignatureStillDeliver() {
        val (session, event) = session()
        try {
            val contacts = listOf(AirPlayContact(0, 0.2, 0.3, true))
            assertTrue(session.sendTouch(contacts, requireNotNull(session.mainScreenSessionToken())))
            assertTrue(session.sendTouch(contacts.map { it.copy(down = false) }))
            val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(event.bytes.toByteArray()).data
            val commands = RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<*, *> }
            assertEquals(listOf("hidSendReport", "hidSendReport"), commands.map { it["type"] })
            assertEquals(listOf(1, 0), commands.map { ((it["hidReport"] as ByteArray)[1]).toInt() })
        } finally { session.close() }
    }
}
