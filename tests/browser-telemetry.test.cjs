const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {browserInfo} = require('../common/src/main/assets/browser/telemetry.js');

function fixture() {
  const requests = [];
  let now = 0;
  class XMLHttpRequest {
    open(method, url) { this.method = method; this.url = url; requests.push(this); }
    setRequestHeader() {}
    send(body) { this.body = body; }
  }
  const window = {document: {}, XMLHttpRequest, navigator: {userAgent: 'Tesla Chrome/129.0 device-secret'},
    isSecureContext: true, innerWidth: 1182, innerHeight: 920};
  const Clock = class extends Date { static now() { return now; } };
  vm.runInNewContext(fs.readFileSync(require.resolve('../common/src/main/assets/browser/telemetry.js'), 'utf8'),
    {window, Date: Clock, Math, JSON});
  return {window, requests, enable(value) {
    requests[0].status = 200; requests[0].responseText = JSON.stringify({enabled: value}); requests[0].onload();
  }, advance(ms) { now += ms; }};
}

test('browser description keeps only a coarse family and major version', () => {
  assert.deepEqual(browserInfo('Tesla Chrome/129.0 private-user-data'), {browserFamily: 'tesla', browserVersion: '129'});
  assert.deepEqual(browserInfo('Version/18.3 Mobile Safari'), {browserFamily: 'safari', browserVersion: '18'});
});
test('no diagnostic payload leaves the browser while reporting is disabled', () => {
  const f = fixture();
  f.window.TesAirTelemetry.event('browser_error', {reason: 'webcodecs_unavailable'});
  f.enable(false);
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].url, '/telemetry');
});
test('unsupported browsers report capabilities without opening a video socket', () => {
  const f = fixture();
  f.window.TesAirTelemetry.event('browser_error', {reason: 'webcodecs_unavailable'});
  f.enable(true);
  const bodies = f.requests.slice(1).map(r => JSON.parse(r.body));
  assert.equal(bodies[0].name, 'browser_page_loaded');
  assert.equal(bodies[0].attributes.webCodecs, false);
  assert.equal(bodies[0].attributes.viewportWidth, 1182);
  assert.equal(bodies[2].attributes.reason, 'webcodecs_unavailable');
  assert.ok(bodies.every(b => b.attributes.displayPath === 'tesair_direct_browser'));
  assert.ok(bodies.every(b => !JSON.stringify(b).includes('device-secret')));
});
test('browser reporting stays bounded and resumes in a later minute', () => {
  const f = fixture(); f.enable(true);
  for (let i = 0; i < 100; i++) f.window.TesAirTelemetry.event('browser_error', {reason: 'decoder_error'});
  assert.equal(f.requests.length, 21);
  f.advance(60000);
  f.window.TesAirTelemetry.event('browser_first_frame', {sampleDecoded: true});
  assert.equal(f.requests.length, 22);
});
