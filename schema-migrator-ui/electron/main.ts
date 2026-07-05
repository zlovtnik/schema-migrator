import { app, BrowserWindow, Menu, session, shell, type MenuItemConstructorOptions } from "electron";
import { createReadStream, promises as fs } from "node:fs";
import { createServer, request as createHttpRequest, type Server, type IncomingMessage, type ServerResponse } from "node:http";
import { request as createHttpsRequest } from "node:https";
import { extname, resolve, sep } from "node:path";

const staticHost = "127.0.0.1";
const configuredStaticPort = Number.parseInt(process.env.BEDROCK_ELECTRON_PORT || "4174", 10);
const staticPort = Number.isInteger(configuredStaticPort) ? configuredStaticPort : 4174;

let staticServer: Server | undefined;
let staticServerUrl: string | undefined;
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

const sendServerError = (response: ServerResponse): void => {
  response.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
  response.end("Internal server error");
};

const sendFile = (response: ServerResponse, filePath: string): void => {
  const extension = extname(filePath).toLowerCase();
  const stream = createReadStream(filePath);

  stream.once("open", () => {
    response.writeHead(200, {
      "Content-Type": contentTypes[extension] || "application/octet-stream",
      "Cache-Control": extension === ".html" ? "no-store" : "public, max-age=31536000, immutable"
    });
    stream.pipe(response);
  });

  stream.once("error", (error: NodeJS.ErrnoException) => {
    if (response.headersSent || response.writableEnded) {
      response.destroy(error);
      return;
    }

    if (error.code === "ENOENT") {
      sendNotFound(response);
      return;
    }

    sendServerError(response);
  });
};

const sendNotFound = (response: ServerResponse): void => {
  response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
  response.end("Not found");
};

const apiProxyTarget = (): URL =>
  new URL(process.env.BEDROCK_API_PROXY_TARGET || process.env.VITE_API_PROXY_TARGET || "http://localhost");

const isApiRequest = (request: IncomingMessage): boolean => {
  const requestUrl = new URL(request.url || "/", `http://${request.headers.host || `${staticHost}:${staticPort}`}`);
  return requestUrl.pathname === "/api" || requestUrl.pathname.startsWith("/api/");
};

const proxyApiRequest = (request: IncomingMessage, response: ServerResponse): void => {
  const target = apiProxyTarget();
  const requestUrl = new URL(request.url || "/", `http://${request.headers.host || `${staticHost}:${staticPort}`}`);
  const proxyUrl = new URL(`${requestUrl.pathname}${requestUrl.search}`, target);
  const failProxyRequest = (): void => {
    if (response.headersSent || response.writableEnded) {
      response.destroy();
      return;
    }

    response.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Bad gateway");
  };
  const proxyRequest = (proxyUrl.protocol === "https:" ? createHttpsRequest : createHttpRequest)(
    proxyUrl,
    {
      method: request.method,
      headers: {
        ...request.headers,
        host: proxyUrl.host
      }
    },
    (proxyResponse) => {
      response.writeHead(proxyResponse.statusCode || 502, proxyResponse.headers);
      proxyResponse.pipe(response);
    }
  );

  proxyRequest.setTimeout(30_000, () => {
    proxyRequest.destroy(new Error("API proxy request timed out"));
  });
  proxyRequest.on("error", failProxyRequest);

  request.pipe(proxyRequest);
};

const requestedFilePath = (root: string, request: IncomingMessage): string | undefined => {
  const requestUrl = new URL(request.url || "/", `http://${request.headers.host || `${staticHost}:${staticPort}`}`);
  const decodedPath = decodeURIComponent(requestUrl.pathname).replace(/^\/+/, "");
  const candidate = resolve(root, decodedPath);
  return candidate === root || candidate.startsWith(`${root}${sep}`) ? candidate : undefined;
};

const serveRequest = async (root: string, request: IncomingMessage, response: ServerResponse): Promise<void> => {
  try {
    if (isApiRequest(request)) {
      proxyApiRequest(request, response);
      return;
    }

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
    sendServerError(response);
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
  if (staticServerUrl) {
    return staticServerUrl;
  }

  const rendererDist = resolve(app.getAppPath(), "dist");
  const server = createServer((request, response) => {
    void serveRequest(rendererDist, request, response);
  });

  await listen(server);
  staticServer = server;
  staticServerUrl = `http://${staticHost}:${staticPort}/`;
  return staticServerUrl;
};

const appUrl = async (): Promise<string> => {
  const devServerUrl = process.env.VITE_DEV_SERVER_URL?.trim();
  if (devServerUrl) {
    return devServerUrl;
  }

  return startStaticServer();
};

const devServerOrigin = (): string | undefined => {
  const devServerUrl = process.env.VITE_DEV_SERVER_URL?.trim();
  if (!devServerUrl) {
    return undefined;
  }

  try {
    return new URL(devServerUrl).origin;
  } catch {
    return undefined;
  }
};

const cspOrigins = (): Set<string> => {
  const origins = new Set([`http://${staticHost}:${staticPort}`]);
  const origin = devServerOrigin();
  if (origin) {
    origins.add(origin);
  }
  return origins;
};

const contentSecurityPolicyFor = (origin: string): string => {
  const isDevServer = origin === devServerOrigin();
  const scriptSource = isDevServer ? "script-src 'self' 'unsafe-inline'" : "script-src 'self'";
  const connectSource = isDevServer ? "connect-src 'self' http: https: ws: wss:" : "connect-src 'self'";

  return [
    "default-src 'none'",
    scriptSource,
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self'",
    "img-src 'self' data: blob:",
    connectSource,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
    "object-src 'none'"
  ].join("; ");
};

const installContentSecurityPolicy = (): void => {
  const origins = cspOrigins();

  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    let origin: string;
    try {
      origin = new URL(details.url).origin;
    } catch {
      callback({ responseHeaders: details.responseHeaders });
      return;
    }

    if (!origins.has(origin)) {
      callback({ responseHeaders: details.responseHeaders });
      return;
    }

    callback({
      responseHeaders: {
        ...details.responseHeaders,
        "Content-Security-Policy": [contentSecurityPolicyFor(origin)]
      }
    });
  });
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
      preload: resolve(app.getAppPath(), "dist-electron-preload/preload.js"),
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
  installContentSecurityPolicy();

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
  staticServer = undefined;
  staticServerUrl = undefined;
});
