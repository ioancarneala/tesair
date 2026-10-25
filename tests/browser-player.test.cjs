const test = require('node:test');
const assert = require('node:assert/strict');
const { pointInVideo, parseViewArea, displayViewport, renderViewport, parsePacket, retryDelay } = require('../common/src/main/assets/browser/player.js');
test('touch coordinates follow the full displayed image, including aligned edge pixels', () => {
  const box = {left: 0, top: 64, width: 1600, height: 1000};
  assert.deepEqual(pointInVideo(800, 564, box), [0.5, 0.5]);
  assert.deepEqual(pointInVideo(0, 64, box), [0, 0]);
  assert.deepEqual(pointInVideo(1600, 1064, box), [1, 1]);
  assert.equal(pointInVideo(800, 63, box), null);
  assert.equal(pointInVideo(NaN, 64, box), null);
  assert.equal(pointInVideo(0, 64, {...box, width: 0}), null);
});
test('native view areas accept only finite integer rectangles inside their source canvas', () => {
  const area = {x: 120, y: 90, width: 1680, height: 900, canvasWidth: 1920, canvasHeight: 1080};
  assert.deepEqual(parseViewArea(area), area);
  for (const invalid of [null, {}, {...area, x: -1}, {...area, width: 0}, {...area, x: 300},
      {...area, y: 200}, {...area, width: 0.5}, {...area, height: Infinity},
      {...area, canvasWidth: 8193}, {...area, canvasHeight: '1080'}]) assert.equal(parseViewArea(invalid), null);
});
test('display requests use physical pixels from the usable viewport', () => {
  assert.deepEqual(displayViewport(1280, 720), {type: 'display', width: 1280, height: 720, dpr: 1});
  assert.deepEqual(displayViewport(1280, 720, 2), {type: 'display', width: 2560, height: 1440, dpr: 2});
  assert.deepEqual(displayViewport(800, 1200, 1.5), {type: 'display', width: 1200, height: 1800, dpr: 1.5});
});
test('display requests bound pixel density and preserve aspect when capping resolution', () => {
  assert.deepEqual(displayViewport(1920, 1080, 3), {type: 'display', width: 2560, height: 1440, dpr: 2});
  assert.deepEqual(displayViewport(4000, 2000, 1), {type: 'display', width: 2560, height: 1280, dpr: 1});
  assert.deepEqual(displayViewport(800, 600, 0.75), {type: 'display', width: 800, height: 600, dpr: 1});
  assert.deepEqual(displayViewport(390.5, 844.25, 2), {type: 'display', width: 781, height: 1689, dpr: 2});
});
test('invalid or hidden viewports do not request a display', () => {
  for (const values of [[0, 720, 1], [1280, -1, 1], [Infinity, 720, 1], [1280, NaN, 1],
      [1280, 720, NaN], [1280, 720, 0], ['1280', 720, 1]]) assert.equal(displayViewport(...values), null);
});
test('timestamps and keyframe flags survive the binary protocol', () => {
  const bytes = new ArrayBuffer(12), view = new DataView(bytes);
  view.setUint8(0, 1); view.setBigUint64(1, 123456789123n);
  new Uint8Array(bytes, 9).set([0, 0, 1]);
  const packet = parsePacket(bytes);
  assert.equal(packet.timestamp, 123456789123); assert.equal(packet.type, 'key');
  assert.deepEqual([...packet.data], [0, 0, 1]);
});
test('malformed packets and nonrepresentable timestamps are rejected', () => {
  assert.throws(() => parsePacket(new ArrayBuffer(9)));
  const bytes = new ArrayBuffer(10), view = new DataView(bytes);
  view.setBigUint64(1, 18446744073709551615n);
  assert.throws(() => parsePacket(bytes));
  view.setBigUint64(1, 0n); view.setUint8(0, 2);
  assert.throws(() => parsePacket(bytes));
});
test('automatic retry backs off and stays bounded after long outages', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 100].map(retryDelay), [500, 1000, 2000, 4000, 8000, 10000, 10000]);
});

