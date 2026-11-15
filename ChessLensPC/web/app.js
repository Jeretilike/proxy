// ChessLens for PC: play both sides on the screen; on your turn Stockfish finds the best move (or
// a brilliant sacrifice in Brilliant mode), draws it on the board and says it out loud.

import { Chess } from './vendor/chess.js';
import { Engine } from './engine.js';
import { chooseMove, spokenSan, sanFor, evaluationText, winChance } from './brilliant.js';
import { ScreenReader } from './screen.js';

const GLYPH = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
const FILES = 'abcdefgh';
const START_FEN = new Chess().fen();

// ---------------------------------------------------------------------------------------------
// Settings (kept in this computer's browser storage)

const DEFAULTS = { mode: 'best', color: 'w', highlight: true, voice: true, speakEval: false, voiceName: '', thinkTime: 2000, multiPv: 6 };
const settings = { ...DEFAULTS, ...load('settings', {}) };

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(`chesslens.${key}`);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(`chesslens.${key}`, JSON.stringify(value));
  } catch {
    // Storage unavailable: settings last for this session only.
  }
}

// ---------------------------------------------------------------------------------------------
// State

const game = new Chess();
const saved = load('game', null);
if (saved?.pgn) {
  try { game.loadPgn(saved.pgn); } catch { game.reset(); }
}
let brilliantPlies = new Set(saved?.brilliantPlies ?? []);
let flipped = settings.color === 'b';
let selected = null;
let suggestion = null; // { line, brilliant, san, fen }
let analysisToken = 0;
let editing = null; // { board: Map(square -> {color,type}), turn, tool }

const engine = new Engine();
let reader = null; // screen reader, set up below
const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------------------------
// Board rendering

function squareAt(row, col) {
  const file = flipped ? 7 - col : col;
  const rank = flipped ? row : 7 - row;
  return FILES[file] + (rank + 1);
}

function squareCenter(square) {
  const file = FILES.indexOf(square[0]);
  const rank = Number(square[1]) - 1;
  const col = flipped ? 7 - file : file;
  const row = flipped ? rank : 7 - rank;
  return { x: col + 0.5, y: row + 0.5 };
}

function pieceAt(square) {
  if (editing) return editing.board.get(square) ?? null;
  return game.get(square) ?? null;
}

function renderBoard() {
  const board = $('board');
  board.textContent = '';
  const history = game.history({ verbose: true });
  const last = editing ? null : history[history.length - 1];
  const targets = new Map();
  if (selected && !editing) {
    for (const move of game.moves({ square: selected, verbose: true })) targets.set(move.to, move);
  }
  let checkSquare = null;
  if (!editing && game.inCheck()) {
    checkSquare = game.findPiece({ type: 'k', color: game.turn() })[0];
  }

  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) {
      const square = squareAt(row, col);
      const cell = document.createElement('div');
      const file = FILES.indexOf(square[0]);
      const rank = Number(square[1]) - 1;
      cell.className = `square ${(file + rank) % 2 === 1 ? 'light' : 'dark'}`;
      cell.dataset.square = square;
      if (last && (last.from === square || last.to === square)) cell.classList.add('last');
      if (selected === square) cell.classList.add('selected');
      if (square === checkSquare) cell.classList.add('check');
      const piece = pieceAt(square);
      if (targets.has(square)) cell.classList.add('target', piece ? 'occupied' : 'empty');
      if (row === 7) cell.append(coord('file', square[0]));
      if (col === 0) cell.append(coord('rank', square[1]));
      if (piece) {
        const span = document.createElement('span');
        span.className = `piece ${piece.color}`;
        span.textContent = GLYPH[piece.type];
        cell.append(span);
      }
      board.append(cell);
    }
  }
  renderArrow();
  updateOverlay();
  renderEvalBar();
}

function coord(kind, text) {
  const span = document.createElement('span');
  span.className = `coord ${kind}`;
  span.textContent = text;
  return span;
}

