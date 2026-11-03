// Regenerate with: node tests/browser-integration/generate-h264-fixture.cjs
// ffmpeg/libx264 is needed only to regenerate this synthetic, non-personal fixture.
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {writeFileSync} = require('node:fs');
const path = require('node:path');

const args = ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=160x96:rate=30',
  '-frames:v', '12', '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
  '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
  '-x264-params', 'keyint=12:min-keyint=12:scenecut=0:bframes=0:repeat-headers=1:aud=1',
  '-f', 'h264', 'pipe:1'];
const result = spawnSync('ffmpeg', args, {maxBuffer: 4 * 1024 * 1024});
assert.equal(result.status, 0, 'ffmpeg with libx264 is required to regenerate the fixture');
const bytes = result.stdout, starts = [];
for (let offset = 0; offset + 3 < bytes.length; offset++) {
  if (bytes[offset] !== 0 || bytes[offset + 1] !== 0) continue;
  const prefix = bytes[offset + 2] === 1 ? 3 : bytes[offset + 2] === 0 && bytes[offset + 3] === 1 ? 4 : 0;
  if (prefix) { starts.push({offset, prefix}); offset += prefix - 1; }
}
const frames = [];
let parts = [], codec;
for (let index = 0; index < starts.length; index++) {
  const {offset, prefix} = starts[index], end = starts[index + 1]?.offset ?? bytes.length;
  const type = bytes[offset + prefix] & 31;
  if (type === 9 && parts.length) { frames.push(Buffer.concat(parts)); parts = []; }
  if (type === 7) codec = 'avc1.' + bytes.subarray(offset + prefix + 1, offset + prefix + 4).toString('hex');
  parts.push(bytes.subarray(offset, end));
}
if (parts.length) frames.push(Buffer.concat(parts));
assert.equal(frames.length, 12);
assert.ok(codec);
writeFileSync(path.join(__dirname, 'h264-fixture.json'), JSON.stringify({
  description: 'Synthetic ffmpeg testsrc2; no captured device or CarPlay content.',
  generator: ['ffmpeg', ...args], codec, width: 160, height: 96, fps: 30,
  frames: frames.map((frame, index) => ({key: index === 0, base64: frame.toString('base64')})),
}, null, 2) + '\n');