function browserFixture(responses = [], videoSupported = true, decoderOptions = {}, options = {}) {
  const elements = new Map(), timers = new Map(), animations = new Map(), sockets = [], requests = [], decoders = [], intervals = [], draws = [];
  const html = require('node:fs').readFileSync(require.resolve('../common/src/main/assets/browser/index.html'), 'utf8');
  const htmlIds = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
  let nextTimer = 0, now = 0;
  function eventTarget() {
    const listeners = new Map();
    return {addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(listener); },
      dispatch(name, event = {}) { for (const listener of listeners.get(name) || []) listener(event); }};
  }
  function element() {
    const classes = new Set(), pointers = new Set();
    return {...eventTarget(), hidden: false, textContent: '', disabled: false, focus() {},
      setPointerCapture: id => pointers.add(id), hasPointerCapture: id => pointers.has(id), releasePointerCapture: id => pointers.delete(id),
      getBoundingClientRect: () => ({left: 0, top: 0, width: 1920, height: 1080}),
      getContext: () => ({drawImage(...args) { if (options.drawError) throw options.drawError; draws.push(args); now += options.drawDelayMs || 0; }}),
      classList: {add: (...names) => names.forEach(name => classes.add(name)),
        remove: (...names) => names.forEach(name => classes.delete(name)),
        toggle: (name, on) => on ? classes.add(name) : classes.delete(name), contains: name => classes.has(name)} };
  }
  const document = {...eventTarget(), body: element(), hidden: false, documentElement: element(),
    getElementById(id) {
      if (!htmlIds.has(id)) return null;
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    }};
  class WebSocket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; sockets.push(this); }
    send(value) { this.sent.push(JSON.parse(value)); }
    close() { this.readyState = 3; }
  }
  class VideoDecoder {
    constructor(callbacks) { this.callbacks = callbacks; this.state = 'unconfigured'; decoders.push(this); }
    configure(config) {
      this.config = config;
      if (decoderOptions.configureError) throw decoderOptions.configureError;
      this.state = 'configured';
    }
    close() { this.state = 'closed'; }
    reset() { this.state = 'unconfigured'; }
    decode(chunk) { (this.chunks ||= []).push(chunk); }
  }
  const window = {...eventTarget(), document, isSecureContext: true, devicePixelRatio: 1,
    visualViewport: eventTarget(),
    VideoDecoder: videoSupported ? VideoDecoder : undefined};
  if (options.rawInput) window.onpointerrawupdate = null;
  if (options.telemetry) window.TesAirTelemetry = options.telemetry;
  if (options.animationFrames !== false) {
    window.requestAnimationFrame = callback => { const id = ++nextTimer; animations.set(id, callback); return id; };
    window.cancelAnimationFrame = id => animations.delete(id);
  }
  require('node:vm').runInNewContext(require('node:fs').readFileSync(require.resolve('../common/src/main/assets/browser/player.js'), 'utf8'), {
    window, document, WebSocket, VideoDecoder, AbortController, ArrayBuffer, DataView, Uint8Array,
    EncodedVideoChunk: class { constructor(packet) { Object.assign(this, packet); } }, performance: {now: () => now}, location: {host: 'bridge.test:8443'},
    fetch: async (url, options) => {
      requests.push({url, options});
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return response || {ok: true};
    },
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, {callback, delay}); return id; },
    clearTimeout: id => timers.delete(id), setInterval(callback) { intervals.push(callback); }
  });
  function runAnimationFrame(elapsed = 16) {
    now += elapsed;
    const callbacks = [...animations.values()]; animations.clear();
    for (const callback of callbacks) callback(now);
  }
  return {elements, sockets, requests, decoders, document, window, draws,
    drawFrame(decoder, frame, submitted = false) {
      if (!submitted) sockets.at(-1).onmessage({data: videoPacket(frame.timestamp)});
      decoder.callbacks.output(frame); runAnimationFrame(0);
    },
    async settle() { await new Promise(setImmediate); },
    pendingTimers() { return [...timers.values()].map(timer => timer.delay); },
    pendingAnimationFrames() { return animations.size; },
    animationCallbacks() { return [...animations.values()]; },
    elapse(elapsed) { now += elapsed; },
    runAnimationFrame,
    tick(elapsed) { now += elapsed; for (const callback of intervals) callback(); },
    runTimer(delay) {
      const entry = [...timers].find(([, timer]) => timer.delay === delay);
      assert.ok(entry, `Expected ${delay} ms timer`); timers.delete(entry[0]); entry[1].callback();
    }};
}
test('page load obtains an automatic cookie session before opening the stream', async () => {
  const browser = browserFixture(); await browser.settle();
  assert.equal(browser.requests.length, 1);
  const {url, options} = browser.requests[0];
  assert.equal(url, '/session'); assert.equal(options.method, 'POST');
  assert.equal(options.body, '{}'); assert.equal(options.credentials, 'same-origin');
  assert.equal(options.cache, 'no-store'); assert.equal(options.headers['Content-Type'], 'application/json');
  assert.equal(browser.sockets[0].url, 'wss://bridge.test:8443/stream');
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  assert.deepEqual(socket.sent[0], {type: 'display', width: 1920, height: 1080, dpr: 1});
  assert.deepEqual(socket.sent[1], {type: 'keyframe'});
  socket.onclose({reason: 'Phone restarted'}); browser.runTimer(500); await browser.settle();
  assert.equal(browser.requests.length, 2); assert.equal(browser.sockets.length, 2);
});
test('session errors reconnect quietly without opening an unauthorized stream', async () => {
  const browser = browserFixture([{ok: false, status: 503, text: async () => 'Phone display is starting'}]);
  await browser.settle();
  assert.equal(browser.sockets.length, 0);
  assert.equal(browser.elements.get('error').textContent, '');
  assert.equal(browser.elements.get('error').hidden, true);
  assert.equal(browser.elements.get('heading').textContent, 'Reconnecting…');
  assert.equal(browser.elements.get('retry').hidden, true);
  assert.equal(browser.elements.get('retry').disabled, false);
  browser.runTimer(500); await browser.settle();
  assert.equal(browser.requests.length, 2); assert.equal(browser.sockets.length, 1);
});
test('failed fetch keeps a single quiet reconnect state throughout automatic backoff', async () => {
  const browser = browserFixture(Array.from({length: 7}, () => new TypeError('Failed to fetch')));
  await browser.settle();
  for (const delay of [500, 1000, 2000, 4000, 8000, 10000]) {
    assert.equal(browser.elements.get('heading').textContent, 'Reconnecting…');
    assert.equal(browser.elements.get('intro').textContent, 'Your display will return automatically.');
    assert.equal(browser.elements.get('activity').hidden, false);
    assert.equal(browser.elements.get('error').hidden, true);
    assert.equal(browser.elements.get('error').textContent, '');
    assert.equal(browser.elements.get('retry').hidden, true);
    browser.runTimer(delay);
    assert.equal(browser.elements.get('heading').textContent, 'Reconnecting…');
    await browser.settle();
  }
  assert.equal(browser.requests.length, 7); assert.equal(browser.sockets.length, 0);
  assert.deepEqual(browser.pendingTimers(), [10000]);
});
test('server notices never overlay a playing picture', async () => {
  const browser = browserFixture(); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1920, height: 1080})});
  browser.drawFrame(browser.decoders[0], {timestamp: 1, close() {}});
  assert.equal(browser.elements.get('panel').hidden, true);
  assert.equal(browser.document.body.classList.contains('playing'), true);
  socket.onmessage({data: JSON.stringify({type: 'error', message: 'DiPlay needs attention'})});
  assert.equal(browser.elements.get('panel').hidden, true);
  assert.equal(browser.elements.get('screen').hidden, false);
  assert.equal(browser.elements.has('message'), false);
  assert.equal(browser.decoders[0].state, 'configured'); assert.equal(socket.readyState, 1);
});
test('optional reporting exceptions never prevent picture drawing or frame acknowledgement', async () => {
  const browser = browserFixture([], true, {}, {telemetry: {event() { throw new Error('Reporting unavailable'); }}});
  await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1920, height: 1080})});
  browser.drawFrame(browser.decoders[0], {timestamp: 1, close() {}});
  assert.equal(browser.elements.get('panel').hidden, true);
  assert.ok(socket.sent.some(message => message.type === 'ack' && message.pts === 1));
  assert.equal(socket.readyState, 1);
});
test('decoder rejection reports the failing codec and encoded resolution without exception text', async () => {
  const events = [];
  const browser = await playingBrowser({telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  browser.decoders[0].callbacks.error({name: 'NotSupportedError', message: 'private browser detail'});
  const error = events.find(event => event.name === 'browser_error');
  assert.equal(error.attributes.reason, 'decoder_unsupported');
  assert.equal(error.attributes.codec, 'avc1.42C01F');
  assert.equal(error.attributes.width, 1920);
  assert.equal(error.attributes.height, 1080);
  assert.equal(JSON.stringify(events).includes('private browser detail'), false);
});
test('session rejection and an unopened socket report distinct bounded failure categories', async () => {
  const events = [], telemetry = {event: (name, attributes) => events.push({name, attributes})};
  const rejected = browserFixture([{ok: false, status: 503, text: async () => 'private phone detail'}], true, {}, {telemetry});
  await rejected.settle();
  assert.ok(events.some(event => event.name === 'browser_error' && event.attributes.reason === 'session_rejected'
    && event.attributes.errorCode === '503'));
  assert.equal(JSON.stringify(events).includes('private phone detail'), false);
  const unopened = browserFixture([], true, {}, {telemetry}); await unopened.settle();
  unopened.runTimer(10000);
  assert.ok(events.some(event => event.name === 'browser_error' && event.attributes.reason === 'socket_open_timeout'));
});
test('a server error is reported even when its notice leaves the picture visible', async () => {
  const events = [];
  const browser = await playingBrowser({telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  browser.sockets[0].onmessage({data: JSON.stringify({type: 'error', message: 'private phone detail'})});
  const error = events.find(event => event.name === 'browser_error');
  assert.equal(error.attributes.reason, 'server_error');
  assert.equal(error.attributes.width, 1920);
  assert.equal(JSON.stringify(events).includes('private phone detail'), false);
  assert.equal(browser.elements.get('panel').hidden, true);
});
test('sparse static video does not flood reports with invented recoveries', async () => {
  const events = [];
  const browser = await playingBrowser({telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  for (let i = 0; i < 12; i++) {
    browser.sockets[0].onmessage({data: JSON.stringify({type: 'pong'})});
    browser.tick(3000);
    browser.drawFrame(browser.decoders[0], {timestamp: i + 2, close() {}});
  }
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 1);
  assert.equal(events.filter(event => event.name === 'recovery_completed').length, 0);
  assert.equal(events.filter(event => event.name === 'browser_error').length, 0);
});
test('a new actual draw resolves a reported playback fault on the same browser', async () => {
  const events = [];
  const browser = await playingBrowser({telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  browser.sockets[0].onerror();
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 1);
  browser.drawFrame(browser.decoders[0], {timestamp: 2, close() {}});
  const frames = events.filter(event => event.name === 'browser_first_frame');
  assert.equal(frames.length, 2);
  assert.equal(frames[1].attributes.source, 'recovery');
  assert.equal(frames[1].attributes.sampleDecoded, true);
  browser.drawFrame(browser.decoders[0], {timestamp: 3, close() {}});
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 2);
});
test('enabling reports waits for a real draw instead of timestamping an old picture as new', async () => {
  const events = [];
  const browser = await playingBrowser({telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  browser.sockets[0].onmessage({data: JSON.stringify({type: 'telemetry_consent', enabled: true})});
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 1);
  browser.drawFrame(browser.decoders[0], {timestamp: 2, close() {}});
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 2);
});
test('playing has no controls or live status announcements and follows browser fullscreen changes', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  const lastStatus = browser.elements.get('status').textContent;
  const notice = {type: 'status', message: 'Direct CarPlay video · 1920×1080'};
  socket.onmessage({data: JSON.stringify(notice)});
  assert.equal(browser.elements.get('status').textContent, lastStatus);
  assert.equal(browser.elements.get('panel').hidden, true);
  for (const id of ['top', 'leave', 'message', 'fullscreen', 'hide', 'reconnect'])
    assert.equal(browser.document.getElementById(id), null);
  browser.elements.get('stage').getBoundingClientRect = () => ({left: 0, top: 0, width: 1920, height: 1200});
  browser.document.dispatch('fullscreenchange'); browser.runTimer(200);
  assert.deepEqual(socket.sent.at(-1), {type: 'display', width: 1920, height: 1200, dpr: 1});
  assert.equal(browser.decoders[0].state, 'configured');
});
test('resize, toolbar and fullscreen changes preserve the stream and fill every viewport edge', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  const canvas = browser.elements.get('screen'), stage = browser.elements.get('stage'), config = decoder.config;
  const changes = [
    [browser.window, 'resize', {left: 40, top: 64, width: 1600, height: 1000}],
    [browser.window.visualViewport, 'resize', {left: 0, top: 0, width: 1600, height: 800}],
    [browser.document, 'fullscreenchange', {left: 0, top: 0, width: 1920, height: 1200}]
  ];
  let pointerId = 0, timestamp = 1;
  for (const [target, event, bounds] of changes) {
    stage.getBoundingClientRect = canvas.getBoundingClientRect = () => bounds;
    target.dispatch(event); browser.runTimer(200);
    assert.deepEqual(socket.sent.at(-1), {type: 'display', width: bounds.width, height: bounds.height, dpr: 1});
    // Keep fresh frames arriving beyond both viewport debounces and the stall watchdog interval.
    for (let elapsed = 0; elapsed < 2500; elapsed += 250) {
      browser.drawFrame(decoder, {timestamp: ++timestamp, close() {}});
      browser.tick(250);
    }
    assert.equal(browser.requests.length, 1); assert.equal(browser.sockets.length, 1);
    assert.equal(browser.sockets[0], socket); assert.equal(socket.readyState, 1);
    assert.equal(browser.decoders.length, 1); assert.equal(browser.decoders[0], decoder);
    assert.equal(decoder.state, 'configured'); assert.equal(decoder.config, config);
    assert.deepEqual([canvas.width, canvas.height], [Math.min(bounds.width, 1920), Math.min(bounds.height, 1080)]);
    assert.deepEqual([config.codedWidth, config.codedHeight], [1920, 1080]);
    assert.equal(browser.elements.get('panel').hidden, true);

    assert.deepEqual(browser.draws.at(-1).slice(1), [0, 0, 1920, 1080, 0, 0, canvas.width, canvas.height]);
    const touchesBefore = socket.sent.filter(message => message.type === 'touch').length;
    const rejectedPointer = ++pointerId;
    canvas.dispatch('pointerdown', {pointerId: rejectedPointer, button: 0,
      clientX: bounds.left - 1, clientY: bounds.top,
      preventDefault() { assert.fail('Touches outside the canvas must be ignored'); }});
    assert.equal(canvas.hasPointerCapture(rejectedPointer), false);
    assert.equal(socket.sent.filter(message => message.type === 'touch').length, touchesBefore);

    for (const [x, y] of [[0, 0], [1, 0], [0, 1], [1, 1], [0.75, 0.25]]) {
      const acceptedPointer = ++pointerId;
      canvas.dispatch('pointerdown', {pointerId: acceptedPointer, button: 0,
        clientX: bounds.left + bounds.width * x, clientY: bounds.top + bounds.height * y,
        preventDefault() {}});
      const touch = socket.sent.at(-1);
      assert.equal(touch.type, 'touch'); assert.equal(touch.action, 'down');
      assert.ok(Math.abs(touch.x - x) < 1e-12); assert.ok(Math.abs(touch.y - y) < 1e-12);
      canvas.dispatch('pointerup', {pointerId: acceptedPointer});
      assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'up'});
    }
  }
});
test('startup notices remain visible before a picture arrives', async () => {
  const browser = browserFixture(); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'status', message: 'Waiting for your iPhone'})});
  assert.equal(browser.elements.get('panel').hidden, false);
  assert.equal(browser.elements.get('intro').textContent, 'Waiting for your iPhone');
});
test('actionable startup errors replace the spinner and clear when startup resumes', async () => {
  const browser = browserFixture(); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'error', message: 'Reconnect your iPhone in DiPlay.'})});
  assert.equal(browser.elements.get('heading').textContent, 'Check your phone');
  assert.equal(browser.elements.get('intro').hidden, true);
  assert.equal(browser.elements.get('error').textContent, 'Reconnect your iPhone in DiPlay.');
  assert.equal(browser.elements.get('error').hidden, false);
  assert.equal(browser.elements.get('activity').hidden, true);
  assert.equal(browser.elements.get('retry').hidden, false);
  socket.onmessage({data: JSON.stringify({type: 'status', message: 'Waiting for your iPhone'})});
  assert.equal(browser.elements.get('error').hidden, true);
  assert.equal(browser.elements.get('activity').hidden, false);
  assert.equal(browser.elements.get('retry').hidden, true);
  assert.equal(browser.sockets.length, 1); assert.equal(socket.readyState, 1);
});
test('missing WebCodecs shows a useful error without repeated network attempts', async () => {
  const browser = browserFixture([], false); await browser.settle();
  assert.equal(browser.requests.length, 0); assert.equal(browser.sockets.length, 0);
  assert.match(browser.elements.get('error').textContent, /video decoder/);
  assert.equal(browser.elements.get('activity').hidden, true);
  assert.equal(browser.elements.get('retry').hidden, false);
  assert.equal(browser.elements.get('retry').disabled, false);
});
async function playingBrowser(options = {}) {
  const browser = browserFixture([], true, {}, options); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1920, height: 1080})});
  browser.drawFrame(browser.decoders[0], {timestamp: 1, close() {}});
  return browser;
}
test('native area changes crop the picture and map touch without restarting video', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  const canvas = browser.elements.get('screen');
  socket.onmessage({data: JSON.stringify({type: 'viewArea', x: 0, y: 0, width: 1440, height: 1080,
    canvasWidth: 1920, canvasHeight: 1080})});
  browser.drawFrame(decoder, {timestamp: 2, close() {}});
  assert.deepEqual(browser.draws.at(-1).slice(1), [0, 0, 1440, 1080, 0, 0, 1440, 1080]);
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 960, clientY: 540, preventDefault() {}});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'down', x: 0.375, y: 0.5});
  socket.onmessage({data: JSON.stringify({type: 'viewArea', x: 0, y: 0, width: 1920, height: 960,
    canvasWidth: 1920, canvasHeight: 1080})});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'cancel'});
  assert.equal(canvas.hasPointerCapture(1), false);
  browser.drawFrame(decoder, {timestamp: 3, close() {}});
  assert.deepEqual(browser.draws.at(-1).slice(1), [0, 0, 1920, 960, 0, 0, 1920, 960]);
  assert.equal(browser.decoders.length, 1); assert.equal(browser.sockets.length, 1);
  assert.equal(browser.elements.get('panel').hidden, true);
});
test('sharper canvas metadata waits for its matching format and keeps native cropping', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  socket.onmessage({data: JSON.stringify({type: 'viewArea', x: 0, y: 0, width: 3840, height: 1920,
    canvasWidth: 3840, canvasHeight: 2160})});
  browser.drawFrame(browser.decoders[0], {timestamp: 2, close() {}});
  assert.deepEqual(browser.draws.at(-1).slice(1), [0, 0, 1920, 1080, 0, 0, 1920, 1080]);
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.640033', width: 3840, height: 2160})});
  browser.drawFrame(browser.decoders[1], {timestamp: 3, close() {}});
  assert.deepEqual(browser.draws.at(-1).slice(1), [0, 0, 3840, 1920, 0, 0, 1920, 1080]);
  assert.equal(browser.sockets.length, 1);
});
test('invalid native metadata cannot replace a valid active area', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  const area = {type: 'viewArea', x: 100, y: 60, width: 1600, height: 900, canvasWidth: 1920, canvasHeight: 1080};
  socket.onmessage({data: JSON.stringify(area)});
  socket.onmessage({data: JSON.stringify({...area, x: 900})});
  browser.drawFrame(browser.decoders[0], {timestamp: 2, close() {}});
  assert.deepEqual(browser.draws.at(-1).slice(1), [100, 60, 1600, 900, 0, 0, 1600, 900]);
});
test('low latency decoding lets the browser choose available hardware or software', async () => {
  const browser = await playingBrowser(), decoder = browser.decoders[0];
  assert.equal(decoder.config.hardwareAcceleration, 'no-preference');
  assert.equal(decoder.config.optimizeForLatency, true);
  browser.tick(2000);
  assert.equal(decoder.state, 'configured');
  assert.equal(decoder.config.hardwareAcceleration, 'no-preference');
  assert.equal(decoder.config.optimizeForLatency, true);
});
function videoPacket(timestamp, key = true, payloadBytes = 1) {
  const packet = new ArrayBuffer(9 + payloadBytes), header = new DataView(packet);
  header.setUint8(0, key ? 1 : 0); header.setBigUint64(1, BigInt(timestamp));
  return packet;
}
test('video timing reports packet-to-output and draw submission automatically without frame content', async () => {
  const browser = await playingBrowser({drawDelayMs: 2}), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(2)}); browser.elapse(3);
  browser.drawFrame(decoder, {timestamp: 2, close() {}}, true);
  socket.onmessage({data: videoPacket(3)}); browser.elapse(7);
  browser.drawFrame(decoder, {timestamp: 3, close() {}}, true);
  browser.tick(1000);
  assert.equal(socket.sent.filter(message => message.event === 'latency').length, 0);
  browser.elapse(3999);
  // Keep the stream active when the five-second diagnostic is due.
  socket.onmessage({data: JSON.stringify({type: 'pong'})});
  browser.drawFrame(decoder, {timestamp: 4, close() {}});
  browser.tick(1);
  const timing = socket.sent.find(message => message.event === 'latency');
  assert.ok(timing);
  assert.deepEqual(Object.keys(timing).sort(), ['type', 'event', 'frameAgeMs', 'packetAgeMs', 'decodeQueue', 'closeCode', 'hidden',
    'samples', 'decodeAvgUs', 'decodeMaxUs', 'drawAvgUs', 'drawMaxUs'].sort());
  assert.equal(timing.samples, 4); assert.equal(timing.decodeAvgUs, 2500); assert.equal(timing.decodeMaxUs, 7000);
  assert.equal(timing.drawAvgUs, 2000); assert.equal(timing.drawMaxUs, 2000);
  assert.equal(browser.elements.get('panel').hidden, true);
  browser.tick(1);
  assert.equal(socket.sent.filter(message => message.event === 'latency').length, 1);
});
test('video timing forgets queued frames and samples when the decoder recovers', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(2)}); browser.elapse(3);
  browser.drawFrame(decoder, {timestamp: 2, close() {}}, true);
  socket.onmessage({data: videoPacket(3)}); browser.elapse(600);
  socket.onmessage({data: JSON.stringify({type: 'recover'})});
  decoder.callbacks.output({timestamp: 3, close() {}});
  socket.onmessage({data: videoPacket(4)}); browser.elapse(2);
  browser.drawFrame(decoder, {timestamp: 4, close() {}}, true);
  browser.elapse(5000);
  socket.onmessage({data: JSON.stringify({type: 'pong'})});
  decoder.callbacks.output({timestamp: 5, close() {}}); browser.tick(0);
  const timing = socket.sent.find(message => message.event === 'latency');
  assert.equal(timing.samples, 1); assert.equal(timing.decodeAvgUs, 2000); assert.equal(timing.decodeMaxUs, 2000);
});
test('short decode bursts keep every reference frame and only a bounded stale backlog recovers', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  decoder.decodeQueueSize = 4;
  for (let timestamp = 2; timestamp <= 10; timestamp++) socket.onmessage({data: videoPacket(timestamp)});
  assert.equal(decoder.chunks.length, 10);
  assert.equal(socket.sent.some(message => message.event === 'decode_backpressure'), false);
  browser.elapse(149);
  socket.onmessage({data: videoPacket(11)});
  assert.equal(decoder.chunks.length, 11);
  browser.elapse(1); socket.onmessage({data: videoPacket(12)});
  assert.equal(decoder.chunks.length, 11);
  assert.equal(socket.sent.filter(message => message.event === 'decode_backpressure').length, 1);
  assert.equal(socket.readyState, 1);
  let closed = 0;
  decoder.callbacks.output({timestamp: 10, close() { closed++; }});
  browser.runAnimationFrame();
  assert.equal(closed, 1);
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 1);
});
test('an unsupported decoder pauses automatic retries until the user retries', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  decoder.callbacks.error({name: 'NotSupportedError'});
  assert.equal(socket.readyState, 3); assert.equal(decoder.state, 'closed');
  assert.equal(browser.elements.get('panel').hidden, false);
  assert.match(browser.elements.get('error').textContent, /Update the browser/);
  assert.equal(browser.elements.get('error').hidden, false);
  assert.equal(browser.elements.get('activity').hidden, true);
  assert.equal(browser.elements.get('retry').hidden, false);
  assert.equal(browser.elements.get('retry').disabled, false);
  browser.tick(60000);
  browser.window.dispatch('online'); browser.document.dispatch('visibilitychange');
  await browser.settle();
  assert.equal(browser.requests.length, 1);
  assert.deepEqual(browser.pendingTimers(), []);
  browser.elements.get('retry').onclick(); await browser.settle();
  assert.equal(browser.requests.length, 2); assert.equal(browser.sockets.length, 2);
});
test('a synchronous unsupported configuration produces the same actionable pause', async () => {
  const browser = browserFixture([], true, {configureError: {name: 'NotSupportedError'}});
  await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1920, height: 1080})});
  assert.equal(browser.decoders[0].state, 'closed'); assert.equal(socket.readyState, 3);
  assert.equal(browser.elements.get('heading').textContent, 'Browser video not supported');
  assert.deepEqual(browser.pendingTimers(), []);
});
test('retired decoder callbacks cannot overwrite the paused error or a newer connection', async () => {
  const browser = await playingBrowser(), previous = browser.decoders[0];
  previous.callbacks.error({name: 'NotSupportedError'});
  const message = browser.elements.get('error').textContent;
  let closed = false;
  previous.callbacks.output({timestamp: 2, close() { closed = true; }});
  previous.callbacks.error({name: 'EncodingError'});
  assert.equal(closed, true); assert.equal(browser.elements.get('panel').hidden, false);
  assert.equal(browser.elements.get('error').textContent, message);
  assert.deepEqual(browser.pendingTimers(), []);
  browser.elements.get('retry').onclick(); await browser.settle();
  const socket = browser.sockets[1]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1600, height: 900})});
  previous.callbacks.error({name: 'NotSupportedError'});
  assert.equal(socket.readyState, 1); assert.equal(browser.decoders[1].state, 'configured');
});
test('transient decoder failures still reconnect automatically', async () => {
  const browser = await playingBrowser();
  browser.decoders[0].callbacks.error({name: 'EncodingError'});
  assert.equal(browser.sockets[0].readyState, 3);
  browser.runTimer(500); await browser.settle();
  assert.equal(browser.requests.length, 2); assert.equal(browser.sockets.length, 2);
});
test('an unresponsive stream reconnects after a bounded heartbeat grace without a close event', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  browser.tick(2000);
  assert.equal(socket.sent.filter(message => message.type === 'keyframe').length, 2);
  assert.equal(socket.readyState, 1);
  browser.tick(1000); assert.equal(socket.readyState, 1);
  browser.tick(5000);
  assert.equal(socket.readyState, 3); assert.equal(socket.onclose, null);
  assert.equal(browser.elements.get('heading').textContent, 'Reconnecting…');
  assert.equal(browser.elements.get('error').hidden, true);
  browser.runTimer(500); await browser.settle();
  assert.equal(browser.requests.length, 2); assert.equal(browser.sockets.length, 2);
});
test('a responsive server can wait for CarPlay without repeatedly closing its browser', async () => {
  const browser = browserFixture(); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  for (let second = 0; second < 40; second++) {
    socket.onmessage({data: JSON.stringify({type: 'pong'})}); browser.tick(1000);
  }
  assert.equal(socket.readyState, 1); assert.equal(browser.requests.length, 1);
  assert.ok(socket.sent.filter(message => message.type === 'keyframe').length > 2);
  assert.equal(socket.sent.filter(message => message.event === 'video_wait').length, 1);
  browser.tick(8000); assert.equal(socket.readyState, 3);
});
test('upstream video pause recovers on the same connection and never shows a stale picture', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  for (let second = 0; second < 20; second++) {
    socket.onmessage({data: JSON.stringify({type: 'pong'})}); browser.tick(1000);
  }
  assert.equal(socket.readyState, 1); assert.equal(browser.requests.length, 1);
  assert.equal(browser.elements.get('screen').hidden, true);
  assert.equal(browser.elements.get('heading').textContent, 'Reconnecting…');
  browser.drawFrame(decoder, {timestamp: 2, close() {}});
  assert.equal(browser.elements.get('screen').hidden, false); assert.equal(browser.elements.get('panel').hidden, true);
  assert.equal(socket.sent.at(-1).event, 'video_resumed');
});
test('server recovery resets the decoder and requests a keyframe without disconnecting', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  browser.tick(1600);
  socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.equal(socket.readyState, 1); assert.equal(browser.decoders.length, 1);
  assert.equal(decoder.state, 'configured'); assert.equal(socket.sent.at(-1).type, 'keyframe');
  assert.equal(socket.sent.some(message => message.event === 'server_recovery'), true);
});
test('a stuck decoder with arriving packets eventually reconnects rather than showing a frozen frame', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  for (let second = 0; second < 10; second++) {
    const packet = new ArrayBuffer(10); new Uint8Array(packet)[0] = 1;
    socket.onmessage({data: packet}); browser.tick(1000);
  }
  assert.equal(socket.readyState, 3);
  assert.equal(socket.sent.some(message => message.event === 'decoder_timeout'), true);
  browser.runTimer(500); await browser.settle(); assert.equal(browser.sockets.length, 2);
});
test('decoder diagnostic messages contain a fixed category rather than the error text', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  browser.decoders[0].callbacks.error({name: 'EncodingError', message: 'private test error'});
  const message = socket.sent.find(value => value.type === 'diagnostic');
  assert.equal(message.event, 'decoder_error');
  assert.equal(JSON.stringify(socket.sent).includes('private test error'), false);
});
test('fresh video cancels stall recovery and a hidden page gets grace on return', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  browser.tick(2000);
  browser.drawFrame(browser.decoders[0], {timestamp: 2, close() {}});
  browser.tick(2000); assert.equal(socket.readyState, 1);
  browser.document.hidden = true; browser.tick(60000); assert.equal(socket.readyState, 1);
  browser.document.hidden = false; browser.document.dispatch('visibilitychange');
  browser.tick(1000); assert.equal(socket.readyState, 1);
  assert.equal(browser.requests.length, 1);
});
test('a hanging WebSocket handshake retries even when close never emits an event', async () => {
  const browser = browserFixture(); await browser.settle();
  browser.runTimer(10000); assert.equal(browser.sockets[0].readyState, 3);
  browser.runTimer(500); await browser.settle(); assert.equal(browser.sockets.length, 2);
});
test('lost capture, context menu and blur clear unfinished gestures so the next tap works', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  let prevented = false;
  function down(id) { canvas.dispatch('pointerdown', {pointerId: id, button: 0, clientX: 100, clientY: 100, preventDefault() {}}); }
  function up(id) { canvas.dispatch('pointerup', {pointerId: id, clientX: 120, clientY: 120}); }
  down(1); canvas.dispatch('lostpointercapture', {pointerId: 1}); up(1);
  assert.equal(canvas.hasPointerCapture(1), false);
  down(2); canvas.dispatch('contextmenu', {preventDefault() { prevented = true; }}); up(2);
  assert.equal(prevented, true); assert.equal(canvas.hasPointerCapture(2), false);
  down(3); browser.window.dispatch('blur'); up(3);
  down(4); up(4);
  assert.equal(socket.sent.filter(message => message.type === 'touch' && message.action === 'up').length, 1);
  assert.equal(socket.sent.filter(message => message.type === 'touch' && message.action === 'cancel').length, 3);
  assert.equal(canvas.hasPointerCapture(4), false);
});
test('reconnection discards a gesture started on the previous stream', async () => {
  const browser = await playingBrowser(), canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 100, clientY: 100, preventDefault() {}});
  browser.tick(8000); assert.equal(canvas.hasPointerCapture(1), false);
  browser.runTimer(500); await browser.settle();
  const socket = browser.sockets[1]; socket.readyState = 1; socket.onopen();
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 120, clientY: 120});
  assert.equal(socket.sent.filter(message => message.type === 'gesture').length, 0);
});

