// Launches the real desktop app and checks Stockfish answers inside it. `xvfb-run node test/electron-check.mjs`
import { createRequire } from 'node:module';
const { _electron } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_PATH ?? 'playwright');

const app = await _electron.launch({ executablePath: createRequire(import.meta.url)('electron'), args: ['.', '--no-sandbox'] });
const page = await app.firstWindow();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.waitForFunction(() => window.chesslens?.suggestion, null, { timeout: 120000 });
console.log(await page.evaluate(() => ({ url: location.href, isolated: self.crossOriginIsolated, engine: window.chesslens.engine.name, san: window.chesslens.suggestion.san })));
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
console.log('errors:', errors.length ? errors : 'none');
await app.close();
