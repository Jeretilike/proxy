// Feeds screenshots of a fake chess site into the app's screen reader (in place of live capture)
// and checks the app follows the game, suggests on your turn, and outlines the right squares.
// `node test/follow-check.mjs`
import { createRequire } from 'node:module';
import { serve } from './serve.mjs';
import { drawSite } from './fake-site.mjs';
import { Chess } from '../web/vendor/chess.js';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const server = await serve();
const base = `http://127.0.0.1:${server.address().port}/`;
const browser = await chromium.launch();
const site = await browser.newPage({ viewport: { width: 1600, height: 900 } });
await site.goto(base + 'index.html');
const app = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = [];
app.on('pageerror', (e) => errors.push(e.message));
await app.goto(base + 'index.html');
await app.waitForFunction(() => window.chesslens?.engine, null, { timeout: 60000 });

// Stand-ins for the desktop app's capture: frames come from screenshots; outlines are recorded.
await app.evaluate(() => {
  const c = window.chesslens;
  c.settings.voice = false;
  c.settings.sounds = false;
  window.highlights = [];
  window.chesslensDesktop = { highlight: (p) => { window.highlights.push(p); }, geometry: async () => window.fakeGeometry, listSources: async () => [] };
  c.reader.stream = { getTracks: () => [] };
  c.reader.frame = () => window.currentFrame;
  window.feed = async (b64) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const canvas = c.reader.canvas;
    canvas.width = img.width; canvas.height = img.height;
    c.reader.ctx.drawImage(img, 0, 0);
    window.currentFrame = c.reader.ctx.getImageData(0, 0, img.width, img.height);
    // Like Windows at 150% display scaling: capture pixels are 1.5× screen coordinates.
    window.fakeGeometry = { display: { x: 0, y: 0, width: img.width / 1.5, height: img.height / 1.5 }, window: { x: -9999, y: 0, width: 1, height: 1 } };
    c.reader.geometry = window.fakeGeometry;
    c.reader.read();
    c.reader.read(); // positions are reported once seen twice
  };
});

const theme = { name: 'green', light: '#eeeed2', dark: '#769656', page: '#312e2b' };
const layout = { theme, style: 'filled', size: 640, left: 200, top: 120, whiteAtBottom: true };
async function show(fen, last) {
  await drawSite(site, fen, { ...layout, last });
  const b64 = (await site.screenshot({ scale: 'device' })).toString('base64');
  await app.evaluate((b) => window.feed(b), b64);
}

let ok = 0, total = 0;
async function expect(fen, label) {
  total++;
  const want = fen.split(' ').slice(0, 2).join(' ');
  const got = await app.waitForFunction((w) => window.chesslens.game.fen().split(' ').slice(0, 2).join(' ') === w, want, { timeout: 8000 })
    .then(() => true, () => false);
  const state = await app.evaluate(() => ({ fen: window.chesslens.game.fen(), status: document.getElementById('screenStatus').textContent }));
  if (got) ok++;
  console.log(`${got ? 'OK  ' : 'FAIL'} ${label}: ${state.fen}${got ? '' : `  [${state.status}]`}`);
}

const game = new Chess();
await show(game.fen(), null);
await expect(game.fen(), 'learned pieces from the start position');
for (const san of ['e4', 'e5', 'Nf3', 'Nc6', 'Bb5', 'a6', 'Bxc6', 'dxc6', 'O-O', 'f6']) {
  const move = game.move(san);
  await show(game.fen(), move);
  await expect(game.fen(), `after ${san}`);
}
// Two moves at once (looked away), and a jump to an unrelated position.
const m1 = game.move('d4'), m2 = game.move('exd4');
await show(game.fen(), m2);
await expect(game.fen(), 'two moves at once (d4 exd4)');

// White to move: a suggestion and an outline on the site at the right place.
await app.waitForFunction(() => window.chesslens.suggestion && window.chesslens.suggestion.fen === window.chesslens.game.fen(), null, { timeout: 20000 });
const outline = await app.evaluate(() => ({ move: window.chesslens.suggestion.line.move, last: window.highlights.filter(Boolean).at(-1) }));
const sq = (name) => {
  const file = name.charCodeAt(0) - 97, rank = Number(name[1]) - 1;
  const cell = layout.size / 8;
  return { x: (layout.left + file * cell) / 1.5, y: (layout.top + (7 - rank) * cell) / 1.5 };
};
const expectedTo = sq(outline.move.slice(2, 4));
const off = outline.last ? Math.max(Math.abs(outline.last.to.x - expectedTo.x), Math.abs(outline.last.to.y - expectedTo.y)) : Infinity;
total++; if (off < 3) ok++;
console.log(`${off < 3 ? 'OK  ' : 'FAIL'} outline for ${outline.move} lands on the site's square (off by ${off.toFixed(1)} px)`);

// Board flipped (playing Black): orientation and colour follow.
layout.whiteAtBottom = false;
const g2 = new Chess();
await show(g2.fen(), null);
const m = g2.move('e4');
await show(g2.fen(), m);
await expect(g2.fen(), 'flipped board (you play Black), after 1.e4');
const color = await app.evaluate(() => window.chesslens.settings.color);
total++; if (color === 'b') ok++;
console.log(`${color === 'b' ? 'OK  ' : 'FAIL'} your colour switched to Black`);

console.log(`\n${ok}/${total} passed; page errors: ${errors.length ? errors : 'none'}`);
await browser.close();
server.close();