test('touch down and first movement arrive immediately while later movement coalesces independently of animation frames', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 960, clientY: 540, preventDefault() {}});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'down', x: 0.5, y: 0.5});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 1000, clientY: 500});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 1000 / 1920, y: 500 / 1080});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 1440, clientY: 270});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 270});
  assert.equal(socket.sent.filter(x => x.type === 'touch' && x.action === 'move').length, 1);
  assert.equal(browser.pendingAnimationFrames(), 0);
  browser.elapse(8); browser.runTimer(8);
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.5, y: 0.25});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 1440, clientY: 270});
  assert.equal(socket.sent.filter(x => x.type === 'touch' && x.action === 'move').length, 2);
  browser.elapse(8); browser.runTimer(8);
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.75, y: 0.25});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 270});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'up'});
  assert.equal(browser.pendingAnimationFrames(), 0);
});
test('drag release flushes the release coordinate before up without waiting for animation', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 480, clientY: 270});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 540});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 810});
  assert.deepEqual(socket.sent.filter(message => message.type === 'touch'), [
    {type: 'touch', action: 'down', x: 0, y: 0},
    {type: 'touch', action: 'move', x: 0.25, y: 0.25},
    {type: 'touch', action: 'move', x: 0.75, y: 0.75},
    {type: 'touch', action: 'up'}
  ]);
  assert.equal(canvas.hasPointerCapture(1), false);
  browser.runAnimationFrame();
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'up'});
});
test('tap release stays at its down point and an outside drag release keeps its last valid movement', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 100, clientY: 100});
  assert.deepEqual(socket.sent.filter(message => message.type === 'touch').map(message => message.action), ['down', 'up']);
  canvas.dispatch('pointerdown', {pointerId: 2, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointermove', {pointerId: 2, clientX: 480, clientY: 270});
  canvas.dispatch('pointermove', {pointerId: 2, clientX: 960, clientY: 540});
  canvas.dispatch('pointerup', {pointerId: 2, clientX: 2000, clientY: 1200});
  assert.deepEqual(socket.sent.slice(-2), [{type: 'touch', action: 'move', x: 0.5, y: 0.5}, {type: 'touch', action: 'up'}]);
});
test('idle movement scheduling stops and the next movement gets another immediate leading edge', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 480, clientY: 270});
  browser.runAnimationFrame();
  assert.equal(browser.pendingAnimationFrames(), 0);
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 540});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.5, y: 0.5});
  assert.equal(browser.pendingAnimationFrames(), 0);
  assert.equal(browser.pendingTimers().includes(8), false);
});
test('cancelling a coalesced drag prevents pending movement from reaching this or the next gesture', async () => {
  for (const cancel of ['pointercancel', 'lostpointercapture', 'blur', 'resize', 'disconnect']) {
    const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
    canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
    canvas.dispatch('pointermove', {pointerId: 1, clientX: 480, clientY: 270});
    canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 540});
    if (cancel === 'disconnect') socket.onclose({code: 1006});
    else if (cancel === 'blur' || cancel === 'resize') browser.window.dispatch(cancel);
    else canvas.dispatch(cancel, {pointerId: 1});
    assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'cancel'}, cancel);
    assert.equal(browser.pendingAnimationFrames(), 0, cancel);
    assert.equal(browser.pendingTimers().includes(8), false, cancel);
    assert.equal(canvas.hasPointerCapture(1), false, cancel);
    browser.runAnimationFrame();
    canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 810});
    assert.equal(socket.sent.filter(message => message.type === 'touch' && message.action === 'move').length, 1, cancel);
    if (cancel !== 'disconnect') {
      canvas.dispatch('pointerdown', {pointerId: 2, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
      canvas.dispatch('pointerup', {pointerId: 2});
      assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'up'}, cancel);
    }
  }
});
test('browsers without animation callbacks still send the first movement immediately and retain the latest point', async () => {
  const browser = await playingBrowser({animationFrames: false}), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 480, clientY: 270});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.25, y: 0.25});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 540});
  browser.runTimer(8);
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.5, y: 0.5});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 810});
  assert.deepEqual(socket.sent.slice(-2), [{type: 'touch', action: 'move', x: 0.75, y: 0.75}, {type: 'touch', action: 'up'}]);
  assert.equal(browser.pendingTimers().includes(8), false);
});
test('raw pointer bursts retain only the newest point and never wait for a rendered frame', async () => {
  const browser = await playingBrowser({rawInput: true}), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  let layoutReads = 0;
  canvas.getBoundingClientRect = () => { layoutReads++; return {left: 0, top: 0, width: 1920, height: 1080}; };
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  for (let x = 1; x <= 1000; x++) canvas.dispatch('pointerrawupdate', {pointerId: 1, clientX: x, clientY: 540});
  // A corresponding pointermove must not replay a second copy or an older sample.
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 500, clientY: 540});
  assert.equal(socket.sent.filter(message => message.action === 'move').length, 1);
  assert.equal(browser.pendingTimers().filter(delay => delay === 8).length, 1);
  assert.equal(browser.pendingAnimationFrames(), 0);
  assert.equal(layoutReads, 1);
  browser.elapse(8); browser.runTimer(8);
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 1000 / 1920, y: 0.5});
  assert.equal(browser.pendingTimers().includes(8), false);
  canvas.dispatch('pointerrawupdate', {pointerId: 1, clientX: 1200, clientY: 540});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 810});
  assert.deepEqual(socket.sent.slice(-2), [{type: 'touch', action: 'move', x: 0.75, y: 0.75}, {type: 'touch', action: 'up'}]);
  assert.equal(browser.pendingTimers().includes(8), false);
});
test('a delayed input timer yields immediately to fresh input and does not replay an old position', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 480, clientY: 270});
  browser.elapse(3);
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 960, clientY: 540});
  assert.equal(browser.pendingTimers().includes(5), true);
  browser.elapse(50);
  canvas.dispatch('pointermove', {pointerId: 1, clientX: 1440, clientY: 810});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.75, y: 0.75});
  assert.equal(browser.pendingTimers().includes(5), false);
  assert.equal(socket.sent.filter(message => message.action === 'move').length, 2);
});
test('raw movement ignores other pointers and cancellation removes the pending sample', async () => {
  const browser = await playingBrowser({rawInput: true}), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() {}});
  canvas.dispatch('pointerrawupdate', {pointerId: 2, clientX: 960, clientY: 540});
  assert.equal(socket.sent.at(-1).action, 'down');
  canvas.dispatch('pointerrawupdate', {pointerId: 1, clientX: 480, clientY: 270});
  canvas.dispatch('pointerrawupdate', {pointerId: 1, clientX: 960, clientY: 540});
  browser.window.dispatch('resize');
  assert.equal(browser.pendingTimers().includes(8), false);
  assert.equal(socket.sent.at(-1).action, 'cancel');
  canvas.dispatch('pointerrawupdate', {pointerId: 1, clientX: 1440, clientY: 810});
  canvas.dispatch('pointerup', {pointerId: 1, clientX: 1440, clientY: 810});
  assert.equal(socket.sent.at(-1).action, 'cancel');
});
test('a new gesture after resize reads fresh geometry instead of the previous cached bounds', async () => {
  const browser = await playingBrowser({rawInput: true}), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 960, clientY: 540, preventDefault() {}});
  canvas.getBoundingClientRect = () => ({left: 100, top: 50, width: 1000, height: 500});
  browser.window.dispatch('resize');
  canvas.dispatch('pointerdown', {pointerId: 2, button: 0, clientX: 600, clientY: 300, preventDefault() {}});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'down', x: 0.5, y: 0.5});
  canvas.dispatch('pointerrawupdate', {pointerId: 2, clientX: 850, clientY: 425});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'move', x: 0.75, y: 0.75});
});
test('different source aspect ratios keep edge touches available and cancel contacts on resize', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1000, height: 1000})});
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 0, clientY: 540, preventDefault() {}});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'down', x: 0, y: 0.5});
  browser.window.dispatch('resize');
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'cancel'});
});
test('leaving the page releases a live contact before closing the stream', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 960, clientY: 540, preventDefault() {}});
  browser.window.dispatch('pagehide');
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'cancel'});
  assert.equal(socket.readyState, 3);
  assert.equal(browser.decoders[0].state, 'closed');
  browser.decoders[0].callbacks.error({name: 'NotSupportedError'});
  browser.window.dispatch('pageshow', {persisted: true}); await browser.settle();
  assert.equal(browser.sockets.length, 2);
});