function renderArrow() {
  const svg = $('arrows');
  svg.textContent = '';
  if (editing || !suggestion || suggestion.fen !== game.fen()) return;
  const move = suggestion.line.move;
  const from = squareCenter(move.slice(0, 2));
  const to = squareCenter(move.slice(2, 4));
  const color = suggestion.brilliant ? '#1baca6' : '#81b64c';
  const ns = 'http://www.w3.org/2000/svg';
  const dx = to.x - from.x, dy = to.y - from.y;
  const length = Math.hypot(dx, dy);
  const ux = dx / length, uy = dy / length;
  const head = 0.42;
  const end = { x: to.x - ux * head, y: to.y - uy * head };
  const line = document.createElementNS(ns, 'line');
  line.setAttribute('x1', from.x + ux * 0.15);
  line.setAttribute('y1', from.y + uy * 0.15);
  line.setAttribute('x2', end.x);
  line.setAttribute('y2', end.y);
  line.setAttribute('stroke', color);
  line.setAttribute('stroke-width', '0.18');
  line.setAttribute('stroke-linecap', 'round');
  line.setAttribute('opacity', '0.85');
  const tip = document.createElementNS(ns, 'polygon');
  const px = -uy, py = ux;
  tip.setAttribute('points', [
    `${to.x - ux * 0.08},${to.y - uy * 0.08}`,
    `${end.x + px * 0.26},${end.y + py * 0.26}`,
    `${end.x - px * 0.26},${end.y - py * 0.26}`,
  ].join(' '));
  tip.setAttribute('fill', color);
  tip.setAttribute('opacity', '0.85');
  svg.append(line, tip);
}

let evalWhite = { cp: 0, mate: null };

function renderEvalBar() {
  const bar = $('evalBar');
  bar.classList.toggle('flipped', flipped);
  const chance = winChance(evalWhite);
  $('evalFill').style.height = `${Math.round(chance * 100)}%`;
  const label = $('evalLabel');
  const text = evalWhite.mate != null
    ? `M${Math.abs(evalWhite.mate)}`
    : Math.abs((evalWhite.cp ?? 0) / 100).toFixed(1);
  label.textContent = text;
  // Show the number on the side that's ahead.
  const whiteAhead = chance >= 0.5;
  label.classList.toggle('top', whiteAhead === flipped);
}

function syncEvalBarHeight() {
  $('evalBar').style.height = `${$('board').getBoundingClientRect().height}px`;
}

// ---------------------------------------------------------------------------------------------
// Moving pieces (click or drag)

let drag = null;

function onPointerDown(event) {
  const cell = event.target.closest('.square');
  if (!cell || event.button > 0) {
    if (cell && editing && event.button === 2) {
      editing.board.delete(cell.dataset.square);
      renderBoard();
    }
    return;
  }
  const square = cell.dataset.square;

  if (editing) {
    const tool = editing.tool;
    if (!tool || tool === 'x') editing.board.delete(square);
    else {
      const existing = editing.board.get(square);
      const piece = { color: tool[0], type: tool[1] };
      if (existing && existing.color === piece.color && existing.type === piece.type) editing.board.delete(square);
      else editing.board.set(square, piece);
    }
    renderBoard();
    return;
  }

  const piece = game.get(square);
  if (selected && selected !== square) {
    const legal = game.moves({ square: selected, verbose: true }).find((m) => m.to === square);
    if (legal) {
      tryMove(selected, square);
      return;
    }
  }
  if (piece && piece.color === game.turn()) {
    selected = square;
    renderBoard();
    startDrag(event, square);
  } else {
    selected = null;
    renderBoard();
  }
}

function startDrag(event, square) {
  const source = $('board').querySelector(`[data-square="${square}"] .piece`);
  if (!source) return;
  const size = $('board').getBoundingClientRect().width / 8 * 0.8;
  const ghost = source.cloneNode(true);
  ghost.classList.add('dragging');
  ghost.style.setProperty('--drag-size', `${size}px`);
  ghost.style.left = `${event.clientX}px`;
  ghost.style.top = `${event.clientY}px`;
  document.body.append(ghost);
  source.classList.add('ghost');
  drag = { square, ghost, source, moved: false, x: event.clientX, y: event.clientY };
}

function onPointerMove(event) {
  if (!drag) return;
  if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) > 4) drag.moved = true;
  drag.ghost.style.left = `${event.clientX}px`;
  drag.ghost.style.top = `${event.clientY}px`;
}

