import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { DriftPage } from "./DriftPage";
import { renderApp } from "../../test/render";

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("DriftPage", () => {
  beforeEach(() => {
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
          return Promise.resolve(
            jsonResponse({
              target_id: "target-1",
              db_kind: "postgres",
              supported: true,
              checked_at: "2026-06-28T12:00:00Z",
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
                  detected_at: "2026-06-28T12:00:00Z"
                }
              ]
            })
          );
        }
        return Promise.resolve(jsonResponse({}));
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads drift results for the selected target", async () => {
    renderApp(<DriftPage />, { route: "/drift?target=target-1" });

    expect(await screen.findByText("devices")).toBeInTheDocument();
    expect(screen.getAllByText("Missing actual").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Filter drift results")).toBeInTheDocument();
  });
});
