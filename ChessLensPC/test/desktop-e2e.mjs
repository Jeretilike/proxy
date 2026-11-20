// End to end on a desktop: a browser window shows a fake chess site; the ChessLens desktop app
// reads it from the screen, learns the pieces from the starting position and follows a game.
// `xvfb-run -a -s "-screen 0 1920x1080x24" node test/desktop-e2e.mjs`
import { createRequire } from 'node:module';
import { serve } from './serve.mjs';
import { drawSite } from './fake-site.mjs';
import { Chess } from '../web/vendor/chess.js';

const require = createRequire(import.meta.url);
const { chromium, _electron } = require(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const server = await serve();
const base = `http://127.0.0.1:${server.address().port}/`;

// The "chess site", in a normal browser window on the right of the screen.
console.log('launching browser');
const browser = await chromium.launch({ headless: false, args: ['--window-position=900,0', '--window-size=1000,1000'] });
const site = await browser.newPage({ viewport: { width: 980, height: 900 } });
await site.goto(base + 'index.html');
await site.evaluate(() => { document.title = 'Fake Chess Site'; });
const theme = { name: 'green', light: '#eeeed2', dark: '#769656', page: '#312e2b' };
const draw = (fen, last = null) => drawSite(site, fen, { theme, style: 'filled', size: 640, left: 40, top: 120, whiteAtBottom: true, last });
await draw(new Chess().fen());
console.log('site drawn');

// ChessLens on the left.
const app = await _electron.launch({ executablePath: require('electron'), args: ['.', '--no-sandbox'] });
console.log('electron launched');
const win = await app.firstWindow();
console.log('window open');
await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setBounds({ x: 0, y: 0, width: 960, height: 760 }));
await win.waitForFunction(() => window.chesslens?.suggestion, null, { timeout: 120000 });
await win.evaluate(() => { window.chesslens.settings.voice = false; });

const sources = await win.evaluate(() => window.chesslensDesktop.listSources());
console.log('sources:', sources.map((s) => s.name).join(' | '));
const screen = sources.find((s) => s.screen);
await win.evaluate((id) => window.chesslens.reader.start(id), screen.id);

async function waitForBoard(fen, label) {
  const want = fen.split(' ')[0];
  const ok = await win.waitForFunction((w) => window.chesslens.game.fen().split(' ')[0] === w, want, { timeout: 20000 }).then(() => true, () => false);
  const state = await win.evaluate(() => ({ fen: window.chesslens.game.fen(), status: document.getElementById('screenStatus').textContent }));
  console.log(`${label}: ${ok ? 'OK' : 'FAILED'}  ${state.fen}  [${state.status}]`);
  return ok;
}

let passed = 0, total = 0;
const game = new Chess();
total++; if (await waitForBoard(game.fen(), 'start position learned')) passed++;
for (const san of ['e4', 'e5', 'Nf3', 'Nc6', 'Bb5', 'a6', 'Bxc6', 'dxc6', 'O-O']) {
  const move = game.move(san);
  await draw(game.fen(), move);
  total++; if (await waitForBoard(game.fen(), `after ${san}`)) passed++;
}
const turn = await win.evaluate(() => ({ turn: window.chesslens.game.turn(), history: window.chesslens.game.history().join(' '), suggestion: window.chesslens.suggestion?.san }));
console.log('app history:', turn.history, '| to move:', turn.turn, '| suggestion:', turn.suggestion);
await win.screenshot({ path: process.env.SHOT ?? 'desktop.png' });
console.log(`\n${passed}/${total} positions followed from the screen`);
await app.close();
await browser.close();
server.close();
