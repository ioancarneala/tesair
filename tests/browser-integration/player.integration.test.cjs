const test = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync, mkdtempSync, rmSync, chmodSync} = require('node:fs');
const {tmpdir} = require('node:os');
const path = require('node:path');
const https = require('node:https');
const {once} = require('node:events');
const {spawnSync} = require('node:child_process');
const {chromium} = require('playwright');
const {WebSocketServer, WebSocket} = require('ws');
const fixture = require('./h264-fixture.json');
const assets = path.resolve(__dirname, '../../common/src/main/assets/browser');

async function waitFor(predicate, message, timeout = 8_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.fail(message);
}

async function startServer() {
  const directory = mkdtempSync(path.join(tmpdir(), 'tesair-browser-test-'));
  chmodSync(directory, 0o700);
  const key = path.join(directory, 'key.pem'), certificate = path.join(directory, 'certificate.pem');
  // This identity exists only for a loopback test and is removed during teardown.
  const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', key, '-out', certificate],
  {stdio: 'ignore'});
  if (generated.status !== 0) { rmSync(directory, {recursive: true, force: true}); throw new Error('OpenSSL is required for loopback test TLS'); }
  chmodSync(key, 0o600);
  const stats = {connections: [], errors: []};
  const server = https.createServer({key: readFileSync(key), cert: readFileSync(certificate)}, (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    const requestPath = request.url.split('?')[0];
    if (requestPath === '/session' && request.method === 'POST') {
      response.writeHead(200, {'Content-Type': 'application/json'}); response.end('{}'); return;
    }
    if (requestPath === '/telemetry') {
      response.writeHead(200, {'Content-Type': 'application/json'}); response.end('{"enabled":false}'); return;
    }
    const file = {'/': 'index.html', '/player.js': 'player.js', '/player-worker.js': 'player-worker.js',
      '/telemetry.js': 'telemetry.js'}[requestPath];
    if (!file) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, {'Content-Type': file.endsWith('.js') ? 'text/javascript' : 'text/html'});
    response.end(readFileSync(path.join(assets, file)));
  });
  const sockets = new WebSocketServer({noServer: true, maxPayload: 8192});
  server.on('upgrade', (request, socket, head) => {
    if (request.url !== '/stream' || request.headers.origin !== `https://${request.headers.host}`) { socket.destroy(); return; }
    sockets.handleUpgrade(request, socket, head, ws => sockets.emit('connection', ws));
  });
  let timestamp = 0;
  const packets = fixture.frames.map(frame => ({key: frame.key, data: Buffer.from(frame.base64, 'base64')}));
  sockets.on('connection', ws => {
    if (stats.connections.some(client => !client.closed)) { ws.close(1008, 'One browser is already connected.'); return; }
    const client = {ws, acknowledgements: [], presented: [], probe: null, controls: [], keyframeRequests: [], outstanding: new Set(), index: 0, closed: false};
    stats.connections.push(client);
    ws.send(JSON.stringify({type: 'format', codec: fixture.codec, width: fixture.width,
      height: fixture.height, fpsRequested: fixture.fps, generation: stats.connections.length}));
    const timer = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN || client.outstanding.size >= 3) return;
      const frame = packets[client.index++ % packets.length];
      const packet = Buffer.alloc(9 + frame.data.length);
      packet[0] = frame.key ? 1 : 0;
      timestamp += Math.round(1_000_000 / fixture.fps);
      packet.writeBigUInt64BE(BigInt(timestamp), 1); frame.data.copy(packet, 9);
      client.outstanding.add(timestamp); ws.send(packet);
    }, 1000 / fixture.fps);
    ws.on('message', bytes => {
      const message = JSON.parse(bytes.toString());
      if (message.type === 'ack' && client.outstanding.has(message.pts)) {
        client.acknowledgements.push(message.pts);
        for (const pts of client.outstanding) if (pts <= message.pts) client.outstanding.delete(pts);
      } else if (message.type === 'presented') {
        client.presented.push(message);
      } else if (message.type === 'draw_probe') {
        client.probe = message;
      } else if (message.type === 'keyframe') {
        client.keyframeRequests.push(message);
        client.index = 0;
        if (message.reset !== false) client.outstanding.clear();
      } else if (message.type === 'ping') ws.send('{"type":"pong"}');
      else if (message.type === 'touch' || message.type === 'display') client.controls.push(message);
    });
    ws.on('error', error => stats.errors.push(error.name));
    ws.on('close', () => { client.closed = true; clearInterval(timer); });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  return {url: `https://127.0.0.1:${server.address().port}`, stats,
    async close() {
      for (const ws of sockets.clients) { ws._socket.resume(); ws.terminate(); }
      await new Promise(resolve => sockets.close(resolve));
      await new Promise(resolve => server.close(resolve));
      rmSync(directory, {recursive: true, force: true});
    }};
}

