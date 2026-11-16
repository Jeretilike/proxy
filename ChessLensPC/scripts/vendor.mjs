// Copies the chess rules (chess.js) and the Stockfish engine (WASM) from node_modules into
// web/vendor, where the app loads them. Runs after `npm install`.
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
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
// Piece pictures: the "cburnett" set (GPL), as embedded SVGs from chessground's stylesheet.
const css = readFileSync(join(root, 'node_modules', 'chessground', 'assets', 'chessground.cburnett.css'), 'utf8');
const TYPES = { pawn: 'p', knight: 'n', bishop: 'b', rook: 'r', queen: 'q', king: 'k' };
let out = '/* Piece pictures: cburnett set by Colin M.L. Burnett (GPL), via chessground. */\n';
for (const match of css.matchAll(/piece\.(\w+)\.(white|black)\s*\{\s*background-image:\s*(url\([^)]*\))/g)) {
  const [, type, color, url] = match;
  if (!TYPES[type]) continue;
  out += `.pc-${color[0]}${TYPES[type]} { background-image: ${url}; }\n`;
}
writeFileSync(join(vendor, 'pieces.css'), out);
console.log('Copied chess.js, Stockfish and the piece pictures into web/vendor');
