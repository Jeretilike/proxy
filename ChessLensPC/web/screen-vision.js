// Reading a chess board from a screenshot: finds the board (an 8×8 grid of alternating flat
// colours, as drawn by chess.com, lichess and most other sites), then identifies the piece on
// each square by comparing it with pieces learned from a starting position of the same piece set.
//
// Pure functions on RGBA images ({ data, width, height }), so the tests can run them on
// rendered screenshots.

const SIDE = 32; // pieces are compared at 32×32
const MASK_THRESHOLD = 60; // colour distance from the square's background that counts as "piece"
export const OCCUPIED = 0.035; // share of piece pixels that makes a square occupied
const MAX_DISTANCE = 0.16; // a square must look at least this much like something we know

// ---------------------------------------------------------------------------------------------
// Pixels

function colorAt(img, x, y) {
  const xi = Math.max(0, Math.min(img.width - 1, Math.round(x)));
  const yi = Math.max(0, Math.min(img.height - 1, Math.round(y)));
  const o = (yi * img.width + xi) * 4;
  return [img.data[o], img.data[o + 1], img.data[o + 2]];
}

function bilinear(img, x, y) {
  const x0 = Math.max(0, Math.min(img.width - 2, Math.floor(x)));
  const y0 = Math.max(0, Math.min(img.height - 2, Math.floor(y)));
  const fx = Math.min(1, Math.max(0, x - x0)), fy = Math.min(1, Math.max(0, y - y0));
  const w = img.width, d = img.data;
  const out = [0, 0, 0];
  for (let c = 0; c < 3; c++) {
    const a = d[(y0 * w + x0) * 4 + c], b = d[(y0 * w + x0 + 1) * 4 + c];
    const e = d[((y0 + 1) * w + x0) * 4 + c], f = d[((y0 + 1) * w + x0 + 1) * 4 + c];
    out[c] = (a * (1 - fx) + b * fx) * (1 - fy) + (e * (1 - fx) + f * fx) * fy;
  }
  return out;
}

export const colorDistance = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
const luma = (c) => 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];

function medianColor(colors) {
  const out = [0, 0, 0];
  for (let c = 0; c < 3; c++) {
    const values = colors.map((v) => v[c]).sort((a, b) => a - b);
    out[c] = values[values.length >> 1];
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Finding the board

/**
 * Runs of evenly spaced colour edges along rows (horizontal = true) or columns, where the colour
 * just inside each cell matches at both ends of the cell and alternates from cell to cell, which
 * is what a row of board squares looks like even with pieces standing on them.
 */
function chains(img, horizontal) {
  const lines = horizontal ? img.height : img.width;
  const length = horizontal ? img.width : img.height;
  const at = horizontal ? (i, line) => colorAt(img, i, line) : (i, line) => colorAt(img, line, i);
  const found = [];
  const minCell = 12, maxCell = length / 7.5;

  for (let line = 1; line < lines - 1; line += 2) {
    const bounds = [];
    const d = img.data;
    const step = horizontal ? 4 : img.width * 4;
    let o = horizontal ? line * img.width * 4 : line * 4;
    let last = -10;
    for (let i = 1; i < length; i++) {
      const p = o + step;
      const diff = Math.abs(d[p] - d[o]) + Math.abs(d[p + 1] - d[o + 1]) + Math.abs(d[p + 2] - d[o + 2]);
      if (diff > 40) {
        if (i - last > 2) bounds.push(i);
        last = i;
      }
      o = p;
    }
    if (bounds.length < 6) continue;

    const used = new Set();
    for (let s = 0; s < bounds.length - 5; s++) {
      if (used.has(s)) continue;
      for (let t = s + 1; t < Math.min(bounds.length, s + 8); t++) {
        const g = bounds[t] - bounds[s];
        if (g < minCell) continue;
        if (g > maxCell) break;
        const tol = Math.max(2, g * 0.04);
        const off = Math.max(2, Math.round(g * 0.06));
        const cells = [];
        let edge = bounds[s];
        let next = bounds[t];
        let k = t;
        let previousInside = null;
        while (true) {
          const inside = at(edge + off, line);
          const insideEnd = at(next - off, line);
          if (colorDistance(inside, insideEnd) > 30) break;
          if (previousInside && colorDistance(inside, previousInside) < 25) break;
          cells.push(next);
          previousInside = inside;
          edge = next;
          // The next cell edge: skip edges inside the cell (pieces).
          let nextIndex = -1;
          for (let j = k + 1; j < bounds.length; j++) {
            const gap = bounds[j] - (edge + g);
            if (Math.abs(gap) <= tol) { nextIndex = j; break; }
            if (gap > tol) break;
          }
          if (nextIndex < 0) break;
          k = nextIndex;
          next = bounds[nextIndex];
        }
        if (cells.length >= 5) {
          const end = cells[cells.length - 1];
          found.push({ line, start: bounds[s], end, cell: (end - bounds[s]) / cells.length, cells: cells.length });
          used.add(s);
          break;
        }
      }
    }
  }
  return found;
}

/** Candidate board origins along one axis, with votes. */
function edgeCandidates(chainList) {
  const votes = new Map();
  for (const c of chainList) {
    for (let k = 0; k <= 8 - c.cells; k++) {
      const origin = c.start - k * c.cell;
      const key = `${Math.round(origin / 2)}:${Math.round(c.cell)}`;
      const v = votes.get(key) ?? { origin: 0, cell: 0, n: 0 };
      v.origin += origin; v.cell += c.cell; v.n += 1;
      votes.set(key, v);
    }
  }
  return [...votes.values()]
    .map((v) => ({ origin: v.origin / v.n, cell: v.cell / v.n, n: v.n }))
    .sort((a, b) => b.n - a.n)
    .slice(0, 12);
}

/** Background colour of each square, from small patches near its four corners. */
function squareBackgrounds(img, grid) {
  const result = [];
  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) result.push(cellBackground(img, grid, row, col));
  }
  return result;
}

