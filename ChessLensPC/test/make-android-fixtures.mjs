// Writes phone-shaped fake chess-app screenshots as raw RGB files (name_WxH.rgb) plus the
// expected position, for the Android app's JVM tests. `node test/make-android-fixtures.mjs <dir>`
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { serve } from './serve.mjs';
import { drawSite } from './fake-site.mjs';
import { Chess } from '../web/vendor/chess.js';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const out = process.argv[2];
mkdirSync(out, { recursive: true });
const server = await serve();
const browser = await chromium.launch();
// A phone screen at half resolution, like the app captures it (1080×2400 → 540×1200).
const page = await browser.newPage({ viewport: { width: 540, height: 1200 } });
await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);

let seed = 11;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const themes = [
  { name: 'green', light: '#eeeed2', dark: '#769656', page: '#312e2b' },
  { name: 'brown', light: '#f0d9b5', dark: '#b58863', page: '#161512' },
];
async function shot(name, fen, opts, expected) {
  await drawSite(page, fen, opts);
  const b64 = (await page.screenshot()).toString('base64');
  const raw = await page.evaluate(async (b) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b;
    await img.decode();
    const c = new OffscreenCanvas(img.width, img.height);
    const ctx = c.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const d = ctx.getImageData(0, 0, img.width, img.height).data;
    const rgb = new Uint8Array(img.width * img.height * 3);
    for (let i = 0, j = 0; i < d.length; i += 4, j += 3) { rgb[j] = d[i]; rgb[j + 1] = d[i + 1]; rgb[j + 2] = d[i + 2]; }
    let s = '';
    for (let i = 0; i < rgb.length; i += 8192) s += String.fromCharCode(...rgb.subarray(i, i + 8192));
    return { w: img.width, h: img.height, data: btoa(s) };
  }, b64);
  writeFileSync(join(out, `${name}_${raw.w}x${raw.h}.rgb`), Buffer.from(raw.data, 'base64'));
  writeFileSync(join(out, `${name}.txt`), expected);
}

let n = 0;
for (const theme of themes) {
  for (const whiteAtBottom of [true, false]) {
    const layout = { theme, style: 'filled', size: 520, left: 10, top: 330, whiteAtBottom };
    const tag = `${theme.name}-${whiteAtBottom ? 'w' : 'b'}`;
    await shot(`${tag}-0start`, new Chess().fen(), { ...layout, last: null }, `${new Chess().fen()}\n${whiteAtBottom}`);
    for (let k = 1; k <= 3; k++) {
      const g = new Chess();
      let last = null;
      const plies = 6 + Math.floor(rand() * 40);
      for (let i = 0; i < plies && !g.isGameOver(); i++) {
        const moves = g.moves({ verbose: true });
        last = moves[Math.floor(rand() * moves.length)];
        g.move(last);
      }
      await shot(`${tag}-${k}`, g.fen(), { ...layout, last }, `${g.fen()}\n${whiteAtBottom}`);
      n++;
      // The player has tapped one of their pieces: the app highlights it too.
      if (k === 1) {
        const mine = g.moves({ verbose: true })[0];
        if (mine) {
          await shot(`${tag}-${k}sel`, g.fen(), { ...layout, last, selected: mine.from }, `${g.fen()}\n${whiteAtBottom}\nselected`);
          n++;
        }
      }
    }
  }
}
console.log(`wrote ${n + 4} screenshots to ${out}`);
await browser.close();
server.close();
