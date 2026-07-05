import { app, BrowserWindow, Menu, shell, type MenuItemConstructorOptions } from "electron";
import { createReadStream, promises as fs } from "node:fs";
import { createServer, type Server, type IncomingMessage, type ServerResponse } from "node:http";
import { extname, resolve, sep } from "node:path";

const staticHost = "127.0.0.1";
const configuredStaticPort = Number.parseInt(process.env.BEDROCK_ELECTRON_PORT || "4174", 10);
const staticPort = Number.isInteger(configuredStaticPort) ? configuredStaticPort : 4174;

let staticServer: Server | undefined;
let mainWindow: BrowserWindow | undefined;
let appOrigin = "";

const contentTypes: Record<string, string> = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
  ".webp": "image/webp"
};

const isSafeExternalUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return ["http:", "https:", "mailto:"].includes(url.protocol);
  } catch {
    return false;
  }
};

const sendFile = (response: ServerResponse, filePath: string): void => {
  const extension = extname(filePath).toLowerCase();
  response.writeHead(200, {
    "Content-Type": contentTypes[extension] || "application/octet-stream",
    "Cache-Control": extension === ".html" ? "no-store" : "public, max-age=31536000, immutable"
  });
  createReadStream(filePath).pipe(response);
};

const sendNotFound = (response: ServerResponse): void => {
  response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
  response.end("Not found");
};

const requestedFilePath = (root: string, request: IncomingMessage): string | undefined => {
  const requestUrl = new URL(request.url || "/", `http://${request.headers.host || `${staticHost}:${staticPort}`}`);
  const decodedPath = decodeURIComponent(requestUrl.pathname).replace(/^\/+/, "");
  const candidate = resolve(root, decodedPath);
  return candidate === root || candidate.startsWith(`${root}${sep}`) ? candidate : undefined;
};

const serveRequest = async (root: string, request: IncomingMessage, response: ServerResponse): Promise<void> => {
  try {
    const candidate = requestedFilePath(root, request);
    if (!candidate) {
      sendNotFound(response);
      return;
    }

    const indexPath = resolve(root, "index.html");
    const stat = await fs.stat(candidate).catch(() => undefined);
    if (stat?.isFile()) {
      sendFile(response, candidate);
      return;
    }

    if (extname(candidate)) {
      sendNotFound(response);
      return;
    }

    sendFile(response, indexPath);
  } catch {
    response.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Internal server error");
  }
};

const listen = (server: Server): Promise<void> =>
  new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(staticPort, staticHost, () => {
      server.off("error", reject);
      resolvePromise();
    });
  });

const startStaticServer = async (): Promise<string> => {
  const rendererDist = resolve(app.getAppPath(), "dist");
  const server = createServer((request, response) => {
    void serveRequest(rendererDist, request, response);
  });

  await listen(server);
  staticServer = server;
  return `http://${staticHost}:${staticPort}/`;
};

const appUrl = async (): Promise<string> => {
  const devServerUrl = process.env.VITE_DEV_SERVER_URL?.trim();
  if (devServerUrl) {
    return devServerUrl;
  }

  return startStaticServer();
};

const contextMenuFor = (window: BrowserWindow, params: Electron.ContextMenuParams): Menu | undefined => {
  const suggestions: MenuItemConstructorOptions[] = params.dictionarySuggestions.slice(0, 5).map((suggestion) => ({
    label: suggestion,
    click: () => {
      window.webContents.replaceMisspelling(suggestion);
    }
  }));

  const editItems: MenuItemConstructorOptions[] = [
    { role: "undo", enabled: params.editFlags.canUndo },
    { role: "redo", enabled: params.editFlags.canRedo },
    { type: "separator" },
    { role: "cut", enabled: params.editFlags.canCut },
    { role: "copy", enabled: params.editFlags.canCopy },
    { role: "paste", enabled: params.editFlags.canPaste },
    { role: "delete", enabled: params.editFlags.canDelete },
    { type: "separator" },
    { role: "selectAll", enabled: params.editFlags.canSelectAll }
  ];

  if (params.isEditable) {
    return Menu.buildFromTemplate([
      ...suggestions,
      ...(suggestions.length > 0 ? [{ type: "separator" } as MenuItemConstructorOptions] : []),
      ...editItems
    ]);
  }

  if (params.selectionText.trim()) {
    return Menu.buildFromTemplate([
      { role: "copy", enabled: params.editFlags.canCopy },
      { type: "separator" },
      { role: "selectAll", enabled: params.editFlags.canSelectAll }
    ]);
  }

  return undefined;
};

const installContextMenu = (window: BrowserWindow): void => {
  window.webContents.on("context-menu", (_event, params) => {
    const menu = contextMenuFor(window, params);
    menu?.popup({ window });
  });
};

const installApplicationMenu = (): void => {
  if (process.platform !== "darwin") {
    Menu.setApplicationMenu(null);
    return;
  }

  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      { role: "appMenu" },
      {
        label: "Edit",
        submenu: [
          { role: "undo" },
          { role: "redo" },
          { type: "separator" },
          { role: "cut" },
          { role: "copy" },
          { role: "paste" },
          { role: "delete" },
          { type: "separator" },
          { role: "selectAll" }
        ]
      },
      { role: "windowMenu" }
    ])
  );
};

const createWindow = async (): Promise<void> => {
  const url = await appUrl();
  appOrigin = new URL(url).origin;

  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 960,
    minHeight: 640,
    title: "Bedrock Schema Migrator",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: resolve(app.getAppPath(), "dist-electron/preload.js"),
      sandbox: true
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url: targetUrl }) => {
    if (isSafeExternalUrl(targetUrl)) {
      void shell.openExternal(targetUrl);
    }
    return { action: "deny" };
  });

  installContextMenu(mainWindow);

  mainWindow.webContents.on("will-navigate", (event, targetUrl) => {
    if (new URL(targetUrl).origin === appOrigin) {
      return;
    }

    event.preventDefault();
    if (isSafeExternalUrl(targetUrl)) {
      void shell.openExternal(targetUrl);
    }
  });

  await mainWindow.loadURL(url);
};

installApplicationMenu();

app.whenReady().then(() => {
  createWindow().catch((error: unknown) => {
    console.error(error);
    app.quit();
  });

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow().catch((error: unknown) => {
        console.error(error);
        app.quit();
      });
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});

app.on("before-quit", () => {
  staticServer?.close();
});
