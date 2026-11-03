/* Draws decoded CarPlay frames away from the page's main thread.
 * The player transfers an OffscreenCanvas here and posts one VideoFrame per draw.
 * No transport, touch or DOM work happens in this worker.
 */
'use strict';

let canvas = null, context = null, epoch = 0, sampled = false;

self.onmessage = event => {
  const message = event.data;
  if (!message || typeof message !== 'object') return;
  if (message.type === 'canvas') {
    canvas = message.canvas;
    context = canvas && canvas.getContext('2d', { alpha: false, desynchronized: true });
    if (!context) { self.postMessage({ type: 'error', name: 'NoCanvasContext' }); return; }
    self.postMessage({ type: 'ready' });
    return;
  }
  if (message.type === 'epoch') {
    epoch = Math.max(epoch, message.epoch | 0);
    return;
  }
  if (message.type !== 'draw') return;
  const frame = message.frame;
  if (!context || !frame) { if (frame) frame.close(); self.postMessage({ type: 'error', name: 'NoCanvasContext' }); return; }
  // A draw posted before a decoder reset must not overwrite the post-recovery picture.
  if ((message.epoch | 0) < epoch) {
    frame.close();
    self.postMessage({ type: 'dropped', epoch: message.epoch | 0 });
    return;
  }
  let drawMs = 0, presentWaitMs = 0, sample = null;
  try {
    const width = message.width | 0, height = message.height | 0;
    if (width > 0 && canvas.width !== width) canvas.width = width;
    if (height > 0 && canvas.height !== height) canvas.height = height;
    const area = message.area;
    const started = performance.now();
    context.drawImage(frame, area.x, area.y, area.width, area.height, 0, 0, canvas.width, canvas.height);
    drawMs = Math.max(0, performance.now() - started);
    presentWaitMs = Math.max(0, started - (message.outputAt || started));
    if (message.probe === true && !sampled && canvas.width > 0 && canvas.height > 0) {
      // A desynchronized canvas may not read back reliably; prove the frame on a tiny copy.
      sampled = true;
      try {
        const probeCanvas = new OffscreenCanvas(Math.min(64, canvas.width), Math.min(64, canvas.height));
        const probeContext = probeCanvas.getContext('2d');
        probeContext.drawImage(frame, area.x, area.y, area.width, area.height,
          0, 0, probeCanvas.width, probeCanvas.height);
        const pixels = probeContext.getImageData(0, 0, probeCanvas.width, probeCanvas.height).data;
        sample = false;
        for (let index = 0; index < pixels.length; index += 4) {
          if (pixels[index] > 16 || pixels[index + 1] > 16 || pixels[index + 2] > 16) { sample = true; break; }
        }
      } catch (_) { sample = null; }
    }
  } catch (error) {
    self.postMessage({ type: 'error', name: (error && error.name) || 'Error' });
    return;
  } finally {
    frame.close();
  }
  self.postMessage({ type: 'presented', pts: message.pts, epoch: message.epoch | 0,
    drawMs, presentWaitMs, sample, width: canvas.width, height: canvas.height });
};