test('real Chromium decodes H.264 over WSS through scheduling pauses, pressure and recovery', {timeout: 45_000}, async t => {
  const server = await startServer();
  t.after(() => server.close());
  const browser = await chromium.launch({headless: true});
  t.after(() => browser.close());
  const context = await browser.newContext({ignoreHTTPSErrors: true, viewport: {width: 1182, height: 920}});
  // Only this temporary loopback server can receive page requests or test data.
  await context.route('**/*', route => new URL(route.request().url()).origin === server.url ? route.continue() : route.abort());
  await context.addInitScript(() => {
    window.__sockets = []; window.__decoderResets = 0;
    const NativeWebSocket = WebSocket;
    window.WebSocket = class extends NativeWebSocket {
      constructor(...args) { super(...args); window.__sockets.push(this); }
    };
    const reset = VideoDecoder.prototype.reset;
    VideoDecoder.prototype.reset = function(...args) { window.__decoderResets++; return reset.apply(this, args); };
  });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(server.url + '/?probe=1');
  await waitFor(() => server.stats.connections[0]?.acknowledgements.length >= 20, 'Real H.264 did not draw and ACK');
  await waitFor(() => server.stats.connections[0]?.presented.length >= 10, 'The draw worker did not present frames');
  const first = server.stats.connections[0];
  assert.ok(first.keyframeRequests.length > 0);
  assert.ok(first.keyframeRequests.every(message => message.reset === false), 'Startup must not discard in-flight IDRs or credits');
  assert.equal(await page.locator('#panel').isVisible(), false);
  // The canvas is transferred to the draw worker, so the worker reports its own pixel check.
  const probe = server.stats.connections[0].probe;
  assert.equal(probe.sample, true, 'OffscreenCanvas painted no visible pixel');
  assert.deepEqual({width: probe.width, height: probe.height}, {width: fixture.width, height: fixture.height});

  const beforePause = first.acknowledgements.length;
  await page.evaluate(() => { const deadline = performance.now() + 250; while (performance.now() < deadline) {} });
  await waitFor(() => first.acknowledgements.length >= beforePause + 10, 'Playback did not resume after a 250 ms scheduling pause');
  assert.equal(await page.evaluate(() => window.__decoderResets), 0);
  assert.equal(server.stats.connections.length, 1);

  await page.setViewportSize({width: 920, height: 700});
  await waitFor(() => first.controls.some(message => message.type === 'display' && message.width === 920), 'Resize was not delivered');
  assert.equal(server.stats.connections.length, 1);
  await page.mouse.move(100, 100); await page.mouse.down();
  await waitFor(() => first.controls.some(message => message.type === 'touch' && message.action === 'down'), 'Touch down did not reach the real socket');

  // Pause the real TCP reader, fill the native send buffer, then release in the same
  // browser task. No mocked bufferedAmount or replacement transport is used.
  first.ws._socket.pause();
  const pressure = await page.evaluate(() => {
    const socket = window.__sockets.at(-1);
    const message = JSON.stringify({type: 'diagnostic', event: 'latency', padding: 'x'.repeat(4096)});
    let count = 0;
    while (socket.bufferedAmount < 128 * 1024 && count++ < 2048) socket.send(message);
    const buffered = socket.bufferedAmount;
    document.getElementById('screen').dispatchEvent(new PointerEvent('pointerup', {
      pointerId: 1, clientX: 100, clientY: 100, button: 0, bubbles: true
    }));
    return buffered;
  });
  assert.ok(pressure >= 16 * 1024, 'Native WebSocket did not become congested');
  first.ws._socket.resume();
  await waitFor(() => first.closed, 'Congested gesture connection was not retired');
  await waitFor(() => server.stats.connections[1]?.acknowledgements.length >= 10, 'Video did not recover on a fresh connection');
  const next = server.stats.connections[1];
  await page.mouse.up();
  const beforeOrphanRelease = next.acknowledgements.length;
  await waitFor(() => next.acknowledgements.length > beforeOrphanRelease, 'Fresh stream stopped after the old pointer release');
  assert.equal(next.controls.some(message => message.type === 'touch'), false, 'An old gesture leaked into the new connection');
  assert.equal(await page.locator('#panel').isVisible(), false);

  const beforeRecovery = next.acknowledgements.length;
  const requestsBeforeRecovery = next.keyframeRequests.length;
  next.outstanding.clear(); next.index = 0; next.ws.send('{"type":"recover"}');
  await waitFor(() => next.acknowledgements.length >= beforeRecovery + 10, 'Explicit video recovery did not draw a fresh IDR');
  assert.ok(next.keyframeRequests.length > requestsBeforeRecovery);
  assert.ok(next.keyframeRequests.slice(requestsBeforeRecovery).every(message => message.reset === false),
    'Server recovery must not reset the new server credit window again');
  assert.equal(await page.evaluate(() => window.__decoderResets), 1);
  assert.equal(server.stats.connections.length, 2);
  assert.deepEqual(errors, []);
  assert.deepEqual(server.stats.errors, []);
  t.diagnostic(`Actual H.264 draw ACKs: ${first.acknowledgements.length + next.acknowledgements.length}; ` +
    `250 ms pause: no decoder resets; native congestion: ${pressure} buffered bytes; one clean reconnect.`);
});
