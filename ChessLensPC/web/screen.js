// Watches a screen or window (a chess site in your browser), finds the board on it and reads the
// position a few times a second. Everything happens on this computer; no images leave it.

import {
  findBoard, readSquares, learnFromStart, learn, recognize, placementFen,
  serializeTemplates, deserializeTemplates,
} from './screen-vision.js';

const INTERVAL = 500;
const MAX_WIDTH = 2560;

export class ScreenReader {
  constructor({ onPosition, onStatus, onFrame, preview }) {
    this.onPosition = onPosition;
    this.onFrame = onFrame;
    this.onStatus = onStatus;
    this.preview = preview;
    this.templates = loadTemplates();
    this.grid = null;
    this.squares = null;
    this.whiteAtBottom = true;
    this.pending = null;
    this.lastEmitted = null;
    this.stream = null;
    this.timer = null;
    this.canvas = document.createElement('canvas');
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true });
    this.video = document.createElement('video');
    this.video.muted = true;
  }

  get running() { return Boolean(this.stream); }
  get knowsPieces() { return Object.keys(this.templates).length >= 12; }

  /** Screens and windows to choose from (desktop app only), or null in a browser. */
  async sources() {
    if (!window.chesslensDesktop) return null;
    return window.chesslensDesktop.listSources();
  }

  async start(sourceId) {
    this.stop();
    if (window.chesslensDesktop && sourceId) await window.chesslensDesktop.chooseSource(sourceId);
    this.stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 5 }, audio: false });
    this.stream.getVideoTracks()[0].addEventListener('ended', () => this.stop('Screen reading stopped.'));
    this.video.srcObject = this.stream;
    await this.video.play();
    this.geometry = window.chesslensDesktop ? await window.chesslensDesktop.geometry() : null;
    this.grid = null;
    this.pending = null;
    this.lastEmitted = null;
    this.onStatus('Looking for a chess board…');
    this.timer = setInterval(() => this.tick(), INTERVAL);
  }

  stop(message) {
    clearInterval(this.timer);
    this.timer = null;
    if (this.stream) for (const track of this.stream.getTracks()) track.stop();
    this.stream = null;
    this.grid = null;
    this.video.srcObject = null;
    window.chesslensDesktop?.highlight(null);
    if (message) this.onStatus(message);
  }

  /** Forget the learned pieces (a different piece set, or a bad lesson). */
  forgetPieces() {
    this.templates = {};
    saveTemplates(this.templates);
  }

  /** Learns the pieces now on screen, given the position that's really there. */
  learnPosition(placement, whiteAtBottom) {
    if (!this.squares) return false;
    this.templates = learn(this.squares, placement, whiteAtBottom, this.templates);
    saveTemplates(this.templates);
    this.whiteAtBottom = whiteAtBottom;
    this.lastEmitted = null;
    return true;
  }

  frame() {
    const { videoWidth: w, videoHeight: h } = this.video;
    if (!w || !h) return null;
    const scale = Math.min(1, MAX_WIDTH / w);
    const width = Math.round(w * scale), height = Math.round(h * scale);
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
    this.ctx.drawImage(this.video, 0, 0, width, height);
    // Blank out ChessLens's own window so its board isn't read.
    const g = this.geometry;
    if (g) {
      const sx = width / g.display.width, sy = height / g.display.height;
      this.ctx.fillStyle = '#000';
      this.ctx.fillRect((g.window.x - g.display.x) * sx, (g.window.y - g.display.y) * sy, g.window.width * sx, g.window.height * sy);
    }
    return this.ctx.getImageData(0, 0, width, height);
  }

  tick() {
    if (this.busy) return;
    this.busy = true;
    try {
      this.read();
    } catch (error) {
      this.onStatus(`Screen reading failed: ${error.message}`);
    } finally {
      this.busy = false;
    }
  }

  /**
   * Where a board square is on the screen being read, in that screen's coordinates (for the
   * highlight layer), or null when reading a window instead of a screen.
   */
  squareOnScreen(square) {
    const g = this.geometry, grid = this.grid;
    if (!g || !grid || !this.canvas.width) return null;
    const file = square.charCodeAt(0) - 97, rank = Number(square[1]) - 1;
    const col = this.whiteAtBottom ? file : 7 - file;
    const row = this.whiteAtBottom ? 7 - rank : rank;
    const sx = g.display.width / this.canvas.width, sy = g.display.height / this.canvas.height;
    return { x: (grid.x + col * grid.cw) * sx, y: (grid.y + row * grid.ch) * sy, w: grid.cw * sx, h: grid.ch * sy };
  }

  read() {
    // The ChessLens window may have moved: refresh where it is every few seconds.
    this.ticks = (this.ticks ?? 0) + 1;
    if (window.chesslensDesktop && this.ticks % 6 === 0) {
      window.chesslensDesktop.geometry().then((g) => { if (this.running) this.geometry = g; });
    }
    const img = this.frame();
    if (!img) return;
    const grid = findBoard(img, this.grid);
    if (!grid) {
      if (this.grid) this.onStatus('Lost the board. Keep the whole board visible.');
      else this.onStatus('Looking for a chess board…');
      this.grid = null;
      this.onFrame?.();
      this.drawPreview(img, null);
      return;
    }
    this.grid = grid;
    this.onFrame?.();
    const squares = readSquares(img, grid);
    this.squares = squares;
    this.drawPreview(img, grid);

    // Any starting position on screen teaches (or refreshes) the piece set.
    const lesson = learnFromStart(squares);
    if (lesson) {
      const first = !this.knowsPieces;
      this.templates = lesson.templates;
      this.whiteAtBottom = lesson.whiteAtBottom;
      saveTemplates(this.templates);
      if (first) this.onStatus('Learned your pieces from the starting position.');
    }
    if (!this.knowsPieces) {
      this.onStatus('Board found. Show a starting position once (new game or analysis board) so I can learn your pieces, or use “Learn pieces”.');
      return;
    }

    const reading = recognize(squares, this.templates, this.whiteAtBottom);
    if (!reading) {
      this.pending = null;
      // The board may have shifted a little (scrolling, a banner): look for it afresh.
      this.unreadable = (this.unreadable ?? 0) + 1;
      if (this.unreadable >= 3) this.grid = null;
      this.onStatus('Board found, but a square is unclear (arrow, dragged piece…).');
      return;
    }
    this.unreadable = 0;
    this.whiteAtBottom = reading.whiteAtBottom;
    const key = placementFen(reading.placement) + (reading.lastMover ?? '-') + reading.whiteAtBottom;
    // Only report a position seen twice in a row, so moving pieces aren't read mid-animation.
    if (this.pending !== key) {
      this.pending = key;
      return;
    }
    if (key === this.lastEmitted) return;
    this.lastEmitted = key;
    this.onStatus('Reading the board.');
    this.onPosition({ ...reading, placementFen: placementFen(reading.placement) });
  }

  drawPreview(img, grid) {
    const canvas = this.preview;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.fillStyle = '#1f1d1a';
    ctx.fillRect(0, 0, w, h);
    if (!grid) {
      // Whole screen, small.
      const scale = Math.min(w / img.width, h / img.height);
      ctx.drawImage(this.canvas, 0, 0, img.width, img.height, (w - img.width * scale) / 2, (h - img.height * scale) / 2, img.width * scale, img.height * scale);
      return;
    }
    const size = Math.max(grid.cw, grid.ch) * 8;
    ctx.drawImage(this.canvas, grid.x, grid.y, grid.cw * 8, grid.ch * 8, 0, 0, w, h);
    ctx.strokeStyle = 'rgba(129, 182, 76, 0.9)';
    ctx.lineWidth = 1;
    for (let k = 0; k <= 8; k++) {
      ctx.beginPath(); ctx.moveTo((k * w) / 8, 0); ctx.lineTo((k * w) / 8, h); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, (k * h) / 8); ctx.lineTo(w, (k * h) / 8); ctx.stroke();
    }
    return size;
  }
}

function loadTemplates() {
  try {
    return deserializeTemplates(JSON.parse(localStorage.getItem('chesslens.pieces') ?? '{}'));
  } catch {
    return {};
  }
}

function saveTemplates(templates) {
  try {
    localStorage.setItem('chesslens.pieces', JSON.stringify(serializeTemplates(templates)));
  } catch {
    // Not stored: the pieces are learned again next time.
  }
}
