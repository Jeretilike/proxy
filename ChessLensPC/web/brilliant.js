// Finding "brilliant" moves the way chess.com describes them: a good piece sacrifice. The move
// must be the engine's best move or almost as good, it must leave a piece (not just a pawn) that
// the opponent can win, and the player must not be worse after it or already completely winning.
//
// Pure functions, no DOM, so they run under Node in the tests. The same rules are ported to the
// Android (Kotlin) and iPhone (Swift) apps.

import { Chess } from './vendor/chess.js';

export const VALUE = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 100 };

/** How far below the best move (in win chance, 0–1) a sacrifice may be and still count. */
export const NEAR_BEST = 0.03;
/** The player must keep at least this win chance after the sacrifice. */
export const MIN_WIN_CHANCE_AFTER = 0.45;
/** Above this evaluation (centipawns) the player is already completely winning. */
export const ALREADY_WINNING_CP = 600;
/** Material (pawn units) the opponent must be able to win for a move to count as a sacrifice. */
export const MIN_SACRIFICE = 2;

/** Win chance for the side to move, 0–1 (the lichess formula). Mate scores are 0 or 1. */
export function winChance(line) {
  if (line.mate != null) return line.mate > 0 ? 1 : 0;
  const cp = line.cp ?? 0;
  return 1 / (1 + Math.exp(-0.00368208 * cp));
}

// ---------------------------------------------------------------------------------------------
// Board as a 64-entry array (index = rank * 8 + file, a1 = 0) of {color, type} or null.

export function squareIndex(name) {
  return (name.charCodeAt(1) - 49) * 8 + (name.charCodeAt(0) - 97);
}

function boardFrom(chess) {
  const squares = new Array(64).fill(null);
  for (const row of chess.board()) {
    for (const cell of row) {
      if (cell) squares[squareIndex(cell.square)] = { color: cell.color, type: cell.type };
    }
  }
  return squares;
}

const KNIGHT = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
const KING = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
const ROOK_DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const BISHOP_DIRS = [[1, 1], [1, -1], [-1, 1], [-1, -1]];

/** Squares holding `color` pieces that attack `target` (pins ignored). */
export function attackers(board, target, color) {
  const result = [];
  const tf = target % 8, tr = target >> 3;
  const at = (f, r) => (f >= 0 && f < 8 && r >= 0 && r < 8 ? r * 8 + f : -1);
  const has = (i, types) => i >= 0 && board[i] && board[i].color === color && types.includes(board[i].type);

  // A white pawn attacks diagonally upwards, so it sits one rank below the target.
  const pr = color === 'w' ? tr - 1 : tr + 1;
  for (const df of [-1, 1]) {
    const i = at(tf + df, pr);
    if (has(i, 'p')) result.push(i);
  }
  for (const [df, dr] of KNIGHT) {
    const i = at(tf + df, tr + dr);
    if (has(i, 'n')) result.push(i);
  }
  for (const [df, dr] of KING) {
    const i = at(tf + df, tr + dr);
    if (has(i, 'k')) result.push(i);
  }
  const slide = (dirs, types) => {
    for (const [df, dr] of dirs) {
      let f = tf + df, r = tr + dr;
      while (f >= 0 && f < 8 && r >= 0 && r < 8) {
        const i = r * 8 + f;
        if (board[i]) {
          if (board[i].color === color && types.includes(board[i].type)) result.push(i);
          break;
        }
        f += df; r += dr;
      }
    }
  };
  slide(ROOK_DIRS, 'rq');
  slide(BISHOP_DIRS, 'bq');
  return result;
}

const other = (color) => (color === 'w' ? 'b' : 'w');

/**
 * Static exchange evaluation: material `side` can gain by capturing on `target` with its least
 * valuable attacker and then trading off optimally (either side may stop). Hidden attackers behind
 * a capturing piece join in because attacks are recomputed after each capture.
 */
export function see(board, target, side) {
  const victim = board[target];
  if (!victim) return 0;
  const from = leastValuable(board, target, side);
  if (from < 0) return 0;
  return Math.max(0, VALUE[victim.type] - seeAfterCapture(board, from, target, side));
}

/** What the opponent of `side` then gains after `side` captures on `target` from `from`. */
function seeAfterCapture(board, from, target, side) {
  const next = board.slice();
  const mover = next[from];
  next[target] = mover;
  next[from] = null;
  // A king may only capture on a square the other side no longer covers.
  if (mover.type === 'k' && attackers(next, target, other(side)).length > 0) return 1000;
  return see(next, target, other(side));
}

function leastValuable(board, target, side) {
  let best = -1;
  for (const i of attackers(board, target, side)) {
    if (best < 0 || VALUE[board[i].type] < VALUE[board[best].type]) best = i;
  }
  return best;
}

