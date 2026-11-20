import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Chess } from '../web/vendor/chess.js';
import { see, squareIndex, sacrificedMaterial, isSacrifice, chooseMove, spokenSan, winChance } from '../web/brilliant.js';

function board(fen) {
  const c = new Chess(fen);
  const b = new Array(64).fill(null);
  for (const row of c.board()) for (const s of row) if (s) b[squareIndex(s.square)] = { color: s.color, type: s.type };
  return b;
}

test('static exchange evaluation', () => {
  // Pawn takes undefended knight: +3.
  assert.equal(see(board('4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1'), squareIndex('d5'), 'w'), 3);
  // Queen takes a pawn defended by a pawn: not worth it (0, may stop).
  assert.equal(see(board('4k3/8/2p5/3p4/8/8/3Q4/4K3 w - - 0 1'), squareIndex('d5'), 'w'), 0);
  // Rook takes a knight defended by a rook, with a second rook behind it (x-ray): wins the knight.
  assert.equal(see(board('3r2k1/8/8/3n4/8/8/3R4/3RK3 w - - 0 1'), squareIndex('d5'), 'w'), 3);
  // King can't take a defended piece.
  assert.equal(see(board('4k3/8/8/8/8/2b5/3n4/4K3 w - - 0 1'), squareIndex('d2'), 'w'), 0);
});

test('sacrifice detection', () => {
  // Légal trap: 5.Nxe5 leaves the queen to Bxd1.
  const legal = 'rn1qkbnr/ppp2p1p/3p2p1/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5';
  assert.ok(isSacrifice(legal, 'f3e5'));
  // Plain developing moves aren't.
  assert.ok(!isSacrifice(legal, 'e1g1'));
  assert.ok(!isSacrifice('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', 'g1f3'));
  // Even trade (bishop takes knight, pawn recaptures) is not a sacrifice.
  assert.ok(!isSacrifice('r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3', 'b5c6'));
  // Greek gift Bxh7+ (bishop for pawn).
  const greek = 'rnbq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 0 7';
  assert.equal(sacrificedMaterial(greek, 'd3h7').net, 2);
  assert.ok(isSacrifice(greek, 'd3h7'));
  // A pawn sacrifice (gambit) doesn't count.
  assert.ok(!isSacrifice('rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2', 'f2f4'));
  // Promoting where the new queen is taken only costs a pawn.
  assert.equal(sacrificedMaterial('3r2k1/4P3/8/8/8/8/8/6K1 w - - 0 1', 'e7e8q').net, 1);
});

test('choosing a brilliant move', () => {
  const legal = 'rn1qkbnr/ppp2p1p/3p2p1/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5';
  const lines = [
    { move: 'e1g1', cp: 80 },
    { move: 'f3e5', cp: 60 },
    { move: 'h2h3', cp: 40 },
  ];
  assert.deepEqual(chooseMove(legal, lines, 'best'), { line: lines[0], brilliant: false });
  assert.deepEqual(chooseMove(legal, lines, 'brilliant'), { line: lines[1], brilliant: true });
  // Too much worse than the best move: not suggested.
  const worse = [{ move: 'e1g1', cp: 150 }, { move: 'f3e5', cp: -100 }];
  assert.deepEqual(chooseMove(legal, worse, 'brilliant'), { line: worse[0], brilliant: false });
  // Already completely winning: a sacrifice that doesn't mate isn't brilliant.
  const winning = [{ move: 'e1g1', cp: 900 }, { move: 'f3e5', cp: 890 }];
  assert.equal(chooseMove(legal, winning, 'brilliant').brilliant, false);
  // ...but a sacrifice that forces mate is.
  const mating = [{ move: 'f3e5', mate: 4 }, { move: 'e1g1', cp: 900 }];
  assert.deepEqual(chooseMove(legal, mating, 'brilliant'), { line: mating[0], brilliant: true });
  assert.ok(winChance({ cp: 0 }) === 0.5);
});

test('spoken moves', () => {
  assert.equal(spokenSan('Nf3'), 'knight to f3');
  assert.equal(spokenSan('Bxh7+'), 'bishop takes h7, check');
  assert.equal(spokenSan('exd5'), 'e pawn takes d5');
  assert.equal(spokenSan('e8=Q#'), 'pawn to e8, promotes to queen, checkmate');
  assert.equal(spokenSan('O-O'), 'castles kingside');
  assert.equal(spokenSan('Rad1'), 'rook on a to d1');
  assert.equal(spokenSan('Qh4xe1'), 'queen on h4 takes e1');
});

import { lastMoverFrom } from '../web/screen-vision.js';

test('whose turn from the last-move highlight', () => {
  const screen = new Array(64).fill(null);
  screen[36] = { color: 'w', type: 'p' }; // the pawn that moved
  screen[60] = { color: 'w', type: 'n' }; // a piece the player tapped
  assert.equal(lastMoverFrom([52, 36], screen), 'w'); // empty square + moved piece
  assert.equal(lastMoverFrom([52, 36, 60], screen), null); // plus a selected piece: not trusted
  assert.equal(lastMoverFrom([60], screen), null); // only a selection
  const castle = new Array(64).fill(null);
  castle[62] = { color: 'w', type: 'k' }; castle[61] = { color: 'w', type: 'r' };
  assert.equal(lastMoverFrom([60, 62, 63, 61], castle), 'w');
});
