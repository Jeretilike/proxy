// Lets the page list the screens and windows it can read, and choose one. Nothing else.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('chesslensDesktop', {
  listSources: () => ipcRenderer.invoke('screen-sources'),
  chooseSource: (id) => ipcRenderer.invoke('choose-screen-source', id),
  geometry: () => ipcRenderer.invoke('screen-geometry'),
  highlight: (payload) => ipcRenderer.invoke('overlay', payload),
});
