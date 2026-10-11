package com.shilapi.xcertplay.browser

import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.airplay.VideoCodec
import java.time.Duration
import java.util.function.BiConsumer
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserTouchTest {
    private lateinit var output: BrowserStreamOutput
    private val reports = mutableListOf<List<AirPlayContact>>()

    @Before fun setUp() {
        output = BrowserStreamOutput(RuntimeEnvironment.getApplication())
        output.onTouch = { reports.add(it) }
        val avcc = "01640028ffe1000b67640028ace80780227e5401000468ce3c80"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        output.onVideoConfig(110, avcc)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(output.status.startsWith("Direct CarPlay video"))
    }

    @After fun tearDown() {
        output.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun immediateTapReleasesAtItsDownPositionWithoutAddingDelay() {
        input("down", 0.875, 0.75)
        input("up")
        assertEquals(listOf(contact(0.875, 0.75, true), contact(0.875, 0.75, false)), reports)
        val hid = reports.map { contacts ->
            AirPlayHid.touchReport(contacts.map { it.copy(x = it.x * 1920, y = it.y * 1080) })
        }
        assertEquals(1, hid[0][1].toInt())
        assertEquals(0, hid[1][1].toInt())
        assertArrayEquals(hid[0].copyOfRange(2, 6), hid[1].copyOfRange(2, 6))
    }

    @Test fun dragReleaseKeepsTheLastAcceptedMove() {
        input("down", 0.2, 0.3)
        input("move", 0.8, 0.7)
        input("move", 2.0, 0.4)
        input("up")
        assertEquals(listOf(contact(0.2, 0.3, true), contact(0.8, 0.7, true), contact(0.8, 0.7, false)), reports)
    }

    @Test fun websocketInputDoesNotWaitForTheAndroidMainLooper() {
        val callbackThreads = mutableListOf<Thread>()
        output.onTouch = { reports.add(it); callbackThreads.add(Thread.currentThread()) }
        val receiver = thread(name = "test-websocket-input") {
            input("down", 0.2, 0.3)
            input("move", 0.8, 0.7)
            input("up")
        }
        receiver.join(1000)
        assertFalse("Input is blocked by the paused Android UI thread", receiver.isAlive)
        assertEquals(listOf(contact(0.2, 0.3, true), contact(0.8, 0.7, true), contact(0.8, 0.7, false)), reports)
        assertEquals(listOf(receiver, receiver, receiver), callbackThreads)
    }

    @Test fun cancellationReleasesOnceAndRejectsOrphanMoves() {
        input("move", 0.1, 0.1)
        input("down", 0.8, 0.6)
        input("cancel")
        input("cancel")
        input("move", 0.2, 0.2)
        assertEquals(listOf(contact(0.8, 0.6, true), contact(0.8, 0.6, false)), reports)
    }

    @Test fun anotherDownReleasesThePreviousContactFirst() {
        input("down", 0.2, 0.3)
        input("down", 0.8, 0.7)
        input("up")
        assertEquals(listOf(contact(0.2, 0.3, true), contact(0.2, 0.3, false),
            contact(0.8, 0.7, true), contact(0.8, 0.7, false)), reports)
    }

    @Test fun legacyGestureReleasesAtItsFinalPoint() {
        output.onControlMessage(JSONObject("""{"type":"gesture","duration":40,"points":[[0.2,0.3],[0.8,0.7]]}"""))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(listOf(contact(0.2, 0.3, true), contact(0.8, 0.7, true), contact(0.8, 0.7, false)), reports)
    }

    @Test fun stoppingTheStreamReleasesTheCurrentContact() {
        input("down", 0.4, 0.9)
        output.onScreenStreamActive(110, false)
        input("move", 0.7, 0.8)
        input("down", 0.1, 0.1)
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun resizeReleasesWithoutWaitingForTheAndroidMainLooper() {
        input("down", 0.4, 0.9)
        output.setViewArea(null, 1600, 900)
        input("move", 0.7, 0.8)
        input("up")
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun browserViewportChangeReleasesBeforeItsUiCallback() {
        var viewportCalls = 0
        output.onViewport = { _, _ -> viewportCalls++ }
        input("down", 0.4, 0.9)
        val server = bridge()
        @Suppress("UNCHECKED_CAST")
        val viewport = BridgeServer::class.java.getDeclaredField("viewport").apply { isAccessible = true }
            .get(server) as BiConsumer<Int, Int>
        viewport.accept(1600, 900)
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
        assertEquals(0, viewportCalls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, viewportCalls)
    }

    @Test fun viewerDisconnectReleasesWithoutWaitingForTheAndroidMainLooper() {
        input("down", 0.4, 0.9)
        val disconnected = BridgeServer::class.java.getDeclaredField("connectionChanged").apply { isAccessible = true }
            .get(bridge()) as Runnable
        disconnected.run()
        input("move", 0.7, 0.8)
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun closingReleasesImmediatelyAndRejectsFurtherInput() {
        input("down", 0.4, 0.9)
        output.close()
        input("down", 0.1, 0.1)
        input("up")
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun unsupportedCodecReleasesImmediately() {
        input("down", 0.4, 0.9)
        output.onVideoCodec(110, VideoCodec.H265)
        input("move", 0.7, 0.8)
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun liveInputSupersedesALegacyGestureQueuedOnTheMainLooper() {
        output.onControlMessage(JSONObject("""{"type":"gesture","duration":40,"points":[[0.2,0.3],[0.8,0.7]]}"""))
        input("down", 0.4, 0.9)
        input("up")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(listOf(contact(0.4, 0.9, true), contact(0.4, 0.9, false)), reports)
    }

    @Test fun resizeCancelsALegacyGestureBeforeItsFirstQueuedPoint() {
        output.onControlMessage(JSONObject("""{"type":"gesture","duration":40,"points":[[0.2,0.3],[0.8,0.7]]}"""))
        output.setViewArea(null, 1600, 900)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertTrue(reports.isEmpty())
    }

    @Test fun cancellingAnActiveLegacyGestureRejectsItsRemainingPoints() {
        output.onControlMessage(JSONObject("""{"type":"gesture","duration":40,"points":[[0.2,0.3],[0.8,0.7]]}"""))
        shadowOf(Looper.getMainLooper()).idle()
        input("cancel")
        assertEquals(listOf(contact(0.2, 0.3, true), contact(0.2, 0.3, false)), reports)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(2, reports.size)
    }

    private fun bridge(): BridgeServer = BrowserStreamOutput::class.java.getDeclaredField("server")
        .apply { isAccessible = true }.get(output) as BridgeServer

    private fun input(action: String, x: Double? = null, y: Double? = null) {
        val message = JSONObject().put("type", "touch").put("action", action)
        if (x != null) message.put("x", x)
        if (y != null) message.put("y", y)
        output.onControlMessage(message)
    }

    private fun contact(x: Double, y: Double, down: Boolean) = listOf(AirPlayContact(0, x, y, down))
}
