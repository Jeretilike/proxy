// The on-screen highlight layer only receives what to draw.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('overlay', {
  onDraw: (callback) => ipcRenderer.on('overlay-draw', (_event, payload) => callback(payload)),
});
