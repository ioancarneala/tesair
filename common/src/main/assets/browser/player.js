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
  // A dropped socket is usually transient: retry at once, then back off to ten seconds.
  function probeHevc() {
    if (typeof root.VideoDecoder !== 'function' || typeof root.VideoDecoder.isConfigSupported !== 'function')
      return Promise.resolve(false);
    // Levels differ per Tesla hardware; accept any profile/level the browser can configure.
    const candidates = ['hvc1.1.6.L123.B0', 'hvc1.1.6.L93.B0', 'hev1.1.6.L123.B0', 'hev1.1.6.L93.B0'];
    try {
      return Promise.all(candidates.map(codec =>
        root.VideoDecoder.isConfigSupported({codec, codedWidth: 1920, codedHeight: 1080})
          .then(result => result.supported === true).catch(() => false)))
        .then(results => results.some(Boolean)).catch(() => false);
    } catch (_) { return Promise.resolve(false); }
  }
  function retryDelay(attempt) {
    if (attempt <= 1) return 0;
    return Math.min(10000, 250 * Math.pow(2, Math.max(0, Math.min(6, attempt - 2))));
  }
  if (typeof module !== 'undefined') module.exports = { pointInVideo, parseViewArea, displayViewport, renderViewport, parsePacket, retryDelay };
  if (!root.document) return;
  const $ = id => document.getElementById(id);
  const canvas = $('screen');
  const pixelProbe = /(?:^|[?&])probe=1(?:&|$)/.test((root.location && root.location.search) || '');
  // A canvas with a context cannot be transferred to the draw worker, so create it lazily.
  let context = null;
  function localContext() {
    if (!context) context = canvas.getContext('2d', { alpha: false, desynchronized: true });
    return context;
  }
  let socket, decoder, config, needKey = true, reconnectTimer, reconnectCount = 0, connected = false;
  let lastFrame = 0, lastPacket = 0, lastServerMessage = 0, recoveryAt = -Infinity;
  let displayTimer, keyframeTimer, lastDisplay = '', decodeError = '';
  let sessionRequest, connectionAttempt = 0, socketTimer, leaving = false, sessionReady = false;
  let hevcSupported = false, hevcProbed = false;
  let receivedVideo = false, recoveryRequested = false, unsupportedVideo = false;
  let reportedPlaybackProblem = false;
  let reportNextDraw = false;
  let waitingForVideo = false, pendingDiagnostic = null;
  let viewArea = null, pendingViewArea = null;
  const frameArrivals = new Map();
  const MAX_PENDING_FRAMES = 12, MAX_PENDING_BYTES = 8 * 1024 * 1024, MAX_CONTROL_BUFFER_BYTES = 16 * 1024;
  let pendingEncodedBytes = 0, pendingFrame = null, renderHandle = null, renderTimer = null, renderEpoch = 0, renderJob = 0, renderSizeDirty = true;
  let canvasPixels = null, drawWorker = null, drawInFlight = false, workerFailed = false;
  let timingSamples = 0, timingDecodeSamples = 0, decodeTotal = 0, decodeMax = 0, drawTotal = 0, drawMax = 0,
    presentWaitTotal = 0, presentWaitMax = 0, timingReportedAt = 0, timingReceived = 0, timingDrawn = 0;
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
  function send(message) {
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    if (socket.bufferedAmount >= MAX_CONTROL_BUFFER_BYTES) {
      if (message.type === 'diagnostic' || (message.type === 'touch' && message.action === 'move')) return false;
      failSend('control_backpressure'); return false;
    }
    try { socket.send(JSON.stringify(message)); return true; }
    catch (_) { failSend('socket_error'); return false; }
  }
  function failSend(reason) {
    playbackProblem(reason);
    // Do not queue a release behind stale input. Closing also cancels the server's contact.
    closeSocket();
    if (connected) reconnect('Connection interrupted. Reconnecting automatically.');
  }
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
    frameArrivals.clear(); pendingEncodedBytes = 0;
    timingSamples = timingDecodeSamples = decodeTotal = decodeMax = drawTotal = drawMax = presentWaitTotal = presentWaitMax = 0;
    timingReceived = timingDrawn = 0;
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
      drawMaxUs: Math.round(drawMax * 1000), presentWaitAvgUs: Math.round(presentWaitTotal * 1000 / timingSamples),
      presentWaitMaxUs: Math.round(presentWaitMax * 1000), framesReceived: timingReceived,
      framesDrawn: timingDrawn, windowMs: Math.round(now - timingReportedAt),
      requestedFps: requestedFps, codec: config ? config.codec : ''});
    timingSamples = timingDecodeSamples = decodeTotal = decodeMax = drawTotal = drawMax = presentWaitTotal = presentWaitMax = 0;
    timingReceived = timingDrawn = 0;
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
    // Tell the relay before reconnecting so its next session is AVC instead of looping.
    if (config && /^(hvc1|hev1)/i.test(config.codec) && socket && socket.readyState === WebSocket.OPEN)
      send({type: 'capabilities', hevc: false});
    closeDecoder();
    if (error && error.name === 'NotSupportedError') {
      unsupportedVideo = true;
      reconnect('This browser cannot decode the CarPlay video format. Update the browser or use a supported browser, then select Try again.');
      return;
    }
    config = null; status('Video decoder unavailable');
    decodeError = 'This browser could not decode the video. The connection will restart automatically.';
    reconnect(decodeError);
  }
  function cancelPresentationSchedule() {
    if (renderHandle != null) {
      if (typeof root.cancelAnimationFrame === 'function') root.cancelAnimationFrame(renderHandle);
      else clearTimeout(renderHandle);
      renderHandle = null;
    }
    if (renderTimer != null) { clearTimeout(renderTimer); renderTimer = null; }
  }
  function discardPresentation() {
    renderEpoch++; renderJob++;
    cancelPresentationSchedule();
    if (drawWorker) drawWorker.postMessage({type: 'epoch', epoch: renderEpoch});
    if (pendingFrame) { pendingFrame.frame.close(); pendingFrame = null; metricDropped++; }
  }
  /** Shared by the local canvas and the off-thread draw worker. */
  let probeReported = false;
  function finishPresentation(pts, drawMs, presentWaitMs, epoch, probe) {
    if (epoch !== renderEpoch || document.hidden) return;
    clearTimeout(keyframeTimer); keyframeTimer = null;
    metricDrawn++; timingDrawn++; timingSamples++; drawTotal += drawMs; drawMax = Math.max(drawMax, drawMs);
    presentWaitTotal += presentWaitMs; presentWaitMax = Math.max(presentWaitMax, presentWaitMs);
    const reportFrame = !receivedVideo || reportedPlaybackProblem || reportNextDraw;
    const reportSource = reportedPlaybackProblem ? 'recovery' : 'first_draw', resumed = waitingForVideo;
    // Decode credits were already released; this only confirms that a picture reached the canvas.
    send({type: 'presented', pts});
    if (pixelProbe && probe && !probeReported) {
      probeReported = true;
      send({type: 'draw_probe', sample: probe.sample === true, width: probe.width | 0, height: probe.height | 0});
    }
    reportedPlaybackProblem = reportNextDraw = false;
    waitingForVideo = false;
    lastFrame = performance.now(); receivedVideo = true; recoveryRequested = false;
    canvas.hidden = false; $('panel').hidden = true; document.body.classList.add('playing'); reconnectCount = 0; decodeError = '';
    if (reportFrame) telemetry('browser_first_frame', {
      ...videoContext(), source: reportSource, sampleDecoded: true,
      width: config.codedWidth, height: config.codedHeight});
    if (resumed) diagnostic('video_resumed');
  }
  function presentFrame(epoch, job) {
    if (epoch !== renderEpoch || job !== renderJob) return;
    renderJob++;
    cancelPresentationSchedule();
    const pending = pendingFrame; pendingFrame = null;
    if (!pending) return;
    const frame = pending.frame;
    let transferred = false;
    try {
      if (document.hidden || pending.decoder !== decoder || decoder.state !== 'configured') return;
      const area = sourceArea();
      if (renderSizeDirty || !canvasPixels) {
        const bounds = $('stage').getBoundingClientRect();
        const size = renderViewport(bounds.width, bounds.height, root.devicePixelRatio || 1, area.width, area.height);
        if (size) { canvasPixels = size; renderSizeDirty = false; }
      }
      if (!canvasPixels) return;
      if (drawWorker) {
        // One draw in flight: a slow GPU must not accumulate VideoFrames in the worker queue.
        if (drawInFlight) { metricDropped++; return; }
        drawInFlight = true;
        drawWorker.postMessage({type: 'draw', frame, pts: frame.timestamp, epoch, area,
          width: canvasPixels.width, height: canvasPixels.height,
          outputAt: pending.outputAt, probe: pixelProbe}, [frame]);
        transferred = true;
        return;
      }
      if (canvas.width !== canvasPixels.width) canvas.width = canvasPixels.width;
      if (canvas.height !== canvasPixels.height) canvas.height = canvasPixels.height;
      const drawAt = performance.now();
      const presentWaitMs = Math.max(0, drawAt - pending.outputAt);
      localContext().drawImage(frame, area.x, area.y, area.width, area.height, 0, 0, canvas.width, canvas.height);
      const drawMs = Math.max(0, performance.now() - drawAt);
      finishPresentation(frame.timestamp, drawMs, presentWaitMs, epoch, null);
    } catch (error) { decoderFailure(error); }
    finally { if (!transferred) frame.close(); }
  }
  let hevcProbe = probeHevc();
  function reloadAfterWorkerFailure() {
    if (workerFailed) return;
    workerFailed = true;
    drawWorker = null;
    try {
      if (root.sessionStorage && !root.sessionStorage.getItem('tesairWorkerReload')) {
        root.sessionStorage.setItem('tesairWorkerReload', '1');
        root.location.reload();
      }
    } catch (_) { try { root.location.reload(); } catch (__) { } }
  }
  function startDrawWorker() {
    if (typeof root.Worker !== 'function' || typeof root.OffscreenCanvas !== 'function'
        || typeof canvas.transferControlToOffscreen !== 'function') return;
    try {
      const offscreen = canvas.transferControlToOffscreen();
      const worker = new root.Worker('/player-worker.js');
      worker.onmessage = event => {
        const message = event.data;
        if (!message) return;
        if (message.type === 'dropped') {
          if (message.epoch === renderEpoch) drawInFlight = false;
          return;
        }
        if (message.type === 'error') { drawInFlight = false; diagnostic('draw_error'); reloadAfterWorkerFailure(); return; }
        if (message.type !== 'presented') return;
        drawInFlight = false;
        try { if (root.sessionStorage) root.sessionStorage.removeItem('tesairWorkerReload'); } catch (_) {}
        finishPresentation(message.pts, message.drawMs, message.presentWaitMs, message.epoch, message);
      };
      worker.onerror = () => { diagnostic('draw_worker_error'); reloadAfterWorkerFailure(); };
      worker.postMessage({type: 'canvas', canvas: offscreen}, [offscreen]);
      drawWorker = worker;
    } catch (_) { drawWorker = null; reloadAfterWorkerFailure(); }
  }
  function queuePresentation(frame, next, outputAt) {
    if (pendingFrame) { pendingFrame.frame.close(); metricDropped++; }
    pendingFrame = {frame, decoder: next, outputAt};
    if (renderHandle != null || renderTimer != null) return;
    const epoch = renderEpoch, job = ++renderJob;
    if (typeof root.requestAnimationFrame !== 'function') { presentFrame(epoch, job); return; }
    renderHandle = root.requestAnimationFrame(() => presentFrame(epoch, job));
    // A visible embedded browser can service timers while postponing refresh callbacks.
    // Keep one deadline for the coalesced job; only a successful draw releases ACK credits.
    renderTimer = setTimeout(() => presentFrame(epoch, job), 50);
  }
  function recover(resetServer = true) {
    needKey = true;
    discardPresentation(); resetVideoTiming();
    try { if (decoder && decoder.state !== 'closed') { decoder.reset(); if (config) decoder.configure(config); } }
    catch (error) { decoderFailure(error); return; }
    requestKeyframe(resetServer);
  }
  function requestKeyframe(resetServer = false) {
    clearTimeout(keyframeTimer);
    const now = performance.now();
    const remaining = 500 - (now - recoveryAt), epoch = renderEpoch;
    const request = () => {
      keyframeTimer = null;
      if (epoch !== renderEpoch || !connected || document.hidden || !socket || socket.readyState !== WebSocket.OPEN) return;
      recoveryAt = performance.now(); send({type: 'keyframe', reset: resetServer});
    };
    if (remaining > 0) keyframeTimer = setTimeout(request, remaining);
    else request();
  }
  function createDecoder(format) {
    if (!Number.isInteger(format.width) || !Number.isInteger(format.height)
        || format.width <= 0 || format.height <= 0 || format.width > 8192 || format.height > 8192
        || typeof format.codec !== 'string') throw new Error('Invalid video format');
    clearGesture();
    closeDecoder();
    renderSizeDirty = true;
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
        metricDecoded++;
        metricDecodeTotal += decodeMs; metricDecodeMax = Math.max(metricDecodeMax, decodeMs); metricDecodeSamples++;
        if (timingDecodeSamples < 10000) {
          timingDecodeSamples++; decodeTotal += decodeMs; decodeMax = Math.max(decodeMax, decodeMs);
        }
        // Credit follows decoding, not drawing: the relay may refill the decoder while the
        // canvas presents the newest frame, so a draw callback no longer caps the frame rate.
        if (!send({type: 'ack', pts: frame.timestamp})) { frame.close(); return; }
        queuePresentation(frame, next, outputAt);
      },
      error(error) { if (decoder === next) decoderFailure(error); }
    });
    decoder = next;
    try {
      decoder.configure(config);
    } catch (first) {
      // Some Chromium builds expose HEVC only under the hev1 sample-entry prefix.
      if (/^hvc1/i.test(config.codec)) {
        config = {...config, codec: config.codec.replace(/^hvc1/i, 'hev1')};
        try { decoder.configure(config); }
        catch (second) { throw first; }
      } else throw first;
    }
    needKey = true;
    telemetry('browser_decoder_ready', {codec: config.codec, width: config.codedWidth, height: config.codedHeight});
    if (typeof VideoDecoder.isConfigSupported === 'function') {
      const probeConfig = config;
      try {
        VideoDecoder.isConfigSupported(probeConfig).then(result => telemetry('browser_capabilities', {
          codec: probeConfig.codec, width: probeConfig.codedWidth, height: probeConfig.codedHeight, configSupported: result.supported
        })).catch(() => telemetry('browser_capabilities', {result: 'unknown'}));
        // Record whether this browser can decode HEVC at all; the relay only switches after a yes.
        telemetry('browser_capabilities', {codec: 'hvc1.1.6.L93.B0', hevcSupported});
        send({type: 'capabilities', hevc: hevcSupported});
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
    clearTimeout(keyframeTimer); keyframeTimer = null;
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
    clearGesture(); closeSocket(); discardPresentation();
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
    connected = false;
    clearGesture(); closeSocket(); discardPresentation();
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
      // The session cookie outlives a dropped socket. Reconnect straight to the stream and only
      // take the extra HTTPS round trip when the phone rejects the stale cookie.
      if (!sessionReady) {
        if (!hevcProbed) { hevcSupported = await hevcProbe; hevcProbed = true; }
        const response = await fetch('/session', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({hevc: hevcSupported}),
          signal: request.signal });
        responseCode = response.status || 0;
        if (!response.ok) throw new Error((await response.text()).trim().slice(0, 240) || `Phone returned ${response.status}.`);
        const sessionInfo = typeof response.json === 'function' ? await response.json().catch(() => null) : null;
        sessionReady = true;
        // An updated app must replace a page that the Tesla browser kept open for days.
        const page = sessionInfo && typeof sessionInfo.page === 'string' ? sessionInfo.page : '';
        if (page) {
          let seen = null;
          try { seen = root.sessionStorage && root.sessionStorage.getItem('tesairPage'); } catch (_) {}
          try { if (root.sessionStorage) root.sessionStorage.setItem('tesairPage', page); } catch (_) {}
          if (seen && seen !== page) { root.location.reload(); return; }
        }
      }
      if (attempt !== connectionAttempt || leaving) return;
      socket = new WebSocket(`wss://${location.host}/stream`); socket.binaryType = 'arraybuffer';
      const current = socket;
      let opened = false;
      socketTimer = setTimeout(() => {
        if (current === socket && current.readyState !== WebSocket.OPEN) {
          playbackProblem('socket_open_timeout');
          reconnect('The phone stream did not open in time.');
        }
      }, 10000);
      current.onopen = () => {
        opened = true;
        telemetry('browser_connected', {automatic: reconnectCount > 0});
        clearTimeout(socketTimer);
        connected = true; lastDisplay = ''; status('Connected · waiting for picture');
        connectionPresentation(reconnectCount ? 'Reconnecting…' : 'Connecting…', 'Waiting for CarPlay.');
        $('retry').disabled = false; lastFrame = lastPacket = lastServerMessage = performance.now();
        receivedVideo = false; recoveryRequested = false; waitingForVideo = false;
        displaySize(); send({ type: 'keyframe', reset: false });
        if (pendingDiagnostic) { send(pendingDiagnostic); pendingDiagnostic = null; }
      };
      current.onmessage = event => {
        lastServerMessage = performance.now();
        try {
          if (typeof event.data === 'string') {
            const payload = JSON.parse(event.data);
            if (payload.type === 'recover') {
              // The server has already invalidated its queue and requested an IDR.
              diagnostic('server_recovery'); recover(false);
            } else if (payload.type === 'format') {
              try { createDecoder(payload); } catch (error) { decoderFailure(error); }
            } else if (payload.type === 'viewArea') {
              const area = parseViewArea(payload);
              if (area) { pendingViewArea = area; applyViewArea(); }
            } else if ((payload.type === 'status' || payload.type === 'error') && typeof payload.message === 'string') {
              if (payload.type === 'error') playbackProblem('server_error');
              if (payload.videoReady === false) {
                clearGesture(); closeDecoder(); needKey = true;
                receivedVideo = recoveryRequested = waitingForVideo = false;
                canvas.hidden = true; $('panel').hidden = false; document.body.classList.remove('playing');
              }
              status(payload.message);
              if (!$('panel').hidden) {
                if (payload.type === 'error') connectionPresentation('Check your phone', '', true, payload.message);
                else connectionPresentation(payload.videoReady === false || reconnectCount ? 'Reconnecting…' : 'Connecting…', payload.message);
              }
            } else if (payload.type === 'telemetry_consent') {
              try { if (root.TesAirTelemetry) root.TesAirTelemetry.refresh(); } catch (_) {}
              // Report the next actual draw, never relabel an old picture as a fresh frame.
              reportNextDraw = payload.enabled === true;
            }
            return;
          }
          const packet = parsePacket(event.data);
          metricReceived++; timingReceived++;
          lastPacket = performance.now();
          if (!decoder || decoder.state !== 'configured') return;
          if (document.hidden) {
            // Keep credits alive while hidden; a visible page asks for a fresh keyframe.
            if (packet.type === 'key') needKey = true;
            send({type: 'ack', pts: packet.timestamp});
            metricDropped++;
            return;
          }
          if (needKey && packet.type !== 'key') { metricDropped++; return; }
          if (frameArrivals.has(packet.timestamp)) { metricDropped++; return; }
          const now = performance.now();
          metricQueueMax = Math.max(metricQueueMax, decoder.decodeQueueSize || 0, frameArrivals.size);
          // Short browser stalls are not decoder failures; ACK liveness handles sustained stalls.
          if (decoder.decodeQueueSize >= MAX_PENDING_FRAMES || frameArrivals.size >= MAX_PENDING_FRAMES
              || pendingEncodedBytes + packet.data.byteLength > MAX_PENDING_BYTES) {
            metricDropped++; diagnostic('decode_backpressure'); recover(); return;
          }
          if (packet.type === 'key') needKey = false;
          frameArrivals.set(packet.timestamp, {at: now, bytes: packet.data.byteLength});
          pendingEncodedBytes += packet.data.byteLength;
          decoder.decode(new EncodedVideoChunk(packet));
        } catch (error) {
          if (error.name === 'NotSupportedError') decoderFailure(error);
          else { diagnostic('packet_error'); status('Video interrupted · recovering'); recover(); }
        }
      };
      current.onclose = event => {
        const normal = leaving || event.code === 1000 || event.code === 1001;
        // A rejection before the socket opens means the cached session cookie went stale.
        if (!opened) sessionReady = false;
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
    const current = gesture;
    if (!current || !point || (point[0] === current.point[0] && point[1] === current.point[1])) return false;
    if (!send({type: 'touch', action: 'move', x: point[0], y: point[1]})) {
      if (gesture === current) pendingPoint = point;
      return false;
    }
    current.point = point;
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
    if (!send({type: 'touch', action: 'down', x: point[0], y: point[1]})) clearGesture(false);
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
    if (!send({ type: 'ping' })) return;
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
    // An unchanged picture can stop producing packets. Keep its decoder and image
    // while the connection is healthy; only pending decode work indicates a stall.
    const quietSource = !waitingForVideo && pendingFrame == null && frameArrivals.size === 0 && (!decoder || !decoder.decodeQueueSize)
      && now - lastPacket >= 2000;
    if (elapsed >= 2000 && (!recoveryRequested || now - recoveryAt >= 3000)) {
      recoveryRequested = true;
      if (quietSource) requestKeyframe();
      else {
        if (!waitingForVideo) { diagnostic('video_wait'); waitingForVideo = true; }
        status('Waiting for video · requesting fresh frame'); recover();
      }
    }
    if (receivedVideo && elapsed >= 3000 && !quietSource) {
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
  startDrawWorker();
  connect();
})(typeof window !== 'undefined' ? window : globalThis);