function cellBackground(img, grid, row, col) {
  const x0 = grid.x + col * grid.cw, y0 = grid.y + row * grid.ch;
  const samples = [];
  for (const fy of [0.07, 0.13, 0.87, 0.93]) {
    for (const fx of [0.07, 0.13, 0.87, 0.93]) samples.push(colorAt(img, x0 + fx * grid.cw, y0 + fy * grid.ch));
  }
  return medianColor(samples);
}

/**
 * How much the grid looks like a board: square backgrounds split cleanly into two alternating
 * colours, and the ring of cells just outside doesn't continue the pattern.
 */
export function checkerScore(img, grid) {
  if (grid.x < -2 || grid.y < -2 || grid.x + 8 * grid.cw > img.width + 2 || grid.y + 8 * grid.ch > img.height + 2) return 0;
  const bgs = squareBackgrounds(img, grid);
  const light = [], dark = [];
  bgs.forEach((c, i) => (((i >> 3) + (i & 7)) % 2 === 0 ? light : dark).push(c));
  const lightMean = medianColor(light), darkMean = medianColor(dark);
  const separation = colorDistance(lightMean, darkMean);
  if (separation < 30) return 0;
  let consistent = 0;
  bgs.forEach((c, i) => {
    const own = ((i >> 3) + (i & 7)) % 2 === 0 ? lightMean : darkMean;
    if (colorDistance(c, own) < Math.min(40, separation / 2)) consistent++;
  });
  const fraction = consistent / 64;
  if (fraction < 0.7) return 0;

  // Cells just outside the board shouldn't look like board squares.
  let outsideMatches = 0, outside = 0;
  for (let i = -1; i <= 8; i++) {
    for (const [row, col] of [[-1, i], [8, i], [i, -1], [i, 8]]) {
      if (i < 0 || i > 7) continue;
      const cx = grid.x + (col + 0.5) * grid.cw, cy = grid.y + (row + 0.5) * grid.ch;
      outside++;
      if (cx < 0 || cy < 0 || cx >= img.width || cy >= img.height) continue;
      const c = cellBackground(img, grid, row, col);
      const expected = (((row + 8) % 2) + ((col + 8) % 2)) % 2 === 0 ? lightMean : darkMean;
      if (colorDistance(c, expected) < Math.min(40, separation / 2)) outsideMatches++;
    }
  }
  return fraction * Math.min(separation, 150) * (1 - outsideMatches / outside);
}

