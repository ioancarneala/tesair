package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayControlDiagnosticsTest {
    @Test fun identifiesNegotiationWithoutRequestHeadersQueriesOrBodyContents() {
        assertEquals("airplay control request method=POST route=pair-verify contentBytes=128",
            AirPlayControlDiagnostics.request("POST", "rtsp://192.168.49.1/pair-verify?token=secret", 128))
        assertEquals("airplay control request method=SETUP route=other contentBytes=64",
            AirPlayControlDiagnostics.request("SETUP", "rtsp://192.168.49.1/private-session", 64))
        assertEquals("airplay control request method=OTHER route=other contentBytes=0",
            AirPlayControlDiagnostics.request("Jane's iPhone\npassword=secret", "/private-secret", 0))
    }

    @Test fun eventChannelEofIsPersistedWithoutChangingSessionClosure() {
        val (session, events) = eventSession()
        readEvents(session, ByteArrayInputStream(byteArrayOf()))

        val ended = events.single { it.startsWith("airplay event connection closed") }
        assertTrue(ended, ended.contains("reason=eof failureClass=none elapsedMs="))
        assertTrue(session.isClosed)
    }

    @Test fun eventReadFailureReportsOnlyItsClassAndKeepsPrivateExceptionTextOutOfTheJournal() {
        val (session, events) = eventSession()
        readEvents(session, object : InputStream() {
            override fun read(): Int = throw SocketException("synthetic-private-network-detail")
        })

        val ended = events.single { it.startsWith("airplay event connection closed") }
        assertTrue(ended, ended.contains("reason=read_error failureClass=SocketException elapsedMs="))
        assertFalse(events.joinToString("\n").contains("synthetic-private-network-detail"))
        assertTrue(session.isClosed)
    }

    @Test fun anAlreadyClosedSessionIsClassifiedAsLocalClose() {
        val (session, events) = eventSession()
        session.close()
        readEvents(session, ByteArrayInputStream(byteArrayOf()))

        assertTrue(events.single { it.startsWith("airplay event connection closed") }
            .contains("reason=local_close failureClass=none elapsedMs="))
    }

    private fun eventSession(): Pair<AirPlaySession, MutableList<String>> {
        val events = mutableListOf<String>()
        val session = AirPlaySession(socket = Socket(), config = AirPlayConfig(deviceName = "test",
            deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { events += message }
            }, media = object : AirPlayMediaHandler {})
        return session to events
    }

    private fun readEvents(session: AirPlaySession, input: InputStream) {
        val event = object : Socket() {
            override fun getInputStream() = input
            override fun getOutputStream() = ByteArrayOutputStream()
        }
        session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java)
            .apply { isAccessible = true }.invoke(session, event)
    }
}