function onPointerUp(event) {
  if (!drag) return;
  const { square, ghost, source, moved } = drag;
  drag = null;
  ghost.remove();
  source.classList.remove('ghost');
  if (!moved) return;
  const target = document.elementFromPoint(event.clientX, event.clientY)?.closest('.square');
  if (target && target.dataset.square !== square) tryMove(square, target.dataset.square);
}

async function tryMove(from, to) {
  const candidates = game.moves({ square: from, verbose: true }).filter((m) => m.to === to);
  if (!candidates.length) {
    selected = null;
    renderBoard();
    return;
  }
  let promotion;
  if (candidates.some((m) => m.promotion)) {
    promotion = await askPromotion(game.turn());
    if (!promotion) {
      renderBoard();
      return;
    }
  }
  makeMove({ from, to, promotion });
}

function askPromotion(color) {
  return new Promise((resolve) => {
    const box = $('promotion');
    box.textContent = '';
    for (const type of ['q', 'r', 'b', 'n']) {
      const button = document.createElement('button');
      button.textContent = GLYPH[type];
      button.style.color = color === 'w' ? '#fff' : '#111';
      button.style.textShadow = color === 'w' ? '0 0 1px #000, 0 0 1px #000' : 'none';
      button.onclick = () => { box.classList.add('hidden'); resolve(type); };
      box.append(button);
    }
    const cancel = (event) => {
      if (event.target === box) { box.classList.add('hidden'); resolve(null); }
    };
    box.onclick = cancel;
    box.classList.remove('hidden');
  });
}

function makeMove(move) {
  const fenBefore = game.fen();
  let played;
  try {
    played = game.move(move);
  } catch {
    return;
  }
  if (suggestion && suggestion.fen === fenBefore && suggestion.brilliant && suggestion.line.move === played.lan) {
    brilliantPlies.add(game.history().length - 1);
  }
  selected = null;
  positionChanged();
}

// ---------------------------------------------------------------------------------------------
// Analysis

function positionChanged() {
  suggestion = null;
  persistGame();
  renderBoard();
  renderMoves();
  renderSuggestion();
  analyze();
}

async function analyze() {
  const token = ++analysisToken;
  engine.stop();
  const fen = game.fen();

  if (game.isGameOver()) {
    setStatus(gameOverText());
    renderSuggestion();
    return;
  }
  const yourTurn = game.turn() === settings.color;
  if (!yourTurn) {
    setStatus(`${colorName(game.turn())} to move: play your opponent's move on the board.`);
    renderSuggestion();
    return;
  }
  setStatus('Thinking…');
  renderSuggestion('thinking');
  const mode = settings.mode;
  try {
    const lines = await engine.analyze(fen, {
      movetime: settings.thinkTime,
      multipv: mode === 'brilliant' ? settings.multiPv : 1,
      onUpdate: (partial) => {
        if (token === analysisToken && partial[0]) setWhiteEval(partial[0], fen);
      },
    });
    if (token !== analysisToken) return;
    $('engineName').textContent = engine.name;
    if (!lines.length) {
      setStatus(gameOverText());
      return;
    }
    const choice = chooseMove(fen, lines, mode);
    suggestion = { ...choice, fen, mode, san: sanFor(fen, choice.line.move), bestSan: sanFor(fen, lines[0].move) };
    setWhiteEval(choice.line, fen);
    setStatus('Your move.');
    renderBoard();
    renderSuggestion();
    speakSuggestion();
  } catch (error) {
    if (token !== analysisToken) return;
    setStatus(error.message, true);
    renderSuggestion();
  }
}

function setWhiteEval(line, fen) {
  const sign = fen.split(' ')[1] === 'w' ? 1 : -1;
  evalWhite = { cp: line.cp != null ? line.cp * sign : null, mate: line.mate != null ? line.mate * sign : null };
  renderEvalBar();
}

function gameOverText() {
  if (game.isCheckmate()) return `Checkmate. ${colorName(game.turn() === 'w' ? 'b' : 'w')} wins.`;
  if (game.isStalemate()) return 'Stalemate: a draw.';
  if (game.isThreefoldRepetition()) return 'Draw by repetition.';
  if (game.isInsufficientMaterial()) return 'Draw: not enough material to mate.';
  if (game.isDraw()) return 'Draw (fifty-move rule).';
  return 'The game is over.';
}

