// Renders fake chess-website screenshots and checks the screen reader finds the board, learns the
// pieces from a starting position and then reads random positions exactly.
// `node test/screen-check.mjs` (needs Playwright's Chromium).
import { createRequire } from 'node:module';
import { serve } from './serve.mjs';
import { Chess } from '../web/vendor/chess.js';
import { drawSite } from './fake-site.mjs';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const server = await serve();
const base = `http://127.0.0.1:${server.address().port}/`;
const browser = await chromium.launch();
const site = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const lab = await browser.newPage();
await site.goto(base + 'index.html');
await lab.goto(base + 'index.html');

const THEMES = [
  { name: 'green', light: '#eeeed2', dark: '#769656', page: '#312e2b' },
  { name: 'brown', light: '#f0d9b5', dark: '#b58863', page: '#161512' },
  { name: 'blue', light: '#dee3e6', dark: '#8ca2ad', page: '#f4f4f4' },
];
const STYLES = ['filled', 'outline'];
let seed = 7;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);

function randomGame(plies) {
  const g = new Chess();
  let last = null;
  for (let i = 0; i < plies && !g.isGameOver(); i++) {
    const moves = g.moves({ verbose: true });
    last = moves[Math.floor(rand() * moves.length)];
    g.move(last);
  }
  return { fen: g.fen(), last };
}

async function render(fen, options) {
  await drawSite(site, fen, options);
  return (await site.screenshot()).toString('base64');
}

await lab.evaluate(() => {
  window.loadShot = async (b64) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const canvas = new OffscreenCanvas(img.width, img.height);
    const ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0);
    return ctx.getImageData(0, 0, img.width, img.height);
  };
});

let total = 0, exact = 0, moverRight = 0, moverKnown = 0, found = 0, failures = [];
for (const theme of THEMES) {
  for (const style of STYLES) {
    for (const whiteAtBottom of [true, false]) {
      const size = 360 + Math.floor(rand() * 400);
      const left = 40 + Math.floor(rand() * 300), top = 60 + Math.floor(rand() * Math.max(10, 820 - size - 60));
      const startShot = await render(new Chess().fen(), { theme, style, size, left, top, whiteAtBottom, last: null });
      const learned = await lab.evaluate(async (b64) => {
        const v = await import('./screen-vision.js');
        const img = await window.loadShot(b64);
        const t0 = performance.now();
        const grid = v.findBoard(img);
        const ms = performance.now() - t0;
        if (!grid) {
          const hc = v._internals.chains(img, true), vc = v._internals.chains(img, false);
          const xs = v._internals.edgeCandidates(hc).slice(0, 3), ys = v._internals.edgeCandidates(vc).slice(0, 3);
          const scores = xs.flatMap((x) => ys.map((y) => [Math.round(x.origin), Math.round(y.origin), x.cell.toFixed(1), y.cell.toFixed(1), v.checkerScore(img, { x: x.origin, y: y.origin, cw: x.cell, ch: y.cell }).toFixed(0)]));
          const col = (x) => { const b = []; let prev = null; for (let y = 0; y < img.height; y++) { const o = (y * img.width + x) * 4; const c = [img.data[o], img.data[o+1], img.data[o+2]]; if (prev && v.colorDistance(c, prev) > 40) b.push(y + ':' + c.join(',')); prev = c; } return b.join(' '); };
          window.dbgcol = col(Math.round(xs[0].origin + xs[0].cell * 1.1));
          return { error: `board not found; col ${window.dbgcol}; chains ${hc.length}/${vc.length} ${JSON.stringify(scores)}` };
        }
        const squares = v.readSquares(img, grid);
        const lesson = v.learnFromStart(squares);
        if (!lesson) return { error: 'start position not recognised: ' + squares.map((q, i) => (i % 8 ? '' : '|') + q.coverage.toFixed(2) + '/' + Math.round(q.meanLum)).join(' '), grid };
        window.templates = lesson.templates;
        window.lastGrid = grid;
        return { grid, ms: Math.round(ms), whiteAtBottom: lesson.whiteAtBottom };
      }, startShot);
      const label = `${theme.name}/${style}/${whiteAtBottom ? 'white' : 'black'} at bottom, ${size}px at ${left},${top}`;
      if (learned.error) { failures.push(`${label}: ${learned.error}`); continue; }
      if (learned.whiteAtBottom !== whiteAtBottom) failures.push(`${label}: orientation wrong`);
      const g = learned.grid;
      const off = Math.max(Math.abs(g.x - left), Math.abs(g.y - top), Math.abs(g.cw * 8 - size));
      console.log(`${label}: board found in ${learned.ms} ms, off by ${off.toFixed(1)} px`);

      for (let n = 0; n < 4; n++) {
        const { fen, last } = randomGame(6 + Math.floor(rand() * 50));
        // Positions are read with the board slightly moved/resized too (window resized).
        const size2 = n === 3 ? size - 40 : size;
        const shot = await render(fen, { theme, style, size: size2, left, top, whiteAtBottom, last });
        const result = await lab.evaluate(async (b64) => {
          const v = await import('./screen-vision.js');
          const img = await window.loadShot(b64);
          const grid = v.findBoard(img, window.lastGrid);
          if (!grid) return { error: 'board not found' };
          window.lastGrid = grid;
          const r = v.recognize(v.readSquares(img, grid), window.templates);
          if (!r) return { error: 'unrecognised square' };
          return { placement: v.placementFen(r.placement), whiteAtBottom: r.whiteAtBottom, lastMover: r.lastMover };
        }, shot);
        total++;
        if (result.error) { failures.push(`${label} #${n}: ${result.error}`); continue; }
        found++;
        const want = fen.split(' ')[0];
        if (result.placement === want) exact++;
        else failures.push(`${label} #${n}: read ${result.placement}\n      wanted ${want}`);
        const mover = fen.split(' ')[1] === 'w' ? 'b' : 'w';
        if (result.lastMover) { moverKnown++; if (result.lastMover === mover) moverRight++; }
      }
    }
  }
}

// No board on the page at all.
await site.evaluate(() => { document.body.innerHTML = '<h1 style="color:#ddd">News</h1>' + '<p style="color:#bbb">Lorem ipsum dolor sit amet. </p>'.repeat(40); });
const none = await lab.evaluate(async (b64) => {
  const v = await import('./screen-vision.js');
  return v.findBoard(await window.loadShot(b64));
}, (await site.screenshot()).toString('base64'));

console.log(`\npositions read exactly: ${exact}/${total} (board found ${found}/${total})`);
console.log(`side that just moved (from highlight): right ${moverRight}/${moverKnown}`);
console.log(`page without a board: ${none ? 'FALSE BOARD FOUND' : 'no board (correct)'}`);
if (failures.length) console.log('failures:\n  ' + failures.join('\n  '));
await browser.close();
server.close();
