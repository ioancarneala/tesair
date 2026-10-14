package com.shilapi.xcertplay.browser;

import android.content.Context;
import android.os.SystemClock;
import fi.iki.elonen.NanoWSD;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.json.JSONObject;
import com.shilapi.xcertplay.BrowserTelemetry;

final class BridgeServer extends NanoWSD {
    static final int PORT = 8443;
    private final BrowserSession access = new BrowserSession();
    private final Context context;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile Runnable keyframe = () -> {}, connectionChanged = () -> {};
    private volatile BiConsumer<Integer, Integer> viewport = (w, h) -> {};
    private volatile Consumer<JSONObject> gesture = message -> {};
    private volatile LongConsumer rendered = epoch -> {};
    private volatile Consumer<String> diagnostic = event -> {};
    private volatile int requestedFps;
    void setRequestedFps(int fps) { requestedFps = Math.max(0, Math.min(120, fps)); }
    private volatile boolean running = true;
    private volatile Video video;
    private volatile String viewArea;
    private long epoch;
    private volatile String status = "Connect an iPhone in TesAir to start CarPlay.";
    private static final class Video {
        final byte[] config; final String json; final long epoch; final int width, height;
        Video(byte[] config, String json, long epoch, int width, int height) {
            this.config = config; this.json = json; this.epoch = epoch; this.width = width; this.height = height;
        }
    }
    BridgeServer(Context context) { super("0.0.0.0", PORT); this.context = context.getApplicationContext(); }
    void setKeyframeHandler(Runnable handler) { keyframe = handler; }
    void setDisplayHandler(BiConsumer<Integer, Integer> handler) { viewport = handler; }
    void setGestureHandler(Consumer<JSONObject> handler) { gesture = handler; }
    void setRenderedHandler(LongConsumer handler) { rendered = handler; }
    void setConnectionHandler(Runnable handler) { connectionChanged = handler; }
    void setDiagnosticHandler(Consumer<String> handler) { diagnostic = handler; }
    private void record(String event) {
        try { diagnostic.accept(event); } catch (RuntimeException ignored) { }
    }
    int clientCount() { return clients.size(); }
    int[] lastViewport() {
        for (Client client : clients) {
            int[] size = client.viewportSize;
            if (size != null) return size.clone();
        }
        return null;
    }
    synchronized long videoGeneration() { return epoch; }
    void setViewArea(int x, int y, int width, int height, int canvasWidth, int canvasHeight) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0
                || canvasWidth > 8192 || canvasHeight > 8192
                || (long) x + width > canvasWidth || (long) y + height > canvasHeight)
            throw new IllegalArgumentException("Invalid CarPlay view area");
        try {
            viewArea = new JSONObject().put("type", "viewArea").put("x", x).put("y", y)
                    .put("width", width).put("height", height)
                    .put("canvasWidth", canvasWidth).put("canvasHeight", canvasHeight).toString();
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }
    void browserStatus(String message, boolean error) {
        status = message;
        try {
            String text = new JSONObject().put("type", error ? "error" : "status").put("message", message).toString();
            for (Client client : clients) { client.pendingStatus.set(text); client.sendSignal.signal(); }
        } catch (org.json.JSONException ignored) { }
    }
    void telemetryConsentChanged() {
        String message = "{\"type\":\"telemetry_consent\",\"enabled\":" + BrowserTelemetry.enabled(context) + "}";
        for (Client client : clients) { client.pendingConsent.set(message); client.sendSignal.signal(); }
    }
    synchronized void resetVideo() {
        video = null; epoch++;
        for (Client client : clients) client.reset();
    }
    synchronized void videoConfig(byte[] config, int width, int height) throws Exception {
        Video previous = video;
        if (previous != null && previous.width == width && previous.height == height && Arrays.equals(previous.config, config)) return;
        String json = new JSONObject().put("type", "format").put("codec", VideoFormat.codec(config))
                .put("width", width).put("height", height).put("generation", ++epoch)
                .put("fpsRequested", requestedFps).toString();
        video = new Video(config.clone(), json, epoch, width, height);
        for (Client client : clients) client.reset();
    }
    void frame(byte[] data, boolean key, long pts) {
        Video current = video;
        if (current == null || clients.isEmpty()) return;
        FrameQueue.Frame frame = new FrameQueue.Frame(VideoFormat.packet(data, current.config, key, pts), key,
                System.nanoTime(), current.json, current.epoch);
        for (Client client : clients) if (!client.queue.offer(frame)) {
            client.recordQueueRecovery(); keyframe.run();
        }
    }
    @Override public Response serve(IHTTPSession session) {
        if ("websocket".equalsIgnoreCase(session.getHeaders().get("upgrade"))) {
            if (!running || !"/stream".equals(session.getUri()) || session.getMethod() != Method.GET
                    || !access.authorized(session.getHeaders().get("cookie")) || !sameOrigin(session))
                return reply(Response.Status.FORBIDDEN, "text/plain", "Refresh the page to reconnect.");
        }
        return super.serve(session);
    }
    private static boolean sameOrigin(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        return host != null && ("https://" + host).equals(session.getHeaders().get("origin"));
    }
    @Override protected Response serveHttp(IHTTPSession session) {
        if ("/telemetry".equals(session.getUri()) && session.getMethod() == Method.GET)
            return reply(Response.Status.OK, "application/json", "{\"enabled\":" + BrowserTelemetry.enabled(context) + "}");
        if ("/diagnostics".equals(session.getUri()) && session.getMethod() == Method.POST) {
            if (!running || !sameOrigin(session)) return reply(Response.Status.FORBIDDEN, "text/plain", "Open the display address directly.");
            if (!BrowserTelemetry.enabled(context)) return reply(Response.Status.OK, "application/json", "{\"ok\":true}");
            return browserTelemetry(session);
        }
        if ("/session".equals(session.getUri()) && session.getMethod() == Method.POST) {
            if (!running || !sameOrigin(session)) return reply(Response.Status.FORBIDDEN, "text/plain", "Open the display address directly.");
            if (!"application/json".equals(session.getHeaders().get("content-type"))
                    || !"2".equals(session.getHeaders().get("content-length")) || session.getHeaders().containsKey("transfer-encoding"))
                return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid session request");
            try {
                byte[] body = new byte[2]; new java.io.DataInputStream(session.getInputStream()).readFully(body);
                if (body[0] != '{' || body[1] != '}') return reply(Response.Status.BAD_REQUEST, "text/plain", "Empty JSON required");
                Response response = reply(Response.Status.OK, "application/json", "{\"ok\":true}");
                response.addHeader("Set-Cookie", access.cookie()); return response;
            } catch (IOException error) { return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid session request"); }
        }
        if (session.getMethod() != Method.GET) return reply(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET required");
        String path = session.getUri();
        String file = "/".equals(path) || "/index.html".equals(path) ? "index.html" : "/player.js".equals(path) ? "player.js"
                : "/telemetry.js".equals(path) ? "telemetry.js" : null;
        if (file == null) return reply(Response.Status.NOT_FOUND, "text/plain", "Not found");
        try (InputStream input = context.getAssets().open("browser/" + file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) >= 0) out.write(block, 0, count);
            return reply(Response.Status.OK, file.endsWith(".js") ? "text/javascript" : "text/html; charset=utf-8",
                    out.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException error) { return reply(Response.Status.INTERNAL_ERROR, "text/plain", "Player unavailable"); }
    }
    private long telemetryWindow, telemetryCount;
    private synchronized boolean acceptTelemetry() {
        long now = SystemClock.elapsedRealtime();
        if (now - telemetryWindow >= 60000) { telemetryWindow = now; telemetryCount = 0; }
        return ++telemetryCount <= 60;
    }
    private Response browserTelemetry(IHTTPSession session) {
        if (!acceptTelemetry()) return reply(Response.Status.TOO_MANY_REQUESTS, "text/plain", "Try later.");
        try {
            int length = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            if (length <= 0 || length > 4096 || session.getHeaders().containsKey("transfer-encoding")
                    || !"application/json".equals(session.getHeaders().get("content-type")))
                return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid diagnostic request.");
            byte[] bytes = new byte[length]; new java.io.DataInputStream(session.getInputStream()).readFully(bytes);
            JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String name = body.optString("name");
            if (!java.util.Arrays.asList("browser_page_loaded", "browser_capabilities", "browser_connected",
                    "browser_disconnected", "browser_decoder_ready", "browser_first_frame", "browser_error", "performance_summary", "recovery_completed").contains(name))
                return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid diagnostic event.");
            JSONObject input = body.optJSONObject("attributes");
            java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            if (input != null && input.length() <= 32) {
                java.util.Iterator<String> keys = input.keys();
                while (keys.hasNext()) { String key = keys.next(); Object value = input.get(key);
                    if (value instanceof String || value instanceof Boolean || value instanceof Number) attributes.put(key, value);
                }
            }
            // The shared collector enforces the same field allowlist as the hosted service.
            BrowserTelemetry.record(context, name, attributes);
            return reply(Response.Status.OK, "application/json", "{\"ok\":true}");
        } catch (Exception error) { return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid diagnostic request."); }
    }
    private static Response reply(Response.Status status, String type, String text) {
        Response response = newFixedLengthResponse(status, type, text);
        response.addHeader("Cache-Control", "no-store"); response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self' wss:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        return response;
    }
    @Override public void start(int timeout, boolean daemon) throws IOException {
        try {
            super.start(timeout, daemon);
        } catch (IOException | RuntimeException error) {
            // NanoHTTPD leaves its socket open when bind fails; retries must release that attempt.
            super.stop();
            throw error;
        }
    }
    @Override protected WebSocket openWebSocket(IHTTPSession session) { return new Client(session); }
    @Override protected ClientHandler createClientHandler(Socket socket, InputStream input) {
        try { socket.setTcpNoDelay(true); }
        catch (SocketException ignored) { record("socket_low_delay_unavailable"); }
        return super.createClientHandler(socket, input);
    }
    @Override public void stop() {
        running = false; record("server_stop"); super.stop();
        for (Client client : clients) client.finish();
        clients.clear();
    }
    private final class Client extends WebSocket {
        private final FrameQueue queue = new FrameQueue();
        private final AtomicBoolean alive = new AtomicBoolean();
        private final AtomicReference<String> pendingStatus = new AtomicReference<>();
        // A regular status update must not replace an unsent consent change.
        private final AtomicReference<String> pendingConsent = new AtomicReference<>();
        private final AtomicBoolean pendingPong = new AtomicBoolean();
        private final AtomicBoolean closeRecorded = new AtomicBoolean();
        private final ConcurrentHashMap<Long, SentFrame> sentFrames = new ConcurrentHashMap<>();
        private final FrameSendSignal sendSignal = new FrameSendSignal();
        private final BrowserLatencyStats latency = new BrowserLatencyStats();
        private final byte[] frameWriteBuffer = new byte[CoalescedWebSocketFrame.BUFFER_SIZE];
        private Thread sender;
        private long lastTouch, lastKeyRequest, lastViewport;
        private volatile long lastAck, acknowledgedPts = -1;
        private long lastBrowserDiagnostic = -1000, lastLatencyDiagnostic = -4000, lastQueueDiagnostic = -10000;
        private volatile int[] viewportSize;
        Client(IHTTPSession session) { super(session); }
        @Override public synchronized void sendFrame(WebSocketFrame frame) throws IOException {
            super.sendFrame(new CoalescedWebSocketFrame(frame, frameWriteBuffer));
        }
        void reset() { queue.reset(); sentFrames.clear(); sendSignal.signal(); }
        @Override protected void onOpen() {
            synchronized (clients) {
                if (!running || !clients.isEmpty()) { reject("viewer_busy", "One browser is already connected."); return; }
                clients.add(this); alive.set(true);
            }
            lastAck = SystemClock.elapsedRealtime();
            record("viewer_open");
            browserStatus(status, false); connectionChanged.run();
            sender = new Thread(this::sendLoop, "DiPlayBrowserFrames"); sender.start(); keyframe.run();
        }
        private void sendLoop() {
            String sentFormat = null;
            String sentViewArea = null;
            long sentAt = 0, lastRenderedPts = -1;
            boolean ackRecovery = false;
            try {
                while (alive.get() && running) {
                    long observedWake = sendSignal.version();
                    String timings = latency.report(System.nanoTime()); if (timings != null) record(timings);
                    String consent = pendingConsent.getAndSet(null); if (consent != null) send(consent);
                    String text = pendingStatus.getAndSet(null); if (text != null) send(text);
                    if (pendingPong.getAndSet(false)) send("{\"type\":\"pong\"}");
                    String area = viewArea;
                    if (area != null && !area.equals(sentViewArea)) { send(area); sentViewArea = area; }
                    if (SystemClock.elapsedRealtime() - lastAck > 5000) { reject("heartbeat_timeout", "Browser stopped responding."); break; }
                    long acked = acknowledgedPts;
                    if (acked > lastRenderedPts) { lastRenderedPts = acked; ackRecovery = false; }
                    // The same map validates ACKs and bounds in-flight frames. A separate
                    // pending list can drift when reset overlaps a blocked queue read.
                    if (sentFrames.size() >= 3) {
                        if (SystemClock.elapsedRealtime() - sentAt > 1500) {
                            if (ackRecovery) { reject("render_ack_timeout", "Video stalled. Connect again."); break; }
                            // A short decoder pause need not tear down the browser/CarPlay session.
                            // One bounded recovery keeps both the frame queue and retry budget small.
                            ackRecovery = true;
                            record("render_ack_recovery pending=" + sentFrames.size());
                            reset();
                            send("{\"type\":\"recover\"}"); keyframe.run();
                            continue;
                        }
                        long waitStarted = System.nanoTime();
                        sendSignal.awaitChange(observedWake, Math.max(1, 1501 - (SystemClock.elapsedRealtime() - sentAt)));
                        latency.waited(System.nanoTime() - waitStarted); continue;
                    }
                    FrameQueue.Frame next = queue.poll();
                    if (next == null || next.epoch() != videoGeneration()) continue;
                    if (!next.format().equals(sentFormat)) { send(next.format()); sentFormat = next.format(); }
                    long pts = ByteBuffer.wrap(next.packet(), 1, 8).getLong();
                    long sendStarted = System.nanoTime();
                    latency.queued(sendStarted - next.queuedNanos());
                    sentFrames.put(pts, new SentFrame(next.epoch(), sendStarted));
                    send(next.packet());
                    latency.written(System.nanoTime() - sendStarted);
                    sentAt = SystemClock.elapsedRealtime();
                }
            } catch (IOException error) { recordClose(running ? "write_io" : "server_stop", false, 0); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { finish(); }
        }
        @Override protected void onMessage(WebSocketFrame frame) {
            if (!alive.get() || frame.getOpCode() != WebSocketFrame.OpCode.Text || frame.getBinaryPayload().length > 8192) {
                reject("invalid_control", "Invalid control message"); return;
            }
            try {
                JSONObject message = new JSONObject(frame.getTextPayload()); long now = SystemClock.elapsedRealtime();
                switch (message.optString("type")) {
                    case "ack": {
                        long pts = message.optLong("pts", -1); SentFrame sent = sentFrames.get(pts);
                        if (sent != null && sent.epoch == videoGeneration() && sentFrames.remove(pts, sent)) {
                            sentFrames.keySet().removeIf(value -> value <= pts);
                            lastAck = now; acknowledgedPts = Math.max(acknowledgedPts, pts);
                            latency.acknowledged(System.nanoTime() - sent.startedNanos);
                            sendSignal.signal(); rendered.accept(sent.epoch);
                        }
                        break;
                    }
                    case "ping": lastAck = now; pendingPong.set(true); sendSignal.signal(); break;
                    case "diagnostic":
                        if ("latency".equals(message.optString("event"))) {
                            if (now - lastLatencyDiagnostic >= 4000) {
                                lastLatencyDiagnostic = now; record(browserDiagnostic(message));
                            }
                            break;
                        }
                        if (now - lastBrowserDiagnostic >= 1000) {
                            String event = browserDiagnostic(message);
                            if (event != null) { lastBrowserDiagnostic = now; record(event); }
                        }
                        break;
                    case "keyframe":
                        if (now - lastKeyRequest > 500) { lastKeyRequest = now; reset(); keyframe.run(); } break;
                    case "gesture":
                        if (video == null || now - lastTouch < 40) return;
                        lastTouch = now; gesture.accept(message); break;
                    case "touch":
                        // The player coalesces moves; a second time gate can discard the release point.
                        gesture.accept(message); break;
                    case "display":
                        if (now - lastViewport < 150) return;
                        lastViewport = now;
                        int[] size = BrowserViewport.fit(message.optInt("width"), message.optInt("height"));
                        viewportSize = size;
                        viewport.accept(size[0], size[1]); break;
                    default: break;
                }
            } catch (Exception ignored) { reject("invalid_control", "Invalid control message"); }
        }
        private void reject(String reason, String message) {
            recordClose(reason, false, 1008);
            try { close(WebSocketFrame.CloseCode.PolicyViolation, message, false); } catch (IOException ignored) { }
            finish();
        }
        private void recordQueueRecovery() {
            long now = SystemClock.elapsedRealtime();
            if (now - lastQueueDiagnostic >= 10000) {
                lastQueueDiagnostic = now; record("frame_queue_recovery");
            }
        }
        private void recordClose(String reason, boolean remote, int code) {
            if (closeRecorded.compareAndSet(false, true)) record("viewer_close reason=" + reason
                    + " remote=" + remote + " code=" + code
                    + " heartbeatAgeMs=" + Math.max(0, SystemClock.elapsedRealtime() - lastAck)
                    + " unacknowledged=" + sentFrames.size());
        }
        private void finish() {
            boolean wasAlive = alive.getAndSet(false); clients.remove(this); reset();
            pendingConsent.set(null);
            if (sender != null && sender != Thread.currentThread()) sender.interrupt();
            if (wasAlive) connectionChanged.run();
        }
        @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            // Close reasons are browser-controlled strings and may contain personal data.
            recordClose(running ? "websocket_close" : "server_stop", remote, code == null ? 0 : code.getValue()); finish();
        }
        @Override protected void onPong(WebSocketFrame frame) { lastAck = SystemClock.elapsedRealtime(); }
        @Override protected void onException(IOException error) {
            recordClose(!running ? "server_stop"
                    : error instanceof java.net.SocketTimeoutException ? "read_timeout" : "read_io", false, 0); finish();
        }
    }

    private static final class SentFrame {
        final long epoch, startedNanos;
        SentFrame(long epoch, long startedNanos) { this.epoch = epoch; this.startedNanos = startedNanos; }
    }

    static String browserDiagnostic(JSONObject message) {
        String event = message.optString("event");
        if ("latency".equals(event)) return "browser_latency"
                + " samples=" + boundedMetric(message, "samples", 10000)
                + " decodeAvgUs=" + boundedMetric(message, "decodeAvgUs", 10000000)
                + " decodeMaxUs=" + boundedMetric(message, "decodeMaxUs", 10000000)
                + " drawAvgUs=" + boundedMetric(message, "drawAvgUs", 10000000)
                + " drawMaxUs=" + boundedMetric(message, "drawMaxUs", 10000000)
                + " decodeQueue=" + boundedMetric(message, "decodeQueue", 1000);
        switch (event) {
            case "decoder_error": case "decoder_unsupported": case "decode_backpressure":
            case "video_wait": case "video_resumed": case "transport_timeout": case "decoder_timeout":
            case "socket_closed": case "socket_error": case "page_hidden": case "page_visible":
            case "page_exit": case "server_recovery": case "packet_error": break;
            default: return null;
        }
        return "browser_" + event
                + " frameAgeMs=" + boundedMetric(message, "frameAgeMs", 3600000)
                + " packetAgeMs=" + boundedMetric(message, "packetAgeMs", 3600000)
                + " decodeQueue=" + boundedMetric(message, "decodeQueue", 1000)
                + " closeCode=" + boundedMetric(message, "closeCode", 4999)
                + " hidden=" + message.optBoolean("hidden", false);
    }
    private static long boundedMetric(JSONObject message, String key, long maximum) {
        return Math.max(0, Math.min(maximum, message.optLong(key, 0)));
    }
}
