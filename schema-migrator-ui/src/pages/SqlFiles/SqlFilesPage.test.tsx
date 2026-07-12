import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SqlFilesPage from "./SqlFilesPage";
import { setAuthToken } from "../../api/client";
import { tokenWithRole } from "../../test/authToken";
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

const target = {
  id: "target-1",
  label: "Local",
  app_name: "app",
  env: "dev",
  jdbc_url: "jdbc:postgresql://localhost:5432/app?user=app",
  created_at: "2026-06-28T12:00:00Z",
  repo_url: "https://github.com/example/schema.git",
  repo_branch: "main",
  repo_sql_path: "sql",
  last_synced_commit: null,
  last_synced_at: null
};

describe("SqlFilesPage", () => {
  beforeEach(() => {
    setAuthToken(tokenWithRole("operator"));
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" || input instanceof URL ? String(input) : input.url;
        const method = init?.method ?? (input instanceof Request ? input.method : "GET");
        if (url.endsWith("/targets")) {
          return Promise.resolve(jsonResponse({ targets: [target] }));
        }
        if (url.endsWith("/targets/target-1")) {
          return Promise.resolve(jsonResponse(target));
        }
        if (url.endsWith("/targets/target-1/repo-sync/status")) {
          return Promise.resolve(
            jsonResponse({
              target_id: "target-1",
              repo_url: target.repo_url,
              repo_branch: "main",
              repo_sql_path: "sql",
              last_synced_commit: "abc123def456",
              last_synced_at: "2026-06-28T12:00:00Z",
              remote_head_commit: "abc123def456",
              drift: false
            })
          );
        }
        if (url.endsWith("/targets/target-1/repo-sync") && method === "POST") {
          return Promise.resolve(
            jsonResponse({
              added: 1,
              removed: 0,
              changed: 0,
              unchanged: 2,
              commit_sha: "abc123def4567890",
              synced_at: "2026-06-28T12:00:00Z"
            })
          );
        }
        if (url.endsWith("/snapshots") && method === "POST") {
          return Promise.resolve(
            jsonResponse({
              id: "snapshot-1",
              target_id: "target-1",
              label: "before",
              created_at: "2026-06-28T12:01:00Z",
              created_by: "operator",
              file_count: 3
            })
          );
        }
        if (url.includes("/sql-files/status?target_id=target-1")) {
          return Promise.resolve(
            jsonResponse({
              loaded: true,
              file_count: sqlFiles.length,
              folders: ["tables", "views", "oracle/tables"]
            })
          );
        }
        if (url.includes("/sql-files?target_id=target-1")) {
          return Promise.resolve(jsonResponse({ files: sqlFiles }));
        }
        throw new Error(`Unexpected fetch request: ${url}`);
      })
    );
  });

  afterEach(() => {
    setAuthToken("");
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders collapsed folder groups with filtering and expansion controls", async () => {
    const user = userEvent.setup();
    renderApp(<SqlFilesPage />, { route: "/sql-files?target=target-1" });

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
    renderApp(<SqlFilesPage />, { route: "/sql-files?target=target-1" });

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
    renderApp(<SqlFilesPage />, { route: "/sql-files?target=target-1" });

    await screen.findByText("tables");
    await user.click(screen.getByRole("button", { name: /expand all/i }));
    await user.click(screen.getByRole("button", { name: /copy sha-256 hash for 001_devices\.sql/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(devicesFile.sha256));
    expect(await screen.findByText("Hash copied for 001_devices.sql")).toBeInTheDocument();
  });

  it("triggers repository sync from the keyboard", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);
    renderApp(<SqlFilesPage />, { route: "/sql-files?target=target-1" });

    const sync = await screen.findByRole("button", { name: /sync now/i });
    sync.focus();
    await user.keyboard("{Enter}");

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/targets/target-1/repo-sync"),
        expect.objectContaining({ method: "POST" })
      )
    );
    expect(await screen.findByText(/synced commit abc123def456/i)).toBeInTheDocument();
  });

  it("creates a snapshot for the selected target", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);
    renderApp(<SqlFilesPage />, { route: "/sql-files?target=target-1" });

    await user.click(await screen.findByRole("button", { name: /create snapshot/i }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/snapshots"),
        expect.objectContaining({
          method: "POST",
          body: JSON.stringify({ target_id: "target-1" })
        })
      )
    );
    expect(await screen.findByText("Created snapshot before")).toBeInTheDocument();
  });
});
