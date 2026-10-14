(function (root) {
  'use strict';
  function browserInfo(ua) {
    var match = /(?:Chrome|Chromium)\/(\d+)/.exec(ua || '');
    var safari = /Version\/(\d+).*Safari/.exec(ua || '');
    return {browserFamily: /Tesla/i.test(ua || '') ? 'tesla' : match ? 'chromium' : safari ? 'safari' : 'other',
      browserVersion: match ? match[1] : safari ? safari[1] : 'unknown'};
  }
  if (typeof module !== 'undefined') module.exports = {browserInfo: browserInfo};
  if (!root.document || !root.XMLHttpRequest) return;
  var enabled = false, known = false, checking = false, queue = [], sent = 0, windowStartedAt = Date.now();
  var info = browserInfo(root.navigator && root.navigator.userAgent);
  var session = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    var n = Math.floor(Math.random() * 16);
    return (c === 'x' ? n : (n & 3) | 8).toString(16);
  });
  function send(name, attributes) {
    try {
    if (!enabled) return;
    if (Date.now() - windowStartedAt >= 60000) { sent = 0; windowStartedAt = Date.now(); }
    if (sent >= 20) return;
    sent++;
    attributes = attributes || {};
    attributes.browserFamily = info.browserFamily;
    attributes.browserVersion = info.browserVersion;
    attributes.viewportWidth = Math.max(1, Math.min(8192, root.innerWidth || 1));
    attributes.viewportHeight = Math.max(1, Math.min(8192, root.innerHeight || 1));
    attributes.browserSessionId = session;
    attributes.displayPath = 'tesair_direct_browser';
    var body = JSON.stringify({name: name, attributes: attributes});
    if (body.length > 4096) return;
    var request = new root.XMLHttpRequest();
    request.open('POST', '/diagnostics', true);
    request.timeout = 3000;
    request.setRequestHeader('Content-Type', 'application/json');
    request.send(body);
    } catch (_) { /* Reporting is independent of playback. */ }
  }
  root.TesAirTelemetry = {
    event: function (name, attributes) {
      if (enabled) send(name, attributes);
      else if (!known && queue.length < 8) queue.push([name, attributes]);
    },
    refresh: refresh
  };
  function refresh() {
  if (checking) return;
  checking = true;
  known = false;
  try {
  var consent = new root.XMLHttpRequest();
  consent.open('GET', '/telemetry', true);
  consent.timeout = 3000;
  consent.onload = function () {
    checking = false;
    var previous = enabled;
    try { enabled = consent.status === 200 && JSON.parse(consent.responseText).enabled === true; }
    catch (_) { enabled = false; }
    known = true;
    if (!enabled) { queue = []; return; }
    info.secureContext = root.isSecureContext === true;
    info.webCodecs = typeof root.VideoDecoder === 'function';
    info.viewportWidth = Math.max(1, Math.min(8192, root.innerWidth || 1));
    info.viewportHeight = Math.max(1, Math.min(8192, root.innerHeight || 1));
    if (!previous) {
      send('browser_page_loaded', info);
      send('browser_capabilities', {secureContext: info.secureContext, webCodecs: info.webCodecs});
    }
    for (var i = 0; i < queue.length; i++) send(queue[i][0], queue[i][1]);
    queue = [];
  };
  consent.onerror = consent.ontimeout = function () { checking = false; enabled = false; queue = []; };
  consent.send();
  } catch (_) { checking = false; enabled = false; queue = []; }
  }
  refresh();
}(typeof window !== 'undefined' ? window : this));
