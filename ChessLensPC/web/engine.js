// Stockfish 18 (WebAssembly) in a Web Worker, spoken to over UCI.
//
// The full-strength multi-threaded build needs SharedArrayBuffer, which the browser only allows
// on cross-origin-isolated pages; the desktop app serves its pages with the right headers. If
// that isn't available, the small single-threaded build is used instead.

export class Engine {
  constructor() {
    this.worker = null;
    this.listeners = new Set();
    this.ready = null;
    this.busy = Promise.resolve();
    this.current = null;
    this.multiThreaded = typeof SharedArrayBuffer !== 'undefined' && self.crossOriginIsolated === true;
    this.name = this.multiThreaded ? 'Stockfish 18' : 'Stockfish 18 (lite)';
  }

  /** Starts the engine once; later calls return the same promise. */
  start() {
    if (this.ready) return this.ready;
    this.ready = (async () => {
      const file = this.multiThreaded ? 'vendor/stockfish-18.js' : 'vendor/stockfish-18-lite-single.js';
      this.worker = new Worker(new URL(file, import.meta.url));
      this.worker.onmessage = (event) => {
        const line = typeof event.data === 'string' ? event.data : String(event.data);
        for (const listener of [...this.listeners]) listener(line);
      };
      this.worker.onerror = (event) => {
        for (const listener of [...this.listeners]) listener(`error ${event.message || 'worker failed'}`);
      };
      this.send('uci');
      await this.waitFor('uciok', 60000);
      if (this.multiThreaded) {
        const threads = Math.max(1, Math.min(8, (navigator.hardwareConcurrency || 2) - 1));
        this.send(`setoption name Threads value ${threads}`);
        this.send('setoption name Hash value 128');
      } else {
        this.send('setoption name Hash value 32');
      }
      this.send('isready');
      await this.waitFor('readyok', 60000);
    })();
    this.ready.catch(() => { this.ready = null; });
    return this.ready;
  }

  send(command) {
    this.worker.postMessage(command);
  }

  waitFor(prefix, timeout) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.listeners.delete(listener);
        reject(new Error('Stockfish did not start. Restart the app to try again.'));
      }, timeout);
      const listener = (line) => {
        if (line.startsWith('error ')) {
          clearTimeout(timer);
          this.listeners.delete(listener);
          reject(new Error(`Stockfish failed: ${line.slice(6)}`));
        } else if (line.startsWith(prefix)) {
          clearTimeout(timer);
          this.listeners.delete(listener);
          resolve(line);
        }
      };
      this.listeners.add(listener);
    });
  }

  /** Stops the analysis in progress, if any (its promise resolves with what it had). */
  stop() {
    if (this.current) this.send('stop');
  }

  /**
   * Analyses `fen` for `movetime` ms and returns up to `multipv` lines, best first:
   * { move, cp, mate, depth, pv } with scores from the side to move's point of view.
   * Calls are queued; `onUpdate(lines)` is called as the search deepens.
   */
  analyze(fen, { movetime, multipv = 1, onUpdate } = {}) {
    const run = this.busy.then(() => this.run(fen, movetime, multipv, onUpdate));
    this.busy = run.catch(() => {});
    return run;
  }

  async run(fen, movetime, multipv, onUpdate) {
    await this.start();
    return new Promise((resolve, reject) => {
      const lines = new Map();
      const finish = (fn) => {
        clearTimeout(timer);
        this.listeners.delete(listener);
        this.current = null;
        fn();
      };
      const timer = setTimeout(() => {
        this.send('stop');
        finish(() => reject(new Error('Stockfish did not answer in time.')));
      }, movetime + 20000);
      const listener = (line) => {
        if (line.startsWith('info ') && line.includes(' pv ') && !line.includes('bound')) {
          const parsed = parseInfo(line);
          if (parsed) {
            lines.set(parsed.multipv, parsed);
            onUpdate?.(sorted(lines));
          }
        } else if (line.startsWith('bestmove')) {
          const best = line.split(' ')[1];
          finish(() => {
            if (!best || best === '(none)' || best === '0000') {
              resolve([]);
              return;
            }
            const result = sorted(lines);
            // The search can stop before reporting a score for the move it settled on.
            if (!result.length || result[0].move !== best) {
              const known = result.find((l) => l.move === best);
              if (known) result.splice(result.indexOf(known), 1);
              result.unshift(known ?? { move: best, cp: 0, mate: null, depth: 0, pv: [best], multipv: 1 });
            }
            resolve(result);
          });
        } else if (line.startsWith('error ')) {
          finish(() => reject(new Error(`Stockfish failed: ${line.slice(6)}`)));
        }
      };
      this.listeners.add(listener);
      this.current = fen;
      this.send(`setoption name MultiPV value ${multipv}`);
      this.send(`position fen ${fen}`);
      this.send(`go movetime ${movetime}`);
    });
  }
}

function sorted(map) {
  return [...map.values()].sort((a, b) => a.multipv - b.multipv);
}

export function parseInfo(line) {
  const tokens = line.trim().split(/\s+/);
  const result = { multipv: 1, depth: 0, cp: null, mate: null, pv: [] };
  for (let i = 1; i < tokens.length; i++) {
    switch (tokens[i]) {
      case 'depth': result.depth = Number(tokens[++i]); break;
      case 'multipv': result.multipv = Number(tokens[++i]); break;
      case 'score': {
        const kind = tokens[++i];
        const value = Number(tokens[++i]);
        if (kind === 'cp') result.cp = value;
        else if (kind === 'mate') result.mate = value;
        break;
      }
      case 'pv': result.pv = tokens.slice(i + 1); i = tokens.length; break;
      default: break;
    }
  }
  if (!result.pv.length) return null;
  result.move = result.pv[0];
  return result;
}