/** Fine-tunes grid lines onto the colour edges between squares. */
function refineAxis(img, grid, horizontal) {
  const origin = horizontal ? grid.x : grid.y;
  const cell = horizontal ? grid.cw : grid.ch;
  let best = { origin, cell, energy: -1 };
  for (let dc = -1; dc <= 1.001; dc += 0.25) {
    for (let d0 = -3; d0 <= 3; d0 += 0.5) {
      const o = origin + d0, c = cell + dc;
      let energy = 0;
      for (let k = 1; k < 8; k++) {
        const p = o + k * c;
        for (let s = 0; s < 32; s++) {
          const q = (horizontal ? grid.y : grid.x) + ((s + 0.5) / 32) * 8 * (horizontal ? grid.ch : grid.cw);
          // Grid positions are pixel edges: pixel i spans [i, i + 1) and is centred at i.
          const a = horizontal ? bilinear(img, p - 1, q) : bilinear(img, q, p - 1);
          const b = horizontal ? bilinear(img, p, q) : bilinear(img, q, p);
          energy += colorDistance(a, b);
        }
      }
      if (energy > best.energy) best = { origin: o, cell: c, energy };
    }
  }
  return horizontal ? { ...grid, x: best.origin, cw: best.cell } : { ...grid, y: best.origin, ch: best.cell };
}

/**
 * Finds the board: { x, y, cw, ch, score } in image pixels, or null. Pass the previous result to
 * check it first (cheap) before searching the whole image.
 */
export function findBoard(img, previous = null) {
  if (previous && checkerScore(img, previous) > 25) return { ...previous, score: checkerScore(img, previous) };

  const xs = edgeCandidates(chains(img, true));
  const ys = edgeCandidates(chains(img, false));
  let best = null;
  for (const cx of xs) {
    for (const cy of ys) {
      if (Math.abs(cx.cell - cy.cell) > Math.max(3, cx.cell * 0.06)) continue;
      const grid = { x: cx.origin, y: cy.origin, cw: cx.cell, ch: cy.cell };
      const score = checkerScore(img, grid);
      if (score > 25 && (!best || score > best.score)) best = { ...grid, score };
    }
  }
  if (!best) return null;
  let grid = refineAxis(img, best, true);
  grid = refineAxis(img, grid, false);
  const score = checkerScore(img, grid);
  return score >= best.score * 0.9 ? { ...grid, score } : best;
}

// ---------------------------------------------------------------------------------------------
// Squares and pieces

/** For each of the 64 squares (screen order, top-left first): background, piece shape, coverage. */
export function readSquares(img, grid) {
  const squares = [];
  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) {
      const bg = cellBackground(img, grid, row, col);
      const mask = new Uint8Array(SIDE * SIDE);
      const lum = new Float32Array(SIDE * SIDE);
      let count = 0, lumSum = 0;
      const x0 = grid.x + col * grid.cw, y0 = grid.y + row * grid.ch;
      for (let v = 0; v < SIDE; v++) {
        for (let u = 0; u < SIDE; u++) {
          const px = x0 + (0.04 + 0.92 * (u + 0.5) / SIDE) * grid.cw;
          const py = y0 + (0.04 + 0.92 * (v + 0.5) / SIDE) * grid.ch;
          const c = bilinear(img, px, py);
          const i = v * SIDE + u;
          if (colorDistance(c, bg) > MASK_THRESHOLD) {
            mask[i] = 1;
            lum[i] = luma(c);
            count++;
            lumSum += lum[i];
          }
        }
      }
      squares.push({ bg, mask, lum, coverage: count / (SIDE * SIDE), meanLum: count ? lumSum / count : 0 });
    }
  }
  return squares;
}

/** Squares whose background differs from their colour class: the last move's highlight. */
export function highlightedSquares(squares) {
  const light = [], dark = [];
  squares.forEach((s, i) => (((i >> 3) + (i & 7)) % 2 === 0 ? light : dark).push(s.bg));
  const means = [medianColor(light), medianColor(dark)];
  const result = [];
  squares.forEach((s, i) => {
    if (colorDistance(s.bg, means[((i >> 3) + (i & 7)) % 2]) > 45) result.push(i);
  });
  return result;
}

