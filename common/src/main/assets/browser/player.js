(function (root) {
  'use strict';
  function pointInVideo(clientX, clientY, rect) {
    if (!Number.isFinite(rect.width) || !Number.isFinite(rect.height) || rect.width <= 0 || rect.height <= 0) return null;
    const x = (clientX - rect.left) / rect.width, y = (clientY - rect.top) / rect.height;
    return !Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x > 1 || y > 1 ? null : [x, y];
  }
  function parseViewArea(value) {
    if (!value || typeof value !== 'object') return null;
    const {x, y, width, height, canvasWidth, canvasHeight} = value;
    if (![x, y, width, height, canvasWidth, canvasHeight].every(Number.isInteger)
        || x < 0 || y < 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0
        || canvasWidth > 8192 || canvasHeight > 8192 || x + width > canvasWidth || y + height > canvasHeight) return null;
    return {x, y, width, height, canvasWidth, canvasHeight};
  }
  function displayViewport(width, height, dpr = 1) {
    if (![width, height, dpr].every(Number.isFinite) || width <= 0 || height <= 0 || dpr <= 0) return null;
    dpr = Math.max(1, Math.min(2, dpr));
    const scale = Math.min(dpr, 2560 / Math.max(width, height));
    return { type: 'display', width: Math.max(1, Math.round(width * scale)),
      height: Math.max(1, Math.round(height * scale)), dpr };
  }
  function renderViewport(width, height, dpr, sourceWidth, sourceHeight) {
    if (![width, height, dpr, sourceWidth, sourceHeight].every(Number.isFinite)
        || width <= 0 || height <= 0 || dpr <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null;
    const scale = Math.min(Math.max(1, Math.min(2, dpr)), 4096 / Math.max(width, height),
      Math.sqrt(8388608 / (width * height)));
    return {width: Math.max(1, Math.min(sourceWidth, Math.round(width * scale))),
      height: Math.max(1, Math.min(sourceHeight, Math.round(height * scale)))};
  }
  function parsePacket(buffer) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength <= 9) throw new Error('Invalid video packet');
    const view = new DataView(buffer);
    if (view.getUint8(0) > 1) throw new Error('Invalid frame flags');
    const timestamp = view.getUint32(1) * 4294967296 + view.getUint32(5);
    if (!Number.isSafeInteger(timestamp)) throw new Error('Invalid video timestamp');
    return { type: view.getUint8(0) ? 'key' : 'delta', timestamp, data: new Uint8Array(buffer, 9) };
  }
  function retryDelay(attempt) { return Math.min(10000, 500 * Math.pow(2, Math.max(0, Math.min(5, attempt - 1)))); }
  if (typeof module !== 'undefined') module.exports = { pointInVideo, parseViewArea, displayViewport, renderViewport, parsePacket, retryDelay };
  if (!root.document) return;
  const $ = id => document.getElementById(id);
  const canvas = $('screen'), context = canvas.getContext('2d', { alpha: false, desynchronized: true });
  let socket, decoder, config, needKey = true, reconnectTimer, reconnectCount = 0, connected = false;
  let lastFrame = 0, lastPacket = 0, lastServerMessage = 0, recoveryAt = -Infinity;
  let displayTimer, keyframeTimer, lastDisplay = '', decodeError = '';
  let sessionRequest, connectionAttempt = 0, socketTimer, leaving = false;
  let receivedVideo = false, recoveryRequested = false, unsupportedVideo = false;
  let reportedPlaybackProblem = false;
  let reportNextDraw = false;
  let waitingForVideo = false, pendingDiagnostic = null;
  let viewArea = null, pendingViewArea = null;
  const frameArrivals = new Map();
  const MAX_PENDING_FRAMES = 12, MAX_PENDING_BYTES = 8 * 1024 * 1024, MAX_PENDING_AGE_MS = 150, STARTUP_DECODE_AGE_MS = 1000;
  let decoderHasOutput = false, decodeTimer = null, pendingEncodedBytes = 0, pendingFrame = null, renderHandle = null, renderEpoch = 0, renderSizeDirty = true;
  let timingSamples = 0, timingDecodeSamples = 0, decodeTotal = 0, decodeMax = 0, drawTotal = 0, drawMax = 0, timingReportedAt = 0;
  let metricReceived = 0, metricDecoded = 0, metricDrawn = 0, metricDropped = 0, metricStartedAt = 0, metricQueueMax = 0;
  let metricDecodeTotal = 0, metricDecodeMax = 0, metricDecodeSamples = 0, requestedFps = 0;
  function telemetry(name, attributes = {}) {
    try { if (root.TesAirTelemetry) root.TesAirTelemetry.event(name, attributes); }
    catch (_) { /* Optional reporting cannot interrupt video or its acknowledgements. */ }
  }
  function videoContext() {
    return config ? {codec: config.codec, width: config.codedWidth, height: config.codedHeight} : {};
  }
  function playbackProblem(reason, attributes = {}) {
    reportedPlaybackProblem = true;
    telemetry('browser_error', {...videoContext(), ...attributes, reason, result: 'failed'});
  }
  function send(message) { if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); }
  function diagnostic(event, closeCode = 0, timings = {}) {
    const now = performance.now();
    const message = {type: 'diagnostic', event, frameAgeMs: Math.round(now - lastFrame),
      packetAgeMs: Math.round(now - lastPacket), decodeQueue: decoder ? decoder.decodeQueueSize || 0 : 0,
      closeCode: Number.isInteger(closeCode) ? closeCode : 0, hidden: document.hidden, ...timings};
    if (socket && socket.readyState === WebSocket.OPEN) send(message);
    else pendingDiagnostic = message;
    if (['decoder_error', 'decoder_unsupported', 'decode_backpressure', 'transport_timeout',
      'decoder_timeout', 'socket_error', 'packet_error'].includes(event))
      playbackProblem(event, {errorCode: String(closeCode), queueDepth: message.decodeQueue});
    else if (event === 'page_exit') telemetry('browser_disconnected', {reason: 'page_exit', result: 'normal'});
  }
  function resetVideoTiming() {
    clearTimeout(decodeTimer); decodeTimer = null;
    frameArrivals.clear(); pendingEncodedBytes = 0;
    timingSamples = timingDecodeSamples = decodeTotal = decodeMax = drawTotal = drawMax = 0;
    timingReportedAt = performance.now();
  }
  function reportPerformance(now, reason = 'interval') {
    const duration = now - metricStartedAt;
    if (duration < 1000 || !metricReceived) return;
    telemetry('performance_summary', {reason, sampleDurationMs: Math.round(duration),
      acceptedPackets: metricReceived, decodedFrames: metricDecoded, drawnFrames: metricDrawn,
      fpsReceived: metricReceived * 1000 / duration, fpsDecoded: metricDecoded * 1000 / duration,
      fpsRequested: requestedFps, droppedFrames: metricDropped, queueDepth: metricQueueMax,
      decodeMeanMs: metricDecodeSamples ? metricDecodeTotal / metricDecodeSamples : 0, decodeMaxMs: metricDecodeMax,
      width: config ? config.codedWidth : 0, height: config ? config.codedHeight : 0});
    metricReceived = metricDecoded = metricDrawn = metricDropped = metricQueueMax = 0;
    metricDecodeTotal = metricDecodeMax = metricDecodeSamples = 0;
    metricStartedAt = now;
  }
  function reportVideoTiming(now) {
    if (!timingSamples || now - timingReportedAt < 5000) return;
    diagnostic('latency', 0, {samples: timingSamples, decodeAvgUs: Math.round(decodeTotal * 1000 / Math.max(1, timingDecodeSamples)),
      decodeMaxUs: Math.round(decodeMax * 1000), drawAvgUs: Math.round(drawTotal * 1000 / timingSamples),
      drawMaxUs: Math.round(drawMax * 1000)});
    timingSamples = timingDecodeSamples = decodeTotal = decodeMax = drawTotal = drawMax = 0;
    timingReportedAt = now;
    if (now - metricStartedAt >= 30000) reportPerformance(now);
  }
  function status(text) { if (!$('panel').hidden) $('status').textContent = text; }
  function connectionPresentation(title, detail, actionable = false, error = '') {
    if ($('heading').textContent !== title) $('heading').textContent = title;
    $('intro').textContent = detail; $('intro').hidden = !detail;
    $('error').textContent = error; $('error').hidden = !error;
    $('activity').hidden = actionable; $('retry').hidden = !actionable;
  }
  function displaySize() {
    if (!connected) return;
    const bounds = $('stage').getBoundingClientRect();
    const display = displayViewport(bounds.width, bounds.height, root.devicePixelRatio || 1);
    if (!display) return;
    const signature = JSON.stringify(display);
    if (signature !== lastDisplay) { send(display); lastDisplay = signature; }
  }
  function scheduleDisplay() { renderSizeDirty = true; clearGesture(); clearTimeout(displayTimer); displayTimer = setTimeout(displaySize, 200); }
  function decoderFailure(error) {
    diagnostic(error && error.name === 'NotSupportedError' ? 'decoder_unsupported' : 'decoder_error');
    if (error && error.name === 'NotSupportedError') {
      unsupportedVideo = true;
      reconnect('This browser cannot decode the CarPlay video format. Update the browser or use a supported browser, then select Try again.');
      return;
    }
    config = null; status('Video decoder unavailable');
    decodeError = 'This browser could not decode the video. The connection will restart automatically.';
    reconnect(decodeError);
  }
  function discardPresentation() {
    renderEpoch++;
    if (renderHandle != null) {
      if (typeof root.cancelAnimationFrame === 'function') root.cancelAnimationFrame(renderHandle);
      else clearTimeout(renderHandle);
      renderHandle = null;
    }
    if (pendingFrame) { pendingFrame.frame.close(); pendingFrame = null; metricDropped++; }
  }
  function presentFrame(epoch) {
    if (epoch !== renderEpoch) return;
    renderHandle = null;
    const pending = pendingFrame; pendingFrame = null;
    if (!pending) return;
    const frame = pending.frame;
    try {
      if (document.hidden || pending.decoder !== decoder || decoder.state !== 'configured') return;
      const area = sourceArea();
      if (renderSizeDirty) {
        const bounds = $('stage').getBoundingClientRect();
        const size = renderViewport(bounds.width, bounds.height, root.devicePixelRatio || 1, area.width, area.height);
        if (size) {
          // Resize only immediately before a draw: changing either attribute clears the canvas.
          if (canvas.width !== size.width) canvas.width = size.width;
          if (canvas.height !== size.height) canvas.height = size.height;
          renderSizeDirty = false;
        }
      }
      const drawAt = performance.now();
      context.drawImage(frame, area.x, area.y, area.width, area.height, 0, 0, canvas.width, canvas.height);
      const drawMs = Math.max(0, performance.now() - drawAt);
      clearTimeout(keyframeTimer); keyframeTimer = null;
      metricDrawn++; timingSamples++; drawTotal += drawMs; drawMax = Math.max(drawMax, drawMs);
      if (!receivedVideo || reportedPlaybackProblem || reportNextDraw) telemetry('browser_first_frame', {
        ...videoContext(), source: reportedPlaybackProblem ? 'recovery' : 'first_draw', sampleDecoded: true,
        width: frame.displayWidth || config.codedWidth, height: frame.displayHeight || config.codedHeight});
      reportedPlaybackProblem = reportNextDraw = false;
      // ACK is cumulative: drawing the newest frame also releases superseded frame credits.
      // It confirms submission, never physical display presentation or merely successful decoding.
      send({type: 'ack', pts: frame.timestamp});
      if (waitingForVideo) diagnostic('video_resumed');
      waitingForVideo = false;
      lastFrame = performance.now(); receivedVideo = true; recoveryRequested = false;
      canvas.hidden = false; $('panel').hidden = true; document.body.classList.add('playing'); reconnectCount = 0; decodeError = '';
    } catch (error) { decoderFailure(error); }
    finally { frame.close(); }
  }
  function queuePresentation(frame, next) {
    if (pendingFrame) { pendingFrame.frame.close(); metricDropped++; }
    pendingFrame = {frame, decoder: next};
    if (renderHandle != null) return;
    const epoch = renderEpoch;
    if (typeof root.requestAnimationFrame !== 'function') { presentFrame(epoch); return; }
    renderHandle = root.requestAnimationFrame(() => presentFrame(epoch));
  }
  function watchDecodeProgress() {
    clearTimeout(decodeTimer); decodeTimer = null;
    const oldest = frameArrivals.values().next().value;
    if (!oldest || document.hidden) return;
    const epoch = renderEpoch, deadline = decoderHasOutput ? MAX_PENDING_AGE_MS : STARTUP_DECODE_AGE_MS;
    decodeTimer = setTimeout(() => {
      decodeTimer = null;
      if (epoch !== renderEpoch || !connected || document.hidden || !decoder) return;
      const pending = frameArrivals.values().next().value;
      if (pending && performance.now() - pending.at >= deadline) {
        metricDropped++; diagnostic('decode_backpressure'); recover();
      } else watchDecodeProgress();
    }, Math.max(1, Math.ceil(deadline - (performance.now() - oldest.at))));
  }
  function recover() {
    needKey = true;
    discardPresentation(); resetVideoTiming(); decoderHasOutput = false;
    const now = performance.now();
    try { if (decoder && decoder.state !== 'closed') { decoder.reset(); if (config) decoder.configure(config); } }
    catch (error) { decoderFailure(error); return; }
    clearTimeout(keyframeTimer);
    const remaining = 500 - (now - recoveryAt), epoch = renderEpoch;
    const requestKeyframe = () => {
      keyframeTimer = null;
      if (epoch !== renderEpoch || !connected || document.hidden || !socket || socket.readyState !== WebSocket.OPEN) return;
      recoveryAt = performance.now(); send({type: 'keyframe'});
    };
    if (remaining > 0) keyframeTimer = setTimeout(requestKeyframe, remaining);
    else requestKeyframe();
  }
  function createDecoder(format) {
    clearGesture();
    closeDecoder();
    if (!Number.isInteger(format.width) || !Number.isInteger(format.height)
        || format.width <= 0 || format.height <= 0 || format.width > 8192 || format.height > 8192
        || typeof format.codec !== 'string') throw new Error('Invalid video format');
    renderSizeDirty = true; decoderHasOutput = false;
    requestedFps = Number.isFinite(format.fpsRequested) ? format.fpsRequested : 0;
    config = { codec: format.codec, codedWidth: format.width, codedHeight: format.height,
      // Let the browser use its available hardware decoder or fall back to software.
      optimizeForLatency: true, hardwareAcceleration: 'no-preference' };
    if (viewArea && (viewArea.canvasWidth !== format.width || viewArea.canvasHeight !== format.height)) viewArea = null;
    applyViewArea();
    const next = new VideoDecoder({
      output(frame) {
        const arrival = frameArrivals.get(frame.timestamp);
        if (decoder !== next || !arrival || document.hidden) { frame.close(); return; }
        const outputAt = performance.now(), decodeMs = Math.max(0, outputAt - arrival.at);
        // The stream uses monotonic, cumulative ACK timestamps. Later output retires older decode bookkeeping too.
        for (const [pts, pending] of frameArrivals) if (pts <= frame.timestamp) {
          frameArrivals.delete(pts); pendingEncodedBytes -= pending.bytes;
        }
        decoderHasOutput = true; watchDecodeProgress();
        metricDecoded++;
        metricDecodeTotal += decodeMs; metricDecodeMax = Math.max(metricDecodeMax, decodeMs); metricDecodeSamples++;
        if (timingDecodeSamples < 10000) {
          timingDecodeSamples++; decodeTotal += decodeMs; decodeMax = Math.max(decodeMax, decodeMs);
        }
        queuePresentation(frame, next);
      },
      error(error) { if (decoder === next) decoderFailure(error); }
    });
    decoder = next;
    decoder.configure(config); needKey = true;
    telemetry('browser_decoder_ready', {codec: config.codec, width: config.codedWidth, height: config.codedHeight});
    if (typeof VideoDecoder.isConfigSupported === 'function') {
      const probeConfig = config;
      try {
        VideoDecoder.isConfigSupported(probeConfig).then(result => telemetry('browser_capabilities', {
          codec: probeConfig.codec, width: probeConfig.codedWidth, height: probeConfig.codedHeight, configSupported: result.supported
        })).catch(() => telemetry('browser_capabilities', {result: 'unknown'}));
      } catch (_) { telemetry('browser_capabilities', {result: 'unknown'}); }
    }
  }
  function sourceArea() {
    return viewArea || {x: 0, y: 0, width: config.codedWidth, height: config.codedHeight,
      canvasWidth: config.codedWidth, canvasHeight: config.codedHeight};
  }
  function applyViewArea() {
    // Native layout metadata can arrive before the matching encoded canvas format.
    if (!config || !pendingViewArea || pendingViewArea.canvasWidth !== config.codedWidth
        || pendingViewArea.canvasHeight !== config.codedHeight) return;
    if (JSON.stringify(viewArea) !== JSON.stringify(pendingViewArea)) clearGesture();
    viewArea = pendingViewArea; renderSizeDirty = true;
    pendingViewArea = null;
  }
  function closeDecoder() {
    clearTimeout(keyframeTimer); keyframeTimer = null;
    discardPresentation(); resetVideoTiming();
    const previous = decoder; decoder = null; config = null;
    if (previous && previous.state !== 'closed') previous.close();
  }
  function closeSocket() {
    viewArea = pendingViewArea = null;
    if (!socket) return;
    reportPerformance(performance.now(), 'connection_ended');
    const previous = socket; socket = null;
    previous.onclose = previous.onmessage = previous.onerror = previous.onopen = null;
    previous.close();
  }
  function reconnect(reason) {
    if (leaving) return;
    connected = false; needKey = true; connectionAttempt++;
    clearTimeout(reconnectTimer); clearTimeout(displayTimer); clearTimeout(socketTimer);
    clearGesture(); closeSocket();
    closeDecoder();
    canvas.hidden = true; $('panel').hidden = false; $('retry').disabled = false;
    document.body.classList.remove('playing');
    if (unsupportedVideo) {
      status('Video unavailable');
      connectionPresentation('Browser video not supported', '', true, 'Update the browser, then try again.');
      return;
    }
    const delay = document.hidden ? 30000 : retryDelay(++reconnectCount);
    status(reason || 'Reconnecting…');
    connectionPresentation('Reconnecting…', 'Your display will return automatically.');
    reconnectTimer = setTimeout(connect, delay);
  }
  async function connect() {
    if (leaving || unsupportedVideo) return;
    clearTimeout(reconnectTimer); clearTimeout(socketTimer);
    const attempt = ++connectionAttempt;
    if (sessionRequest) sessionRequest.abort();
    clearGesture(); closeSocket();
    connected = false;
    closeDecoder();
    metricStartedAt = performance.now();
    try { if (root.TesAirTelemetry) root.TesAirTelemetry.refresh(); } catch (_) {}
    canvas.hidden = true; $('panel').hidden = false; document.body.classList.remove('playing');
    if (!root.isSecureContext || !root.VideoDecoder) {
      telemetry('browser_error', {reason: !root.isSecureContext ? 'insecure_context' : 'webcodecs_unavailable'});
      status('Browser support required');
      connectionPresentation('Video unavailable', '', true, !root.isSecureContext
        ? 'Open the HTTPS address to connect.' : 'Update the browser to use its video decoder, then try again.');
      $('retry').disabled = false; return;
    }
    status('Connecting to phone…');
    connectionPresentation(reconnectCount ? 'Reconnecting…' : 'Connecting…',
      reconnectCount ? 'Your display will return automatically.' : 'Your display will appear automatically.');
    $('retry').disabled = true;
    const request = new AbortController(); sessionRequest = request;
    let responseCode = 0;
    const timeout = setTimeout(() => request.abort(), 8000);
    try {
      const response = await fetch('/session', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' }, body: '{}', signal: request.signal });
      responseCode = response.status || 0;
      if (!response.ok) throw new Error((await response.text()).trim().slice(0, 240) || `Phone returned ${response.status}.`);
      if (attempt !== connectionAttempt || leaving) return;
      socket = new WebSocket(`wss://${location.host}/stream`); socket.binaryType = 'arraybuffer';
      const current = socket;
      socketTimer = setTimeout(() => {
        if (current === socket && current.readyState !== WebSocket.OPEN) {
          playbackProblem('socket_open_timeout');
          reconnect('The phone stream did not open in time.');
        }
      }, 10000);
      current.onopen = () => {
        telemetry('browser_connected', {automatic: reconnectCount > 0});
        clearTimeout(socketTimer);
        connected = true; lastDisplay = ''; status('Connected · waiting for picture');
        connectionPresentation(reconnectCount ? 'Reconnecting…' : 'Connecting…', 'Waiting for CarPlay.');
        $('retry').disabled = false; lastFrame = lastPacket = lastServerMessage = performance.now();
        receivedVideo = false; recoveryRequested = false; waitingForVideo = false;
        displaySize(); send({ type: 'keyframe' });
        if (pendingDiagnostic) { send(pendingDiagnostic); pendingDiagnostic = null; }
      };
      current.onmessage = event => {
        lastServerMessage = performance.now();
        try {
          if (typeof event.data === 'string') {
            const payload = JSON.parse(event.data);
            if (payload.type === 'recover') {
              diagnostic('server_recovery'); recover();
            } else if (payload.type === 'format') {
              try { createDecoder(payload); } catch (error) { decoderFailure(error); }
            } else if (payload.type === 'viewArea') {
              const area = parseViewArea(payload);
              if (area) { pendingViewArea = area; applyViewArea(); }
            } else if ((payload.type === 'status' || payload.type === 'error') && typeof payload.message === 'string') {
              if (payload.type === 'error') playbackProblem('server_error');
              status(payload.message);
              if (!$('panel').hidden) {
                if (payload.type === 'error') connectionPresentation('Check your phone', '', true, payload.message);
                else connectionPresentation(reconnectCount ? 'Reconnecting…' : 'Connecting…', payload.message);
              }
            } else if (payload.type === 'telemetry_consent') {
              try { if (root.TesAirTelemetry) root.TesAirTelemetry.refresh(); } catch (_) {}
              // Report the next actual draw, never relabel an old picture as a fresh frame.
              reportNextDraw = payload.enabled === true;
            }
            return;
          }
          const packet = parsePacket(event.data);
          metricReceived++;
          lastPacket = performance.now();
          if (!decoder || decoder.state !== 'configured') return;
          if (document.hidden) { metricDropped++; return; }
          if (needKey && packet.type !== 'key') { metricDropped++; return; }
          if (frameArrivals.has(packet.timestamp)) { metricDropped++; return; }
          const now = performance.now(), oldest = frameArrivals.values().next().value;
          metricQueueMax = Math.max(metricQueueMax, decoder.decodeQueueSize || 0, frameArrivals.size);
          // Decode reference frames through short network bursts; reset only bounded, genuinely stale work.
          if (decoder.decodeQueueSize >= MAX_PENDING_FRAMES || frameArrivals.size >= MAX_PENDING_FRAMES
              || pendingEncodedBytes + packet.data.byteLength > MAX_PENDING_BYTES
              || (oldest && now - oldest.at >= (decoderHasOutput ? MAX_PENDING_AGE_MS : STARTUP_DECODE_AGE_MS))) {
            metricDropped++; diagnostic('decode_backpressure'); recover(); return;
          }
          if (packet.type === 'key') needKey = false;
          frameArrivals.set(packet.timestamp, {at: now, bytes: packet.data.byteLength});
          pendingEncodedBytes += packet.data.byteLength;
          decoder.decode(new EncodedVideoChunk(packet));
          watchDecodeProgress();
        } catch (error) {
          if (error.name === 'NotSupportedError') decoderFailure(error);
          else { diagnostic('packet_error'); status('Video interrupted · recovering'); recover(); }
        }
      };
      current.onclose = event => {
        const normal = leaving || event.code === 1000 || event.code === 1001;
        telemetry('browser_disconnected', {errorCode: String(event.code || 0),
          reason: leaving ? 'page_exit' : 'socket_closed', result: normal ? 'normal' : 'failed'});
        diagnostic('socket_closed', event.code);
        reconnect(event.reason || decodeError || 'Connection interrupted. Check that the phone hotspot is connected.');
      };
      current.onerror = () => { diagnostic('socket_error'); status('Connection unavailable'); };
    } catch (error) {
      if (attempt === connectionAttempt && !leaving) {
        playbackProblem(error.name === 'AbortError' ? 'session_timeout' : responseCode ? 'session_rejected' : 'session_unreachable',
          {errorCode: String(responseCode)});
        reconnect(error.name === 'AbortError' ? 'The phone did not respond in time.' : error.message || 'Phone not reachable.');
      }
    } finally { clearTimeout(timeout); if (sessionRequest === request) sessionRequest = null; }
  }
  $('retry').onclick = () => { reconnectCount = 0; decodeError = ''; unsupportedVideo = false; connect(); };
  let gesture, pendingPoint, pointerTimer, lastMoveAt = -Infinity;
  function flushMove() {
    const point = pendingPoint; pendingPoint = null;
    if (!gesture || !point || (point[0] === gesture.point[0] && point[1] === gesture.point[1])) return false;
    send({type: 'touch', action: 'move', x: point[0], y: point[1]});
    gesture.point = point;
    lastMoveAt = performance.now();
    return true;
  }
  function scheduleMove() {
    const remaining = 8 - (performance.now() - lastMoveAt);
    if (remaining <= 0) { cancelMoveTimer(); flushMove(); }
    else if (pointerTimer == null) pointerTimer = setTimeout(() => {
      pointerTimer = null; flushMove();
    }, remaining);
  }
  function cancelMoveTimer() {
    if (pointerTimer != null) {
      clearTimeout(pointerTimer);
      pointerTimer = null;
    }
  }
  function clearGesture(cancel = true) {
    const previous = gesture; gesture = null; pendingPoint = null; cancelMoveTimer(); lastMoveAt = -Infinity;
    if (previous && cancel) send({type: 'touch', action: 'cancel'});
    try { if (previous && canvas.hasPointerCapture(previous.id)) canvas.releasePointerCapture(previous.id); }
    catch (_) { /* The browser may already have released this pointer. */ }
  }
  function touchPoint(event, bounds = gesture ? gesture.bounds : canvas.getBoundingClientRect()) {
    if (!config) return null;
    const point = pointInVideo(event.clientX, event.clientY, bounds);
    if (!point) return null;
    const area = sourceArea();
    // CarPlay receives coordinates in the full encoded canvas, including native layout offsets.
    return [(area.x + point[0] * area.width) / area.canvasWidth,
      (area.y + point[1] * area.height) / area.canvasHeight];
  }
  canvas.addEventListener('pointerdown', event => {
    if (!connected || event.button !== 0 || gesture) return;
    const bounds = canvas.getBoundingClientRect(), point = touchPoint(event, bounds);
    if (!point) return;
    try { canvas.setPointerCapture(event.pointerId); } catch (_) { return; }
    // Resize and native-layout changes cancel the gesture, so these bounds stay valid.
    gesture = {id: event.pointerId, point, bounds};
    send({type: 'touch', action: 'down', x: point[0], y: point[1]});
    event.preventDefault();
  });
  // Raw input avoids browser rendering alignment; older browsers keep pointermove.
  const movementEvent = 'onpointerrawupdate' in root ? 'pointerrawupdate' : 'pointermove';
  canvas.addEventListener(movementEvent, event => {
    if (!gesture || gesture.id !== event.pointerId) return;
    const point = touchPoint(event);
    if (!point) return;
    if (point[0] !== gesture.point[0] || point[1] !== gesture.point[1]) gesture.moved = true;
    pendingPoint = point;
    // Bound high-frequency devices without tying remote input to local video rendering.
    scheduleMove();
  }, {passive: true});
  canvas.addEventListener('pointerup', event => {
    if (!gesture || gesture.id !== event.pointerId) return;
    cancelMoveTimer();
    const point = gesture.moved ? touchPoint(event) : null;
    if (point) pendingPoint = point;
    flushMove();
    try { send({type: 'touch', action: 'up'}); } finally { clearGesture(false); }
  });
  for (const eventName of ['pointercancel', 'lostpointercapture'])
    canvas.addEventListener(eventName, event => { if (gesture && gesture.id === event.pointerId) clearGesture(); });
  canvas.addEventListener('contextmenu', event => { event.preventDefault(); clearGesture(); });
  root.addEventListener('blur', () => clearGesture());
  document.addEventListener('visibilitychange', () => {
    clearGesture();
    diagnostic(document.hidden ? 'page_hidden' : 'page_visible');
    if (document.hidden) {
      clearTimeout(keyframeTimer); keyframeTimer = null;
      discardPresentation(); resetVideoTiming(); needKey = true;
    }
    if (!document.hidden) {
      try { if (root.TesAirTelemetry) root.TesAirTelemetry.refresh(); } catch (_) {}
      if (connected) {
        lastFrame = lastPacket = lastServerMessage = performance.now();
        recoveryRequested = false; scheduleDisplay(); recover();
      }
      else connect();
    }
  });
  root.addEventListener('online', () => { if (!connected) connect(); });
  document.addEventListener('fullscreenchange', scheduleDisplay);
  root.addEventListener('resize', scheduleDisplay);
  root.addEventListener('orientationchange', scheduleDisplay);
  if (root.visualViewport) root.visualViewport.addEventListener('resize', scheduleDisplay);
  setInterval(() => {
    if (!connected) return;
    send({ type: 'ping' });
    if (document.hidden) return;
    const now = performance.now(), elapsed = now - lastFrame;
    if (now - metricStartedAt >= 30000) reportPerformance(now);
    reportVideoTiming(now);
    if (now - lastServerMessage >= 8000) {
      diagnostic('transport_timeout');
      reconnect('Connection stopped responding. Restarting automatically.'); return;
    }
    if (elapsed >= 10000 && now - lastPacket < 3000) {
      diagnostic('decoder_timeout');
      reconnect('Video decoder stopped responding. Restarting automatically.'); return;
    }
    // The iPhone can pause video while its wireless session recovers. A responsive
    // server is not a broken browser connection: keep it open and ask for a keyframe.
    if (elapsed >= 2000 && (!recoveryRequested || now - recoveryAt >= 3000)) {
      if (!waitingForVideo) { diagnostic('video_wait'); waitingForVideo = true; }
      recoveryRequested = true; status('Waiting for video · requesting fresh frame'); recover();
    }
    if (receivedVideo && elapsed >= 3000) {
      clearGesture();
      canvas.hidden = true; $('panel').hidden = false; document.body.classList.remove('playing');
      connectionPresentation('Reconnecting…', 'Your display will return automatically.');
    }
  }, 1000);
  root.addEventListener('pagehide', () => {
    diagnostic('page_exit');
    clearGesture(); leaving = true; connected = false; connectionAttempt++;
    clearTimeout(reconnectTimer); clearTimeout(displayTimer);
    clearTimeout(socketTimer); if (sessionRequest) sessionRequest.abort();
    closeSocket(); closeDecoder();
  });
  root.addEventListener('pageshow', event => { if (event.persisted) { leaving = false; connect(); } });
  connect();
})(typeof window !== 'undefined' ? window : globalThis);
