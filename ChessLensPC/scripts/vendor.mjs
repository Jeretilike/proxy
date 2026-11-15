// Copies the chess rules (chess.js) and the Stockfish engine (WASM) from node_modules into
// web/vendor, where the app loads them. Runs after `npm install`.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const vendor = join(root, 'web', 'vendor');
mkdirSync(vendor, { recursive: true });
const copy = (from, to) => copyFileSync(join(root, 'node_modules', from), join(vendor, to));

copy('chess.js/dist/esm/chess.js', 'chess.js');
// Full-strength multi-threaded engine (needs cross-origin isolation, which the app sets up),
// plus the small single-threaded one as a fallback.
for (const name of ['stockfish-18.js', 'stockfish-18.wasm', 'stockfish-18-lite-single.js', 'stockfish-18-lite-single.wasm']) {
  copy(`stockfish/bin/${name}`, name);
}
console.log('Copied chess.js and Stockfish into web/vendor');