test('render backing uses the measured Tesla viewport and physical density without changing encoded dimensions', () => {
  assert.deepEqual(renderViewport(1182, 920, 1, 2880, 2160), {width: 1182, height: 920});
  assert.deepEqual(renderViewport(1182, 920, 2, 2880, 2160), {width: 2364, height: 1840});
  assert.deepEqual(renderViewport(1182, 920, 3, 2880, 2160), {width: 2364, height: 1840});
  assert.deepEqual(renderViewport(1920, 1200, 2, 1440, 1080), {width: 1440, height: 1080});
  const large = renderViewport(8000, 6000, 2, 8192, 8192);
  assert.ok(large.width <= 4096 && large.height <= 4096);
  assert.ok(large.width * large.height <= 8388608 + 4096);
  for (const values of [[0, 920, 1, 2880, 2160], [1182, 920, NaN, 2880, 2160],
      [1182, Infinity, 1, 2880, 2160], [1182, 920, 1, 0, 2160]]) assert.equal(renderViewport(...values), null);
});
test('a decoded burst presents only its latest frame on the next refresh and closes every retained frame', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  const drawnBefore = browser.draws.length, ackBefore = socket.sent.filter(message => message.type === 'ack').length;
  const closed = [];
  for (const timestamp of [2, 3, 4]) {
    socket.onmessage({data: videoPacket(timestamp, false)});
    decoder.callbacks.output({timestamp, close() { closed.push(timestamp); }});
  }
  assert.deepEqual(decoder.chunks.slice(-3).map(chunk => chunk.type), ['delta', 'delta', 'delta']);
  assert.deepEqual(closed, [2, 3]);
  assert.equal(browser.pendingAnimationFrames(), 1);
  assert.equal(browser.draws.length, drawnBefore);
  assert.equal(socket.sent.filter(message => message.type === 'ack').length, ackBefore);
  browser.runAnimationFrame();
  assert.equal(browser.draws.length, drawnBefore + 1);
  assert.equal(browser.draws.at(-1)[0].timestamp, 4);
  assert.deepEqual(closed, [2, 3, 4]);
  assert.deepEqual(socket.sent.filter(message => message.type === 'ack').slice(ackBefore), [{type: 'ack', pts: 4}]);
  assert.equal(browser.pendingAnimationFrames(), 0);
});
test('newest-frame cumulative acknowledgements keep a three-credit stream moving through repeated bursts', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  const outstanding = new Set();
  for (let burst = 0; burst < 20; burst++) {
    for (let offset = 0; offset < 3; offset++) {
      const timestamp = 2 + burst * 3 + offset;
      assert.ok(outstanding.size < 3);
      outstanding.add(timestamp);
      socket.onmessage({data: videoPacket(timestamp, false)});
      decoder.callbacks.output({timestamp, close() {}});
    }
    assert.equal(outstanding.size, 3);
    browser.runAnimationFrame();
    const acknowledgement = socket.sent.filter(message => message.type === 'ack').at(-1);
    for (const timestamp of outstanding) if (timestamp <= acknowledgement.pts) outstanding.delete(timestamp);
    assert.equal(outstanding.size, 0);
  }
  assert.equal(decoder.chunks.length, 61);
  assert.equal(socket.sent.some(message => message.event === 'decode_backpressure'), false);
  assert.equal(browser.draws.length, 21);
  assert.equal(socket.readyState, 1);
});
test('first-frame success and acknowledgements wait for the actual draw, not decoder output', async () => {
  const events = [], browser = browserFixture([], true, {}, {telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 2880, height: 2160})});
  socket.onmessage({data: videoPacket(1)});
  browser.decoders[0].callbacks.output({timestamp: 1, close() {}});
  assert.equal(events.some(event => event.name === 'browser_first_frame'), false);
  assert.equal(socket.sent.some(message => message.type === 'ack'), false);
  assert.equal(browser.elements.get('panel').hidden, false);
  browser.runAnimationFrame();
  assert.equal(events.filter(event => event.name === 'browser_first_frame').length, 1);
  assert.equal(socket.sent.filter(message => message.type === 'ack').length, 1);
  assert.equal(browser.elements.get('panel').hidden, true);
});
test('rAF waiting is excluded from decode and draw timings, while superseded outputs still count as decoded', async () => {
  const events = [], browser = await playingBrowser({drawDelayMs: 2,
    telemetry: {event: (name, attributes) => events.push({name, attributes})}});
  const socket = browser.sockets[0], decoder = browser.decoders[0];
  for (const timestamp of [2, 3, 4]) {
    socket.onmessage({data: videoPacket(timestamp, false)}); browser.elapse(3);
    decoder.callbacks.output({timestamp, close() {}});
  }
  browser.runAnimationFrame(100);
  browser.elapse(4900); socket.onmessage({data: JSON.stringify({type: 'pong'})}); browser.tick(0);
  const timing = socket.sent.find(message => message.event === 'latency');
  assert.equal(timing.samples, 2);
  assert.equal(timing.decodeAvgUs, 2250);
  assert.equal(timing.decodeMaxUs, 3000);
  assert.equal(timing.drawAvgUs, 2000);
  assert.equal(timing.drawMaxUs, 2000);
  browser.elapse(25000); socket.onmessage({data: JSON.stringify({type: 'pong'})}); browser.tick(0);
  const summary = events.find(event => event.name === 'performance_summary').attributes;
  assert.equal(summary.decodedFrames, 4); assert.equal(summary.drawnFrames, 2); assert.equal(summary.droppedFrames, 2);
});
test('a failed deferred draw closes its frame and never emits an ACK or first-frame success', async () => {
  const events = [], options = {drawError: {name: 'OperationError'},
    telemetry: {event: (name, attributes) => events.push({name, attributes})}};
  const browser = browserFixture([], true, {}, options); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1920, height: 1080})});
  socket.onmessage({data: videoPacket(1)});
  let closed = 0;
  browser.decoders[0].callbacks.output({timestamp: 1, close() { closed++; }});
  assert.doesNotThrow(() => browser.runAnimationFrame());
  assert.equal(closed, 1); assert.equal(socket.readyState, 3);
  assert.equal(socket.sent.some(message => message.type === 'ack'), false);
  assert.equal(events.some(event => event.name === 'browser_first_frame'), false);
  assert.ok(events.some(event => event.name === 'browser_error' && event.attributes.reason === 'decoder_error'));
  assert.equal(browser.pendingAnimationFrames(), 0);
});
test('resizing waits for a new frame before changing backing storage and preserves encoded crop and touch coordinates', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], canvas = browser.elements.get('screen');
  const bounds = {left: 20, top: 30, width: 1182, height: 920};
  browser.elements.get('stage').getBoundingClientRect = canvas.getBoundingClientRect = () => bounds;
  browser.window.devicePixelRatio = 2;
  browser.window.dispatch('resize'); browser.runTimer(200);
  assert.deepEqual([canvas.width, canvas.height], [1920, 1080]);
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.640033', width: 2880, height: 2160})});
  socket.onmessage({data: JSON.stringify({type: 'viewArea', x: 80, y: 100, width: 2800, height: 2000,
    canvasWidth: 2880, canvasHeight: 2160})});
  const decoder = browser.decoders[1];
  socket.onmessage({data: videoPacket(2)}); decoder.callbacks.output({timestamp: 2, close() {}});
  assert.deepEqual([canvas.width, canvas.height], [1920, 1080]);
  browser.runAnimationFrame();
  assert.deepEqual([canvas.width, canvas.height], [2364, 1840]);
  assert.deepEqual([decoder.config.codedWidth, decoder.config.codedHeight], [2880, 2160]);
  assert.deepEqual(browser.draws.at(-1).slice(1), [80, 100, 2800, 2000, 0, 0, 2364, 1840]);
  canvas.dispatch('pointerdown', {pointerId: 1, button: 0, clientX: 20 + 1182 / 2, clientY: 30 + 920 / 2, preventDefault() {}});
  assert.deepEqual(socket.sent.at(-1), {type: 'touch', action: 'down', x: 1480 / 2880, y: 1100 / 2160});
  assert.equal(browser.sockets.length, 1); assert.equal(browser.requests.length, 1);
});
test('pending frames are closed and stale refresh callbacks cannot draw or ACK after reset, format or leaving', async () => {
  for (const transition of ['recover', 'format', 'disconnect', 'hidden', 'pagehide']) {
    const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
    socket.onmessage({data: videoPacket(2)});
    let closed = 0;
    decoder.callbacks.output({timestamp: 2, close() { closed++; }});
    const [staleCallback] = browser.animationCallbacks(), draws = browser.draws.length;
    const acknowledgements = socket.sent.filter(message => message.type === 'ack').length;
    if (transition === 'recover') socket.onmessage({data: JSON.stringify({type: 'recover'})});
    else if (transition === 'format') socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.42C01F', width: 1600, height: 900})});
    else if (transition === 'disconnect') socket.onclose({code: 1006});
    else if (transition === 'hidden') { browser.document.hidden = true; browser.document.dispatch('visibilitychange'); }
    else browser.window.dispatch('pagehide');
    assert.equal(closed, 1, transition); assert.equal(browser.pendingAnimationFrames(), 0, transition);
    staleCallback();
    assert.equal(browser.draws.length, draws, transition);
    assert.equal(socket.sent.filter(message => message.type === 'ack').length, acknowledgements, transition);
    if (transition === 'recover' || transition === 'format') {
      const current = browser.decoders.at(-1);
      socket.onmessage({data: videoPacket(3)}); current.callbacks.output({timestamp: 3, close() {}});
      staleCallback();
      assert.equal(browser.pendingAnimationFrames(), 1, transition);
      browser.runAnimationFrame();
      assert.equal(browser.draws.at(-1)[0].timestamp, 3, transition);
      assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 3, transition);
    }
  }
});
test('a decoder with no output gets one bounded progress deadline even when server credits stop new packets', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(2, false)});
  browser.elapse(50); socket.onmessage({data: videoPacket(3, false)});
  assert.equal(browser.pendingTimers().filter(delay => delay <= 150).length, 1);
  browser.elapse(100); browser.runTimer(100);
  assert.equal(socket.sent.filter(message => message.event === 'decode_backpressure').length, 1);
  assert.equal(socket.sent.at(-1).type, 'keyframe'); assert.equal(socket.readyState, 1);
  socket.onmessage({data: videoPacket(4, false)});
  assert.equal(decoder.chunks.length, 3);
  socket.onmessage({data: videoPacket(5)}); decoder.callbacks.output({timestamp: 5, close() {}});
  assert.equal(browser.pendingTimers().some(delay => delay <= 1000), false);
  browser.runAnimationFrame();
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 5);
});
test('decode request and compressed byte ceilings reset instead of dropping a reference and continuing a broken chain', async () => {
  for (const limit of ['requests', 'bytes']) {
    const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
    if (limit === 'requests') for (let timestamp = 2; timestamp <= 14; timestamp++) socket.onmessage({data: videoPacket(timestamp, false)});
    else {
      socket.onmessage({data: videoPacket(2, false, 4 * 1024 * 1024)});
      socket.onmessage({data: videoPacket(3, false, 4 * 1024 * 1024)});
      socket.onmessage({data: videoPacket(4, false)});
    }
    assert.equal(socket.sent.filter(message => message.event === 'decode_backpressure').length, 1, limit);
    const count = decoder.chunks.length;
    socket.onmessage({data: videoPacket(20, false)});
    assert.equal(decoder.chunks.length, count, limit);
    socket.onmessage({data: videoPacket(21)}); decoder.callbacks.output({timestamp: 21, close() {}});
    browser.runAnimationFrame();
    assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 21, limit);
  }
});
test('hidden pages discard decoded pictures and do not decode again until a visible recovery keyframe', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(2, false)}); decoder.callbacks.output({timestamp: 2, close() {}});
  browser.document.hidden = true; browser.document.dispatch('visibilitychange');
  const decoded = decoder.chunks.length;
  socket.onmessage({data: videoPacket(3)});
  assert.equal(decoder.chunks.length, decoded);
  browser.document.hidden = false; browser.document.dispatch('visibilitychange');
  socket.onmessage({data: videoPacket(4, false)});
  assert.equal(decoder.chunks.length, decoded);
  socket.onmessage({data: videoPacket(5)}); decoder.callbacks.output({timestamp: 5, close() {}});
  browser.runAnimationFrame();
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 5);
});
test('a browser without refresh callbacks retains immediate rendering and post-draw acknowledgements', async () => {
  const browser = await playingBrowser({animationFrames: false}), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(2, false)});
  decoder.callbacks.output({timestamp: 2, close() {}});
  assert.equal(browser.draws.at(-1)[0].timestamp, 2);
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 2);
  assert.equal(browser.pendingAnimationFrames(), 0);
});


