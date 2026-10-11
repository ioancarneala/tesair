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
  function parsePacket(buffer) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength <= 9) throw new Error('Invalid video packet');
    const view = new DataView(buffer);
    if (view.getUint8(0) > 1) throw new Error('Invalid frame flags');
    const timestamp = view.getUint32(1) * 4294967296 + view.getUint32(5);
    if (!Number.isSafeInteger(timestamp)) throw new Error('Invalid video timestamp');
    return { type: view.getUint8(0) ? 'key' : 'delta', timestamp, data: new Uint8Array(buffer, 9) };
  }
  function retryDelay(attempt) { return Math.min(10000, 500 * Math.pow(2, Math.max(0, Math.min(5, attempt - 1)))); }
  if (typeof module !== 'undefined') module.exports = { pointInVideo, parseViewArea, displayViewport, parsePacket, retryDelay };
  if (!root.document) return;
  const $ = id => document.getElementById(id);
  const canvas = $('screen'), context = canvas.getContext('2d', { alpha: false, desynchronized: true });
  let socket, decoder, config, needKey = true, reconnectTimer, reconnectCount = 0, connected = false;
  let lastFrame = 0, lastPacket = 0, lastServerMessage = 0, recoveryAt = 0;
  let displayTimer, lastDisplay = '', decodeError = '';
  let sessionRequest, connectionAttempt = 0, socketTimer, leaving = false;
  let receivedVideo = false, recoveryRequested = false, unsupportedVideo = false;
  let waitingForVideo = false, pendingDiagnostic = null;
  let viewArea = null, pendingViewArea = null;
  const frameArrivals = new Map();
  let timingSamples = 0, decodeTotal = 0, decodeMax = 0, drawTotal = 0, drawMax = 0, timingReportedAt = 0;
  function send(message) { if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); }
  function diagnostic(event, closeCode = 0, timings = {}) {
    const now = performance.now();
    const message = {type: 'diagnostic', event, frameAgeMs: Math.round(now - lastFrame),
      packetAgeMs: Math.round(now - lastPacket), decodeQueue: decoder ? decoder.decodeQueueSize || 0 : 0,
      closeCode: Number.isInteger(closeCode) ? closeCode : 0, hidden: document.hidden, ...timings};
    if (socket && socket.readyState === WebSocket.OPEN) send(message);
    else pendingDiagnostic = message;
  }
  function resetVideoTiming() {
    frameArrivals.clear();
    timingSamples = decodeTotal = decodeMax = drawTotal = drawMax = 0;
    timingReportedAt = performance.now();
  }
  function reportVideoTiming(now) {
    if (!timingSamples || now - timingReportedAt < 5000) return;
    diagnostic('latency', 0, {samples: timingSamples, decodeAvgUs: Math.round(decodeTotal * 1000 / timingSamples),
      decodeMaxUs: Math.round(decodeMax * 1000), drawAvgUs: Math.round(drawTotal * 1000 / timingSamples),
      drawMaxUs: Math.round(drawMax * 1000)});
    timingSamples = decodeTotal = decodeMax = drawTotal = drawMax = 0;
    timingReportedAt = now;
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
  function scheduleDisplay() { clearGesture(); clearTimeout(displayTimer); displayTimer = setTimeout(displaySize, 200); }
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
  function recover() {
    needKey = true;
    const now = performance.now();
    if (now - recoveryAt < 500) return;
    recoveryAt = now;
    resetVideoTiming();
    try { if (decoder && decoder.state !== 'closed') { decoder.reset(); if (config) decoder.configure(config); } }
    catch (error) { decoderFailure(error); return; }
    send({ type: 'keyframe' });
  }
  function createDecoder(format) {
    clearGesture();
    closeDecoder();
    if (!Number.isInteger(format.width) || !Number.isInteger(format.height)
        || format.width <= 0 || format.height <= 0 || format.width > 8192 || format.height > 8192
        || typeof format.codec !== 'string') throw new Error('Invalid video format');
    canvas.width = format.width; canvas.height = format.height;
    config = { codec: format.codec, codedWidth: format.width, codedHeight: format.height,
      // Let the browser use its available hardware decoder or fall back to software.
      optimizeForLatency: true, hardwareAcceleration: 'no-preference' };
    if (viewArea && (viewArea.canvasWidth !== format.width || viewArea.canvasHeight !== format.height)) viewArea = null;
    applyViewArea();
    const next = new VideoDecoder({
      output(frame) {
        if (decoder !== next) { frame.close(); return; }
        const area = sourceArea();
        const receivedAt = frameArrivals.get(frame.timestamp), outputAt = performance.now();
        frameArrivals.delete(frame.timestamp);
        try {
          context.drawImage(frame, area.x, area.y, area.width, area.height, 0, 0, canvas.width, canvas.height);
          // Decode includes browser queueing; draw measures submission, not physical presentation.
          if (receivedAt !== undefined && timingSamples < 10000) {
            const decodeMs = Math.max(0, outputAt - receivedAt), drawMs = Math.max(0, performance.now() - outputAt);
            timingSamples++; decodeTotal += decodeMs; drawTotal += drawMs;
            decodeMax = Math.max(decodeMax, decodeMs); drawMax = Math.max(drawMax, drawMs);
          }
          send({ type: 'ack', pts: frame.timestamp });
        }
        finally { frame.close(); }
        if (waitingForVideo) diagnostic('video_resumed');
        waitingForVideo = false;
        lastFrame = performance.now(); receivedVideo = true; recoveryRequested = false;
        canvas.hidden = false; $('panel').hidden = true; document.body.classList.add('playing'); reconnectCount = 0; decodeError = '';
      },
      error(error) { if (decoder === next) decoderFailure(error); }
    });
    decoder = next;
    decoder.configure(config); needKey = true;
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
    viewArea = pendingViewArea;
    pendingViewArea = null;
  }
  function closeDecoder() {
    resetVideoTiming();
    const previous = decoder; decoder = null; config = null;
    if (previous && previous.state !== 'closed') previous.close();
  }
  function closeSocket() {
    viewArea = pendingViewArea = null;
    if (!socket) return;
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
    canvas.hidden = true; $('panel').hidden = false; document.body.classList.remove('playing');
    if (!root.isSecureContext || !root.VideoDecoder) {
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
    const timeout = setTimeout(() => request.abort(), 8000);
    try {
      const response = await fetch('/session', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' }, body: '{}', signal: request.signal });
      if (!response.ok) throw new Error((await response.text()).trim().slice(0, 240) || `Phone returned ${response.status}.`);
      if (attempt !== connectionAttempt || leaving) return;
      socket = new WebSocket(`wss://${location.host}/stream`); socket.binaryType = 'arraybuffer';
      const current = socket;
      socketTimer = setTimeout(() => {
        if (current === socket && current.readyState !== WebSocket.OPEN) reconnect('The phone stream did not open in time.');
      }, 10000);
      current.onopen = () => {
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
              status(payload.message);
              if (!$('panel').hidden) {
                if (payload.type === 'error') connectionPresentation('Check your phone', '', true, payload.message);
                else connectionPresentation(reconnectCount ? 'Reconnecting…' : 'Connecting…', payload.message);
              }
            }
            return;
          }
          const packet = parsePacket(event.data);
          lastPacket = performance.now();
          if (!decoder || decoder.state !== 'configured') return;
          if (decoder.decodeQueueSize > 3) { diagnostic('decode_backpressure'); recover(); return; }
          if (needKey && packet.type !== 'key') return;
          if (packet.type === 'key') needKey = false;
          if (frameArrivals.size >= 16) frameArrivals.delete(frameArrivals.keys().next().value);
          frameArrivals.set(packet.timestamp, performance.now());
          decoder.decode(new EncodedVideoChunk(packet));
        } catch (error) {
          if (error.name === 'NotSupportedError') decoderFailure(error);
          else { diagnostic('packet_error'); status('Video interrupted · recovering'); recover(); }
        }
      };
      current.onclose = event => {
        diagnostic('socket_closed', event.code);
        reconnect(event.reason || decodeError || 'Connection interrupted. Check that the phone hotspot is connected.');
      };
      current.onerror = () => { diagnostic('socket_error'); status('Connection unavailable'); };
    } catch (error) {
      if (attempt === connectionAttempt && !leaving)
        reconnect(error.name === 'AbortError' ? 'The phone did not respond in time.' : error.message || 'Phone not reachable.');
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
    if (!document.hidden) {
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