const colorName = (c) => (c === 'w' ? 'White' : 'Black');

function renderSuggestion(state) {
  const card = $('suggestion');
  const current = suggestion && suggestion.fen === game.fen() ? suggestion : null;
  card.classList.toggle('brilliant', Boolean(current?.brilliant));
  $('badge').classList.toggle('hidden', !current?.brilliant);
  $('playIt').disabled = !current;
  $('sayAgain').disabled = !current;
  if (current) {
    $('suggestionMove').textContent = current.san ?? current.line.move;
    $('suggestionEval').textContent = evaluationText(current.line);
    $('suggestionNote').textContent = noteFor(current);
  } else {
    $('suggestionMove').textContent = state === 'thinking' ? '…' : '—';
    $('suggestionEval').textContent = '';
    $('suggestionNote').textContent = state === 'thinking'
      ? (settings.mode === 'brilliant' ? 'Looking for a brilliant move…' : 'Finding the best move…')
      : (game.isGameOver() ? '' : game.turn() === settings.color ? '' : 'Waiting for your opponent’s move.');
  }
}

function noteFor(s) {
  if (s.brilliant) {
    return s.san === s.bestSan
      ? 'Brilliant: a sacrifice, and the best move.'
      : `Brilliant: a sacrifice almost as good as the best move (${s.bestSan}).`;
  }
  if (s.mode === 'brilliant') return 'No brilliant move here. This is the best move.';
  return 'Best move.';
}

function speakSuggestion() {
  if (!suggestion || !settings.voice) return;
  const words = spokenSan(suggestion.san ?? suggestion.line.move);
  let text;
  if (suggestion.brilliant) text = `Brilliant move! ${words}.`;
  else if (suggestion.mode === 'brilliant') text = `No brilliant move. Best move: ${words}.`;
  else text = `Best move: ${words}.`;
  if (settings.speakEval) text += ` ${spokenEvaluation(suggestion.line)}.`;
  say(text);
}

function spokenEvaluation(line) {
  if (line.mate != null) return line.mate > 0 ? `You have mate in ${line.mate}` : `You get mated in ${-line.mate}`;
  const pawns = Math.abs(line.cp / 100).toFixed(1);
  if (Math.abs(line.cp) < 30) return 'The position is equal';
  return line.cp > 0 ? `You're ahead by ${pawns}` : `You're behind by ${pawns}`;
}

function say(text) {
  if (!('speechSynthesis' in window)) return;
  speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  const voice = speechSynthesis.getVoices().find((v) => v.name === settings.voiceName);
  if (voice) utterance.voice = voice;
  utterance.rate = 1.02;
  speechSynthesis.speak(utterance);
}

function setStatus(text, warn = false) {
  const status = $('status');
  status.textContent = text;
  status.classList.toggle('warn', warn);
}

function renderMoves() {
  const list = $('moves');
  list.textContent = '';
  const history = game.history();
  const startFen = game.getHeaders().FEN;
  const blackFirst = startFen && startFen.split(' ')[1] === 'b';
  const plies = blackFirst ? ['…', ...history] : history;
  const offset = blackFirst ? 1 : 0;
  for (let i = 0; i < plies.length; i += 2) {
    const li = document.createElement('li');
    for (const ply of [i, i + 1]) {
      if (ply >= plies.length) continue;
      const span = document.createElement('span');
      const index = ply - offset;
      const brilliant = brilliantPlies.has(index);
      span.textContent = plies[ply] + (brilliant ? '!!' : '');
      if (brilliant) span.className = 'brilliant-move';
      li.append(span);
    }
    list.append(li);
  }
  if (startFen) list.start = Number(startFen.split(' ')[5]) || 1;
  else list.removeAttribute('start');
  list.scrollTop = list.scrollHeight;
}

function persistGame() {
  save('game', { pgn: game.pgn(), brilliantPlies: [...brilliantPlies] });
}

// ---------------------------------------------------------------------------------------------
// Controls

function setSegmented(groupId, attribute, value) {
  for (const button of $(groupId).querySelectorAll('button')) {
    button.setAttribute('aria-checked', String(button.dataset[attribute] === value));
  }
}

