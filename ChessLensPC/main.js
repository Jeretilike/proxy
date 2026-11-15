// ChessLens for PC: the desktop window around the web app in web/.
//
// Pages are served from a private app:// address with cross-origin isolation headers, which lets
// the full-strength multi-threaded Stockfish (WebAssembly with shared memory) run.
import { app, BrowserWindow, Menu, net, protocol, shell } from 'electron';
import { extname, join, normalize, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, 'web');
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
};

protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } },
]);

if (!app.requestSingleInstanceLock()) app.quit();

let window = null;

function createWindow() {
  window = new BrowserWindow({
    width: 1240,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#1f1d1a',
    title: 'ChessLens',
    icon: join(here, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, sandbox: true },
  });
  Menu.setApplicationMenu(null);
  // Links (none today) open in the normal browser, never inside the app.
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) shell.openExternal(url);
    return { action: 'deny' };
  });
  window.webContents.on('before-input-event', (event, input) => {
    if (input.type === 'keyDown' && (input.key === 'F12' || (input.control && input.shift && input.key === 'I'))) {
      window.webContents.toggleDevTools();
    }
  });
  window.loadURL('app://chesslens/index.html');
}

app.on('second-instance', () => {
  if (window) {
    if (window.isMinimized()) window.restore();
    window.focus();
  }
});

app.whenReady().then(() => {
  protocol.handle('app', async (request) => {
    let path = decodeURIComponent(new URL(request.url).pathname);
    if (path === '/' || path === '') path = '/index.html';
    const file = normalize(join(web, path));
    if (!file.startsWith(web)) return new Response('Forbidden', { status: 403 });
    const response = await net.fetch(pathToFileURL(file).toString());
    const headers = new Headers();
    headers.set('Content-Type', TYPES[extname(file)] ?? 'application/octet-stream');
    headers.set('Cross-Origin-Opener-Policy', 'same-origin');
    headers.set('Cross-Origin-Embedder-Policy', 'require-corp');
    headers.set('Cross-Origin-Resource-Policy', 'same-origin');
    return new Response(response.body, { status: response.status, headers });
  });
  createWindow();
});

app.on('window-all-closed', () => app.quit());
