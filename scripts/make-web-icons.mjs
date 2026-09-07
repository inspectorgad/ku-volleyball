// Generates the dashboard's home-screen icons into docs/.
//
//   node scripts/make-web-icons.mjs [docsDir]
//
// The mark comes from icon-art.mjs, the same geometry as the Android launcher.
// Rasterizing goes through the Chromium binary's --screenshot rather than
// playwright, so this runs anywhere Chromium is on disk and needs no npm
// install; set CHROME to override the path.
import fs from 'fs';
import path from 'path';
import os from 'os';
import { execFileSync } from 'child_process';
import { svgIcon, KU_BLUE } from './icon-art.mjs';

const DOCS = process.argv[2] || new URL('../docs', import.meta.url).pathname;
const CHROME = process.env.CHROME
  || ['/opt/pw-browsers/chromium/chrome-linux/chrome',
      '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
      '/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => fs.existsSync(p));
if (!CHROME) throw new Error('no Chromium found; set CHROME to its path');

// 180 is what iOS asks for via apple-touch-icon; 192 and 512 are the manifest
// sizes Chrome wants; the maskable 512 is the one a launcher may crop.
const TARGETS = [
  ['icon-180.png', 'full', 180],
  ['icon-192.png', 'full', 192],
  ['icon-512.png', 'full', 512],
  ['icon-maskable-512.png', 'maskable', 512],
];

// The SVG is checked in too: it is what a browser tab uses, and it is the
// legible version of the art if the PNGs ever need regenerating by hand.
fs.writeFileSync(path.join(DOCS, 'icon.svg'), svgIcon('full') + '\n');
console.log('wrote icon.svg');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kuicons-'));
for (const [file, shape, size] of TARGETS) {
  // Drawn onto a canvas at exactly the target size and encoded there, the way
  // make-icons.mjs does for the launcher raster. --screenshot was the obvious
  // route and the wrong one: its output is window-size pixels while the layout
  // viewport is that divided by a device scale factor it applies regardless of
  // --force-device-scale-factor, so the mark cropped at 512 and vanished
  // altogether at 180. A canvas has no viewport to disagree with.
  //
  // The encoded bytes come back through --dump-dom, which needs no automation
  // library — just the browser binary.
  const page = path.join(tmp, `${size}-${shape}.html`);
  fs.writeFileSync(page, `<!doctype html><body><pre id="out"></pre><script>
    (async () => {
      const img = new Image();
      img.src = 'data:image/svg+xml;base64,' + btoa(${JSON.stringify(svgIcon(shape, size))});
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = cv.height = ${size};
      const ctx = cv.getContext('2d');
      ctx.drawImage(img, 0, 0, ${size}, ${size});
      document.getElementById('out').textContent = cv.toDataURL('image/png').split(',')[1];
    })();
  </script></body>`);
  const dom = execFileSync(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--virtual-time-budget=5000', '--dump-dom', `file://${page}`,
  ], { stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 }).toString();
  const b64 = dom.match(/<pre id="out">([A-Za-z0-9+/=]*)<\/pre>/)?.[1];
  if (!b64) throw new Error(`no image came back for ${file}`);
  const buf = Buffer.from(b64, 'base64');
  // Chromium can fail by producing nothing rather than by erroring, and an icon
  // that is silently absent is worse than a build that stops.
  if (buf.slice(1, 4).toString() !== 'PNG') throw new Error(`not a png: ${file}`);
  fs.writeFileSync(path.join(DOCS, file), buf);
  console.log(`wrote ${file} (${size}px, ${buf.length}b)`);
}
fs.rmSync(tmp, { recursive: true, force: true });
