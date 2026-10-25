package com.shilapi.xcertplay.browser;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BrowserConnectionHealthTest {
    private BridgeServer server;
    private NanoWSD.WebSocket client;
    private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private long pts;

    @Before public void setUp() {
        server = new BridgeServer(RuntimeEnvironment.getApplication());
        server.setDiagnosticHandler(events::add);
        NanoHTTPD.IHTTPSession session = (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{NanoHTTPD.IHTTPSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInputStream")) return new ByteArrayInputStream(new byte[0]);
                    if (method.getName().equals("getHeaders")) return Map.of();
                    return null;
                });
        client = server.openWebSocket(session);
        ReflectionHelpers.setField(client, "out", wire);
        ReflectionHelpers.callInstanceMethod(client, "onOpen");
    }

    @After public void tearDown() throws Exception {
        server.stop();
        Thread sender = ReflectionHelpers.getField(client, "sender");
        if (sender != null) { sender.join(2000); assertFalse(sender.isAlive()); }
    }

    @Test public void heartbeatRespondsWhileWaitingForIphoneVideo() throws Exception {
        message("{\"type\":\"ping\"}");
        await(() -> wireText().contains("\"type\":\"pong\""));
        assertEquals(1, server.clientCount());
        assertEquals(List.of("viewer_open"), events);
    }

    @Test public void consentBroadcastSurvivesAStatusUpdateBeforeTheWriterDrainsControls() throws Exception {
        await(() -> senderWaitingIn(FrameQueue.class, "poll"));
        FrameQueue queue = ReflectionHelpers.getField(client, "queue");
        synchronized (queue) {
            // Keep the writer in poll until both independently coalesced messages are pending.
            server.telemetryConsentChanged();
            server.browserStatus("ready_after_consent", false);
        }
        await(() -> wireText().contains("\"type\":\"telemetry_consent\"")
                && wireText().contains("ready_after_consent"));
        assertEquals(1, server.clientCount());
        assertTrue(events.stream().noneMatch(value -> value.startsWith("viewer_close")));
    }

    @Test public void renderStallGetsOneRecoveryThenClosesIfFramesStillAreNotAcknowledged() throws Exception {
        fillFrameWindow();
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600));
        message("{\"type\":\"ping\"}");
        await(() -> wireText().contains("\"type\":\"recover\""));
        assertEquals(1, server.clientCount());
        assertEquals(1, events.stream().filter(value -> value.startsWith("render_ack_recovery")).count());

        fillFrameWindow();
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600));
        message("{\"type\":\"ping\"}");
        await(() -> server.clientCount() == 0);
        assertTrue(events.stream().anyMatch(value -> value.startsWith("viewer_close reason=render_ack_timeout")));
        assertEquals(1, events.stream().filter(value -> value.startsWith("viewer_close")).count());
    }

    @Test public void aRenderedRecoveryKeepsTheConnectionAlive() throws Exception {
        fillFrameWindow();
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600));
        message("{\"type\":\"ping\"}");
        await(() -> wireText().contains("\"type\":\"recover\""));
        fillFrameWindow();
        message("{\"type\":\"ack\",\"pts\":" + pts + "}");
        await(() -> sentFrames().isEmpty());
        assertEquals(1, server.clientCount());
        assertTrue(events.stream().noneMatch(value -> value.startsWith("viewer_close")));
    }

    @Test public void remoteCloseRecordsDirectionAndCodeWithoutRecordingItsText() {
        ReflectionHelpers.callInstanceMethod(client, "onClose",
                from(NanoWSD.WebSocketFrame.CloseCode.class, NanoWSD.WebSocketFrame.CloseCode.GoingAway),
                from(String.class, "private remote reason"), from(boolean.class, true));
        assertEquals(0, server.clientCount());
        assertTrue(events.stream().anyMatch(value -> value.contains("remote=true code=1001")));
        assertFalse(events.toString().contains("private remote reason"));
    }

    @Test public void untrustedBrowserDiagnosticsAreAllowlistedBoundedAndRateLimited() throws Exception {
        assertNull(BridgeServer.browserDiagnostic(new JSONObject("{\"event\":\"private arbitrary value\"}")));
        String event = BridgeServer.browserDiagnostic(new JSONObject()
                .put("event", "decoder_error").put("frameAgeMs", Long.MAX_VALUE).put("packetAgeMs", -1)
                .put("decodeQueue", 9000).put("detail", "private error text"));
        assertTrue(event.contains("frameAgeMs=3600000 packetAgeMs=0 decodeQueue=1000"));
        assertFalse(event.contains("private"));
        for (int i = 0; i < 10; i++) message("{\"type\":\"diagnostic\",\"event\":\"decoder_error\"}");
        assertEquals(1, events.stream().filter(value -> value.startsWith("browser_decoder_error")).count());
    }

    @Test public void batchedMovesPreserveTheFinalPositionBeforeRelease() throws Exception {
        List<String> touches = new java.util.ArrayList<>();
        server.setGestureHandler(value -> touches.add(value.optString("action") + ":" + value.optDouble("x", -1)));
        message("{\"type\":\"touch\",\"action\":\"down\",\"x\":0.1,\"y\":0.2}");
        message("{\"type\":\"touch\",\"action\":\"move\",\"x\":0.5,\"y\":0.2}");
        message("{\"type\":\"touch\",\"action\":\"move\",\"x\":0.9,\"y\":0.2}");
        message("{\"type\":\"touch\",\"action\":\"up\"}");
        assertEquals(List.of("down:0.1", "move:0.5", "move:0.9", "up:-1.0"), touches);
    }

    @Test public void latencyReportsAcceptOnlyBoundedNumericMeasurements() throws Exception {
        String event = BridgeServer.browserDiagnostic(new JSONObject().put("event", "latency")
                .put("samples", Long.MAX_VALUE).put("decodeAvgUs", -1).put("decodeMaxUs", Long.MAX_VALUE)
                .put("drawAvgUs", 120).put("drawMaxUs", 240).put("detail", "private data"));
        assertEquals("browser_latency samples=10000 decodeAvgUs=0 decodeMaxUs=10000000"
                + " drawAvgUs=120 drawMaxUs=240 decodeQueue=0", event);
        message("{\"type\":\"diagnostic\",\"event\":\"latency\"}");
        message("{\"type\":\"diagnostic\",\"event\":\"latency\"}");
        message("{\"type\":\"diagnostic\",\"event\":\"decoder_error\"}");
        assertEquals(1, events.stream().filter(value -> value.startsWith("browser_latency")).count());
        assertEquals(1, events.stream().filter(value -> value.startsWith("browser_decoder_error")).count());
    }

    @Test public void acknowledgingAFullWindowReleasesCreditWithoutReconnect() throws Exception {
        fillFrameWindow();
        long previous = pts;
        server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
        message("{\"type\":\"ack\",\"pts\":" + previous + "}");
        await(() -> sentFrames().containsKey(pts));
        assertEquals(1, server.clientCount());
        assertTrue(events.stream().noneMatch(value -> value.startsWith("render_ack_recovery")));
    }

    @Test public void formatArrivingDuringQueueWaitKeepsAllThreeFramesInTheCreditWindow() throws Exception {
        await(() -> senderWaitingIn(FrameQueue.class, "poll"));
        FrameQueue queue = ReflectionHelpers.getField(client, "queue");
        synchronized (queue) {
            server.videoConfig(new byte[]{0, 0, 0, 1, 0x67, 0x64, 0, 0x28}, 1920, 1080);
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
        }
        await(() -> sentFrames().size() == 1);
        for (int count = 2; count <= 3; count++) {
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
            int expected = count;
            await(() -> sentFrames().size() == expected);
        }
        await(() -> senderWaitingIn(FrameSendSignal.class, "awaitChange"));
        assertTrue(senderWaitingIn(FrameSendSignal.class, "awaitChange"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600));
        message("{\"type\":\"ping\"}");
        await(() -> wireText().contains("\"type\":\"recover\""));
        assertEquals(1, server.clientCount());
    }

    @Test public void resetBetweenPollAndDispatchCannotReintroduceOldFrameCredits() throws Exception {
        await(() -> senderWaitingIn(FrameQueue.class, "poll"));
        Object dispatchLock = ReflectionHelpers.getField(client, "dispatchLock");
        long oldPts;
        synchronized (dispatchLock) {
            server.videoConfig(new byte[]{0, 0, 0, 1, 0x67, 0x64, 0, 0x28}, 1920, 1080);
            oldPts = ++pts;
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, oldPts);
            await(() -> {
                Thread sender = ReflectionHelpers.getField(client, "sender");
                return sender.getState() == Thread.State.BLOCKED;
            });
            ReflectionHelpers.callInstanceMethod(client, "reset");
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
        }
        await(() -> sentFrames().containsKey(pts));
        assertFalse(sentFrames().containsKey(oldPts));
        message("{\"type\":\"ack\",\"pts\":" + oldPts + "}");
        assertEquals(1, sentFrames().size());
        message("{\"type\":\"ack\",\"pts\":" + pts + "}");
        assertTrue(sentFrames().isEmpty());
        assertEquals(1, server.clientCount());
    }

    @Test public void lostIdrRequestsAreThrottledAndRetriedWithoutMoreIncomingFrames() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        server.setKeyframeHandler(requests::incrementAndGet);
        server.videoConfig(new byte[]{0, 0, 0, 1, 0x67, 0x64, 0, 0x28}, 1920, 1080);
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        message("{\"type\":\"ping\"}");
        await(() -> requests.get() == 1);
        for (int i = 0; i < 100; i++) server.frame(new byte[]{0, 0, 1, 0x41, 1}, false, ++pts);
        assertEquals(1, requests.get());
        assertEquals(0, events.stream().filter(value -> value.startsWith("frame_queue_recovery")).count());
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        await(() -> requests.get() == 2);
        assertEquals(1, server.clientCount());
        server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
        await(() -> sentFrames().containsKey(pts));
        FrameQueue queue = ReflectionHelpers.getField(client, "queue");
        assertFalse(queue.waitingForKey());
        assertEquals(2, requests.get());
    }

    @Test public void browserResetDuringIdrCooldownClearsCreditsAndRetriesWhenAllowed() throws Exception {
        fillFrameWindow();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        server.setKeyframeHandler(requests::incrementAndGet);
        message("{\"type\":\"keyframe\"}");
        assertTrue(sentFrames().isEmpty());
        FrameQueue queue = ReflectionHelpers.getField(client, "queue");
        assertTrue(queue.waitingForKey());
        assertEquals(0, requests.get());
        server.frame(new byte[]{0, 0, 1, 0x41, 1}, false, ++pts);
        assertTrue(sentFrames().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        await(() -> requests.get() == 1);
        server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
        await(() -> sentFrames().containsKey(pts));
        assertEquals(1, server.clientCount());
    }

    @Test public void aFullCreditWindowToleratesAShortBurstAndLogsOnlyActualRecovery() throws Exception {
        fillFrameWindow();
        for (int i = 0; i < FrameQueue.MAX_FRAMES; i++)
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, i == 0, ++pts);
        assertEquals(0, events.stream().filter(value -> value.startsWith("frame_queue_recovery")).count());
        server.frame(new byte[]{0, 0, 1, 0x41, 1}, false, ++pts);
        assertEquals(1, events.stream().filter(value -> value.startsWith("frame_queue_recovery reason=frame_capacity dropped=12 bytes=")).count());
        for (int i = 0; i < 100; i++) server.frame(new byte[]{0, 0, 1, 0x41, 1}, false, ++pts);
        assertEquals(1, events.stream().filter(value -> value.startsWith("frame_queue_recovery")).count());
        assertEquals(3, sentFrames().size());
        assertEquals(1, server.clientCount());
    }

    private void fillFrameWindow() throws Exception {
        server.videoConfig(new byte[]{0, 0, 0, 1, 0x67, 0x64, 0, 0x28}, 1920, 1080);
        for (int i = 0; i < 3; i++) {
            server.frame(new byte[]{0, 0, 1, 0x65, 1}, true, ++pts);
            int count = i + 1;
            await(() -> sentFrames().size() == count);
        }
        // Entries are registered before writing so fast ACKs are valid. Advance the
        // synthetic clock only after the final write has set its recovery deadline.
        await(() -> sentFrames().size() == 3 && senderWaitingIn(FrameSendSignal.class, "awaitChange"));
    }

    private boolean senderWaitingIn(Class<?> owner, String method) {
        Thread sender = ReflectionHelpers.getField(client, "sender");
        if (sender.getState() != Thread.State.TIMED_WAITING) return false;
        return java.util.Arrays.stream(sender.getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals(owner.getName()) && frame.getMethodName().equals(method));
    }

    private Map<?, ?> sentFrames() { return ReflectionHelpers.getField(client, "sentFrames"); }
    private String wireText() { return new String(wire.toByteArray(), StandardCharsets.UTF_8); }
    private void message(String json) throws Exception {
        ReflectionHelpers.callInstanceMethod(client, "onMessage", from(NanoWSD.WebSocketFrame.class,
                new NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Text, true, json)));
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) {
            Thread sender = ReflectionHelpers.getField(client, "sender");
            fail("Browser sender did not reach the expected state: events=" + events
                    + " sent=" + sentFrames().keySet() + " clock=" + android.os.SystemClock.elapsedRealtime()
                    + " lastAck=" + ReflectionHelpers.getField(client, "lastAck")
                    + " stack=" + java.util.Arrays.toString(sender.getStackTrace()));
        }
    }
}
