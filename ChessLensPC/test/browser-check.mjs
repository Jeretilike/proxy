// Opens the app in Chromium, checks the engine answers, and runs Brilliant mode on known
// sacrifice positions. Not part of `npm test` (needs a browser): `node test/browser-check.mjs`.
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');
import { serve } from './serve.mjs';

const server = await serve();
const url = `http://127.0.0.1:${server.address().port}/`;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForFunction(() => window.chesslens?.suggestion, null, { timeout: 120000 });
const info = await page.evaluate(() => ({ isolated: self.crossOriginIsolated, engine: window.chesslens.engine.name, san: window.chesslens.suggestion.san }));
console.log('start position:', info);
await page.screenshot({ path: process.env.SHOT ?? 'start.png' });

const positions = [
  ['Légal trap (Nxe5!! gives the queen)', 'rn1qkbnr/ppp2p1p/3p2p1/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5'],
  ['Greek gift (Bxh7+)', 'rnbq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 0 7'],
  ['Quiet opening (no brilliant)', 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2'],
  ['Queen sac mate (Qxh7+)', '5rk1/pp3ppp/8/3pN1Q1/8/8/PPP2PPP/6K1 w - - 0 1'],
];
await page.evaluate(() => { window.chesslens.settings.mode = 'brilliant'; window.chesslens.settings.voice = false; window.chesslens.settings.thinkTime = 3000; });
for (const [name, fen] of positions) {
  const result = await page.evaluate(async (fen) => {
    const c = window.chesslens;
    c.settings.color = fen.split(' ')[1];
    c.game.load(fen);
    c.positionChanged();
    const start = Date.now();
    while (!(c.suggestion && c.suggestion.fen === c.game.fen()) && Date.now() - start < 30000) await new Promise((r) => setTimeout(r, 100));
    const s = c.suggestion;
    return s ? { san: s.san, brilliant: s.brilliant, best: s.bestSan, cp: s.line.cp, mate: s.line.mate } : null;
  }, fen);
  console.log(name, '→', JSON.stringify(result));
  if (name.startsWith('Légal')) await page.screenshot({ path: process.env.SHOT2 ?? 'brilliant.png' });
}
console.log('errors:', errors.length ? errors : 'none');
await browser.close();
server.close();
