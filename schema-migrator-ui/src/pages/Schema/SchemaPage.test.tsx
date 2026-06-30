import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import axe from "axe-core";
import { SchemaPage } from "./SchemaPage";
import { renderApp } from "../../test/render";

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("SchemaPage", () => {
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
        if (url.includes("/schema")) {
          return Promise.resolve(
            jsonResponse({
              target_id: "target-1",
              db_kind: "postgres",
              supported: true,
              checked_at: "2026-06-28T12:00:00Z",
              warnings: [],
              objects: [
                {
                  schema: "public",
                  name: "devices",
                  object_type: "table",
                  status: "defined",
                  source_file: "tables/001_devices.sql",
                  checksum: "abc",
                  expected_ddl: "create table if not exists public.devices (id bigint primary key);",
                  last_checked: "2026-06-28T12:00:00Z"
                }
              ]
            })
          );
        }
        throw new Error(`Unexpected fetch request: ${url}`);
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads schema objects for the selected target", async () => {
    renderApp(<SchemaPage />, { route: "/schema?target=target-1" });

    expect((await screen.findAllByText("devices")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Defined").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Filter schema objects")).toBeInTheDocument();
  });

  it("has no axe violations for the loaded catalog surface", async () => {
    const { container } = renderApp(<SchemaPage />, { route: "/schema?target=target-1" });

    await screen.findAllByText("devices");
    const results = await axe.run(container, {
      rules: {
        "color-contrast": { enabled: false }
      }
    });
    await waitFor(() => expect(results.violations).toEqual([]));
  });
});
