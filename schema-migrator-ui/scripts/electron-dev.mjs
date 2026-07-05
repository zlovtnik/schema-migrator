import { spawn } from "node:child_process";
import http from "node:http";
import { delimiter, join } from "node:path";

const devServerUrl = process.env.VITE_DEV_SERVER_URL || "http://127.0.0.1:5173";
const binDir = join(process.cwd(), "node_modules", ".bin");
const env = {
  ...process.env,
  PATH: `${binDir}${delimiter}${process.env.PATH || ""}`,
  VITE_DEV_SERVER_URL: devServerUrl
};

const commandName = (name) => (process.platform === "win32" ? `${name}.cmd` : name);
const spawnOptions = { env, shell: process.platform === "win32", stdio: "inherit" };

let vite;
let electron;

const shutdown = () => {
  vite?.kill();
  electron?.kill();
};

const spawnChild = (label, command, args) => {
  const child = spawn(commandName(command), args, spawnOptions);
  child.on("error", (error) => {
    console.error(`Failed to start ${label}: ${error.message}`);
    shutdown();
    process.exit(1);
  });
  return child;
};

const waitForUrl = (url, attempts = 120) =>
  new Promise((resolvePromise, reject) => {
    let remaining = attempts;

    const check = () => {
      const request = http.get(url, (response) => {
        response.resume();
        resolvePromise();
      });

      request.on("error", () => {
        remaining -= 1;
        if (remaining <= 0) {
          reject(new Error(`Timed out waiting for ${url}`));
        } else {
          setTimeout(check, 500);
        }
      });
    };

    check();
  });

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

try {
  vite = spawnChild("Vite dev server", "bun", ["run", "dev"]);
  await waitForUrl(devServerUrl);
  electron = spawnChild("Electron", "electron", ["dist-electron/main.js"]);

  electron.on("exit", (code) => {
    shutdown();
    process.exit(code ?? 0);
  });
} catch (error) {
  shutdown();
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
}