test('a newly configured decoder gets a bounded cold-start allowance before the active-frame deadline applies', async () => {
  const browser = browserFixture(); await browser.settle();
  const socket = browser.sockets[0]; socket.readyState = 1; socket.onopen();
  socket.onmessage({data: JSON.stringify({type: 'format', codec: 'avc1.640033', width: 2880, height: 2160})});
  const decoder = browser.decoders[0];
  socket.onmessage({data: videoPacket(1)});
  assert.ok(browser.pendingTimers().includes(1000));
  browser.elapse(350); decoder.callbacks.output({timestamp: 1, close() {}}); browser.runAnimationFrame();
  assert.equal(socket.sent.some(message => message.event === 'decode_backpressure'), false);
  socket.onmessage({data: videoPacket(2, false)});
  assert.ok(browser.pendingTimers().includes(150));
  browser.elapse(150); browser.runTimer(150);
  assert.equal(socket.sent.filter(message => message.event === 'decode_backpressure').length, 1);
  socket.onmessage({data: videoPacket(3)});
  assert.ok(browser.pendingTimers().includes(1000));
  browser.elapse(1000); browser.runTimer(1000);
  assert.equal(socket.sent.filter(message => message.event === 'decode_backpressure').length, 2);
  assert.equal(socket.readyState, 1);
});