// Templates: { key: 'wp' | 'bn' | …, pm: probability each pixel is piece, lum: brightness there }

function emptyTemplate() {
  return { n: 0, pm: new Float32Array(SIDE * SIDE), lum: new Float32Array(SIDE * SIDE) };
}

function addSample(template, square) {
  const n = template.n;
  for (let i = 0; i < SIDE * SIDE; i++) {
    const oldPm = template.pm[i];
    template.pm[i] = (oldPm * n + square.mask[i]) / (n + 1);
    if (square.mask[i]) {
      const weight = oldPm * n;
      template.lum[i] = (template.lum[i] * weight + square.lum[i]) / (weight + 1);
    }
  }
  template.n = n + 1;
}

export function distance(square, template) {
  let total = 0;
  for (let i = 0; i < SIDE * SIDE; i++) {
    const p = template.pm[i];
    if (square.mask[i]) {
      total += 1 - p;
      if (p > 0.3) total += (Math.abs(square.lum[i] - template.lum[i]) / 255) * 0.8;
    } else {
      total += p;
    }
  }
  return total / (SIDE * SIDE);
}

const BACK_RANK = ['r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'];

/** Which board square (index rank * 8 + file, a1 = 0) a screen cell shows. */
export function boardIndex(screenIndex, whiteAtBottom) {
  const row = screenIndex >> 3, col = screenIndex & 7;
  return whiteAtBottom ? (7 - row) * 8 + col : row * 8 + (7 - col);
}

/**
 * If the screen shows the starting position, learns every piece from it and returns
 * { templates, whiteAtBottom }; otherwise null.
 */
export function learnFromStart(squares) {
  for (let i = 0; i < 64; i++) {
    const row = i >> 3;
    const shouldBeOccupied = row <= 1 || row >= 6;
    if ((squares[i].coverage > OCCUPIED) !== shouldBeOccupied) return null;
  }
  const meanLum = (rows) => {
    const values = squares.filter((_, i) => rows.includes(i >> 3)).map((s) => s.meanLum);
    return values.reduce((a, b) => a + b, 0) / values.length;
  };
  const average = (rows, f) => {
    const values = squares.filter((_, i) => rows.includes(i >> 3)).map(f);
    return values.reduce((a, b) => a + b, 0) / values.length;
  };
  const bottom = meanLum([6, 7]), top = meanLum([0, 1]);
  let whiteAtBottom;
  if (Math.abs(bottom - top) >= 25) whiteAtBottom = bottom > top;
  else {
    // Outline-style white pieces: same ink as Black's, but hollow.
    const bottomCover = average([6, 7], (sq) => sq.coverage), topCover = average([0, 1], (sq) => sq.coverage);
    if (Math.abs(bottomCover - topCover) < 0.03) return null;
    whiteAtBottom = bottomCover < topCover;
  }
  const placement = new Array(64).fill(null);
  for (let file = 0; file < 8; file++) {
    placement[file] = { color: 'w', type: BACK_RANK[file] };
    placement[8 + file] = { color: 'w', type: 'p' };
    placement[48 + file] = { color: 'b', type: 'p' };
    placement[56 + file] = { color: 'b', type: BACK_RANK[file] };
  }
  return { templates: learn(squares, placement, whiteAtBottom), whiteAtBottom };
}

/**
 * Learns the pieces on screen given what's really on the board (`placement`: 64 entries indexed
 * a1 = 0, {color, type} or null), merged into `existing` templates.
 */
export function learn(squares, placement, whiteAtBottom, existing = {}) {
  const templates = {};
  for (const [key, t] of Object.entries(existing)) templates[key] = { n: t.n, pm: Float32Array.from(t.pm), lum: Float32Array.from(t.lum) };
  for (let i = 0; i < 64; i++) {
    const piece = placement[boardIndex(i, whiteAtBottom)];
    if (!piece || squares[i].coverage <= OCCUPIED) continue;
    const key = piece.color + piece.type;
    // A fresh lesson replaces the old one for this piece (the piece set may have changed).
    if (!templates[key] || templates[key].fresh !== true) {
      templates[key] = emptyTemplate();
      templates[key].fresh = true;
    }
    addSample(templates[key], squares[i]);
  }
  for (const t of Object.values(templates)) delete t.fresh;
  return templates;
}