function renderModeHelp() {
  $('modeHelp').textContent = settings.mode === 'brilliant'
    ? 'Looks for a piece sacrifice that is the best move or almost as good, the kind chess.com marks “!!”. If there isn’t one, you get the best move.'
    : 'Always the strongest move Stockfish finds.';
}

function bindControls() {
  $('board').addEventListener('pointerdown', onPointerDown);
  $('board').addEventListener('contextmenu', (e) => e.preventDefault());
  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);

  $('modeSwitch').addEventListener('click', (e) => {
    const mode = e.target.closest('button')?.dataset.mode;
    if (!mode || mode === settings.mode) return;
    settings.mode = mode;
    save('settings', settings);
    setSegmented('modeSwitch', 'mode', mode);
    renderModeHelp();
    suggestion = null;
    renderBoard();
    analyze();
  });

  $('colorSwitch').addEventListener('click', (e) => {
    const color = e.target.closest('button')?.dataset.color;
    if (!color || color === settings.color) return;
    settings.color = color;
    save('settings', settings);
    setSegmented('colorSwitch', 'color', color);
    flipped = color === 'b';
    suggestion = null;
    renderBoard();
    analyze();
  });

  $('playIt').addEventListener('click', playSuggestion);
  $('sayAgain').addEventListener('click', speakSuggestion);
  $('undo').addEventListener('click', undo);
  $('newGame').addEventListener('click', () => {
    game.reset();
    brilliantPlies = new Set();
    evalWhite = { cp: 0, mate: null };
    positionChanged();
  });
  $('flip').addEventListener('click', () => { flipped = !flipped; renderBoard(); });
  $('setPosition').addEventListener('click', openPositionDialog);
  $('openSettings').addEventListener('click', openSettings);

  document.addEventListener('keydown', (e) => {
    if (document.querySelector('dialog[open]') || e.target.matches('input, textarea, select')) return;
    if (e.code === 'Space') { e.preventDefault(); playSuggestion(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
    else if (e.key.toLowerCase() === 'f' && !e.ctrlKey) { flipped = !flipped; renderBoard(); }
  });

  new ResizeObserver(syncEvalBarHeight).observe($('board'));
}

function playSuggestion() {
  if (editing || !suggestion || suggestion.fen !== game.fen()) return;
  const m = suggestion.line.move;
  makeMove({ from: m.slice(0, 2), to: m.slice(2, 4), promotion: m[4] });
}

function undo() {
  if (editing || !game.undo()) return;
  brilliantPlies.delete(game.history().length);
  positionChanged();
}

// ---------------------------------------------------------------------------------------------
// Set position: FEN / PGN, or edit by hand

function openPositionDialog() {
  const dialog = $('positionDialog');
  $('positionText').value = '';
  $('positionError').textContent = '';
  dialog.showModal();
}

$('positionDialog').addEventListener('close', () => {
  const value = $('positionDialog').returnValue;
  if (value === 'edit') startEditing();
});

$('loadPosition').addEventListener('click', (e) => {
  const text = $('positionText').value.trim();
  if (!text) return;
  const next = new Chess();
  try {
    if (/^\s*([rnbqkpRNBQKP1-8]+\/){7}[rnbqkpRNBQKP1-8]+/.test(text)) next.load(fenWithDefaults(text));
    else next.loadPgn(text);
  } catch (error) {
    e.preventDefault();
    $('positionError').textContent = `That isn't a position I can read: ${error.message}`;
    return;
  }
  game.loadPgn(next.pgn());
  brilliantPlies = new Set();
  positionChanged();
});

function fenWithDefaults(text) {
  const parts = text.trim().split(/\s+/);
  const defaults = ['', 'w', '-', '-', '0', '1'];
  for (let i = parts.length; i < 6; i++) parts.push(defaults[i]);
  return parts.join(' ');
}

function startEditing() {
  const board = new Map();
  for (const row of game.board()) for (const cell of row) if (cell) board.set(cell.square, { color: cell.color, type: cell.type });
  editing = { board, turn: game.turn(), tool: 'wq' };
  selected = null;
  $('editBar').classList.remove('hidden');
  setSegmented('editTurn', 'turn', editing.turn);
  renderPalette();
  renderBoard();
}

function renderPalette() {
  const palette = $('palette');
  palette.textContent = '';
  for (const color of ['w', 'b']) {
    for (const type of ['k', 'q', 'r', 'b', 'n', 'p']) {
      const button = document.createElement('button');
      button.className = color;
      button.textContent = GLYPH[type];
      button.title = `${colorName(color)} ${({ k: 'king', q: 'queen', r: 'rook', b: 'bishop', n: 'knight', p: 'pawn' })[type]}`;
      button.setAttribute('aria-pressed', String(editing.tool === color + type));
      button.onclick = () => { editing.tool = color + type; renderPalette(); };
      palette.append(button);
    }
  }
  const eraser = document.createElement('button');
  eraser.textContent = '✕';
  eraser.title = 'Clear squares';
  eraser.setAttribute('aria-pressed', String(editing.tool === 'x'));
  eraser.onclick = () => { editing.tool = 'x'; renderPalette(); };
  palette.append(eraser);
}

$('editTurn').addEventListener('click', (e) => {
  const turn = e.target.closest('button')?.dataset.turn;
  if (!turn || !editing) return;
  editing.turn = turn;
  setSegmented('editTurn', 'turn', turn);
});
$('editClear').addEventListener('click', () => { editing.board.clear(); renderBoard(); });
$('editStart').addEventListener('click', () => {
  const start = new Chess();
  editing.board.clear();
  for (const row of start.board()) for (const cell of row) if (cell) editing.board.set(cell.square, { color: cell.color, type: cell.type });
  editing.turn = 'w';
  setSegmented('editTurn', 'turn', 'w');
  renderBoard();
});
$('editCancel').addEventListener('click', stopEditing);
$('editDone').addEventListener('click', () => {
  const fen = editedFen();
  const next = new Chess();
  try {
    next.load(fen);
  } catch (error) {
    setStatus(`That position isn't legal: ${error.message}`, true);
    return;
  }
  game.load(fen);
  brilliantPlies = new Set();
  stopEditing();
  positionChanged();
});

function stopEditing() {
  editing = null;
  $('editBar').classList.add('hidden');
  renderBoard();
}

function editedFen() {
  const rows = [];
  for (let rank = 8; rank >= 1; rank--) {
    let row = '', empty = 0;
    for (const file of FILES) {
      const piece = editing.board.get(file + rank);
      if (!piece) { empty++; continue; }
      if (empty) { row += empty; empty = 0; }
      row += piece.color === 'w' ? piece.type.toUpperCase() : piece.type;
    }
    rows.push(row + (empty || ''));
  }
  // Castling is allowed when king and rook stand on their starting squares.
  const at = (sq, color, type) => {
    const p = editing.board.get(sq);
    return p && p.color === color && p.type === type;
  };
  let castling = '';
  if (at('e1', 'w', 'k')) { if (at('h1', 'w', 'r')) castling += 'K'; if (at('a1', 'w', 'r')) castling += 'Q'; }
  if (at('e8', 'b', 'k')) { if (at('h8', 'b', 'r')) castling += 'k'; if (at('a8', 'b', 'r')) castling += 'q'; }
  return `${rows.join('/')} ${editing.turn} ${castling || '-'} - 0 1`;
}

// ---------------------------------------------------------------------------------------------
// Settings dialog

function openSettings() {
  $('voice').checked = settings.voice;
  $('highlight').checked = settings.highlight;
  $('speakEval').checked = settings.speakEval;
  $('thinkTime').value = String(settings.thinkTime);
  $('multiPv').value = String(settings.multiPv);
  fillVoices();
  $('settingsDialog').showModal();
}

function fillVoices() {
  const select = $('voiceName');
  const voices = 'speechSynthesis' in window ? speechSynthesis.getVoices() : [];
  select.textContent = '';
  const auto = new Option('Default voice', '');
  select.append(auto);
  for (const v of voices.filter((v) => v.lang.startsWith('en'))) select.append(new Option(`${v.name} (${v.lang})`, v.name));
  select.value = settings.voiceName;
}
if ('speechSynthesis' in window) speechSynthesis.addEventListener?.('voiceschanged', fillVoices);

$('settingsDialog').addEventListener('close', () => {
  const before = { thinkTime: settings.thinkTime, multiPv: settings.multiPv };
  settings.voice = $('voice').checked;
  settings.highlight = $('highlight').checked;
  updateOverlay();
  settings.speakEval = $('speakEval').checked;
  settings.voiceName = $('voiceName').value;
  settings.thinkTime = Number($('thinkTime').value);
  settings.multiPv = Number($('multiPv').value);
  save('settings', settings);
  if (before.thinkTime !== settings.thinkTime || before.multiPv !== settings.multiPv) analyze();
});

// ---------------------------------------------------------------------------------------------
// Reading the board from the screen

let overlaySent = 'null';

/** Outlines the suggested move on the chess site itself (when reading a whole screen). */
function updateOverlay() {
  if (!reader || !window.chesslensDesktop) return;
  let payload = null;
  const current = suggestion && suggestion.fen === game.fen() ? suggestion : null;
  if (settings.highlight && current && reader.running && !editing) {
    const move = current.line.move;
    const from = reader.squareOnScreen(move.slice(0, 2));
    const to = reader.squareOnScreen(move.slice(2, 4));
    if (from && to) payload = { display: reader.geometry.display, from, to, brilliant: current.brilliant };
  }
  const key = JSON.stringify(payload);
  if (key === overlaySent) return;
  overlaySent = key;
  window.chesslensDesktop.highlight(payload);
}

reader = new ScreenReader({
  preview: $('screenPreview'),
  onStatus: (text) => { $('screenStatus').textContent = text; },
  onPosition: followScreen,
  onFrame: () => updateOverlay(),
});

/** Brings this board in line with the board read from the screen. */
function followScreen({ placement, placementFen: seen, whiteAtBottom, lastMover }) {
  if (editing) return;
  // On chess sites your own pieces are at the bottom.
  const bottom = whiteAtBottom ? 'w' : 'b';
  if (settings.color !== bottom) {
    settings.color = bottom;
    save('settings', settings);
    setSegmented('colorSwitch', 'color', bottom);
  }
  flipped = !whiteAtBottom;

  const current = game.fen().split(' ')[0];
  if (seen === current) {
    // Same pieces; fix the side to move if the highlight says otherwise.
    if (lastMover && game.turn() === lastMover) loadScreenPosition(seen, lastMover === 'w' ? 'b' : 'w', placement);
    else renderBoard();
    return;
  }
  const path = movesBetween(game.fen(), seen);
  if (path) {
    for (const move of path) game.move(move);
    selected = null;
    positionChanged();
    return;
  }
  const turn = lastMover ? (lastMover === 'w' ? 'b' : 'w') : game.turn();
  loadScreenPosition(seen, turn, placement);
}

/** One or two legal moves that lead from `fen` to the placement seen on screen, if any. */
function movesBetween(fen, seen) {
  const chess = new Chess(fen);
  for (const first of chess.moves({ verbose: true })) {
    chess.move(first);
    if (chess.fen().split(' ')[0] === seen) return [first.lan];
    for (const second of chess.moves({ verbose: true })) {
      chess.move(second);
      const match = chess.fen().split(' ')[0] === seen;
      chess.undo();
      if (match) { chess.undo(); return [first.lan, second.lan]; }
    }
    chess.undo();
  }
  return null;
}

function loadScreenPosition(seen, turn, placement) {
  const at = (sq, color, type) => {
    const p = placement[(Number(sq[1]) - 1) * 8 + FILES.indexOf(sq[0])];
    return p && p.color === color && p.type === type;
  };
  let castling = '';
  if (at('e1', 'w', 'k')) { if (at('h1', 'w', 'r')) castling += 'K'; if (at('a1', 'w', 'r')) castling += 'Q'; }
  if (at('e8', 'b', 'k')) { if (at('h8', 'b', 'r')) castling += 'k'; if (at('a8', 'b', 'r')) castling += 'q'; }
  for (const side of [turn, turn === 'w' ? 'b' : 'w']) {
    try {
      game.load(`${seen} ${side} ${castling || '-'} - 0 1`);
      brilliantPlies = new Set();
      selected = null;
      positionChanged();
      return;
    } catch {
      // Try the other side to move (e.g. the side not to move can't be in check).
    }
  }
  $('screenStatus').textContent = 'The position on screen isn’t a legal chess position. Is the whole board visible?';
}

async function fillScreenSources() {
  const select = $('screenSource');
  const sources = await reader.sources().catch(() => null);
  if (!sources) {
    select.classList.add('hidden');
    return;
  }
  const previous = select.value || load('screenSource', '');
  select.textContent = '';
  // Browser windows showing a chess site first, then the other windows, then whole screens.
  // Whole screens first (needed for the on-screen highlights), then chess sites, then the rest.
  const rank = (s) => (s.screen ? 0 : /chess|lichess/i.test(s.name) ? 1 : 2);
  sources.sort((a, b) => rank(a) - rank(b));
  for (const s of sources) select.append(new Option(s.screen ? `Screen: ${s.name}` : s.name, s.id));
  if ([...select.options].some((o) => o.value === previous)) select.value = previous;
}

$('screenSource').addEventListener('pointerenter', fillScreenSources);
$('screenSource').addEventListener('change', () => {
  save('screenSource', $('screenSource').value);
  if (reader.running) startReading();
});

async function startReading() {
  try {
    await reader.start($('screenSource').value || null);
    $('screenToggle').textContent = 'Stop';
    $('screenCard').classList.add('on');
    renderMiniBar();
  } catch (error) {
    $('screenStatus').textContent = error.name === 'NotAllowedError'
      ? 'Screen reading was not allowed.'
      : `Couldn’t read the screen: ${error.message}`;
  }
}

$('screenToggle').addEventListener('click', () => {
  if (reader.running) {
    reader.stop('Stopped.');
    $('screenToggle').textContent = 'Start';
    $('screenCard').classList.remove('on');
    renderMiniBar();
  } else {
    startReading();
  }
});

// ---------------------------------------------------------------------------------------------
// Mini mode

function setMini(on) {
  document.body.classList.toggle('mini', on);
  window.chesslensDesktop?.mini(on);
  save('mini', on);
  renderMiniBar();
}

function renderMiniBar() {
  const brilliant = settings.mode === 'brilliant';
  $('miniMode').textContent = brilliant ? 'Brilliant !!' : 'Best';
  $('miniMode').classList.toggle('brilliant', brilliant);
  $('miniRead').textContent = reader.running ? 'Reading' : 'Read screen';
  $('miniRead').classList.toggle('on', reader.running);
}

$('miniToggle').addEventListener('click', () => setMini(true));
$('miniExpand').addEventListener('click', () => setMini(false));
$('miniMode').addEventListener('click', () => {
  const next = settings.mode === 'brilliant' ? 'best' : 'brilliant';
  $('modeSwitch').querySelector(`[data-mode="${next}"]`).click();
  renderMiniBar();
});
$('miniRead').addEventListener('click', async () => {
  $('screenToggle').click();
  setTimeout(renderMiniBar, 400);
});

$('learnPieces').addEventListener('click', () => {
  if (!reader.running || !reader.squares) {
    $('screenStatus').textContent = 'Start reading the screen first, with the board visible.';
    return;
  }
  const placement = new Array(64).fill(null);
  for (const row of game.board()) for (const cell of row) {
    if (cell) placement[(Number(cell.square[1]) - 1) * 8 + FILES.indexOf(cell.square[0])] = { color: cell.color, type: cell.type };
  }
  reader.learnPosition(placement, settings.color === 'w');
  $('screenStatus').textContent = 'Learned the pieces from the board as set up here. Make sure every piece type is on the board.';
});

$('forgetPieces').addEventListener('click', () => {
  reader.forgetPieces();
  $('screenStatus').textContent = 'Forgot the pieces. Show a starting position to learn them again.';
});

fillScreenSources();
renderMiniBar();

// ---------------------------------------------------------------------------------------------
// Start

setSegmented('modeSwitch', 'mode', settings.mode);
setSegmented('colorSwitch', 'color', settings.color);
renderModeHelp();
bindControls();
renderBoard();
renderMoves();
renderSuggestion();
syncEvalBarHeight();
setStatus('Starting Stockfish…');
engine.start()
  .then(() => {
    $('engineName').textContent = engine.name;
    analyze();
  })
  .catch((error) => setStatus(error.message, true));

// For the automated tests.
window.chesslens = { game, engine, settings, reader, followScreen, get suggestion() { return suggestion; }, makeMove, analyze, positionChanged };
