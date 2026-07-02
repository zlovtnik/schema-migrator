import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DriftPage } from "./DriftPage";
import { renderApp } from "../../test/render";

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("DriftPage", () => {
  let driftPayload: unknown;

  beforeEach(() => {
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
        return Promise.resolve(jsonResponse({}));
      })
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads drift results for the selected target", async () => {
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    expect(await screen.findByText("devices")).toBeInTheDocument();
    expect(screen.getAllByText("Missing actual").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Filter drift results")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Schema control summary" })).toBeInTheDocument();
    expect(screen.getByText("Control state")).toBeInTheDocument();
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
    expect(screen.queryByLabelText("Filter drift results")).not.toBeInTheDocument();
  });
});