/**
 * Identifies every square. Returns { placement (a1 = 0), whiteAtBottom, lastMover } or null when
 * some square doesn't look like any known piece (a hand, an arrow, a piece being dragged…).
 */
export function recognize(squares, templates, previousWhiteAtBottom = true) {
  const keys = Object.keys(templates);
  if (keys.length < 12) return null;
  const screen = new Array(64).fill(null);
  for (let i = 0; i < 64; i++) {
    const square = squares[i];
    let bestKey = null, bestDistance = square.coverage; // distance to "empty"
    if (square.coverage > OCCUPIED * 0.6) {
      for (const key of keys) {
        const d = distance(square, templates[key]);
        if (d < bestDistance) { bestDistance = d; bestKey = key; }
      }
    }
    if (bestDistance > MAX_DISTANCE) return null;
    screen[i] = bestKey ? { color: bestKey[0], type: bestKey[1] } : null;
  }

  // Orientation from the kings: White's king in the bottom half means White is at the bottom.
  let whiteAtBottom = previousWhiteAtBottom;
  const kingRow = (color) => screen.findIndex((p) => p && p.color === color && p.type === 'k') >> 3;
  const wk = kingRow('w'), bk = kingRow('b');
  if (wk >= 4 && bk >= 0 && bk < 4) whiteAtBottom = true;
  else if (wk >= 0 && wk < 4 && bk >= 4) whiteAtBottom = false;

  const placement = new Array(64).fill(null);
  for (let i = 0; i < 64; i++) placement[boardIndex(i, whiteAtBottom)] = screen[i];

  return { placement, whiteAtBottom, lastMover: lastMoverFrom(highlightedSquares(squares), screen) };
}

/**
 * Who just moved, from the last-move highlight, trusted only when it looks like one: the empty
 * square a piece left plus the square it's on now (or a castling king and rook). A tapped,
 * selected piece adds a highlighted square of its own, so it's never mistaken for a move.
 */
export function lastMoverFrom(highlights, screen) {
  const occupied = highlights.map((i) => screen[i]).filter(Boolean);
  const empty = highlights.length - occupied.length;
  if (highlights.length === 2 && occupied.length === 1 && empty === 1) return occupied[0].color;
  const kinds = new Set(occupied.map((p) => p.type));
  if (highlights.length >= 3 && highlights.length <= 4 && empty >= 1 && occupied.length === 2 &&
      kinds.has('k') && kinds.has('r') && occupied[0].color === occupied[1].color) return occupied[0].color;
  return null;
}

/** "rnbqkbnr/pppppppp/…" from a placement (a1 = 0). */
export function placementFen(placement) {
  const rows = [];
  for (let rank = 7; rank >= 0; rank--) {
    let row = '', empty = 0;
    for (let file = 0; file < 8; file++) {
      const p = placement[rank * 8 + file];
      if (!p) { empty++; continue; }
      if (empty) { row += empty; empty = 0; }
      row += p.color === 'w' ? p.type.toUpperCase() : p.type;
    }
    rows.push(row + (empty || ''));
  }
  return rows.join('/');
}

/** Templates to/from something JSON can store. */
export function serializeTemplates(templates) {
  const out = {};
  for (const [key, t] of Object.entries(templates)) {
    out[key] = { n: t.n, pm: Array.from(t.pm, (v) => Math.round(v * 100) / 100), lum: Array.from(t.lum, (v) => Math.round(v)) };
  }
  return out;
}

export function deserializeTemplates(data) {
  const out = {};
  for (const [key, t] of Object.entries(data ?? {})) out[key] = { n: t.n, pm: Float32Array.from(t.pm), lum: Float32Array.from(t.lum) };
  return out;
}
export const _internals = { chains, edgeCandidates, refineAxis };
