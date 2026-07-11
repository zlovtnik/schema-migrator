import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DriftPage } from "./DriftPage";
import { setAuthToken } from "../../api/client";
import { tokenWithRole } from "../../test/authToken";
import { renderApp } from "../../test/render";

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("DriftPage", () => {
  let driftPayload: unknown;

  beforeEach(() => {
    setAuthToken(tokenWithRole("operator"));
    driftPayload = {
      target_id: "target-1",
      db_kind: "postgres",
      supported: true,
      checked_at: "2026-06-28T12:00:00Z",
      control_summary: {
        total_count: 3,
        applied_count: 2,
        skipped_count: 1,
        pending_count: 0,
        failed_count: 0,
        ready: true,
        failed_objects: [],
        last_applied_at: "2026-06-28T11:30:00Z",
        last_updated_at: "2026-06-28T11:45:00Z"
      },
      warnings: [],
      items: [
        {
          schema: "public",
          name: "devices",
          object_type: "table",
          drift_type: "missing_actual",
          expected: "tables/001_devices.sql",
          actual: "not present in live Postgres catalog",
          source_file: "tables/001_devices.sql",
          checksum: "abc",
          apply_status: "applied",
          detected_at: "2026-06-28T12:00:00Z"
        }
      ]
    };

    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/drift/runs")) {
          return Promise.resolve(
            jsonResponse({
              id: "run-1",
              target_id: "target-1",
              patch_id: "patch-1",
              status: "pending",
              scripts: [],
              started_at: "2026-06-28T12:01:00Z",
              triggered_by: "operator"
            })
          );
        }
        if (url.includes("/targets")) {
          return Promise.resolve(
            jsonResponse({
              targets: [
                {
                  id: "target-1",
                  label: "Local",
                  app_name: "app",
                  env: "dev",
                  jdbc_url: "jdbc:postgresql://localhost/app",
                  created_at: "2026-06-28T12:00:00Z"
                }
              ]
            })
          );
        }
        if (url.includes("/drift")) {
          return Promise.resolve(jsonResponse(driftPayload));
        }
        if (url.includes("/runs")) {
          return Promise.resolve(jsonResponse({ runs: [] }));
        }
        return Promise.resolve(jsonResponse({}));
      })
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    setAuthToken("");
  });

  it("loads drift results for the selected target", async () => {
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    expect(await screen.findByRole("heading", { name: "Schema drift" })).toBeInTheDocument();
    expect(await screen.findByText("devices")).toBeInTheDocument();
    expect(screen.getAllByText("Missing actual").length).toBeGreaterThan(0);
    expect(screen.queryByText("Drift detection")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Filter results")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Schema control summary" })).toBeInTheDocument();
    expect(screen.getByText("Control state")).toBeInTheDocument();
    expect(screen.getByText("Last checked")).toBeInTheDocument();
    expect(screen.getByText("Drift detected")).toBeInTheDocument();
    expect(screen.getByText("1 object")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Run executable drift" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Run" })).toBeInTheDocument();
  });

  it("keeps run gating banners hidden until a target is selected", async () => {
    vi.mocked(fetch).mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/targets")) {
        return Promise.resolve(
          jsonResponse({
            targets: [
              {
                id: "target-1",
                label: "Local",
                app_name: "app",
                env: "dev",
                jdbc_url: "jdbc:postgresql://localhost/app",
                created_at: "2026-06-28T12:00:00Z"
              }
            ]
          })
        );
      }
      if (url.includes("/runs")) {
        return Promise.resolve(
          jsonResponse({
            runs: [
              {
                id: "run-global",
                target_id: "other-target",
                patch_id: "patch-1",
                status: "running",
                scripts: [],
                started_at: "2026-06-28T12:01:00Z",
                triggered_by: "operator"
              }
            ]
          })
        );
      }
      return Promise.resolve(jsonResponse({}));
    });

    renderApp(<DriftPage />, { route: "/drift" });

    expect(await screen.findByText("Select a target")).toBeInTheDocument();
    expect(screen.queryByText("Drift execution is disabled while this target has an active run.")).not.toBeInTheDocument();
  });

  it("starts all executable drift from the page action", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    await user.click(await screen.findByRole("button", { name: "Run executable drift" }));

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/drift/runs"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ target_id: "target-1" })
      })
    );
  });

  it("starts drift execution for a single source file from a row", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    await user.click(await screen.findByRole("button", { name: "Run" }));

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/drift/runs"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ target_id: "target-1", source_files: ["tables/001_devices.sql"] })
      })
    );
  });

  it("counts drift type chips after applying the text filter", async () => {
    const user = userEvent.setup();
    driftPayload = {
      ...(driftPayload as Record<string, unknown>),
      items: [
        {
          schema: "public",
          name: "devices",
          object_type: "table",
          drift_type: "missing_actual",
          expected: "tables/001_devices.sql",
          actual: "not present in live Postgres catalog",
          source_file: "tables/001_devices.sql",
          checksum: "abc",
          apply_status: "applied",
          detected_at: "2026-06-28T12:00:00Z"
        },
        {
          schema: "public",
          name: "accounts",
          object_type: "view",
          drift_type: "definition_changed",
          expected: "create view accounts as select 1;",
          actual: "create view accounts as select 2;",
          source_file: "views/001_accounts.sql",
          checksum: "def",
          apply_status: "applied",
          detected_at: "2026-06-28T12:00:00Z"
        }
      ]
    };

    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    await screen.findByText("devices");
    expect(screen.getByRole("button", { name: /all\s+2/i })).toBeInTheDocument();

    await user.type(screen.getByLabelText("Filter results"), "devices");

    expect(screen.getByRole("button", { name: /all\s+1/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /missing actual\s+1/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /definition changed/i })).not.toBeInTheDocument();
  });

  it("renders long object names without truncating the table text", async () => {
    const longName = "process_ingest_ledger(text[], text[], integer, integer, integer)";
    driftPayload = {
      ...(driftPayload as Record<string, unknown>),
      items: [
        {
          schema: "coordinator",
          name: longName,
          object_type: "function",
          drift_type: "definition_changed",
          expected: "create function coordinator.process_ingest_ledger() returns integer language sql as $$ select 1 $$;",
          actual: "create function coordinator.process_ingest_ledger() returns integer language sql as $$ select 2 $$;",
          source_file: "functions/023_coordinator_process_ingest_ledger.sql",
          checksum: "long",
          apply_status: "skipped",
          detected_at: "2026-06-28T12:00:00Z"
        }
      ]
    };

    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    const objectButton = await screen.findByRole("button", { name: longName });

    expect(objectButton).toHaveAttribute("title", longName);
    expect(objectButton).toHaveTextContent(longName);
  });

  it("opens a lazy-rendered drift detail from the table", async () => {
    const user = userEvent.setup();
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    const objectButton = await screen.findByRole("button", { name: "devices" });
    expect(screen.queryByRole("region", { name: "Actual" })).not.toBeInTheDocument();

    await user.click(objectButton);

    expect(await screen.findByRole("region", { name: "Actual" })).toBeInTheDocument();
    expect(await screen.findByText(/schema_control status/i)).toBeInTheDocument();
    expect(screen.getByText("applied")).toBeInTheDocument();
    expect(objectButton.closest("tr")).toHaveAttribute("data-selected", "true");
  });

  it("shows schema control summary when no drift is returned", async () => {
    driftPayload = {
      target_id: "target-1",
      db_kind: "postgres",
      supported: true,
      checked_at: "2026-06-28T12:00:00Z",
      control_summary: {
        total_count: 2,
        applied_count: 2,
        skipped_count: 0,
        pending_count: 0,
        failed_count: 0,
        ready: true,
        failed_objects: [],
        last_applied_at: "2026-06-28T11:30:00Z",
        last_updated_at: "2026-06-28T11:45:00Z"
      },
      warnings: [],
      items: []
    };

    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    expect(await screen.findByText("No drift detected")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Schema control summary" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Filter results")).not.toBeInTheDocument();
  });
});