test('early recovery always requests a keyframe and repeated resets schedule a bounded coalesced request', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  const initialRequests = socket.sent.filter(message => message.type === 'keyframe').length;
  browser.elapse(100); socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.equal(socket.sent.filter(message => message.type === 'keyframe').length, initialRequests + 1);
  browser.elapse(100); socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.ok(browser.pendingTimers().includes(400));
  browser.elapse(100); socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.ok(browser.pendingTimers().includes(300));
  assert.equal(browser.pendingTimers().filter(delay => delay <= 500).length, 1);
  browser.elapse(300); browser.runTimer(300);
  assert.equal(socket.sent.filter(message => message.type === 'keyframe').length, initialRequests + 2);
  socket.onmessage({data: videoPacket(2)}); decoder.callbacks.output({timestamp: 2, close() {}});
  browser.runAnimationFrame();
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 2);
});
test('closing the decoder cancels a throttled keyframe request from its old stream', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0];
  socket.onmessage({data: JSON.stringify({type: 'recover'})});
  browser.elapse(100); socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.ok(browser.pendingTimers().includes(400));
  socket.onclose({code: 1006});
  assert.equal(browser.pendingTimers().includes(400), false);
});


test('a newly drawn recovery frame cancels the deferred keyframe request instead of disturbing healthy video', async () => {
  const browser = await playingBrowser(), socket = browser.sockets[0], decoder = browser.decoders[0];
  socket.onmessage({data: JSON.stringify({type: 'recover'})});
  browser.elapse(100); socket.onmessage({data: JSON.stringify({type: 'recover'})});
  assert.ok(browser.pendingTimers().includes(400));
  const requests = socket.sent.filter(message => message.type === 'keyframe').length;
  browser.elapse(100); socket.onmessage({data: videoPacket(2)});
  decoder.callbacks.output({timestamp: 2, close() {}});
  assert.ok(browser.pendingTimers().includes(400));
  browser.runAnimationFrame();
  assert.equal(browser.pendingTimers().includes(400), false);
  browser.elapse(500);
  assert.equal(socket.sent.filter(message => message.type === 'keyframe').length, requests);
  assert.equal(socket.sent.filter(message => message.type === 'ack').at(-1).pts, 2);
});
