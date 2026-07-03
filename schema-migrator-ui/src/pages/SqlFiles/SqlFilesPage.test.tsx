import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SqlFilesPage, { ZipWriter } from "./SqlFilesPage";
import { renderApp } from "../../test/render";
import type { SqlFileEntry } from "../../api/sqlFiles";

const devicesFile: SqlFileEntry = {
  path: "tables/001_devices.sql",
  folder: "tables",
  filename: "001_devices.sql",
  sha256: "aaaaaaaa11111111bbbbbbbb22222222cccccccc33333333dddddddd44444444",
  content_base64: "",
  uploaded_at: "2026-06-28T12:00:00Z"
};

const sqlFiles: SqlFileEntry[] = [
  devicesFile,
  {
    path: "views/010_devices_view.sql",
    folder: "views",
    filename: "010_devices_view.sql",
    sha256: "bbbbbbbb11111111cccccccc22222222dddddddd33333333eeeeeeee44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  },
  {
    path: "oracle/tables/001_accounts.sql",
    folder: "oracle/tables",
    filename: "001_accounts.sql",
    sha256: "cccccccc11111111dddddddd22222222eeeeeeee33333333ffffffff44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  }
];

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

const findSignature = (bytes: Uint8Array, signature: number[]): number => {
  for (let i = 0; i <= bytes.length - signature.length; i++) {
    if (signature.every((byte, offset) => bytes[i + offset] === byte)) return i;
  }
  throw new Error(`ZIP signature not found: ${signature.map((byte) => byte.toString(16)).join(" ")}`);
};

const viewOf = (bytes: Uint8Array): DataView =>
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

describe("SqlFilesPage", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/sql-files/status")) {
          return Promise.resolve(
            jsonResponse({
              loaded: true,
              file_count: sqlFiles.length,
              folders: ["tables", "views", "oracle/tables"]
            })
          );
        }
        if (url.endsWith("/sql-files")) {
          return Promise.resolve(jsonResponse({ files: sqlFiles }));
        }
        throw new Error(`Unexpected fetch request: ${url}`);
      })
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders collapsed folder groups with filtering and expansion controls", async () => {
    const user = userEvent.setup();
    renderApp(<SqlFilesPage />);

    expect(await screen.findByText("tables")).toBeInTheDocument();
    expect(screen.getByLabelText("Filter SQL files")).toBeInTheDocument();
    expect(screen.queryByText("001_devices.sql")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /expand all/i }));
    expect(screen.getByText("001_devices.sql")).toBeInTheDocument();
    expect(screen.getByText("010_devices_view.sql")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /collapse all/i }));
    expect(screen.queryByText("001_devices.sql")).not.toBeInTheDocument();
  });

  it("expands matching folders when the file filter is used", async () => {
    const user = userEvent.setup();
    renderApp(<SqlFilesPage />);

    await screen.findByText("tables");
    await user.type(screen.getByLabelText("Filter SQL files"), "view");

    expect(await screen.findByText("010_devices_view.sql")).toBeInTheDocument();
    expect(screen.queryByText("001_devices.sql")).not.toBeInTheDocument();
    expect(screen.getByText("1 of 3 files")).toBeInTheDocument();
  });

  it("copies the full SHA-256 hash from a hash chip", async () => {
    const clipboard = navigator.clipboard;
    if (!clipboard) throw new Error("Clipboard API unavailable in test environment");
    const writeText = vi.spyOn(clipboard, "writeText").mockResolvedValue(undefined);

    const user = userEvent.setup();
    renderApp(<SqlFilesPage />);

    await screen.findByText("tables");
    await user.click(screen.getByRole("button", { name: /expand all/i }));
    await user.click(screen.getByRole("button", { name: /copy sha-256 hash for 001_devices\.sql/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(devicesFile.sha256));
    expect(await screen.findByText("Hash copied for 001_devices.sql")).toBeInTheDocument();
  });

  it("activates the SQL directory picker from the keyboard", async () => {
    const user = userEvent.setup();
    const inputClick = vi.spyOn(HTMLInputElement.prototype, "click").mockImplementation(() => undefined);
    renderApp(<SqlFilesPage />);

    const chooser = await screen.findByRole("button", { name: /choose sql directory/i });
    chooser.focus();
    await user.keyboard("{Enter}");

    expect(inputClick).toHaveBeenCalled();
  });
});

describe("ZipWriter", () => {
  it("writes stored entries in local and central directory headers", async () => {
    const payload = new TextEncoder().encode("select 1;\n");
    const writer = new ZipWriter();
    writer.addFile("tables/001.sql", payload);

    const bytes = writer.toBytes();
    const view = viewOf(bytes);
    const localOffset = findSignature(bytes, [0x50, 0x4b, 0x03, 0x04]);
    const centralOffset = findSignature(bytes, [0x50, 0x4b, 0x01, 0x02]);
    const eocdOffset = findSignature(bytes, [0x50, 0x4b, 0x05, 0x06]);

    expect(view.getUint16(localOffset + 8, true)).toBe(0);
    expect(view.getUint16(centralOffset + 10, true)).toBe(0);
    expect(view.getUint32(localOffset + 18, true)).toBe(payload.length);
    expect(view.getUint32(centralOffset + 20, true)).toBe(payload.length);
    expect(view.getUint32(eocdOffset + 12, true)).toBe(eocdOffset - centralOffset);
  });
});
