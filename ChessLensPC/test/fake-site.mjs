// Draws a fake chess website with a board on it, for the screen-reading tests.

export async function drawSite(site, fen, { theme, style, size, left, top, whiteAtBottom, last, selected = null }) {
  await site.evaluate(({ fen, theme, style, size, left, top, whiteAtBottom, last, selected }) => {
    const filled = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
    const outline = { k: '♔', q: '♕', r: '♖', b: '♗', n: '♘', p: '♙' };
    document.body.innerHTML = '';
    document.body.style.cssText = `margin:0;background:${theme.page};overflow:hidden;font-family:sans-serif`;
    const header = document.createElement('div');
    header.style.cssText = 'height:48px;background:#262421;color:#ccc;padding:12px;font-size:18px';
    header.textContent = 'Play Chess Online · Puzzles · Learn · Watch · News · Social    1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9';
    document.body.append(header);
    const side = document.createElement('div');
    side.style.cssText = `position:absolute;left:${left + size + 30}px;top:${top}px;width:380px;color:#ddd;font-size:15px;line-height:1.6`;
    side.innerHTML = '1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7<br>'.repeat(8);
    document.body.append(side);
    const board = document.createElement('div');
    board.style.cssText = `position:absolute;left:${left}px;top:${top}px;width:${size}px;height:${size}px;display:grid;grid-template-columns:repeat(8,1fr);grid-template-rows:repeat(8,1fr)`;
    const rows = fen.split(' ')[0].split('/');
    const grid = rows.map((r) => r.replace(/\d/g, (n) => '.'.repeat(Number(n))).split(''));
    for (let row = 0; row < 8; row++) {
      for (let col = 0; col < 8; col++) {
        const rank = whiteAtBottom ? 7 - row : row;
        const file = whiteAtBottom ? col : 7 - col;
        const sq = 'abcdefgh'[file] + (rank + 1);
        const cell = document.createElement('div');
        const isLight = (row + col) % 2 === 0;
        cell.style.cssText = `position:relative;overflow:hidden;min-width:0;min-height:0;background:${isLight ? theme.light : theme.dark};display:flex;align-items:center;justify-content:center`;
        if ((last && (last.from === sq || last.to === sq)) || sq === selected) {
          const hl = document.createElement('div');
          hl.style.cssText = 'position:absolute;inset:0;background:rgba(255,255,51,0.5)';
          cell.append(hl);
        }
        if (col === 0) {
          const n = document.createElement('span');
          n.textContent = rank + 1;
          n.style.cssText = `position:absolute;left:3px;top:1px;font-size:${size / 50}px;font-weight:700;color:${isLight ? theme.dark : theme.light}`;
          cell.append(n);
        }
        if (row === 7) {
          const f = document.createElement('span');
          f.textContent = 'abcdefgh'[file];
          f.style.cssText = `position:absolute;right:3px;bottom:1px;font-size:${size / 50}px;font-weight:700;color:${isLight ? theme.dark : theme.light}`;
          cell.append(f);
        }
        const ch = grid[7 - rank][file];
        if (ch !== '.') {
          const white = ch === ch.toUpperCase();
          const t = ch.toLowerCase();
          const p = document.createElement('span');
          p.style.cssText = `position:relative;font-size:${size / 8 * 0.78}px;line-height:1;font-family:'DejaVu Sans'`;
          if (style === 'filled') {
            p.textContent = filled[t];
            p.style.color = white ? '#fff' : '#222';
            p.style.textShadow = white ? '0 0 1px #000,0 0 1px #000,0 0 2px #000' : '0 0 1px #888';
          } else {
            p.textContent = white ? outline[t] : filled[t];
            p.style.color = '#111';
          }
          cell.append(p);
        }
        board.append(cell);
      }
    }
    document.body.append(board);
  }, { fen, theme, style, size, left, top, whiteAtBottom, last, selected });
}

