// Screenshots of the main screen, a game against Stockfish, settings and Mini mode, and checks
// for page errors. `node test/ui-check.mjs <output folder>`
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { serve } from './serve.mjs';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const out = process.argv[2] ?? '.';
const server = await serve();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(`http://127.0.0.1:${server.address().port}/`);
const ready = () => page.waitForFunction(() => window.chesslens?.suggestion && window.chesslens.suggestion.fen === window.chesslens.game.fen(), null, { timeout: 60000 });
await ready();

// A game with captures, in Brilliant mode, at the Légal trap.
await page.evaluate(() => {
  const c = window.chesslens;
  c.settings.voice = false;
  c.settings.sounds = false;
  c.game.load('rn1qkbnr/ppp2p1p/3p2p1/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5');
  document.querySelector('#modeSwitch [data-mode="brilliant"]').click();
});
await ready();
await page.waitForTimeout(500);
await page.screenshot({ path: join(out, 'ui-main.png') });
const main = await page.evaluate(() => ({
  pieces: document.querySelectorAll('#board .piece').length,
  pieceImage: getComputedStyle(document.querySelector('#board .piece')).backgroundImage.slice(0, 30),
  lines: document.querySelectorAll('#lines .line').length,
  suggestion: document.getElementById('suggestionMove').textContent,
  brilliant: document.getElementById('suggestion').classList.contains('brilliant'),
  toast: document.getElementById('toast').textContent,
}));
console.log('main:', main);

// Against Stockfish: play a move, Stockfish should reply.
await page.evaluate(() => {
  document.querySelector('#modeSwitch [data-mode="best"]').click();
  document.querySelector('#newGame').click();
  document.querySelector('#opponentSwitch [data-opponent="engine"]').click();
});
await ready();
await page.evaluate(() => window.chesslens.makeMove({ from: 'e2', to: 'e4' }));
const replied = await page.waitForFunction(() => window.chesslens.game.history().length === 2, null, { timeout: 20000 }).then(() => true, () => false);
await ready();
await page.evaluate(() => { for (const m of ['Nf3', 'Bc4']) {} });
await page.waitForTimeout(400);
await page.screenshot({ path: join(out, 'ui-engine.png') });
console.log('vs Stockfish:', { replied, history: await page.evaluate(() => window.chesslens.game.history().join(' ')),
  topName: await page.textContent('#topName'), bottomName: await page.textContent('#bottomName') });

// Undo takes back both moves.
await page.click('#undo');
console.log('after undo:', await page.evaluate(() => window.chesslens.game.history().length), 'plies');

// Promotion picker and edit palette show piece pictures.
await page.evaluate(() => { window.chesslens.game.load('8/4P1k1/8/8/8/8/8/4K3 w - - 0 1'); window.chesslens.positionChanged(); });
await page.mouse.click(...(await page.evaluate(() => { const r = document.querySelector('[data-square="e7"]').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; })));
await page.mouse.click(...(await page.evaluate(() => { const r = document.querySelector('[data-square="e8"]').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; })));
await page.waitForTimeout(200);
console.log('promotion picker:', await page.evaluate(() => [...document.querySelectorAll('#promotion button')].map((b) => getComputedStyle(b).backgroundImage.startsWith('url') ? 'image' : 'NO IMAGE').join(',')));
await page.screenshot({ path: join(out, 'ui-promotion.png') });
await page.click('#promotion button');

await page.click('#openSettings');
await page.waitForTimeout(200);
await page.screenshot({ path: join(out, 'ui-settings.png') });
await page.keyboard.press('Escape');

// Mini mode (the window itself is resized by the desktop app; here just the page).
await page.setViewportSize({ width: 280, height: 150 });
await page.evaluate(() => document.getElementById('miniToggle').click());
await page.waitForTimeout(200);
await page.screenshot({ path: join(out, 'ui-mini.png') });

console.log('errors:', errors.length ? errors : 'none');
await browser.close();
server.close();