/**
 * Material (pawn units) the opponent can win right after `move` is played, net of anything the
 * move itself captured, and which piece they would win. Only legal captures are considered for
 * the opponent's first capture; the trades after it are estimated with `see`.
 */
export function sacrificedMaterial(fen, move) {
  const chess = new Chess(fen);
  const mover = chess.turn();
  let played;
  try {
    played = chess.move(move);
  } catch {
    return { net: 0, piece: null };
  }
  const board = boardFrom(chess);
  let taken = played.captured ? VALUE[played.captured] : 0;
  // A promoted piece only cost the pawn.
  if (played.promotion) taken += VALUE[played.promotion] - VALUE.p;

  let best = 0, piece = null;
  for (const reply of chess.moves({ verbose: true })) {
    if (!reply.captured || reply.captured === 'k') continue;
    const target = squareIndex(reply.to);
    const from = squareIndex(reply.from);
    const victimValue = reply.isEnPassant() ? VALUE.p : VALUE[board[target].type];
    // En passant captures don't land on the victim's square; treat them as winning a pawn outright.
    const gain = reply.isEnPassant()
      ? VALUE.p
      : victimValue - seeAfterCapture(board, from, target, other(mover));
    if (gain > best) {
      best = gain;
      piece = { square: reply.to, type: reply.isEnPassant() ? 'p' : board[target].type };
    }
  }
  return { net: best - taken, piece };
}

/** Is `move` a sacrifice of at least a minor piece's worth (pawn sacrifices don't count)? */
export function isSacrifice(fen, move) {
  const { net, piece } = sacrificedMaterial(fen, move);
  return net >= MIN_SACRIFICE && piece != null && piece.type !== 'p';
}

/**
 * Picks the move to suggest from the engine's lines (MultiPV, best first, scores from the side to
 * move's point of view). Returns { line, brilliant } where `brilliant` is true when the chosen move
 * is a brilliant sacrifice; otherwise `line` is the best move.
 */
export function chooseMove(fen, lines, mode) {
  if (!lines.length) return null;
  const best = lines[0];
  if (mode !== 'brilliant') return { line: best, brilliant: false };

  const bestChance = winChance(best);
  const alreadyWinning = best.mate == null && (best.cp ?? 0) > ALREADY_WINNING_CP;
  let pick = null;
  for (const line of lines) {
    const chance = winChance(line);
    if (bestChance - chance > NEAR_BEST) continue;
    if (chance < MIN_WIN_CHANCE_AFTER) continue;
    // Already completely winning without a sacrifice: nothing brilliant about it, unless the
    // sacrifice forces mate.
    if (alreadyWinning && !(line.mate != null && line.mate > 0)) continue;
    if (!isSacrifice(fen, line.move)) continue;
    if (!pick || chance > winChance(pick)) pick = line;
  }
  return pick ? { line: pick, brilliant: true } : { line: best, brilliant: false };
}

// ---------------------------------------------------------------------------------------------
// Words

const PIECE_NAME = { p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king' };

/** "Bxh7+" → "bishop takes h7, check". */
export function spokenSan(san) {
  if (san.startsWith('O-O-O')) return 'castles queenside' + suffix(san);
  if (san.startsWith('O-O')) return 'castles kingside' + suffix(san);
  const m = san.match(/^([NBRQK])?([a-h])?([1-8])?(x)?([a-h][1-8])(=([NBRQ]))?/);
  if (!m) return san;
  const piece = m[1] ? PIECE_NAME[m[1].toLowerCase()] : 'pawn';
  let words = piece;
  if (!m[1] && m[4]) words = `${m[2]} pawn`;
  else if (m[2] || m[3]) words += ` on ${m[2] ?? ''}${m[3] ?? ''}`;
  words += m[4] ? ` takes ${m[5]}` : ` to ${m[5]}`;
  if (m[7]) words += `, promotes to ${PIECE_NAME[m[7].toLowerCase()]}`;
  return words + suffix(san);
}

function suffix(san) {
  if (san.includes('#')) return ', checkmate';
  if (san.includes('+')) return ', check';
  return '';
}

/** SAN for a UCI move in `fen`, or null if illegal. */
export function sanFor(fen, uci) {
  try {
    const chess = new Chess(fen);
    return chess.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] }).san;
  } catch {
    return null;
  }
}

/** "+1.3", "-0.4", "Mate in 3", "Mated in 2", from the side to move's point of view. */
export function evaluationText(line) {
  if (line.mate != null) return line.mate > 0 ? `Mate in ${line.mate}` : `Mated in ${-line.mate}`;
  const pawns = (line.cp ?? 0) / 100;
  return (pawns > 0 ? '+' : '') + pawns.toFixed(1);
}
